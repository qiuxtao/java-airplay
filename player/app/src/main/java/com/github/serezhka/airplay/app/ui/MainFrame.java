package com.github.serezhka.airplay.app.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
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
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public final class MainFrame extends JFrame implements ReceiverView {

    private static final String IDLE = "idle";
    private static final String PLAYER = "player";

    private final ReceiverController controller;
    private final I18n i18n;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JLabel pageTitle = heading(25, Font.BOLD);
    private final JLabel statusLabel = new JLabel();
    private final JToggleButton receiverToggle = new JToggleButton();
    private final JLabel receiverName = heading(28, Font.BOLD);
    private final JLabel waitingTitle = heading(20, Font.BOLD);
    private final JLabel waitingSubtitle = new JLabel();
    private final JLabel instructionsTitle = heading(17, Font.BOLD);
    private final JLabel[] instructionSteps = {new JLabel(), new JLabel(), new JLabel()};
    private final JLabel deviceInfoTitle = heading(17, Font.BOLD);
    private final JLabel networkTitle = new JLabel();
    private final JLabel capabilityTitle = new JLabel();
    private final JLabel trustedNetworkLabel = new JLabel();
    private final JLabel networkValue = new JLabel();
    private final JLabel resolutionValue = new JLabel();
    private final JLabel languageHint = new JLabel();
    private final JLabel sessionLabel = new JLabel();
    private final JLabel formatLabel = new JLabel("—");
    private final JPanel errorBanner = new JPanel(new BorderLayout(12, 0));
    private final JLabel errorLabel = new JLabel();
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 9));
    private final Timer hideControlsTimer;
    private final JToggleButton muteButton = iconToggle("icons/volume.svg", 18);
    private final JCheckBox alwaysOnTop = new JCheckBox();
    private final JButton settingsButton = iconButton("icons/settings.svg", 18);
    private final JButton logsButton = iconButton("icons/logs.svg", 16);
    private final JButton firewallButton = new JButton();
    private final JButton fullScreenButton = iconButton("icons/fullscreen.svg", 18);
    private final JButton stopButton = new JButton(new FlatSVGIcon("icons/stop.svg", 16, 16));
    private TrayController tray;
    private AppSettings settings;
    private boolean fullScreen;
    private boolean playing;
    private String sessionAddress;
    private ServerState serverState = ServerState.STOPPED;

    public MainFrame(ReceiverController controller, I18n i18n) {
        super("AirPlay Receiver");
        this.controller = controller;
        this.i18n = i18n;
        this.settings = controller.settings();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(860, 600));
        setSize(1080, 720);
        setLocationRelativeTo(null);
        buildUi();
        hideControlsTimer = new Timer(3000, event -> controls.setVisible(false));
        hideControlsTimer.setRepeats(false);
        installWindowBehavior();
        refreshTexts();
        tray = new TrayController(this, controller, i18n);
    }

    @Override
    public void onServerState(ServerState state) {
        serverState = state;
        statusLabel.setText(i18n.text("state." + state.name().toLowerCase()));
        statusLabel.putClientProperty("FlatLaf.style", statusStyle(state));
        receiverToggle.setSelected(state == ServerState.READY || state == ServerState.STARTING);
        receiverToggle.setEnabled(state != ServerState.STARTING && state != ServerState.STOPPING);
        tray.update(state);
        if (state == ServerState.READY) {
            hideError();
        }
    }

    @Override
    public void onSessionStarted(SessionInfo session) {
        playing = true;
        sessionAddress = session.remoteAddress() == null
                ? i18n.text("player.unknownDevice")
                : session.remoteAddress().getAddress().getHostAddress();
        sessionLabel.setText(i18n.text("player.device", sessionAddress));
        cardLayout.show(cards, PLAYER);
        pageTitle.setText(i18n.text("player.title"));
        showControls();
        if (settings.bringToFront()) {
            restoreAndShow();
        }
    }

    @Override
    public void onSessionStopped() {
        playing = false;
        sessionAddress = null;
        cardLayout.show(cards, IDLE);
        pageTitle.setText(i18n.text("home.title"));
        formatLabel.setText("—");
        if (fullScreen) {
            toggleFullScreen();
        }
    }

    @Override
    public void onVideoFormat(int width, int height) {
        formatLabel.setText(width + " × " + height);
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
    public void onSettingsChanged(AppSettings settings) {
        boolean languageChanged = this.settings.language() != settings.language();
        this.settings = settings;
        i18n.setLanguage(settings.language());
        receiverName.setText(settings.receiverName());
        resolutionValue.setText(displayDescription(settings));
        refreshTexts();
        if (languageChanged) {
            tray.close();
            tray = new TrayController(this, controller, i18n);
        }
        tray.update(serverState);
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
        tray.close();
        controller.close();
        dispose();
        System.exit(0);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        root.add(buildSidebar(), BorderLayout.WEST);

        JPanel workspace = new JPanel(new BorderLayout(0, 14));
        workspace.setBorder(BorderFactory.createEmptyBorder(22, 26, 24, 26));
        workspace.add(buildHeader(), BorderLayout.NORTH);
        cards.add(buildIdlePanel(), IDLE);
        cards.add(buildPlayerPanel(), PLAYER);
        workspace.add(cards, BorderLayout.CENTER);
        root.add(workspace, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(230, 0));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(28, 24, 24, 24));
        sidebar.putClientProperty("FlatLaf.style", "background: darken(@background,3%)");

        JLabel logo = new JLabel("  AirPlay Receiver", new FlatSVGIcon("icons/app.svg", 32, 32),
                SwingConstants.LEFT);
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 16f));
        logo.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(logo);
        sidebar.add(Box.createVerticalStrut(42));

        receiverName.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(receiverName);
        sidebar.add(Box.createVerticalStrut(8));
        languageHint.setAlignmentX(LEFT_ALIGNMENT);
        languageHint.putClientProperty("FlatLaf.styleClass", "small");
        sidebar.add(languageHint);
        sidebar.add(Box.createVerticalGlue());

        logsButton.setHorizontalAlignment(SwingConstants.LEFT);
        logsButton.setAlignmentX(LEFT_ALIGNMENT);
        logsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        logsButton.addActionListener(event -> WindowsIntegration.openDirectory(AppPaths.logsDirectory()));
        sidebar.add(logsButton);
        sidebar.add(Box.createVerticalStrut(8));

        settingsButton.setText(i18n.text("settings.title"));
        settingsButton.setHorizontalAlignment(SwingConstants.LEFT);
        settingsButton.setAlignmentX(LEFT_ALIGNMENT);
        settingsButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        settingsButton.addActionListener(event -> new SettingsDialog(this, i18n)
                .showSettings(settings, controller::updateSettings));
        sidebar.add(settingsButton);
        return sidebar;
    }

    private JPanel buildHeader() {
        JPanel headerAndError = new JPanel(new BorderLayout(0, 12));
        JPanel header = new JPanel(new BorderLayout());
        header.add(pageTitle, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(7, 13, 7, 13));
        receiverToggle.addActionListener(event -> controller.setReceiverEnabled(receiverToggle.isSelected()));
        status.add(statusLabel);
        status.add(receiverToggle);
        header.add(status, BorderLayout.EAST);
        headerAndError.add(header, BorderLayout.NORTH);

        errorBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 83, 83, 120)),
                BorderFactory.createEmptyBorder(10, 14, 10, 8)));
        errorBanner.putClientProperty("FlatLaf.style", "arc: 14; background: fade(#d84d4d,12%)");
        errorBanner.add(errorLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        firewallButton.addActionListener(event -> WindowsIntegration.openFirewallSettings());
        JButton dismiss = new JButton("×");
        dismiss.addActionListener(event -> hideError());
        actions.add(firewallButton);
        actions.add(dismiss);
        errorBanner.add(actions, BorderLayout.EAST);
        errorBanner.setVisible(false);
        headerAndError.add(errorBanner, BorderLayout.SOUTH);
        return headerAndError;
    }

    private JPanel buildIdlePanel() {
        JPanel idle = new JPanel(new BorderLayout(0, 20));
        JPanel hero = cardPanel(new BorderLayout(18, 8));
        hero.setBorder(BorderFactory.createEmptyBorder(34, 38, 34, 38));
        JLabel illustration = new JLabel(new FlatSVGIcon("icons/cast.svg", 96, 96));
        hero.add(illustration, BorderLayout.WEST);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(waitingTitle);
        copy.add(Box.createVerticalStrut(7));
        waitingSubtitle.setFont(waitingSubtitle.getFont().deriveFont(15f));
        copy.add(waitingSubtitle);
        hero.add(copy, BorderLayout.CENTER);
        idle.add(hero, BorderLayout.NORTH);

        JPanel lower = new JPanel(new GridLayout(1, 2, 18, 0));
        lower.add(buildInstructions());
        lower.add(buildDeviceInfo());
        idle.add(lower, BorderLayout.CENTER);
        return idle;
    }

    private JPanel buildInstructions() {
        JPanel panel = cardPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
        panel.add(instructionsTitle);
        panel.add(Box.createVerticalStrut(20));
        for (int index = 1; index <= 3; index++) {
            JLabel step = instructionSteps[index - 1];
            step.setIcon(numberIcon(index));
            step.setIconTextGap(13);
            panel.add(step);
            panel.add(Box.createVerticalStrut(17));
        }
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildDeviceInfo() {
        JPanel panel = cardPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 24, 26));
        panel.add(deviceInfoTitle);
        panel.add(Box.createVerticalStrut(24));
        panel.add(infoRow(networkTitle, networkValue));
        panel.add(Box.createVerticalStrut(20));
        panel.add(infoRow(capabilityTitle, resolutionValue));
        panel.add(Box.createVerticalStrut(20));
        trustedNetworkLabel.setIcon(new FlatSVGIcon("icons/shield.svg", 18, 18));
        trustedNetworkLabel.setHorizontalAlignment(SwingConstants.LEFT);
        trustedNetworkLabel.setIconTextGap(10);
        panel.add(trustedNetworkLabel);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel buildPlayerPanel() {
        JPanel player = new JPanel(new BorderLayout());
        player.setBackground(Color.BLACK);
        player.setBorder(BorderFactory.createLineBorder(new Color(0, 0, 0, 80)));
        player.add(controller.videoComponent(), BorderLayout.CENTER);

        controls.setBackground(new Color(20, 22, 28));
        sessionLabel.setForeground(Color.WHITE);
        formatLabel.setForeground(new Color(190, 195, 205));
        controls.add(sessionLabel);
        controls.add(formatLabel);
        fullScreenButton.addActionListener(event -> toggleFullScreen());
        controls.add(fullScreenButton);
        muteButton.addActionListener(event -> {
            controller.setMuted(muteButton.isSelected());
            muteButton.setIcon(new FlatSVGIcon(muteButton.isSelected()
                    ? "icons/muted.svg" : "icons/volume.svg", 18, 18));
        });
        controls.add(muteButton);
        JSlider volume = new JSlider(0, 100, (int) Math.round(settings.volume() * 100));
        volume.setPreferredSize(new Dimension(110, 26));
        volume.addChangeListener(event -> controller.setVolume(volume.getValue() / 100.0));
        controls.add(volume);
        alwaysOnTop.setText(i18n.text("player.alwaysOnTop"));
        alwaysOnTop.setForeground(Color.WHITE);
        alwaysOnTop.addActionListener(event -> setAlwaysOnTop(alwaysOnTop.isSelected()));
        controls.add(alwaysOnTop);
        stopButton.addActionListener(event -> controller.disconnectSession());
        controls.add(stopButton);
        player.add(controls, BorderLayout.SOUTH);

        MouseAdapter reveal = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                showControls();
            }
        };
        player.addMouseMotionListener(reveal);
        controller.videoComponent().addMouseMotionListener(reveal);
        return player;
    }

    private JPanel infoRow(JLabel label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        label.putClientProperty("FlatLaf.styleClass", "small");
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private void refreshTexts() {
        pageTitle.setText(playing
                ? i18n.text("player.title") : i18n.text("home.title"));
        receiverToggle.setText(i18n.text("receiver.toggle"));
        receiverName.setText(settings.receiverName());
        languageHint.setText(i18n.text("sidebar.subtitle"));
        waitingTitle.setText(i18n.text("home.readyTitle"));
        waitingSubtitle.setText(i18n.text("home.readySubtitle", settings.receiverName()));
        instructionsTitle.setText(i18n.text("home.howTo"));
        for (int index = 0; index < instructionSteps.length; index++) {
            instructionSteps[index].setText(i18n.text("home.step" + (index + 1)));
        }
        deviceInfoTitle.setText(i18n.text("home.deviceInfo"));
        networkTitle.setText(i18n.text("home.network"));
        capabilityTitle.setText(i18n.text("home.capability"));
        trustedNetworkLabel.setText(i18n.text("home.trustedNetwork"));
        List<String> addresses = NetworkInfo.localAddresses();
        networkValue.setText(addresses.isEmpty() ? i18n.text("home.noNetwork") : String.join("  ·  ", addresses));
        resolutionValue.setText(displayDescription(settings));
        settingsButton.setText(i18n.text("settings.title"));
        logsButton.setText(i18n.text("action.logs"));
        firewallButton.setText(i18n.text("action.firewall"));
        fullScreenButton.setToolTipText(i18n.text("player.fullscreen"));
        alwaysOnTop.setText(i18n.text("player.alwaysOnTop"));
        stopButton.setText(i18n.text("player.stop"));
        statusLabel.setText(i18n.text("state." + serverState.name().toLowerCase()));
        if (sessionAddress != null) {
            sessionLabel.setText(i18n.text("player.device", sessionAddress));
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
            case HD_720 -> "1280 × 720 · " + appSettings.maxFps() + "fps";
            case FULL_HD_1080 -> "1920 × 1080 · " + appSettings.maxFps() + "fps";
            case CUSTOM -> appSettings.customWidth() + " × " + appSettings.customHeight()
                    + " · " + appSettings.maxFps() + "fps";
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

    private void toggleFullScreen() {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        fullScreen = !fullScreen;
        device.setFullScreenWindow(fullScreen ? this : null);
        if (!fullScreen) {
            setSize(Math.max(getWidth(), 860), Math.max(getHeight(), 600));
            setLocationRelativeTo(null);
        }
    }

    private void showControls() {
        controls.setVisible(true);
        hideControlsTimer.restart();
    }

    private void hideError() {
        errorBanner.setVisible(false);
        revalidate();
    }

    private static JPanel cardPanel() {
        JPanel panel = new JPanel();
        panel.putClientProperty("FlatLaf.style", "arc: 22; background: lighten(@background,2%)");
        return panel;
    }

    private static JPanel cardPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.putClientProperty("FlatLaf.style", "arc: 22; background: lighten(@background,2%)");
        return panel;
    }

    private static JLabel heading(float size, int style) {
        JLabel label = new JLabel();
        label.setFont(label.getFont().deriveFont(style, size));
        return label;
    }

    private static JButton iconButton(String icon, int size) {
        JButton button = new JButton(new FlatSVGIcon(icon, size, size));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JToggleButton iconToggle(String icon, int size) {
        JToggleButton button = new JToggleButton(new FlatSVGIcon(icon, size, size));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static javax.swing.Icon numberIcon(int number) {
        return new javax.swing.Icon() {
            @Override
            public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(83, 109, 254));
                g.fillOval(x, y, 26, 26);
                g.setColor(Color.WHITE);
                g.setFont(component.getFont().deriveFont(Font.BOLD, 13f));
                g.drawString(String.valueOf(number), x + 9, y + 18);
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return 26;
            }

            @Override
            public int getIconHeight() {
                return 26;
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
}
