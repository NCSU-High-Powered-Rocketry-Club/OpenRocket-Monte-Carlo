package com.hprc.montecarlo;

import com.opencsv.CSVParser;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.WindModelType;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.extension.SimulationExtension;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.Chars;
import info.openrocket.core.util.GeodeticComputationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * The main class that is run
 */
public class SimulationEngine {
    private final static Random random = new Random();
    private final static Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final static Unit[] CSV_SIMULATION_UNITS = {
            UnitGroup.UNITS_TEMPERATURE.getUnit(Chars.DEGREE + "C"), // temp
            UnitGroup.UNITS_PRESSURE.getUnit("mbar")}; // pressure
    private final static Unit[] CSV_WIND_LEVEL_UNITS = {
            UnitGroup.UNITS_VELOCITY.getUnit("mph"), // speed
            UnitGroup.UNITS_VELOCITY.getUnit("mph"), // stdev
            UnitGroup.UNITS_ANGLE.getUnit(String.valueOf(Chars.DEGREE))}; // direction
    private final static Unit CSV_ALTITUDE_UNIT = UnitGroup.UNITS_LENGTH.getUnit("m");
    private final static int CSV_SIMULATION_COLUMN_COUNT = 2; // skip the date column
    private final static int CSV_WIND_LEVEL_COLUMN_COUNT = 3;
    private static final double FEET_METRES = 3.28084;
    
    /**
     * How many simulations we should run
     */
    public final int simulationCount;
    private final Configurator config = Configurator.getInstance();
    private final boolean keepSimulationObject = config.isKeepSimulationObject();
    private final OpenRocketDocument document;
    private final List<RunEntry> runs = new ArrayList<>();

    private static final class RunEntry {
        final Simulation simulation;
        SimulationData data;

        RunEntry(Simulation simulation) {
            this.simulation = simulation;
        }
    }

    // Updated to match Old Code variable naming
    private double windDirStdDev, tempStdDev, pressureStdDev;

    private static final double DEFAULT_T0_K = 288.15;      // 15 C
    private static final double DEFAULT_P0_PA = 101325.0;   // sea-level standard

    private static boolean finite(double v) {
        return Double.isFinite(v);
    }

    /**
     * If ISA atmosphere is disabled, OpenRocket expects launch temp/pressure to be valid.
     * Many of your CSV runs show pressure=0 mbar, which produces non-physical flight behavior.
     */
    private static void ensureManualAtmosphereHasValues(SimulationOptions opts) {
        if (opts == null) return;

        if (!opts.isISAAtmosphere()) {
            double t = opts.getLaunchTemperature();
            double p = opts.getLaunchPressure();

            if (!finite(t) || t <= 0.0) {
                opts.setLaunchTemperature(DEFAULT_T0_K);
            }
            if (!finite(p) || p <= 0.0) {
                opts.setLaunchPressure(DEFAULT_P0_PA);
            }
        }
    }

    /**
     * Creates a SimulationEngine with simulations specified by the given csvFile
     *
     * @param document OpenRocket document to be used with the simulation
     * @param csvFile  CSV file that specifies simulation conditions
     * @throws Exception On CSV parse fail
     */
    SimulationEngine(OpenRocketDocument document, File csvFile) throws Exception {
        this.document = document;
        Simulation defaultSimulation = this.generateDefaultSimulation();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            CSVParser parser = new CSVParser();
            String[] header = parser.parseLine(reader.readLine());

            List<Double> altitudes = new ArrayList<>();
            for (int i = CSV_SIMULATION_COLUMN_COUNT + 1; i < header.length; i += CSV_WIND_LEVEL_COLUMN_COUNT)
                altitudes.add(CSV_ALTITUDE_UNIT.fromUnit(Double.parseDouble(header[i])));
            log.info("Loaded wind level altitudes: {}", altitudes);

            String row;
            while ((row = reader.readLine()) != null) {
                String[] rawData = parser.parseLine(row);

                String date = rawData[0];
                double[] simData = Arrays.stream(rawData).skip(1) // skip date
                        .mapToDouble(Double::parseDouble).toArray();

                // convert to OR internal units (SI units)
                for (int i = 0; i < CSV_SIMULATION_COLUMN_COUNT; i++)
                    simData[i] = CSV_SIMULATION_UNITS[i].fromUnit(simData[i]);

                for (int i = CSV_SIMULATION_COLUMN_COUNT; i < simData.length; i++)
                    simData[i] = CSV_WIND_LEVEL_UNITS[(i - CSV_SIMULATION_COLUMN_COUNT) % CSV_WIND_LEVEL_COLUMN_COUNT].fromUnit(simData[i]);

                log.info("Creating simulation {}", date);
                Simulation simulation = new Simulation(document, document.getRocket());
                simulation.copySimulationOptionsFrom(defaultSimulation.getOptions()); // copy default options
                simulation.setName(date);
                simulation.getOptions().setLaunchTemperature(simData[0]);
                simulation.getOptions().setLaunchPressure(simData[1]);

                MultiLevelPinkNoiseWindModel windModel = simulation.getOptions().getMultiLevelWindModel();
                for (int i = 0; i < altitudes.size(); i++) {
                    windModel.addWindLevel(altitudes.get(i),
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT],     // Speed
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT + 2], // Direction
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT + 1]);// StDev
                }

                runs.add(new RunEntry(simulation));
            }
        }
        this.simulationCount = runs.size();
    }

    /**
     * Creates a SimulationEngine with the passed values. Does not create the simulation objects.
     * Must call createMonteCarloSimulations to finish initialization.
     *
     * @param document        OpenRocket document to be used with the simulation
     * @param simulationCount Number of simulations
     * @param windDirStdDev   Wind direction standard deviation (Degrees)
     * @param tempStdDev      Temperature standard deviation
     * @param pressureStdDev  Pressure standard deviation
     */
    SimulationEngine(OpenRocketDocument document, int simulationCount,
                     double windDirStdDev, double tempStdDev, double pressureStdDev) {
        this.document = document;
        this.simulationCount = simulationCount;
        this.windDirStdDev = windDirStdDev;
        this.tempStdDev = tempStdDev;
        this.pressureStdDev = pressureStdDev;
    }

    /**
     * Creates a SimulationEngine with existing simulations in the document
     *
     * @param document OpenRocket document to be used with the simulation
     */
    SimulationEngine(OpenRocketDocument document) {
        this.document = document;

        List<Simulation> sims = document.getSimulations();
        this.simulationCount = sims.size();

        for (Simulation sim : sims) {
            runs.add(new RunEntry(sim));
        }
    }

    /**
     * Choose a random number from a Gaussian distribution with a given mean and standard deviation
     *
     * @param mu    Mean
     * @param sigma Standard deviation
     */
    private static double randomGauss(double mu, double sigma) {
        return SimulationEngine.random.nextGaussian() * sigma + mu;
    }

    /**
     * Creates simulations with randomized conditions based on referenceSim and provided values at construct time
     *
     * @param referenceSim Reference simulation to copy base conditions, extensions from
     * @implNote Clears existing simulations
     */
    public void createMonteCarloSimulations(Simulation referenceSim) {
        runs.clear();
        for (int i = 0; i < simulationCount; i++) {
            // IMPORTANT: use the document rocket.
            // Motor selection is tied to FlightConfigurations owned by the document rocket;
            // simulating with a copied rocket can silently fall back to "[No motors]" and end at t=0.
            Simulation sim = new Simulation(document, document.getRocket());
            sim.setName("Simulation " + i);
            log.info("Generating conditions for {}", sim.getName());

            sim.copySimulationOptionsFrom(referenceSim.getOptions());

            sim.getSimulationExtensions().clear();
            for (SimulationExtension c : referenceSim.getSimulationExtensions()) {
                sim.getSimulationExtensions().add(c.clone());
            }

            ensureManualAtmosphereHasValues(sim.getOptions());

            configureMonteCarloSimulationOptions(sim.getOptions());
            runs.add(new RunEntry(sim));
        }
    }

    /**
     * Set the Monte-Carlo conditions for the flight simulation
     *
     * @param opts The SimulationOptions object of the simulation
     */
    private void configureMonteCarloSimulationOptions(SimulationOptions opts) {

        for (MultiLevelPinkNoiseWindModel.LevelWindModel windLevel : opts.getMultiLevelWindModel().getLevels()) {
            // Speed
            double windSpeed = randomGauss(windLevel.getSpeed(), windLevel.getStandardDeviation());
            windLevel.setSpeed(windSpeed);
            log.debug("Cond @ {}: Avg WindSpeed: {}m/s", windLevel.getAltitude(), windSpeed);

            // Direction
            // OpenRocket stores direction in Radians. StdDev is provided in Degrees.
            // We convert the Mean to Degrees, apply the Gaussian noise (in degrees), then convert back to Radians.
            double meanDirDeg = Math.toDegrees(windLevel.getDirection());
            double windDirection = randomGauss(meanDirDeg, windDirStdDev);
            windLevel.setDirection(Math.toRadians(windDirection));
            log.debug("Cond @ {}: windDirection: {}degrees", windLevel.getAltitude(), windDirection);
        }

        double temperature = randomGauss(opts.getLaunchTemperature(), tempStdDev);
        if (!Double.isFinite(temperature) || temperature <= 0.0) temperature = DEFAULT_T0_K;
        opts.setLaunchTemperature(temperature);
        log.debug("Cond: Temperature: {}K", temperature);

        double pressure = randomGauss(opts.getLaunchPressure(), pressureStdDev);
        if (!Double.isFinite(pressure) || pressure <= 0.0) pressure = DEFAULT_P0_PA;
        opts.setLaunchPressure(pressure);
        log.debug("Cond: Pressure: {}Pa", pressure);
    }

    /**
     * Generates a reference simulation with default values.
     *
     * @return Reference simulation with default values
     */
    public Simulation generateDefaultSimulation() {
        Simulation defaultSimulation = new Simulation(document, document.getRocket());
        defaultSimulation.setName("Monte-Carlo Simulation");
        SimulationOptions opts = defaultSimulation.getOptions();

        opts.setLaunchLatitude(config.getLaunchLatitude());
        opts.setLaunchLongitude(config.getLaunchLongitude());
        opts.setLaunchAltitude(config.getLaunchAltitude());

        opts.setISAAtmosphere(false);
        ensureManualAtmosphereHasValues(opts);

        opts.setWindModelType(WindModelType.MULTI_LEVEL);
        opts.getMultiLevelWindModel().clearLevels();

        opts.setLaunchRodLength(config.getLaunchRodLength());
        opts.setLaunchIntoWind(config.isLaunchIntoWind());
        opts.setLaunchRodAngle(config.getLaunchRodAngle());
        opts.setLaunchRodDirection(config.getLaunchRodDirection());

        opts.setGeodeticComputation(GeodeticComputationStrategy.WGS84);
        opts.setMaxSimulationTime(config.getMaxSimulationTime());

        return defaultSimulation;
    }

    /**
     * Gets a range of simulations
     *
     * @param start starting index
     * @param size  number of simulations following the start to return
     * @return list of simulations
     */
    public List<Simulation> getSimulations(int start, int size) {
        return runs.stream().skip(start).limit(size).map(r -> r.simulation).toList();
    }

    public List<SimulationData> getData() {
        return runs.stream().map(r -> r.data).filter(d -> d != null).toList();
    }

    public void processSimulationData() {
        for (RunEntry r : runs) {
            try {
                if (r.data == null && r.simulation.hasSimulationData()) {
                    r.data = SimulationData.fromSimulation(r.simulation, 0);
                }
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

    public void summarizeSimulations() {
        List<SimulationData> results = getData();
        if (results.size() < 2) {
            log.warn("Not enough processed data to summarize (need >= 2).");
            return;
        }

        List<Double> apogeeFeet = results.stream()
                .filter(d -> d.hasApogee)
                .map(d -> d.apogee_m * FEET_METRES)
                .collect(Collectors.toList());

        if (apogeeFeet.size() >= 2) {
            Statistics.Sample apogee = Statistics.calculateSample(apogeeFeet);
            log.info("Apogee (ft): {}", apogee);
        } else {
            log.warn("Not enough apogee samples for statistics.");
        }

        List<Double> landingEast = results.stream()
                .filter(d -> d.hasLanding)
                .map(d -> d.landingEast_m)
                .collect(Collectors.toList());

        List<Double> landingNorth = results.stream()
                .filter(d -> d.hasLanding)
                .map(d -> d.landingNorth_m)
                .collect(Collectors.toList());

        if (landingEast.size() >= 2 && landingNorth.size() >= 2) {
            Statistics.Sample east = Statistics.calculateSample(landingEast);
            Statistics.Sample north = Statistics.calculateSample(landingNorth);
            log.info("Landing East (m): {}", east);
            log.info("Landing North (m): {}", north);
        } else {
            log.warn("Not enough landing samples for statistics.");
        }
    }

    public void exportToCSV(File csvFile) {
        List<SimulationData> results = getData();
        if (results.isEmpty()) {
            log.warn("No data has been generated, ignoring CSV export");
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            StringBuilder header = new StringBuilder(
                    "Simulation,Apogee (m),Apogee Time (s),Landing Time (s)," +
                    "Landing East (m),Landing North (m),Landing Lat (deg),Landing Lon (deg)," +
                    "Landing Downrange (m),Landing Crossrange (m),Has Apogee,Has Landing\n"
            );

            writer.write(header.toString());

            for (SimulationData simData : results) {
                StringBuilder row = new StringBuilder();
                row.append(simData.simulationName).append(",");
                row.append(simData.apogee_m).append(",");
                row.append(simData.apogeeTime_s).append(",");
                row.append(simData.landingTime_s).append(",");
                row.append(simData.landingEast_m).append(",");
                row.append(simData.landingNorth_m).append(",");
                row.append(simData.landingLat_deg).append(",");
                row.append(simData.landingLon_deg).append(",");
                row.append(simData.landingDownrange_m).append(",");
                row.append(simData.landingCrossrange_m).append(",");
                row.append(simData.hasApogee).append(",");
                row.append(simData.hasLanding).append("\n");
                writer.write(row.toString());
            }
        } catch (IOException e) {
            System.err.println("Error writing to CSV file: " + e.getMessage());
        }
    }

    public interface SimulationProgressListener {
        void onProgress(int completed, int total);
    }

    /**
     * Runs the provided simulations in parallel using a fixed thread pool.
     */
    public void runSimulationsInParallel(List<Simulation> sims, int threads, SimulationProgressListener listener) throws Exception {
        if (sims == null || sims.isEmpty()) return;

        int threadCount = Math.max(1, threads);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            final int total = sims.size();
            final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>(sims.size());
            for (Simulation s : sims) {
                futures.add(pool.submit(() -> {
                    try {
                        SimulationRunner.runSimulationInProcess(s);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        int done = completed.incrementAndGet();
                        if (listener != null) listener.onProgress(done, total);
                    }
                }));
            }

            // Propagate first failure (if any)
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof RuntimeException re && re.getCause() != null) {
                        throw (re.getCause() instanceof Exception e) ? e : re;
                    }
                    throw ex;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

}