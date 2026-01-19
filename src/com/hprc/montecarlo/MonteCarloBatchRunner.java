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
import java.util.function.Consumer;

/**
 * Runs N Monte Carlo simulations sequentially or in parallel and returns per-run records.
 * Optimized for reduced scheduling overhead via chunking.
 */
public final class MonteCarloBatchRunner {

    private MonteCarloBatchRunner() {}

    public interface ProgressCallback {
        void onProgress(int completed, int total, String message);
    }

    /**
     * Executes batch runs in memory returning a List. 
     * Delegates to the streaming implementation to ensure consistent optimization.
     */
    public static List<MonteCarloRunRecord> runBatch(
            Simulation baseSimulation,
            int runs,
            ProgressCallback cb
    ) throws Exception {
        // Sequential execution is just parallel execution with 1 thread
        return runBatchParallel(baseSimulation, runs, 1, cb);
    }

    /**
     * Runs N Monte Carlo simulations in parallel using a fixed thread pool.
     * Returns results ordered by runIndex (1..runs).
     * 
     * Optimizations:
     * 1. Uses chunking to lower Future/Task overhead.
     * 2. Reduces lock contention on progress callback.
     */
    public static List<MonteCarloRunRecord> runBatchParallel(
            Simulation baseSimulation,
            int runs,
            int threads,
            ProgressCallback cb
    ) throws Exception {
        // Prepare storage for ordered results
        final MonteCarloRunRecord[] results = new MonteCarloRunRecord[runs];

        // Run streaming with a consumer that populates the array
        runBatchParallelStreaming(baseSimulation, runs, threads, cb, (record) -> {
            if (record != null) {
                // Determine 0-based index from 1-based runIndex
                // Safe to write concurrently to different array indices
                results[record.runIndex - 1] = record;
            }
        });

        // Filter out any potential nulls if partial failure occurred
        List<MonteCarloRunRecord> out = new ArrayList<>(runs);
        for (MonteCarloRunRecord r : results) {
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * New Pathway: Streaming execution.
     * Allows processing large batches without holding all outcomes in memory.
     * 
     * Thread Safety Note:
     * OpenRocket Simulation objects are not thread-safe if shared.
     * We create a dedicated new Simulation(doc, rocket) for each run inside the worker threads.
     * The document and rocket are treated as effectively read-only during simulation.
     */
    public static void runBatchParallelStreaming(
            Simulation baseSimulation,
            int runs,
            int threads,
            ProgressCallback cb,
            Consumer<MonteCarloRunRecord> resultConsumer
    ) throws Exception {
        if (runs < 1) throw new IllegalArgumentException("runs must be >= 1");
        
        final int availableCores = Runtime.getRuntime().availableProcessors();
        final int threadCount = threads <= 0 ? availableCores : Math.max(1, threads);

        OpenRocketDocument doc = resolveDocument(baseSimulation);
        Rocket rocket = doc.getRocket(); // Shared Rocket model

        // Atomic counters for progress
        final AtomicInteger completedCounter = new AtomicInteger(0);
        
        // Calculate chunk size to balance load and overhead
        // Target: ~4 chunks per thread to allow some stealing/balancing, but cap min/max size
        int rawChunkSize = runs / (threadCount * 4);
        int chunkSize = Math.max(10, Math.min(200, rawChunkSize)); // Between 10 and 200 per task
        
        // Interval for callbacks to reduce UI contention (e.g. notify every 1% or 20 runs)
        final int notifyInterval = Math.max(1, runs / 100);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        if (cb != null) cb.onProgress(0, runs, "Initializing batch...");

        try {
            int currentStart = 0;
            while (currentStart < runs) {
                final int start = currentStart;
                final int end = Math.min(currentStart + chunkSize, runs);
                
                // Submit task for runs [start, end)
                futures.add(pool.submit(() -> {
                    try {
                        // Thread-local logic: Create independent sim per run
                        for (int i = start; i < end; i++) {
                            // Check for interruption (fast exit on cancel)
                            if (Thread.currentThread().isInterrupted()) break;

                            int runIndex = i + 1;
                            
                            // 1. Create Simulation & Copy settings
                            Simulation s = new Simulation(doc, rocket);
                            s.setName(baseSimulation.getName() + " MC " + runIndex);
                            s.copySimulationOptionsFrom(baseSimulation.getOptions());
                            
                            // 2. OR 24.12 Safeguards (Motor + Wind)
                            copyFlightConfigurationBestEffort(baseSimulation, s);
                            copyWindSettings(baseSimulation.getOptions(), s.getOptions());

                            // 3. Clone extensions to isolate state
                            s.getSimulationExtensions().clear();
                            for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                                s.getSimulationExtensions().add(ext.clone());
                            }

                            // 4. MC Logic: Seeds & Perturbations
                            MonteCarloExtension mc = findMonteCarloExtension(s);
                            long seedUsed = 0L;
                            boolean det = false;
                            double wSpeedSigma = 0;
                            double wTurbSigma = 0;
                            
                            if (mc != null) {
                                det = mc.isUseDeterministicSeed();
                                wSpeedSigma = mc.getWindSpeedAverageSigmaMps();
                                wTurbSigma = mc.getWindSpeedTurbulenceSigmaMps();
                                if (det) {
                                    // Deterministic Seed Logic: Base + RunIndex
                                    seedUsed = mc.getRandomSeed() + i;
                                    mc.setRandomSeed(seedUsed);
                                }
                            }

                            // 5. Execute Run (In-process)
                            SimulationRunner.runSimulationInProcess(s);
                            
                            // 6. Extract Data
                            SimulationData data = SimulationData.fromSimulation(s, 0);
                            SimulationOptions opts = s.getOptions();
                            
                            MonteCarloRunRecord rec = new MonteCarloRunRecord(
                                    runIndex, s.getName(), det, seedUsed,
                                    wSpeedSigma, wTurbSigma, opts, data
                            );
                            rec.setLandingEastM(data.landingEast_m);
                            rec.setLandingNorthM(data.landingNorth_m);
                            rec.setLandingLatDeg(data.landingLat_deg);
                            rec.setLandingLonDeg(data.landingLon_deg);
                            
                            // 7. Stream result
                            if (resultConsumer != null) {
                                resultConsumer.accept(rec);
                            }

                            // 8. Throttled Progress
                            int c = completedCounter.incrementAndGet();
                            if (cb != null && (c % notifyInterval == 0 || c == runs)) {
                                cb.onProgress(c, runs, String.format("Completed %d / %d", c, runs));
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
                
                currentStart = end;
            }

            // Wait for all chunks
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ex) {
                    // Start polite cancel
                    for (Future<?> other : futures) other.cancel(true);
                    throw unwrap(ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Exception unwrap(Exception ex) {
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

        throw new IllegalStateException("Could not resolve OpenRocketDocument from Simulation.");
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
