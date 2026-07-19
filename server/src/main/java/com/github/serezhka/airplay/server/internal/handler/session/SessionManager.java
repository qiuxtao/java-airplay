package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.MediaStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.AirPlayServerListener;
import com.github.serezhka.airplay.server.SessionInfo;
import com.github.serezhka.airplay.server.SessionState;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SessionManager {

    private final Map<String, Session> sessions = new HashMap<>();
    private final AirPlayConsumer consumer;
    private final AirPlayServerListener listener;
    private String activeSessionId;

    public SessionManager(AirPlayConsumer consumer, AirPlayServerListener listener) {
        this.consumer = consumer;
        this.listener = listener;
    }

    public synchronized Session getSession(String requestedId,
                                           InetSocketAddress remoteAddress,
                                           Channel channel) {
        String sessionId = requestedId == null || requestedId.isBlank()
                ? channel.id().asLongText()
                : requestedId;
        Session session = sessions.computeIfAbsent(sessionId, id -> {
            Session created = new Session(id, remoteAddress);
            listener.onSessionChanged(created.info(), SessionState.CONNECTING);
            return created;
        });
        session.addChannel(channel);
        return session;
    }

    public synchronized boolean claim(Session session) {
        if (activeSessionId == null || activeSessionId.equals(session.getId())) {
            activeSessionId = session.getId();
            return true;
        }
        return false;
    }

    public synchronized void streamStarted(Session session, MediaStreamInfo.StreamType streamType) {
        session.addStream(streamType);
        listener.onSessionChanged(session.info(), SessionState.PLAYING);
    }

    public synchronized void streamStopped(Session session, MediaStreamInfo.StreamType streamType) {
        if (streamType == MediaStreamInfo.StreamType.AUDIO) {
            consumer.onAudioSrcDisconnect();
        } else {
            consumer.onVideoSrcDisconnect();
        }
        session.removeStream(streamType);
        if (!session.hasStreams()) {
            remove(session.getId(), false);
        } else {
            listener.onSessionChanged(session.info(), SessionState.PLAYING);
        }
    }

    public synchronized void channelClosed(Channel channel) {
        sessions.values().stream()
                .filter(session -> session.getChannels().remove(channel))
                .filter(session -> session.getChannels().isEmpty())
                .map(Session::getId)
                .toList()
                .forEach(id -> remove(id, false));
    }

    public synchronized Optional<SessionInfo> activeSession() {
        return Optional.ofNullable(activeSessionId)
                .map(sessions::get)
                .map(Session::info);
    }

    public synchronized void disconnectActiveSession() {
        if (activeSessionId != null) {
            remove(activeSessionId, true);
        }
    }

    public synchronized void disconnectSession(Session session) {
        remove(session.getId(), false);
    }

    public synchronized void closeAll() {
        for (String id : sessions.keySet().toArray(String[]::new)) {
            remove(id, true);
        }
    }

    public void notifyError(Throwable error) {
        listener.onError(error);
    }

    private void remove(String sessionId, boolean closeChannels) {
        Session removed = sessions.remove(sessionId);
        if (removed == null) {
            return;
        }
        SessionInfo info = removed.info();
        if (sessionId.equals(activeSessionId)) {
            activeSessionId = null;
        }
        removed.stop(consumer, closeChannels);
        listener.onSessionChanged(info, SessionState.STOPPED);
    }
}
