package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.AirPlayServerListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlServerLifecycleTest {

    @Test
    void canStartStopAndStartAgain() throws Exception {
        ControlServer server = new ControlServer(new AirPlayConfig(), new NoOpConsumer(),
                new AirPlayServerListener() {
                });
        try {
            server.start();
            assertTrue(server.getPort() > 0);
            server.stop();
            assertEquals(0, server.getPort());
            server.start();
            assertTrue(server.getPort() > 0);
        } finally {
            server.stop();
        }
    }

    private static final class NoOpConsumer implements AirPlayConsumer {
        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideo(byte[] bytes) { }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }
}
