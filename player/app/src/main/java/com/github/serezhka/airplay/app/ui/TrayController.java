package com.github.serezhka.airplay.app.ui;

import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.server.ServerState;
import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

@Slf4j
final class TrayController implements AutoCloseable {

    private final MainFrame frame;
    private final I18n i18n;
    private final TrayIcon trayIcon;
    private final MenuItem stateItem = new MenuItem();

    TrayController(MainFrame frame, I18n i18n) {
        this.frame = frame;
        this.i18n = i18n;
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            return;
        }

        Font menuFont = menuFont();
        PopupMenu menu = new PopupMenu();
        menu.setFont(menuFont);
        MenuItem show = new MenuItem(i18n.text("tray.show"));
        show.setFont(menuFont);
        show.addActionListener(event -> frame.restoreAndShow());
        menu.add(show);
        stateItem.setFont(menuFont);
        stateItem.setEnabled(false);
        menu.add(stateItem);
        menu.addSeparator();
        MenuItem exit = new MenuItem(i18n.text("tray.exit"));
        exit.setFont(menuFont);
        exit.addActionListener(event -> frame.exitApplication());
        menu.add(exit);

        TrayIcon createdIcon = new TrayIcon(createIcon(), "AirPlay Receiver", menu);
        createdIcon.setImageAutoSize(true);
        createdIcon.addActionListener(event -> frame.restoreAndShow());
        TrayIcon installedIcon = null;
        try {
            SystemTray.getSystemTray().add(createdIcon);
            installedIcon = createdIcon;
        } catch (Exception error) {
            log.warn("System tray is unavailable; closing the window will exit", error);
        }
        trayIcon = installedIcon;
        update(ServerState.STOPPED);
    }

    boolean available() {
        return trayIcon != null;
    }

    void update(ServerState state) {
        if (trayIcon == null) {
            return;
        }
        stateItem.setLabel(i18n.text("tray.status", i18n.text("state." + state.name().toLowerCase())));
    }

    void showError(String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(i18n.text("error.title"), message, TrayIcon.MessageType.ERROR);
        }
    }

    @Override
    public void close() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    private BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(83, 109, 254));
        graphics.fillRoundRect(2, 3, 28, 21, 6, 6);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        graphics.drawString("A", 10, 20);
        graphics.fillRoundRect(11, 27, 10, 2, 2, 2);
        graphics.dispose();
        return image;
    }

    private Font menuFont() {
        String sample = i18n.text("tray.show") + i18n.text("tray.exit") + i18n.text("tray.status", "");
        String[] families = {"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", Font.SANS_SERIF};
        for (String family : families) {
            Font candidate = new Font(family, Font.PLAIN, 13);
            if (candidate.canDisplayUpTo(sample) == -1) {
                return candidate;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }
}
