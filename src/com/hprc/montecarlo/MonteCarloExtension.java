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
                if (windSpeedStdDev > 0) {
                    double base = level.getSpeed();
                    double varied = Math.max(0.0, base + rng.nextGaussian() * windSpeedStdDev);
                    level.setSpeed(varied);
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
            double variedK = baseK + rng.nextGaussian() * temperatureStdDevC; // delta C == delta K
            opts.setLaunchTemperature(variedK);
        }

        if (pressureStdDevMbar > 0) {
            double basePa = opts.getLaunchPressure();
            double sigmaPa = pressureStdDevMbar * 100.0;
            double variedPa = Math.max(0.0, basePa + rng.nextGaussian() * sigmaPa);
            opts.setLaunchPressure(variedPa);
        }

        // --- Initial Velocity (via SimulationListener) ---
        if (initialVelocityStdDev > 0) {
            // We cannot set this on SimulationOptions easily. 
            // Instead, we attach a listener to inject velocity at t=0.
            double addedVelocity = Math.abs(rng.nextGaussian() * initialVelocityStdDev);
            
            conditions.getSimulationListenerList().add(new AbstractSimulationListener() {
                @Override
                public void startSimulation(SimulationStatus status) {
                    try {
                        // Get current velocity (usually 0,0,0)
                        Coordinate current = status.getRocketVelocity();
                        
                        // Assuming we want to add velocity in the Z (up) direction 
                        // or simply set the magnitude if it's a rail launch.
                        // For simplicity, we add to Z (altitude axis in simulation frame usually).
                        // A more complex implementation would project this along the launch rod vector.
                        Coordinate newVel = current.add(new Coordinate(0, 0, addedVelocity));
                        
                        status.setRocketVelocity(newVel);
                        
                        if (debugEnabled) {
                            log.debug("MC applied: injected initial velocity {} m/s", addedVelocity);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to inject initial velocity", e);
                    }
                }
            });
        }

        // --- Mass Variation ---
        if (massStdDevPercent > 0) {
            // Calculate a global multiplier (e.g. 1.05 for +5%)
            double factor = 1.0 + (rng.nextGaussian() * (massStdDevPercent / 100.0));
            
            // Apply to the rocket components
            Rocket rocket = conditions.getRocket();
            applyMassMultiplier(rocket, factor);
            
            if (debugEnabled) {
                log.debug("MC applied: mass factor {}", factor);
            }
        }
    }

    /**
     * Recursively scales the mass of the component and all its children.
     */
    private void applyMassMultiplier(RocketComponent component, double factor) {
        // getComponentMass() returns the mass of THIS component (including material), excluding children.
        double originalMass = component.getComponentMass();
        
        // We set an override. This overrides the material calculation for this specific simulation instance.
        // Since we cloned the rocket in the runner, this is safe.
        if (originalMass > 0) {
            component.setOverrideMass(originalMass * factor);
        }

        for (RocketComponent child : component.getChildren()) {
            applyMassMultiplier(child, factor);
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

    private static void tryInvokeSetter(Object target, String methodName, Class<?> ignored, double value) {
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
        } catch (Exception ignored2) {
            // intentionally silent: method may not exist in this OR version
        }
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