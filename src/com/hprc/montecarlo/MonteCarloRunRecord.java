package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * One Monte Carlo run: stores the actual options used (after perturbation) + extracted results.
 */
public final class MonteCarloRunRecord {

    public static final class WindLevel {
        public final double altitudeM;
        public final double speedMps;
        public final double directionRad;
        public final double stdDevMps;
        public final double turbIntensity;

        public WindLevel(double altitudeM, double speedMps, double directionRad, double stdDevMps, double turbIntensity) {
            this.altitudeM = altitudeM;
            this.speedMps = speedMps;
            this.directionRad = directionRad;
            this.stdDevMps = stdDevMps;
            this.turbIntensity = turbIntensity;
        }
    }

    public final int runIndex;              // 1..N
    public final String simulationName;

    // Seed info (for reproducibility)
    public final boolean deterministicSeed;
    public final long seedUsed;

    // Actual conditions used in this run (post-variation)
    public final double launchLatitudeDeg;
    public final double launchLongitudeDeg;
    public final double launchAltitudeM;

    public final double launchRodAngleRad;
    public final double launchRodDirectionRad;

    public final double launchTemperatureK;
    public final double launchPressurePa;

    // Wind sigmas configured for this run (plugin settings)
    public final double windSpeedAverageSigmaMps;
    public final double windSpeedTurbulenceSigmaMps;

    // Wind model type string (e.g., "Average", "MultiLevel", etc.)
    public final String windModelType;
    public final List<WindLevel> windLevels = new ArrayList<>();

    // Outputs (from SimulationData)
    public final SimulationData results;

    private double landingEastM = Double.NaN;
    private double landingNorthM = Double.NaN;
    private double landingLatDeg = Double.NaN;
    private double landingLonDeg = Double.NaN;

    public MonteCarloRunRecord(
            int runIndex,
            String simulationName,
            boolean deterministicSeed,
            long seedUsed,
            double windSpeedAverageSigmaMps,
            double windSpeedTurbulenceSigmaMps,
            SimulationOptions opts,
            SimulationData results
    ) {
        this.runIndex = runIndex;
        this.simulationName = simulationName;
        this.deterministicSeed = deterministicSeed;
        this.seedUsed = seedUsed;

        this.windSpeedAverageSigmaMps = windSpeedAverageSigmaMps;
        this.windSpeedTurbulenceSigmaMps = windSpeedTurbulenceSigmaMps;

        this.launchLatitudeDeg = opts.getLaunchLatitude();
        this.launchLongitudeDeg = opts.getLaunchLongitude();
        this.launchAltitudeM = opts.getLaunchAltitude();

        this.launchRodAngleRad = opts.getLaunchRodAngle();
        this.launchRodDirectionRad = opts.getLaunchRodDirection();

        this.launchTemperatureK = opts.getLaunchTemperature();
        this.launchPressurePa = opts.getLaunchPressure();

        this.windModelType = resolveWindModelType(opts);
        populateWindLevels(opts, this.windModelType, this.windLevels);

        this.results = results;
    }

    public double getLandingEastM() { return landingEastM; }
    public double getLandingNorthM() { return landingNorthM; }
    public double getLandingLatDeg() { return landingLatDeg; }
    public double getLandingLonDeg() { return landingLonDeg; }

    public void setLandingEastM(double v) { this.landingEastM = v; }
    public void setLandingNorthM(double v) { this.landingNorthM = v; }
    public void setLandingLatDeg(double v) { this.landingLatDeg = v; }
    public void setLandingLonDeg(double v) { this.landingLonDeg = v; }

    /**
     * OR 24.12: wind model is owned by SimulationConditions (typically opts.getConditions().getWindModel()).
     * We avoid compile-time dependence on specific wind model enums/types by using reflection.
     */
    private static String resolveWindModelType(SimulationOptions opts) {
        if (opts == null) return "";

        // Prefer: opts.getConditions().getWindModel()
        SimulationConditions cond = getConditions(opts);
        if (cond != null) {
            Object windModelObj = invokeObject(cond, "getWindModel");
            if (windModelObj != null) return windModelObj.getClass().getSimpleName();

            // Some builds expose "getWindModelType"
            Object windModelTypeObj = invokeObject(cond, "getWindModelType");
            if (windModelTypeObj != null) return windModelTypeObj.toString();
        }

        // Fallbacks if conditions not available / different API
        Object windModelObj2 = invokeObject(opts, "getWindModel");
        if (windModelObj2 != null) return windModelObj2.getClass().getSimpleName();

        Object windModelTypeObj2 = invokeObject(opts, "getWindModelType");
        if (windModelTypeObj2 != null) return windModelTypeObj2.toString();

        return "";
    }

    private static void populateWindLevels(SimulationOptions opts, String windModelType, List<WindLevel> out) {
        if (opts == null || out == null) return;

        final SimulationConditions cond = getConditions(opts);
        Object windModel = (cond != null) ? invokeObject(cond, "getWindModel") : null;
        if (windModel == null) windModel = invokeObject(opts, "getWindModel");

        // ---- Multi-level wind model (preferred) ----
        MultiLevelPinkNoiseWindModel ml = null;
        if (windModel instanceof MultiLevelPinkNoiseWindModel) {
            ml = (MultiLevelPinkNoiseWindModel) windModel;
        }
        if (ml == null && cond != null) {
            Object maybe = invokeObject(cond, "getMultiLevelWindModel");
            if (maybe instanceof MultiLevelPinkNoiseWindModel) ml = (MultiLevelPinkNoiseWindModel) maybe;
        }
        if (ml == null) {
            try { ml = opts.getMultiLevelWindModel(); } catch (Throwable ignored) { }
        }

        if (ml != null) {
            for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : ml.getLevels()) {
                double turb = invokeDouble(lvl, "getTurbulenceIntensity", Double.NaN);
                if (!Double.isFinite(turb)) turb = invokeDouble(lvl, "getTurbulence", Double.NaN);
                out.add(new WindLevel(
                        lvl.getAltitude(),
                        lvl.getSpeed(),
                        lvl.getDirection(),
                        lvl.getStandardDeviation(),
                        turb
                ));
            }
            return;
        }

        // ---- Scalar wind model (PinkNoise, Average, etc.) ----
        final Object src = (cond != null) ? cond : opts;

        double speed = Double.NaN;
        if (windModel != null) {
            speed = invokeDouble(windModel, "getWindSpeed", Double.NaN);
            if (!Double.isFinite(speed)) speed = invokeDouble(windModel, "getSpeed", Double.NaN);
            if (!Double.isFinite(speed)) speed = invokeDouble(windModel, "getAverageWindSpeed", Double.NaN);
        }
        if (!Double.isFinite(speed)) speed = invokeDouble(src, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(speed) || speed < 0.0) speed = 0.0;

        double dirRad = Double.NaN;
        if (windModel != null) {
            dirRad = invokeDouble(windModel, "getWindDirection", Double.NaN);
            if (!Double.isFinite(dirRad)) dirRad = invokeDouble(windModel, "getDirection", Double.NaN);
        }
        if (!Double.isFinite(dirRad)) dirRad = invokeDouble(src, "getWindDirection", Double.NaN);
        if (!Double.isFinite(dirRad)) dirRad = 0.0;

        double std = Double.NaN;
        if (windModel != null) {
            std = invokeDouble(windModel, "getWindStandardDeviation", Double.NaN);
            if (!Double.isFinite(std)) std = invokeDouble(windModel, "getWindSpeedStandardDeviation", Double.NaN);
            if (!Double.isFinite(std)) std = invokeDouble(windModel, "getStandardDeviation", Double.NaN);
        }
        if (!Double.isFinite(std)) std = invokeDouble(src, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(std)) std = invokeDouble(src, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(std) || std < 0.0) std = 0.0;

        double turb = Double.NaN;
        if (windModel != null) {
            turb = invokeDouble(windModel, "getTurbulenceIntensity", Double.NaN);
            if (!Double.isFinite(turb)) turb = invokeDouble(windModel, "getWindTurbulenceIntensity", Double.NaN);
         }
        if (!Double.isFinite(turb)) turb = invokeDouble(src, "getWindTurbulenceIntensity", Double.NaN);
        if (!Double.isFinite(turb)) turb = invokeDouble(src, "getWindTurbulence", Double.NaN);

        out.add(new WindLevel(0.0, speed, dirRad, std, turb));
    }

    private static SimulationConditions getConditions(SimulationOptions opts) {
        try {
            Object cond = invokeObject(opts, "getConditions");
            if (cond instanceof SimulationConditions) return (SimulationConditions) cond;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) return null;
        try {
            var m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double invokeDouble(Object target, String methodName, double fallback) {
        if (target == null) return fallback;
        try {
            var m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number n) return n.doubleValue();
            return fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
