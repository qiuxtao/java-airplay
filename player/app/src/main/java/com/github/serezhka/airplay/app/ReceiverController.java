package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.app.platform.WindowsIntegration;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.app.settings.SettingsStore;
import com.github.serezhka.airplay.app.theme.ThemeManager;
import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.player.gstreamer.GstPlayerListener;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.AirPlayServerListener;
import com.github.serezhka.airplay.server.ServerState;
import com.github.serezhka.airplay.server.SessionInfo;
import com.github.serezhka.airplay.server.SessionState;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JComponent;
import java.awt.DisplayMode;
import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class ReceiverController implements AutoCloseable {

    private final SettingsStore settingsStore;
    private final GstPlayer player;
    private final ThemeManager themeManager;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "airplay-controller");
        thread.setDaemon(true);
        return thread;
    });

    private volatile AppSettings settings;
    private volatile ReceiverView view;
    private volatile AirPlayServer server;
    private volatile boolean pendingRestart;
    private volatile String displayedSessionId;

    public ReceiverController(SettingsStore settingsStore, AppSettings settings, ThemeManager themeManager) {
        this.settingsStore = settingsStore;
        this.settings = settings;
        this.themeManager = themeManager;
        try {
            WindowsIntegration.setStartWithWindows(settings.startWithWindows());
        } catch (RuntimeException error) {
            log.warn("Unable to synchronize the Windows startup setting", error);
        }
        player = new GstPlayer();
        player.setVolume(settings.volume());
        player.setListener(new GstPlayerListener() {
            @Override
            public void onVideoFormatChanged(int width, int height) {
                onEdt(receiverView -> receiverView.onVideoFormat(width, height));
            }

            @Override
            public void onPlaybackError(String message, Throwable error) {
                onEdt(receiverView -> receiverView.onError(message, error));
            }

            @Override
            public void onEndOfStream() {
                disconnectSession();
            }
        });
        server = createServer(settings);
    }

    public void attachView(ReceiverView view) {
        this.view = Objects.requireNonNull(view);
        view.onSettingsChanged(settings);
        view.onServerState(server.state());
    }

    public JComponent videoComponent() {
        return player.videoComponent();
    }

    public AppSettings settings() {
        return settings;
    }

    public void start() {
        startReceiver();
    }

    public void updateSettings(AppSettings updated) {
        AppSettings normalized = updated.normalized();
        AppSettings previous = settings;
        settings = normalized;
        settingsStore.save(normalized);
        player.setVolume(normalized.volume());
        themeManager.apply(normalized.theme());
        try {
            WindowsIntegration.setStartWithWindows(normalized.startWithWindows());
        } catch (RuntimeException error) {
            onEdt(receiverView -> receiverView.onError("Unable to update Windows startup setting", error));
        }
        onEdt(receiverView -> receiverView.onSettingsChanged(normalized));

        if (!serverSettingsEqual(previous, normalized)) {
            if (server.activeSession().isPresent()) {
                pendingRestart = true;
            } else {
                restartReceiver();
            }
        }
    }

    public void setVolume(double volume) {
        player.setVolume(volume);
        AppSettings updated = settings.withVolume(volume);
        settings = updated;
        settingsStore.save(updated);
    }

    public void setMuted(boolean muted) {
        player.setMuted(muted);
    }

    public boolean muted() {
        return player.muted();
    }

    public void disconnectSession() {
        worker.execute(() -> server.disconnectActiveSession());
    }

    public void startReceiver() {
        worker.execute(() -> {
            try {
                server.start();
            } catch (Exception error) {
                log.error("Unable to start AirPlay receiver", error);
                onEdt(receiverView -> receiverView.onError(error.getMessage(), error));
            }
        });
    }

    public void restartReceiver() {
        worker.execute(() -> {
            try {
                server.restart(toServerConfig(settings));
                pendingRestart = false;
            } catch (Exception error) {
                log.error("Unable to restart AirPlay receiver", error);
                onEdt(receiverView -> receiverView.onError(error.getMessage(), error));
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdownNow();
        try {
            server.close();
        } finally {
            try {
                player.close();
            } finally {
                themeManager.close();
            }
        }
    }

    private AirPlayServer createServer(AppSettings appSettings) {
        return new AirPlayServer(toServerConfig(appSettings), player, new AirPlayServerListener() {
            @Override
            public void onServerStateChanged(ServerState state) {
                onEdt(receiverView -> receiverView.onServerState(state));
            }

            @Override
            public void onSessionChanged(SessionInfo session, SessionState state) {
                if (state == SessionState.PLAYING) {
                    if (!session.id().equals(displayedSessionId)) {
                        displayedSessionId = session.id();
                        onEdt(receiverView -> receiverView.onSessionStarted(session));
                    }
                } else if (state == SessionState.STOPPED) {
                    if (session.id().equals(displayedSessionId)) {
                        displayedSessionId = null;
                        onEdt(ReceiverView::onSessionStopped);
                    }
                    if (pendingRestart && server.activeSession().isEmpty()) {
                        restartReceiver();
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                onEdt(receiverView -> receiverView.onError(error.getMessage(), error));
            }
        });
    }

    private AirPlayConfig toServerConfig(AppSettings appSettings) {
        int[] dimensions = displayDimensions(appSettings);
        AirPlayConfig config = new AirPlayConfig();
        config.setServerName(appSettings.receiverName());
        config.setWidth(dimensions[0]);
        config.setHeight(dimensions[1]);
        config.setFps(appSettings.maxFps());
        config.setMirrorOnly(true);
        return config;
    }

    private int[] displayDimensions(AppSettings appSettings) {
        return switch (appSettings.displayMode()) {
            case HD_720 -> new int[]{1280, 720};
            case FULL_HD_1080 -> new int[]{1920, 1080};
            case CUSTOM -> new int[]{appSettings.customWidth(), appSettings.customHeight()};
            case PRIMARY_DISPLAY -> {
                DisplayMode mode = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDisplayMode();
                yield new int[]{mode.getWidth(), mode.getHeight()};
            }
        };
    }

    private boolean serverSettingsEqual(AppSettings first, AppSettings second) {
        return first.receiverName().equals(second.receiverName())
                && first.displayMode() == second.displayMode()
                && first.customWidth() == second.customWidth()
                && first.customHeight() == second.customHeight()
                && first.maxFps() == second.maxFps();
    }

    private void onEdt(java.util.function.Consumer<ReceiverView> action) {
        ReceiverView currentView = view;
        if (currentView == null) {
            return;
        }
        SwingDispatcher.dispatch(() -> action.accept(currentView));
    }
}
