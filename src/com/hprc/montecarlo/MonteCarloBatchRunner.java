package com.hprc.montecarlo;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.extension.SimulationExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs N Monte Carlo simulations sequentially (in-process) and returns per-run records.
 */
public final class MonteCarloBatchRunner {

    private MonteCarloBatchRunner() {}

    public interface ProgressCallback {
        void onProgress(int completed, int total, String message);
    }

    public static List<MonteCarloRunRecord> runBatch(
            Simulation baseSimulation,
            int runs,
            ProgressCallback cb
    ) throws Exception {
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1");

        OpenRocketDocument doc = resolveDocument(baseSimulation);
        // IMPORTANT:
        // Do NOT clone/copy the Rocket when creating new Simulation instances.
        // OpenRocket ties motor selections to FlightConfigurations owned by the document rocket.
        // If you simulate with a copied Rocket, the Simulation can silently fall back to a
        // "[No motors]" configuration, resulting in 0 m apogees.
        // Waterloo Rocketry's implementation always uses document.getRocket() for this reason.
        // (You can still vary conditions via SimulationOptions/SimulationConditions per-run.)
        Rocket rocket = doc.getRocket();

        List<MonteCarloRunRecord> out = new ArrayList<>(runs);

        for (int i = 0; i < runs; i++) {
            int runIndex = i + 1;
            if (cb != null) cb.onProgress(i, runs, "Preparing run " + runIndex + " / " + runs);

            // Use the document rocket (see note above)
            Simulation s = new Simulation(doc, rocket);
            s.setName(baseSimulation.getName() + " MC " + runIndex);

            // Copy base options
            s.copySimulationOptionsFrom(baseSimulation.getOptions());

            // IMPORTANT (OR 24.12): motor selection lives on the rocket's FlightConfiguration,
            // and some OR builds do not fully propagate the active FlightConfiguration when creating
            // a brand-new Simulation(doc, rocket) + copySimulationOptionsFrom(...).
            // If this step is skipped, simulations can silently fall back to a "[No motors]" config
            // and terminate immediately at t=0 with ~0 m apogee.
            copyFlightConfigurationBestEffort(baseSimulation, s);

            // IMPORTANT (OR 24.12): wind settings may not be deep-copied by copySimulationOptionsFrom().
            // Without this, newly constructed Simulations tend to fall back to default wind values.
            copyWindSettings(baseSimulation.getOptions(), s.getOptions());

            // Copy extensions (including MonteCarloExtension)
            s.getSimulationExtensions().clear();
            for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                s.getSimulationExtensions().add(ext.clone());
            }

            // If deterministic seed is enabled, vary it per-run for repeatable-but-distinct draws
            MonteCarloExtension mc = findMonteCarloExtension(s);
            long seedUsed = 0L;
            boolean det = false;
            double windSpeedAverageSigmaMps = 0.0;
            double windSpeedTurbulenceSigmaMps = 0.0;
            if (mc != null) {
                det = mc.isUseDeterministicSeed();
                windSpeedAverageSigmaMps = mc.getWindSpeedAverageSigmaMps();
                windSpeedTurbulenceSigmaMps = mc.getWindSpeedTurbulenceSigmaMps();
                if (det) {
                    seedUsed = mc.getRandomSeed() + i;
                    mc.setRandomSeed(seedUsed);
                } else {
                    seedUsed = 0L;
                }
            }

            if (cb != null) cb.onProgress(i, runs, "Running simulation " + runIndex + " / " + runs);

            SimulationRunner.runSimulationInProcess(s);

            // Extract results
            SimulationData data = SimulationData.fromSimulation(s, 0);

            SimulationOptions opts = s.getOptions();
            MonteCarloRunRecord rec = new MonteCarloRunRecord(runIndex, s.getName(), det, seedUsed,
                    windSpeedAverageSigmaMps, windSpeedTurbulenceSigmaMps,
                    opts, data);
            rec.setLandingEastM(data.landingEast_m);
            rec.setLandingNorthM(data.landingNorth_m);
            rec.setLandingLatDeg(data.landingLat_deg);
            rec.setLandingLonDeg(data.landingLon_deg);
            out.add(rec);

            if (cb != null) cb.onProgress(runIndex, runs, "Completed " + runIndex + " / " + runs);
        }

        return out;
    }

    /**
     * Runs N Monte Carlo simulations in parallel using a fixed thread pool (JVM threads).
     * Returns results ordered by runIndex (1..runs).
     */
    public static List<MonteCarloRunRecord> runBatchParallel(
            Simulation baseSimulation,
            int runs,
            int threads,
            ProgressCallback cb
    ) throws Exception {
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1");
        int threadCount = Math.max(1, threads);

        OpenRocketDocument doc = resolveDocument(baseSimulation);
        Rocket rocket = doc.getRocket();

        // Keep deterministic ordering in the returned list.
        final MonteCarloRunRecord[] results = new MonteCarloRunRecord[runs];

        // Progress counter for "completed" (1..runs)
        final AtomicInteger completed = new AtomicInteger(0);

        // Avoid concurrent UI/log callbacks interleaving mid-message
        final Object cbLock = new Object();
        final ProgressCallback safeCb = (done, total, msg) -> {
            if (cb == null) return;
            synchronized (cbLock) {
                cb.onProgress(done, total, msg);
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(runs);

        try {
            for (int i = 0; i < runs; i++) {
                final int runZeroBased = i;
                final int runIndex = i + 1;

                safeCb.onProgress(completed.get(), runs, "Queued run " + runIndex + " / " + runs);

                futures.add(pool.submit(() -> {
                    // Build an independent Simulation instance for this run
                    Simulation s = new Simulation(doc, rocket);
                    s.setName(baseSimulation.getName() + " MC " + runIndex);

                    // Copy base options
                    s.copySimulationOptionsFrom(baseSimulation.getOptions());

                    // Ensure motor FlightConfiguration carries over (see runBatch)
                    copyFlightConfigurationBestEffort(baseSimulation, s);

                    // IMPORTANT (OR 24.12): deep-copy wind settings (see comment in runBatch)
                    copyWindSettings(baseSimulation.getOptions(), s.getOptions());

                    // Copy extensions (including MonteCarloExtension)
                    s.getSimulationExtensions().clear();
                    for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                        s.getSimulationExtensions().add(ext.clone());
                    }

                    // If deterministic seed is enabled, vary it per-run for repeatable-but-distinct draws
                    MonteCarloExtension mc = findMonteCarloExtension(s);
                    long seedUsed = 0L;
                    boolean det = false;
                    double windSpeedAverageSigmaMps = 0.0;
                    double windSpeedTurbulenceSigmaMps = 0.0;
                    if (mc != null) {
                        det = mc.isUseDeterministicSeed();
                        windSpeedAverageSigmaMps = mc.getWindSpeedAverageSigmaMps();
                        windSpeedTurbulenceSigmaMps = mc.getWindSpeedTurbulenceSigmaMps();
                        if (det) {
                            seedUsed = mc.getRandomSeed() + runZeroBased;
                            mc.setRandomSeed(seedUsed);
                        } else {
                            seedUsed = 0L;
                        }
                    }

                    safeCb.onProgress(completed.get(), runs, "Running simulation " + runIndex + " / " + runs);

                    try {
                        SimulationRunner.runSimulationInProcess(s);

                        // Extract results
                        SimulationData data = SimulationData.fromSimulation(s, 0);

                        SimulationOptions opts = s.getOptions();
                        MonteCarloRunRecord rec = new MonteCarloRunRecord(runIndex, s.getName(), det, seedUsed,
                                windSpeedAverageSigmaMps, windSpeedTurbulenceSigmaMps,
                                opts, data);
                        rec.setLandingEastM(data.landingEast_m);
                        rec.setLandingNorthM(data.landingNorth_m);
                        rec.setLandingLatDeg(data.landingLat_deg);
                        rec.setLandingLonDeg(data.landingLon_deg);
                        results[runZeroBased] = rec;

                        int done = completed.incrementAndGet();
                        safeCb.onProgress(done, runs, "Completed " + done + " / " + runs);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }));
            }

            // Wait; cancel remaining tasks on first failure
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ex) {
                    for (Future<?> other : futures) {
                        other.cancel(true);
                    }
                    throw unwrap(ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<MonteCarloRunRecord> out = new ArrayList<>(runs);
        for (MonteCarloRunRecord r : results) {
            if (r != null) out.add(r);
        }
        return out;
    }

    private static Exception unwrap(Exception ex) {
        // Future.get wraps exceptions; unwrap the original Exception if possible.
        Throwable t = ex;
        while (t.getCause() != null && (t instanceof java.util.concurrent.ExecutionException || t instanceof RuntimeException)) {
            t = t.getCause();
        }
        if (t instanceof Exception e) return e;
        return ex;
    }

    private static MonteCarloExtension findMonteCarloExtension(Simulation sim) {
        for (SimulationExtension ext : sim.getSimulationExtensions()) {
            if (ext instanceof MonteCarloExtension mc) return mc;
        }
        return null;
    }

    private static OpenRocketDocument resolveDocument(Simulation sim) throws Exception {
        // Prefer sim.getDocument() if available
        try {
            Method m = sim.getClass().getMethod("getDocument");
            Object v = m.invoke(sim);
            if (v instanceof OpenRocketDocument d) return d;
        } catch (NoSuchMethodException ignored) { }

        // Some versions store it as a field; try reflection fallback
        for (var f : sim.getClass().getDeclaredFields()) {
            if (OpenRocketDocument.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object v = f.get(sim);
                if (v instanceof OpenRocketDocument d) return d;
            }
        }

        throw new IllegalStateException("Could not resolve OpenRocketDocument from Simulation. (Simulation.getDocument() not found)");
    }

    // -------------------------------------------------------------------------
    // FLIGHT CONFIGURATION (motor selection) BEST-EFFORT COPY
    // -------------------------------------------------------------------------

    /**
     * Best-effort copy of motor / flight configuration from one Simulation to another.
     *
     * Why this exists:
     *  - Motor selection in OpenRocket is handled through FlightConfigurations.
     *  - Depending on OR build/version, copySimulationOptionsFrom(...) may NOT carry over the
     *    selected FlightConfiguration, which can cause new simulations to run with "[No motors]".
     *
     * This method uses reflection only so we don't hard-bind to OpenRocket internal API changes.
     */
    private static void copyFlightConfigurationBestEffort(Simulation srcSim, Simulation dstSim) {
        if (srcSim == null || dstSim == null) return;

        // 1) Try copying an ID (most stable pattern)
        Object id = invokeObject(srcSim, "getFlightConfigurationId");
        if (id == null) id = invokeObject(srcSim, "getMotorConfigurationID");
        if (id == null) id = invokeObject(srcSim, "getMotorConfigurationId");
        if (id != null) {
            if (tryInvokeSetter(dstSim, "setFlightConfigurationId", id)) return;
            if (tryInvokeSetter(dstSim, "setMotorConfigurationID", id)) return;
            if (tryInvokeSetter(dstSim, "setMotorConfigurationId", id)) return;
        }

        // 2) Try copying an object (FlightConfiguration)
        Object cfg = invokeObject(srcSim, "getFlightConfiguration");
        if (cfg == null) cfg = invokeObject(srcSim, "getActiveConfiguration");
        if (cfg == null) cfg = invokeObject(srcSim, "getSelectedConfiguration");
        if (cfg != null) {
            if (tryInvokeSetter(dstSim, "setFlightConfiguration", cfg)) return;
            if (tryInvokeSetter(dstSim, "setActiveConfiguration", cfg)) return;
            if (tryInvokeSetter(dstSim, "setSelectedConfiguration", cfg)) return;
        }

        // 3) Try on SimulationOptions as a fallback
        try {
            SimulationOptions srcOpts = srcSim.getOptions();
            SimulationOptions dstOpts = dstSim.getOptions();
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
        } catch (Throwable ignored) {
        }

        // 4) Last resort: copy selected configuration on the rocket itself
        try {
            Object srcRocket = invokeObject(srcSim, "getRocket");
            Object dstRocket = invokeObject(dstSim, "getRocket");
            if (srcRocket != null && dstRocket != null) {
                Object sel = invokeObject(srcRocket, "getSelectedConfiguration");
                if (sel != null) {
                    tryInvokeSetter(dstRocket, "setSelectedConfiguration", sel);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // WIND DEEP-COPY (OR 24.12 safe)
    // -------------------------------------------------------------------------

    /**
     * OpenRocket 24.12 note:
     * - Wind "model type" and scalar wind values often live under SimulationConditions.
     * - MultiLevel wind profile lives under MultiLevelPinkNoiseWindModel (sometimes reachable from options, sometimes conditions).
     *
     * copySimulationOptionsFrom() does not reliably deep-copy wind fields when creating a new Simulation(doc, rocket),
     * so we explicitly transfer wind fields here.
     */
    private static void copyWindSettings(SimulationOptions src, SimulationOptions dst) {
        if (src == null || dst == null) return;

        final SimulationConditions srcCond = getConditions(src);
        final SimulationConditions dstCond = getConditions(dst);

        // ---- 0) Copy wind model selector if API provides it (best-effort; reflection only) ----
        // Prefer conditions.getWindModelType() -> conditions.setWindModelType(...)
        // DO NOT copy the wind model instance object between simulations; it may hold state
        // (pink-noise generator, cached profiles, etc.) and may not be safe to share.
        if (srcCond != null && dstCond != null) {
            // Some builds expose wind model type separately
            Object srcWindModelType = invokeObject(srcCond, "getWindModelType");
            if (srcWindModelType != null) {
                tryInvokeSetter(dstCond, "setWindModelType", srcWindModelType);
            }
        }

        // ---- 1) Copy scalar/average wind fields (prefer conditions; fallback to options) ----
        Object srcScalar = (srcCond != null) ? srcCond : src;
        Object dstScalar = (dstCond != null) ? dstCond : dst;

        // Speed & direction
        copyDoubleIfPresent(srcScalar, dstScalar, "getWindSpeed", "setWindSpeed");
        copyDoubleIfPresent(srcScalar, dstScalar, "getWindDirection", "setWindDirection");

        // Std dev (name varies across OR builds)
        if (!copyDoubleIfPresent(srcScalar, dstScalar, "getWindStandardDeviation", "setWindStandardDeviation")) {
            copyDoubleIfPresent(srcScalar, dstScalar, "getWindSpeedStandardDeviation", "setWindSpeedStandardDeviation");
        }

        // Turbulence knobs (if present)
        copyDoubleIfPresent(srcScalar, dstScalar, "getWindTurbulence", "setWindTurbulence");
        copyDoubleIfPresent(srcScalar, dstScalar, "getWindTurbulenceIntensity", "setWindTurbulenceIntensity");

        // ---- 2) Copy multi-level wind profile (if present) ----
        MultiLevelPinkNoiseWindModel srcModel = resolveMultiLevelWindModel(src, srcCond);
        MultiLevelPinkNoiseWindModel dstModel = resolveMultiLevelWindModel(dst, dstCond);

        if (srcModel != null && dstModel != null) {
            // Preferred: clearLevels() then addWindLevel(...)
            boolean didClear = false;
            try {
                Method clear = dstModel.getClass().getMethod("clearLevels");
                clear.invoke(dstModel);
                didClear = true;
            } catch (Exception ignored) { }

            boolean hasAdd = hasMethod(dstModel, "addWindLevel", double.class, double.class, double.class, double.class);

            if (didClear && hasAdd) {
                for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : srcModel.getLevels()) {
                    try {
                        Method add = dstModel.getClass().getMethod(
                                "addWindLevel", double.class, double.class, double.class, double.class
                        );
                        add.invoke(dstModel,
                                lvl.getAltitude(),
                                lvl.getSpeed(),
                                lvl.getDirection(),
                                lvl.getStandardDeviation()
                        );
                    } catch (Exception ignored) { }
                }
            } else {
                // Fallback: try to match existing dst levels by index and set values
                List<MultiLevelPinkNoiseWindModel.LevelWindModel> srcLvls = srcModel.getLevels();
                List<MultiLevelPinkNoiseWindModel.LevelWindModel> dstLvls = dstModel.getLevels();
                int n = Math.min(srcLvls.size(), dstLvls.size());
                for (int i = 0; i < n; i++) {
                    MultiLevelPinkNoiseWindModel.LevelWindModel s = srcLvls.get(i);
                    MultiLevelPinkNoiseWindModel.LevelWindModel d = dstLvls.get(i);
                    try { d.setSpeed(s.getSpeed()); } catch (Throwable ignored) { }
                    try { d.setDirection(s.getDirection()); } catch (Throwable ignored) { }
                    try { d.setStandardDeviation(s.getStandardDeviation()); } catch (Throwable ignored) { }
                    // altitude is often not mutable; ignore
                }
            }
        }
    }

    private static SimulationConditions getConditions(SimulationOptions opts) {
        if (opts == null) return null;
        try {
            Method m = opts.getClass().getMethod("getConditions");
            Object v = m.invoke(opts);
            if (v instanceof SimulationConditions sc) return sc;
        } catch (Exception ignored) { }
        return null;
    }

    private static MultiLevelPinkNoiseWindModel resolveMultiLevelWindModel(SimulationOptions opts, SimulationConditions cond) {
        if (cond != null) {
            Object maybe = invokeObject(cond, "getMultiLevelWindModel");
            if (maybe instanceof MultiLevelPinkNoiseWindModel ml) return ml;
        }
        try {
            return opts.getMultiLevelWindModel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean copyDoubleIfPresent(Object src, Object dst, String getterName, String setterName) {
        try {
            Method g = src.getClass().getMethod(getterName);
            Object v = g.invoke(src);
            if (!(v instanceof Number)) return false;
            double d = ((Number) v).doubleValue();

            Method s = dst.getClass().getMethod(setterName, double.class);
            s.invoke(dst, d);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryInvokeSetter(Object target, String setterName, Object value) {
        if (target == null || value == null) return false;
        try {
            Method m = target.getClass().getMethod(setterName, value.getClass());
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) {
            // If the setter takes an interface/supertype, try to find compatible method
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(setterName)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                if (p[0].isAssignableFrom(value.getClass())) {
                    try {
                        m.invoke(target, value);
                        return true;
                    } catch (Exception ignored2) { }
                }
            }
        }
        return false;
    }

    private static boolean hasMethod(Object target, String name, Class<?>... params) {
        if (target == null) return false;
        try {
            target.getClass().getMethod(name, params);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
