package com.hprc.montecarlo;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Rocket;
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
        Rocket rocket = doc.getRocket();

        List<MonteCarloRunRecord> out = new ArrayList<>(runs);

        for (int i = 0; i < runs; i++) {
            int runIndex = i + 1;
            if (cb != null) cb.onProgress(i, runs, "Preparing run " + runIndex + " / " + runs);

            // CLONE the rocket to ensure mass variations don't accumulate or affect the document
            Rocket rocketCopy = (Rocket) rocket.copy();
            Simulation s = new Simulation(doc, rocketCopy);
            s.setName(baseSimulation.getName() + " MC " + runIndex);

            // Copy base options
            s.copySimulationOptionsFrom(baseSimulation.getOptions());

            // Copy extensions (including MonteCarloExtension)
            s.getSimulationExtensions().clear();
            for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                s.getSimulationExtensions().add(ext.clone());
            }

            // If deterministic seed is enabled, vary it per-run for repeatable-but-distinct draws
            MonteCarloExtension mc = findMonteCarloExtension(s);
            long seedUsed = 0L;
            boolean det = false;
            if (mc != null) {
                det = mc.isUseDeterministicSeed();
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
            MonteCarloRunRecord rec = new MonteCarloRunRecord(runIndex, s.getName(), det, seedUsed, opts, data);
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
                    // CLONE the rocket here as well
                    Rocket rocketCopy = (Rocket) rocket.copy();
                    Simulation s = new Simulation(doc, rocketCopy);
                    s.setName(baseSimulation.getName() + " MC " + runIndex);

                    // Copy base options
                    s.copySimulationOptionsFrom(baseSimulation.getOptions());

                    // Copy extensions (including MonteCarloExtension)
                    s.getSimulationExtensions().clear();
                    for (SimulationExtension ext : baseSimulation.getSimulationExtensions()) {
                        s.getSimulationExtensions().add(ext.clone());
                    }

                    // If deterministic seed is enabled, vary it per-run for repeatable-but-distinct draws
                    MonteCarloExtension mc = findMonteCarloExtension(s);
                    long seedUsed = 0L;
                    boolean det = false;
                    if (mc != null) {
                        det = mc.isUseDeterministicSeed();
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
                        MonteCarloRunRecord rec = new MonteCarloRunRecord(runIndex, s.getName(), det, seedUsed, opts, data);
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
}