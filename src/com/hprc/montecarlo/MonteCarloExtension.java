package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import info.openrocket.core.util.Coordinate;
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
    private double windSpeedStdDev = 0.0;          // m/s (UnitSelector can display mph etc)
    private double windDirectionStdDevDeg = 0.0;   // degrees
    private double temperatureStdDevC = 0.0;       // degC (delta == delta K)
    private double pressureStdDevMbar = 0.0;       // mbar (1 mbar = 100 Pa)

    // NEW: optional average wind speed override
    private boolean useAverageWindSpeed = false;
    private double averageWindSpeedMps = 0.0;      // m/s

    // Vehicle / initial state (may require deeper hooks depending on OR API)
    private double massStdDevPercent = 0.0;        // %
    private double initialVelocityStdDev = 0.0;    // m/s

    // Batch execution (JVM threads)
    private int workerThreads = 1;

    @Override
    public void initialize(final SimulationConditions conditions) throws SimulationException {
        if (!enabled) return;

        SimulationOptions opts = resolveOptions(conditions);
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

        // --- Wind model: vary each wind level ---
        if (opts.getMultiLevelWindModel() != null) {
            for (MultiLevelPinkNoiseWindModel.LevelWindModel level : opts.getMultiLevelWindModel().getLevels()) {

                // speed in m/s
                if (useAverageWindSpeed) {
                    double base = Math.max(0.0, averageWindSpeedMps);
                    double varied = base;
                    if (windSpeedStdDev > 0) {
                        varied = base + rng.nextGaussian() * windSpeedStdDev;
                        if (varied < 0.0) varied = 0.0;
                    }
                    level.setSpeed(varied);

                    if (debugEnabled) {
                        log.debug("MC wind speed (avg override): base={} m/s, σ={} m/s, varied={} m/s",
                                base, windSpeedStdDev, varied);
                    }
                } else {
                    if (windSpeedStdDev > 0) {
                        double base = level.getSpeed();          // current sim’s wind speed (m/s)
                        double varied = base + rng.nextGaussian() * windSpeedStdDev;
                        if (varied < 0.0) varied = 0.0;           // keep physical
                        level.setSpeed(varied);

                        if (debugEnabled) {
                            log.debug("MC wind speed: base={} m/s, σ={} m/s, varied={} m/s", base, windSpeedStdDev, varied);
                        }
                    }
                }

                // direction stored in radians
                if (windDirectionStdDevDeg > 0) {
                    double base = level.getDirection();
                    double varied = base + Math.toRadians(rng.nextGaussian() * windDirectionStdDevDeg);
                    level.setDirection(varied);
                }
            }
        }

        // --- Temperature/pressure at launch ---
        if (temperatureStdDevC > 0) {
            double baseK = opts.getLaunchTemperature();
            if (!Double.isFinite(baseK) || baseK <= 0.0) baseK = DEFAULT_T0_K;

            // clamp to something physically sane (tweak bounds for your use case)
            double sigmaK = clamp(temperatureStdDevC, 0.0, 50.0);     // e.g., <= 50°C sigma
            double variedK = baseK + rng.nextGaussian() * sigmaK;
            variedK = clamp(variedK, 180.0, 330.0);                   // e.g., -93°C to +57°C

            opts.setLaunchTemperature(variedK);
        }

        if (pressureStdDevMbar > 0) {
            double basePa = opts.getLaunchPressure();
            if (!Double.isFinite(basePa) || basePa <= 0.0) basePa = DEFAULT_P0_PA;

            double sigmaMbar = clamp(pressureStdDevMbar, 0.0, 200.0); // e.g., <= 200 mbar sigma
            double variedPa = basePa + rng.nextGaussian() * (sigmaMbar * 100.0);
            variedPa = clamp(variedPa, 50_000.0, 120_000.0);          // e.g., ~500–1200 mbar

            opts.setLaunchPressure(variedPa);
        }

        // --- Initial Velocity (via SimulationListener) ---
        if (initialVelocityStdDev > 0) {
            // Draw from N(0, sigma) to preserve the requested standard deviation
            final double injectedVelocity = rng.nextGaussian() * initialVelocityStdDev;

            conditions.getSimulationListenerList().add(new AbstractSimulationListener() {
                @Override
                public void startSimulation(SimulationStatus status) {
                    try {
                        Coordinate current = status.getRocketVelocity();

                        // Add along +Z; if you later want rod-aligned, project accordingly.
                        Coordinate newVel = current.add(new Coordinate(0, 0, injectedVelocity));

                        status.setRocketVelocity(newVel);

                        if (debugEnabled) {
                            log.debug("MC applied: injected initial velocity {} m/s", injectedVelocity);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to inject initial velocity", e);
                    }
                }
            });
        }

        // --- Mass Variation ---
        // IMPORTANT: always clear existing mass overrides first.
        // OpenRocket may reuse the same Rocket instance between runs; overrides can persist.
        Rocket rocket = conditions.getRocket();
        clearMassOverridesRecursive(rocket);

        if (massStdDevPercent > 0) {
            double r = massStdDevPercent / 100.0;

            // Mean=1, stddev=r (relative). Clamp only to keep physical (>0).
            double factor = 1.0 + rng.nextGaussian() * r;
            factor = clamp(factor, 1e-6, 100.0);

            applyMassMultiplier(rocket, factor);

            if (debugEnabled) {
                log.debug("MC applied: mass σ={}%, multiplier={}", massStdDevPercent, factor);
            }
        } else {
            if (debugEnabled) {
                log.debug("MC applied: mass σ=0%, cleared any previous mass overrides");
            }
        }
    }

    /**
     * Draws a strictly-positive multiplicative factor with mean 1 and relative stddev = r.
     *
     * For r = 0, returns exactly 1.
     */
    // private static double drawLogNormalMultiplierMeanOne(double r, Random rng) {
    //     if (!(r > 0.0) || !Double.isFinite(r)) {
    //         return 1.0;
    //     }

    //     // s^2 = ln(1 + r^2), mu = -s^2/2 ensures mean = 1
    //     double s2 = Math.log1p(r * r);
    //     double s = Math.sqrt(s2);
    //     double mu = -0.5 * s2;

    //     double z = rng.nextGaussian();
    //     double factor = Math.exp(mu + s * z);

    //     // ultra-defensive guard
    //     if (!Double.isFinite(factor) || factor <= 0.0) return 1.0;
    //     return factor;
    // }

    /**
     * Recursively scales the mass of the component and all its children.
     */
    private void applyMassMultiplier(RocketComponent component, double factor) {
        // getComponentMass() returns the mass of THIS component (including material), excluding children.
        double originalMass = component.getComponentMass();
        if (originalMass > 0 && Double.isFinite(originalMass) && Double.isFinite(factor) && factor > 0) {
            component.setOverrideMass(originalMass * factor);
            // Some OR versions have an explicit toggle; enable if available.
            tryInvokeBooleanSetter(component, "setMassOverridden", true);
        }

        for (RocketComponent child : component.getChildren()) {
            applyMassMultiplier(child, factor);
        }
    }

    /**
     * Clears any per-component mass override so a σ=0 run truly uses the base rocket mass.
     * Uses reflection to tolerate OpenRocket API differences.
     */
    private static void clearMassOverridesRecursive(RocketComponent component) {
        if (component == null) return;

        // Newer OR: clearOverrideMass()
        try {
            Method m = component.getClass().getMethod("clearOverrideMass");
            m.invoke(component);
        } catch (Exception ignored) {
            // Older OR: setMassOverridden(false) (or similar)
            tryInvokeBooleanSetter(component, "setMassOverridden", false);

            // Another variant seen in forks: setOverrideMassEnabled(false)
            tryInvokeBooleanSetter(component, "setOverrideMassEnabled", false);
        }

        for (RocketComponent child : component.getChildren()) {
            clearMassOverridesRecursive(child);
        }
    }

    private static void tryInvokeBooleanSetter(Object target, String methodName, boolean value) {
        try {
            Method m = target.getClass().getMethod(methodName, boolean.class);
            m.invoke(target, value);
        } catch (Exception ignored) {
            // method may not exist in this OR version
        }
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

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double wrapLongitudeDeg(double lon) {
        // wrap to [-180, 180)
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

    public double getWindSpeedStdDev() { return windSpeedStdDev; }
    public void setWindSpeedStdDev(double v) { windSpeedStdDev = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    // NEW: average wind speed toggle + value
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

    public double getMassStdDevPercent() { return massStdDevPercent; }
    public void setMassStdDevPercent(double v) { massStdDevPercent = clamp(finiteOrZero(v), 0.0, 100.0); fireChangeEvent(); }

    public double getInitialVelocityStdDev() { return initialVelocityStdDev; }
    public void setInitialVelocityStdDev(double v) { initialVelocityStdDev = Math.max(0.0, finiteOrZero(v)); fireChangeEvent(); }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = Math.max(1, workerThreads);
    }

    private static double finiteOrZero(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}