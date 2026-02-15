package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.swing.gui.SpinnerEditor;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.components.UnitSelector;
import info.openrocket.swing.simulation.extension.AbstractSwingSimulationExtensionConfigurator;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/**
 * GUI shown in OpenRocket's Simulation Extensions tab when "HPRC Monte Carlo" is selected.
 *
 * Layout:
 *   1) General settings strip (always visible at top)
 *   2) Tabbed pane: Launch | Atmosphere | Disturbances | Vehicle
 *   3) Batch run / export strip (always visible at bottom)
 *
 * Must be discoverable by OpenRocket's plugin scanner ({@code @Plugin}) and must extend
 * {@link AbstractSwingSimulationExtensionConfigurator}.
 */
@Plugin
public class MonteCarloConfigurator
        extends AbstractSwingSimulationExtensionConfigurator<MonteCarloExtension> {

    // Unicode constants for clean display
    private static final String SIGMA      = "\u03c3";    // \u03c3
    private static final String DELTA      = "\u0394";    // \u0394
    private static final String DEG_C      = "\u00b0C";   // \u00b0C
    private static final String PLUS_MINUS = "\u00b1";    // \u00b1

    // Keep last results in-memory so user can export after completion
    private final AtomicReference<List<MonteCarloRunRecord>> lastBatchResults = new AtomicReference<>();

    public MonteCarloConfigurator() {
        super(MonteCarloExtension.class);
    }

    @Override
    protected JComponent getConfigurationComponent(MonteCarloExtension ext, Simulation sim, JPanel panel) {
        // =====================================================================
        // Root container: vertical layout with three zones
        //   1) General settings strip (always visible)
        //   2) Tabbed pane (Launch | Atmosphere | Disturbances | Vehicle)
        //   3) Batch run / export strip (always visible)
        // =====================================================================
        panel.setLayout(new MigLayout("fill, ins 6, wrap 1, hidemode 3",
                "[grow, fill]", "[]6[]6[]"));

        // ---- 1. General settings ----
        panel.add(buildGeneralPanel(ext), "growx");

        // ---- 2. Tabbed pane ----
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 12f));
        tabs.addTab("Launch", buildLaunchTab(ext));
        tabs.addTab("Atmosphere", buildAtmosphereTab(ext, sim));
        tabs.addTab("Disturbances", buildDisturbancesTab(ext));
        tabs.addTab("Vehicle", buildVehicleTab(ext));
        panel.add(tabs, "growx, growy, push");

        // ---- 3. Batch run / export ----
        panel.add(buildBatchPanel(ext, sim, panel), "growx");

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        return scroll;
    }

    // =====================================================================
    // Section builders
    // =====================================================================

    /**
     * General settings: enabled, debug, sim count, deterministic seed.
     */
    private JPanel buildGeneralPanel(MonteCarloExtension ext) {
        JPanel p = titledPanel("General");
        p.setLayout(new MigLayout("ins 8, wrap 4, gap 8 4",
                "[grow 0][grow 0][grow][grow 0]"));

        // Row 1: Enabled + Debug
        JCheckBox enabled = new JCheckBox("Enabled", ext.isEnabled());
        enabled.setToolTipText("Master switch \u2014 uncheck to disable all Monte Carlo perturbations.");
        enabled.addActionListener(e -> ext.setEnabled(enabled.isSelected()));
        p.add(enabled, "span 2");

        JCheckBox debug = new JCheckBox("Debug logging", ext.isDebugEnabled());
        debug.setToolTipText("Log per-run perturbation values to the OpenRocket console.");
        debug.addActionListener(e -> ext.setDebugEnabled(debug.isSelected()));
        p.add(debug, "span 2, wrap");

        // Row 2: Number of simulations
        p.add(new JLabel("Simulations"), "align label");
        JSpinner nSpinner = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(ext.getNumberOfSimulations()),
                (Comparable<Integer>) Integer.valueOf(1),
                (Comparable<Integer>) Integer.valueOf(1_000_000),
                (Number) Integer.valueOf(1)));
        nSpinner.setEditor(new SpinnerEditor(nSpinner));
        nSpinner.setToolTipText("Total number of Monte Carlo simulation runs.");
        nSpinner.addChangeListener(e -> ext.setNumberOfSimulations(((Number) nSpinner.getValue()).intValue()));
        p.add(nSpinner, "span 3, growx, wrap");

        // Row 3: Deterministic seed
        p.add(new JLabel("Seed"), "align label");
        JCheckBox deterministicSeed = new JCheckBox("Deterministic", ext.isUseDeterministicSeed());
        deterministicSeed.setToolTipText("Use a fixed seed for reproducible results.");
        deterministicSeed.addActionListener(e -> ext.setUseDeterministicSeed(deterministicSeed.isSelected()));
        p.add(deterministicSeed);

        JSpinner seedSpinner = new JSpinner(new SpinnerNumberModel(
                ext.getRandomSeed(), Long.MIN_VALUE, Long.MAX_VALUE, 1L));
        seedSpinner.setEditor(new SpinnerEditor(seedSpinner));
        seedSpinner.setToolTipText("Base seed value (only used when Deterministic is checked).");
        seedSpinner.addChangeListener(e -> ext.setRandomSeed(((Number) seedSpinner.getValue()).longValue()));
        p.add(seedSpinner, "span 2, growx");

        return p;
    }

    /**
     * Launch / Orientation variation tab.
     */
    private JPanel buildLaunchTab(MonteCarloExtension ext) {
        JPanel p = new JPanel(new MigLayout("ins 12, wrap 3, gap 8 6",
                "[grow 0, 180::][grow][grow 0]"));

        addAngleRow(p, "Launch rail angle " + SIGMA, ext, "LaunchRodAngleStdDevDeg",
                "Standard deviation of the launch rail elevation angle.");
        addAngleRow(p, "Launch rail direction " + SIGMA, ext, "LaunchRodDirectionStdDevDeg",
                "Standard deviation of the launch rail azimuth direction.");

        p.add(new JSeparator(), "span 3, growx, gaptop 6, gapbottom 6");

        addLengthRow(p, "Launch altitude " + SIGMA, ext, "LaunchAltitudeStdDevM",
                "Standard deviation of launch site altitude (AGL).");
        addAngleRow(p, "Launch latitude " + SIGMA, ext, "LaunchLatitudeStdDevDeg",
                "Standard deviation of launch site latitude.");
        addAngleRow(p, "Launch longitude " + SIGMA, ext, "LaunchLongitudeStdDevDeg",
                "Standard deviation of launch site longitude.");

        // Push remaining space to bottom so fields stay at top
        p.add(new JLabel(), "span 3, growy, pushy");
        return p;
    }

    /**
     * Atmosphere / Wind variation tab.
     */
    private JPanel buildAtmosphereTab(MonteCarloExtension ext, Simulation sim) {
        JPanel p = new JPanel(new MigLayout("ins 12, wrap 3, gap 8 6",
                "[grow 0, 180::][grow][grow 0]"));

        final boolean multiLevel = isMultiLevelWindSelected(sim.getOptions());

        // Wind speed average sigma
        JLabel windAvgLabel = new JLabel("Wind speed average " + SIGMA);
        windAvgLabel.setEnabled(!multiLevel);
        p.add(windAvgLabel, "align label");

        DoubleModel windAvgModel = new DoubleModel(ext, "WindSpeedAverageSigmaMps",
                UnitGroup.UNITS_VELOCITY, 0);
        JSpinner windAvgSpinner = new JSpinner(windAvgModel.getSpinnerModel());
        windAvgSpinner.setEditor(new SpinnerEditor(windAvgSpinner));
        windAvgSpinner.setEnabled(!multiLevel);
        UnitSelector windAvgUnits = new UnitSelector(windAvgModel);
        windAvgUnits.setEnabled(!multiLevel);

        if (multiLevel) {
            String tip = "Disabled \u2014 base simulation uses Multi-level wind.";
            windAvgLabel.setToolTipText(tip);
            windAvgSpinner.setToolTipText(tip);
            windAvgUnits.setToolTipText(tip);
            ext.setWindSpeedAverageSigmaMps(0.0);
        } else {
            String tip = "Standard deviation of mean wind speed perturbation.";
            windAvgLabel.setToolTipText(tip);
            windAvgSpinner.setToolTipText(tip);
        }
        p.add(windAvgSpinner, "growx");
        p.add(windAvgUnits);

        // Wind speed turbulence sigma
        addVelocityRow(p, "Wind speed turbulence " + SIGMA, ext,
                "WindSpeedTurbulenceSigmaMps",
                "Standard deviation of wind gust / turbulence intensity perturbation.");

        // Wind direction sigma
        addAngleRow(p, "Wind direction " + SIGMA, ext, "WindDirectionStdDevDeg",
                "Standard deviation of wind direction perturbation.");

        p.add(new JSeparator(), "span 3, growx, gaptop 6, gapbottom 6");

        // Temperature sigma
        addTemperatureRow(p, "Temperature " + SIGMA, ext.getTemperatureStdDevC(),
                ext::setTemperatureStdDevC,
                "Standard deviation of launch temperature perturbation (" + DEG_C + ").");

        // Pressure sigma
        addPressureRow(p, "Pressure " + SIGMA, ext.getPressureStdDevMbar(),
                ext::setPressureStdDevMbar,
                "Standard deviation of launch pressure perturbation (mbar).");

        p.add(new JLabel(), "span 3, growy, pushy");
        return p;
    }

    /**
     * Wind disturbances tab: Gust events + Shear layer, each in a titled sub-panel
     * with an enable checkbox that grays out all child controls.
     */
    private JPanel buildDisturbancesTab(MonteCarloExtension ext) {
        JPanel p = new JPanel(new MigLayout("ins 8, wrap 1, gap 0 8", "[grow, fill]"));

        // ---- Gust events sub-panel ----
        p.add(buildGustPanel(ext), "growx");

        // ---- Shear layer sub-panel ----
        p.add(buildShearPanel(ext), "growx");

        p.add(new JLabel(), "growy, pushy");
        return p;
    }

    /**
     * Gust events sub-panel with enable checkbox controlling child enabled state.
     */
    private JPanel buildGustPanel(MonteCarloExtension ext) {
        JPanel gust = titledPanel("Gust Events");
        gust.setLayout(new MigLayout("ins 8, wrap 3, gap 8 4",
                "[grow 0, 160::][grow][grow 0]"));

        // Collect child components for enable/disable toggling
        final List<JComponent> gustChildren = new ArrayList<>();

        // Enable checkbox
        JCheckBox gustEnable = new JCheckBox("Enable gust events", ext.isGustEventsEnabled());
        gustEnable.setToolTipText("Add random discrete gust events during the simulation.");
        gustEnable.addActionListener(e -> {
            ext.setGustEventsEnabled(gustEnable.isSelected());
            setChildrenEnabled(gustChildren, gustEnable.isSelected());
        });
        gust.add(gustEnable, "span 3, wrap");

        // Gust parameters
        gustChildren.addAll(addIntRow(gust, "Event count", ext.getGustEventCount(), 0, 50,
                v -> ext.setGustEventCount(v), "events",
                "Number of discrete gust events per simulation run."));

        gustChildren.addAll(addTimeRow(gust, "Window start", ext.getGustWindowStartS(),
                ext::setGustWindowStartS,
                "Earliest time (s) a gust can begin."));
        gustChildren.addAll(addTimeRow(gust, "Window end", ext.getGustWindowEndS(),
                ext::setGustWindowEndS,
                "Latest time (s) a gust can begin."));

        gust.add(new JSeparator(), "span 3, growx, gaptop 4, gapbottom 4");

        gustChildren.addAll(addTimeRow(gust, "Duration mean", ext.getGustDurationMeanS(),
                ext::setGustDurationMeanS,
                "Mean duration of each gust event (s)."));
        gustChildren.addAll(addTimeRow(gust, "Duration " + SIGMA, ext.getGustDurationSigmaS(),
                ext::setGustDurationSigmaS,
                "Standard deviation of gust duration (s)."));

        gustChildren.addAll(addVelocityRowCollect(gust, "Peak " + DELTA + "V mean", ext,
                "GustPeakDeltaMeanMps",
                "Mean peak wind speed change during a gust event."));
        gustChildren.addAll(addVelocityRowCollect(gust, "Peak " + DELTA + "V " + SIGMA, ext,
                "GustPeakDeltaSigmaMps",
                "Standard deviation of peak wind speed change."));

        // Set initial enabled state
        setChildrenEnabled(gustChildren, ext.isGustEventsEnabled());

        return gust;
    }

    /**
     * Shear layer sub-panel with enable checkbox controlling child enabled state.
     */
    private JPanel buildShearPanel(MonteCarloExtension ext) {
        JPanel shear = titledPanel("Wind Shear Layer");
        shear.setLayout(new MigLayout("ins 8, wrap 3, gap 8 4",
                "[grow 0, 160::][grow][grow 0]"));

        final List<JComponent> shearChildren = new ArrayList<>();

        JCheckBox shearEnable = new JCheckBox("Enable shear layer", ext.isShearLayerEnabled());
        shearEnable.setToolTipText("Apply a wind shear layer at a specified altitude band.");
        shearEnable.addActionListener(e -> {
            ext.setShearLayerEnabled(shearEnable.isSelected());
            setChildrenEnabled(shearChildren, shearEnable.isSelected());
        });
        shear.add(shearEnable, "span 3, wrap");

        shearChildren.addAll(addLengthRowCollect(shear, "Center altitude", ext,
                "ShearCenterAltM",
                "Altitude at the center of the shear layer."));
        shearChildren.addAll(addLengthRowCollect(shear, "Thickness", ext,
                "ShearThicknessM",
                "Vertical thickness of the shear transition zone."));

        shear.add(new JSeparator(), "span 3, growx, gaptop 4, gapbottom 4");

        shearChildren.addAll(addVelocityRowCollect(shear, DELTA + "V mean", ext,
                "ShearDeltaMeanMps",
                "Mean wind speed change across the shear layer."));
        shearChildren.addAll(addVelocityRowCollect(shear, DELTA + "V " + SIGMA, ext,
                "ShearDeltaSigmaMps",
                "Standard deviation of wind speed change across the shear layer."));

        setChildrenEnabled(shearChildren, ext.isShearLayerEnabled());

        return shear;
    }

    /**
     * Vehicle / Motor variation tab.
     */
    private JPanel buildVehicleTab(MonteCarloExtension ext) {
        JPanel p = new JPanel(new MigLayout("ins 12, wrap 3, gap 8 6",
                "[grow 0, 180::][grow][grow 0]"));

        addPercentSigma(p, "CD multiplier " + SIGMA,
                ext.getCdMultiplierSigma() * 100.0,
                v -> ext.setCdMultiplierSigma(v / 100.0),
                "Drag coefficient variation (e.g. 5 = " + PLUS_MINUS + "5% at 1" + SIGMA + "). " +
                "Accounts for surface roughness, paint, fin alignment, launch lugs.");

        addPercentSigma(p, "Thrust multiplier " + SIGMA,
                ext.getThrustMultiplierSigma() * 100.0,
                v -> ext.setThrustMultiplierSigma(v / 100.0),
                "Motor total impulse variation (e.g. 3 = " + PLUS_MINUS + "3% at 1" + SIGMA + "). " +
                "Typical motor-to-motor variation is 2\u20135%.");

        addPercentSigma(p, "Mass multiplier " + SIGMA,
                ext.getMassMultiplierSigma() * 100.0,
                v -> ext.setMassMultiplierSigma(v / 100.0),
                "Rocket mass variation (e.g. 2 = " + PLUS_MINUS + "2% at 1" + SIGMA + "). " +
                "Accounts for epoxy, paint, and hardware tolerances.");

        p.add(new JLabel(), "span 3, growy, pushy");
        return p;
    }

    /**
     * Batch run / export panel (always visible at bottom).
     */
    private JPanel buildBatchPanel(MonteCarloExtension ext, Simulation sim, JPanel rootPanel) {
        JPanel p = titledPanel("Batch Run / Export");
        p.setLayout(new MigLayout("ins 8, wrap 3, gap 6 4",
                "[grow 0][grow][grow 0]"));

        // Worker threads
        p.add(new JLabel("Worker threads"), "align label");
        int maxThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        int initialThreads = Math.min(Math.max(1, ext.getWorkerThreads()), maxThreads);
        JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(initialThreads),
                (Comparable<Integer>) Integer.valueOf(1),
                (Comparable<Integer>) Integer.valueOf(maxThreads),
                (Number) Integer.valueOf(1)));
        threadsSpinner.setEditor(new SpinnerEditor(threadsSpinner));
        threadsSpinner.setToolTipText("Number of parallel JVM threads for the batch run (" +
                Runtime.getRuntime().availableProcessors() + " cores detected).");
        threadsSpinner.addChangeListener(e -> ext.setWorkerThreads(
                ((Number) threadsSpinner.getValue()).intValue()));
        p.add(threadsSpinner, "span 2, growx, wrap");

        // Progress bar
        final JProgressBar progress = new JProgressBar(0, Math.max(1, ext.getNumberOfSimulations()));
        progress.setStringPainted(true);
        progress.setValue(0);
        progress.setString("Idle");
        progress.setPreferredSize(new Dimension(progress.getPreferredSize().width, 26));
        p.add(progress, "span 3, growx, gaptop 4");

        // Buttons
        final JButton runBatch = new JButton("Run Monte Carlo Batch");
        final JButton exportAll = new JButton("Export All Results (CSV + KML + PNG)");

        // Style the run button to stand out
        runBatch.setFont(runBatch.getFont().deriveFont(Font.BOLD));
        runBatch.setToolTipText("Start the Monte Carlo batch simulation.");

        exportAll.setEnabled(false);
        exportAll.setToolTipText("Export detailed Monte Carlo CSV plus landing dispersion KML/PNG/CSV files.");

        // ---- Run batch action ----
        runBatch.addActionListener(e -> {
            runBatch.setEnabled(false);
            exportAll.setEnabled(false);
            lastBatchResults.set(null);

            progress.setMaximum(Math.max(1, ext.getNumberOfSimulations()));
            progress.setValue(0);
            progress.setString("Starting...");

            final int runs = ext.getNumberOfSimulations();
            final int threads = Math.max(1, ext.getWorkerThreads());

            SwingWorker<List<MonteCarloRunRecord>, String> worker = new SwingWorker<>() {
                @Override
                protected List<MonteCarloRunRecord> doInBackground() throws Exception {
                    if (threads <= 1) {
                        return MonteCarloBatchRunner.runBatch(sim, runs,
                                (completed, total) -> SwingUtilities.invokeLater(() -> {
                                    progress.setMaximum(total);
                                    progress.setValue(completed);
                                    progress.setString(completed + " / " + total);
                                }));
                    }
                    return MonteCarloBatchRunner.runBatchParallel(sim, runs, threads,
                            (completed, total) -> SwingUtilities.invokeLater(() -> {
                                progress.setMaximum(total);
                                progress.setValue(completed);
                                progress.setString(completed + " / " + total);
                            }));
                }

                @Override
                protected void done() {
                    try {
                        List<MonteCarloRunRecord> results = get();
                        lastBatchResults.set(results);
                        progress.setValue(progress.getMaximum());
                        progress.setString("Done (" + results.size() + " runs)");
                        exportAll.setEnabled(true);
                    } catch (Exception ex) {
                        progress.setString("Failed");
                        JOptionPane.showMessageDialog(rootPanel,
                                "Batch run failed:\n" + ex.getMessage(),
                                "Monte Carlo Batch Error",
                                JOptionPane.ERROR_MESSAGE);
                    } finally {
                        runBatch.setEnabled(true);
                    }
                }
            };
            worker.execute();
        });

        // ---- Unified export action (detailed CSV + landing dispersion bundle) ----
        exportAll.addActionListener(e -> {
            List<MonteCarloRunRecord> results = lastBatchResults.get();
            if (results == null || results.isEmpty()) {
                JOptionPane.showMessageDialog(rootPanel,
                        "No batch results available. Run a batch first.",
                        "Nothing to Export", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String orkStem = resolveCurrentOrkStem(sim);
            String detailedCsvName = orkStem + "_montecarlo_detailed.csv";
            String dispersionStem = orkStem + "_landing_dispersion";

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select output folder for Monte Carlo exports");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showSaveDialog(rootPanel) != JFileChooser.APPROVE_OPTION) return;

            File dir = chooser.getSelectedFile();
            if (dir == null) return;

            File detailedCsv = new File(dir, detailedCsvName);
            Path outDir = Paths.get(dir.getAbsolutePath());

            SimulationOptions opts = sim.getOptions();
            double launchLatDeg = opts.getLaunchLatitude();
            double launchLonDeg = opts.getLaunchLongitude();

            try {
                MonteCarloCsvExporter.exportDetailedCsv(detailedCsv, results);
                LandingDispersion6DOF.exportAll(outDir, dispersionStem,
                        results, launchLatDeg, launchLonDeg);

                JOptionPane.showMessageDialog(rootPanel,
                        "Exported to:\n" + outDir.toAbsolutePath() + "\n\n" +
                        "Files:\n" +
                        " • " + detailedCsvName + "\n" +
                        " • " + dispersionStem + ".kml\n" +
                        " • " + dispersionStem + ".png\n" +
                        " • " + dispersionStem + "_points.csv\n" +
                        " • " + dispersionStem + "_summary.csv",
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(rootPanel,
                        "Export failed:\n" + ex.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        p.add(runBatch, "span 2, growx, gaptop 4");
        p.add(exportAll, "growx, gaptop 4");

        return p;
    }

    // =====================================================================
    // Row-builder helpers (with tooltip support)
    // =====================================================================

    /** Angle row using OpenRocket's DoubleModel + UnitSelector. */
    private static void addAngleRow(JPanel panel, String label, MonteCarloExtension ext,
                                    String property, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_ANGLE, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    /** Velocity row using OpenRocket's DoubleModel + UnitSelector. */
    private static void addVelocityRow(JPanel panel, String label, MonteCarloExtension ext,
                                       String property, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_VELOCITY, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    /** Velocity row that returns all created components for enable/disable toggling. */
    private static List<JComponent> addVelocityRowCollect(JPanel panel, String label,
                                                          MonteCarloExtension ext,
                                                          String property, String tooltip) {
        List<JComponent> comps = new ArrayList<>();
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        comps.add(lbl);

        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_VELOCITY, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        comps.add(spinner);

        UnitSelector us = new UnitSelector(model);
        panel.add(us);
        comps.add(us);
        return comps;
    }

    /** Length row using OpenRocket's DoubleModel + UnitSelector. */
    private static void addLengthRow(JPanel panel, String label, MonteCarloExtension ext,
                                     String property, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_LENGTH, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    /** Length row that returns components for enable/disable toggling. */
    private static List<JComponent> addLengthRowCollect(JPanel panel, String label,
                                                        MonteCarloExtension ext,
                                                        String property, String tooltip) {
        List<JComponent> comps = new ArrayList<>();
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        comps.add(lbl);

        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_LENGTH, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        comps.add(spinner);

        UnitSelector us = new UnitSelector(model);
        panel.add(us);
        comps.add(us);
        return comps;
    }

    /** Temperature row with fixed degree-C unit. */
    private static void addTemperatureRow(JPanel panel, String label, double initialC,
                                          DoubleConsumer setter, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialC, 0.0, 500.0, 0.1));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new JLabel(DEG_C));
    }

    /** Pressure row with fixed mbar unit. */
    private static void addPressureRow(JPanel panel, String label, double initialMbar,
                                       DoubleConsumer setter, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialMbar, 0.0, 20000.0, 1.0));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new JLabel("mbar"));
    }

    /** Time-in-seconds row that returns components for enable/disable toggling. */
    private static List<JComponent> addTimeRow(JPanel panel, String label,
                                               double initialSeconds, DoubleConsumer setter,
                                               String tooltip) {
        List<JComponent> comps = new ArrayList<>();
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        comps.add(lbl);

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialSeconds, 0.0, 10_000.0, 0.05));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        comps.add(spinner);

        JLabel unit = new JLabel("s");
        panel.add(unit);
        comps.add(unit);
        return comps;
    }

    /** Integer row that returns components for enable/disable toggling. */
    private static List<JComponent> addIntRow(JPanel panel, String label,
                                              int initial, int min, int max,
                                              java.util.function.IntConsumer setter,
                                              String unitText, String tooltip) {
        List<JComponent> comps = new ArrayList<>();
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");
        comps.add(lbl);

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(initial),
                (Comparable<Integer>) Integer.valueOf(min),
                (Comparable<Integer>) Integer.valueOf(max),
                (Number) Integer.valueOf(1)));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).intValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        comps.add(spinner);

        JLabel unit = new JLabel(unitText);
        panel.add(unit);
        comps.add(unit);
        return comps;
    }

    /**
     * Percentage sigma spinner (e.g. "5" meaning 5%).
     * Displayed/edited in percent; setter receives percent.
     */
    private static void addPercentSigma(JPanel panel, String label, double initialPercent,
                                        DoubleConsumer setter, String tooltip) {
        JLabel lbl = tooltipLabel(label, tooltip);
        panel.add(lbl, "align label");

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialPercent, 0.0, 100.0, 0.5));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);
        panel.add(spinner, "growx");
        panel.add(new JLabel("%"));
    }

    // =====================================================================
    // UI utility helpers
    // =====================================================================

    /** Creates a JPanel with a titled etched border. */
    private static JPanel titledPanel(String title) {
        JPanel p = new JPanel();
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD, 12f));
        p.setBorder(border);
        return p;
    }

    /** Creates a JLabel with a tooltip. */
    private static JLabel tooltipLabel(String text, String tooltip) {
        JLabel l = new JLabel(text);
        if (tooltip != null && !tooltip.isEmpty()) {
            l.setToolTipText(tooltip);
        }
        return l;
    }

    /** Recursively enables or disables a list of components. */
    private static void setChildrenEnabled(List<JComponent> components, boolean enabled) {
        for (JComponent c : components) {
            c.setEnabled(enabled);
            // Also disable sub-components (e.g. spinner buttons)
            for (Component child : c.getComponents()) {
                child.setEnabled(enabled);
            }
        }
    }

    /**
     * Best-effort check of the simulation's wind model selection.
     * Uses reflection to remain compatible across OpenRocket versions.
     */
    private static boolean isMultiLevelWindSelected(SimulationOptions opts) {
        if (opts == null) return false;

        // 1) Wind model type enum/name (most reliable)
        try {
            Object t = opts.getClass().getMethod("getWindModelType").invoke(opts);
            if (t != null) {
                String name = String.valueOf(t).trim().toLowerCase();
                if (name.contains("multi") && name.contains("level")) return true;
                if (name.contains("average")) return false;
            }
        } catch (Exception ignored) { }

        // 2) Explicit boolean flag in some versions
        try {
            Object b = opts.getClass().getMethod("isMultiLevelWindModel").invoke(opts);
            if (b instanceof Boolean bb) return bb;
        } catch (Exception ignored) { }
        try {
            Object b = opts.getClass().getMethod("isMultiLevelWindModelEnabled").invoke(opts);
            if (b instanceof Boolean bb) return bb;
        } catch (Exception ignored) { }

        // 3) Wind model instance type/name
        try {
            Object wm = opts.getClass().getMethod("getWindModel").invoke(opts);
            if (wm != null) {
                String cls = wm.getClass().getName().toLowerCase();
                if (cls.contains("multilevel")) return true;
            }
        } catch (Exception ignored) { }

        return false;
    }

    /**
     * Attempts to resolve the currently loaded .ork filename stem and sanitize it
     * for safe filesystem use.
     */
    private static String resolveCurrentOrkStem(Simulation sim) {
        String fallback = "unsaved_ork";
        if (sim == null) return fallback;

        Object doc = invokeObject(sim, "getDocument");
        if (doc == null) {
            Object rocket = invokeObject(sim, "getRocket");
            doc = invokeObject(rocket, "getDocument");
        }

        String stem = extractDocStem(doc);
        if (stem == null || stem.isBlank()) {
            stem = safeString(sim.getName());
        }
        if (stem == null || stem.isBlank()) {
            stem = fallback;
        }

        stem = stripKnownExtension(stem, ".ork");
        stem = sanitizeFileStem(stem);
        if (stem.isBlank()) return fallback;
        return stem;
    }

    private static String extractDocStem(Object doc) {
        if (doc == null) return null;

        Object fileLike = firstNonNull(
                invokeObject(doc, "getFile"),
                invokeObject(doc, "getDocumentFile"),
                invokeObject(doc, "getSourceFile"),
                invokeObject(doc, "getPath")
        );

        String fromFileLike = fileLikeToName(fileLike);
        if (fromFileLike != null && !fromFileLike.isBlank()) return fromFileLike;

        String byMethod = firstNonBlank(
                invokeString(doc, "getFileName"),
                invokeString(doc, "getName"),
                invokeString(doc, "toString")
        );
        if (byMethod == null || byMethod.isBlank()) return null;

        return new File(byMethod).getName();
    }

    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) {
            if (v != null) return v;
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String fileLikeToName(Object fileLike) {
        if (fileLike == null) return null;
        if (fileLike instanceof File f) return f.getName();
        if (fileLike instanceof Path p) {
            Path n = p.getFileName();
            return (n != null) ? n.toString() : p.toString();
        }
        String s = String.valueOf(fileLike);
        if (s == null || s.isBlank()) return null;
        return new File(s).getName();
    }

    private static String invokeString(Object target, String method) {
        Object v = invokeObject(target, method);
        return (v == null) ? null : String.valueOf(v);
    }

    private static Object invokeObject(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripKnownExtension(String name, String extLower) {
        if (name == null) return "";
        String out = name.trim();
        if (out.toLowerCase(Locale.US).endsWith(extLower)) {
            out = out.substring(0, out.length() - extLower.length());
        }
        return out;
    }

    private static String sanitizeFileStem(String input) {
        if (input == null) return "";
        String s = input.trim();
        // Replace characters invalid on Windows/macOS/Linux filesystems.
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Collapse whitespace to single underscores for cleaner names.
        s = s.replaceAll("\\s+", "_");
        // Remove leading/trailing dots/underscores.
        s = s.replaceAll("^[._]+", "").replaceAll("[._]+$", "");
        return s;
    }

    private static String safeString(String s) {
        return (s == null) ? "" : s;
    }
}
