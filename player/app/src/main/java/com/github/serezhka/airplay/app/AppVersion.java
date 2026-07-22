package com.github.serezhka.airplay.app;

/** Exposes the package version embedded by Gradle to the desktop UI. */
public final class AppVersion {

    private static final String DEVELOPMENT_VERSION = "development";

    private AppVersion() {
    }

    public static String current() {
        String version = AppVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
    }

    public static String display() {
        return "v" + current();
    }
}
