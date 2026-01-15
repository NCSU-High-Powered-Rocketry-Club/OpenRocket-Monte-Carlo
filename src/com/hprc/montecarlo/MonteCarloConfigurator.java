package com.hprc.montecarlo;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.plugin.Plugin;
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
import java.util.List;
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
                ext.getNumberOfSimulations(), 1, 1_000_000, 1));
        nSpinner.setEditor(new SpinnerEditor(nSpinner));
        nSpinner.addChangeListener(e -> ext.setNumberOfSimulations(((Number) nSpinner.getValue()).intValue()));
        panel.add(nSpinner, "span 2, growx");

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Launch / Orientation variation"), "span 3, growx");

        addAngleStdDev(panel, "Launch rail angle σ", UnitGroup.UNITS_ANGLE,
                Math.toRadians(ext.getLaunchRodAngleStdDevDeg()),
                v -> ext.setLaunchRodAngleStdDevDeg(Math.toDegrees(v)));

        addAngleStdDev(panel, "Launch rail direction σ", UnitGroup.UNITS_ANGLE,
                Math.toRadians(ext.getLaunchRodDirectionStdDevDeg()),
                v -> ext.setLaunchRodDirectionStdDevDeg(Math.toDegrees(v)));

        addLengthStdDev(panel, "Launch altitude σ",
                ext.getLaunchAltitudeStdDevM(), ext::setLaunchAltitudeStdDevM);

        addAngleStdDev(panel, "Launch latitude σ", UnitGroup.UNITS_ANGLE,
                Math.toRadians(ext.getLaunchLatitudeStdDevDeg()),
                v -> ext.setLaunchLatitudeStdDevDeg(Math.toDegrees(v)));

        addAngleStdDev(panel, "Launch longitude σ", UnitGroup.UNITS_ANGLE,
                Math.toRadians(ext.getLaunchLongitudeStdDevDeg()),
                v -> ext.setLaunchLongitudeStdDevDeg(Math.toDegrees(v)));

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Atmosphere / Wind variation"), "span 3, growx");

        addVelocityStdDev(panel, "Wind speed σ",
                ext.getWindSpeedStdDev(), ext::setWindSpeedStdDev);

        // NEW: optional average wind speed override
        JCheckBox useAvgWind = new JCheckBox("Use average wind speed", ext.isUseAverageWindSpeed());
        panel.add(useAvgWind, "span 3, wrap");

        panel.add(new JLabel("Average wind speed"), "align label");
        DoubleModel avgWindModel = new DoubleModel(ext.getAverageWindSpeedMps(), UnitGroup.UNITS_VELOCITY, 0);
        JSpinner avgWindSpinner = new JSpinner(avgWindModel.getSpinnerModel());
        avgWindSpinner.setEditor(new SpinnerEditor(avgWindSpinner));
        avgWindSpinner.addChangeListener(e -> ext.setAverageWindSpeedMps(avgWindModel.getValue()));
        UnitSelector avgWindUnit = new UnitSelector(avgWindModel);
        panel.add(avgWindSpinner, "growx");
        panel.add(avgWindUnit);

        // enable/disable inputs based on toggle
        java.util.function.Consumer<Boolean> setAvgEnabled = avgEnabled -> {
            avgWindSpinner.setEnabled(avgEnabled);
            avgWindUnit.setEnabled(avgEnabled);
        };
        
        setAvgEnabled.accept(ext.isUseAverageWindSpeed());
        useAvgWind.addActionListener(e -> {
            boolean on = useAvgWind.isSelected();
            ext.setUseAverageWindSpeed(on);
            setAvgEnabled.accept(on);
        });

        addAngleStdDev(panel, "Wind direction σ", UnitGroup.UNITS_ANGLE,
                Math.toRadians(ext.getWindDirectionStdDevDeg()),
                v -> ext.setWindDirectionStdDevDeg(Math.toDegrees(v)));
                
        addTemperatureStdDev(panel, "Temperature σ",
                ext.getTemperatureStdDevC(), ext::setTemperatureStdDevC);

        addPressureStdDev(panel, "Pressure σ",
                ext.getPressureStdDevMbar(), ext::setPressureStdDevMbar);

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Vehicle / Initial conditions"), "span 3, growx");

        panel.add(new JLabel("Mass variation σ (%)"), "align label");
        JSpinner massPct = new JSpinner(new SpinnerNumberModel(ext.getMassStdDevPercent(), 0.0, 100.0, 0.1));
        massPct.setEditor(new SpinnerEditor(massPct));
        massPct.addChangeListener(e -> ext.setMassStdDevPercent(((Number) massPct.getValue()).doubleValue()));
        panel.add(massPct, "span 2, growx");

        addVelocityStdDev(panel, "Initial velocity σ",
                ext.getInitialVelocityStdDev(), ext::setInitialVelocityStdDev);

        panel.add(note(
                        "Note: Some items (mass/initial velocity) require deeper OpenRocket API hooks.\n" +
                        "This panel stores them now; applying them is handled in MonteCarloExtension.initialize()."
                ),
                "span 3, growx");

        panel.add(new JSeparator(), "span 3, growx");
        panel.add(sectionLabel("Batch run / Export"), "span 3, growx");

        // NEW: Worker threads selector (JVM threads)
        panel.add(new JLabel("Worker threads"), "align label");
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int initialThreads = Math.min(Math.max(1, ext.getWorkerThreads()), maxThreads);
        JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(initialThreads, 1, maxThreads, 1));
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
        exportDetailed.setEnabled(false);

        runBatch.addActionListener(e -> {
            runBatch.setEnabled(false);
            exportDetailed.setEnabled(false);
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
                                (completed, total, msg) -> SwingUtilities.invokeLater(() -> {
                                    progress.setMaximum(total);
                                    progress.setValue(completed);
                                    progress.setString(msg);
                                })
                        );
                    }

                    return MonteCarloBatchRunner.runBatchParallel(
                            sim,
                            runs,
                            threads,
                            (completed, total, msg) -> SwingUtilities.invokeLater(() -> {
                                progress.setMaximum(total);
                                progress.setValue(completed);
                                progress.setString(msg);
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
            if (!file.getName().toLowerCase().endsWith(".csv")) {
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

        panel.add(runBatch, "span 2, growx");
        panel.add(exportDetailed, "growx");

        return panel;
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JComponent note(String text) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setOpaque(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        return a;
    }

    private static void addAngleStdDev(JPanel panel, String label, UnitGroup group, double initial, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(initial, group, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(model.getValue()));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addVelocityStdDev(JPanel panel, String label, double initial, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(initial, UnitGroup.UNITS_VELOCITY, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(model.getValue()));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addLengthStdDev(JPanel panel, String label, double initial, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(initial, UnitGroup.UNITS_LENGTH, 0);
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(model.getValue()));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addTemperatureStdDev(JPanel panel, String label, double initialC, DoubleConsumer setter) {
    panel.add(new JLabel(label), "align label");

    // σ as a delta in °C (Δ°C == ΔK)
    JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialC, 0.0, 500.0, 0.1));
    spinner.setEditor(new SpinnerEditor(spinner));
    spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));

    panel.add(spinner, "growx");
    panel.add(new JLabel("°C"));   // explicitly delta °C
    }
    
    private static void addPressureStdDev(JPanel panel, String label, double initialMbar, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
    
        // σ as a delta in mbar
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initialMbar, 0.0, 20000.0, 1.0));
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(((Number) spinner.getValue()).doubleValue()));
    
        panel.add(spinner, "growx");
        panel.add(new JLabel("mbar"));
    }
}