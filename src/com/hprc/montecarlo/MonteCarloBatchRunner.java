package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.extension.SimulationExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Modifier;


/**
 * Runs Monte Carlo simulations by cloning the base simulation N times and running them in-process.
 *
 * IMPORTANT:
 * - This class is intended to be used by SimulationEngine (the Simulation Extension "Run" button flow).
 * - Each run gets a fresh Simulation(doc, rocket) instance and fresh cloned extensions.
 * - Wind settings are deep-copied to avoid shared references that would mutate the base simulation.
 */
public final class MonteCarloBatchRunner {

    private MonteCarloBatchRunner() { }

    private static final Logger log = LoggerFactory.getLogger(MonteCarloBatchRunner.class);

    public interface ProgressCallback {
        void onProgress(int completed, int total);
    }

    public interface RecordConsumer {
        void onRecord(MonteCarloRunRecord record);
    }

    public static List<MonteCarloRunRecord> runBatch(
            Simulation baseSimulation,
            int runs,
            ProgressCallback cb
    ) throws Exception {
        return runBatchParallel(baseSimulation, runs, 1, cb);
    }

    public static List<MonteCarloRunRecord> runBatchParallel(
            Simulation baseSimulation,
            int runs,
            int threads,
            ProgressCallback cb
    ) throws Exception {
        List<MonteCarloRunRecord> out = Collections.synchronizedList(new ArrayList<>());
        runBatchParallelStreaming(baseSimulation, runs, threads, cb, out::add);
        return out;
    }

    public static void runBatchParallelStreaming(
            Simulation baseSimulation,
            int runs,
            int threads,
            ProgressCallback cb,
            RecordConsumer consumer
    ) throws Exception {

        Objects.requireNonNull(baseSimulation, "baseSimulation");
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1");
        if (threads < 1) threads = 1;

        final Rocket rocket = baseSimulation.getRocket();
        if (rocket == null) throw new IllegalStateException("baseSimulation.getRocket() returned null");
        
        // Try multiple methods to resolve the document
        final Object doc = resolveDocument(baseSimulation, rocket);
        if (doc == null) {
            throw new IllegalStateException(
                "Cannot resolve document from base simulation. " +
                "Tried: getDocument(), rocket.getDocument(), document field. " +
                "Ensure the simulation is properly attached to an OpenRocketDocument."
            );
        }

        final ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "hprc-mc-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        final AtomicInteger completed = new AtomicInteger(0);
        final List<Future<?>> futures = new ArrayList<>();

        // Chunking reduces executor overhead for large run counts.
        final int chunkSize = Math.max(1, runs / (threads * 4));

        try {
            for (int start = 0; start < runs; start += chunkSize) {
                final int s0 = start;
                final int s1 = Math.min(runs, start + chunkSize);

                futures.add(pool.submit(() -> {
                    for (int i = s0; i < s1; i++) {
                        if (Thread.currentThread().isInterrupted()) return;

                        int runIndex = i + 1;

                        try {
                            Simulation sim = newSimulation(doc, rocket);
                            sim.setName(baseSimulation.getName() + " MC " + runIndex);

                            // Copy base options (many things are correct here, but wind objects can be shared)
                            sim.copySimulationOptionsFrom(baseSimulation.getOptions());

                            // Ensure motor/flight configuration is preserved
                            copyFlightConfigurationBestEffort(baseSimulation, sim);

                            // Deep-copy wind to prevent base mutation via shared wind-model references
                            deepCopyWindSettings(baseSimulation.getOptions(), sim.getOptions());

                            // Clone extensions to isolate state
                            sim.getSimulationExtensions().clear();
                            for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                                SimulationExtension cloned = (ext != null) ? ext.clone() : null;
                                // Ensure MC config maps survive cloning across OR versions.
                                if (cloned instanceof MonteCarloExtension mcClone && ext instanceof MonteCarloExtension mcOrig) {
                                    mcClone.copyPersistentSettingsFrom(mcOrig);
                                }
                                if (cloned != null) {
                                    sim.getSimulationExtensions().add(cloned);
                                }
                            }

                            // Configure MC extension for this run (batch context + per-run seed)
                            MonteCarloExtension mc = findMonteCarloExtension(sim);
                            long seedUsed = ThreadLocalRandom.current().nextLong();
                            boolean det = false;
                            double wSpeedSigma = 0.0;
                            double wTurbSigma = 0.0;

                            // Gust/Shear config (captured for CSV)
                            boolean gustEnabled = false;
                            boolean shearEnabled = false;
                            int gustCountCfg = 0;
                            double gustWindowStartS = 0.0;
                            double gustWindowEndS = 0.0;
                            double gustDurMeanS = 0.0;
                            double gustDurSigmaS = 0.0;
                            double gustPeakMeanMps = 0.0;
                            double gustPeakSigmaMps = 0.0;

                            double shearCenterAltM = 0.0;
                            double shearThicknessM = 0.0;
                            double shearDeltaMeanMps = 0.0;
                            double shearDeltaSigmaMps = 0.0;

                            // Physics override config (captured for CSV)
                            double cdMultSigma = 0.0;
                            double thrustMultSigma = 0.0;
                            double massMultSigma = 0.0;

                            if (mc != null) {
                                mc.setBatchRunContext(true);

                                det = mc.isUseDeterministicSeed();
                                wSpeedSigma = mc.getWindSpeedAverageSigmaMps();
                                wTurbSigma = mc.getWindSpeedTurbulenceSigmaMps();

                                gustEnabled = mc.isGustEventsEnabled();
                                shearEnabled = mc.isShearLayerEnabled();
                                gustCountCfg = mc.getGustEventCount();
                                gustWindowStartS = mc.getGustWindowStartS();
                                gustWindowEndS = mc.getGustWindowEndS();
                                gustDurMeanS = mc.getGustDurationMeanS();
                                gustDurSigmaS = mc.getGustDurationSigmaS();
                                gustPeakMeanMps = mc.getGustPeakDeltaMeanMps();
                                gustPeakSigmaMps = mc.getGustPeakDeltaSigmaMps();

                                shearCenterAltM = mc.getShearCenterAltM();
                                shearThicknessM = mc.getShearThicknessM();
                                shearDeltaMeanMps = mc.getShearDeltaMeanMps();
                                shearDeltaSigmaMps = mc.getShearDeltaSigmaMps();

                                cdMultSigma = mc.getCdMultiplierSigma();
                                thrustMultSigma = mc.getThrustMultiplierSigma();
                                massMultSigma = mc.getMassMultiplierSigma();

                                if (det) {
                                    // Deterministic: baseSeed + (runIndex-1)
                                    seedUsed = mc.getRandomSeed() + (runIndex - 1);
                                }
                                mc.setBatchSeed(seedUsed);
                            }

                            // Execute run (OpenRocket will call initialize(...) on extensions)
                            SimulationRunner.runSimulationInProcess(sim);

                            // Prefer the effective seed actually used inside initialize (for debugging)
                            if (mc != null) {
                                long eff = mc.getEffectiveSeedUsed();
                                if (eff != Long.MIN_VALUE) seedUsed = eff;
                            }

                            // Extract results
                            SimulationData data = SimulationData.fromSimulation(sim, 0);
                            SimulationOptions opts = sim.getOptions();

                            // Pull per-run gust/shear metrics from extension (if available)
                            int gustCountReal = 0;
                            double gustMaxDeltaWindMps = Double.NaN;
                            double shearDeltaMps = Double.NaN;
                            double deltaWindImpulse = Double.NaN;
                            double maxTiltDeg = Double.NaN;
                            double maxAoADeg = Double.NaN;

                            // Per-run physics override values
                            double cdMultUsed = 1.0;
                            double thrustMultUsed = 1.0;
                            double massMultUsed = 1.0;

                            if (mc != null) {
                                GustShearMetrics m = mc.getLastGustShearMetrics();
                                RunWindDisturbanceProfile p = mc.getLastWindDisturbanceProfile();
                                if (m != null) {
                                    gustCountReal = m.gustCount;
                                    gustMaxDeltaWindMps = m.maxDeltaWind_mps;
                                    shearDeltaMps = m.shearDelta_mps;
                                    deltaWindImpulse = m.deltaWindImpulse_mps_s;
                                    maxTiltDeg = m.maxTilt_deg;
                                    maxAoADeg = m.maxAoA_deg;
                                } else if (p != null) {
                                    // Fallback: at least capture how many gusts were sampled.
                                    gustCountReal = (p.gusts != null) ? p.gusts.size() : 0;
                                }

                                // Capture realized physics override values
                                cdMultUsed = mc.getLastCdMultiplier();
                                thrustMultUsed = mc.getLastThrustMultiplier();
                                massMultUsed = mc.getLastMassMultiplier();
                            }

                            MonteCarloRunRecord rec = new MonteCarloRunRecord(
                                    runIndex, sim.getName(), det, seedUsed,
                                    wSpeedSigma, wTurbSigma,

                                    gustEnabled, shearEnabled,
                                    gustCountCfg, gustWindowStartS, gustWindowEndS,
                                    gustDurMeanS, gustDurSigmaS,
                                    gustPeakMeanMps, gustPeakSigmaMps,
                                    shearCenterAltM, shearThicknessM,
                                    shearDeltaMeanMps, shearDeltaSigmaMps,

                                    gustCountReal, gustMaxDeltaWindMps, shearDeltaMps,
                                    deltaWindImpulse, maxTiltDeg, maxAoADeg,

                                    cdMultSigma, thrustMultSigma, massMultSigma,
                                    cdMultUsed, thrustMultUsed, massMultUsed,

                                    opts, data
                            );
                            rec.setLandingEastM(data.landingEast_m);
                            rec.setLandingNorthM(data.landingNorth_m);
                            rec.setLandingLatDeg(data.landingLat_deg);
                            rec.setLandingLonDeg(data.landingLon_deg);

                            if (consumer != null) consumer.onRecord(rec);

                        } catch (Exception ex) {
                            // Surface the first failure with context
                            throw new RuntimeException("Monte Carlo run " + runIndex + " failed", ex);
                        } finally {
                            int c = completed.incrementAndGet();
                            if (cb != null) cb.onProgress(c, runs);
                        }
                    }
                }));
            }

            // Join
            for (Future<?> f : futures) f.get();

        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Document resolution (multiple fallback methods)
    // -------------------------------------------------------------------------

    /**
     * Attempts to resolve the OpenRocketDocument from various sources.
     * Tries multiple methods to handle different OpenRocket versions and contexts.
     */
    private static Object resolveDocument(Simulation baseSimulation, Rocket rocket) {
        Object doc = null;

        // 1) Try simulation.getDocument()
        doc = invokeObject(baseSimulation, "getDocument");
        if (doc != null) return doc;

        // 2) Try rocket.getDocument()
        doc = invokeObject(rocket, "getDocument");
        if (doc != null) return doc;

        // 3) Try to get document from rocket's parent (some versions store it there)
        Object parent = invokeObject(rocket, "getParent");
        if (parent != null) {
            doc = invokeObject(parent, "getDocument");
            if (doc != null) return doc;
        }

        // 4) Try accessing 'document' field directly via reflection on the simulation
        doc = getFieldValue(baseSimulation, "document");
        if (doc != null) return doc;

        // 5) Try accessing 'doc' field directly via reflection on the simulation
        doc = getFieldValue(baseSimulation, "doc");
        if (doc != null) return doc;

        // 6) Try simulation.getOptions().getDocument() (some versions)
        SimulationOptions opts = baseSimulation.getOptions();
        if (opts != null) {
            doc = invokeObject(opts, "getDocument");
            if (doc != null) return doc;
        }

        // 7) Try to get the document from the rocket's default configuration
        Object config = invokeObject(rocket, "getDefaultConfiguration");
        if (config != null) {
            doc = invokeObject(config, "getDocument");
            if (doc != null) return doc;
        }

        // 8) Try rocket.getRoot() then getDocument()
        Object root = invokeObject(rocket, "getRoot");
        if (root != null && root != rocket) {
            doc = invokeObject(root, "getDocument");
            if (doc != null) return doc;
        }

        // 9) Look for document in simulation's stored data
        doc = invokeObject(baseSimulation, "getSimulatedData");
        if (doc != null) {
            Object dataDoc = invokeObject(doc, "getDocument");
            if (dataDoc != null) return dataDoc;
        }

        return null;
    }

    /**
     * Gets a field value using reflection, checking the class hierarchy.
     */
    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null) return null;
        
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // Try parent class
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Simulation construction (reflection-safe)
    // -------------------------------------------------------------------------

    private static Simulation newSimulation(Object doc, Rocket rocket) throws Exception {
        // Prefer: new Simulation(OpenRocketDocument, Rocket)
        try {
            Constructor<Simulation> c = Simulation.class.getConstructor(doc.getClass(), Rocket.class);
            return c.newInstance(doc, rocket);
        } catch (NoSuchMethodException ignored) {
            // Common OR signature: Simulation(OpenRocketDocument, Rocket) with OpenRocketDocument class, but
            // reflection above may fail due to different classloaders. Fall back to public constructor by name.
        }

        // Try to find any (OpenRocketDocument-like, Rocket) constructor
        for (Constructor<?> c : Simulation.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && Rocket.class.isAssignableFrom(p[1]) && p[0].isInstance(doc)) {
                return (Simulation) c.newInstance(doc, rocket);
            }
        }

        // Try constructors with different parameter orders
        for (Constructor<?> c : Simulation.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && Rocket.class.isAssignableFrom(p[0]) && p[1].isInstance(doc)) {
                return (Simulation) c.newInstance(rocket, doc);
            }
        }

        // Try single-argument constructor with just the document
        for (Constructor<?> c : Simulation.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(doc)) {
                Simulation sim = (Simulation) c.newInstance(doc);
                // Set the rocket if there's a method for it
                tryInvokeSetter(sim, "setRocket", rocket);
                return sim;
            }
        }

        throw new IllegalStateException("Unable to construct Simulation(doc, rocket) for this OpenRocket version");
    }

    // -------------------------------------------------------------------------
    // Extension lookup
    // -------------------------------------------------------------------------

    private static MonteCarloExtension findMonteCarloExtension(Simulation sim) {
        if (sim == null) return null;
        for (SimulationExtension ext : sim.getSimulationExtensions()) {
            if (ext instanceof MonteCarloExtension mc) return mc;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Flight configuration copy (motor selection)
    // -------------------------------------------------------------------------

    private static void copyFlightConfigurationBestEffort(Simulation srcSim, Simulation dstSim) {
        if (srcSim == null || dstSim == null) return;

        // 1) Copy an ID if available (most stable)
        Object id = invokeObject(srcSim, "getFlightConfigurationId");
        if (id == null) id = invokeObject(srcSim, "getMotorConfigurationID");
        if (id == null) id = invokeObject(srcSim, "getMotorConfigurationId");
        if (id != null) {
            if (tryInvokeSetter(dstSim, "setFlightConfigurationId", id)) return;
            if (tryInvokeSetter(dstSim, "setMotorConfigurationID", id)) return;
            if (tryInvokeSetter(dstSim, "setMotorConfigurationId", id)) return;
        }

        // 2) Copy a configuration object if exposed
        Object cfg = invokeObject(srcSim, "getFlightConfiguration");
        if (cfg == null) cfg = invokeObject(srcSim, "getActiveConfiguration");
        if (cfg == null) cfg = invokeObject(srcSim, "getSelectedConfiguration");
        if (cfg != null) {
            if (tryInvokeSetter(dstSim, "setFlightConfiguration", cfg)) return;
            if (tryInvokeSetter(dstSim, "setActiveConfiguration", cfg)) return;
            if (tryInvokeSetter(dstSim, "setSelectedConfiguration", cfg)) return;
        }

        // 3) Try via options
        try {
            Object srcOpts = invokeObject(srcSim, "getOptions");
            Object dstOpts = invokeObject(dstSim, "getOptions");
            if (srcOpts != null && dstOpts != null) {
                Object id2 = invokeObject(srcOpts, "getFlightConfigurationId");
                if (id2 == null) id2 = invokeObject(srcOpts, "getMotorConfigurationID");
                if (id2 == null) id2 = invokeObject(srcOpts, "getMotorConfigurationId");
                if (id2 != null) {
                    if (tryInvokeSetter(dstOpts, "setFlightConfigurationId", id2)) return;
                    if (tryInvokeSetter(dstOpts, "setMotorConfigurationID", id2)) return;
                    if (tryInvokeSetter(dstOpts, "setMotorConfigurationId", id2)) return;
                }
            }
        } catch (Throwable ignored) { }

        // 4) Last resort: copy selected configuration on the rocket itself
        try {
            Object srcRocket = invokeObject(srcSim, "getRocket");
            Object dstRocket = invokeObject(dstSim, "getRocket");
            if (srcRocket != null && dstRocket != null) {
                Object sel = invokeObject(srcRocket, "getSelectedConfiguration");
                if (sel != null) tryInvokeSetter(dstRocket, "setSelectedConfiguration", sel);
            }
        } catch (Throwable ignored) { }
    }

    // -------------------------------------------------------------------------
    // WIND deep copy (fixes base-wind mutation + multi-level support)
    // -------------------------------------------------------------------------

    private static void deepCopyWindSettings(SimulationOptions src, SimulationOptions dst) {
        if (src == null || dst == null) return;

        final Object srcType = invokeObject(src, "getWindModelType");
        final Object srcWindModelObj = invokeObject(src, "getWindModel");

        boolean multiActive = false;
        if (srcType != null) {
            String s = String.valueOf(srcType).toLowerCase();
            multiActive = (s.contains("multi") && s.contains("level"));
        }
        if (!multiActive && srcWindModelObj != null) {
            String cls = srcWindModelObj.getClass().getName().toLowerCase();
            if (cls.contains("multi") && cls.contains("level")) multiActive = true;
        }

        // Set type first (some OR builds key off this)
        if (srcType != null) {
            tryInvokeSetter(dst, "setWindModelType", srcType);
        }

        if (multiActive) {
            // Imported wind profiles often use a multi-level model class OTHER than MultiLevelPinkNoiseWindModel.
            Object srcMulti = invokeObject(src, "getMultiLevelWindModel");
            if (srcMulti == null) srcMulti = srcWindModelObj;

            if (srcMulti == null) {
                log.warn("MC: Multi-level wind active but src model is null; dst may use defaults.");
                return;
            }

            Object cloned = null;

            // If it's the known OR class, keep the existing precise clone path
            if (srcMulti instanceof MultiLevelPinkNoiseWindModel ml) {
                cloned = cloneMultiLevel(ml);
            } else {
                // Generic deep clone for imported profiles
                cloned = deepCloneGeneric(srcMulti);
            }

            boolean setOk = false;
            if (cloned != null) {
                setOk = tryInvokeSetter(dst, "setMultiLevelWindModel", cloned);
                if (!setOk) setOk = tryInvokeSetter(dst, "setWindModel", cloned);
            }

            // Re-assert type after installing (guards against internal resets)
            if (srcType != null) {
                tryInvokeSetter(dst, "setWindModelType", srcType);
            }

            if (!setOk) {
                // Last resort: attempt to copy level list into whatever dst already has
                Object dstMulti = invokeObject(dst, "getMultiLevelWindModel");
                if (dstMulti == null) dstMulti = invokeObject(dst, "getWindModel");

                boolean copied = (dstMulti != null) && copyLevelsGeneric(srcMulti, dstMulti);
                if (!copied) {
                    log.warn("MC: Failed to copy imported multi-level wind profile (srcClass={}, type={}). Dst may fall back to defaults.",
                            srcMulti.getClass().getName(),
                            (srcType != null ? String.valueOf(srcType) : "null"));
                }
            }
            return;
        }

        // ---- Scalar wind ----
        copyIfHas(src, dst, "getWindSpeed", "setWindSpeed");
        copyIfHas(src, dst, "getAverageWindSpeed", "setAverageWindSpeed");
        copyIfHas(src, dst, "getAverageWindspeed", "setAverageWindspeed");
        copyIfHas(src, dst, "getWindDirection", "setWindDirection");
        copyIfHas(src, dst, "getWindStandardDeviation", "setWindStandardDeviation");
        copyIfHas(src, dst, "getWindSpeedStandardDeviation", "setWindSpeedStandardDeviation");

        Object srcWM = invokeObject(src, "getWindModel");
        Object dstWM = invokeObject(dst, "getWindModel");
        if (srcWM != null && dstWM != null && srcWM == dstWM) {
            Object clonedWM = cloneScalarWindModel(srcWM);
            if (clonedWM != null) tryInvokeSetter(dst, "setWindModel", clonedWM);
        }
    }

    private static MultiLevelPinkNoiseWindModel resolveMultiLevelWindModel(Object opts, Object windModelObj) {
        if (windModelObj instanceof MultiLevelPinkNoiseWindModel ml) return ml;
        try {
            Method m = opts.getClass().getMethod("getMultiLevelWindModel");
            Object v = m.invoke(opts);
            if (v instanceof MultiLevelPinkNoiseWindModel ml) return ml;
        } catch (Exception ignored) { }
        return null;
    }

    private static MultiLevelPinkNoiseWindModel cloneMultiLevel(MultiLevelPinkNoiseWindModel src) {
        // 1) clone() if supported
        try {
            Method m = src.getClass().getMethod("clone");
            Object v = m.invoke(src);
            if (v instanceof MultiLevelPinkNoiseWindModel ml) return ml;
        } catch (Exception ignored) { }

        // 2) build new and copy levels
        MultiLevelPinkNoiseWindModel dst = new MultiLevelPinkNoiseWindModel();
        copyLevels(src, dst);
        return dst;
    }

    @SuppressWarnings("unchecked")
    private static void copyLevels(MultiLevelPinkNoiseWindModel src, MultiLevelPinkNoiseWindModel dst) {
        if (src == null || dst == null) return;

        // Try: dst.clearLevels()
        try {
            Method clear = dst.getClass().getMethod("clearLevels");
            clear.invoke(dst);
        } catch (Exception ignored) {
            try {
                List<?> lst = (List<?>) invokeObject(dst, "getLevels");
                if (lst != null) lst.clear();
            } catch (Exception ignored2) { }
        }

        for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : src.getLevels()) {
            // Prefer: dst.addLevel(altitude) -> LevelWindModel
            Object created = null;
            try {
                Method add = dst.getClass().getMethod("addLevel", double.class);
                created = add.invoke(dst, lvl.getAltitude());
            } catch (Exception ignored) { }

            if (created instanceof MultiLevelPinkNoiseWindModel.LevelWindModel newLvl) {
                newLvl.setSpeed(lvl.getSpeed());
                newLvl.setDirection(lvl.getDirection());
                try { newLvl.setStandardDeviation(lvl.getStandardDeviation()); } catch (Throwable ignored) { }
                continue;
            }

            // Try: constructor LevelWindModel(double alt, double speed, double dir, double std)
            try {
                Class<?> cls = Class.forName("info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel$LevelWindModel");
                for (Constructor<?> c : cls.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 4 &&
                            p[0] == double.class && p[1] == double.class && p[2] == double.class && p[3] == double.class) {
                        c.setAccessible(true);
                        Object obj = c.newInstance(lvl.getAltitude(), lvl.getSpeed(), lvl.getDirection(), lvl.getStandardDeviation());
                        try {
                            Method add2 = dst.getClass().getMethod("addLevel", cls);
                            add2.invoke(dst, obj);
                            created = obj;
                            break;
                        } catch (Exception ignored2) { }
                    }
                }
            } catch (Exception ignored) { }

            // Last resort: if we couldn't create, just skip (better than mutating shared refs)
        }
    }

    private static Object cloneScalarWindModel(Object srcWM) {
        if (srcWM == null) return null;

        // clone()
        try {
            Method m = srcWM.getClass().getMethod("clone");
            return m.invoke(srcWM);
        } catch (Exception ignored) { }

        // default constructor
        try {
            Constructor<?> c = srcWM.getClass().getDeclaredConstructor();
            c.setAccessible(true);
            Object dst = c.newInstance();

            // Copy common scalar properties
            copyIfHas(srcWM, dst, "getWindSpeed", "setWindSpeed");
            copyIfHas(srcWM, dst, "getSpeed", "setSpeed");
            copyIfHas(srcWM, dst, "getAverageWindSpeed", "setAverageWindSpeed");
            copyIfHas(srcWM, dst, "getAverageWindspeed", "setAverageWindspeed");

            copyIfHas(srcWM, dst, "getWindDirection", "setWindDirection");
            copyIfHas(srcWM, dst, "getDirection", "setDirection");

            copyIfHas(srcWM, dst, "getWindStandardDeviation", "setWindStandardDeviation");
            copyIfHas(srcWM, dst, "getWindSpeedStandardDeviation", "setWindSpeedStandardDeviation");
            copyIfHas(srcWM, dst, "getStandardDeviation", "setStandardDeviation");
            copyIfHas(srcWM, dst, "getStdDev", "setStdDev");

            return dst;
        } catch (Exception ignored) { }

        return null;
    }

    private static void copyIfHas(Object src, Object dst, String getter, String setter) {
        try {
            Method g = src.getClass().getMethod(getter);
            Object v = g.invoke(src);
            if (v == null) return;
            tryInvokeSetter(dst, setter, v);
        } catch (Exception ignored) { }
    }

    private static Object deepCloneGeneric(Object src) {
        if (src == null) return null;

        // 1) clone() if available
        Object c = tryInvokeClone(src);
        if (c != null) return c;

        // 2) Java serialization deep clone if possible
        c = trySerializeClone(src);
        if (c != null) return c;

        // 3) Reflective copy into new instance, with special handling for List fields
        Object dst = tryNewInstance(src.getClass());
        if (dst == null) return null;

        copyFieldsWithListDeepCopy(src, dst);
        return dst;
    }

    private static Object tryInvokeClone(Object src) {
        try {
            Method m;
            try {
                m = src.getClass().getMethod("clone");
            } catch (NoSuchMethodException e) {
                m = src.getClass().getDeclaredMethod("clone");
                m.setAccessible(true);
            }
            Object out = m.invoke(src);
            if (out != null && out != src) return out;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object trySerializeClone(Object src) {
        if (!(src instanceof Serializable)) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(src);
            }
            byte[] bytes = bos.toByteArray();
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                return ois.readObject();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryNewInstance(Class<?> cls) {
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copyFieldsWithListDeepCopy(Object src, Object dst) {
        Class<?> c = src.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(src);
                    if (v == null) continue;

                    // If it's a list, deep-copy elements (levels!)
                    if (v instanceof List<?> list) {
                        List<Object> copy = new ArrayList<>(list.size());
                        for (Object e : list) {
                            Object ec = tryInvokeClone(e);
                            if (ec == null) ec = trySerializeClone(e);
                            if (ec == null) {
                                Object ne = tryNewInstance(e.getClass());
                                if (ne != null) {
                                    copyFieldsShallow(e, ne);
                                    ec = ne;
                                } else {
                                    ec = e; // last resort
                                }
                            }
                            copy.add(ec);
                        }
                        f.set(dst, copy);
                    } else {
                        // Shallow for non-list fields (OK for primitives/immutable refs)
                        f.set(dst, v);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static void copyFieldsShallow(Object src, Object dst) {
        Class<?> c = src.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try { f.set(dst, f.get(src)); } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static boolean copyLevelsGeneric(Object srcMulti, Object dstMulti) {
        // Try getLevels()
        Object srcLevelsObj = invokeObject(srcMulti, "getLevels");
        Object dstLevelsObj = invokeObject(dstMulti, "getLevels");
        if (!(srcLevelsObj instanceof List srcLevels)) return false;

        // If dst exposes a mutable list, replace contents
        if (dstLevelsObj instanceof List dstLevels) {
            try {
                dstLevels.clear();
                for (Object lvl : srcLevels) {
                    Object lv2 = tryInvokeClone(lvl);
                    if (lv2 == null) lv2 = trySerializeClone(lvl);
                    if (lv2 == null) {
                        Object ne = tryNewInstance(lvl.getClass());
                        if (ne != null) { copyFieldsShallow(lvl, ne); lv2 = ne; }
                        else lv2 = lvl;
                    }
                    dstLevels.add(lv2);
                }
                return true;
            } catch (Throwable ignored) {}
        }

        // Otherwise try setLevels(List)
        List<Object> clonedLevels = new ArrayList<>();
        for (Object lvl : srcLevels) {
            Object lv2 = tryInvokeClone(lvl);
            if (lv2 == null) lv2 = trySerializeClone(lvl);
            if (lv2 == null) {
                Object ne = tryNewInstance(lvl.getClass());
                if (ne != null) { copyFieldsShallow(lvl, ne); lv2 = ne; }
                else lv2 = lvl;
            }
            clonedLevels.add(lv2);
        }
        return tryInvokeSetter(dstMulti, "setLevels", clonedLevels) || tryInvokeSetter(dstMulti, "setWindLevels", clonedLevels);
    }

    // -------------------------------------------------------------------------
    // Generic reflection helpers
    // -------------------------------------------------------------------------

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean tryInvokeSetter(Object target, String methodName, Object value) {
        if (target == null || methodName == null) return false;
        Method[] methods = target.getClass().getMethods();
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (value == null) continue;
            if (!p.isAssignableFrom(value.getClass())) {
                // allow primitive boxing (double/int/long/boolean)
                if (p.isPrimitive()) {
                    if ((p == double.class && value instanceof Number) ||
                        (p == int.class && value instanceof Number) ||
                        (p == long.class && value instanceof Number) ||
                        (p == boolean.class && value instanceof Boolean)) {
                        // ok
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }
            try {
                if (p == double.class && value instanceof Number n) {
                    m.invoke(target, n.doubleValue());
                } else if (p == int.class && value instanceof Number n) {
                    m.invoke(target, n.intValue());
                } else if (p == long.class && value instanceof Number n) {
                    m.invoke(target, n.longValue());
                } else {
                    m.invoke(target, value);
                }
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
}
