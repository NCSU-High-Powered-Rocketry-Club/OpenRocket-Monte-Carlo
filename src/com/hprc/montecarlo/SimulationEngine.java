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
import info.openrocket.core.rocketcomponent.Rocket;

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
    private final List<SimulationData> data = new ArrayList<>();

    private double windDirStdDev, tempStdDev, pressureStdDev;

    private static final double DEFAULT_T0_K = 288.15;      // 15 C
    private static final double DEFAULT_P0_PA = 101325.0;   // sea-level standard

    /**
     * Creates a SimulationEngine with simulations specified by the given csvFile
     *
     * @param document OpenRocket document to be used with the simulation
     * @param csvFile  CSV file that specifies simulation conditions
     * @throws Exception On CSV parse fail
     * @see SimulationEngine#CSV_SIMULATION_UNITS
     * @see SimulationEngine#CSV_WIND_LEVEL_UNITS
     * @see SimulationEngine#CSV_ALTITUDE_UNIT
     * @see SimulationEngine#CSV_SIMULATION_COLUMN_COUNT
     * @see SimulationEngine#CSV_WIND_LEVEL_COLUMN_COUNT
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
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT],
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT + 2],
                            simData[2 + i * CSV_WIND_LEVEL_COLUMN_COUNT + 1]);
                }

                SimulationData simulationData = new SimulationData(simulation);
                log.debug(simulationData.toString());

                data.add(simulationData);
            }
        }
        this.simulationCount = data.size();
    }

    /**
     * Creates a SimulationEngine with the passed values. Does not create the simulation objects.
     * Must call createMonteCarloSimulations to finish initialization.
     *
     * @param document        OpenRocket document to be used with the simulation
     * @param simulationCount Number of simulations
     * @param windDirStdDev   Wind direction standard deviation
     * @param tempStdDev      Temperature standard deviation
     * @param pressureStdDev  Pressure standard deviation
     * @see SimulationEngine#createMonteCarloSimulations(Simulation)
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
            data.add(new SimulationData(sim));
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

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    /**
     * Creates simulations with randomized conditions based on referenceSim and provided values at construct time
     *
     * @param referenceSim Reference simulation to copy base conditions, extensions from
     * @implNote Clears existing simulations
     * @see SimulationEngine#configureMonteCarloSimulationOptions(SimulationOptions)
     */
    public void createMonteCarloSimulations(Simulation referenceSim) {
        data.clear();
        for (int i = 0; i < simulationCount; i++) {
            // CLONE the rocket so MonteCarloExtension can safely modify mass
            Rocket rocketCopy = (Rocket) document.getRocket().copy();
            Simulation sim = new Simulation(document, rocketCopy);
            sim.setName("Simulation " + i);
            log.info("Generating conditions for {}", sim.getName());

            sim.copySimulationOptionsFrom(referenceSim.getOptions());

            sim.getSimulationExtensions().clear();
            for (SimulationExtension c : referenceSim.getSimulationExtensions()) {
                sim.getSimulationExtensions().add(c.clone());
            }

            // FIX: if ISA is off, make sure pressure/temp are valid before perturbing.
            ensureManualAtmosphereHasValues(sim.getOptions());

            configureMonteCarloSimulationOptions(sim.getOptions());
            data.add(new SimulationData(sim));
        }
    }

    /**
     * Set the Monte-Carlo conditions for the flight simulation
     *
     * @param opts The SimulationOptions object of the simulation
     */
    private void configureMonteCarloSimulationOptions(SimulationOptions opts) {

        for (MultiLevelPinkNoiseWindModel.LevelWindModel windLevel : opts.getMultiLevelWindModel().getLevels()) {
            double windSpeed = randomGauss(windLevel.getSpeed(), windLevel.getStandardDeviation());
            windLevel.setSpeed(windSpeed);
            log.debug("Cond @ {}: Avg WindSpeed: {}m/s", windLevel.getAltitude(), windSpeed);

            // FIX: direction is already radians; windDirStdDev is radians.
            double windDirectionRad = randomGauss(windLevel.getDirection(), windDirStdDev);
            windLevel.setDirection(wrapRadians(windDirectionRad));
            log.debug("Cond @ {}: windDirection: {} rad", windLevel.getAltitude(), windDirectionRad);
        }

        // FIX: keep manual atmosphere values physical when ISA is off.
        // (delta C == delta K, so σ in C is fine as σ in K)
        double temperature = randomGauss(opts.getLaunchTemperature(), tempStdDev);
        if (!Double.isFinite(temperature) || temperature <= 0.0) temperature = DEFAULT_T0_K;
        opts.setLaunchTemperature(temperature);

        double pressure = randomGauss(opts.getLaunchPressure(), pressureStdDev);
        if (!Double.isFinite(pressure) || pressure <= 0.0) pressure = DEFAULT_P0_PA;
        opts.setLaunchPressure(pressure);
    }

    private static double wrapRadians(double a) {
        // wrap to [-pi, pi)
        double x = a;
        while (x >= Math.PI) x -= 2.0 * Math.PI;
        while (x < -Math.PI) x += 2.0 * Math.PI;
        return x;
    }

    /**
     * Generates a reference simulation with default values. Use createMonteCarloSimulations to create
     * Monte-Carlo simulations based on this reference simulation.
     *
     * @return Reference simulation with default values
     * @see SimulationEngine#createMonteCarloSimulations(Simulation)
     */
    public Simulation generateDefaultSimulation() {
        Simulation defaultSimulation = new Simulation(document, document.getRocket());
        defaultSimulation.setName("Monte-Carlo Simulation");
        SimulationOptions opts = defaultSimulation.getOptions();

        opts.setLaunchLatitude(config.getLaunchLatitude());
        opts.setLaunchLongitude(config.getLaunchLongitude());
        opts.setLaunchAltitude(config.getLaunchAltitude());

        // Keep your intended behavior (ISA off), but ensure manual values are not left at 0.
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
        return data.stream().skip(start).limit(size).map(SimulationData::getSimulation).toList();
    }

    public List<SimulationData> getData() {
        return data;
    }

    public void processSimulationData() {
        for (SimulationData d : data) {
            try {
                if (!d.hasData() && d.getSimulation().hasSimulationData()) // only process unprocessed simulations
                    d.processData(keepSimulationObject);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

    public void summarizeSimulations() {
        Statistics.Sample apogee = Statistics.calculateSample(
                data.stream().map(SimulationData::getApogee).map((v) -> v * FEET_METRES).collect(Collectors.toList()));
        double minStability = data.stream().mapToDouble(x -> x.getMinStability().get(0)).min().orElseThrow();
        double maxStability = data.stream().mapToDouble(x -> x.getMaxStability().get(0)).max().orElseThrow();
        Statistics.Sample initStability = Statistics.calculateSample(
                data.stream().map(x -> x.getInitStability().get(0)).collect(Collectors.toList()));
        double lowInitStabilityPercentage = (double) data.stream().mapToDouble(x -> x.getInitStability().get(0))
                .filter((stability) -> stability < 1.5).count() / data.size();
        Statistics.Sample apogeeStability = Statistics.calculateSample(
                data.stream().map(x -> x.getApogeeStability().get(0)).collect(Collectors.toList()));
        Statistics.Sample maxMach = Statistics.calculateSample(
                data.stream().map(SimulationData::getMaxMachNumber).collect(Collectors.toList()));

        log.info("Data over {} runs:", simulationCount);
        log.info("Apogee (ft): {}", apogee);
        log.info("Max mach number: {}", maxMach);
        log.info("Min stability: {}", minStability);
        log.info("Max stability: {}", maxStability);
        log.info("Apogee stability: {}", apogeeStability);
        log.info("Initial stability: {}", initStability);
        log.info("Percentage of initial stability less than 1.5: {}", lowInitStabilityPercentage);

//        return data.stream().sorted(Comparator.comparing(x -> x.getMinStability().get(0)))
//                .limit(5).toList();
    }

    public void exportToCSV(File csvFile) {
        if (data.isEmpty()) {
            log.warn("No data has been generated, ignoring CSV export");
            return;
        }
        // Write all simulation data to CSV
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            // Write comprehensive header
            StringBuilder header = new StringBuilder("Simulation,Max Windspeed (mph),Wind Direction (deg),Temperature (°C),Pressure (mbar),Apogee (ft),Max Mach");

            // add branch-specific headers
            String[] branchHeaders =
                    {"Initial Stability", "Min Stability", "Max Stability", "Apogee Stability", "Landing Latitude (deg N)",
                            "Landing Longitude (deg E)", "Position East of Launch (ft)", "Position North of Launch (ft)",
                            "Lateral Velocity at Apogee (m/s)"};
            int branches = data.get(0).getBranchName().size();
            for (int i = 0; i < branches; i++) {
                String branchName = data.get(0).getBranchName().get(i);
                StringBuilder branchHeader = new StringBuilder();
                for (String branchHeaderLabel : branchHeaders) {
                    branchHeader.append(",").append(branchName).append(" ").append(branchHeaderLabel);
                }
                header.append(branchHeader);
            }
            header.append("\n");

            writer.write(header.toString());

            // Write data for each simulation
            for (SimulationData simData : data) {
                StringBuilder row = new StringBuilder();
                row.append(simData.getName()).append(",");
                row.append(simData.getMaxWindSpeedInMPH()).append(",");
                row.append(simData.getMaxWindDirectionInDegrees()).append(",");
                row.append(simData.getTemperatureInCelsius()).append(",");
                row.append(simData.getPressureInMBar()).append(",");
                row.append(simData.getApogeeInFeet()).append(",");
                row.append(simData.getMaxMachNumber()).append(",");

                for (int i = 0; i < branches; i++) { // branch-specific data
                    row.append(simData.getInitStability().get(i)).append(",");
                    row.append(simData.getMinStability().get(i)).append(",");
                    row.append(simData.getMaxStability().get(i)).append(",");
                    row.append(simData.getApogeeStability().get(i)).append(",");
                    row.append(simData.getLandingLatitude().get(i)).append(",");
                    row.append(simData.getLandingLongitude().get(i)).append(",");
                    row.append(simData.getEastPostLandingInFeet().get(i)).append(",");
                    row.append(simData.getNorthPostLandingInFeet().get(i)).append(",");
                    row.append(simData.getApogeeLateralVelocity().get(i)).append(",");
                }
                row.append("\n");
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
     * This executes the OpenRocket simulation in-process (no GUI dialog).
     *
     * @param sims     simulations to run
     * @param threads  number of worker threads (>= 1)
     * @param listener optional progress callback (may be called from worker threads)
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
