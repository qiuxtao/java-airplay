package com.github.serezhka.airplay.app.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.serezhka.airplay.app.platform.WindowsIntegration;
import com.github.serezhka.airplay.app.settings.AppSettings;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ThemeManager implements AutoCloseable {

    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "windows-theme-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private volatile AppSettings.ThemeMode mode;
    private volatile boolean dark;

    public ThemeManager(AppSettings.ThemeMode mode) {
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.useRoundedPopupBorder", "true");
        FlatLaf.registerCustomDefaultsSource("themes");
        apply(mode);
        watcher.scheduleWithFixedDelay(this::pollSystemTheme, 2, 2, TimeUnit.SECONDS);
    }

    public void apply(AppSettings.ThemeMode nextMode) {
        mode = nextMode;
        boolean nextDark = switch (nextMode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> WindowsIntegration.isSystemDarkTheme();
        };
        applyLookAndFeel(nextDark);
    }

    public boolean isDark() {
        return dark;
    }

    @Override
    public void close() {
        watcher.shutdownNow();
    }

    private void pollSystemTheme() {
        if (mode == AppSettings.ThemeMode.SYSTEM) {
            boolean systemDark = WindowsIntegration.isSystemDarkTheme();
            if (systemDark != dark) {
                SwingUtilities.invokeLater(() -> applyLookAndFeel(systemDark));
            }
        }
    }

    private void applyLookAndFeel(boolean useDark) {
        dark = useDark;
        if (useDark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.setUseNativeWindowDecorations(true);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
    }
}
