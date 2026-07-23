package com.github.serezhka.airplay.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads every application identity icon from the same selected master artwork. */
public final class AppIcons {

    private static final Logger LOG = LoggerFactory.getLogger(AppIcons.class);
    private static final int[] WINDOW_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final int[] TRAY_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64};
    private static final Map<Integer, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private AppIcons() {
    }

    public static ImageIcon icon(int size) {
        return new ImageIcon(image(size));
    }

    public static BufferedImage image(int size) {
        return CACHE.computeIfAbsent(size, AppIcons::load);
    }

    public static List<Image> windowIcons() {
        return Arrays.stream(WINDOW_ICON_SIZES)
                .mapToObj(AppIcons::image)
                .map(Image.class::cast)
                .toList();
    }

    public static Image trayIcon() {
        Image[] variants = Arrays.stream(TRAY_ICON_SIZES)
                .mapToObj(AppIcons::image)
                .toArray(Image[]::new);
        return new BaseMultiResolutionImage(variants);
    }

    public static void installTaskbarIcon() {
        if (GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) {
            return;
        }
        try {
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(image(256));
            }
        } catch (SecurityException | UnsupportedOperationException error) {
            LOG.debug("The operating system did not accept the taskbar icon", error);
        }
    }

    private static BufferedImage load(int size) {
        String path = "/icons/app-icon-" + size + ".png";
        try (InputStream input = AppIcons.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing application icon resource: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("Unsupported application icon resource: " + path);
            }
            if (image.getWidth() != size || image.getHeight() != size) {
                throw new IllegalStateException("Unexpected application icon dimensions for " + path
                        + ": " + image.getWidth() + "x" + image.getHeight());
            }
            return image;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load application icon resource: " + path, error);
        }
    }
}
