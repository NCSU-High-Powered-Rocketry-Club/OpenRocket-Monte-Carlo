package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Utility for running an OpenRocket Simulation in-process (no GUI).
 * Uses reflection to tolerate API differences between OpenRocket versions.
 */
public final class SimulationRunner {

    private SimulationRunner() { }

    public static void runSimulationInProcess(Simulation sim) throws Exception {
        if (sim == null) throw new IllegalArgumentException("sim is null");

        // IMPORTANT:
        // Prefer no-arg simulate() when it exists.
        // Simulation Extensions (AbstractSimulationExtension.initialize) are typically
        // wired into the normal simulation entry-point. Some OR builds also expose
        // simulate(SimulationListener... listeners); calling that overload can skip
        // the SimulationExtension pipeline depending on version.
        try {
            Method m = sim.getClass().getMethod("simulate");
            if (m.getParameterCount() == 0) {
                m.invoke(sim);
                return;
            }
        } catch (NoSuchMethodException ignored) {
            // fall through
        }

        // Fallback: simulate(SimulationListener... listeners)
        // Pass empty listener array so OR can still attach its own extension listeners.
        try {
            for (Method m : sim.getClass().getMethods()) {
                if (!m.getName().equals("simulate")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (!params[0].isArray()) continue;

                Object emptyListeners = java.lang.reflect.Array.newInstance(params[0].getComponentType(), 0);
                m.invoke(sim, emptyListeners);
                return;
            }
        } catch (Exception ignored) {
            // fall through
        }

        // Other no-arg fallbacks for forks/older builds
        String[] candidates = { "runSimulation", "run" };
        for (String name : candidates) {
            try {
                Method m = sim.getClass().getMethod(name);
                m.invoke(sim);
                return;
            } catch (NoSuchMethodException ignored) {
                // keep trying
            }
        }

        throw new NoSuchMethodException("No compatible simulation run method found on " + sim.getClass().getName());
    }

    // ---------------------------------------------------------------------
    // Landing extraction (filled-out pseudocode)
    // ---------------------------------------------------------------------

    /**
     * How to interpret FlightDataType.TYPE_POSITION_X/Y
     *
     * In many OpenRocket builds, X/Y are NOT true East/North; they're downrange/crossrange
     * in the launch-azimuth frame (especially when Launch Into Wind is enabled).
     *
     * If you treat X/Y as East/North when they are downrange/crossrange, you will get
     * "all points on a line" behavior.
     */
    public enum XYFrame {
        /** X=East, Y=North (rarely what you want if Launch Into Wind is enabled). */
        EAST_NORTH,

        /** X=Downrange, Y=Crossrange; rotate into East/North using launch rod direction. */
        DOWNRANGE_CROSSRANGE
    }

    /** Returned landing info for one branch of one simulation. */
    public static final class LandingInfo {
        public final int branchIndex;
        public final String branchName;

        public final double landingTime_s;

        // Raw values from OpenRocket branch arrays at landing index:
        public final double posX_m;
        public final double posY_m;

        // Interpreted as ENU meters relative to launch:
        public final double east_m;
        public final double north_m;

        // World coords computed from ENU + launch lat/lon:
        public final double landingLat_deg;
        public final double landingLon_deg;

        // Useful context
        public final double launchLat_deg;
        public final double launchLon_deg;
        public final double launchRodDirection_deg;

        LandingInfo(
                int branchIndex,
                String branchName,
                double landingTime_s,
                double posX_m,
                double posY_m,
                double east_m,
                double north_m,
                double landingLat_deg,
                double landingLon_deg,
                double launchLat_deg,
                double launchLon_deg,
                double launchRodDirection_deg
        ) {
            this.branchIndex = branchIndex;
            this.branchName = branchName;
            this.landingTime_s = landingTime_s;
            this.posX_m = posX_m;
            this.posY_m = posY_m;
            this.east_m = east_m;
            this.north_m = north_m;
            this.landingLat_deg = landingLat_deg;
            this.landingLon_deg = landingLon_deg;
            this.launchLat_deg = launchLat_deg;
            this.launchLon_deg = launchLon_deg;
            this.launchRodDirection_deg = launchRodDirection_deg;
        }
    }

    /**
     * Convenience: run the sim, then extract landing from branch 0 assuming DOWNRANGE/CROSSRANGE.
     */
    public static LandingInfo runAndExtractLanding(Simulation sim) throws Exception {
        runSimulationInProcess(sim);
        return extractLanding(sim, 0, XYFrame.DOWNRANGE_CROSSRANGE);
    }

    /**
     * Run the sim, then extract landing from the requested branch & frame interpretation.
     */
    public static LandingInfo runAndExtractLanding(Simulation sim, int branchIndex, XYFrame frame) throws Exception {
        runSimulationInProcess(sim);
        return extractLanding(sim, branchIndex, frame);
    }

    /**
     * Extract landing (impact) location from an already-simulated Simulation.
     *
     * This is the "filled out" version of your pseudocode:
     *   FlightDataBranch branch = simResult.getFlightData().getBranch(0);
     *   double x = branch.getLast(TYPE_POSITION_X);
     *   double y = branch.getLast(TYPE_POSITION_Y);
     *   rotate if needed
     *   convert ENU -> lat/lon
     */
    public static LandingInfo extractLanding(Simulation sim, int branchIndex, XYFrame frame) {
        if (sim == null) throw new IllegalArgumentException("sim is null");
        if (!sim.hasSimulationData()) {
            throw new IllegalStateException("Simulation has no simulated data. Run it first.");
        }

        final SimulationOptions opts = sim.getOptions();
        final double launchLatDeg = opts.getLaunchLatitude();
        final double launchLonDeg = opts.getLaunchLongitude();
        final double rodDirRad = opts.getLaunchRodDirection(); // radians, clockwise from North in OR
        final double rodDirDeg = Math.toDegrees(rodDirRad);

        final FlightData flight = sim.getSimulatedData();
        final FlightDataBranch branch = flight.getBranch(branchIndex);
        final String branchName = branch.getName();

        // --- Find landing time from events (GROUND_HIT), robustly ---
        double landingTime = Double.NaN;
        for (FlightEvent e : branch.getEvents()) {
            if (e.getType() == FlightEvent.Type.GROUND_HIT) {
                landingTime = e.getTime();
                break;
            }
        }

        // Pull arrays
        final List<Double> time = branch.get(FlightDataType.TYPE_TIME);
        final List<Double> xList = branch.get(FlightDataType.TYPE_POSITION_X);
        final List<Double> yList = branch.get(FlightDataType.TYPE_POSITION_Y);

        int n = Math.min(time.size(), Math.min(xList.size(), yList.size()));
        if (n < 1) throw new IllegalStateException("Branch has no time/position samples.");

        // landing index:
        //  - if we have landingTime, choose first index with time >= landingTime (or last)
        //  - otherwise, use last sample
        int landingIndex = n - 1;
        if (Double.isFinite(landingTime)) {
            for (int i = 0; i < n; i++) {
                if (time.get(i) >= landingTime) {
                    landingIndex = i;
                    break;
                }
            }
        } else {
            landingTime = time.get(landingIndex);
        }

        // --- Raw horizontal position at landing ---
        double x = xList.get(landingIndex);
        double y = yList.get(landingIndex);

        // --- Interpret X/Y as either EN or downrange/crossrange ---
        final double east_m;
        final double north_m;

        if (frame == XYFrame.EAST_NORTH) {
            // Direct interpretation (only correct if OR is truly outputting ENU)
            east_m = x;
            north_m = y;
        } else {
            // Default: X=downrange, Y=crossrange; rotate into East/North using launch azimuth.
            // psi is clockwise from North.
            double psi = rodDirRad;

            double down = x;
            double cross = y;

            // Convert down/cross -> East/North
            double east = down * Math.sin(psi) + cross * Math.cos(psi);
            double north = down * Math.cos(psi) - cross * Math.sin(psi);

            east_m = east;
            north_m = north;
        }

        // --- Convert ENU -> lat/lon (degrees) using launch site as reference ---
        double[] ll = enuToLatLonDeg(east_m, north_m, launchLatDeg, launchLonDeg);
        double landingLatDeg = ll[0];
        double landingLonDeg = ll[1];

        return new LandingInfo(
                branchIndex,
                branchName,
                landingTime,
                x,
                y,
                east_m,
                north_m,
                landingLatDeg,
                landingLonDeg,
                launchLatDeg,
                launchLonDeg,
                rodDirDeg
        );
    }

    private static double[] enuToLatLonDeg(double east_m, double north_m, double launchLatDeg, double launchLonDeg) {
        final double earthRadius_m = 6378137.0;
        double dLat = north_m / earthRadius_m;
        double dLon = east_m / (earthRadius_m * Math.cos(Math.toRadians(launchLatDeg)));
        double lat = launchLatDeg + Math.toDegrees(dLat);
        double lon = launchLonDeg + Math.toDegrees(dLon);
        return new double[] { lat, lon };
    }
}
