package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.settings.AppSettings;
import com.github.serezhka.airplay.app.settings.SettingsStore;
import com.github.serezhka.airplay.app.theme.ThemeManager;
import com.github.serezhka.airplay.app.ui.MainFrame;
import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.player.gstreamer.GstRuntime;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.Arrays;

public final class PlayerApp {

    private PlayerApp() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppPaths.logsDirectory());
        System.setProperty("APP_LOG_DIR", AppPaths.logsDirectory().toString());
        System.setProperty("apple.awt.application.name", AppVersion.productName());

        if (Arrays.asList(args).contains("--self-test")) {
            runSelfTest();
            return;
        }

        SettingsStore settingsStore = new SettingsStore();
        AppSettings settings = settingsStore.load();
        ThemeManager themeManager = new ThemeManager(settings.theme());
        I18n i18n = new I18n(settings.language());

        try {
            ReceiverController controller = new ReceiverController(settingsStore, settings, themeManager);
            SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame(controller, i18n);
                controller.attachView(frame);
                frame.setVisible(true);
                controller.start();
            });
        } catch (RuntimeException | LinkageError error) {
            LoggerFactory.getLogger(PlayerApp.class).error("Media runtime initialization failed", error);
            themeManager.close();
            String details = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    i18n.text("error.mediaRuntime", details),
                    AppVersion.productName(), JOptionPane.ERROR_MESSAGE));
            System.exit(1);
        }
    }

    private static void runSelfTest() {
        GstRuntime.RuntimeCheck runtime = GstRuntime.verifyInstallation();
        if (Runtime.version().feature() < 21) {
            throw new IllegalStateException("Java 21 or later is required");
        }
        if (!runtime.available()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), runtime.problems()));
        }
        try (GstPlayer ignored = new GstPlayer()) {
            // Construct all production pipelines so missing properties or plug-ins fail the smoke test.
        }
        System.out.println("AirPlay Receiver self-test passed. GStreamer: " + runtime.root());
    }
}
