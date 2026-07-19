package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.server.ServerState;
import com.github.serezhka.airplay.server.SessionInfo;

public interface ReceiverView {

    void onServerState(ServerState state);

    void onSessionStarted(SessionInfo session);

    void onSessionStopped();

    void onVideoFormat(int width, int height);

    void onError(String message, Throwable error);

    void onSettingsChanged(AppSettings settings);
}
