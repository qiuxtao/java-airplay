package com.github.serezhka.airplay.app.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.serezhka.airplay.app.ReceiverController;
import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.server.SessionInfo;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.OverlayLayout;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** A dedicated, reusable window for the active mirroring session. */
final class PlaybackWindow extends JFrame {

    private static final Dimension MINIMUM_WINDOW_SIZE = new Dimension(720, 460);

    private final ReceiverController controller;
    private final I18n i18n;
    private final JPanel player = new JPanel(new BorderLayout());
    private final JPanel controlOverlay = new JPanel(new BorderLayout());
    private final JPanel controls = new JPanel(new BorderLayout(12, 0));
    private final JLabel sessionLabel = new JLabel();
    private final JLabel formatLabel = new JLabel("—");
    private final JButton fullScreenButton = iconButton("icons/fullscreen.svg", 18);
    private final JToggleButton muteButton = new JToggleButton(new FlatSVGIcon("icons/volume.svg", 18, 18));
    private final JCheckBox alwaysOnTop = new JCheckBox();
    private final JButton stopButton = new JButton(new FlatSVGIcon("icons/stop.svg", 16, 16));
    private final JSlider volume;
    private final Timer hideControlsTimer;
    private boolean activeSession;
    private boolean fullScreen;
    private Rectangle windowedBounds;
    private String sessionAddress;

    PlaybackWindow(ReceiverController controller, I18n i18n) {
        super("AirPlay Receiver");
        this.controller = controller;
        this.i18n = i18n;
        this.volume = new JSlider(0, 100, (int) Math.round(controller.settings().volume() * 100));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(MINIMUM_WINDOW_SIZE);
        setSize(980, 640);
        buildUi();
        hideControlsTimer = new Timer(3000, event -> controls.setVisible(false));
        hideControlsTimer.setRepeats(false);
        installBehavior();
        refreshTexts();
    }

    void showSession(SessionInfo session, AppSettings settings) {
        activeSession = true;
        stopButton.setEnabled(true);
        sessionAddress = session.remoteAddress() == null
                ? i18n.text("player.unknownDevice")
                : session.remoteAddress().getAddress().getHostAddress();
        sessionLabel.setText(i18n.text("player.device", sessionAddress));
        setTitle(i18n.text("player.windowTitle"));
        showControls();
        if (!isVisible()) {
            setAutoRequestFocus(settings.bringToFront());
            setLocationRelativeTo(null);
            setVisible(true);
            setAutoRequestFocus(true);
        }
        if (settings.bringToFront()) {
            setExtendedState(getExtendedState() & ~ICONIFIED);
            toFront();
            requestFocus();
        }
    }

    void endSession() {
        activeSession = false;
        sessionAddress = null;
        formatLabel.setText("—");
        stopButton.setEnabled(true);
        hideControlsTimer.stop();
        if (fullScreen) {
            leaveFullScreen();
        }
        setVisible(false);
    }

    void updateVideoFormat(int width, int height) {
        formatLabel.setText(width + " × " + height);
        if (!fullScreen && width > 0 && height > 0) {
            fitWindowToVideo(width, height);
        }
    }

    void refreshTexts() {
        setTitle(i18n.text("player.windowTitle"));
        fullScreenButton.setToolTipText(i18n.text("player.fullscreen"));
        muteButton.setToolTipText(i18n.text("player.mute"));
        alwaysOnTop.setText(i18n.text("player.alwaysOnTop"));
        stopButton.setText(i18n.text("player.stop"));
        if (sessionAddress != null) {
            sessionLabel.setText(i18n.text("player.device", sessionAddress));
        }
    }

    void closeWindow() {
        hideControlsTimer.stop();
        if (fullScreen) {
            leaveFullScreen();
        }
        dispose();
    }

    private void buildUi() {
        player.setBackground(Color.BLACK);
        player.setLayout(new OverlayLayout(player));

        controls.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 12));
        controls.setBackground(new Color(20, 22, 28));
        JPanel session = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        session.setOpaque(false);
        sessionLabel.setForeground(Color.WHITE);
        sessionLabel.setFont(sessionLabel.getFont().deriveFont(13f));
        formatLabel.setForeground(new Color(177, 183, 196));
        session.add(sessionLabel);
        session.add(formatLabel);
        controls.add(session, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        fullScreenButton.addActionListener(event -> toggleFullScreen());
        actions.add(fullScreenButton);
        muteButton.addActionListener(event -> updateMutedState(muteButton.isSelected()));
        actions.add(muteButton);
        volume.setPreferredSize(new Dimension(96, 28));
        volume.addChangeListener(event -> controller.setVolume(volume.getValue() / 100.0));
        actions.add(volume);
        alwaysOnTop.setForeground(Color.WHITE);
        alwaysOnTop.addActionListener(event -> setAlwaysOnTop(alwaysOnTop.isSelected()));
        actions.add(alwaysOnTop);
        stopButton.addActionListener(event -> requestDisconnect());
        actions.add(stopButton);
        controls.add(actions, BorderLayout.EAST);

        controlOverlay.setOpaque(false);
        controlOverlay.add(controls, BorderLayout.SOUTH);
        controlOverlay.setAlignmentX(0.5f);
        controlOverlay.setAlignmentY(0.5f);
        controller.videoComponent().setAlignmentX(0.5f);
        controller.videoComponent().setAlignmentY(0.5f);
        player.add(controlOverlay);
        player.add(controller.videoComponent());
        installControlHover(controls);
        setContentPane(player);
    }

    private void installBehavior() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (activeSession) {
                    requestDisconnect();
                } else {
                    setVisible(false);
                }
            }
        });

        MouseAdapter revealControls = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                showControls();
            }
        };
        player.addMouseMotionListener(revealControls);
        controlOverlay.addMouseMotionListener(revealControls);
        controller.videoComponent().addMouseMotionListener(revealControls);

        JRootPane rootPane = getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("F11"), "toggleFullScreen");
        rootPane.getActionMap().put("toggleFullScreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                toggleFullScreen();
            }
        });
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "leaveFullScreen");
        rootPane.getActionMap().put("leaveFullScreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (fullScreen) {
                    leaveFullScreen();
                }
            }
        });
    }

    private void requestDisconnect() {
        if (!activeSession) {
            setVisible(false);
            return;
        }
        stopButton.setEnabled(false);
        controller.disconnectSession();
    }

    private void updateMutedState(boolean muted) {
        controller.setMuted(muted);
        muteButton.setIcon(new FlatSVGIcon(muted ? "icons/muted.svg" : "icons/volume.svg", 18, 18));
        muteButton.setToolTipText(i18n.text(muted ? "player.unmute" : "player.mute"));
    }

    private void toggleFullScreen() {
        if (fullScreen) {
            leaveFullScreen();
            return;
        }
        windowedBounds = getBounds();
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        GraphicsDevice device = configuration == null ? null : configuration.getDevice();
        if (device != null) {
            fullScreen = true;
            device.setFullScreenWindow(this);
        }
    }

    private void leaveFullScreen() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        GraphicsDevice device = configuration == null ? null : configuration.getDevice();
        if (device != null && device.getFullScreenWindow() == this) {
            device.setFullScreenWindow(null);
        }
        fullScreen = false;
        if (windowedBounds != null) {
            setBounds(windowedBounds);
        }
    }

    private void fitWindowToVideo(int width, int height) {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            return;
        }
        Rectangle screen = configuration.getBounds();
        int maximumWidth = Math.min(1180, (int) (screen.width * 0.82));
        int maximumVideoHeight = Math.min(760, (int) (screen.height * 0.78) - controls.getPreferredSize().height);
        double scale = Math.min((double) maximumWidth / width, (double) maximumVideoHeight / height);
        int targetWidth = Math.max(MINIMUM_WINDOW_SIZE.width, (int) Math.round(width * scale));
        int targetHeight = Math.max(MINIMUM_WINDOW_SIZE.height,
                (int) Math.round(height * scale) + controls.getPreferredSize().height);
        setSize(Math.min(targetWidth, maximumWidth), Math.min(targetHeight, screen.height - 80));
        setLocationRelativeTo(null);
    }

    private void showControls() {
        controls.setVisible(true);
        hideControlsTimer.restart();
    }

    private void installControlHover(java.awt.Component component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hideControlsTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hideControlsTimer.restart();
            }
        });
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                installControlHover(child);
            }
        }
    }

    private static JButton iconButton(String icon, int size) {
        JButton button = new JButton(new FlatSVGIcon(icon, size, size));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        return button;
    }
}
