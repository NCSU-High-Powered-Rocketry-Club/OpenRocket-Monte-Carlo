package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * OpenRocket Simulation Extension entry point for the "HPRC Monte Carlo" plugin.
 *
 * This extension is configured through the Simulation Extensions tab (via MonteCarloConfigurator)
 * and applies random perturbations to the simulation at run start.
 */
public class MonteCarloExtension extends AbstractSimulationExtension {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloExtension.class);

    private static final double DEFAULT_T0_K = 288.15;
    private static final double DEFAULT_P0_PA = 101325.0;

    // -------------------------------------------------------------------------
    // UI / stored properties (saved in the .ork file)
    // -------------------------------------------------------------------------

    private boolean enabled = true;
    private boolean debugEnabled = false;

    // Monte Carlo run controls (batch count is for future batch-run UX inside OR)
    private int numberOfSimulations = 100;

    // Reproducibility
    private boolean useDeterministicSeed = false;
    private long randomSeed = 1L;

    // Launch / orientation variation
    private double launchRodAngleStdDevDeg = 0.0;      // rail angle (pitch)
    private double launchRodDirectionStdDevDeg = 0.0;  // rail direction (heading)

    // Launch coordinates variation
    private double launchLatitudeStdDevDeg = 0.0;
    private double launchLongitudeStdDevDeg = 0.0;
    private double launchAltitudeStdDevM = 0.0;
    // Atmosphere / wind variation
    private double windDirectionStdDevDeg = 0.0;   // degrees
    private double temperatureStdDevC = 0.0;       // degC (delta == delta K)
    private double pressureStdDevMbar = 0.0;       // mbar (1 mbar = 100 Pa)

    // Wind speed variation controls
    // - windSpeedAverageSigmaMps: perturbs the MEAN wind speed per Monte Carlo run
    // - windSpeedTurbulenceSigmaMps: sets the wind model's internal turbulence/gust std dev
    private double windSpeedAverageSigmaMps = 0.0;     // m/s
    private double windSpeedTurbulenceSigmaMps = 0.0;  // m/s

    // Batch execution (JVM threads)
    private int workerThreads = 1;

    @Override
    public void initialize(final SimulationConditions conditions) throws SimulationException {
        if (!enabled) return;

        // Resolve options (some fields still live in SimulationOptions in 24.12)
        SimulationOptions opts = resolveOptions(conditions);

        // RNG
        Random rng = useDeterministicSeed ? new Random(randomSeed) : new Random();

        // If user wants manual pressure/temp variation, ensure ISA is disabled AND manual fields are valid.
        if ((temperatureStdDevC > 0.0 || pressureStdDevMbar > 0.0) && opts.isISAAtmosphere()) {
            opts.setISAAtmosphere(false);
        }
        if (!opts.isISAAtmosphere()) {
            // Ensure physical defaults if missing/zero
            double t = opts.getLaunchTemperature();
            if (!Double.isFinite(t) || t <= 0.0) {
                opts.setLaunchTemperature(DEFAULT_T0_K);
            }
            double p = opts.getLaunchPressure();
            if (!Double.isFinite(p) || p <= 0.0) {
                opts.setLaunchPressure(DEFAULT_P0_PA);
            }
        }

        // --- Launch rod angle/direction (stored internally in radians in OR) ---
        if (launchRodAngleStdDevDeg > 0) {
            double base = opts.getLaunchRodAngle();
            double varied = base + Math.toRadians(rng.nextGaussian() * launchRodAngleStdDevDeg);
            opts.setLaunchRodAngle(varied);
        }

        if (launchRodDirectionStdDevDeg > 0) {
            double base = opts.getLaunchRodDirection();
            double varied = base + Math.toRadians(rng.nextGaussian() * launchRodDirectionStdDevDeg);
            opts.setLaunchRodDirection(varied);
        }

        // --- Launch coordinates ---
        if (launchLatitudeStdDevDeg > 0) {
            double base = opts.getLaunchLatitude();
            double varied = clamp(base + rng.nextGaussian() * launchLatitudeStdDevDeg, -90.0, 90.0);
            opts.setLaunchLatitude(varied);
        }

        if (launchLongitudeStdDevDeg > 0) {
            double base = opts.getLaunchLongitude();
            double varied = wrapLongitudeDeg(base + rng.nextGaussian() * launchLongitudeStdDevDeg);
            opts.setLaunchLongitude(varied);
        }

        if (launchAltitudeStdDevM > 0) {
            double base = opts.getLaunchAltitude();
            double varied = Math.max(-500.0, base + rng.nextGaussian() * launchAltitudeStdDevM);
            opts.setLaunchAltitude(varied);
        }

        // ---------------------------------------------------------------------
        // WIND (Waterloo-style)
        //
        // Goal: match Waterloo Rocketry's approach:
        //  - Do NOT vary rocket mass (no mass overrides) or initial velocity.
        //  - Wind speed variation comes from the WIND MODEL itself:
        //      * Average model: use the model's own wind-speed std dev (if present)
        //      * MultiLevel model: for each level, use level.getStandardDeviation()
        //  - Wind direction variation is applied as a global sigma (this extension),
        //    in degrees, converted to radians internally.
        //
        // IMPORTANT (OR 24.12): the active wind model often lives on SimulationConditions,
        // not the SimulationOptions object. Always resolve and mutate the ACTIVE model.
        // ---------------------------------------------------------------------

        // Wind model object (often owned by SimulationConditions in OR 24.12)
        final Object windModel = resolveWindModel(conditions, opts);
        MultiLevelPinkNoiseWindModel ml = resolveMultiLevelWindModel(conditions, opts);

        final double avgSigmaMps = Math.max(0.0, finiteOrZero(windSpeedAverageSigmaMps));
        final double turbSigmaMps = Math.max(0.0, finiteOrZero(windSpeedTurbulenceSigmaMps));
        final double dirSigmaRad = Math.toRadians(Math.max(0.0, finiteOrZero(windDirectionStdDevDeg)));

        if (ml != null) {
            // -----------------------------
            // Multi-level wind profile
            // -----------------------------

            // A) Mean wind speed variation: shift ALL levels by the same delta for this run.
            if (avgSigmaMps > 0.0) {
                final double delta = rng.nextGaussian() * avgSigmaMps;
                for (MultiLevelPinkNoiseWindModel.LevelWindModel level : ml.getLevels()) {
                    double base = level.getSpeed();
                    double varied = Math.max(0.0, base + delta);
                    level.setSpeed(varied);
                    if (debugEnabled) {
                        log.debug("MC Wind(mean) @ {} m: baseSpeed={} m/s, sigmaMean={} m/s, variedSpeed={} m/s",
                                level.getAltitude(), base, avgSigmaMps, varied);
                    }
                }
            }

            // B) Turbulence std dev: perturb the per-level turbulence.
            //    We calculate ONE delta to shift the day's turbulence globally (calmer/gustier day).
            if (turbSigmaMps > 0.0) {
                final double deltaTurb = rng.nextGaussian() * turbSigmaMps;
                for (MultiLevelPinkNoiseWindModel.LevelWindModel level : ml.getLevels()) {
                    double baseTurb = level.getStandardDeviation(); // This is the level's turbulence setting
                    double varied = Math.max(0.0, baseTurb + deltaTurb);
                    try {
                        level.setStandardDeviation(varied);
                    } catch (Throwable ignored) { }
                    
                    if (debugEnabled) {
                        log.debug("MC Wind(turb) @ {} m: baseTurb={} m/s, sigmaTurb={} m/s, variedTurb={} m/s",
                                level.getAltitude(), baseTurb, turbSigmaMps, varied);
                    }
                }
            }

            // Direction perturbation: Shift ALL levels by same random rotation.
            // (Previously this was inside the loop, creating chaotic shear)
            if (dirSigmaRad > 0.0) {
                final double deltaDir = rng.nextGaussian() * dirSigmaRad;
                for (MultiLevelPinkNoiseWindModel.LevelWindModel level : ml.getLevels()) {
                    double baseDir = level.getDirection();
                    double variedDir = baseDir + deltaDir;
                    level.setDirection(variedDir);
                    if (debugEnabled) {
                        log.debug("MC Wind(dir) @ {} m: baseDir={} deg, delta={} deg, variedDir={} deg",
                                level.getAltitude(), Math.toDegrees(baseDir), Math.toDegrees(deltaDir), Math.toDegrees(variedDir));
                    }
                }
            }

        } else {
            // -----------------------------
            // Scalar wind model (PinkNoise / Average / others)
            // -----------------------------

            // Prefer setting values on SimulationConditions (24.12); fallback to opts setters if needed
            final Object windTarget = hasMethod(conditions, "setWindSpeed", double.class) ? conditions : opts;

            // A) Mean wind speed variation (per-run)
            if (avgSigmaMps > 0.0) {
                double baseSpeed = getScalarWindSpeedMps(conditions, opts, windModel);
                double variedSpeed = Math.max(0.0, baseSpeed + rng.nextGaussian() * avgSigmaMps);
                invokeVoidDouble(windTarget, "setWindSpeed", variedSpeed);
                applyScalarWindSpeedToModel(windModel, variedSpeed);
                if (debugEnabled) {
                    log.debug("MC Wind(mean): baseSpeed={} m/s, sigmaMean={} m/s, variedSpeed={} m/s (target={})",
                            baseSpeed, avgSigmaMps, variedSpeed, windTarget.getClass().getSimpleName());
                }
            }

            // B) Turbulence variation
            if (turbSigmaMps > 0.0) {
                double baseTurb = getScalarWindTurbulenceMps(conditions, opts, windModel);
                double variedTurb = Math.max(0.0, baseTurb + rng.nextGaussian() * turbSigmaMps);

                boolean set = tryInvokeVoidDouble(windTarget, "setWindStandardDeviation", variedTurb);
                if (!set) {
                    set = tryInvokeVoidDouble(windTarget, "setWindSpeedStandardDeviation", variedTurb);
                }
                applyScalarWindStdDevToModel(windModel, variedTurb);
                if (debugEnabled) {
                    log.debug("MC Wind(turb): baseTurb={} m/s, sigmaTurb={} m/s, variedTurb={} m/s",
                            baseTurb, turbSigmaMps, variedTurb);
                }
            }

            // Direction perturbation (direction stored in radians)
            if (dirSigmaRad > 0.0) {
                double baseDirRad = getScalarWindDirectionRad(conditions, opts, windModel);
                double variedDir = baseDirRad + rng.nextGaussian() * dirSigmaRad;
                invokeVoidDouble(windTarget, "setWindDirection", variedDir);
                applyScalarWindDirectionToModel(windModel, variedDir);
                if (debugEnabled) {
                    log.debug("MC Wind(dir): baseDir={} deg, sigmaDir={} deg, variedDir={} deg (target={})",
                            Math.toDegrees(baseDirRad), windDirectionStdDevDeg, Math.toDegrees(variedDir), windTarget.getClass().getSimpleName());
                }
            }
        }

        // --- Temperature/pressure at launch ---
        if (temperatureStdDevC > 0.0) {
            double baseK = opts.getLaunchTemperature();
            if (!Double.isFinite(baseK) || baseK <= 0.0) baseK = DEFAULT_T0_K;

            // clamp to something physically sane
            double sigmaK = clamp(temperatureStdDevC, 0.0, 50.0);
            double variedK = baseK + rng.nextGaussian() * sigmaK;
            variedK = clamp(variedK, 180.0, 330.0);

            opts.setLaunchTemperature(variedK);
        }

        if (pressureStdDevMbar > 0.0) {
            double basePa = opts.getLaunchPressure();
            if (!Double.isFinite(basePa) || basePa <= 0.0) basePa = DEFAULT_P0_PA;

            double sigmaMbar = clamp(pressureStdDevMbar, 0.0, 200.0);
            double variedPa = basePa + rng.nextGaussian() * (sigmaMbar * 100.0);
            variedPa = clamp(variedPa, 50_000.0, 120_000.0);

            opts.setLaunchPressure(variedPa);
        }

        // NOTE:
        // Do NOT call Simulation.setOptions(...) or Simulation.copySimulationOptionsFrom(...) from here.
        // In OR 24.12, SimulationConditions/SimulationOptions are tightly coupled to the active
        // FlightConfiguration (motor selection). For some builds, re-copying options at this stage can
        // cause OpenRocket to re-validate and fall back to "[No motors]", which ends the simulation at t=0.
        // We only mutate the live SimulationConditions / Options objects that OpenRocket provides.
    }

    // -------------------------------------------------------------------------
    // Resolve SimulationOptions (API differs between OR versions)
    // -------------------------------------------------------------------------

    private static SimulationOptions resolveOptions(SimulationConditions conditions) throws SimulationException {
        // 1) conditions.getOptions()
        try {
            Method m = conditions.getClass().getMethod("getOptions");
            Object v = m.invoke(conditions);
            if (v instanceof SimulationOptions so) return so;
        } catch (Exception ignored) { }

        // 2) conditions.getSimulation().getOptions()
        try {
            Method m = conditions.getClass().getMethod("getSimulation");
            Object sim = m.invoke(conditions);
            if (sim != null) {
                Method mo = sim.getClass().getMethod("getOptions");
                Object v = mo.invoke(sim);
                if (v instanceof SimulationOptions so) return so;
            }
        } catch (Exception ignored) { }

        throw new SimulationException("Cannot resolve SimulationOptions from SimulationConditions in this OpenRocket version.");
    }

    // (intentionally no "sync back" helper; see note above)

    // -------------------------------------------------------------------------
    // Wind helpers (24.12-safe: avoid compile-time dependency on wind model enums)
    // -------------------------------------------------------------------------

    private static String resolveWindModelType(SimulationConditions conditions, SimulationOptions opts) {
        // Prefer conditions.getWindModel()
        Object wm = invokeObject(conditions, "getWindModel");
        if (wm != null) return wm.toString();

        // Fallbacks
        Object wmt = invokeObject(conditions, "getWindModelType");
        if (wmt != null) return wmt.toString();

        Object wm2 = invokeObject(opts, "getWindModel");
        if (wm2 != null) return wm2.toString();

        Object wmt2 = invokeObject(opts, "getWindModelType");
        if (wmt2 != null) return wmt2.toString();

        return "";
    }

    private static MultiLevelPinkNoiseWindModel resolveMultiLevelWindModel(SimulationConditions conditions, SimulationOptions opts) {
        // Prefer conditions.getMultiLevelWindModel()
        Object maybe = invokeObject(conditions, "getMultiLevelWindModel");
        if (maybe instanceof MultiLevelPinkNoiseWindModel ml) return ml;

        // Fallback to opts.getMultiLevelWindModel()
        try {
            return opts.getMultiLevelWindModel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolve the active wind model object (scalar or multilevel). In OR 24.12 this typically
     * lives on SimulationConditions, but older builds may expose it on SimulationOptions.
     */
    private static Object resolveWindModel(SimulationConditions conditions, SimulationOptions opts) {
        Object wm = invokeObject(conditions, "getWindModel");
        if (wm != null) return wm;
        return invokeObject(opts, "getWindModel");
    }

    private static double getScalarWindSpeedMps(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        double s = invokeDouble(windModel, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(conditions, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(opts, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s) || s < 0.0) s = 0.0;
        return s;
    }

    private static double getScalarWindTurbulenceMps(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        // Try various property names for turbulence/stddev
        double t = invokeDouble(windModel, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(conditions, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(conditions, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(opts, "getWindStandardDeviation", Double.NaN);
        // Default to 0.0 (no turbulence) if not set
        if (!Double.isFinite(t) || t < 0.0) t = 0.0;
        return t;
    }

    private static double getScalarWindDirectionRad(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        double d = invokeDouble(windModel, "getWindDirection", Double.NaN);
        if (!Double.isFinite(d)) d = invokeDouble(windModel, "getDirection", Double.NaN);
        if (!Double.isFinite(d)) d = invokeDouble(conditions, "getWindDirection", Double.NaN);
        if (!Double.isFinite(d)) d = invokeDouble(opts, "getWindDirection", 0.0);
        if (!Double.isFinite(d)) d = 0.0;
        return d;
    }

    private static void applyScalarWindSpeedToModel(Object windModel, double speedMps) {
        if (windModel == null) return;
        if (tryInvokeVoidDouble(windModel, "setWindSpeed", speedMps)) return;
        if (tryInvokeVoidDouble(windModel, "setSpeed", speedMps)) return;
        if (tryInvokeVoidDouble(windModel, "setAverageWindSpeed", speedMps)) return;
        tryInvokeVoidDouble(windModel, "setMeanWindSpeed", speedMps);
    }

    private static void applyScalarWindDirectionToModel(Object windModel, double dirRad) {
        if (windModel == null) return;
        if (tryInvokeVoidDouble(windModel, "setWindDirection", dirRad)) return;
        tryInvokeVoidDouble(windModel, "setDirection", dirRad);
    }

    private static void applyScalarWindStdDevToModel(Object windModel, double stdMps) {
        if (windModel == null) return;
        if (tryInvokeVoidDouble(windModel, "setWindStandardDeviation", stdMps)) return;
        if (tryInvokeVoidDouble(windModel, "setWindSpeedStandardDeviation", stdMps)) return;
        if (tryInvokeVoidDouble(windModel, "setStandardDeviation", stdMps)) return;
        tryInvokeVoidDouble(windModel, "setStdDev", stdMps);
    }

    private static boolean tryInvokeVoidDouble(Object target, String methodName, double value) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) {
            return false;
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

    private static void invokeVoidDouble(Object target, String methodName, double value) {
        if (target == null) return;
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
        } catch (Exception ignored) {
            // Not available in this build / target
        }
    }

    private static boolean hasMethod(Object target, String methodName, Class<?>... params) {
        if (target == null) return false;
        try {
            target.getClass().getMethod(methodName, params);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double wrapLongitudeDeg(double lon) {
        double x = lon;
        while (x >= 180.0) x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "HPRC Monte Carlo";
    }

    @Override
    public String getDescription() {
        return "Monte Carlo variations for wind, launch rail, and atmosphere (configured in Simulation Extensions tab).";
    }

    // -------------------------------------------------------------------------
    // Bean properties (called by GUI/configurator; setters call fireChangeEvent)
    // -------------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; fireChangeEvent(); }

    public boolean isDebugEnabled() { return debugEnabled; }
    public void setDebugEnabled(boolean v) { debugEnabled = v; fireChangeEvent(); }

    public int getNumberOfSimulations() { return numberOfSimulations; }
    public void setNumberOfSimulations(int v) { numberOfSimulations = Math.max(1, v); fireChangeEvent(); }

    public boolean isUseDeterministicSeed() { return useDeterministicSeed; }
    public void setUseDeterministicSeed(boolean v) { useDeterministicSeed = v; fireChangeEvent(); }

    public long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(long v) { randomSeed = v; fireChangeEvent(); }

    public double getLaunchRodAngleStdDevDeg() { return launchRodAngleStdDevDeg; }
    public void setLaunchRodAngleStdDevDeg(double v) { launchRodAngleStdDevDeg = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getLaunchRodDirectionStdDevDeg() { return launchRodDirectionStdDevDeg; }
    public void setLaunchRodDirectionStdDevDeg(double v) { launchRodDirectionStdDevDeg = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getLaunchLatitudeStdDevDeg() { return launchLatitudeStdDevDeg; }
    public void setLaunchLatitudeStdDevDeg(double v) { launchLatitudeStdDevDeg = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getLaunchLongitudeStdDevDeg() { return launchLongitudeStdDevDeg; }
    public void setLaunchLongitudeStdDevDeg(double v) { launchLongitudeStdDevDeg = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getLaunchAltitudeStdDevM() { return launchAltitudeStdDevM; }
    public void setLaunchAltitudeStdDevM(double v) { launchAltitudeStdDevM = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    /**
     * Sigma used to vary the mean wind speed per Monte Carlo run (m/s).
     * This is intentionally separate from turbulence/gust sigma.
     */
    public double getWindSpeedAverageSigmaMps() { return windSpeedAverageSigmaMps; }
    public void setWindSpeedAverageSigmaMps(double v) { windSpeedAverageSigmaMps = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    /**
     * Sigma used to set the wind model's internal turbulence / gust standard deviation (m/s).
     */
    public double getWindSpeedTurbulenceSigmaMps() { return windSpeedTurbulenceSigmaMps; }
    public void setWindSpeedTurbulenceSigmaMps(double v) { windSpeedTurbulenceSigmaMps = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getWindDirectionStdDevDeg() { return windDirectionStdDevDeg; }
    public void setWindDirectionStdDevDeg(double v) { windDirectionStdDevDeg = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getTemperatureStdDevC() { return temperatureStdDevC; }
    public void setTemperatureStdDevC(double v) { temperatureStdDevC = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public double getPressureStdDevMbar() { return pressureStdDevMbar; }
    public void setPressureStdDevMbar(double v) { pressureStdDevMbar = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = Math.max(1, workerThreads); }

    private static double finiteOrZero(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}
