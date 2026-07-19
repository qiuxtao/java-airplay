package com.github.serezhka.airplay.player.gstreamer;

public interface GstPlayerListener {

    default void onVideoFormatChanged(int width, int height) {
    }

    default void onPlaybackError(String message, Throwable error) {
    }

    default void onEndOfStream() {
    }
}
