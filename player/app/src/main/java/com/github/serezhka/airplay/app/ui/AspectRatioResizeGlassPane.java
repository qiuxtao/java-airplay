package com.github.serezhka.airplay.app.ui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Handles all interactive playback resizing in Swing logical pixels. */
final class AspectRatioResizeGlassPane extends JComponent {

    private static final int CORNER_SIZE = 14;

    private final JFrame window;
    private final JComponent videoArea;
    private final DoubleSupplier aspectSupplier;
    private final BooleanSupplier enabledSupplier;
    private Corner activeCorner;
    private Rectangle startBounds;

    AspectRatioResizeGlassPane(JFrame window,
                               JComponent videoArea,
                               DoubleSupplier aspectSupplier,
                               BooleanSupplier enabledSupplier) {
        this.window = window;
        this.videoArea = videoArea;
        this.aspectSupplier = aspectSupplier;
        this.enabledSupplier = enabledSupplier;
        setOpaque(false);
        ResizeMouseHandler mouseHandler = new ResizeMouseHandler();
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    @Override
    public boolean contains(int x, int y) {
        return activeCorner != null || cornerAt(x, y) != null;
    }

    private boolean resizeEnabled() {
        return enabledSupplier.getAsBoolean() && (window.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0;
    }

    private Corner cornerAt(int x, int y) {
        if (!resizeEnabled()) {
            return null;
        }
        boolean left = x >= 0 && x < CORNER_SIZE;
        boolean right = x >= getWidth() - CORNER_SIZE && x < getWidth();
        boolean top = y >= 0 && y < CORNER_SIZE;
        boolean bottom = y >= getHeight() - CORNER_SIZE && y < getHeight();
        if (left && top) {
            return Corner.TOP_LEFT;
        }
        if (right && top) {
            return Corner.TOP_RIGHT;
        }
        if (left && bottom) {
            return Corner.BOTTOM_LEFT;
        }
        if (right && bottom) {
            return Corner.BOTTOM_RIGHT;
        }
        return null;
    }

    private int chromeWidth() {
        return Math.max(0, window.getWidth() - videoArea.getWidth());
    }

    private int chromeHeight() {
        return Math.max(0, window.getHeight() - videoArea.getHeight());
    }

    static Rectangle resizeBounds(Rectangle initial,
                                  Point pointer,
                                  Corner corner,
                                  int chromeWidth,
                                  int chromeHeight,
                                  double aspect,
                                  Dimension minimumOuterSize) {
        int anchorX = corner.left ? initial.x + initial.width : initial.x;
        int anchorY = corner.top ? initial.y + initial.height : initial.y;
        int proposedOuterWidth = corner.left ? anchorX - pointer.x : pointer.x - anchorX;
        int proposedOuterHeight = corner.top ? anchorY - pointer.y : pointer.y - anchorY;
        int proposedContentWidth = Math.max(1, proposedOuterWidth - chromeWidth);
        int proposedContentHeight = Math.max(1, proposedOuterHeight - chromeHeight);
        int minimumContentWidth = Math.max(1, minimumOuterSize.width - chromeWidth);
        int minimumContentHeight = Math.max(1, minimumOuterSize.height - chromeHeight);
        Dimension content = projectToAspect(
                proposedContentWidth, proposedContentHeight, aspect, minimumContentWidth, minimumContentHeight);
        int outerWidth = content.width + chromeWidth;
        int outerHeight = content.height + chromeHeight;
        int x = corner.left ? anchorX - outerWidth : anchorX;
        int y = corner.top ? anchorY - outerHeight : anchorY;
        return new Rectangle(x, y, outerWidth, outerHeight);
    }

    static Dimension projectToAspect(int proposedWidth,
                                     int proposedHeight,
                                     double aspect,
                                     int minimumWidth,
                                     int minimumHeight) {
        double projectedHeight = (aspect * proposedWidth + proposedHeight) / (aspect * aspect + 1d);
        double projectedWidth = projectedHeight * aspect;
        double minimumScale = Math.max(
                minimumWidth / Math.max(1d, projectedWidth),
                minimumHeight / Math.max(1d, projectedHeight));
        if (minimumScale > 1d) {
            projectedWidth *= minimumScale;
            projectedHeight *= minimumScale;
        }
        int height = Math.max(minimumHeight, Math.max(1, (int) Math.round(projectedHeight)));
        height = Math.max(height, (int) Math.ceil(minimumWidth / aspect));
        int width = Math.max(1, (int) Math.round(height * aspect));
        return new Dimension(width, height);
    }

    enum Corner {
        TOP_LEFT(true, true, Cursor.NW_RESIZE_CURSOR),
        TOP_RIGHT(false, true, Cursor.NE_RESIZE_CURSOR),
        BOTTOM_LEFT(true, false, Cursor.SW_RESIZE_CURSOR),
        BOTTOM_RIGHT(false, false, Cursor.SE_RESIZE_CURSOR);

        private final boolean left;
        private final boolean top;
        private final int cursor;

        Corner(boolean left, boolean top, int cursor) {
            this.left = left;
            this.top = top;
            this.cursor = cursor;
        }
    }

    private final class ResizeMouseHandler extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent event) {
            activeCorner = cornerAt(event.getX(), event.getY());
            if (activeCorner != null) {
                startBounds = window.getBounds();
                setCursor(Cursor.getPredefinedCursor(activeCorner.cursor));
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (activeCorner == null || startBounds == null) {
                return;
            }
            Rectangle bounds = resizeBounds(
                    startBounds,
                    event.getLocationOnScreen(),
                    activeCorner,
                    chromeWidth(),
                    chromeHeight(),
                    aspectSupplier.getAsDouble(),
                    window.getMinimumSize());
            window.setBounds(bounds);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            activeCorner = null;
            startBounds = null;
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            Corner corner = cornerAt(event.getX(), event.getY());
            setCursor(corner == null
                    ? Cursor.getDefaultCursor()
                    : Cursor.getPredefinedCursor(corner.cursor));
        }
    }
}
