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

    // Optional average wind speed override
    private boolean useAverageWindSpeed = false;
    private double averageWindSpeedMps = 0.0;      // m/s

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

        final String windModelType = resolveWindModelType(conditions, opts);
        final boolean isAverageWindModel = (windModelType != null) && windModelType.toLowerCase().contains("average");

        if (isAverageWindModel) {
            // Prefer setting values on SimulationConditions (24.12); fallback to opts setters if needed
            Object windTarget = hasMethod(conditions, "setWindSpeed", double.class) ? conditions : opts;

            // Mean speed: either override value or current model value
            double baseSpeed = useAverageWindSpeed ? Math.max(0.0, averageWindSpeedMps)
                    : invokeDouble(conditions, "getWindSpeed", invokeDouble(opts, "getWindSpeed", 0.0));

            // Sigma speed: read from the model/conditions if available (no plugin-specific wind-speed sigma)
            double sigmaSpeed = invokeDouble(conditions, "getWindStandardDeviation", Double.NaN);
            if (!Double.isFinite(sigmaSpeed)) {
                sigmaSpeed = invokeDouble(conditions, "getWindSpeedStandardDeviation", Double.NaN);
            }
            if (!Double.isFinite(sigmaSpeed)) {
                sigmaSpeed = invokeDouble(opts, "getWindStandardDeviation", Double.NaN);
            }
            if (!Double.isFinite(sigmaSpeed)) {
                sigmaSpeed = invokeDouble(opts, "getWindSpeedStandardDeviation", 0.0);
            }
            if (!Double.isFinite(sigmaSpeed) || sigmaSpeed < 0.0) sigmaSpeed = 0.0;

            double variedSpeed = baseSpeed;
            if (sigmaSpeed > 0.0) {
                variedSpeed = Math.max(0.0, baseSpeed + rng.nextGaussian() * sigmaSpeed);
            }

            // Even if sigma=0, still apply override if enabled (avoid falling back to 2.0 m/s defaults)
            if (useAverageWindSpeed || sigmaSpeed > 0.0) {
                invokeVoidDouble(windTarget, "setWindSpeed", variedSpeed);
                if (debugEnabled) {
                    log.debug("MC Average wind speed: base={} m/s, σ(model)={} m/s, varied={} m/s (target={})",
                            baseSpeed, sigmaSpeed, variedSpeed, windTarget.getClass().getSimpleName());
                }
            }

            // Direction perturbation (direction is stored in radians internally)
            if (windDirectionStdDevDeg > 0.0) {
                double baseDirRad = invokeDouble(conditions, "getWindDirection",
                        invokeDouble(opts, "getWindDirection", 0.0));

                double variedDeg = Math.toDegrees(baseDirRad) + rng.nextGaussian() * windDirectionStdDevDeg;
                double variedRad = Math.toRadians(variedDeg);

                invokeVoidDouble(windTarget, "setWindDirection", variedRad);

                if (debugEnabled) {
                    log.debug("MC Average wind direction: base={} deg, σ={} deg, varied={} deg (target={})",
                            Math.toDegrees(baseDirRad), windDirectionStdDevDeg, variedDeg, windTarget.getClass().getSimpleName());
                }
            }

        } else {
            // --- Wind model: MultiLevel ---
            MultiLevelPinkNoiseWindModel ml = resolveMultiLevelWindModel(conditions, opts);
            if (ml != null) {
                final double sigmaDirRad = Math.toRadians(windDirectionStdDevDeg);

                for (MultiLevelPinkNoiseWindModel.LevelWindModel level : ml.getLevels()) {
                    // Speed in m/s: use per-level sigma (Waterloo-style)
                    double sigma = level.getStandardDeviation();
                    if (Double.isFinite(sigma) && sigma > 0.0) {
                        double base = level.getSpeed();
                        double varied = base + rng.nextGaussian() * sigma;
                        if (varied < 0.0) varied = 0.0;
                        level.setSpeed(varied);

                        if (debugEnabled) {
                            log.debug("MC Wind @ {} m: baseSpeed={} m/s, σ(level)={} m/s, variedSpeed={} m/s",
                                    level.getAltitude(), base, sigma, varied);
                        }
                    }

                    // Direction in radians
                    if (windDirectionStdDevDeg > 0.0) {
                        double baseDir = level.getDirection();
                        double variedDir = baseDir + rng.nextGaussian() * sigmaDirRad;
                        level.setDirection(variedDir);

                        if (debugEnabled) {
                            log.debug("MC Wind @ {} m: baseDir={} deg, σ={} deg, variedDir={} deg",
                                    level.getAltitude(), Math.toDegrees(baseDir), windDirectionStdDevDeg, Math.toDegrees(variedDir));
                        }
                    }
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
    public boolean isUseAverageWindSpeed() { return useAverageWindSpeed; }
    public void setUseAverageWindSpeed(boolean v) { useAverageWindSpeed = v; fireChangeEvent(); }

    public double getAverageWindSpeedMps() { return averageWindSpeedMps; }
    public void setAverageWindSpeedMps(double v) { averageWindSpeedMps = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

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
