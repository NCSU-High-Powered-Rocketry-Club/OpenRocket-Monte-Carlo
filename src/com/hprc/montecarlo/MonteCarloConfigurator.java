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
                ext.getLaunchRodAngleStdDevDeg(), ext::setLaunchRodAngleStdDevDeg);

        addAngleStdDev(panel, "Launch rail direction σ", UnitGroup.UNITS_ANGLE,
                ext.getLaunchRodDirectionStdDevDeg(), ext::setLaunchRodDirectionStdDevDeg);

        addLengthStdDev(panel, "Launch altitude σ",
                ext.getLaunchAltitudeStdDevM(), ext::setLaunchAltitudeStdDevM);

        addAngleStdDev(panel, "Launch latitude σ", UnitGroup.UNITS_ANGLE,
                ext.getLaunchLatitudeStdDevDeg(), ext::setLaunchLatitudeStdDevDeg);

        addAngleStdDev(panel, "Launch longitude σ", UnitGroup.UNITS_ANGLE,
                ext.getLaunchLongitudeStdDevDeg(), ext::setLaunchLongitudeStdDevDeg);

        panel.add(new JSeparator(), "span 3, growx");

        panel.add(sectionLabel("Atmosphere / Wind variation"), "span 3, growx");

        addVelocityStdDev(panel, "Wind speed σ",
                ext.getWindSpeedStdDev(), ext::setWindSpeedStdDev);

        addAngleStdDev(panel, "Wind direction σ", UnitGroup.UNITS_ANGLE,
                ext.getWindDirectionStdDevDeg(), ext::setWindDirectionStdDevDeg);

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

            SwingWorker<List<MonteCarloRunRecord>, String> worker = new SwingWorker<>() {
                @Override
                protected List<MonteCarloRunRecord> doInBackground() throws Exception {
                    return MonteCarloBatchRunner.runBatch(
                            sim,
                            ext.getNumberOfSimulations(),
                            (completed, total, msg) -> {
                                setProgress((int) (100.0 * completed / Math.max(1, total)));
                                publish(msg + " (" + completed + "/" + total + ")");
                                // Update actual bar counts on EDT
                                SwingUtilities.invokeLater(() -> {
                                    progress.setMaximum(total);
                                    progress.setValue(completed);
                                    progress.setString(msg);
                                });
                            }
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

    private static void addTemperatureStdDev(JPanel panel, String label, double initial, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(initial, UnitGroup.UNITS_TEMPERATURE, 0);
        model.setCurrentUnit(UnitGroup.UNITS_TEMPERATURE.getUnit("\u00B0C"));
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(model.getValue()));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }

    private static void addPressureStdDev(JPanel panel, String label, double initial, DoubleConsumer setter) {
        panel.add(new JLabel(label), "align label");
        DoubleModel model = new DoubleModel(initial, UnitGroup.UNITS_PRESSURE, 0);
        model.setCurrentUnit(UnitGroup.UNITS_PRESSURE.getUnit("mbar"));
        JSpinner spinner = new JSpinner(model.getSpinnerModel());
        spinner.setEditor(new SpinnerEditor(spinner));
        spinner.addChangeListener(e -> setter.accept(model.getValue()));
        panel.add(spinner, "growx");
        panel.add(new UnitSelector(model));
    }
}