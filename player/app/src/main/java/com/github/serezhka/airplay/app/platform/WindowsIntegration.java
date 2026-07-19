package com.github.serezhka.airplay.app.platform;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public final class WindowsIntegration {

    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String PERSONALIZE_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String VALUE_NAME = "AirPlay Receiver";

    private WindowsIntegration() {
    }

    public static void setStartWithWindows(boolean enabled) {
        if (!Platform.isWindows()) {
            return;
        }
        if (enabled) {
            String executablePath = System.getProperty("jpackage.app-path");
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("Start with Windows is available in the packaged application");
            }
            String executable = '"' + executablePath + '"';
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, executable);
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
        }
    }

    public static boolean isSystemDarkTheme() {
        if (!Platform.isWindows()) {
            return false;
        }
        try {
            return Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, "AppsUseLightTheme") == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void openFirewallSettings() {
        browse("windowsdefender://network/");
    }

    public static void openDirectory(Path directory) {
        try {
            java.nio.file.Files.createDirectories(directory);
            Desktop.getDesktop().open(directory.toFile());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to open directory", error);
        }
    }

    private static void browse(String uri) {
        try {
            Desktop.getDesktop().browse(URI.create(uri));
        } catch (IOException error) {
            throw new IllegalStateException("Unable to open Windows settings", error);
        }
    }
}
