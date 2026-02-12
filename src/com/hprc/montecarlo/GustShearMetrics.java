package com.hprc.montecarlo;

/**
 * Per-run metrics exported to CSV to provide "simulation awareness" about gusts/shear.
 */
public final class GustShearMetrics {
    public int gustCount = 0;

    /** Max instantaneous |delta wind| applied at the vehicle (m/s). */
    public double maxDeltaWind_mps = Double.NaN;

    /** |shear deltaTop| (m/s) if shear enabled, else NaN. */
    public double shearDelta_mps = Double.NaN;

    /** Approximate impulse: integral |delta wind| dt (m/s*s). */
    public double deltaWindImpulse_mps_s = Double.NaN;

    /** Proxy for weathercocking severity: max tilt from vertical (deg) based on velocity vector. */
    public double maxTilt_deg = Double.NaN;

    /** Max angle-of-attack if accessible (deg), else NaN. */
    public double maxAoA_deg = Double.NaN;

    void resetAccumulators() {
        gustCount = 0;
        maxDeltaWind_mps = Double.NaN;
        shearDelta_mps = Double.NaN;
        deltaWindImpulse_mps_s = 0.0;
        maxTilt_deg = Double.NaN;
        maxAoA_deg = Double.NaN;
    }
}
