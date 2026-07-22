package com.github.serezhka.airplay.app.ui;

import com.github.serezhka.airplay.app.i18n.I18n;
import com.github.serezhka.airplay.app.AppVersion;
import com.github.serezhka.airplay.server.ServerState;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

@Slf4j
final class TrayController implements AutoCloseable {

    private final MainFrame frame;
    private final I18n i18n;
    private final TrayIcon trayIcon;
    private final JLabel stateLabel = new JLabel();
    private final JWindow popupWindow;

    TrayController(MainFrame frame, I18n i18n) {
        this.frame = frame;
        this.i18n = i18n;
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            popupWindow = null;
            return;
        }

        popupWindow = buildPopupWindow();
        TrayIcon createdIcon = new TrayIcon(createIcon(), AppVersion.productName());
        createdIcon.setImageAutoSize(true);
        createdIcon.addActionListener(event -> frame.restoreAndShow());
        createdIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    Point anchor = pointerLocation(event);
                    SwingUtilities.invokeLater(() -> showPopup(anchor.x, anchor.y));
                }
            }
        });

        TrayIcon installedIcon = null;
        try {
            SystemTray.getSystemTray().add(createdIcon);
            installedIcon = createdIcon;
        } catch (Exception error) {
            popupWindow.dispose();
            log.warn("System tray is unavailable; closing the window will exit", error);
        }
        trayIcon = installedIcon;
        update(ServerState.STOPPED, false);
    }

    boolean available() {
        return trayIcon != null;
    }

    void update(ServerState state, boolean playing) {
        if (trayIcon == null) {
            return;
        }
        String stateText = StatusText.resolve(i18n, state, playing);
        stateLabel.setText(i18n.text("tray.status", stateText));
    }

    void showError(String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(i18n.text("error.title"), message, TrayIcon.MessageType.ERROR);
        }
    }

    @Override
    public void close() {
        if (trayIcon != null) {
            popupWindow.setVisible(false);
            popupWindow.dispose();
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    private JWindow buildPopupWindow() {
        JWindow popup = new JWindow(frame);
        popup.setAlwaysOnTop(true);
        popup.setFocusableWindowState(true);
        popup.setBackground(new Color(0, 0, 0, 0));

        JPanel menu = new TrayMenuPanel();
        menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));
        menu.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton show = menuButton(i18n.text("tray.show"));
        show.addActionListener(event -> {
            popup.setVisible(false);
            frame.restoreAndShow();
        });
        menu.add(show);
        menu.add(Box.createVerticalStrut(3));

        stateLabel.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        stateLabel.putClientProperty("FlatLaf.styleClass", "small");
        stateLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        menu.add(stateLabel);
        menu.add(Box.createVerticalStrut(6));

        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        menu.add(separator);
        menu.add(Box.createVerticalStrut(5));

        JButton exit = menuButton(i18n.text("tray.exit"));
        exit.addActionListener(event -> {
            popup.setVisible(false);
            frame.exitApplication();
        });
        menu.add(exit);

        popup.setContentPane(menu);
        popup.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                popup.setVisible(false);
            }
        });
        return popup;
    }

    private JButton menuButton(String text) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        button.putClientProperty("FlatLaf.style",
                "borderWidth: 0; focusWidth: 0; arc: 10; margin: 8,12,8,12;"
                        + "hoverBackground: fade(@accentColor,16%); pressedBackground: fade(@accentColor,24%)");
        return button;
    }

    private void showPopup(int screenX, int screenY) {
        if (trayIcon == null) {
            return;
        }
        popupWindow.pack();
        Dimension size = popupWindow.getSize();
        Rectangle usable = usableScreenBounds(screenX, screenY);
        int x = Math.max(usable.x + 8, Math.min(screenX + 8,
                usable.x + usable.width - size.width - 8));
        int y = screenY - size.height - 8;
        y = Math.max(usable.y + 8, Math.min(y, usable.y + usable.height - size.height - 8));
        popupWindow.setLocation(x, y);
        try {
            popupWindow.setShape(new RoundRectangle2D.Double(0, 0, size.width, size.height, 18, 18));
        } catch (UnsupportedOperationException ignored) {
            // The Swing menu still works on graphics drivers without shaped-window support.
        }
        popupWindow.setVisible(true);
        popupWindow.toFront();
        popupWindow.requestFocus();
    }

    private Point pointerLocation(MouseEvent event) {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer != null) {
            return pointer.getLocation();
        }
        return new Point(event.getX(), event.getY());
    }

    private Rectangle usableScreenBounds(int x, int y) {
        GraphicsConfiguration selected = null;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().contains(x, y)) {
                selected = configuration;
                break;
            }
        }
        if (selected == null) {
            selected = frame.getGraphicsConfiguration();
        }
        if (selected == null) {
            selected = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle bounds = selected.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(selected);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
    }

    private BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(83, 109, 254));
        graphics.fillRoundRect(2, 3, 28, 21, 6, 6);
        graphics.setColor(Color.WHITE);
        graphics.drawArc(7, 11, 17, 17, 35, 110);
        graphics.drawArc(10, 16, 11, 11, 35, 110);
        graphics.fillOval(14, 24, 3, 3);
        graphics.setColor(new Color(83, 109, 254));
        graphics.fillRoundRect(11, 27, 10, 2, 2, 2);
        graphics.dispose();
        return image;
    }

    private static final class TrayMenuPanel extends JPanel {

        TrayMenuPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = UIManager.getColor("Panel.background");
            Color fill = base == null ? new Color(24, 29, 47) : base;
            g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 248));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            Color border = UIManager.getColor("Component.borderColor");
            g.setColor(border == null ? new Color(100, 115, 170, 80) : border);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
