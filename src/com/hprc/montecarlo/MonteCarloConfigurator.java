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
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/**
 * GUI shown in OpenRocket's Simulation Extensions tab when "HPRC Monte Carlo" is selected.
 *
 * Must be discoverable by OpenRocket's plugin scanner (@Plugin) and must extend
 * AbstractSwingSimulationExtensionConfigurator.
 */
@Plugin
public class MonteCarloConfigurator
        extends AbstractSwingSimulationExtensionConfigurator<MonteCarloExtension> {

    // Keep last results in-memory so user can export after completion
    private final AtomicReference<List<MonteCarloRunRecord>> lastBatchResults = new AtomicReference<>();

    public MonteCarloConfigurator() {
        super(MonteCarloExtension.class);
    }

    @Override
    protected JComponent getConfigurationComponent(MonteCarloExtension ext, Simulation sim, JPanel panel) {
        panel.setLayout(new MigLayout("fill, ins 10, wrap 3", "[grow 0]10[grow]10[grow 0]"));

        panel.add(sectionLabel("General"), "span 3, growx");

        JCheckBox enabled = new JCheckBox("Enabled", ext.isEnabled());
        enabled.addActionListener(e -> ext.setEnabled(enabled.isSelected()));
        panel.add(enabled, "span 3, wrap");

        JCheckBox debug = new JCheckBox("Debug logging", ext.isDebugEnabled());
        debug.addActionListener(e -> ext.setDebugEnabled(debug.isSelected()));
        panel.add(debug, "span 3, wrap");

        panel.add(new JLabel("Deterministic seed"), "align label");
        JCheckBox deterministicSeed = new JCheckBox("", ext.isUseDeterministicSeed());
        deterministicSeed.addActionListener(e -> ext.setUseDeterministicSeed(deterministicSeed.isSelected()));
        panel.add(deterministicSeed, "split 2");

        JSpinner seedSpinner = new JSpinner(new SpinnerNumberModel(
                ext.getRandomSeed(), Long.MIN_VALUE, Long.MAX_VALUE, 1L));
        seedSpinner.setEditor(new SpinnerEditor(seedSpinner));
        seedSpinner.addChangeListener(e -> ext.setRandomSeed(((Number) seedSpinner.getValue()).longValue()));
        panel.add(seedSpinner, "growx");

        panel.add(new JLabel("Number of simulations"), "align label");
        JSpinner nSpinner = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(ext.getNumberOfSimulations()),
                (Comparable<Integer>) Integer.valueOf(1),
                (Comparable<Integer>) Integer.valueOf(1_000_000),
                (Number) Integer.valueOf(1)));
        nSpinner.setEditor(new SpinnerEditor(nSpinner));
        nSpinner.addChangeListener(e -> ext.setNumberOfSimulations(((Number) nSpinner.getValue()).intValue()));
        panel.add(nSpinner, "span 2, growx");

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Launch / Orientation variation"), "span 3, growx");

        addAngleStdDev(panel, "Launch rail angle Ïƒ", ext, "LaunchRodAngleStdDevDeg");
        addAngleStdDev(panel, "Launch rail direction Ïƒ", ext, "LaunchRodDirectionStdDevDeg");

        addLengthStdDev(panel, "Launch altitude Ïƒ", ext, "LaunchAltitudeStdDevM");

        addAngleStdDev(panel, "Launch latitude Ïƒ", ext, "LaunchLatitudeStdDevDeg");
        addAngleStdDev(panel, "Launch longitude Ïƒ", ext, "LaunchLongitudeStdDevDeg");

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Atmosphere / Wind variation"), "span 3, growx");

        // Determine current wind model selection from the simulation options.
        // If the simulation is using Multi-level wind, we disable the mean-wind sigma UI (by request)
        // and only use the turbulence (gust std-dev) sigma.
        final boolean multiLevelSelected = isMultiLevelWindSelected(sim.getOptions());

        // Wind speed average Ïƒ (disabled when Multi-level wind is selected)
        JLabel windAvgSigmaLabel = new JLabel("Wind speed average Ïƒ");
        windAvgSigmaLabel.setEnabled(!multiLevelSelected);
        panel.add(windAvgSigmaLabel, "align label");

        // Use bound model manually constructed so we can set enabled state on components
        DoubleModel windAvgSigmaModel = new DoubleModel(ext, "WindSpeedAverageSigmaMps", UnitGroup.UNITS_VELOCITY, 0);
        JSpinner windAvgSigmaSpinner = new JSpinner(windAvgSigmaModel.getSpinnerModel());
        windAvgSigmaSpinner.setEditor(new SpinnerEditor(windAvgSigmaSpinner));
        windAvgSigmaSpinner.setEnabled(!multiLevelSelected);

        UnitSelector windAvgSigmaUnits = new UnitSelector(windAvgSigmaModel);
        windAvgSigmaUnits.setEnabled(!multiLevelSelected);

        if (multiLevelSelected) {
            String tip = "Disabled because the base simulation is set to Multi-level wind.";
            windAvgSigmaLabel.setToolTipText(tip);
            windAvgSigmaSpinner.setToolTipText(tip);
            windAvgSigmaUnits.setToolTipText(tip);
            // Ensure the extension won't accidentally apply mean-wind variation in Multi-level mode.
            ext.setWindSpeedAverageSigmaMps(0.0);
        }

        panel.add(windAvgSigmaSpinner, "growx");
        panel.add(windAvgSigmaUnits);

        // Wind speed turbulence Ïƒ (gust std-dev) â€” always enabled
        addVelocityStdDev(panel, "Wind speed turbulence Ïƒ", ext, "WindSpeedTurbulenceSigmaMps");

        addAngleStdDev(panel, "Wind direction Ïƒ", ext, "WindDirectionStdDevDeg");

        addTemperatureStdDev(panel, "Temperature Ïƒ",
                ext.getTemperatureStdDevC(), ext::setTemperatureStdDevC);

        addPressureStdDev(panel, "Pressure Ïƒ",
                ext.getPressureStdDevMbar(), ext::setPressureStdDevMbar);

        panel.add(new JSeparator(), "span 3, growx");

        // --------------------------------------------------------------
        // Gusts / Shear (per-step wind disturbances)
        // --------------------------------------------------------------

        panel.add(sectionLabel("Wind disturbances (Gusts / Shear)"), "span 3, growx");

        panel.add(new JLabel("Enable gust events"), "align label");
        JCheckBox gustEnabled = new JCheckBox("", ext.isGustEventsEnabled());
        gustEnabled.addActionListener(e -> ext.setGustEventsEnabled(gustEnabled.isSelected()));
        panel.add(gustEnabled, "span 2, wrap");

        panel.add(new JLabel("Gust event count"), "align label");
        JSpinner gustCount = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(ext.getGustEventCount()),
                (Comparable<Integer>) Integer.valueOf(0),
                (Comparable<Integer>) Integer.valueOf(50),
                (Number) Integer.valueOf(1)));
        gustCount.setEditor(new SpinnerEditor(gustCount));
        gustCount.addChangeListener(e -> ext.setGustEventCount(((Number) gustCount.getValue()).intValue()));
        panel.add(gustCount, "growx");
        panel.add(new JLabel("events"));

        addTimeSeconds(panel, "Gust window start", ext.getGustWindowStartS(), ext::setGustWindowStartS);
        addTimeSeconds(panel, "Gust window end", ext.getGustWindowEndS(), ext::setGustWindowEndS);

        addTimeSeconds(panel, "Gust duration mean", ext.getGustDurationMeanS(), ext::setGustDurationMeanS);
        addTimeSeconds(panel, "Gust duration Ïƒ", ext.getGustDurationSigmaS(), ext::setGustDurationSigmaS);

        addVelocityStdDev(panel, "Gust peak Î”V mean", ext, "GustPeakDeltaMeanMps");
        addVelocityStdDev(panel, "Gust peak Î”V Ïƒ", ext, "GustPeakDeltaSigmaMps");

        panel.add(new JLabel("Enable shear layer"), "align label");
        JCheckBox shearEnabled = new JCheckBox("", ext.isShearLayerEnabled());
        shearEnabled.addActionListener(e -> ext.setShearLayerEnabled(shearEnabled.isSelected()));
        panel.add(shearEnabled, "span 2, wrap");

        addLengthStdDev(panel, "Shear center altitude", ext, "ShearCenterAltM");
        addLengthStdDev(panel, "Shear thickness", ext, "ShearThicknessM");
        addVelocityStdDev(panel, "Shear Î”V mean", ext, "ShearDeltaMeanMps");
        addVelocityStdDev(panel, "Shear Î”V Ïƒ", ext, "ShearDeltaSigmaMps");

        panel.add(new JSeparator(), "span 3, growx");

        // --------------------------------------------------------------
        // Vehicle / Motor physics overrides
        // --------------------------------------------------------------

        panel.add(sectionLabel("Vehicle / Motor variation"), "span 3, growx");

        addPercentSigma(panel, "CD multiplier \u03c3",
                ext.getCdMultiplierSigma() * 100.0,
                v -> ext.setCdMultiplierSigma(v / 100.0),
                "Drag coefficient variation (e.g. 5 = \u00b15% at 1\u03c3). " +
                "Accounts for surface roughness, paint, fin alignment, launch lugs.");

        addPercentSigma(panel, "Thrust multiplier \u03c3",
                ext.getThrustMultiplierSigma() * 100.0,
                v -> ext.setThrustMultiplierSigma(v / 100.0),
                "Motor total impulse variation (e.g. 3 = \u00b13% at 1\u03c3). " +
                "Typical motor-to-motor variation is 2\u20135%.");

        addPercentSigma(panel, "Mass multiplier \u03c3",
                ext.getMassMultiplierSigma() * 100.0,
                v -> ext.setMassMultiplierSigma(v / 100.0),
                "Rocket mass variation (e.g. 2 = \u00b12% at 1\u03c3). " +
                "Accounts for epoxy, paint, and hardware tolerances.");

        panel.add(new JSeparator(), "span 3, growx");
        panel.add(sectionLabel("Batch run / Export"), "span 3, growx");

        // Worker threads selector (JVM threads)
        panel.add(new JLabel("Worker threads"), "align label");
        int maxThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        int initialThreads = Math.min(Math.max(1, ext.getWorkerThreads()), maxThreads);
        JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(
                (Number) Integer.valueOf(initialThreads),
                (Comparable<Integer>) Integer.valueOf(1),
                (Comparable<Integer>) Integer.valueOf(maxThreads),
                (Number) Integer.valueOf(1)));
        threadsSpinner.setEditor(new SpinnerEditor(threadsSpinner));
        threadsSpinner.addChangeListener(e -> ext.setWorkerThreads(((Number) threadsSpinner.getValue()).intValue()));
        panel.add(threadsSpinner, "span 2, growx");

        final JProgressBar progress = new JProgressBar(0, Math.max(1, ext.getNumberOfSimulations()));
        progress.setStringPainted(true);
        progress.setValue(0);
        progress.setString("Idle");
        panel.add(progress, "span 3, growx");

        final JButton runBatch = new JButton("Run Monte Carlo Batch");
        final JButton exportDetailed = new JButton("Export Detailed CSV");

        // UPDATED: one-button export that calls LandingDispersion6DOF.exportAll(...)
        final JButton exportDispersionBundle = new JButton("Export Landing Dispersion (KML + CSV)");

        exportDetailed.setEnabled(false);
        exportDispersionBundle.setEnabled(false);

        runBatch.addActionListener(e -> {
            runBatch.setEnabled(false);
            exportDetailed.setEnabled(false);
            exportDispersionBundle.setEnabled(false);

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
                        return MonteCarloBatchRunner.runBatch(
                                sim,
                                runs,
                                (completed, total) -> SwingUtilities.invokeLater(() -> {
                                    progress.setMaximum(total);
                                    progress.setValue(completed);
                                    progress.setString(completed + " / " + total);
                                })
                        );
                    }

                    return MonteCarloBatchRunner.runBatchParallel(
                            sim,
                            runs,
                            threads,
                            (completed, total) -> SwingUtilities.invokeLater(() -> {
                                progress.setMaximum(total);
                                progress.setValue(completed);
                                progress.setString(completed + " / " + total);
                            })
                    );
                }

                @Override
                protected void done() {
                    try {
                        List<MonteCarloRunRecord> results = get();
                        lastBatchResults.set(results);

                        progress.setValue(progress.getMaximum());
                        progress.setString("Done (" + results.size() + " runs)");

                        exportDetailed.setEnabled(true);
                        exportDispersionBundle.setEnabled(true);
                    } catch (Exception ex) {
                        progress.setString("Failed");
                        JOptionPane.showMessageDialog(panel,
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

        exportDetailed.addActionListener(e -> {
            List<MonteCarloRunRecord> results = lastBatchResults.get();
            if (results == null || results.isEmpty()) {
                JOptionPane.showMessageDialog(panel,
                        "No batch results available. Run a batch first.",
                        "Nothing to Export",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Monte Carlo Detailed CSV");
            chooser.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
            chooser.setSelectedFile(new File("montecarlo_detailed.csv"));

            int option = chooser.showSaveDialog(panel);
            if (option != JFileChooser.APPROVE_OPTION) return;

            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(Locale.US).endsWith(".csv")) {
                file = new File(file.getParentFile(), file.getName() + ".csv");
            }

            try {
                MonteCarloCsvExporter.exportDetailedCsv(file, results);
                JOptionPane.showMessageDialog(panel,
                        "Exported:\n" + file.getAbsolutePath(),
                        "Export Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(panel,
                        "Export failed:\n" + ex.getMessage(),
                        "Export Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        // UPDATED: Export landing dispersion bundle (KML + points CSV + summary CSV)
        exportDispersionBundle.addActionListener(e -> {
            List<MonteCarloRunRecord> results = lastBatchResults.get();
            if (results == null || results.isEmpty()) {
                JOptionPane.showMessageDialog(panel,
                        "No batch results available. Run a batch first.",
                        "Nothing to Export",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Choose output folder (directory)
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select output folder for Landing Dispersion exports");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);

            int option = chooser.showSaveDialog(panel);
            if (option != JFileChooser.APPROVE_OPTION) return;

            File dir = chooser.getSelectedFile();
            if (dir == null) return;

            // Pull launch site lat/lon from OpenRocket simulation options
            SimulationOptions opts = sim.getOptions();
            double launchLatDeg = opts.getLaunchLatitude();
            double launchLonDeg = opts.getLaunchLongitude();

            try {
                // Reflect the requested pattern:
                String outputFolder = dir.getAbsolutePath();
                Path outDir = Paths.get(outputFolder);        // whatever you already use
                LandingDispersion6DOF.exportAll(
                        outDir,
                        "landing_dispersion",
                        results,           // List<MonteCarloRunRecord>
                        launchLatDeg,
                        launchLonDeg
                );

                JOptionPane.showMessageDialog(panel,
                        "Exported to:\n" + outDir.toAbsolutePath() + "\n\n" +
                        "Files:\n" +
                        " - landing_dispersion.kml\n" +
                        " - landing_dispersion_points.csv\n" +
                        " - landing_dispersion_summary.csv",
                        "Export Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(panel,
                        "Export failed:\n" + ex.getMessage(),
                        "Export Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(runBatch, "span 2, growx");
        panel.add(exportDetailed, "growx");

        panel.add(exportDispersionBundle, "span 3, growx");

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        return scroll;
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }


    private static void addAngleStdDev(JPanel panel, String label, MonteCarloExtension ext, String property) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_ANGLE, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addVelocityStdDev(JPanel panel, String label, MonteCarloExtension ext, String property) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_VELOCITY, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addLengthStdDev(JPanel panel, String label, MonteCarloExtension ext, String property) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(ext, property, UnitGroup.UNITS_LENGTH, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addTemperatureStdDev(JPanel panel, String label, double initialC, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");

        // Ïƒ as a delta in Â°C (Î”Â°C == Î”K)
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialC, 0.0, 500.0, 0.1));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));

        panel.add(spinner, "growx");
        panel.add(new JLabel("Â°C"));
    }

    private static void addPressureStdDev(JPanel panel, String label, double initialMbar, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");

        // Ïƒ as a delta in mbar
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialMbar, 0.0, 20000.0, 1.0));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));

        panel.add(spinner, "growx");
        panel.add(new JLabel("mbar"));
    }

    /** Simple seconds spinner with a fixed unit label.
     *  We avoid UnitGroup.UNITS_TIME because it is not present in all OR builds.
     */
    private static void addTimeSeconds(JPanel panel, String label, double initialSeconds, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialSeconds, 0.0, 10_000.0, 0.05));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));

        panel.add(spinner, "growx");
        panel.add(new JLabel("s"));
    }

    /**
     * Percentage sigma spinner (e.g. "5" meaning 5%).
     * The value is displayed/edited in percent; the setter receives percent too.
     */
    private static void addPercentSigma(JPanel panel, String label, double initialPercent, DoubleConsumer setter, String tooltip) {
        JLabel lbl = new JLabel(label);
        if (tooltip != null) lbl.setToolTipText(tooltip);
        panel.add(lbl, "align label");

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialPercent, 0.0, 100.0, 0.5));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
        if (tooltip != null) spinner.setToolTipText(tooltip);

        panel.add(spinner, "growx");
        panel.add(new JLabel("%"));
    }


    /**
     * Best-effort check of the simulation's wind model selection.
     * We use reflection here to remain compatible across OpenRocket versions.
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
}
