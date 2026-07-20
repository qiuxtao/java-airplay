package com.github.serezhka.airplay.app.settings;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record AppSettings(String receiverName,
                          DisplayMode displayMode,
                          int customWidth,
                          int customHeight,
                          int maxFps,
                          ThemeMode theme,
                          LanguageMode language,
                          boolean startWithWindows,
                          boolean bringToFront,
                          boolean closeToTray,
                          boolean receiverEnabled,
                          double volume) {

    public static AppSettings defaults() {
        return new AppSettings(defaultReceiverName(), DisplayMode.PRIMARY_DISPLAY,
                1920, 1080, 60, ThemeMode.SYSTEM, LanguageMode.SYSTEM,
                false, true, true, true, 1.0);
    }

    public AppSettings normalized() {
        String name = receiverName == null || receiverName.isBlank()
                ? defaultReceiverName()
                : receiverName.trim();
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            name = defaultReceiverName();
        }
        return new AppSettings(name,
                displayMode == null ? DisplayMode.PRIMARY_DISPLAY : displayMode,
                clamp(customWidth, 640, 7680),
                clamp(customHeight, 480, 4320),
                clamp(maxFps, 15, 60),
                theme == null ? ThemeMode.SYSTEM : theme,
                language == null ? LanguageMode.SYSTEM : language,
                startWithWindows, bringToFront, closeToTray, true,
                Math.max(0, Math.min(1, volume)));
    }

    public AppSettings withVolume(double newVolume) {
        return new AppSettings(receiverName, displayMode, customWidth, customHeight, maxFps,
                theme, language, startWithWindows, bringToFront, closeToTray, receiverEnabled, newVolume);
    }

    private static String defaultReceiverName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            return "AirPlay Receiver";
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum DisplayMode {
        PRIMARY_DISPLAY,
        HD_720,
        FULL_HD_1080,
        CUSTOM
    }

    public enum ThemeMode {
        SYSTEM,
        LIGHT,
        DARK
    }

    public enum LanguageMode {
        SYSTEM,
        ZH_CN,
        EN
    }
}
