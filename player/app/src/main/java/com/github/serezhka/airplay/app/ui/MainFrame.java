package com.github.serezhka.airplay.app.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.FlatLaf;
import com.github.serezhka.airplay.app.AppPaths;
import com.github.serezhka.airplay.app.ReceiverController;
import com.github.serezhka.airplay.app.ReceiverView;
import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.platform.NetworkInfo;
import com.github.serezhka.airplay.app.platform.WindowsIntegration;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.server.ServerState;
import com.github.serezhka.airplay.server.SessionInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainFrame extends JFrame implements ReceiverView {

    private final ReceiverController controller;
    private final I18n i18n;
    private final PlaybackWindow playbackWindow;
    private final JLabel statusLabel = new JLabel();
    private final JLabel waitingTitle = heading(22, Font.BOLD);
    private final JTextArea waitingSubtitle = textArea(15f);
    private final JLabel receiverCaption = new JLabel();
    private final JTextArea receiverName = textArea(28f, Font.BOLD);
    private final JLabel instructionsTitle = heading(18, Font.BOLD);
    private final JTextArea[] instructionSteps = {textArea(14f), textArea(14f), textArea(14f)};
    private final JLabel deviceInfoTitle = heading(18, Font.BOLD);
    private final JLabel networkTitle = new JLabel();
    private final JLabel capabilityTitle = new JLabel();
    private final JLabel trustedNetworkLabel = new JLabel();
    private final JTextArea networkValue = textArea(14f);
    private final JTextArea resolutionValue = textArea(14f);
    private final JPanel errorBanner = new JPanel(new BorderLayout(12, 0));
    private final JLabel errorLabel = new JLabel();
    private final JButton settingsButton = iconButton("icons/settings.svg", 18);
    private final JButton logsButton = iconButton("icons/logs.svg", 17);
    private final JButton firewallButton = new JButton();
    private final AtomicBoolean exiting = new AtomicBoolean();
    private TrayController tray;
    private AppSettings settings;
    private boolean playing;
    private ServerState serverState = ServerState.STOPPED;

    public MainFrame(ReceiverController controller, I18n i18n) {
        super("AirPlay Receiver");
        this.controller = controller;
        this.i18n = i18n;
        this.settings = controller.settings();
        this.playbackWindow = new PlaybackWindow(controller, i18n);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1040, 720);
        setLocationRelativeTo(null);
        buildUi();
        installWindowBehavior();
        refreshTexts();
        tray = new TrayController(this, i18n);
    }

    @Override
    public void onServerState(ServerState state) {
        serverState = state;
        refreshStatus();
        tray.update(state, playing);
        if (state == ServerState.READY) {
            hideError();
        }
    }

    @Override
    public void onSessionStarted(SessionInfo session) {
        playing = true;
        refreshHero();
        refreshStatus();
        tray.update(serverState, true);
        playbackWindow.showSession(session, settings);
    }

    @Override
    public void onSessionStopped() {
        playing = false;
        refreshHero();
        refreshStatus();
        tray.update(serverState, false);
        playbackWindow.endSession();
    }

    @Override
    public void onVideoFormat(int width, int height) {
        playbackWindow.updateVideoFormat(width, height);
    }

    @Override
    public void onError(String message, Throwable error) {
        String displayMessage;
        if (message != null && message.contains("No active multicast-capable IPv4 network")) {
            displayMessage = i18n.text("error.noNetwork");
        } else if (message == null || message.isBlank()) {
            displayMessage = i18n.text("error.generic");
        } else if (error == null) {
            displayMessage = i18n.text("error.mediaPlayback", message);
        } else {
            displayMessage = message;
        }
        errorLabel.setText(displayMessage);
        errorBanner.setVisible(true);
        revalidate();
        tray.showError(displayMessage);
    }

    @Override
    public void onSettingsChanged(AppSettings updatedSettings) {
        boolean languageChanged = this.settings.language() != updatedSettings.language();
        this.settings = updatedSettings;
        i18n.setLanguage(updatedSettings.language());
        refreshTexts();
        playbackWindow.refreshTexts();
        if (languageChanged) {
            tray.close();
            tray = new TrayController(this, i18n);
        }
        tray.update(serverState, playing);
    }

    public void restoreAndShow() {
        if (!isVisible()) {
            setVisible(true);
        }
        setExtendedState(getExtendedState() & ~ICONIFIED);
        toFront();
        requestFocus();
    }

    public void exitApplication() {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        setVisible(false);
        playbackWindow.closeWindow();
        tray.close();
        dispose();

        Timer forcedExit = new Timer(3000, event -> System.exit(0));
        forcedExit.setRepeats(false);
        forcedExit.start();
        Thread.ofPlatform().name("airplay-shutdown").start(() -> {
            try {
                controller.close();
            } finally {
                System.exit(0);
            }
        });
    }

    private void buildUi() {
        JPanel root = new BrandBackgroundPanel();
        root.setLayout(new BorderLayout());
        root.add(buildAppBar(), BorderLayout.NORTH);

        JPanel workspace = new JPanel(new BorderLayout(0, 16));
        workspace.setOpaque(false);
        workspace.setBorder(BorderFactory.createEmptyBorder(22, 28, 28, 28));
        workspace.add(buildErrorBanner(), BorderLayout.NORTH);
        workspace.add(buildDashboard(), BorderLayout.CENTER);
        root.add(workspace, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildAppBar() {
        JPanel appBar = new AppBarPanel();
        appBar.setLayout(new BorderLayout());
        appBar.setBorder(BorderFactory.createEmptyBorder(15, 28, 15, 22));

        JLabel product = new JLabel("  AirPlay Receiver", new FlatSVGIcon("icons/app.svg", 32, 32),
                SwingConstants.LEFT);
        product.setFont(product.getFont().deriveFont(Font.BOLD, 17f));
        appBar.add(product, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(7, 13, 7, 13));
        actions.add(statusLabel);
        logsButton.addActionListener(event -> WindowsIntegration.openDirectory(AppPaths.logsDirectory()));
        actions.add(logsButton);
        settingsButton.addActionListener(event -> new SettingsDialog(this, i18n)
                .showSettings(settings, controller::updateSettings));
        actions.add(settingsButton);
        appBar.add(actions, BorderLayout.EAST);
        return appBar;
    }

    private JPanel buildErrorBanner() {
        errorBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 83, 83, 100)),
                BorderFactory.createEmptyBorder(10, 14, 10, 8)));
        errorBanner.putClientProperty("FlatLaf.style", "arc: 16; background: fade(#d84d4d,12%)");
        errorBanner.add(errorLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        firewallButton.addActionListener(event -> WindowsIntegration.openFirewallSettings());
        JButton dismiss = new JButton("×");
        dismiss.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0");
        dismiss.addActionListener(event -> hideError());
        actions.add(firewallButton);
        actions.add(dismiss);
        errorBanner.add(actions, BorderLayout.EAST);
        errorBanner.setVisible(false);
        return errorBanner;
    }

    private JPanel buildDashboard() {
        JPanel dashboard = new JPanel(new GridBagLayout());
        dashboard.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 18, 0);
        dashboard.add(buildHero(), constraints);

        constraints.gridy = 1;
        constraints.gridwidth = 1;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(0, 0, 0, 9);
        dashboard.add(buildInstructions(), constraints);
        constraints.gridx = 1;
        constraints.insets = new Insets(0, 9, 0, 0);
        dashboard.add(buildDeviceInfo(), constraints);
        return dashboard;
    }

    private JPanel buildHero() {
        JPanel hero = cardPanel(new BorderLayout(26, 0));
        hero.setBorder(BorderFactory.createEmptyBorder(28, 34, 28, 34));
        JLabel illustration = new JLabel(new FlatSVGIcon("icons/cast.svg", 92, 92));
        hero.add(illustration, BorderLayout.WEST);

        JPanel copy = transparentColumn();
        waitingTitle.setAlignmentX(LEFT_ALIGNMENT);
        copy.add(waitingTitle);
        copy.add(Box.createVerticalStrut(5));
        waitingSubtitle.setAlignmentX(LEFT_ALIGNMENT);
        copy.add(waitingSubtitle);
        copy.add(Box.createVerticalStrut(17));
        receiverCaption.putClientProperty("FlatLaf.styleClass", "small");
        receiverCaption.setAlignmentX(LEFT_ALIGNMENT);
        copy.add(receiverCaption);
        copy.add(Box.createVerticalStrut(2));
        receiverName.setRows(2);
        receiverName.setAlignmentX(LEFT_ALIGNMENT);
        copy.add(receiverName);
        hero.add(copy, BorderLayout.CENTER);
        return hero;
    }

    private JPanel buildInstructions() {
        JPanel panel = cardPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
        instructionsTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(instructionsTitle);
        panel.add(Box.createVerticalStrut(18));
        for (int index = 0; index < instructionSteps.length; index++) {
            panel.add(instructionRow(index + 1, instructionSteps[index]));
            if (index < instructionSteps.length - 1) {
                panel.add(Box.createVerticalStrut(15));
            }
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel instructionRow(int number, JTextArea text) {
        JPanel row = new JPanel(new BorderLayout(13, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel badge = new JLabel(String.valueOf(number), numberIcon(), SwingConstants.CENTER);
        badge.setHorizontalTextPosition(SwingConstants.CENTER);
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 13f));
        row.add(badge, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildDeviceInfo() {
        JPanel panel = cardPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
        deviceInfoTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(deviceInfoTitle);
        panel.add(Box.createVerticalStrut(18));
        networkTitle.putClientProperty("FlatLaf.styleClass", "small");
        networkTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(networkTitle);
        panel.add(Box.createVerticalStrut(5));
        networkValue.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(networkValue);
        panel.add(Box.createVerticalStrut(18));
        capabilityTitle.putClientProperty("FlatLaf.styleClass", "small");
        capabilityTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(capabilityTitle);
        panel.add(Box.createVerticalStrut(5));
        resolutionValue.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(resolutionValue);
        panel.add(Box.createVerticalGlue());
        trustedNetworkLabel.setIcon(new FlatSVGIcon("icons/shield.svg", 18, 18));
        trustedNetworkLabel.setIconTextGap(9);
        trustedNetworkLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(trustedNetworkLabel);
        return panel;
    }

    private void refreshTexts() {
        refreshHero();
        instructionsTitle.setText(i18n.text("home.howTo"));
        for (int index = 0; index < instructionSteps.length; index++) {
            instructionSteps[index].setText(i18n.text("home.step" + (index + 1)));
        }
        deviceInfoTitle.setText(i18n.text("home.deviceInfo"));
        networkTitle.setText(i18n.text("home.network"));
        capabilityTitle.setText(i18n.text("home.capability"));
        trustedNetworkLabel.setText(i18n.text("home.trustedNetwork"));
        List<String> addresses = NetworkInfo.localAddresses();
        String addressText = addresses.isEmpty() ? i18n.text("home.noNetwork") : String.join("  ·  ", addresses);
        networkValue.setText(addressText);
        networkValue.setToolTipText(addressText);
        resolutionValue.setText(displayDescription(settings));
        logsButton.setToolTipText(i18n.text("action.logs"));
        settingsButton.setToolTipText(i18n.text("settings.title"));
        firewallButton.setText(i18n.text("action.firewall"));
        refreshStatus();

        receiverCaption.setText(i18n.text("home.receiverName"));
    }

    private void refreshHero() {
        receiverName.setText(settings.receiverName());
        receiverName.setToolTipText(settings.receiverName());
        if (playing) {
            waitingTitle.setText(i18n.text("home.castingTitle"));
            waitingSubtitle.setText(i18n.text("home.castingSubtitle"));
        } else {
            waitingTitle.setText(i18n.text("home.readyTitle"));
            waitingSubtitle.setText(i18n.text("home.readySubtitle", settings.receiverName()));
        }
    }

    private void refreshStatus() {
        statusLabel.setVisible(playing || serverState != ServerState.READY);
        if (playing) {
            statusLabel.setText(i18n.text("state.playing"));
            statusLabel.putClientProperty("FlatLaf.style",
                    "arc: 999; background: fade(#536dfe,20%); foreground: #7085ff");
        } else {
            statusLabel.setText(i18n.text("state." + serverState.name().toLowerCase()));
            statusLabel.putClientProperty("FlatLaf.style", statusStyle(serverState));
        }
    }

    private String displayDescription(AppSettings appSettings) {
        return switch (appSettings.displayMode()) {
            case PRIMARY_DISPLAY -> {
                java.awt.DisplayMode display = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDisplayMode();
                yield i18n.text("display.primary", String.valueOf(display.getWidth()),
                        String.valueOf(display.getHeight()), String.valueOf(appSettings.maxFps()));
            }
            case HD_720 -> "1280 × 720  ·  " + appSettings.maxFps() + "fps";
            case FULL_HD_1080 -> "1920 × 1080  ·  " + appSettings.maxFps() + "fps";
            case CUSTOM -> appSettings.customWidth() + " × " + appSettings.customHeight()
                    + "  ·  " + appSettings.maxFps() + "fps";
        };
    }

    private void installWindowBehavior() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                if (settings.closeToTray() && tray.available()) {
                    setVisible(false);
                } else {
                    exitApplication();
                }
            }
        });
    }

    private void hideError() {
        errorBanner.setVisible(false);
        revalidate();
    }

    private static JPanel transparentColumn() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel cardPanel() {
        return new BrandCard(false, null);
    }

    private static JPanel cardPanel(LayoutManager layout) {
        return new BrandCard(layout instanceof BorderLayout, layout);
    }

    private static JLabel heading(float size, int style) {
        JLabel label = new JLabel();
        label.setFont(label.getFont().deriveFont(style, size));
        return label;
    }

    private static JTextArea textArea(float size) {
        return textArea(size, Font.PLAIN);
    }

    private static JTextArea textArea(float size, int style) {
        JTextArea text = new JTextArea();
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(null);
        text.setFont(text.getFont().deriveFont(style, size));
        return text;
    }

    private static JButton iconButton(String icon, int size) {
        JButton button = new JButton(new FlatSVGIcon(icon, size, size));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("FlatLaf.style", "arc: 12; margin: 8,10,8,10");
        return button;
    }

    private static javax.swing.Icon numberIcon() {
        return new javax.swing.Icon() {
            @Override
            public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(83, 109, 254));
                g.fillOval(x, y, 28, 28);
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return 28;
            }

            @Override
            public int getIconHeight() {
                return 28;
            }
        };
    }

    private String statusStyle(ServerState state) {
        return switch (state) {
            case READY -> "arc: 999; background: fade(#22a06b,18%); foreground: #22a06b";
            case FAILED -> "arc: 999; background: fade(#d84d4d,18%); foreground: #d84d4d";
            case STARTING, STOPPING -> "arc: 999; background: fade(#e39b24,18%); foreground: #c88413";
            case STOPPED -> "arc: 999; background: fade(#7a8190,16%); foreground: #7a8190";
        };
    }

    private static final class BrandBackgroundPanel extends JPanel {

        BrandBackgroundPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean dark = FlatLaf.isLafDark();
            Color top = dark ? new Color(8, 13, 28) : new Color(247, 249, 255);
            Color bottom = dark ? new Color(17, 25, 48) : new Color(232, 239, 253);
            g.setPaint(new GradientPaint(0, 0, top, 0, Math.max(1, getHeight()), bottom));
            g.fillRect(0, 0, getWidth(), getHeight());

            float radius = Math.max(240f, getWidth() * 0.62f);
            Color glow = dark ? new Color(77, 96, 255, 72) : new Color(92, 119, 255, 58);
            g.setPaint(new RadialGradientPaint(
                    new Point2D.Float(getWidth() * 0.78f, getHeight() * 0.08f), radius,
                    new float[]{0f, 0.52f, 1f},
                    new Color[]{glow, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 18),
                            new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0)}));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
        }
    }

    private static final class AppBarPanel extends JPanel {

        AppBarPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            boolean dark = FlatLaf.isLafDark();
            g.setColor(dark ? new Color(9, 14, 29, 218) : new Color(255, 255, 255, 210));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(dark ? new Color(112, 131, 230, 42) : new Color(84, 105, 190, 35));
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class BrandCard extends JPanel {

        private final boolean hero;

        BrandCard(boolean hero, LayoutManager layout) {
            super(layout);
            this.hero = hero;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean dark = FlatLaf.isLafDark();
            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 24f, 24f);
            if (hero) {
                Color start = dark ? new Color(30, 40, 77, 238) : new Color(255, 255, 255, 242);
                Color end = dark ? new Color(20, 31, 60, 238) : new Color(235, 241, 255, 242);
                g.setPaint(new GradientPaint(0, 0, start, getWidth(), getHeight(), end));
            } else {
                g.setColor(dark ? new Color(20, 27, 48, 224) : new Color(255, 255, 255, 224));
            }
            g.fill(shape);
            g.setColor(dark ? new Color(119, 137, 231, hero ? 70 : 42)
                    : new Color(96, 116, 196, hero ? 60 : 36));
            g.draw(shape);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
