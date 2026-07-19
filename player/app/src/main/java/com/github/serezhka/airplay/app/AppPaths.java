package com.github.serezhka.airplay.app;

import java.nio.file.Path;

public final class AppPaths {

    private static final String APP_DIRECTORY = "AirPlay Receiver";

    private AppPaths() {
    }

    public static Path settingsDirectory() {
        return environmentPath("APPDATA", Path.of(System.getProperty("user.home")))
                .resolve(APP_DIRECTORY);
    }

    public static Path logsDirectory() {
        return environmentPath("LOCALAPPDATA", Path.of(System.getProperty("user.home")))
                .resolve(APP_DIRECTORY)
                .resolve("logs");
    }

    private static Path environmentPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}
