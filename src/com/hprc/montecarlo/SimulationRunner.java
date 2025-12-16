package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;

import java.lang.reflect.Method;

/**
 * Utility for running an OpenRocket Simulation in-process (no GUI).
 * Uses reflection to tolerate API differences between OpenRocket versions.
 */
public final class SimulationRunner {

    private SimulationRunner() { }

    public static void runSimulationInProcess(Simulation sim) throws Exception {
        // 1) Try simulate(SimulationListener... listeners) (common OR API)
        try {
            for (Method m : sim.getClass().getMethods()) {
                if (!m.getName().equals("simulate")) continue;

                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].isArray()) {
                    Object emptyListeners = java.lang.reflect.Array.newInstance(params[0].getComponentType(), 0);
                    m.invoke(sim, emptyListeners);
                    return;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }

        // 2) Try no-arg fallbacks seen in some versions/forks
        String[] candidates = { "simulate", "runSimulation", "run" };
        for (String name : candidates) {
            try {
                Method m = sim.getClass().getMethod(name);
                m.invoke(sim);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }

        throw new NoSuchMethodException("No compatible simulation run method found on " + sim.getClass().getName());
    }
}