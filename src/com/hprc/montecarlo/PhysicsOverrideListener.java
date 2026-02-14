package com.hprc.montecarlo;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

import java.lang.reflect.Method;

/**
 * Simulation listener that applies per-run physics overrides:
 *   - CD (drag coefficient) multiplier: scales aerodynamic drag every step
 *   - Thrust multiplier: scales motor thrust every step
 *   - Launch mass multiplier: scales total rocket mass every step
 *
 * These are the dominant sources of apogee prediction error and are critical
 * for realistic Monte Carlo dispersion when an airbrakes controller is active,
 * since the controller can compensate for wind/atmosphere variation but NOT
 * for fundamental vehicle performance differences (higher drag, lower thrust,
 * heavier rocket).
 *
 * Each multiplier is sampled once per run (in MonteCarloExtension.initialize)
 * and held constant throughout the flight.
 *
 * NOTE: If the AerodynamicForces or RigidBody imports fail to compile with
 * your OpenRocket version, remove those overrides — the thrust multiplier
 * alone provides the most important variation for apogee prediction.
 */
final class PhysicsOverrideListener extends AbstractSimulationListener {

    private final double cdMultiplier;
    private final double thrustMultiplier;
    private final double massMultiplier;
    private final boolean debug;

    // Tracks whether we've logged the first application (avoid log spam)
    private boolean loggedCd = false;
    private boolean loggedThrust = false;
    private boolean loggedMass = false;

    PhysicsOverrideListener(double cdMultiplier, double thrustMultiplier, double massMultiplier, boolean debug) {
        this.cdMultiplier = cdMultiplier;
        this.thrustMultiplier = thrustMultiplier;
        this.massMultiplier = massMultiplier;
        this.debug = debug;
    }

    double getCdMultiplier()     { return cdMultiplier; }
    double getThrustMultiplier() { return thrustMultiplier; }
    double getMassMultiplier()   { return massMultiplier; }

    // -----------------------------------------------------------------
    // Thrust override
    // -----------------------------------------------------------------

    /**
     * Called by OpenRocket after computing motor thrust for the current step.
     * We scale it by our per-run multiplier.
     *
     * A multiplier < 1.0 simulates under-performing motors (lower total impulse).
     * A multiplier > 1.0 simulates over-performing motors.
     */
    @Override
    public double postSimpleThrustCalculation(SimulationStatus status, double thrust)
            throws SimulationException {
        if (thrustMultiplier == 1.0) return thrust;

        double scaled = thrust * thrustMultiplier;

        if (debug && !loggedThrust && thrust > 0.0) {
            loggedThrust = true;
            System.out.printf("[MC PhysicsOverride] Thrust multiplier=%.4f, base=%.2f N, scaled=%.2f N%n",
                    thrustMultiplier, thrust, scaled);
        }

        return scaled;
    }

    // -----------------------------------------------------------------
    // Drag (CD) override
    // -----------------------------------------------------------------

    /**
     * Called by OpenRocket after computing aerodynamic forces for the current step.
     * We scale the drag coefficient by our per-run multiplier.
     *
     * A multiplier > 1.0 simulates higher drag (rough surfaces, imperfect fins,
     * launch lugs, paint, etc.) which is the typical real-world scenario.
     */
    @Override
    public AerodynamicForces postAerodynamicCalculation(SimulationStatus status,
                                                         AerodynamicForces forces)
            throws SimulationException {
        if (forces == null || cdMultiplier == 1.0) return forces;

        try {
            double baseCd = forces.getCD();
            if (Double.isFinite(baseCd)) {
                forces.setCD(baseCd * cdMultiplier);

                if (debug && !loggedCd) {
                    loggedCd = true;
                    System.out.printf("[MC PhysicsOverride] CD multiplier=%.4f, base=%.6f, scaled=%.6f%n",
                            cdMultiplier, baseCd, baseCd * cdMultiplier);
                }
            }
        } catch (Throwable t) {
            // If getCD/setCD don't exist in this OR version, try reflection fallbacks
            if (!loggedCd) {
                loggedCd = true;
                boolean applied = tryReflectiveScaleCd(forces);
                if (!applied && debug) {
                    System.out.println("[MC PhysicsOverride] WARNING: Could not scale CD on " +
                            forces.getClass().getName());
                }
            } else {
                tryReflectiveScaleCd(forces);
            }
        }

        return forces;
    }

    /**
     * Reflective fallback for scaling CD when the direct API doesn't match.
     */
    private boolean tryReflectiveScaleCd(Object forces) {
        String[][] pairs = {
            {"getCD",      "setCD"},
            {"getCDTotal", "setCDTotal"},
            {"getCd",      "setCd"},
        };

        for (String[] pair : pairs) {
            Double baseCd = invokeDoubleMethod(forces, pair[0]);
            if (baseCd != null && Double.isFinite(baseCd)) {
                if (invokeSetter(forces, pair[1], baseCd * cdMultiplier)) {
                    return true;
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    // Mass override
    // -----------------------------------------------------------------

    /**
     * Called by OpenRocket after computing mass properties for the current step.
     * We scale the total mass by our per-run multiplier.
     *
     * A multiplier > 1.0 simulates a heavier rocket (extra epoxy, paint, etc.).
     */
    @Override
    public RigidBody postMassCalculation(SimulationStatus status, RigidBody rigidBody)
            throws SimulationException {
        if (rigidBody == null || massMultiplier == 1.0) return rigidBody;

        Double baseMassObj = invokeDoubleMethod(rigidBody, "getMass");
        if (baseMassObj == null || !Double.isFinite(baseMassObj) || baseMassObj <= 0.0) {
            if (debug && !loggedMass) {
                loggedMass = true;
                System.out.println("[MC PhysicsOverride] WARNING: Could not read mass from " +
                        rigidBody.getClass().getName());
            }
            return rigidBody;
        }

        double baseMass = baseMassObj;
        double scaledMass = baseMass * massMultiplier;
        RigidBody scaled = withScaledMass(rigidBody, scaledMass);

        if (scaled != null) {
            if (debug && !loggedMass) {
                loggedMass = true;
                System.out.printf("[MC PhysicsOverride] Mass multiplier=%.4f, base=%.4f kg, scaled=%.4f kg%n",
                        massMultiplier, baseMass, scaledMass);
            }
            return scaled;
        }

        if (debug && !loggedMass) {
            loggedMass = true;
            System.out.println("[MC PhysicsOverride] WARNING: Could not construct scaled RigidBody on " +
                    rigidBody.getClass().getName());
        }
        return rigidBody;
    }

    private static RigidBody withScaledMass(RigidBody rigidBody, double newMass) {
        if (rigidBody == null || !Double.isFinite(newMass) || newMass <= 0.0) return null;
        try {
            return new RigidBody(
                    rigidBody.getCM().setWeight(newMass),
                    rigidBody.getIxx(),
                    rigidBody.getIyy(),
                    rigidBody.getIzz()
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------

    private static Double invokeDoubleMethod(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean invokeSetter(Object target, String methodName, double value) {
        if (target == null || methodName == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) { }
        try {
            Method m = target.getClass().getMethod(methodName, Double.class);
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) { }
        return false;
    }
}
