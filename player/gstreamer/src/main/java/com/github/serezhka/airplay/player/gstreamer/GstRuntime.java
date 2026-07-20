package com.github.serezhka.airplay.player.gstreamer;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.glib.GLib;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class GstRuntime {

    private static final Version REQUIRED_WINDOWS_VERSION = new Version(1, 28, 5, 0);
    private static final List<String> REQUIRED_ELEMENTS = List.of(
            "appsrc", "appsink", "h264parse", "avdec_h264", "videoconvert",
            "avdec_alac", "avdec_aac", "audioconvert", "audioresample", "volume", "autoaudiosink");

    private static volatile Path runtimeRoot;

    private GstRuntime() {
    }

    public static synchronized RuntimeCheck configure() {
        if (!Platform.isWindows()) {
            return new RuntimeCheck(true, null, List.of());
        }

        Path root = locateWindowsRuntime();
        if (root == null) {
            return new RuntimeCheck(false, null,
                    List.of("GStreamer runtime was not found. Reinstall AirPlay Receiver."));
        }

        Path bin = root.resolve("bin");
        Path plugins = root.resolve("lib").resolve("gstreamer-1.0");
        String path = bin + File.pathSeparator + System.getenv().getOrDefault("PATH", "");
        Kernel32.INSTANCE.SetEnvironmentVariable("PATH", path);
        Kernel32.INSTANCE.SetEnvironmentVariable("GST_PLUGIN_PATH", plugins.toString());
        GLib.setEnv("GST_PLUGIN_PATH", plugins.toString(), true);
        System.setProperty("jna.library.path", bin.toString());
        runtimeRoot = root;

        List<String> missing = Stream.of(bin, plugins)
                .filter(pathEntry -> !Files.isDirectory(pathEntry))
                .map(pathEntry -> "Missing GStreamer directory: " + pathEntry)
                .toList();
        return new RuntimeCheck(missing.isEmpty(), root, missing);
    }

    public static Path runtimeRoot() {
        return runtimeRoot;
    }

    public static synchronized RuntimeCheck verifyInstallation() {
        RuntimeCheck configured = configure();
        if (!configured.available()) {
            return configured;
        }

        List<String> problems = new ArrayList<>();
        try {
            Gst.init(Version.of(1, 20), "AirPlay Receiver self-test");
            Version actual = Gst.getVersion();
            if (Platform.isWindows() && !actual.checkSatisfies(REQUIRED_WINDOWS_VERSION)) {
                problems.add("GStreamer 1.28.5 or later is required; found " + actual);
            }
            for (String elementName : REQUIRED_ELEMENTS) {
                try (ElementFactory factory = ElementFactory.find(elementName)) {
                    if (factory == null) {
                        problems.add("Missing GStreamer element: " + elementName);
                    }
                }
            }
            if (Platform.isWindows()) {
                try (ElementFactory factory = ElementFactory.find("wasapi2sink")) {
                    if (factory == null) {
                        problems.add("Missing GStreamer element: wasapi2sink");
                    }
                }
            }
        } catch (RuntimeException | LinkageError error) {
            problems.add("GStreamer could not initialize: " + error.getMessage());
        }
        return new RuntimeCheck(problems.isEmpty(), configured.root(), List.copyOf(problems));
    }

    static Path locateWindowsRuntime() {
        String override = System.getProperty("gstreamer.path");
        if (override != null && !override.isBlank()) {
            return normalizeRoot(Path.of(override));
        }

        Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath();
        Path packaged = javaHome.getParent() == null ? null : javaHome.getParent().resolve("gstreamer");
        if (packaged != null && Files.isDirectory(packaged)) {
            return packaged;
        }

        return Stream.of("GSTREAMER_1_0_ROOT_MSVC_X86_64",
                        "GSTREAMER_1_0_ROOT_MINGW_X86_64",
                        "GSTREAMER_1_0_ROOT_X86_64")
                .map(System::getenv)
                .filter(value -> value != null && !value.isBlank())
                .map(Path::of)
                .map(GstRuntime::normalizeRoot)
                .findFirst()
                .orElse(null);
    }

    private static Path normalizeRoot(Path candidate) {
        Path absolute = candidate.toAbsolutePath().normalize();
        return absolute.getFileName() != null && absolute.getFileName().toString().equalsIgnoreCase("bin")
                ? absolute.getParent()
                : absolute;
    }

    public record RuntimeCheck(boolean available, Path root, List<String> problems) {
    }
}
