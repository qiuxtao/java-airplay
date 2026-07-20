package com.github.serezhka.airplay.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.serezhka.airplay.app.ReceiverController;
import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.server.SessionInfo;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** A dedicated, reusable window for the active mirroring session. */
final class PlaybackWindow extends JFrame {

    private static final Dimension FALLBACK_MINIMUM_SIZE = new Dimension(640, 400);
    private static final int MINIMUM_PORTRAIT_WIDTH = 420;
    private static final int MINIMUM_LANDSCAPE_HEIGHT = 360;

    private final ReceiverController controller;
    private final I18n i18n;
    private final JPanel player = new JPanel(new BorderLayout());
    private final JMenuBar titleControls = new JMenuBar();
    private final JLabel sessionLabel = new JLabel(new FlatSVGIcon("icons/app.svg", 20, 20));
    private final JLabel formatLabel = new JLabel("—");
    private final JButton fullScreenButton = iconButton("icons/fullscreen.svg", 17);
    private final JToggleButton muteButton = toggleButton("icons/volume.svg", 17);
    private final JToggleButton alwaysOnTopButton = toggleButton("icons/pin.svg", 17);
    private final JSlider volume;
    private final Timer aspectTimer;

    private boolean activeSession;
    private boolean disconnectRequested;
    private boolean fullScreen;
    private boolean adjustingAspect;
    private Rectangle windowedBounds;
    private String sessionAddress;
    private int sourceWidth;
    private int sourceHeight;
    private Dimension lastAcceptedVideoSize;

    PlaybackWindow(ReceiverController controller, I18n i18n) {
        super("AirPlay Receiver");
        this.controller = controller;
        this.i18n = i18n;
        this.volume = new JSlider(0, 100, (int) Math.round(controller.settings().volume() * 100));
        this.aspectTimer = new Timer(12, event -> enforceAspectRatio());
        aspectTimer.setRepeats(false);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(FALLBACK_MINIMUM_SIZE);
        setSize(980, 640);
        buildUi();
        installBehavior();
        refreshTexts();
    }

    void showSession(SessionInfo session, AppSettings settings) {
        activeSession = true;
        disconnectRequested = false;
        sessionAddress = session.remoteAddress() == null
                ? i18n.text("player.unknownDevice")
                : session.remoteAddress().getAddress().getHostAddress();
        sessionLabel.setToolTipText(i18n.text("player.device", sessionAddress));
        setTitle(i18n.text("player.windowTitle"));
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
        disconnectRequested = false;
        sessionAddress = null;
        sourceWidth = 0;
        sourceHeight = 0;
        lastAcceptedVideoSize = null;
        formatLabel.setText("—");
        aspectTimer.stop();
        setMinimumSize(FALLBACK_MINIMUM_SIZE);
        if (fullScreen) {
            leaveFullScreen();
        }
        setVisible(false);
    }

    void updateVideoFormat(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        sourceWidth = width;
        sourceHeight = height;
        formatLabel.setText(width + " × " + height);
        if (!fullScreen) {
            fitWindowToVideo(width, height);
        }
    }

    void refreshTexts() {
        setTitle(i18n.text("player.windowTitle"));
        fullScreenButton.setToolTipText(i18n.text("player.fullscreen"));
        muteButton.setToolTipText(i18n.text(controller.muted() ? "player.unmute" : "player.mute"));
        alwaysOnTopButton.setToolTipText(i18n.text("player.alwaysOnTop"));
        if (sessionAddress != null) {
            sessionLabel.setToolTipText(i18n.text("player.device", sessionAddress));
        }
    }

    void closeWindow() {
        aspectTimer.stop();
        if (fullScreen) {
            leaveFullScreen();
        }
        dispose();
    }

    private void buildUi() {
        JRootPane rootPane = getRootPane();
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, 46);

        titleControls.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
        titleControls.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION,
                (java.util.function.Function<java.awt.Point, Boolean>) point -> null);

        JPanel caption = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        caption.setOpaque(false);
        caption.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        sessionLabel.setIconTextGap(7);
        formatLabel.putClientProperty("FlatLaf.styleClass", "small");
        caption.add(sessionLabel);
        caption.add(formatLabel);
        titleControls.add(caption);
        titleControls.add(Box.createHorizontalGlue());

        fullScreenButton.addActionListener(event -> toggleFullScreen());
        titleControls.add(fullScreenButton);
        titleControls.add(Box.createHorizontalStrut(3));

        muteButton.addActionListener(event -> updateMutedState(muteButton.isSelected()));
        titleControls.add(muteButton);
        titleControls.add(Box.createHorizontalStrut(4));

        volume.setPreferredSize(new Dimension(82, 26));
        volume.setMaximumSize(new Dimension(96, 26));
        volume.addChangeListener(event -> controller.setVolume(volume.getValue() / 100.0));
        titleControls.add(volume);
        titleControls.add(Box.createHorizontalStrut(4));

        alwaysOnTopButton.addActionListener(event -> {
            boolean selected = alwaysOnTopButton.isSelected();
            setAlwaysOnTop(selected);
        });
        titleControls.add(alwaysOnTopButton);
        setJMenuBar(titleControls);

        player.setBackground(Color.BLACK);
        player.add(controller.videoComponent(), BorderLayout.CENTER);
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

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (!adjustingAspect && sourceWidth > 0 && sourceHeight > 0 && !fullScreen) {
                    aspectTimer.restart();
                }
            }
        });

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
        if (!activeSession || disconnectRequested) {
            return;
        }
        disconnectRequested = true;
        setVisible(false);
        controller.disconnectSession();
    }

    private void updateMutedState(boolean muted) {
        controller.setMuted(muted);
        muteButton.setIcon(new FlatSVGIcon(muted ? "icons/muted.svg" : "icons/volume.svg", 17, 17));
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
            titleControls.setVisible(false);
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
        titleControls.setVisible(true);
        if (windowedBounds != null) {
            adjustingAspect = true;
            try {
                setBounds(windowedBounds);
            } finally {
                adjustingAspect = false;
            }
        }
    }

    private void fitWindowToVideo(int width, int height) {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            return;
        }
        Rectangle screen = configuration.getBounds();
        int chromeWidth = chromeWidth();
        int chromeHeight = chromeHeight();
        int availableWidth = Math.min(1280, (int) Math.round(screen.width * 0.82) - chromeWidth);
        int availableHeight = Math.min(1000, (int) Math.round(screen.height * 0.82) - chromeHeight);
        double scale = Math.min(1d, Math.min((double) availableWidth / width, (double) availableHeight / height));
        Dimension target = new Dimension(
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)));
        Dimension minimum = minimumVideoSize(width, height, screen, chromeWidth, chromeHeight);
        if (target.width < minimum.width || target.height < minimum.height) {
            target = minimum;
        }
        setMinimumSize(frameSizeForVideo(minimum));
        applyVideoSize(target, true);
    }

    private void enforceAspectRatio() {
        if (sourceWidth <= 0 || sourceHeight <= 0 || fullScreen
                || (getExtendedState() & MAXIMIZED_BOTH) != 0 || adjustingAspect) {
            return;
        }
        Dimension current = player.getSize();
        if (current.width <= 0 || current.height <= 0) {
            return;
        }
        if (Math.abs((double) current.width / current.height - (double) sourceWidth / sourceHeight) < 0.002) {
            lastAcceptedVideoSize = current;
            return;
        }
        Dimension previous = lastAcceptedVideoSize == null ? current : lastAcceptedVideoSize;
        Dimension constrained = constrainVideoSize(
                sourceWidth, sourceHeight, current.width, current.height, previous.width, previous.height);
        applyVideoSize(constrained, false);
    }

    private void applyVideoSize(Dimension videoSize, boolean recenter) {
        adjustingAspect = true;
        try {
            setSize(videoSize.width + chromeWidth(), videoSize.height + chromeHeight());
            lastAcceptedVideoSize = new Dimension(videoSize);
            if (recenter) {
                setLocationRelativeTo(null);
            }
        } finally {
            adjustingAspect = false;
        }
    }

    private Dimension minimumVideoSize(int width,
                                       int height,
                                       Rectangle screen,
                                       int chromeWidth,
                                       int chromeHeight) {
        double aspect = (double) width / height;
        int maximumWidth = Math.max(280, (int) (screen.width * 0.82) - chromeWidth);
        int maximumHeight = Math.max(280, (int) (screen.height * 0.82) - chromeHeight);
        int minimumWidth;
        int minimumHeight;
        if (aspect < 1) {
            minimumWidth = MINIMUM_PORTRAIT_WIDTH;
            minimumHeight = (int) Math.round(minimumWidth / aspect);
        } else {
            minimumHeight = MINIMUM_LANDSCAPE_HEIGHT;
            minimumWidth = (int) Math.round(minimumHeight * aspect);
        }
        if (minimumWidth > maximumWidth || minimumHeight > maximumHeight) {
            double scale = Math.min((double) maximumWidth / minimumWidth,
                    (double) maximumHeight / minimumHeight);
            minimumWidth = Math.max(280, (int) Math.round(minimumWidth * scale));
            minimumHeight = Math.max(200, (int) Math.round(minimumHeight * scale));
        }
        return new Dimension(minimumWidth, minimumHeight);
    }

    private Dimension frameSizeForVideo(Dimension videoSize) {
        return new Dimension(videoSize.width + chromeWidth(), videoSize.height + chromeHeight());
    }

    private int chromeWidth() {
        int measured = getWidth() - player.getWidth();
        return measured > 0 ? measured : getInsets().left + getInsets().right;
    }

    private int chromeHeight() {
        int measured = getHeight() - player.getHeight();
        return measured > 0
                ? measured
                : getInsets().top + getInsets().bottom + titleControls.getPreferredSize().height;
    }

    static Dimension constrainVideoSize(int sourceWidth,
                                        int sourceHeight,
                                        int currentWidth,
                                        int currentHeight,
                                        int previousWidth,
                                        int previousHeight) {
        double aspect = (double) sourceWidth / sourceHeight;
        double widthChange = Math.abs(currentWidth - previousWidth) / (double) Math.max(1, previousWidth);
        double heightChange = Math.abs(currentHeight - previousHeight) / (double) Math.max(1, previousHeight);
        if (widthChange >= heightChange) {
            return new Dimension(Math.max(1, currentWidth),
                    Math.max(1, (int) Math.round(currentWidth / aspect)));
        }
        return new Dimension(Math.max(1, (int) Math.round(currentHeight * aspect)),
                Math.max(1, currentHeight));
    }

    private static JButton iconButton(String icon, int size) {
        JButton button = new JButton(new FlatSVGIcon(icon, size, size));
        styleTitleButton(button);
        return button;
    }

    private static JToggleButton toggleButton(String icon, int size) {
        JToggleButton button = new JToggleButton(new FlatSVGIcon(icon, size, size));
        styleTitleButton(button);
        return button;
    }

    private static void styleTitleButton(javax.swing.AbstractButton button) {
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE,
                FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.SQUARE_SIZE, true);
        button.setPreferredSize(new Dimension(32, 30));
        button.setMaximumSize(new Dimension(32, 30));
        button.setFocusable(false);
    }
}
