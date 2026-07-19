package com.github.serezhka.airplay.app.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.serezhka.airplay.app.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;

public final class SettingsStore {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path settingsFile;
    private final Path legacySettingsFile;

    public SettingsStore() {
        this(AppPaths.settingsDirectory().resolve("settings.json"), Path.of("application.properties"));
    }

    SettingsStore(Path settingsFile) {
        this(settingsFile, Path.of("application.properties"));
    }

    SettingsStore(Path settingsFile, Path legacySettingsFile) {
        this.settingsFile = settingsFile;
        this.legacySettingsFile = legacySettingsFile;
    }

    public AppSettings load() {
        if (!Files.exists(settingsFile)) {
            AppSettings migrated = migrateLegacySettings();
            save(migrated);
            return migrated;
        }
        try {
            return mapper.readValue(settingsFile.toFile(), AppSettings.class).normalized();
        } catch (Exception error) {
            backupCorruptSettings();
            AppSettings defaults = AppSettings.defaults();
            save(defaults);
            return defaults;
        }
    }

    public synchronized void save(AppSettings settings) {
        try {
            Files.createDirectories(settingsFile.getParent());
            Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), settings.normalized());
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to save settings", error);
        }
    }

    public Path settingsFile() {
        return settingsFile;
    }

    private AppSettings migrateLegacySettings() {
        if (!Files.isRegularFile(legacySettingsFile)) {
            return AppSettings.defaults();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(legacySettingsFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            AppSettings defaults = AppSettings.defaults();
            return new AppSettings(
                    properties.getProperty("airplay.serverName", defaults.receiverName()),
                    AppSettings.DisplayMode.CUSTOM,
                    integer(properties, "airplay.width", defaults.customWidth()),
                    integer(properties, "airplay.height", defaults.customHeight()),
                    integer(properties, "airplay.fps", defaults.maxFps()),
                    defaults.theme(), defaults.language(), defaults.startWithWindows(),
                    defaults.bringToFront(), defaults.closeToTray(), defaults.receiverEnabled(),
                    defaults.volume()).normalized();
        } catch (IOException ignored) {
            return AppSettings.defaults();
        }
    }

    private int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void backupCorruptSettings() {
        try {
            Files.move(settingsFile, settingsFile.resolveSibling(
                    "settings.corrupt-" + Instant.now().toEpochMilli() + ".json"));
        } catch (IOException ignored) {
            // Defaults can still be used when the damaged file cannot be moved.
        }
    }
}
