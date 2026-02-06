package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SimulationData
 *
 * Purpose:
 * Extract key scalar results and landing location from an OpenRocket simulation run,
 * using Python-style logic:
 * - detect event time (apogee, landing)
 * - choose nearest sample index: argmin(|t - t_event|)
 * - read POSITION_X/Y at landing index
 * - rotate downrange/crossrange -> ENU east/north using launch rod direction
 * - compute landing lat/lon from ENU + launch lat/lon (degrees)
 *
 * Output feeds MonteCarloRunRecord and LandingDispersion6DOF.
 */
public final class SimulationData {

    private static final Logger log = LoggerFactory.getLogger(SimulationData.class);

    // ---------------------------------------------------------------------------
    // Fields stored per run
    // ---------------------------------------------------------------------------

    // Basic outcome
    public double apogee_m;           // Altitude (MSL or AGL depending on simulation configuration)
    public double apogeeTime_s;

    public double landingTime_s;
    public double landingEast_m;      // ENU (meters)
    public double landingNorth_m;     // ENU (meters)
    public double landingLat_deg;     // Computed from ENU + launch lat/lon
    public double landingLon_deg;

    // Optional debugging: raw OR frame at landing (downrange/crossrange)
    public double landingDownrange_m;
    public double landingCrossrange_m;

    public double maxVelocity_mps;
    public double maxAcceleration_mps2;
    public double flightTime_s;

    // Launch site used
    public double launchLat_deg;
    public double launchLon_deg;
    public double launchRodDirection_deg;

    // Status flags
    public boolean hasLanding = false;
    public boolean hasApogee = false;
    public String simulationName;

    // ---------------------------------------------------------------------------
    // Factory / Main Entry
    // ---------------------------------------------------------------------------

    /**
     * Extract from Simulation after it has been simulated.
     *
     * @param sim         - already run simulation
     * @param branchIndex - usually 0 for the main flight branch
     */
    public static SimulationData fromSimulation(Simulation sim, int branchIndex) {

        SimulationData out = new SimulationData();
        out.simulationName = sim.getName();

        // 1) Pull launch site & heading from simulation options
        SimulationOptions opts = sim.getOptions();
        out.launchLat_deg = opts.getLaunchLatitude();
        out.launchLon_deg = opts.getLaunchLongitude();
        // OR stores rod direction in radians; convert to degrees for consistency
        out.launchRodDirection_deg = Math.toDegrees(opts.getLaunchRodDirection());

        // 2) Pull flight branch data
        if (!sim.hasSimulationData()) {
            log.error("Simulation '{}' has no data; this means the simulation failed to run or crashed.", sim.getName());
            throw new IllegalStateException("Simulation '" + sim.getName() + "' has no data; run simulation first.");
        }

        FlightData flight = sim.getSimulatedData();
        FlightDataBranch branch = flight.getBranch(branchIndex);

        // 3) Extract time series arrays needed
        List<Double> time = branch.get(FlightDataType.TYPE_TIME);
        List<Double> altitude = branch.get(FlightDataType.TYPE_ALTITUDE);
        List<Double> posX = branch.get(FlightDataType.TYPE_POSITION_X);
        List<Double> posY = branch.get(FlightDataType.TYPE_POSITION_Y);

        List<Double> velocity = branch.get(FlightDataType.TYPE_VELOCITY_TOTAL);
        List<Double> accel = branch.get(FlightDataType.TYPE_ACCELERATION_TOTAL);

        // Validate lengths
        int n = min(time.size(), altitude.size(), posX.size(), posY.size());
        if (n <= 0) {
            log.error("Flight data arrays empty for simulation '{}'. This indicates the simulation crashed immediately.", sim.getName());
            throw new IllegalStateException("Flight data arrays empty for simulation: " + sim.getName());
        }

        log.debug("Simulation '{}': {} data points collected", sim.getName(), n);

        // 4) Determine apogee time
        // Prefer event timestamp if present; fallback to argmax(altitude)
        Double apogeeTime = findEventTime(branch, FlightEvent.Type.APOGEE);

        if (isValid(apogeeTime)) {
            int iApo = nearestIndex(time, apogeeTime);
            out.apogeeTime_s = time.get(iApo);
            out.apogee_m = altitude.get(iApo);
            out.hasApogee = true;
        } else {
            int iApo = argMaxIndex(altitude, n);
            out.apogeeTime_s = time.get(iApo);
            out.apogee_m = altitude.get(iApo);
            out.hasApogee = true;
        }

        // 4b) Basic scalars from time series
        out.flightTime_s = time.get(n - 1);
        out.maxVelocity_mps = maxFinite(velocity);
        out.maxAcceleration_mps2 = maxFinite(accel);

        // 5) Determine landing time (GROUND_HIT) and pick nearest index
        Double landingTime = findEventTime(branch, FlightEvent.Type.GROUND_HIT);

        if (isValid(landingTime)) {
            int iLand = nearestIndex(time, landingTime);

            out.landingTime_s = time.get(iLand);
            out.hasLanding = true;

            // 5a) Extract raw OR X/Y at landing index
            double down = posX.get(iLand);
            double cross = posY.get(iLand);

            out.landingDownrange_m = down;
            out.landingCrossrange_m = cross;

            // 5b) Rotate and Compute Lat/Lon
            computeLandingENU(out, down, cross);

        } else {
            // No landing event found (sim stopped early or crashed).
            // Fallback: use the very last sample point available.
            int iLand = n - 1;

            out.landingTime_s = time.get(iLand);
            out.hasLanding = false;

            double down = posX.get(iLand);
            double cross = posY.get(iLand);
            out.landingDownrange_m = down;
            out.landingCrossrange_m = cross;

            computeLandingENU(out, down, cross);
        }

        return out;
    }

    /**
     * Helper to perform coordinate rotation and Lat/Lon conversion.
     */
    private static void computeLandingENU(SimulationData out, double posX, double posY) {
        // OpenRocket 24.12: TYPE_POSITION_X = East, TYPE_POSITION_Y = North
        // in the world ENU frame. No rotation needed.
        out.landingEast_m  = posX;
        out.landingNorth_m = posY;

        double[] ll = LandingDispersion6DOF.enuToLatLonDeg(
                out.landingEast_m,
                out.landingNorth_m,
                out.launchLat_deg,
                out.launchLon_deg
        );
        out.landingLat_deg = ll[0];
        out.landingLon_deg = ll[1];
    }

    // ---------------------------------------------------------------------------
    // Event time extraction
    // ---------------------------------------------------------------------------

    private static Double findEventTime(FlightDataBranch branch, FlightEvent.Type type) {
        // Iterate branch events; return first matching time
        for (FlightEvent e : branch.getEvents()) {
            if (e.getType() == type) return e.getTime();
        }
        return Double.NaN;
    }

    private static boolean isValid(Double d) {
        return d != null && !Double.isNaN(d) && !Double.isInfinite(d);
    }

    // ---------------------------------------------------------------------------
    // Python-style nearest index selection
    // ---------------------------------------------------------------------------

    /**
     * Return index i that minimizes |time[i] - t|.
     * Matches Python: argmin(abs(time - t_event)).
     */
    private static int nearestIndex(List<Double> time, double tEvent) {
        int bestI = 0;
        double bestErr = Double.MAX_VALUE;

        for (int i = 0; i < time.size(); i++) {
            double err = Math.abs(time.get(i) - tEvent);
            if (err < bestErr) {
                bestErr = err;
                bestI = i;
            }
        }
        return bestI;
    }

    // ---------------------------------------------------------------------------
    // Argmax helper
    // ---------------------------------------------------------------------------

    private static int argMaxIndex(List<Double> arr, int n) {
        int bestI = 0;
        double bestV = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < n; i++) {
            double v = arr.get(i);
            if (v > bestV) {
                bestV = v;
                bestI = i;
            }
        }
        return bestI;
    }

    // ---------------------------------------------------------------------------
    // Utility math helpers
    // ---------------------------------------------------------------------------

    private static int min(int... vals) {
        int m = Integer.MAX_VALUE;
        for (int v : vals) {
            if (v < m) m = v;
        }
        return m;
    }

    private static double maxFinite(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double max = Double.NEGATIVE_INFINITY;
        for (Double v : values) {
            if (v == null || !Double.isFinite(v)) continue;
            if (v > max) max = v;
        }
        return (max == Double.NEGATIVE_INFINITY) ? Double.NaN : max;
    }
}