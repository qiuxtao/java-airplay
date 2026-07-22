package com.github.serezhka.airplay.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.serezhka.airplay.app.ReceiverController;
import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.platform.WindowsAspectRatioWindowResizer;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.server.SessionInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** A dedicated, reusable window for the active mirroring session. */
final class PlaybackWindow extends JFrame {

    private static final Dimension FALLBACK_MINIMUM_SIZE = new Dimension(640, 400);
    private static final int MINIMUM_PORTRAIT_WIDTH = 420;
    private static final int MINIMUM_LANDSCAPE_HEIGHT = 360;
    private static final int SCREEN_GAP = 18;

    private final ReceiverController controller;
    private final I18n i18n;
    private final JPanel player = new JPanel(new BorderLayout());
    private final JMenuBar titleControls = new JMenuBar();
    private final JLabel sessionLabel = new JLabel(new FlatSVGIcon("icons/app.svg", 20, 20));
    private final JLabel formatLabel = new JLabel("—");
    private final JToggleButton muteButton = toggleButton("icons/volume.svg", 17);
    private final JToggleButton alwaysOnTopButton = toggleButton("icons/pin.svg", 17);
    private final JSlider volume;
    private WindowsAspectRatioWindowResizer aspectResizer;

    private boolean activeSession;
    private boolean disconnectRequested;
    private String sessionAddress;
    private AppSettings sessionSettings;
    private int sourceWidth;
    private int sourceHeight;

    PlaybackWindow(ReceiverController controller, I18n i18n) {
        super("AirPlay Receiver");
        this.controller = controller;
        this.i18n = i18n;
        this.volume = new JSlider(0, 100, (int) Math.round(controller.settings().volume() * 100));
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
        sessionSettings = settings;
        sessionAddress = session.remoteAddress() == null
                ? i18n.text("player.unknownDevice")
                : session.remoteAddress().getAddress().getHostAddress();
        sessionLabel.setToolTipText(i18n.text("player.device", sessionAddress));
        setTitle(playbackTitle());
        if (sourceWidth > 0 && sourceHeight > 0) {
            prepareAndShow(settings);
        }
    }

    void endSession() {
        activeSession = false;
        disconnectRequested = false;
        sessionAddress = null;
        sessionSettings = null;
        sourceWidth = 0;
        sourceHeight = 0;
        formatLabel.setText("—");
        if (aspectResizer != null) {
            aspectResizer.clearVideoFormat();
        }
        setMinimumSize(FALLBACK_MINIMUM_SIZE);
        setVisible(false);
    }

    void updateVideoFormat(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        sourceWidth = width;
        sourceHeight = height;
        formatLabel.setText(width + " × " + height);
        ensureDisplayable();
        ensureAspectResizer();
        if ((getExtendedState() & MAXIMIZED_BOTH) == 0 || !isVisible()) {
            fitWindowToVideo(width, height);
        }
        if (activeSession && sessionSettings != null) {
            prepareAndShow(sessionSettings);
            correctInitialLayout(width, height);
        }
    }

    void refreshTexts() {
        setTitle(playbackTitle());
        muteButton.setToolTipText(i18n.text(controller.muted() ? "player.unmute" : "player.mute"));
        alwaysOnTopButton.setToolTipText(i18n.text("player.alwaysOnTop"));
        if (sessionAddress != null) {
            sessionLabel.setToolTipText(i18n.text("player.device", sessionAddress));
        }
        SwingUtilities.invokeLater(this::updateAspectResizer);
    }

    void closeWindow() {
        WindowsAspectRatioWindowResizer currentResizer = aspectResizer;
        if (currentResizer != null) {
            currentResizer.close();
        }
        dispose();
        aspectResizer = null;
    }

    private void buildUi() {
        JRootPane rootPane = getRootPane();
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE, true);
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
        addPropertyChangeListener("graphicsConfiguration", event ->
                SwingUtilities.invokeLater(this::updateAspectResizer));
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

    private void fitWindowToVideo(int width, int height) {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle screen = usableScreenBounds(configuration);
        int chromeWidth = chromeWidth();
        int chromeHeight = chromeHeight();
        int availableWidth = Math.max(1, screen.width - SCREEN_GAP * 2 - chromeWidth);
        int availableHeight = Math.max(1, screen.height - SCREEN_GAP * 2 - chromeHeight);
        Dimension target = fitVideoSize(width, height, availableWidth, availableHeight);
        Dimension minimum = minimumVideoSize(width, height, screen, chromeWidth, chromeHeight);
        if (target.width < minimum.width || target.height < minimum.height) {
            target = minimum;
        }
        setMinimumSize(frameSizeForVideo(minimum));
        applyVideoSize(target, screen);
        updateAspectResizer();
    }

    private void applyVideoSize(Dimension videoSize, Rectangle usableScreen) {
        setBounds(sideWindowBounds(frameSizeForVideo(videoSize), usableScreen, SCREEN_GAP));
        validate();
    }

    static Rectangle sideWindowBounds(Dimension windowSize, Rectangle usableScreen, int gap) {
        int x = usableScreen.x + usableScreen.width - windowSize.width - gap;
        int y = usableScreen.y + Math.max(gap, (usableScreen.height - windowSize.height) / 2);
        return new Rectangle(
                Math.max(usableScreen.x + gap, x),
                y,
                windowSize.width,
                windowSize.height);
    }

    private void ensureDisplayable() {
        if (!isDisplayable()) {
            addNotify();
            validate();
        }
    }

    private void ensureAspectResizer() {
        if (aspectResizer == null) {
            aspectResizer = WindowsAspectRatioWindowResizer.install(this);
            setResizable(aspectResizer.isNativeActive());
        }
    }

    private void prepareAndShow(AppSettings settings) {
        ensureDisplayable();
        ensureAspectResizer();
        if (!isVisible()) {
            setAutoRequestFocus(settings.bringToFront());
            setVisible(true);
            setAutoRequestFocus(true);
        }
        if (settings.bringToFront()) {
            setExtendedState(getExtendedState() & ~ICONIFIED);
            toFront();
            requestFocus();
        }
    }

    private void correctInitialLayout(int width, int height) {
        SwingUtilities.invokeLater(() -> {
            if (!activeSession || sourceWidth != width || sourceHeight != height
                    || (getExtendedState() & MAXIMIZED_BOTH) != 0) {
                return;
            }
            validate();
            fitWindowToVideo(width, height);
        });
    }

    private void updateAspectResizer() {
        if (aspectResizer != null && sourceWidth > 0 && sourceHeight > 0) {
            aspectResizer.setVideoFormat(
                    sourceWidth, sourceHeight, chromeWidth(), chromeHeight(), getMinimumSize());
        }
    }

    private String playbackTitle() {
        return "AirPlay Receiver";
    }

    static Dimension fitVideoSize(int sourceWidth,
                                  int sourceHeight,
                                  int availableWidth,
                                  int availableHeight) {
        double aspect = (double) sourceWidth / sourceHeight;
        int height = Math.max(1, Math.min(availableHeight, (int) Math.floor(availableWidth / aspect)));
        int width = Math.max(1, (int) Math.round(height * aspect));
        while (width > availableWidth && height > 1) {
            width = Math.max(1, (int) Math.round(--height * aspect));
        }
        return new Dimension(width, height);
    }

    private Rectangle usableScreenBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
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
            return fitVideoSize(width, height, maximumWidth, maximumHeight);
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
