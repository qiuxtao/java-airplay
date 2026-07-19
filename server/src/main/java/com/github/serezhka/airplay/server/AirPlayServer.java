package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AirPlayBonjour;
import com.github.serezhka.airplay.server.internal.ControlServer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AirPlayServer implements AutoCloseable {

    private final AirPlayConsumer airPlayConsumer;
    private final List<AirPlayServerListener> listeners = new CopyOnWriteArrayList<>();

    private volatile AirPlayConfig airPlayConfig;
    private volatile AirPlayBonjour airPlayBonjour;
    private volatile ControlServer controlServer;
    private volatile ServerState state = ServerState.STOPPED;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        this(airPlayConfig, airPlayConsumer, null);
    }

    public AirPlayServer(AirPlayConfig airPlayConfig,
                         AirPlayConsumer airPlayConsumer,
                         AirPlayServerListener listener) {
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        if (listener != null) {
            listeners.add(listener);
        }
        rebuildServers();
    }

    public synchronized void start() throws Exception {
        if (state == ServerState.READY || state == ServerState.STARTING) {
            return;
        }

        transition(ServerState.STARTING);
        try {
            controlServer.start();
            airPlayBonjour.start(controlServer.getPort());
            transition(ServerState.READY);
        } catch (Exception error) {
            airPlayBonjour.stop();
            controlServer.stop();
            transition(ServerState.FAILED);
            listeners.forEach(listener -> listener.onError(error));
            throw error;
        }
    }

    public synchronized void stop() {
        if (state == ServerState.STOPPED || state == ServerState.STOPPING) {
            return;
        }

        transition(ServerState.STOPPING);
        airPlayBonjour.stop();
        controlServer.stop();
        transition(ServerState.STOPPED);
    }

    public synchronized void restart() throws Exception {
        stop();
        rebuildServers();
        start();
    }

    public synchronized void restart(AirPlayConfig config) throws Exception {
        stop();
        airPlayConfig = config;
        rebuildServers();
        start();
    }

    public void disconnectActiveSession() {
        controlServer.disconnectActiveSession();
    }

    public ServerState state() {
        return state;
    }

    public Optional<SessionInfo> activeSession() {
        return controlServer.activeSession();
    }

    public void addListener(AirPlayServerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AirPlayServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void close() {
        stop();
    }

    private void rebuildServers() {
        airPlayBonjour = new AirPlayBonjour(airPlayConfig.getServerName(), airPlayConfig.isMirrorOnly());
        controlServer = new ControlServer(airPlayConfig, airPlayConsumer, new AirPlayServerListener() {
            @Override
            public void onSessionChanged(SessionInfo session, SessionState state) {
                listeners.forEach(listener -> listener.onSessionChanged(session, state));
            }

            @Override
            public void onError(Throwable error) {
                listeners.forEach(listener -> listener.onError(error));
            }
        });
    }

    private void transition(ServerState nextState) {
        state = nextState;
        listeners.forEach(listener -> listener.onServerStateChanged(nextState));
    }
}
