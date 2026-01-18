package com.hprc.montecarlo;

import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.Chars;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Writes Monte Carlo batch results into a "wide" CSV suitable for histogram/scatter plots.
 */
public final class MonteCarloCsvExporter {

    private static final double FEET_PER_M = 3.28084;

    private MonteCarloCsvExporter() {}

    public static void exportDetailedCsv(File file, List<MonteCarloRunRecord> records) throws IOException {
        if (records == null || records.isEmpty()) {
            throw new IOException("No records to export.");
        }

        // Determine max wind level count for column layout
        int maxWindLevels = records.stream().mapToInt(r -> r.windLevels.size()).max().orElse(0);

        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            // Header
            StringBuilder header = new StringBuilder();
            header.append("run_index,simulation_name,deterministic_seed,seed_used,")
                  .append("launch_lat_deg,launch_lon_deg,launch_alt_m,")
                  .append("launch_rod_angle_deg,launch_rod_direction_deg,")
                  .append("temperature_C,pressure_mbar,");

            for (int i = 0; i < maxWindLevels; i++) {
                int n = i + 1;
                header.append("wind_level_").append(n).append("_alt_m,")
                      .append("wind_level_").append(n).append("_speed_mps,")
                      .append("wind_level_").append(n).append("_speed_mph,")
                      .append("wind_level_").append(n).append("_dir_deg,")
                      .append("wind_level_").append(n).append("_std_mps,")
                      .append("wind_level_").append(n).append("_turb_intensity,");
            }

            header.append("apogee_m,apogee_ft,apogee_time_s,landing_time_s,")
                  .append("landing_east_m,landing_north_m,landing_lat_deg,landing_lon_deg,")
                  .append("landing_downrange_m,landing_crossrange_m,has_apogee,has_landing");

            w.write(header.toString());
            w.write("\n");

            // Rows
            for (MonteCarloRunRecord r : records) {
                SimulationData d = r.results;

                StringBuilder row = new StringBuilder();
                row.append(r.runIndex).append(",")
                   .append(csv(safe(r.simulationName))).append(",")
                   .append(r.deterministicSeed).append(",")
                   .append(r.seedUsed).append(",")

                   .append(r.launchLatitudeDeg).append(",")
                   .append(r.launchLongitudeDeg).append(",")
                   .append(r.launchAltitudeM).append(",")

                   .append(Math.toDegrees(r.launchRodAngleRad)).append(",")
                   .append(Math.toDegrees(r.launchRodDirectionRad)).append(",")

                   .append(UnitGroup.UNITS_TEMPERATURE.getUnit(Chars.DEGREE + "C").toUnit(r.launchTemperatureK)).append(",")
                   .append(UnitGroup.UNITS_PRESSURE.getUnit("mbar").toUnit(r.launchPressurePa)).append(",");

                for (int i = 0; i < maxWindLevels; i++) {
                    if (i < r.windLevels.size()) {
                        MonteCarloRunRecord.WindLevel wl = r.windLevels.get(i);
                        double mph = UnitGroup.UNITS_VELOCITY.getUnit("mph").toUnit(wl.speedMps);
                        double dirDeg = UnitGroup.UNITS_ANGLE.getUnit(String.valueOf(Chars.DEGREE)).toUnit(wl.directionRad);

                        row.append(wl.altitudeM).append(",")
                           .append(wl.speedMps).append(",")
                           .append(mph).append(",")
                           .append(dirDeg).append(",")
                           .append(wl.stdDevMps).append(",")
                           .append(wl.turbIntensity).append(",");
                    } else {
                        row.append(",,,,,,"); // 6 columns
                    }
                }

                row.append(d.apogee_m).append(",")
                   .append(d.apogee_m * FEET_PER_M).append(",")
                   .append(d.apogeeTime_s).append(",")
                   .append(d.landingTime_s).append(",")
                   .append(d.landingEast_m).append(",")
                   .append(d.landingNorth_m).append(",")
                   .append(d.landingLat_deg).append(",")
                   .append(d.landingLon_deg).append(",")
                   .append(d.landingDownrange_m).append(",")
                   .append(d.landingCrossrange_m).append(",")
                   .append(d.hasApogee).append(",")
                   .append(d.hasLanding);

                w.write(row.toString());
                w.write("\n");
            }
        }
    }

    private static String csv(String s) {
        // Minimal CSV escaping
        if (s == null) return "";
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String out = s.replace("\"", "\"\"");
        return needQuotes ? "\"" + out + "\"" : out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}