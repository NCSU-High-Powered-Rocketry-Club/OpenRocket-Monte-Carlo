package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * OpenRocket Simulation Extension entry point for the "HPRC Monte Carlo" plugin.
 *
 * Configured via the Simulation Extensions tab (MonteCarloConfigurator).
 *
 * <p><b>Persistence (run-to-run / .ork save-load):</b>
 * OpenRocket persists a simulation extension's settings into the <code>.ork</code> file via the
 * {@link AbstractSimulationExtension}-provided {@code config} object.
 *
 * <ul>
 *   <li>Read values with the cfgXxx() helpers below.</li>
 *   <li>Write values with the cfgPutXxx() helpers below.</li>
 *   <li>After every UI-driven setter, call {@code fireChangeEvent()} so the Simulation is marked dirty
 *       and the updated config is serialized into the <code>.ork</code>.</li>
 * </ul>
 *
 * <p>
 * IMPORTANT: do <b>not</b> cache the config values at construction time. OpenRocket may populate the
 * extension's {@code config} after instantiation when loading a <code>.ork</code>; caching too early
 * can cause saved values to be ignored. Therefore, getters read directly from {@code config}.
 * </p>
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

    // ---------------------------------------------------------------------
    // Gust / Shear disturbance keys (per-step wind deltas)
    // ---------------------------------------------------------------------

    private static final String K_GUST_ENABLED          = CFG_PREFIX + "gustEventsEnabled";
    private static final String K_GUST_COUNT            = CFG_PREFIX + "gustEventCount";
    private static final String K_GUST_WINDOW_START_S   = CFG_PREFIX + "gustWindowStartS";
    private static final String K_GUST_WINDOW_END_S     = CFG_PREFIX + "gustWindowEndS";
    private static final String K_GUST_DURATION_MEAN_S  = CFG_PREFIX + "gustDurationMeanS";
    private static final String K_GUST_DURATION_SIGMA_S = CFG_PREFIX + "gustDurationSigmaS";
    private static final String K_GUST_PEAK_MEAN_MPS    = CFG_PREFIX + "gustPeakDeltaMeanMps";
    private static final String K_GUST_PEAK_SIGMA_MPS   = CFG_PREFIX + "gustPeakDeltaSigmaMps";

    private static final String K_SHEAR_ENABLED         = CFG_PREFIX + "shearLayerEnabled";
    private static final String K_SHEAR_CENTER_ALT_M    = CFG_PREFIX + "shearCenterAltM";
    private static final String K_SHEAR_THICKNESS_M     = CFG_PREFIX + "shearThicknessM";
    private static final String K_SHEAR_DELTA_MEAN_MPS  = CFG_PREFIX + "shearDeltaMeanMps";
    private static final String K_SHEAR_DELTA_SIGMA_MPS = CFG_PREFIX + "shearDeltaSigmaMps";

    // Vehicle / Motor physics override keys
    private static final String K_CD_MULT_SIGMA      = CFG_PREFIX + "cdMultiplierSigma";
    private static final String K_THRUST_MULT_SIGMA  = CFG_PREFIX + "thrustMultiplierSigma";
    private static final String K_MASS_MULT_SIGMA    = CFG_PREFIX + "massMultiplierSigma";

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private static final boolean D_ENABLED = true;
    private static final boolean D_DEBUG = false;
    private static final int D_NUM_SIMS = 100;

    private static final boolean D_DETERMINISTIC = false;
    private static final long D_RANDOM_SEED = 1L;

    private static final double D_ROD_ANGLE_SIGMA_DEG = 0.0;
    private static final double D_ROD_DIR_SIGMA_DEG = 0.0;

    private static final double D_LAT_SIGMA_DEG = 0.0;
    private static final double D_LON_SIGMA_DEG = 0.0;
    private static final double D_ALT_SIGMA_M = 0.0;

    private static final double D_WIND_DIR_SIGMA_DEG = 0.0;
    private static final double D_TEMP_SIGMA_C = 0.0;
    private static final double D_PRES_SIGMA_MBAR = 0.0;

    private static final double D_WIND_AVG_SIGMA_MPS = 0.0;
    private static final double D_WIND_TURB_SIGMA_MPS = 0.0;

    private static final int D_WORKER_THREADS = 1;

    // Gust defaults
    private static final boolean D_GUST_ENABLED = false;
    private static final int D_GUST_COUNT = 0;
    private static final double D_GUST_WINDOW_START_S = 0.0;
    private static final double D_GUST_WINDOW_END_S = 15.0;
    private static final double D_GUST_DURATION_MEAN_S = 1.0;
    private static final double D_GUST_DURATION_SIGMA_S = 0.25;
    private static final double D_GUST_PEAK_MEAN_MPS = 0.0;
    private static final double D_GUST_PEAK_SIGMA_MPS = 0.0;

    // Shear defaults
    private static final boolean D_SHEAR_ENABLED = false;
    private static final double D_SHEAR_CENTER_ALT_M = 300.0;
    private static final double D_SHEAR_THICKNESS_M = 150.0;
    private static final double D_SHEAR_DELTA_MEAN_MPS = 0.0;
    private static final double D_SHEAR_DELTA_SIGMA_MPS = 0.0;

    // Vehicle / Motor physics override defaults (multiplier sigma, 0 = disabled)
    private static final double D_CD_MULT_SIGMA = 0.0;
    private static final double D_THRUST_MULT_SIGMA = 0.0;
    private static final double D_MASS_MULT_SIGMA = 0.0;

    // -------------------------------------------------------------------------
    // Config helpers (same pattern as AirbrakeExtension â€” read/write directly
    // from the inherited `config` field, never cache)
    // -------------------------------------------------------------------------

    private static String safeString(String s) {
        return (s == null) ? "" : s;
    }

    private Object invokeConfigGetRaw(String key) {
        if (key == null) return null;
        try {
            Method m = config.getClass().getMethod("get", String.class);
            return m.invoke(config, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean invokeConfigPut(String key, Object value) {
        if (key == null) return false;
        try {
            Method m = config.getClass().getMethod("put", String.class, Object.class);
            m.invoke(config, key, value);
            return true;
        } catch (Exception ignored) {
            // fall through
        }
        // Try primitive overloads if present
        if (value instanceof Boolean b) {
            try {
                Method m = config.getClass().getMethod("put", String.class, boolean.class);
                m.invoke(config, key, b.booleanValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof Number n) {
            try {
                Method m = config.getClass().getMethod("put", String.class, double.class);
                m.invoke(config, key, n.doubleValue());
                return true;
            } catch (Exception ignored) { }
        }
        if (value instanceof String s) {
            try {
                Method m = config.getClass().getMethod("put", String.class, String.class);
                m.invoke(config, key, s);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private double cfgDouble(String key, double fallback) {
        Object v = invokeConfigGetRaw(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) { }
        }
        try {
            return config.getDouble(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean cfgBool(String key, boolean fallback) {
        Object v = invokeConfigGetRaw(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return Boolean.parseBoolean(s);
            try { return Double.parseDouble(s.trim()) != 0.0; } catch (Exception ignored) { }
        }
        return fallback;
    }

    private int cfgInt(String key, int fallback) {
        Object v = invokeConfigGetRaw(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { }
            try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (Exception ignored) { }
        }
        // Try getDouble as fallback (OR config often stores ints as doubles)
        try {
            double d = config.getDouble(key, Double.NaN);
            if (Double.isFinite(d)) return (int) Math.round(d);
        } catch (Throwable ignored) { }
        return fallback;
    }

    private long cfgLong(String key, long fallback) {
        Object v = invokeConfigGetRaw(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignored) { }
            try { return (long) Math.round(Double.parseDouble(s.trim())); } catch (Exception ignored) { }
        }
        return fallback;
    }

    private void cfgPutDouble(String key, double value) {
        if (invokeConfigPut(key, value)) return;
        try { config.put(key, value); } catch (Throwable ignored) { }
    }

    private void cfgPutString(String key, String value) {
        value = safeString(value);
        if (invokeConfigPut(key, value)) return;
        try { config.put(key, value); } catch (Throwable ignored) { }
    }

    private void cfgPutBool(String key, boolean value) {
        if (invokeConfigPut(key, value)) return;
        // As a last resort, store as string for max compatibility
        cfgPutString(key, value ? "true" : "false");
    }

    private void cfgPutInt(String key, int value) {
        // Store as double since OR's config typically supports put(String, double)
        cfgPutDouble(key, (double) value);
    }

    private void cfgPutLong(String key, long value) {
        // Prefer storing as String to avoid precision loss
        if (invokeConfigPut(key, String.valueOf(value))) return;
        if (invokeConfigPut(key, value)) return;
        cfgPutString(key, String.valueOf(value));
    }

    // -------------------------------------------------------------------------
    // Batch-only controls (NOT persisted)
    // -------------------------------------------------------------------------

    private transient boolean batchRunContext = false;
    private transient long batchSeed = Long.MIN_VALUE;
    private transient long effectiveSeedUsed = Long.MIN_VALUE;

    // ---------------------------------------------------------------------
    // Per-run gust/shear artifacts (NOT persisted)
    // ---------------------------------------------------------------------

    private transient GustShearMetrics lastGustShearMetrics = null;
    private transient RunWindDisturbanceProfile lastWindDisturbanceProfile = null;

    // Per-run physics override values (NOT persisted — set during initialize())
    private transient double lastCdMultiplier = 1.0;
    private transient double lastThrustMultiplier = 1.0;
    private transient double lastMassMultiplier = 1.0;

    /** Called by MonteCarloBatchRunner on cloned extensions only. */
    public void setBatchRunContext(boolean v) { this.batchRunContext = v; }

    /** Called by MonteCarloBatchRunner on cloned extensions only. */
    public void setBatchSeed(long seed) { this.batchSeed = seed; }

    /** For logging / CSV. */
    public long getEffectiveSeedUsed() { return effectiveSeedUsed; }

    /** Per-run gust/shear metrics captured by GustShearListener (null if disabled or failed). */
    public GustShearMetrics getLastGustShearMetrics() { return lastGustShearMetrics; }

    /** Per-run gust/shear profile sampled at initialize(...) (null if disabled or failed). */
    public RunWindDisturbanceProfile getLastWindDisturbanceProfile() { return lastWindDisturbanceProfile; }

    /** Per-run CD multiplier actually used (1.0 if disabled). */
    public double getLastCdMultiplier() { return lastCdMultiplier; }

    /** Per-run thrust multiplier actually used (1.0 if disabled). */
    public double getLastThrustMultiplier() { return lastThrustMultiplier; }

    /** Per-run mass multiplier actually used (1.0 if disabled). */
    public double getLastMassMultiplier() { return lastMassMultiplier; }

    // -------------------------------------------------------------------------
    // OpenRocket entry point
    // -------------------------------------------------------------------------

    @Override
    public void initialize(final SimulationConditions conditions) throws SimulationException {
        if (!isEnabled()) return;

        // CRITICAL: never perturb when user is running a normal single sim from the UI.
        // Only MonteCarloBatchRunner sets this true for cloned sims.
        if (!batchRunContext) return;

        // Resolve options
        final SimulationOptions opts = resolveOptions(conditions);

        // Per-run RNG (batch runner provides seed; fallback to deterministic base or random)
        final boolean useDeterministicSeed = isUseDeterministicSeed();
        final long seed = (batchSeed != Long.MIN_VALUE)
                ? batchSeed
                : (useDeterministicSeed ? getRandomSeed() : new Random().nextLong());
        this.effectiveSeedUsed = seed;
        final Random rng = new Random(seed);

        final boolean debugEnabled = isDebugEnabled();

        // ---- Atmosphere: if varying temp/pressure, disable ISA and ensure sane defaults ----
        final double temperatureStdDevC = getTemperatureStdDevC();
        final double pressureStdDevMbar = getPressureStdDevMbar();
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
        final double launchRodAngleStdDevDeg = getLaunchRodAngleStdDevDeg();
        if (launchRodAngleStdDevDeg > 0) {
            double base = opts.getLaunchRodAngle();
            double varied = base + rng.nextGaussian() * launchRodAngleStdDevDeg;
            opts.setLaunchRodAngle(varied);
        }
        final double launchRodDirectionStdDevDeg = getLaunchRodDirectionStdDevDeg();
        if (launchRodDirectionStdDevDeg > 0) {
            double base = opts.getLaunchRodDirection();
            double varied = base + rng.nextGaussian() * launchRodDirectionStdDevDeg;
            opts.setLaunchRodDirection(varied);
        }

        // ---- Launch coordinates ----
        final double launchLatitudeStdDevDeg = getLaunchLatitudeStdDevDeg();
        if (launchLatitudeStdDevDeg > 0) {
            double base = opts.getLaunchLatitude();
            opts.setLaunchLatitude(clamp(base + rng.nextGaussian() * launchLatitudeStdDevDeg, -90.0, 90.0));
        }
        final double launchLongitudeStdDevDeg = getLaunchLongitudeStdDevDeg();
        if (launchLongitudeStdDevDeg > 0) {
            double base = opts.getLaunchLongitude();
            opts.setLaunchLongitude(wrapLongitudeDeg(base + rng.nextGaussian() * launchLongitudeStdDevDeg));
        }
        final double launchAltitudeStdDevM = getLaunchAltitudeStdDevM();
        if (launchAltitudeStdDevM > 0) {
            double base = opts.getLaunchAltitude();
            opts.setLaunchAltitude(Math.max(-500.0, base + rng.nextGaussian() * launchAltitudeStdDevM));
        }

        // ---------------------------------------------------------------------
        // WIND
        // ---------------------------------------------------------------------

        final Object windModel = resolveWindModel(conditions, opts);
        final MultiLevelPinkNoiseWindModel mlFromModel =
                (windModel instanceof MultiLevelPinkNoiseWindModel) ? (MultiLevelPinkNoiseWindModel) windModel : null;
        final MultiLevelPinkNoiseWindModel mlCond = getMultiLevelWindModelFromConditions(conditions);
        final MultiLevelPinkNoiseWindModel mlOpts = getMultiLevelWindModelFromOptions(opts);

        final double avgSigmaMps  = Math.max(0.0, finiteOrZero(getWindSpeedAverageSigmaMps()));
        final double turbSigmaMps = Math.max(0.0, finiteOrZero(getWindSpeedTurbulenceSigmaMps()));
        final double dirSigmaRad = Math.max(0.0, finiteOrZero(getWindDirectionStdDevDeg()));

        final boolean multiLevelActive = isMultiLevelActive(conditions, opts, windModel);

        if (multiLevelActive && (mlFromModel != null || mlCond != null || mlOpts != null)) {
            final double deltaSpeed = (avgSigmaMps > 0.0)  ? rng.nextGaussian() * avgSigmaMps  : 0.0;
            final double deltaTurb  = (turbSigmaMps > 0.0) ? rng.nextGaussian() * turbSigmaMps : 0.0;
            final double deltaDir   = (dirSigmaRad > 0.0)  ? rng.nextGaussian() * dirSigmaRad  : 0.0;

            final Set<Object> seen = new HashSet<>();
            if (mlFromModel != null && seen.add(mlFromModel)) {
                applyMultiLevelUniformDeltas(mlFromModel, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad, debugEnabled);
            }
            if (mlCond != null && seen.add(mlCond)) {
                applyMultiLevelUniformDeltas(mlCond, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad, debugEnabled);
            }
            if (mlOpts != null && seen.add(mlOpts)) {
                applyMultiLevelUniformDeltas(mlOpts, deltaSpeed, deltaTurb, deltaDir, avgSigmaMps, turbSigmaMps, dirSigmaRad, debugEnabled);
            }

        } else {
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

        // -----------------------------------------------------------------
        // Per-step wind disturbances (gusts + shear)
        // -----------------------------------------------------------------

        this.lastGustShearMetrics = null;
        this.lastWindDisturbanceProfile = null;

        if (isGustEventsEnabled() || isShearLayerEnabled()) {
            try {
                RunWindDisturbanceProfile profile = RunWindDisturbanceProfile.sampleFromConfig(this, rng);
                GustShearMetrics metrics = new GustShearMetrics();
                metrics.resetAccumulators();

                WindSnapshot baseline = WindSnapshot.capture(conditions, opts);

                this.lastWindDisturbanceProfile = profile;
                this.lastGustShearMetrics = metrics;

                GustShearListener listener = new GustShearListener(baseline, profile, metrics, debugEnabled);
                addSimulationListener(conditions, listener);

                if (debugEnabled) {
                    log.debug("MC Gust/Shear: enabled, gusts={}, shearEnabled={}",
                            profile.gusts.size(), profile.shear != null);
                }
            } catch (Throwable t) {
                // Never fail the whole run due to this optional feature.
                if (debugEnabled) log.warn("MC Gust/Shear: failed to attach listener", t);
            }
        }

        // -----------------------------------------------------------------
        // Vehicle / Motor physics overrides (CD, thrust, mass multipliers)
        // -----------------------------------------------------------------

        this.lastCdMultiplier = 1.0;
        this.lastThrustMultiplier = 1.0;
        this.lastMassMultiplier = 1.0;

        final double cdSigma = Math.max(0.0, finiteOrZero(getCdMultiplierSigma()));
        final double thrustSigma = Math.max(0.0, finiteOrZero(getThrustMultiplierSigma()));
        final double massSigma = Math.max(0.0, finiteOrZero(getMassMultiplierSigma()));

        if (cdSigma > 0.0 || thrustSigma > 0.0 || massSigma > 0.0) {
            // Sample multipliers: Gaussian centered at 1.0, clamped to safe bounds
            double cdMult = 1.0;
            double thrustMult = 1.0;
            double massMult = 1.0;

            if (cdSigma > 0.0) {
                cdMult = clamp(1.0 + rng.nextGaussian() * cdSigma, 0.5, 2.0);
            }
            if (thrustSigma > 0.0) {
                thrustMult = clamp(1.0 + rng.nextGaussian() * thrustSigma, 0.5, 1.5);
            }
            if (massSigma > 0.0) {
                massMult = clamp(1.0 + rng.nextGaussian() * massSigma, 0.7, 1.5);
            }

            this.lastCdMultiplier = cdMult;
            this.lastThrustMultiplier = thrustMult;
            this.lastMassMultiplier = massMult;

            try {
                PhysicsOverrideListener physListener =
                        new PhysicsOverrideListener(cdMult, thrustMult, massMult, debugEnabled);
                addSimulationListener(conditions, physListener);

                if (debugEnabled) {
                    log.debug("MC Physics: cdMult={}, thrustMult={}, massMult={}",
                            cdMult, thrustMult, massMult);
                }
            } catch (Throwable t) {
                if (debugEnabled) log.warn("MC Physics: failed to attach listener", t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Options resolution (API differs between OR versions)
    // -------------------------------------------------------------------------

    private static SimulationOptions resolveOptions(SimulationConditions conditions) throws SimulationException {
        try {
            Method m = conditions.getClass().getMethod("getOptions");
            Object v = m.invoke(conditions);
            if (v instanceof SimulationOptions so) return so;
        } catch (Exception ignored) { }

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
    // Simulation listener wiring (reflection-safe)
    // -------------------------------------------------------------------------

    /**
     * Adds a simulation listener in a version-tolerant way.
     *
     * We prefer SimulationConditions.getSimulationListenerList().add(...)
     * but fall back to other patterns used by forks/older builds.
     */
    private static void addSimulationListener(SimulationConditions conditions, Object listener) {
        if (conditions == null || listener == null) return;

        Object list = invokeObject(conditions, "getSimulationListenerList");
        if (list != null && invokeBestEffortAdd(list, listener)) return;

        // Some forks use a different accessor name
        Object list2 = invokeObject(conditions, "getSimulationListeners");
        if (list2 != null && invokeBestEffortAdd(list2, listener)) return;

        // Last resort: direct add methods on conditions
        invokeBestEffortCall(conditions, "addSimulationListener", listener);
        invokeBestEffortCall(conditions, "addListener", listener);
    }

    private static boolean invokeBestEffortAdd(Object list, Object listener) {
        if (list == null || listener == null) return false;
        for (Method m : list.getClass().getMethods()) {
            String name = m.getName();
            if (!(name.equals("add") || name.equals("addListener") || name.equals("addSimulationListener"))) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (!p.isAssignableFrom(listener.getClass())) continue;
            try {
                m.invoke(list, listener);
                return true;
            } catch (Exception ignored) {
                // keep trying
            }
        }
        return false;
    }

    private static boolean invokeBestEffortCall(Object target, String methodName, Object arg) {
        if (target == null || methodName == null || arg == null) return false;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (!p.isAssignableFrom(arg.getClass())) continue;
            try {
                m.invoke(target, arg);
                return true;
            } catch (Exception ignored) {
                // keep trying
            }
        }
        return false;
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

    private static void applyMultiLevelUniformDeltas(MultiLevelPinkNoiseWindModel ml,
                                             double deltaSpeed,
                                             double deltaTurb,
                                             double deltaDir,
                                             double avgSigmaMps,
                                             double turbSigmaMps,
                                             double dirSigmaRad,
                                             boolean debugEnabled) {
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
        tryInvokeVoidDouble(conditions, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(conditions, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(conditions, "setAverageWindspeed", speedMps);
        tryInvokeVoidDouble(conditions, "setWindSpeedAverage", speedMps);

        tryInvokeVoidDouble(opts, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindspeed", speedMps);
        tryInvokeVoidDouble(opts, "setWindSpeedAverage", speedMps);

        if (windModel != null) {
            tryInvokeVoidDouble(windModel, "setWindSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setAverageWindSpeed", speedMps);
            tryInvokeVoidDouble(windModel, "setAverageWindspeed", speedMps);
            tryInvokeVoidDouble(windModel, "setMeanWindSpeed", speedMps);
        }
    }

    private static void setScalarWindStdDevEverywhere(SimulationConditions conditions, SimulationOptions opts, Object windModel, double stdMps) {
        tryInvokeVoidDouble(conditions, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(conditions, "setStdDev", stdMps);
        tryInvokeVoidDouble(conditions, "setTurbulence", stdMps);

        tryInvokeVoidDouble(opts, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStdDev", stdMps);
        tryInvokeVoidDouble(opts, "setTurbulence", stdMps);

        if (windModel != null) {
            tryInvokeVoidDouble(windModel, "setStandardDeviation", stdMps);
            tryInvokeVoidDouble(windModel, "setStdDev", stdMps);
            tryInvokeVoidDouble(windModel, "setWindStandardDeviation", stdMps);
            tryInvokeVoidDouble(windModel, "setTurbulence", stdMps);
        }
    }

    private static void setScalarWindDirectionEverywhere(SimulationConditions conditions, SimulationOptions opts, Object windModel, double dirRad) {
        tryInvokeVoidDouble(conditions, "setWindDirection", dirRad);
        tryInvokeVoidDouble(conditions, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(conditions, "setDirection", dirRad);

        tryInvokeVoidDouble(opts, "setWindDirection", dirRad);
        tryInvokeVoidDouble(opts, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(opts, "setDirection", dirRad);

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
    // Metadata
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "HPRC Monte Carlo";
    }

    @Override
    public String getDescription() {
        return "Monte Carlo variations for wind, launch rail, atmosphere, and vehicle physics (CD, thrust, mass).";
    }

    // -------------------------------------------------------------------------
    // Bean properties used by configurator
    // Every getter reads directly from config (never cached).
    // Every setter: (1) writes to persisted config, (2) calls fireChangeEvent().
    // -------------------------------------------------------------------------

    public boolean isEnabled() {
        return cfgBool(K_ENABLED, D_ENABLED);
    }

    public void setEnabled(boolean v) {
        cfgPutBool(K_ENABLED, v);
        fireChangeEvent();
    }

    public boolean isDebugEnabled() {
        return cfgBool(K_DEBUG, D_DEBUG);
    }

    public void setDebugEnabled(boolean v) {
        cfgPutBool(K_DEBUG, v);
        fireChangeEvent();
    }

    public int getNumberOfSimulations() {
        return Math.max(1, cfgInt(K_NUM_SIMS, D_NUM_SIMS));
    }

    public void setNumberOfSimulations(int v) {
        cfgPutInt(K_NUM_SIMS, Math.max(1, v));
        fireChangeEvent();
    }

    public boolean isUseDeterministicSeed() {
        return cfgBool(K_DETERMINISTIC, D_DETERMINISTIC);
    }

    public void setUseDeterministicSeed(boolean v) {
        cfgPutBool(K_DETERMINISTIC, v);
        fireChangeEvent();
    }

    public long getRandomSeed() {
        return cfgLong(K_RANDOM_SEED, D_RANDOM_SEED);
    }

    public void setRandomSeed(long v) {
        cfgPutLong(K_RANDOM_SEED, v);
        fireChangeEvent();
    }

    public double getLaunchRodAngleStdDevDeg() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_ROD_ANGLE_SIGMA_DEG, D_ROD_ANGLE_SIGMA_DEG)));
    }

    public void setLaunchRodAngleStdDevDeg(double v) {
        cfgPutDouble(K_ROD_ANGLE_SIGMA_DEG, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getLaunchRodDirectionStdDevDeg() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_ROD_DIR_SIGMA_DEG, D_ROD_DIR_SIGMA_DEG)));
    }

    public void setLaunchRodDirectionStdDevDeg(double v) {
        cfgPutDouble(K_ROD_DIR_SIGMA_DEG, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getLaunchLatitudeStdDevDeg() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_LAT_SIGMA_DEG, D_LAT_SIGMA_DEG)));
    }

    public void setLaunchLatitudeStdDevDeg(double v) {
        cfgPutDouble(K_LAT_SIGMA_DEG, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getLaunchLongitudeStdDevDeg() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_LON_SIGMA_DEG, D_LON_SIGMA_DEG)));
    }

    public void setLaunchLongitudeStdDevDeg(double v) {
        cfgPutDouble(K_LON_SIGMA_DEG, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getLaunchAltitudeStdDevM() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_ALT_SIGMA_M, D_ALT_SIGMA_M)));
    }

    public void setLaunchAltitudeStdDevM(double v) {
        cfgPutDouble(K_ALT_SIGMA_M, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getWindSpeedAverageSigmaMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_WIND_AVG_SIGMA_MPS, D_WIND_AVG_SIGMA_MPS)));
    }

    public void setWindSpeedAverageSigmaMps(double v) {
        cfgPutDouble(K_WIND_AVG_SIGMA_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getWindSpeedTurbulenceSigmaMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_WIND_TURB_SIGMA_MPS, D_WIND_TURB_SIGMA_MPS)));
    }

    public void setWindSpeedTurbulenceSigmaMps(double v) {
        cfgPutDouble(K_WIND_TURB_SIGMA_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getWindDirectionStdDevDeg() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_WIND_DIR_SIGMA_DEG, D_WIND_DIR_SIGMA_DEG)));
    }

    public void setWindDirectionStdDevDeg(double v) {
        cfgPutDouble(K_WIND_DIR_SIGMA_DEG, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getTemperatureStdDevC() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_TEMP_SIGMA_C, D_TEMP_SIGMA_C)));
    }

    public void setTemperatureStdDevC(double v) {
        cfgPutDouble(K_TEMP_SIGMA_C, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getPressureStdDevMbar() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_PRES_SIGMA_MBAR, D_PRES_SIGMA_MBAR)));
    }

    public void setPressureStdDevMbar(double v) {
        cfgPutDouble(K_PRES_SIGMA_MBAR, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    // -------------------------------------------------------------------------
    // Gust / Shear disturbance config
    // -------------------------------------------------------------------------

    public boolean isGustEventsEnabled() {
        return cfgBool(K_GUST_ENABLED, D_GUST_ENABLED);
    }

    public void setGustEventsEnabled(boolean v) {
        cfgPutBool(K_GUST_ENABLED, v);
        fireChangeEvent();
    }

    public int getGustEventCount() {
        return Math.max(0, cfgInt(K_GUST_COUNT, D_GUST_COUNT));
    }

    public void setGustEventCount(int v) {
        cfgPutInt(K_GUST_COUNT, Math.max(0, Math.min(50, v)));
        fireChangeEvent();
    }

    public double getGustWindowStartS() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_GUST_WINDOW_START_S, D_GUST_WINDOW_START_S)));
    }

    public void setGustWindowStartS(double v) {
        cfgPutDouble(K_GUST_WINDOW_START_S, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getGustWindowEndS() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_GUST_WINDOW_END_S, D_GUST_WINDOW_END_S)));
    }

    public void setGustWindowEndS(double v) {
        cfgPutDouble(K_GUST_WINDOW_END_S, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getGustDurationMeanS() {
        return Math.max(0.05, finiteOrZero(cfgDouble(K_GUST_DURATION_MEAN_S, D_GUST_DURATION_MEAN_S)));
    }

    public void setGustDurationMeanS(double v) {
        cfgPutDouble(K_GUST_DURATION_MEAN_S, Math.max(0.05, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getGustDurationSigmaS() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_GUST_DURATION_SIGMA_S, D_GUST_DURATION_SIGMA_S)));
    }

    public void setGustDurationSigmaS(double v) {
        cfgPutDouble(K_GUST_DURATION_SIGMA_S, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getGustPeakDeltaMeanMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_GUST_PEAK_MEAN_MPS, D_GUST_PEAK_MEAN_MPS)));
    }

    public void setGustPeakDeltaMeanMps(double v) {
        cfgPutDouble(K_GUST_PEAK_MEAN_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getGustPeakDeltaSigmaMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_GUST_PEAK_SIGMA_MPS, D_GUST_PEAK_SIGMA_MPS)));
    }

    public void setGustPeakDeltaSigmaMps(double v) {
        cfgPutDouble(K_GUST_PEAK_SIGMA_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public boolean isShearLayerEnabled() {
        return cfgBool(K_SHEAR_ENABLED, D_SHEAR_ENABLED);
    }

    public void setShearLayerEnabled(boolean v) {
        cfgPutBool(K_SHEAR_ENABLED, v);
        fireChangeEvent();
    }

    public double getShearCenterAltM() {
        return finiteOrZero(cfgDouble(K_SHEAR_CENTER_ALT_M, D_SHEAR_CENTER_ALT_M));
    }

    public void setShearCenterAltM(double v) {
        cfgPutDouble(K_SHEAR_CENTER_ALT_M, finiteOrZero(v));
        fireChangeEvent();
    }

    public double getShearThicknessM() {
        return Math.max(1.0, finiteOrZero(cfgDouble(K_SHEAR_THICKNESS_M, D_SHEAR_THICKNESS_M)));
    }

    public void setShearThicknessM(double v) {
        cfgPutDouble(K_SHEAR_THICKNESS_M, Math.max(1.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getShearDeltaMeanMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_SHEAR_DELTA_MEAN_MPS, D_SHEAR_DELTA_MEAN_MPS)));
    }

    public void setShearDeltaMeanMps(double v) {
        cfgPutDouble(K_SHEAR_DELTA_MEAN_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public double getShearDeltaSigmaMps() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_SHEAR_DELTA_SIGMA_MPS, D_SHEAR_DELTA_SIGMA_MPS)));
    }

    public void setShearDeltaSigmaMps(double v) {
        cfgPutDouble(K_SHEAR_DELTA_SIGMA_MPS, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    // ---- Vehicle / Motor physics override ----

    /**
     * Standard deviation of the CD (drag coefficient) multiplier.
     * 0 = disabled. 0.05 = ±5% variation at 1σ. Multiplier is N(1.0, sigma).
     */
    public double getCdMultiplierSigma() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_CD_MULT_SIGMA, D_CD_MULT_SIGMA)));
    }

    public void setCdMultiplierSigma(double v) {
        cfgPutDouble(K_CD_MULT_SIGMA, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    /**
     * Standard deviation of the thrust multiplier.
     * 0 = disabled. 0.03 = ±3% variation at 1σ. Multiplier is N(1.0, sigma).
     */
    public double getThrustMultiplierSigma() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_THRUST_MULT_SIGMA, D_THRUST_MULT_SIGMA)));
    }

    public void setThrustMultiplierSigma(double v) {
        cfgPutDouble(K_THRUST_MULT_SIGMA, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    /**
     * Standard deviation of the launch mass multiplier.
     * 0 = disabled. 0.02 = ±2% variation at 1σ. Multiplier is N(1.0, sigma).
     */
    public double getMassMultiplierSigma() {
        return Math.max(0.0, finiteOrZero(cfgDouble(K_MASS_MULT_SIGMA, D_MASS_MULT_SIGMA)));
    }

    public void setMassMultiplierSigma(double v) {
        cfgPutDouble(K_MASS_MULT_SIGMA, Math.max(0.0, finiteOrZero(v)));
        fireChangeEvent();
    }

    public int getWorkerThreads() {
        return Math.max(1, cfgInt(K_WORKER_THREADS, D_WORKER_THREADS));
    }

    public void setWorkerThreads(int v) {
        cfgPutInt(K_WORKER_THREADS, Math.max(1, v));
        fireChangeEvent();
    }

    /**
     * Copies all user-configurable, persisted settings from another extension instance
     * into this instance.
     *
     * Why: OpenRocket's default extension cloning is not guaranteed to copy the config
     * map across versions.  The MonteCarloBatchRunner uses this to ensure batch clones
     * inherit the exact same UI settings (including gust/shear enable flags).
     */
    public void copyPersistentSettingsFrom(MonteCarloExtension other) {
        if (other == null) return;

        // General
        cfgPutBool(K_ENABLED, other.isEnabled());
        cfgPutBool(K_DEBUG, other.isDebugEnabled());
        cfgPutInt(K_NUM_SIMS, other.getNumberOfSimulations());
        cfgPutBool(K_DETERMINISTIC, other.isUseDeterministicSeed());
        cfgPutLong(K_RANDOM_SEED, other.getRandomSeed());

        // Launch
        cfgPutDouble(K_ROD_ANGLE_SIGMA_DEG, other.getLaunchRodAngleStdDevDeg());
        cfgPutDouble(K_ROD_DIR_SIGMA_DEG, other.getLaunchRodDirectionStdDevDeg());
        cfgPutDouble(K_LAT_SIGMA_DEG, other.getLaunchLatitudeStdDevDeg());
        cfgPutDouble(K_LON_SIGMA_DEG, other.getLaunchLongitudeStdDevDeg());
        cfgPutDouble(K_ALT_SIGMA_M, other.getLaunchAltitudeStdDevM());

        // Atmosphere
        cfgPutDouble(K_WIND_DIR_SIGMA_DEG, other.getWindDirectionStdDevDeg());
        cfgPutDouble(K_TEMP_SIGMA_C, other.getTemperatureStdDevC());
        cfgPutDouble(K_PRES_SIGMA_MBAR, other.getPressureStdDevMbar());

        // Wind distributions
        cfgPutDouble(K_WIND_AVG_SIGMA_MPS, other.getWindSpeedAverageSigmaMps());
        cfgPutDouble(K_WIND_TURB_SIGMA_MPS, other.getWindSpeedTurbulenceSigmaMps());

        // Gusts
        cfgPutBool(K_GUST_ENABLED, other.isGustEventsEnabled());
        cfgPutInt(K_GUST_COUNT, other.getGustEventCount());
        cfgPutDouble(K_GUST_WINDOW_START_S, other.getGustWindowStartS());
        cfgPutDouble(K_GUST_WINDOW_END_S, other.getGustWindowEndS());
        cfgPutDouble(K_GUST_DURATION_MEAN_S, other.getGustDurationMeanS());
        cfgPutDouble(K_GUST_DURATION_SIGMA_S, other.getGustDurationSigmaS());
        cfgPutDouble(K_GUST_PEAK_MEAN_MPS, other.getGustPeakDeltaMeanMps());
        cfgPutDouble(K_GUST_PEAK_SIGMA_MPS, other.getGustPeakDeltaSigmaMps());

        // Shear
        cfgPutBool(K_SHEAR_ENABLED, other.isShearLayerEnabled());
        cfgPutDouble(K_SHEAR_CENTER_ALT_M, other.getShearCenterAltM());
        cfgPutDouble(K_SHEAR_THICKNESS_M, other.getShearThicknessM());
        cfgPutDouble(K_SHEAR_DELTA_MEAN_MPS, other.getShearDeltaMeanMps());
        cfgPutDouble(K_SHEAR_DELTA_SIGMA_MPS, other.getShearDeltaSigmaMps());

        // Vehicle / Motor physics overrides
        cfgPutDouble(K_CD_MULT_SIGMA, other.getCdMultiplierSigma());
        cfgPutDouble(K_THRUST_MULT_SIGMA, other.getThrustMultiplierSigma());
        cfgPutDouble(K_MASS_MULT_SIGMA, other.getMassMultiplierSigma());

        // Batch
        cfgPutInt(K_WORKER_THREADS, other.getWorkerThreads());
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
