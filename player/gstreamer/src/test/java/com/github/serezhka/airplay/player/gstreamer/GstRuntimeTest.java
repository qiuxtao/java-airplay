package com.github.serezhka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GstRuntimeTest {

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsBinDirectoryAsExplicitRuntimePath() {
        String previous = System.getProperty("gstreamer.path");
        try {
            Path bin = tempDirectory.resolve("bin");
            System.setProperty("gstreamer.path", bin.toString());

            assertEquals(tempDirectory.toAbsolutePath().normalize(), GstRuntime.locateWindowsRuntime());
        } finally {
            if (previous == null) {
                System.clearProperty("gstreamer.path");
            } else {
                System.setProperty("gstreamer.path", previous);
            }
        }
    }

    @Test
    void clampsLocalVolume() {
        assertEquals(0, GstPlayer.normalizeVolume(-1));
        assertEquals(0.42, GstPlayer.normalizeVolume(0.42));
        assertEquals(1, GstPlayer.normalizeVolume(2));
    }
}
