package com.github.serezhka.airplay.app.ui;

import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.settings.AppSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class SettingsDialog extends JDialog {

    private final I18n i18n;
    private final JTextField receiverName = new JTextField();
    private final JComboBox<AppSettings.DisplayMode> displayMode = new JComboBox<>(AppSettings.DisplayMode.values());
    private final JSpinner width = new JSpinner(new SpinnerNumberModel(1920, 640, 7680, 1));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(1080, 480, 4320, 1));
    private final JSpinner fps = new JSpinner(new SpinnerNumberModel(60, 15, 60, 1));
    private final JComboBox<AppSettings.ThemeMode> theme = new JComboBox<>(AppSettings.ThemeMode.values());
    private final JComboBox<AppSettings.LanguageMode> language = new JComboBox<>(AppSettings.LanguageMode.values());
    private final JCheckBox startWithWindows = new JCheckBox();
    private final JCheckBox bringToFront = new JCheckBox();
    private final JCheckBox closeToTray = new JCheckBox();
    private AppSettings original;

    SettingsDialog(Frame owner, I18n i18n) {
        super(owner, true);
        this.i18n = i18n;
        setTitle(i18n.text("settings.title"));
        setMinimumSize(new Dimension(560, 560));
        setLocationRelativeTo(owner);
        buildUi();
    }

    void showSettings(AppSettings settings, Consumer<AppSettings> onSave) {
        original = settings;
        receiverName.setText(settings.receiverName());
        displayMode.setSelectedItem(settings.displayMode());
        width.setValue(settings.customWidth());
        height.setValue(settings.customHeight());
        fps.setValue(settings.maxFps());
        theme.setSelectedItem(settings.theme());
        language.setSelectedItem(settings.language());
        startWithWindows.setSelected(settings.startWithWindows());
        bringToFront.setSelected(settings.bringToFront());
        closeToTray.setSelected(settings.closeToTray());
        updateCustomFields();
        setLocationRelativeTo(getOwner());
        setVisible(true);
        if (getRootPane().getClientProperty("saved") == Boolean.TRUE) {
            onSave.accept(readSettings());
        }
        getRootPane().putClientProperty("saved", false);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(22, 24, 12, 24));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(7, 0, 7, 12);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 0;

        int row = 0;
        addRow(form, constraints, row++, i18n.text("settings.receiverName"), receiverName);
        addRow(form, constraints, row++, i18n.text("settings.displayMode"), displayMode);

        JPanel dimensions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        dimensions.add(width);
        dimensions.add(new JLabel("×"));
        dimensions.add(height);
        addRow(form, constraints, row++, i18n.text("settings.customSize"), dimensions);
        addRow(form, constraints, row++, i18n.text("settings.maxFps"), fps);
        addRow(form, constraints, row++, i18n.text("settings.theme"), theme);
        addRow(form, constraints, row++, i18n.text("settings.language"), language);

        startWithWindows.setText(i18n.text("settings.startWithWindows"));
        bringToFront.setText(i18n.text("settings.bringToFront"));
        closeToTray.setText(i18n.text("settings.closeToTray"));
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(startWithWindows);
        options.add(Box.createVerticalStrut(8));
        options.add(bringToFront);
        options.add(Box.createVerticalStrut(8));
        options.add(closeToTray);
        addRow(form, constraints, row, i18n.text("settings.behavior"), options);

        displayMode.addActionListener(event -> updateCustomFields());
        installLocalizedRenderer(displayMode, "displayMode.");
        installLocalizedRenderer(theme, "theme.");
        installLocalizedRenderer(language, "language.");

        JButton cancel = new JButton(i18n.text("action.cancel"));
        cancel.addActionListener(event -> setVisible(false));
        JButton save = new JButton(i18n.text("action.save"));
        save.putClientProperty("FlatLaf.styleClass", "accent");
        save.addActionListener(event -> save());
        getRootPane().setDefaultButton(save);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 18, 8, 18));
        buttons.add(cancel);
        buttons.add(save);

        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component input) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        form.add(input, constraints);
    }

    private void updateCustomFields() {
        boolean custom = displayMode.getSelectedItem() == AppSettings.DisplayMode.CUSTOM;
        width.setEnabled(custom);
        height.setEnabled(custom);
    }

    private void save() {
        String name = receiverName.getText().trim();
        if (name.isBlank() || name.getBytes(StandardCharsets.UTF_8).length > 63) {
            JOptionPane.showMessageDialog(this, i18n.text("settings.invalidName"),
                    i18n.text("error.title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        getRootPane().putClientProperty("saved", true);
        setVisible(false);
    }

    private AppSettings readSettings() {
        return new AppSettings(receiverName.getText(),
                (AppSettings.DisplayMode) displayMode.getSelectedItem(),
                (Integer) width.getValue(), (Integer) height.getValue(), (Integer) fps.getValue(),
                (AppSettings.ThemeMode) theme.getSelectedItem(),
                (AppSettings.LanguageMode) language.getSelectedItem(),
                startWithWindows.isSelected(), bringToFront.isSelected(), closeToTray.isSelected(),
                original.receiverEnabled(), original.volume()).normalized();
    }

    private <T extends Enum<T>> void installLocalizedRenderer(JComboBox<T> comboBox, String prefix) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean selected, boolean focused) {
                String text = value instanceof Enum<?> enumValue
                        ? i18n.text(prefix + enumValue.name().toLowerCase()) : "";
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
    }
}
