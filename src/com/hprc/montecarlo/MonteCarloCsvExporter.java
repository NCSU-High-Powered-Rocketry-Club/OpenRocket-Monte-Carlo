package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.Chars;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Writes Monte Carlo batch results into a "wide" CSV suitable for histogram/scatter plots.
 */
public final class MonteCarloCsvExporter {

    private static final double FEET_PER_M = 3.28084;

    // Cache units to avoid repeated lookup overhead in hot loops
    private static final Unit UNIT_TEMP_C = UnitGroup.UNITS_TEMPERATURE.getUnit(Chars.DEGREE + "C");
    private static final Unit UNIT_PRESSURE_MBAR = UnitGroup.UNITS_PRESSURE.getUnit("mbar");
    private static final Unit UNIT_VELOCITY_MPH = UnitGroup.UNITS_VELOCITY.getUnit("mph");
    private static final Unit UNIT_ANGLE_DEG = UnitGroup.UNITS_ANGLE.getUnit(String.valueOf(Chars.DEGREE));

    private MonteCarloCsvExporter() {}

    public static void exportDetailedCsv(File file, List<MonteCarloRunRecord> records) throws IOException {
        if (records == null || records.isEmpty()) {
            throw new IOException("No records to export.");
        }

        // Determine max wind level count based on actual data
        int maxWindLevels = records.stream().mapToInt(r -> r.windLevels.size()).max().orElse(1);
        
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file), 65536)) {
            writeHeader(w, maxWindLevels);
            for (MonteCarloRunRecord r : records) {
                writeRow(w, r, maxWindLevels);
            }
        }
    }

    /**
     * Starts an asynchronous CSV writer thread.
     * Returns a handler that accepts records and must be closed to finish writing.
     */
    public static AsyncCsvWriter createAsyncWriter(File file, Simulation baseSimulation) throws IOException {
        // Estimate header size from base simulation configuration
        int maxWindLevels = 1;
        try {
            maxWindLevels = countWindLevels(baseSimulation);
        } catch (Exception ignored) {}
        
        return new AsyncCsvWriter(file, Math.max(1, maxWindLevels));
    }

    /**
     * Consumer/AutoCloseable for streaming results to CSV on a dedicated I/O thread.
     */
    public static class AsyncCsvWriter implements Consumer<MonteCarloRunRecord>, AutoCloseable {
        private final BlockingQueue<MonteCarloRunRecord> queue = new LinkedBlockingQueue<>(5000); // Backpressure buffer
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Thread writerThread;
        private volatile IOException ioException;

        public AsyncCsvWriter(File file, int windLevelColumns) throws IOException {
            // Validate file creation early
            final BufferedWriter writer = new BufferedWriter(new FileWriter(file), 65536);
            
            // Write header immediately
            writeHeader(writer, windLevelColumns);

            this.writerThread = new Thread(() -> {
                try (writer) {
                    while (true) {
                        try {
                            MonteCarloRunRecord r = queue.poll(500, TimeUnit.MILLISECONDS);
                            if (r != null) {
                                writeRow(writer, r, windLevelColumns);
                            } else if (finished.get() && queue.isEmpty()) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    writer.flush();
                } catch (IOException e) {
                    ioException = e;
                }
            }, "CSV-Writer-Thread");
            
            this.writerThread.start();
        }

        @Override
        public void accept(MonteCarloRunRecord record) {
            if (record == null) return;
            if (ioException != null) throw new RuntimeException("Async CSV write failed", ioException);
            try {
                // Determine if we should block or drop? Blocking provides backpressure to simulations.
                queue.put(record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            finished.set(true);
            try {
                writerThread.join(10000); // wait up to 10s for drain
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (ioException != null) {
                throw new RuntimeException("Error during CSV write", ioException);
            }
        }
    }

    // --- Internal Helpers ---

    private static void writeHeader(BufferedWriter w, int maxWindLevels) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("run_index,simulation_name,deterministic_seed,seed_used,")
              .append("launch_lat_deg,launch_lon_deg,launch_alt_m,")
              .append("launch_rod_angle_deg,launch_rod_direction_deg,")
              .append("temperature_C,pressure_mbar,")
              .append("wind_model_type,wind_levels_used,")
              .append("wind_speed_avg_sigma_mps,wind_speed_avg_sigma_mph,")
              .append("wind_speed_turb_sigma_mps,wind_speed_turb_sigma_mph,");

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
              .append("landing_downrange_m,landing_crossrange_m,has_apogee,has_landing\n");

        w.write(header.toString());
    }

    private static void writeRow(BufferedWriter w, MonteCarloRunRecord r, int maxWindLevels) throws IOException {
        SimulationData d = r.results;
        StringBuilder row = new StringBuilder(512);

        row.append(r.runIndex).append(",")
           .append(csv(safe(r.simulationName))).append(",")
           .append(r.deterministicSeed).append(",")
           .append(r.seedUsed).append(",")

           .append(r.launchLatitudeDeg).append(",")
           .append(r.launchLongitudeDeg).append(",")
           .append(r.launchAltitudeM).append(",")

           .append(Math.toDegrees(r.launchRodAngleRad)).append(",")
           .append(Math.toDegrees(r.launchRodDirectionRad)).append(",")

           .append(UNIT_TEMP_C.toUnit(r.launchTemperatureK)).append(",")
           .append(UNIT_PRESSURE_MBAR.toUnit(r.launchPressurePa)).append(",")

           .append(csv(safe(r.windModelType))).append(",")
           .append(r.windLevels.size()).append(",")

           .append(r.windSpeedAverageSigmaMps).append(",")
           .append(UNIT_VELOCITY_MPH.toUnit(r.windSpeedAverageSigmaMps)).append(",")
           .append(r.windSpeedTurbulenceSigmaMps).append(",")
           .append(UNIT_VELOCITY_MPH.toUnit(r.windSpeedTurbulenceSigmaMps)).append(",");

        for (int i = 0; i < maxWindLevels; i++) {
            if (i < r.windLevels.size()) {
                MonteCarloRunRecord.WindLevel wl = r.windLevels.get(i);
                double mph = UNIT_VELOCITY_MPH.toUnit(wl.speedMps);
                double dirDeg = UNIT_ANGLE_DEG.toUnit(wl.directionRad);

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

        row.append("\n"); // explicit newline
        w.write(row.toString());
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String out = s.replace("\"", "\"\"");
        return needQuotes ? "\"" + out + "\"" : out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static int countWindLevels(Simulation s) {
        if (s == null) return 1;
        try {
            SimulationConditions c = getConditions(s.getOptions());
            Object mlObj = (c != null) ? getMultiLevelWindModel(c) : null;
            if (mlObj == null) mlObj = s.getOptions().getMultiLevelWindModel();
            
            if (mlObj instanceof MultiLevelPinkNoiseWindModel) {
                 return ((MultiLevelPinkNoiseWindModel) mlObj).getLevels().size();
            }
        } catch (Throwable ignored) {}
        return 1;
    }

    // Best-effort reflection helpers to peek at base simulation config
    private static SimulationConditions getConditions(info.openrocket.core.simulation.SimulationOptions opts) {
        try {
             Method m = opts.getClass().getMethod("getConditions");
             return (SimulationConditions) m.invoke(opts);
        } catch(Exception e) { return null; }
    }
    
    private static MultiLevelPinkNoiseWindModel getMultiLevelWindModel(SimulationConditions c) {
        try {
            Method m = c.getClass().getMethod("getMultiLevelWindModel");
            return (MultiLevelPinkNoiseWindModel) m.invoke(c);
        } catch(Exception e) { return null; }
    }
}