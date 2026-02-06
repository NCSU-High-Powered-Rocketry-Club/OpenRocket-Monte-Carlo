package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * OpenRocket Simulation Extension entry point for the "HPRC Monte Carlo" plugin.
 *
 * Configured via the Simulation Extensions tab (MonteCarloConfigurator).
 *
 * IMPORTANT BEHAVIOR:
 * - This extension must NOT mutate the user's base simulation when they click the normal "Run".
 * - Monte Carlo perturbations should only be applied for batch clones (MonteCarloBatchRunner).
 *
 * The batch runner will:
 *   1) clone the simulation (options + wind deep copy),
 *   2) clone extensions,
 *   3) set batchRunContext=true and a per-run seed on the cloned extension instance.
 *
 * Then OpenRocket calls initialize(...) for that cloned simulation run.
 */
public class MonteCarloExtension extends AbstractSimulationExtension {

    private static final Logger log = LoggerFactory.getLogger(MonteCarloExtension.class);

    private static final double DEFAULT_T0_K = 288.15;
    private static final double DEFAULT_P0_PA = 101325.0;

    // -------------------------------------------------------------------------
    // Persistent keys (stored inside OpenRocket's extension config map)
    // -------------------------------------------------------------------------

    private static final String CFG_PREFIX = "hprc_mc.";

    private static final String K_ENABLED = CFG_PREFIX + "enabled";
    private static final String K_DEBUG = CFG_PREFIX + "debugEnabled";
    private static final String K_NUM_SIMS = CFG_PREFIX + "numberOfSimulations";

    private static final String K_DETERMINISTIC = CFG_PREFIX + "useDeterministicSeed";
    private static final String K_RANDOM_SEED = CFG_PREFIX + "randomSeed";

    private static final String K_ROD_ANGLE_SIGMA_DEG = CFG_PREFIX + "launchRodAngleStdDevDeg";
    private static final String K_ROD_DIR_SIGMA_DEG   = CFG_PREFIX + "launchRodDirectionStdDevDeg";

    private static final String K_LAT_SIGMA_DEG = CFG_PREFIX + "launchLatitudeStdDevDeg";
    private static final String K_LON_SIGMA_DEG = CFG_PREFIX + "launchLongitudeStdDevDeg";
    private static final String K_ALT_SIGMA_M   = CFG_PREFIX + "launchAltitudeStdDevM";

    private static final String K_WIND_DIR_SIGMA_DEG = CFG_PREFIX + "windDirectionStdDevDeg";
    private static final String K_TEMP_SIGMA_C       = CFG_PREFIX + "temperatureStdDevC";
    private static final String K_PRES_SIGMA_MBAR    = CFG_PREFIX + "pressureStdDevMbar";

    private static final String K_WIND_AVG_SIGMA_MPS  = CFG_PREFIX + "windSpeedAverageSigmaMps";
    private static final String K_WIND_TURB_SIGMA_MPS = CFG_PREFIX + "windSpeedTurbulenceSigmaMps";

    private static final String K_WORKER_THREADS = CFG_PREFIX + "workerThreads";

    // -------------------------------------------------------------------------
    // Cached values (mirrors config for quick access)
    // -------------------------------------------------------------------------

    private boolean enabled = true;
    private boolean debugEnabled = false;
    private int numberOfSimulations = 100;

    private boolean useDeterministicSeed = false;
    private long randomSeed = 1L;

    private double launchRodAngleStdDevDeg = 0.0;
    private double launchRodDirectionStdDevDeg = 0.0;

    private double launchLatitudeStdDevDeg = 0.0;
    private double launchLongitudeStdDevDeg = 0.0;
    private double launchAltitudeStdDevM = 0.0;

    private double windDirectionStdDevDeg = 0.0;
    private double temperatureStdDevC = 0.0;
    private double pressureStdDevMbar = 0.0;

    private double windSpeedAverageSigmaMps = 0.0;
    private double windSpeedTurbulenceSigmaMps = 0.0;

    private int workerThreads = 1;

    // -------------------------------------------------------------------------
    // Batch-only controls (NOT persisted)
    // -------------------------------------------------------------------------

    private transient boolean batchRunContext = false;
    private transient long batchSeed = Long.MIN_VALUE;
    private transient long effectiveSeedUsed = Long.MIN_VALUE;

    /** Called by MonteCarloBatchRunner on cloned extensions only. */
    public void setBatchRunContext(boolean v) { this.batchRunContext = v; }

    /** Called by MonteCarloBatchRunner on cloned extensions only. */
    public void setBatchSeed(long seed) { this.batchSeed = seed; }

    /** For logging / CSV. */
    public long getEffectiveSeedUsed() { return effectiveSeedUsed; }

    // -------------------------------------------------------------------------
    // OpenRocket entry point
    // -------------------------------------------------------------------------

    @Override
    public void initialize(final SimulationConditions conditions) throws SimulationException {
        // Refresh cached values from config map (so .ork persistence works even across reloads)
        reloadFromConfig();

        if (!enabled) return;

        // CRITICAL: never perturb when user is running a normal single sim from the UI.
        // Only MonteCarloBatchRunner sets this true for cloned sims.
        if (!batchRunContext) return;

        // Resolve options
        final SimulationOptions opts = resolveOptions(conditions);

        // Per-run RNG (batch runner provides seed; fallback to deterministic base or random)
        final long seed = (batchSeed != Long.MIN_VALUE)
                ? batchSeed
                : (useDeterministicSeed ? randomSeed : new Random().nextLong());
        this.effectiveSeedUsed = seed;
        final Random rng = new Random(seed);

        // ---- Atmosphere: if varying temp/pressure, disable ISA and ensure sane defaults ----
        if ((temperatureStdDevC > 0.0 || pressureStdDevMbar > 0.0) && opts.isISAAtmosphere()) {
            opts.setISAAtmosphere(false);
        }
        if (!opts.isISAAtmosphere()) {
            double t = opts.getLaunchTemperature();
            if (!Double.isFinite(t) || t <= 0.0) opts.setLaunchTemperature(DEFAULT_T0_K);
            double p = opts.getLaunchPressure();
            if (!Double.isFinite(p) || p <= 0.0) opts.setLaunchPressure(DEFAULT_P0_PA);
        }

        // ---- Launch rail angle/direction (stored in radians in OR) ----
        if (launchRodAngleStdDevDeg > 0) {
            double base = opts.getLaunchRodAngle();
            double varied = base + rng.nextGaussian() * launchRodAngleStdDevDeg;            
            opts.setLaunchRodAngle(varied);
        }
        if (launchRodDirectionStdDevDeg > 0) {
            double base = opts.getLaunchRodDirection();
            double varied = base + rng.nextGaussian() * launchRodDirectionStdDevDeg;            
            opts.setLaunchRodDirection(varied);
        }

        // ---- Launch coordinates ----
        if (launchLatitudeStdDevDeg > 0) {
            double base = opts.getLaunchLatitude();
            opts.setLaunchLatitude(clamp(base + rng.nextGaussian() * launchLatitudeStdDevDeg, -90.0, 90.0));
        }
        if (launchLongitudeStdDevDeg > 0) {
            double base = opts.getLaunchLongitude();
            opts.setLaunchLongitude(wrapLongitudeDeg(base + rng.nextGaussian() * launchLongitudeStdDevDeg));
        }
        if (launchAltitudeStdDevM > 0) {
            double base = opts.getLaunchAltitude();
            opts.setLaunchAltitude(Math.max(-500.0, base + rng.nextGaussian() * launchAltitudeStdDevM));
        }

        // ---------------------------------------------------------------------
        // WIND (Average / PinkNoise / MultiLevelPinkNoise)
        //
        // Monte Carlo semantics:
        // - windSpeedAverageSigmaMps: per-run perturbation to the MEAN wind speed
        // - windSpeedTurbulenceSigmaMps: per-run perturbation to the GUST std-dev
        // - windDirectionStdDevDeg: per-run perturbation to the direction (radians internal)
        //
        // Multi-level: apply ONE uniform delta to ALL levels (recommended).
        // ---------------------------------------------------------------------

        final Object windModel = resolveWindModel(conditions, opts);
        final MultiLevelPinkNoiseWindModel mlFromModel =
                (windModel instanceof MultiLevelPinkNoiseWindModel) ? (MultiLevelPinkNoiseWindModel) windModel : null;
        final MultiLevelPinkNoiseWindModel mlCond = getMultiLevelWindModelFromConditions(conditions);
        final MultiLevelPinkNoiseWindModel mlOpts = getMultiLevelWindModelFromOptions(opts);

        final double avgSigmaMps  = Math.max(0.0, finiteOrZero(windSpeedAverageSigmaMps));
        final double turbSigmaMps = Math.max(0.0, finiteOrZero(windSpeedTurbulenceSigmaMps));
        final double dirSigmaRad = Math.max(0.0, finiteOrZero(windDirectionStdDevDeg));
        
        final boolean multiLevelActive = isMultiLevelActive(conditions, opts, windModel);

        if (multiLevelActive && (mlFromModel != null || mlCond != null || mlOpts != null)) {
            // Uniform per-run deltas
            final double deltaSpeed = (avgSigmaMps > 0.0)  ? rng.nextGaussian() * avgSigmaMps  : 0.0;
            final double deltaTurb  = (turbSigmaMps > 0.0) ? rng.nextGaussian() * turbSigmaMps : 0.0;
            final double deltaDir   = (dirSigmaRad > 0.0)  ? rng.nextGaussian() * dirSigmaRad  : 0.0;

            // Apply once per unique instance
            final Set<Object> seen = new HashSet<>();
            if (mlFromModel != null && seen.add(mlFromModel)) {
                applyMultiLevelUniformDeltas(mlFromModel, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad);
            }
            if (mlCond != null && seen.add(mlCond)) {
                applyMultiLevelUniformDeltas(mlCond, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad);
            }
            if (mlOpts != null && seen.add(mlOpts)) {
                applyMultiLevelUniformDeltas(mlOpts, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad);
            }

        } else {
            // Scalar wind model: apply per-run perturbations to speed/std-dev/direction.
            if (avgSigmaMps > 0.0) {
                double baseSpeed = getScalarWindSpeedMps(conditions, opts, windModel);
                double variedSpeed = Math.max(0.0, baseSpeed + rng.nextGaussian() * avgSigmaMps);
                setScalarWindSpeedEverywhere(conditions, opts, windModel, variedSpeed);

                if (debugEnabled) log.debug("MC Wind(mean): base={} m/s, sigma={} m/s, varied={} m/s",
                        baseSpeed, avgSigmaMps, variedSpeed);
            }

            if (turbSigmaMps > 0.0) {
                double baseStd = getScalarWindStdDevMps(conditions, opts, windModel);
                double variedStd = Math.max(0.0, baseStd + rng.nextGaussian() * turbSigmaMps);
                setScalarWindStdDevEverywhere(conditions, opts, windModel, variedStd);

                if (debugEnabled) log.debug("MC Wind(turb): base={} m/s, sigma={} m/s, varied={} m/s",
                        baseStd, turbSigmaMps, variedStd);
            }

            if (dirSigmaRad > 0.0) {
                double baseDir = getScalarWindDirectionRad(conditions, opts, windModel);
                double variedDir = baseDir + rng.nextGaussian() * dirSigmaRad;
                setScalarWindDirectionEverywhere(conditions, opts, windModel, variedDir);

                if (debugEnabled) log.debug("MC Wind(dir): base={} deg, sigma={} deg, varied={} deg",
                        Math.toDegrees(baseDir), Math.toDegrees(dirSigmaRad), Math.toDegrees(variedDir));
            }

            // IMPORTANT:
            // Do NOT set "turbulence intensity" here. In some OpenRocket builds, intensity is derived from
            // std-dev and mean wind, and writing it can cause std-dev to be recomputed/overwritten.
        }

        // ---- Temperature / Pressure variations ----
        if (temperatureStdDevC > 0.0) {
            double baseK = opts.getLaunchTemperature();
            if (!Double.isFinite(baseK) || baseK <= 0.0) baseK = DEFAULT_T0_K;

            double sigmaK = clamp(temperatureStdDevC, 0.0, 50.0);
            double variedK = clamp(baseK + rng.nextGaussian() * sigmaK, 180.0, 330.0);
            opts.setLaunchTemperature(variedK);
        }

        if (pressureStdDevMbar > 0.0) {
            double basePa = opts.getLaunchPressure();
            if (!Double.isFinite(basePa) || basePa <= 0.0) basePa = DEFAULT_P0_PA;

            double sigmaMbar = clamp(pressureStdDevMbar, 0.0, 200.0);
            double variedPa = clamp(basePa + rng.nextGaussian() * (sigmaMbar * 100.0), 50_000.0, 120_000.0);
            opts.setLaunchPressure(variedPa);
        }
    }

    // -------------------------------------------------------------------------
    // Options resolution (API differs between OR versions)
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

    // -------------------------------------------------------------------------
    // Wind model helpers (reflection-safe)
    // -------------------------------------------------------------------------

    private static Object resolveWindModel(SimulationConditions conditions, SimulationOptions opts) {
        Object wm = invokeObject(conditions, "getWindModel");
        if (wm != null) return wm;
        return invokeObject(opts, "getWindModel");
    }

    private static MultiLevelPinkNoiseWindModel getMultiLevelWindModelFromConditions(SimulationConditions conditions) {
        if (conditions == null) return null;
        Object maybe = invokeObject(conditions, "getMultiLevelWindModel");
        return (maybe instanceof MultiLevelPinkNoiseWindModel ml) ? ml : null;
    }

    private static MultiLevelPinkNoiseWindModel getMultiLevelWindModelFromOptions(SimulationOptions opts) {
        if (opts == null) return null;
        try {
            return opts.getMultiLevelWindModel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isMultiLevelActive(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        if (windModel instanceof MultiLevelPinkNoiseWindModel) return true;

        Object t = invokeObject(conditions, "getWindModelType");
        if (t == null) t = invokeObject(opts, "getWindModelType");
        if (t == null) return false;

        final String name = String.valueOf(t).toLowerCase();
        return name.contains("multi") || name.contains("level");
    }

    private void applyMultiLevelUniformDeltas(MultiLevelPinkNoiseWindModel ml,
                                             double deltaSpeed,
                                             double deltaTurb,
                                             double deltaDir,
                                             double avgSigmaMps,
                                             double turbSigmaMps,
                                             double dirSigmaRad) {
        if (ml == null) return;

        final boolean doSpeed = Double.isFinite(deltaSpeed) && Math.abs(deltaSpeed) > 0.0;
        final boolean doTurb  = Double.isFinite(deltaTurb)  && Math.abs(deltaTurb)  > 0.0;
        final boolean doDir   = Double.isFinite(deltaDir)   && Math.abs(deltaDir)   > 0.0;
        if (!doSpeed && !doTurb && !doDir) return;

        for (MultiLevelPinkNoiseWindModel.LevelWindModel level : ml.getLevels()) {
            if (doSpeed) {
                double base = level.getSpeed();
                double varied = Math.max(0.0, base + deltaSpeed);
                level.setSpeed(varied);
                if (debugEnabled) {
                    log.debug("MC Wind(mean) [uniform] @ {} m: base={} m/s, sigma={} m/s, varied={} m/s",
                            level.getAltitude(), base, avgSigmaMps, varied);
                }
            }

            if (doTurb) {
                double baseStd = level.getStandardDeviation();
                double variedStd = Math.max(0.0, baseStd + deltaTurb);
                try {
                    level.setStandardDeviation(variedStd);
                } catch (Throwable ignored) { }
                if (debugEnabled) {
                    log.debug("MC Wind(turb) [uniform] @ {} m: base={} m/s, sigma={} m/s, varied={} m/s",
                            level.getAltitude(), baseStd, turbSigmaMps, variedStd);
                }
            }

            if (doDir) {
                double baseDir = level.getDirection();
                double variedDir = baseDir + deltaDir;
                level.setDirection(variedDir);
                if (debugEnabled) {
                    log.debug("MC Wind(dir) [uniform] @ {} m: base={} deg, sigma={} deg, varied={} deg",
                            level.getAltitude(), Math.toDegrees(baseDir), Math.toDegrees(dirSigmaRad), Math.toDegrees(variedDir));
                }
            }
        }
    }

    // Scalar getters

    private static double getScalarWindSpeedMps(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        double s = invokeDouble(windModel, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getAverageWindspeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(windModel, "getWindSpeedAverage", Double.NaN);

        if (!Double.isFinite(s)) s = invokeDouble(conditions, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(conditions, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(conditions, "getAverageWindspeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(opts, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(opts, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(s)) s = invokeDouble(opts, "getAverageWindspeed", Double.NaN);

        if (!Double.isFinite(s) || s < 0.0) s = 0.0;
        return s;
    }

    private static double getScalarWindStdDevMps(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        double t = invokeDouble(windModel, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getStdDev", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(windModel, "getTurbulence", Double.NaN);

        if (!Double.isFinite(t)) t = invokeDouble(conditions, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(conditions, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(opts, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(t)) t = invokeDouble(opts, "getWindSpeedStandardDeviation", Double.NaN);

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

    // Scalar setters

    private static void setScalarWindSpeedEverywhere(SimulationConditions conditions, SimulationOptions opts, Object windModel, double speedMps) {
        // Conditions
        tryInvokeVoidDouble(conditions, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(conditions, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(conditions, "setAverageWindspeed", speedMps);
        tryInvokeVoidDouble(conditions, "setWindSpeedAverage", speedMps);

        // Options
        tryInvokeVoidDouble(opts, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindspeed", speedMps);
        tryInvokeVoidDouble(opts, "setWindSpeedAverage", speedMps);

        // Model
        if (windModel != null) {
            tryInvokeVoidDouble(windModel, "setWindSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setAverageWindSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setAverageWindspeed", speedMps);
            tryInvokeVoidDouble(windModel, "setMeanWindSpeed", speedMps);
        }
    }

    private static void setScalarWindStdDevEverywhere(SimulationConditions conditions, SimulationOptions opts, Object windModel, double stdMps) {
        // Conditions
        tryInvokeVoidDouble(conditions, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setStdDev", stdMps);
        tryInvokeVoidDouble(conditions, "setTurbulence", stdMps);

        // Options
        tryInvokeVoidDouble(opts, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStdDev", stdMps);
        tryInvokeVoidDouble(opts, "setTurbulence", stdMps);

        // Model
        if (windModel != null) {
            tryInvokeVoidDouble(windModel, "setStandardDeviation", stdMps);
            tryInvokeVoidDouble(windModel, "setStdDev", stdMps);
            tryInvokeVoidDouble(windModel, "setWindStandardDeviation", stdMps);
            tryInvokeVoidDouble(windModel, "setTurbulence", stdMps);
        }
    }

    private static void setScalarWindDirectionEverywhere(SimulationConditions conditions, SimulationOptions opts, Object windModel, double dirRad) {
        // Conditions
        tryInvokeVoidDouble(conditions, "setWindDirection", dirRad);
        tryInvokeVoidDouble(conditions, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(conditions, "setDirection", dirRad);

        // Options
        tryInvokeVoidDouble(opts, "setWindDirection", dirRad);
        tryInvokeVoidDouble(opts, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(opts, "setDirection", dirRad);

        // Model
        if (windModel != null) {
            tryInvokeVoidDouble(windModel, "setWindDirection", dirRad);
            tryInvokeVoidDouble(windModel, "setDirection", dirRad);
        }
    }

    private static boolean tryInvokeVoidDouble(Object target, String methodName, double value) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) {
            try {
                Method m = target.getClass().getMethod(methodName, Double.class);
                m.invoke(target, value);
                return true;
            } catch (Exception ignored2) {
                return false;
            }
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

    // -------------------------------------------------------------------------
    // Persistence: bridge to AbstractSimulationExtension config map (reflection)
    // -------------------------------------------------------------------------

    private void reloadFromConfig() {
        // If we can't access config, keep cached values.
        Object cfg = getCfgObject();
        if (cfg == null) return;

        enabled = cfgGetBool(cfg, K_ENABLED, enabled);
        debugEnabled = cfgGetBool(cfg, K_DEBUG, debugEnabled);
        numberOfSimulations = cfgGetInt(cfg, K_NUM_SIMS, numberOfSimulations);

        useDeterministicSeed = cfgGetBool(cfg, K_DETERMINISTIC, useDeterministicSeed);
        randomSeed = cfgGetLong(cfg, K_RANDOM_SEED, randomSeed);

        launchRodAngleStdDevDeg = cfgGetDouble(cfg, K_ROD_ANGLE_SIGMA_DEG, launchRodAngleStdDevDeg);
        launchRodDirectionStdDevDeg = cfgGetDouble(cfg, K_ROD_DIR_SIGMA_DEG, launchRodDirectionStdDevDeg);

        launchLatitudeStdDevDeg = cfgGetDouble(cfg, K_LAT_SIGMA_DEG, launchLatitudeStdDevDeg);
        launchLongitudeStdDevDeg = cfgGetDouble(cfg, K_LON_SIGMA_DEG, launchLongitudeStdDevDeg);
        launchAltitudeStdDevM = cfgGetDouble(cfg, K_ALT_SIGMA_M, launchAltitudeStdDevM);

        windDirectionStdDevDeg = cfgGetDouble(cfg, K_WIND_DIR_SIGMA_DEG, windDirectionStdDevDeg);
        temperatureStdDevC = cfgGetDouble(cfg, K_TEMP_SIGMA_C, temperatureStdDevC);
        pressureStdDevMbar = cfgGetDouble(cfg, K_PRES_SIGMA_MBAR, pressureStdDevMbar);

        windSpeedAverageSigmaMps = cfgGetDouble(cfg, K_WIND_AVG_SIGMA_MPS, windSpeedAverageSigmaMps);
        windSpeedTurbulenceSigmaMps = cfgGetDouble(cfg, K_WIND_TURB_SIGMA_MPS, windSpeedTurbulenceSigmaMps);

        workerThreads = cfgGetInt(cfg, K_WORKER_THREADS, workerThreads);
        if (workerThreads < 1) workerThreads = 1;
        if (numberOfSimulations < 1) numberOfSimulations = 1;
    }

    private void storeToConfig(String key, Object value) {
        Object cfg = getCfgObject();
        if (cfg == null || key == null) return;

        // Prefer storing long values as String to avoid any precision loss across implementations.
        if (value instanceof Long l) {
            if (invokeConfigPut(cfg, key, String.valueOf(l))) return;
        }

        // Try best-effort put with overloads (Object + primitives), then fall back to set*/put* variants.
        if (invokeConfigPut(cfg, key, value)) return;

        // Some OR builds expose "set(key, value)" instead of "put(key, value)".
        try {
            Method m = cfg.getClass().getMethod("set", String.class, Object.class);
            m.invoke(cfg, key, value);
            return;
        } catch (Exception ignored) { }

        // Some builds expose only String setters.
        try {
            Method m = cfg.getClass().getMethod("set", String.class, String.class);
            m.invoke(cfg, key, String.valueOf(value));
            return;
        } catch (Exception ignored) { }

        try {
            Method m = cfg.getClass().getMethod("setString", String.class, String.class);
            m.invoke(cfg, key, String.valueOf(value));
        } catch (Exception ignored) { }
    }

    private Object getCfgObject() {
        // 1) Look for a no-arg getConfig()/getConfiguration() method anywhere in the class hierarchy
        for (Class<?> c = this.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getConfig");
                m.setAccessible(true);
                Object v = m.invoke(this);
                if (v != null) return v;
            } catch (Exception ignored) { }
            try {
                Method m = c.getDeclaredMethod("getConfiguration");
                m.setAccessible(true);
                Object v = m.invoke(this);
                if (v != null) return v;
            } catch (Exception ignored) { }
        }

        // 2) Search for a protected field named "config" in the superclass chain.
        for (Class<?> c = this.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("config");
                f.setAccessible(true);
                Object v = f.get(this);
                if (v != null) return v;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static Object invokeConfigGet(Object cfg, String key) {
        if (cfg == null || key == null) return null;

        // get(key)
        try {
            Method m = cfg.getClass().getMethod("get", String.class);
            return m.invoke(cfg, key);
        } catch (Exception ignored) { }

        // getString(key)
        try {
            Method m = cfg.getClass().getMethod("getString", String.class);
            return m.invoke(cfg, key);
        } catch (Exception ignored) { }

        return null;
    }

    private static boolean invokeConfigPut(Object cfg, String key, Object value) {
        if (cfg == null || key == null) return false;

        // put(String, <exact class>)
        if (value != null) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, value.getClass());
                m.invoke(cfg, key, value);
                return true;
            } catch (Exception ignored) { }
        }

        // put(String, Object)
        try {
            Method m = cfg.getClass().getMethod("put", String.class, Object.class);
            m.invoke(cfg, key, value);
            return true;
        } catch (Exception ignored) { }

        // Primitive overloads (common in OR config impls)
        if (value instanceof Boolean b) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, boolean.class);
                m.invoke(cfg, key, b.booleanValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof Integer i) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, int.class);
                m.invoke(cfg, key, i.intValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof Long l) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, long.class);
                m.invoke(cfg, key, l.longValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof Number n) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, double.class);
                m.invoke(cfg, key, n.doubleValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof String s) {
            try {
                Method m = cfg.getClass().getMethod("put", String.class, String.class);
                m.invoke(cfg, key, s);
                return true;
            } catch (Exception ignored) { }
        }

        return false;
    }

    private static String cfgGetString(Object cfg, String key, String fallback) {
        Object v = invokeConfigGet(cfg, key);
        if (v == null) {
            // getString(key, fallback) (present in some builds)
            try {
                Method m = cfg.getClass().getMethod("getString", String.class, String.class);
                Object out = m.invoke(cfg, key, fallback);
                return (out != null) ? out.toString() : fallback;
            } catch (Exception ignored) { }
            return fallback;
        }
        return v.toString();
    }

    private static boolean cfgGetBool(Object cfg, String key, boolean fallback) {
        Object v = invokeConfigGet(cfg, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;

        // getBoolean(key, fallback)
        try {
            Method m = cfg.getClass().getMethod("getBoolean", String.class, boolean.class);
            Object out = m.invoke(cfg, key, fallback);
            if (out instanceof Boolean b) return b;
        } catch (Exception ignored) { }

        String s = (v != null) ? v.toString() : cfgGetString(cfg, key, null);
        if (s == null) return fallback;
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return Boolean.parseBoolean(s);
        try { return Double.parseDouble(s.trim()) != 0.0; } catch (Exception ignored) { }
        return fallback;
    }

    private static int cfgGetInt(Object cfg, String key, int fallback) {
        Object v = invokeConfigGet(cfg, key);
        if (v instanceof Number n) return n.intValue();

        // getInt(key, fallback)
        try {
            Method m = cfg.getClass().getMethod("getInt", String.class, int.class);
            Object out = m.invoke(cfg, key, fallback);
            if (out instanceof Number n) return n.intValue();
        } catch (Exception ignored) { }

        String s = (v != null) ? v.toString() : cfgGetString(cfg, key, null);
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { }
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (Exception ignored) { }
        return fallback;
    }

    private static long cfgGetLong(Object cfg, String key, long fallback) {
        Object v = invokeConfigGet(cfg, key);
        if (v instanceof Number n) return n.longValue();

        // getLong(key, fallback)
        try {
            Method m = cfg.getClass().getMethod("getLong", String.class, long.class);
            Object out = m.invoke(cfg, key, fallback);
            if (out instanceof Number n) return n.longValue();
        } catch (Exception ignored) { }

        String s = (v != null) ? v.toString() : cfgGetString(cfg, key, null);
        if (s == null) return fallback;
        try { return Long.parseLong(s.trim()); } catch (Exception ignored) { }
        try { return (long) Math.round(Double.parseDouble(s.trim())); } catch (Exception ignored) { }
        return fallback;
    }

    private static double cfgGetDouble(Object cfg, String key, double fallback) {
        // getDouble(key, fallback) (present in OR's config impls)
        try {
            Method m = cfg.getClass().getMethod("getDouble", String.class, double.class);
            Object out = m.invoke(cfg, key, fallback);
            if (out instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) { }

        Object v = invokeConfigGet(cfg, key);
        if (v instanceof Number n) return n.doubleValue();

        String s = (v != null) ? v.toString() : cfgGetString(cfg, key, null);
        if (s == null) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { return fallback; }
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
    // Bean properties used by configurator
    // (setters also persist to config)
    // -------------------------------------------------------------------------

    public boolean isEnabled() { reloadFromConfig(); return enabled; }
    public void setEnabled(boolean v) { enabled = v; storeToConfig(K_ENABLED, v); fireChangeEvent(); }

    public boolean isDebugEnabled() { reloadFromConfig(); return debugEnabled; }
    public void setDebugEnabled(boolean v) { debugEnabled = v; storeToConfig(K_DEBUG, v); fireChangeEvent(); }

    public int getNumberOfSimulations() { reloadFromConfig(); return numberOfSimulations; }
    public void setNumberOfSimulations(int v) {
        numberOfSimulations = Math.max(1, v);
        storeToConfig(K_NUM_SIMS, numberOfSimulations);
        fireChangeEvent();
    }

    public boolean isUseDeterministicSeed() { reloadFromConfig(); return useDeterministicSeed; }
    public void setUseDeterministicSeed(boolean v) {
        useDeterministicSeed = v;
        storeToConfig(K_DETERMINISTIC, v);
        fireChangeEvent();
    }

    public long getRandomSeed() { reloadFromConfig(); return randomSeed; }
    public void setRandomSeed(long v) {
        randomSeed = v;
        storeToConfig(K_RANDOM_SEED, v);
        fireChangeEvent();
    }

    public double getLaunchRodAngleStdDevDeg() { reloadFromConfig(); return launchRodAngleStdDevDeg; }
    public void setLaunchRodAngleStdDevDeg(double v) {
        launchRodAngleStdDevDeg = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_ROD_ANGLE_SIGMA_DEG, launchRodAngleStdDevDeg);
        fireChangeEvent();
    }

    public double getLaunchRodDirectionStdDevDeg() { reloadFromConfig(); return launchRodDirectionStdDevDeg; }
    public void setLaunchRodDirectionStdDevDeg(double v) {
        launchRodDirectionStdDevDeg = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_ROD_DIR_SIGMA_DEG, launchRodDirectionStdDevDeg);
        fireChangeEvent();
    }

    public double getLaunchLatitudeStdDevDeg() { reloadFromConfig(); return launchLatitudeStdDevDeg; }
    public void setLaunchLatitudeStdDevDeg(double v) {
        launchLatitudeStdDevDeg = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_LAT_SIGMA_DEG, launchLatitudeStdDevDeg);
        fireChangeEvent();
    }

    public double getLaunchLongitudeStdDevDeg() { reloadFromConfig(); return launchLongitudeStdDevDeg; }
    public void setLaunchLongitudeStdDevDeg(double v) {
        launchLongitudeStdDevDeg = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_LON_SIGMA_DEG, launchLongitudeStdDevDeg);
        fireChangeEvent();
    }

    public double getLaunchAltitudeStdDevM() { reloadFromConfig(); return launchAltitudeStdDevM; }
    public void setLaunchAltitudeStdDevM(double v) {
        launchAltitudeStdDevM = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_ALT_SIGMA_M, launchAltitudeStdDevM);
        fireChangeEvent();
    }

    public double getWindSpeedAverageSigmaMps() { reloadFromConfig(); return windSpeedAverageSigmaMps; }
    public void setWindSpeedAverageSigmaMps(double v) {
        windSpeedAverageSigmaMps = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_WIND_AVG_SIGMA_MPS, windSpeedAverageSigmaMps);
        fireChangeEvent();
    }

    public double getWindSpeedTurbulenceSigmaMps() { reloadFromConfig(); return windSpeedTurbulenceSigmaMps; }
    public void setWindSpeedTurbulenceSigmaMps(double v) {
        windSpeedTurbulenceSigmaMps = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_WIND_TURB_SIGMA_MPS, windSpeedTurbulenceSigmaMps);
        fireChangeEvent();
    }

    public double getWindDirectionStdDevDeg() { reloadFromConfig(); return windDirectionStdDevDeg; }
    public void setWindDirectionStdDevDeg(double v) {
        windDirectionStdDevDeg = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_WIND_DIR_SIGMA_DEG, windDirectionStdDevDeg);
        fireChangeEvent();
    }

    public double getTemperatureStdDevC() { reloadFromConfig(); return temperatureStdDevC; }
    public void setTemperatureStdDevC(double v) {
        temperatureStdDevC = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_TEMP_SIGMA_C, temperatureStdDevC);
        fireChangeEvent();
    }

    public double getPressureStdDevMbar() { reloadFromConfig(); return pressureStdDevMbar; }
    public void setPressureStdDevMbar(double v) {
        pressureStdDevMbar = Math.max(0.0, finiteOrZero(v));
        storeToConfig(K_PRES_SIGMA_MBAR, pressureStdDevMbar);
        fireChangeEvent();
    }

    public int getWorkerThreads() { reloadFromConfig(); return workerThreads; }
    public void setWorkerThreads(int v) {
        workerThreads = Math.max(1, v);
        storeToConfig(K_WORKER_THREADS, workerThreads);
        fireChangeEvent();
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static double finiteOrZero(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double wrapLongitudeDeg(double lon) {
        double x = lon;
        while (x >= 180.0) x -= 360.0;
        while (x < -180.0) x += 360.0;
        return x;
    }
}
