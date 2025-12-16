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

    private MonteCarloCsvExporter() {}

    public static void exportDetailedCsv(File file, List<MonteCarloRunRecord> records) throws IOException {
        if (records == null || records.isEmpty()) {
            throw new IOException("No records to export.");
        }

        // Determine max wind level count and branch count for column layout
        int maxWindLevels = records.stream().mapToInt(r -> r.windLevels.size()).max().orElse(0);
        int branchCount = records.get(0).results.getBranchName().size();

        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            // Header
            StringBuilder header = new StringBuilder();
            header.append("run_index,simulation_name,deterministic_seed,seed_used,")
                  .append("launch_lat_deg,launch_lon_deg,launch_alt_m,")
                  .append("launch_rod_angle_deg,launch_rod_direction_deg,")
                  .append("temperature_C,pressure_mbar,");

            // Wind level columns
            for (int i = 0; i < maxWindLevels; i++) {
                int n = i + 1;
                header.append("wind_level_").append(n).append("_alt_m,")
                      .append("wind_level_").append(n).append("_speed_mps,")
                      .append("wind_level_").append(n).append("_speed_mph,")
                      .append("wind_level_").append(n).append("_dir_deg,")
                      .append("wind_level_").append(n).append("_std_mps,");
            }

            // Global outputs
            header.append("apogee_m,apogee_ft,max_velocity_mps,max_mach,");

            // Branch outputs (same pattern you already export)
            String[] branchHeaders = {
                    "Initial_Stability",
                    "Min_Stability",
                    "Max_Stability",
                    "Apogee_Stability",
                    "Landing_Latitude_degN",
                    "Landing_Longitude_degE",
                    "Position_East_of_Launch_ft",
                    "Position_North_of_Launch_ft",
                    "Lateral_Velocity_at_Apogee_mps"
            };

            for (int b = 0; b < branchCount; b++) {
                String bn = safe(records.get(0).results.getBranchName().get(b));
                for (String lbl : branchHeaders) {
                    header.append(bn).append("_").append(lbl).append(",");
                }
            }

            // trim trailing comma
            header.setLength(header.length() - 1);
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

                // wind levels (pad missing)
                for (int i = 0; i < maxWindLevels; i++) {
                    if (i < r.windLevels.size()) {
                        MonteCarloRunRecord.WindLevel wl = r.windLevels.get(i);
                        double mph = UnitGroup.UNITS_VELOCITY.getUnit("mph").toUnit(wl.speedMps);
                        double dirDeg = UnitGroup.UNITS_ANGLE.getUnit(String.valueOf(Chars.DEGREE)).toUnit(wl.directionRad);

                        row.append(wl.altitudeM).append(",")
                           .append(wl.speedMps).append(",")
                           .append(mph).append(",")
                           .append(dirDeg).append(",")
                           .append(wl.stdDevMps).append(",");
                    } else {
                        row.append(",,,,,"); // 5 columns
                    }
                }

                // global outputs
                row.append(d.getApogee()).append(",")
                   .append(d.getApogeeInFeet()).append(",")
                   .append(d.getMaxVelocity()).append(",")
                   .append(d.getMaxMachNumber()).append(",");

                // branch outputs
                for (int b = 0; b < branchCount; b++) {
                    row.append(d.getInitStability().get(b)).append(",")
                       .append(d.getMinStability().get(b)).append(",")
                       .append(d.getMaxStability().get(b)).append(",")
                       .append(d.getApogeeStability().get(b)).append(",")
                       .append(d.getLandingLatitude().get(b)).append(",")
                       .append(d.getLandingLongitude().get(b)).append(",")
                       .append(d.getEastPostLandingInFeet().get(b)).append(",")
                       .append(d.getNorthPostLandingInFeet().get(b)).append(",")
                       .append(d.getApogeeLateralVelocity().get(b)).append(",");
                }

                // trim trailing comma
                row.setLength(row.length() - 1);
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