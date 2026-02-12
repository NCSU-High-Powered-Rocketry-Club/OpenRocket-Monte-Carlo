package com.hprc.montecarlo;

import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Simulation listener that applies per-run wind gusts/shear by mutating the wind model each step.
 *
 * This intentionally uses only very small amounts of OpenRocket API directly and relies on
 * reflection for many getters so it survives minor version differences.
 */
final class GustShearListener extends AbstractSimulationListener {

    private final WindSnapshot windSnapshot;
    private final RunWindDisturbanceProfile profile;
    private final GustShearMetrics metrics;
    private final boolean debug;

    private double lastTime_s = Double.NaN;

    GustShearListener(WindSnapshot windSnapshot, RunWindDisturbanceProfile profile, GustShearMetrics metrics, boolean debug) {
        this.windSnapshot = windSnapshot;
        this.profile = profile;
        this.metrics = metrics;
        this.debug = debug;
    }

    @Override
    public void startSimulation(SimulationStatus status) throws SimulationException {
        if (metrics != null) {
            metrics.resetAccumulators();
            metrics.gustCount = (profile != null && profile.gusts != null) ? profile.gusts.size() : 0;
            if (profile != null && profile.shear != null) {
                metrics.shearDelta_mps = profile.shear.deltaTop.mag();
            } else {
                metrics.shearDelta_mps = Double.NaN;
            }
        }

        lastTime_s = safeSimulationTime(status);
    }

    @Override
    public boolean preStep(SimulationStatus status) throws SimulationException {
        if (windSnapshot == null || profile == null) return true;

        double t = safeSimulationTime(status);
        double alt = safeAltitude(status);

        windSnapshot.applyDelta(profile, t, alt);
        return true;
    }

    @Override
    public void postStep(SimulationStatus status) throws SimulationException {
        if (metrics == null || profile == null) return;

        double t = safeSimulationTime(status);
        double alt = safeAltitude(status);

        Vec2 delta = profile.deltaWindXY(t, alt);
        double mag = delta.mag();

        if (!Double.isFinite(metrics.maxDeltaWind_mps) || mag > metrics.maxDeltaWind_mps) {
            metrics.maxDeltaWind_mps = mag;
        }

        if (Double.isFinite(lastTime_s)) {
            double dt = t - lastTime_s;
            if (dt > 0 && Double.isFinite(dt)) {
                metrics.deltaWindImpulse_mps_s += mag * dt;
            }
        }
        lastTime_s = t;

        // Weathercocking proxy: tilt from vertical based on velocity vector
        double tiltDeg = safeTiltDeg(status);
        if (Double.isFinite(tiltDeg) && (!Double.isFinite(metrics.maxTilt_deg) || tiltDeg > metrics.maxTilt_deg)) {
            metrics.maxTilt_deg = tiltDeg;
        }

        // AoA (if available)
        double aoaDeg = safeAoADeg(status);
        if (Double.isFinite(aoaDeg) && (!Double.isFinite(metrics.maxAoA_deg) || aoaDeg > metrics.maxAoA_deg)) {
            metrics.maxAoA_deg = aoaDeg;
        }
    }

    @Override
    public void endSimulation(SimulationStatus status, SimulationException exception) {
        if (windSnapshot != null) {
            windSnapshot.restore();
        }
    }

    // ---------------------------------------------------------------------
    // Reflection-safe extractors
    // ---------------------------------------------------------------------

    private static double safeSimulationTime(SimulationStatus status) {
        if (status == null) return Double.NaN;
        try {
            return status.getSimulationTime();
        } catch (Throwable ignored) {
            // reflection fallback
            return invokeDouble(status, "getSimulationTime", Double.NaN);
        }
    }

    private static double safeAltitude(SimulationStatus status) {
        if (status == null) return Double.NaN;

        // Prefer rocket position Z
        Object pos = invokeObject(status, "getRocketPosition");
        if (pos != null) {
            double z = invokeDouble(pos, "getZ", Double.NaN);
            if (!Double.isFinite(z)) z = invokeDouble(pos, "z", Double.NaN);
            if (!Double.isFinite(z)) z = readFieldDouble(pos, "z", Double.NaN);
            if (Double.isFinite(z)) return z;
        }

        // Fallback: flight conditions altitude if exposed
        Object fc = invokeObject(status, "getFlightConditions");
        if (fc != null) {
            double alt = invokeDouble(fc, "getAltitude", Double.NaN);
            if (Double.isFinite(alt)) return alt;
        }

        return Double.NaN;
    }

    private static double safeTiltDeg(SimulationStatus status) {
        if (status == null) return Double.NaN;

        Object vel = invokeObject(status, "getRocketVelocity");
        if (vel == null) vel = invokeObject(status, "getVelocity");
        if (vel == null) return Double.NaN;

        double vx = invokeDouble(vel, "getX", Double.NaN);
        double vy = invokeDouble(vel, "getY", Double.NaN);
        double vz = invokeDouble(vel, "getZ", Double.NaN);

        if (!Double.isFinite(vx)) vx = readFieldDouble(vel, "x", Double.NaN);
        if (!Double.isFinite(vy)) vy = readFieldDouble(vel, "y", Double.NaN);
        if (!Double.isFinite(vz)) vz = readFieldDouble(vel, "z", Double.NaN);

        if (!(Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz))) return Double.NaN;

        double vmag = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vmag <= 1e-9) return Double.NaN;

        // Tilt from vertical: acos(vz/|v|)
        double c = clamp(vz / vmag, -1.0, 1.0);
        double tiltRad = Math.acos(c);
        return Math.toDegrees(tiltRad);
    }

    private static double safeAoADeg(SimulationStatus status) {
        if (status == null) return Double.NaN;

        Object fc = invokeObject(status, "getFlightConditions");
        if (fc == null) return Double.NaN;

        double aoaRad = invokeDouble(fc, "getAOA", Double.NaN);
        if (!Double.isFinite(aoaRad)) aoaRad = invokeDouble(fc, "getAngleOfAttack", Double.NaN);
        if (!Double.isFinite(aoaRad)) aoaRad = invokeDouble(fc, "getAngleOfAttackRad", Double.NaN);

        if (!Double.isFinite(aoaRad)) {
            // Sometimes exposed on status directly
            aoaRad = invokeDouble(status, "getAOA", Double.NaN);
            if (!Double.isFinite(aoaRad)) aoaRad = invokeDouble(status, "getAngleOfAttack", Double.NaN);
        }

        if (!Double.isFinite(aoaRad)) return Double.NaN;
        return Math.toDegrees(aoaRad);
    }

    // ---------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double invokeDouble(Object target, String methodName, double fallback) {
        if (target == null) return fallback;
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number n) return n.doubleValue();
            return fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double readFieldDouble(Object target, String fieldName, double fallback) {
        if (target == null) return fallback;
        try {
            Field f = target.getClass().getField(fieldName);
            Object v = f.get(target);
            if (v instanceof Number n) return n.doubleValue();
            return fallback;
        } catch (Exception ignored) {
            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Number n) return n.doubleValue();
                return fallback;
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
