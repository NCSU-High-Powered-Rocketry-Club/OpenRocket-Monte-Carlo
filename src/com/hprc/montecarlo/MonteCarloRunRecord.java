package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A single Monte Carlo run record. Stored to CSV by MonteCarloCsvExporter.
 *
 * Wind logging rules:
 * - If the active wind model is multi-level (MultiLevelPinkNoiseWindModel / MultiLevelWindModelType),
 *   log each level's (altitude, speed, direction, std-dev).
 * - Otherwise log a single "level" at altitude 0 using the scalar wind (speed, direction, std-dev).
 *
 * This avoids the common bug where opts.getMultiLevelWindModel() is always non-null even when not active.
 */
public class MonteCarloRunRecord {

    public final int runIndex;
    public final String simulationName;

    public final boolean deterministicSeed;
    public final long seedUsed;

    public final double windSpeedAverageSigma_mps;
    public final double windSpeedTurbulenceSigma_mps;

    // ---------------------------------------------------------------------
    // Gust / Shear configuration (what the extension was asked to do)
    // ---------------------------------------------------------------------

    public final boolean gustEventsEnabled;
    public final boolean shearLayerEnabled;

    public final int gustEventCountConfigured;
    public final double gustWindowStart_s;
    public final double gustWindowEnd_s;
    public final double gustDurationMean_s;
    public final double gustDurationSigma_s;
    public final double gustPeakDeltaMean_mps;
    public final double gustPeakDeltaSigma_mps;

    public final double shearCenterAlt_m;
    public final double shearThickness_m;
    public final double shearDeltaMean_mps;
    public final double shearDeltaSigma_mps;

    // ---------------------------------------------------------------------
    // Gust / Shear metrics (what actually happened during the run)
    // ---------------------------------------------------------------------

    public final int gustEventCountRealized;
    public final double gustMaxDeltaWind_mps;
    public final double shearDeltaApplied_mps;
    public final double deltaWindImpulse_mps_s;
    public final double maxTilt_deg;
    public final double maxAoA_deg;

    // ---------------------------------------------------------------------
    // Vehicle / Motor physics overrides (config + realized)
    // ---------------------------------------------------------------------

    /** Configured CD multiplier sigma (0 = disabled) */
    public final double cdMultiplierSigma;
    /** Configured thrust multiplier sigma (0 = disabled) */
    public final double thrustMultiplierSigma;
    /** Configured mass multiplier sigma (0 = disabled) */
    public final double massMultiplierSigma;

    /** Actual CD multiplier used for this run (1.0 if disabled) */
    public final double cdMultiplierUsed;
    /** Actual thrust multiplier used for this run (1.0 if disabled) */
    public final double thrustMultiplierUsed;
    /** Actual mass multiplier used for this run (1.0 if disabled) */
    public final double massMultiplierUsed;

    public final String windModelType;
    public final List<WindLevel> windLevels = new ArrayList<>();

    public final double apogee_m;
    public final double maxVelocity_mps;
    public final double maxAcceleration_mps2;

    public final double flightTime_s;

    public final double landingEast_m;
    public final double landingNorth_m;
    public final double landingLat_deg;
    public final double landingLon_deg;

    // Extra values for downstream tools (optional)
    private double landingEastM = Double.NaN;
    private double landingNorthM = Double.NaN;
    private double landingLatDeg = Double.NaN;
    private double landingLonDeg = Double.NaN;

    // -------------------------------------------------------------------------
    // Additional fields for CSV export (launch conditions and atmosphere)
    // -------------------------------------------------------------------------

    /** SimulationData reference for detailed CSV export */
    public final SimulationData results;

    /** Launch latitude in degrees */
    public final double launchLatitudeDeg;

    /** Launch longitude in degrees */
    public final double launchLongitudeDeg;

    /** Launch altitude in meters */
    public final double launchAltitudeM;

    /** Launch rod angle in radians */
    public final double launchRodAngleRad;

    /** Launch rod direction in radians */
    public final double launchRodDirectionRad;

    /** Launch temperature in Kelvin */
    public final double launchTemperatureK;

    /** Launch pressure in Pascals */
    public final double launchPressurePa;

    /** Wind speed average sigma in m/s (alias for windSpeedAverageSigma_mps) */
    public final double windSpeedAverageSigmaMps;

    /** Wind speed turbulence sigma in m/s (alias for windSpeedTurbulenceSigma_mps) */
    public final double windSpeedTurbulenceSigmaMps;

    public MonteCarloRunRecord(
            int runIndex,
            String simulationName,
            boolean deterministicSeed,
            long seedUsed,
            double windSpeedAverageSigma_mps,
            double windSpeedTurbulenceSigma_mps,

            boolean gustEventsEnabled,
            boolean shearLayerEnabled,
            int gustEventCountConfigured,
            double gustWindowStart_s,
            double gustWindowEnd_s,
            double gustDurationMean_s,
            double gustDurationSigma_s,
            double gustPeakDeltaMean_mps,
            double gustPeakDeltaSigma_mps,
            double shearCenterAlt_m,
            double shearThickness_m,
            double shearDeltaMean_mps,
            double shearDeltaSigma_mps,

            int gustEventCountRealized,
            double gustMaxDeltaWind_mps,
            double shearDeltaApplied_mps,
            double deltaWindImpulse_mps_s,
            double maxTilt_deg,
            double maxAoA_deg,

            double cdMultiplierSigma,
            double thrustMultiplierSigma,
            double massMultiplierSigma,
            double cdMultiplierUsed,
            double thrustMultiplierUsed,
            double massMultiplierUsed,

            SimulationOptions opts,
            SimulationData data
    ) {
        this.runIndex = runIndex;
        this.simulationName = simulationName;

        this.deterministicSeed = deterministicSeed;
        this.seedUsed = seedUsed;

        this.windSpeedAverageSigma_mps = windSpeedAverageSigma_mps;
        this.windSpeedTurbulenceSigma_mps = windSpeedTurbulenceSigma_mps;

        // Gust/shear config
        this.gustEventsEnabled = gustEventsEnabled;
        this.shearLayerEnabled = shearLayerEnabled;
        this.gustEventCountConfigured = gustEventCountConfigured;
        this.gustWindowStart_s = gustWindowStart_s;
        this.gustWindowEnd_s = gustWindowEnd_s;
        this.gustDurationMean_s = gustDurationMean_s;
        this.gustDurationSigma_s = gustDurationSigma_s;
        this.gustPeakDeltaMean_mps = gustPeakDeltaMean_mps;
        this.gustPeakDeltaSigma_mps = gustPeakDeltaSigma_mps;
        this.shearCenterAlt_m = shearCenterAlt_m;
        this.shearThickness_m = shearThickness_m;
        this.shearDeltaMean_mps = shearDeltaMean_mps;
        this.shearDeltaSigma_mps = shearDeltaSigma_mps;

        // Gust/shear metrics
        this.gustEventCountRealized = gustEventCountRealized;
        this.gustMaxDeltaWind_mps = gustMaxDeltaWind_mps;
        this.shearDeltaApplied_mps = shearDeltaApplied_mps;
        this.deltaWindImpulse_mps_s = deltaWindImpulse_mps_s;
        this.maxTilt_deg = maxTilt_deg;
        this.maxAoA_deg = maxAoA_deg;

        // Physics overrides
        this.cdMultiplierSigma = cdMultiplierSigma;
        this.thrustMultiplierSigma = thrustMultiplierSigma;
        this.massMultiplierSigma = massMultiplierSigma;
        this.cdMultiplierUsed = cdMultiplierUsed;
        this.thrustMultiplierUsed = thrustMultiplierUsed;
        this.massMultiplierUsed = massMultiplierUsed;

        // Aliases for CSV exporter compatibility
        this.windSpeedAverageSigmaMps = windSpeedAverageSigma_mps;
        this.windSpeedTurbulenceSigmaMps = windSpeedTurbulenceSigma_mps;

        final SimulationConditions cond = getConditions(opts);

        this.windModelType = resolveWindModelType(cond, opts);
        populateWindLevels(cond, opts, this.windModelType, this.windLevels);

        this.apogee_m = data.apogee_m;
        this.maxVelocity_mps = data.maxVelocity_mps;
        this.maxAcceleration_mps2 = data.maxAcceleration_mps2;
        this.flightTime_s = data.flightTime_s;

        this.landingEast_m = data.landingEast_m;
        this.landingNorth_m = data.landingNorth_m;
        this.landingLat_deg = data.landingLat_deg;
        this.landingLon_deg = data.landingLon_deg;

        // Store SimulationData reference for CSV export
        this.results = data;

        // Capture launch conditions from SimulationOptions
        this.launchLatitudeDeg = opts.getLaunchLatitude();
        this.launchLongitudeDeg = opts.getLaunchLongitude();
        this.launchAltitudeM = opts.getLaunchAltitude();
        this.launchRodAngleRad = opts.getLaunchRodAngle();
        this.launchRodDirectionRad = opts.getLaunchRodDirection();
        this.launchTemperatureK = opts.getLaunchTemperature();
        this.launchPressurePa = opts.getLaunchPressure();
    }

    // For compatibility with existing code
    public void setLandingEastM(double v) { this.landingEastM = v; }
    public void setLandingNorthM(double v) { this.landingNorthM = v; }
    public void setLandingLatDeg(double v) { this.landingLatDeg = v; }
    public void setLandingLonDeg(double v) { this.landingLonDeg = v; }

    public double getLandingEastM() { return landingEastM; }
    public double getLandingNorthM() { return landingNorthM; }
    public double getLandingLatDeg() { return landingLatDeg; }
    public double getLandingLonDeg() { return landingLonDeg; }

    // -------------------------------------------------------------------------
    // Wind capture
    // -------------------------------------------------------------------------

    private static String resolveWindModelType(SimulationConditions cond, SimulationOptions opts) {
        Object type = invokeObject(cond, "getWindModelType");
        if (type == null) type = invokeObject(opts, "getWindModelType");
        if (type != null) return String.valueOf(type);

        Object model = invokeObject(cond, "getWindModel");
        if (model == null) model = invokeObject(opts, "getWindModel");
        return (model != null) ? model.getClass().getSimpleName() : "";
    }

    private static void populateWindLevels(SimulationConditions cond,
                                          SimulationOptions opts,
                                          String windModelType,
                                          List<WindLevel> out) {
        out.clear();

        Object model = invokeObject(cond, "getWindModel");
        if (model == null) model = invokeObject(opts, "getWindModel");

        boolean multiActive = (model instanceof MultiLevelPinkNoiseWindModel);
        if (!multiActive && windModelType != null) {
            String s = windModelType.toLowerCase();
            multiActive = s.contains("multi") || s.contains("level");
        }

        if (multiActive) {
            MultiLevelPinkNoiseWindModel ml = null;
            if (model instanceof MultiLevelPinkNoiseWindModel m) ml = m;

            if (ml == null) {
                Object maybe = invokeObject(cond, "getMultiLevelWindModel");
                if (maybe instanceof MultiLevelPinkNoiseWindModel m) ml = m;
            }
            if (ml == null) {
                try {
                    ml = opts.getMultiLevelWindModel();
                } catch (Throwable ignored) { }
            }

            if (ml != null) {
                for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : ml.getLevels()) {
                    out.add(new WindLevel(
                            lvl.getAltitude(),
                            lvl.getSpeed(),
                            lvl.getDirection(),
                            safeStdDev(lvl)
                    ));
                }
                return;
            }
        }

        // Scalar fallback: log one "level" at altitude 0
        double speed = readScalarSpeed(cond, opts, model);
        double dir = readScalarDirection(cond, opts, model);
        double std = readScalarStdDev(cond, opts, model);

        out.add(new WindLevel(0.0, speed, dir, std));
    }

    private static double safeStdDev(MultiLevelPinkNoiseWindModel.LevelWindModel lvl) {
        try {
            return lvl.getStandardDeviation();
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private static double readScalarSpeed(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = readDouble(model, "getWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(model, "getSpeed");
        if (!Double.isFinite(v)) v = readDouble(model, "getAverageWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(model, "getAverageWindspeed");
        if (!Double.isFinite(v)) v = readDouble(cond, "getWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(cond, "getAverageWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(cond, "getAverageWindspeed");
        if (!Double.isFinite(v)) v = readDouble(opts, "getWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(opts, "getAverageWindSpeed");
        if (!Double.isFinite(v)) v = readDouble(opts, "getAverageWindspeed");
        if (!Double.isFinite(v) || v < 0.0) v = 0.0;
        return v;
    }

    private static double readScalarDirection(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = readDouble(model, "getWindDirection");
        if (!Double.isFinite(v)) v = readDouble(model, "getDirection");
        if (!Double.isFinite(v)) v = readDouble(cond, "getWindDirection");
        if (!Double.isFinite(v)) v = readDouble(opts, "getWindDirection");
        if (!Double.isFinite(v)) v = 0.0;
        return v;
    }

    private static double readScalarStdDev(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = readDouble(model, "getWindStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(model, "getWindSpeedStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(model, "getStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(model, "getStdDev");
        if (!Double.isFinite(v)) v = readDouble(cond, "getWindStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(cond, "getWindSpeedStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(opts, "getWindStandardDeviation");
        if (!Double.isFinite(v)) v = readDouble(opts, "getWindSpeedStandardDeviation");
        if (!Double.isFinite(v) || v < 0.0) v = 0.0;
        return v;
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private static SimulationConditions getConditions(SimulationOptions opts) {
        if (opts == null) return null;
        Object cond = invokeObject(opts, "getConditions");
        return (cond instanceof SimulationConditions sc) ? sc : null;
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

    private static double readDouble(Object target, String methodName) {
        if (target == null) return Double.NaN;
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return (v instanceof Number n) ? n.doubleValue() : Double.NaN;
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    // -------------------------------------------------------------------------
    // Data type
    // -------------------------------------------------------------------------

    public static class WindLevel {
        // Keep legacy field names used by the CSV exporter
        public final double altitudeM;
        public final double speedMps;
        public final double directionRad;
        public final double stdDevMps;
        public final double turbIntensity;

        public WindLevel(double altitudeM, double speedMps, double directionRad, double stdDevMps) {
            this.altitudeM = altitudeM;
            this.speedMps = speedMps;
            this.directionRad = directionRad;
            this.stdDevMps = stdDevMps;
            this.turbIntensity = (speedMps > 1e-9) ? (stdDevMps / speedMps) : 0.0;
        }
    }
}
