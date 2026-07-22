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

/** Provides proportional resizing from the playback window's visible edges and corners. */
final class AspectRatioResizeGlassPane extends JComponent {

    private static final int RESIZE_BORDER = 7;

    private final JFrame window;
    private final DoubleSupplier aspectSupplier;
    private final BooleanSupplier enabledSupplier;
    private Handle activeHandle;
    private Rectangle startBounds;
    private double dragAspect;

    AspectRatioResizeGlassPane(JFrame window,
                               DoubleSupplier aspectSupplier,
                               BooleanSupplier enabledSupplier) {
        this.window = window;
        this.aspectSupplier = aspectSupplier;
        this.enabledSupplier = enabledSupplier;
        setOpaque(false);
        ResizeMouseHandler mouseHandler = new ResizeMouseHandler();
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    @Override
    public boolean contains(int x, int y) {
        return activeHandle != null || handleAt(x, y) != null;
    }

    private boolean resizeEnabled() {
        return enabledSupplier.getAsBoolean() && (window.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0;
    }

    private Handle handleAt(int x, int y) {
        if (!resizeEnabled()) {
            return null;
        }
        return handleAt(x, y, getWidth(), getHeight());
    }

    static Handle handleAt(int x, int y, int width, int height) {
        boolean left = x >= 0 && x < RESIZE_BORDER;
        boolean right = x >= width - RESIZE_BORDER && x < width;
        boolean top = y >= 0 && y < RESIZE_BORDER;
        boolean bottom = y >= height - RESIZE_BORDER && y < height;
        if (left && top) {
            return Handle.TOP_LEFT;
        }
        if (right && top) {
            return Handle.TOP_RIGHT;
        }
        if (left && bottom) {
            return Handle.BOTTOM_LEFT;
        }
        if (right && bottom) {
            return Handle.BOTTOM_RIGHT;
        }
        if (left) {
            return Handle.LEFT;
        }
        if (right) {
            return Handle.RIGHT;
        }
        if (top) {
            return Handle.TOP;
        }
        if (bottom) {
            return Handle.BOTTOM;
        }
        return null;
    }

    static Rectangle resizeBounds(Rectangle initial,
                                  Point pointer,
                                  Handle handle,
                                  double aspect,
                                  Dimension minimumOuterSize) {
        Dimension outerSize;
        if (handle.isCorner()) {
            int anchorX = handle.left() ? initial.x + initial.width : initial.x;
            int anchorY = handle.top() ? initial.y + initial.height : initial.y;
            int proposedOuterWidth = handle.left() ? anchorX - pointer.x : pointer.x - anchorX;
            int proposedOuterHeight = handle.top() ? anchorY - pointer.y : pointer.y - anchorY;
            outerSize = projectToAspect(
                    Math.max(1, proposedOuterWidth),
                    Math.max(1, proposedOuterHeight),
                    aspect, minimumOuterSize.width, minimumOuterSize.height);
            return anchoredCornerBounds(anchorX, anchorY, handle, outerSize);
        }
        if (handle.horizontalDirection != 0) {
            int anchorX = handle.left() ? initial.x + initial.width : initial.x;
            int proposedOuterWidth = handle.left() ? anchorX - pointer.x : pointer.x - anchorX;
            outerSize = sizeFromWidth(
                    proposedOuterWidth, aspect, minimumOuterSize.width, minimumOuterSize.height);
            return new Rectangle(
                    handle.left() ? anchorX - outerSize.width : anchorX,
                    initial.y + (initial.height - outerSize.height) / 2,
                    outerSize.width,
                    outerSize.height);
        }

        int anchorY = handle.top() ? initial.y + initial.height : initial.y;
        int proposedOuterHeight = handle.top() ? anchorY - pointer.y : pointer.y - anchorY;
        outerSize = sizeFromHeight(
                proposedOuterHeight, aspect, minimumOuterSize.width, minimumOuterSize.height);
        return new Rectangle(
                initial.x + (initial.width - outerSize.width) / 2,
                handle.top() ? anchorY - outerSize.height : anchorY,
                outerSize.width,
                outerSize.height);
    }

    private static Rectangle anchoredCornerBounds(int anchorX,
                                                   int anchorY,
                                                   Handle handle,
                                                   Dimension outerSize) {
        return new Rectangle(
                handle.left() ? anchorX - outerSize.width : anchorX,
                handle.top() ? anchorY - outerSize.height : anchorY,
                outerSize.width,
                outerSize.height);
    }

    static Dimension projectToAspect(int proposedWidth,
                                     int proposedHeight,
                                     double aspect,
                                     int minimumWidth,
                                     int minimumHeight) {
        double projectedHeight = (aspect * proposedWidth + proposedHeight) / (aspect * aspect + 1d);
        int height = Math.max(1, (int) Math.round(projectedHeight));
        return normalizedSize(height, aspect, minimumWidth, minimumHeight);
    }

    private static Dimension sizeFromWidth(int proposedWidth,
                                           double aspect,
                                           int minimumWidth,
                                           int minimumHeight) {
        int height = Math.max(1, (int) Math.round(Math.max(1, proposedWidth) / aspect));
        return normalizedSize(height, aspect, minimumWidth, minimumHeight);
    }

    private static Dimension sizeFromHeight(int proposedHeight,
                                            double aspect,
                                            int minimumWidth,
                                            int minimumHeight) {
        return normalizedSize(Math.max(1, proposedHeight), aspect, minimumWidth, minimumHeight);
    }

    private static Dimension normalizedSize(int proposedHeight,
                                            double aspect,
                                            int minimumWidth,
                                            int minimumHeight) {
        int height = Math.max(Math.max(1, proposedHeight), minimumHeight);
        height = Math.max(height, (int) Math.ceil(minimumWidth / aspect));
        return new Dimension(Math.max(1, (int) Math.round(height * aspect)), height);
    }

    enum Handle {
        TOP_LEFT(-1, -1, Cursor.NW_RESIZE_CURSOR),
        TOP(0, -1, Cursor.N_RESIZE_CURSOR),
        TOP_RIGHT(1, -1, Cursor.NE_RESIZE_CURSOR),
        RIGHT(1, 0, Cursor.E_RESIZE_CURSOR),
        BOTTOM_RIGHT(1, 1, Cursor.SE_RESIZE_CURSOR),
        BOTTOM(0, 1, Cursor.S_RESIZE_CURSOR),
        BOTTOM_LEFT(-1, 1, Cursor.SW_RESIZE_CURSOR),
        LEFT(-1, 0, Cursor.W_RESIZE_CURSOR);

        private final int horizontalDirection;
        private final int verticalDirection;
        private final int cursor;

        Handle(int horizontalDirection, int verticalDirection, int cursor) {
            this.horizontalDirection = horizontalDirection;
            this.verticalDirection = verticalDirection;
            this.cursor = cursor;
        }

        boolean left() {
            return horizontalDirection < 0;
        }

        boolean top() {
            return verticalDirection < 0;
        }

        boolean isCorner() {
            return horizontalDirection != 0 && verticalDirection != 0;
        }
    }

    private final class ResizeMouseHandler extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent event) {
            activeHandle = handleAt(event.getX(), event.getY());
            if (activeHandle != null) {
                startBounds = window.getBounds();
                dragAspect = aspectSupplier.getAsDouble();
                setCursor(Cursor.getPredefinedCursor(activeHandle.cursor));
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (activeHandle == null || startBounds == null) {
                return;
            }
            Rectangle bounds = resizeBounds(
                    startBounds,
                    event.getLocationOnScreen(),
                    activeHandle,
                    dragAspect,
                    window.getMinimumSize());
            window.setBounds(bounds);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            activeHandle = null;
            startBounds = null;
            dragAspect = 0;
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            Handle handle = handleAt(event.getX(), event.getY());
            setCursor(handle == null
                    ? Cursor.getDefaultCursor()
                    : Cursor.getPredefinedCursor(handle.cursor));
        }
    }
}
