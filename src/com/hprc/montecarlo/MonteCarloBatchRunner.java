package com.hprc.montecarlo;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.SimulationExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

            Simulation s = new Simulation(doc, rocket);
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

            runSimulationInProcess(s);

            // Extract results
            SimulationData data = new SimulationData(s);
            data.processData(true); // keep sim object while we snapshot options (safe, runs count is your choice)

            SimulationOptions opts = s.getOptions();
            out.add(new MonteCarloRunRecord(runIndex, s.getName(), det, seedUsed, opts, data));

            if (cb != null) cb.onProgress(runIndex, runs, "Completed " + runIndex + " / " + runs);
        }

        return out;
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

    private static void runSimulationInProcess(Simulation sim) throws Exception {
        // 1. Try simulate(SimulationListener... listeners) - Standard OR API
        // We use reflection to find the method that takes an array (varargs)
        try {
            for (Method m : sim.getClass().getMethods()) {
                if (m.getName().equals("simulate")) {
                    Class<?>[] params = m.getParameterTypes();
                    // Check for varargs/array argument (e.g. SimulationListener[])
                    if (params.length == 1 && params[0].isArray()) {
                        // Create empty array of the listener type
                        Object emptyListeners = java.lang.reflect.Array.newInstance(params[0].getComponentType(), 0);
                        m.invoke(sim, emptyListeners);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to try other methods if this fails
        }

        // 2. Try no-arg methods (older versions or forks)
        String[] candidates = { "simulate", "runSimulation", "run" };
        for (String name : candidates) {
            try {
                Method m = sim.getClass().getMethod(name);
                m.invoke(sim);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
    }
}