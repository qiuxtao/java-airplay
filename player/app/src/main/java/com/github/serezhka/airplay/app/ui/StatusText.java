package com.github.serezhka.airplay.app.ui;

import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.server.ServerState;

import java.util.Locale;

final class StatusText {

    private StatusText() {
    }

    static String resolve(I18n i18n, ServerState state, boolean playing) {
        return playing
                ? i18n.text("state.playing")
                : i18n.text("state." + state.name().toLowerCase(Locale.ROOT));
    }
}
