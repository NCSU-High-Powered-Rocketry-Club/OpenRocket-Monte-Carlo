package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
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

        public WindLevel(double altitudeM, double speedMps, double directionRad, double stdDevMps) {
            this.altitudeM = altitudeM;
            this.speedMps = speedMps;
            this.directionRad = directionRad;
            this.stdDevMps = stdDevMps;
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

    public final List<WindLevel> windLevels = new ArrayList<>();

    // Outputs (from SimulationData)
    public final SimulationData results;

    public MonteCarloRunRecord(
            int runIndex,
            String simulationName,
            boolean deterministicSeed,
            long seedUsed,
            SimulationOptions opts,
            SimulationData results
    ) {
        this.runIndex = runIndex;
        this.simulationName = simulationName;
        this.deterministicSeed = deterministicSeed;
        this.seedUsed = seedUsed;

        this.launchLatitudeDeg = opts.getLaunchLatitude();
        this.launchLongitudeDeg = opts.getLaunchLongitude();
        this.launchAltitudeM = opts.getLaunchAltitude();

        this.launchRodAngleRad = opts.getLaunchRodAngle();
        this.launchRodDirectionRad = opts.getLaunchRodDirection();

        this.launchTemperatureK = opts.getLaunchTemperature();
        this.launchPressurePa = opts.getLaunchPressure();

        if (opts.getMultiLevelWindModel() != null) {
            for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : opts.getMultiLevelWindModel().getLevels()) {
                windLevels.add(new WindLevel(
                        lvl.getAltitude(),
                        lvl.getSpeed(),
                        lvl.getDirection(),
                        lvl.getStandardDeviation()
                ));
            }
        }

        this.results = results;
    }
}