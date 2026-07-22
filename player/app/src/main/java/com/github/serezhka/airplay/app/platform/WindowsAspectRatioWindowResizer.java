package com.github.serezhka.airplay.app.platform;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;

/** Keeps the video area proportional while Windows resizes the real outer window frame. */
public final class WindowsAspectRatioWindowResizer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowsAspectRatioWindowResizer.class);

    private static final int GWL_WNDPROC = -4;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_SIZING = 0x0214;

    static final int WMSZ_LEFT = 1;
    static final int WMSZ_RIGHT = 2;
    static final int WMSZ_TOP = 3;
    static final int WMSZ_TOPLEFT = 4;
    static final int WMSZ_TOPRIGHT = 5;
    static final int WMSZ_BOTTOM = 6;
    static final int WMSZ_BOTTOMLEFT = 7;
    static final int WMSZ_BOTTOMRIGHT = 8;

    private final Window window;
    private HWND handle;
    private Pointer previousWindowProc;
    private WindowProc windowProc;
    private boolean installed;
    private boolean fallbackInstalled;
    private boolean correctingFallback;
    private Rectangle lastBounds;
    private volatile double contentAspect;
    private volatile int logicalChromeWidth;
    private volatile int logicalChromeHeight;
    private volatile int logicalMinimumOuterWidth;
    private volatile int logicalMinimumOuterHeight;
    private final ComponentAdapter fallbackListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            correctFallbackResize();
        }
    };

    private WindowsAspectRatioWindowResizer(Window window) {
        this.window = window;
    }

    public static WindowsAspectRatioWindowResizer install(Window window) {
        WindowsAspectRatioWindowResizer resizer = new WindowsAspectRatioWindowResizer(window);
        resizer.installFallback();
        if (!Platform.isWindows() || !window.isDisplayable()) {
            return resizer;
        }
        try {
            resizer.installHook();
        } catch (RuntimeException | LinkageError error) {
            log.warn("Could not install the native playback window resize hook", error);
        }
        return resizer;
    }

    public void setVideoFormat(int width,
                               int height,
                               int chromeWidth,
                               int chromeHeight,
                               Dimension minimumOuterSize) {
        if (width <= 0 || height <= 0) {
            clearVideoFormat();
            return;
        }
        contentAspect = (double) width / height;
        logicalChromeWidth = Math.max(0, chromeWidth);
        logicalChromeHeight = Math.max(0, chromeHeight);
        logicalMinimumOuterWidth = Math.max(1, minimumOuterSize.width);
        logicalMinimumOuterHeight = Math.max(1, minimumOuterSize.height);
        lastBounds = window.getBounds();
    }

    public void clearVideoFormat() {
        contentAspect = 0;
    }

    @Override
    public void close() {
        uninstallFallback();
        if (!installed) {
            return;
        }
        try {
            if (handle != null && previousWindowProc != null && User32.INSTANCE.IsWindow(handle)) {
                User32.INSTANCE.SetWindowLongPtr(handle, GWL_WNDPROC, previousWindowProc);
            }
        } catch (RuntimeException | LinkageError error) {
            log.debug("Could not restore the playback window procedure", error);
        } finally {
            installed = false;
            handle = null;
            previousWindowProc = null;
            windowProc = null;
        }
    }

    private void installHook() {
        handle = new HWND(Native.getWindowPointer(window));
        windowProc = this::windowProc;
        previousWindowProc = User32.INSTANCE.SetWindowLongPtr(handle, GWL_WNDPROC,
                CallbackReference.getFunctionPointer(windowProc));
        if (previousWindowProc == null) {
            windowProc = null;
            throw new IllegalStateException("Windows rejected the playback window procedure");
        }
        installed = true;
    }

    private void installFallback() {
        if (!fallbackInstalled) {
            lastBounds = window.getBounds();
            window.addComponentListener(fallbackListener);
            fallbackInstalled = true;
        }
    }

    private void uninstallFallback() {
        if (fallbackInstalled) {
            window.removeComponentListener(fallbackListener);
            fallbackInstalled = false;
        }
    }

    private void correctFallbackResize() {
        if (correctingFallback || contentAspect <= 0) {
            return;
        }
        if (window instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
            lastBounds = window.getBounds();
            return;
        }
        Rectangle proposed = window.getBounds();
        Rectangle previous = lastBounds;
        if (previous == null) {
            lastBounds = proposed;
            return;
        }
        DeviceMetrics logicalMetrics = new DeviceMetrics(
                logicalChromeWidth, logicalChromeHeight,
                logicalMinimumOuterWidth, logicalMinimumOuterHeight);
        if (hasExpectedAspect(proposed, contentAspect, logicalMetrics)) {
            lastBounds = proposed;
            return;
        }
        int edge = inferSizingEdge(previous, proposed);
        Rectangle corrected = constrainBounds(proposed, edge, contentAspect, logicalMetrics);
        correctingFallback = true;
        try {
            window.setBounds(corrected);
            window.validate();
            lastBounds = corrected;
        } finally {
            correctingFallback = false;
        }
    }

    private LRESULT windowProc(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        try {
            if (message == WM_SIZING && contentAspect > 0 && isSizingEdge(wParam.intValue())) {
                constrainNativeRect(lParam.toPointer(), wParam.intValue());
                return new LRESULT(1);
            }
            LRESULT result = callPrevious(hwnd, message, wParam, lParam);
            if (message == WM_NCDESTROY) {
                installed = false;
                handle = null;
                previousWindowProc = null;
                windowProc = null;
            }
            return result;
        } catch (Throwable error) {
            log.debug("Native playback window sizing failed", error);
            return callPrevious(hwnd, message, wParam, lParam);
        }
    }

    private LRESULT callPrevious(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        Pointer previous = previousWindowProc;
        return previous == null
                ? User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
                : User32.INSTANCE.CallWindowProc(previous, hwnd, message, wParam, lParam);
    }

    private void constrainNativeRect(Pointer pointer, int edge) {
        Rectangle proposed = new Rectangle(
                pointer.getInt(0),
                pointer.getInt(4),
                pointer.getInt(8) - pointer.getInt(0),
                pointer.getInt(12) - pointer.getInt(4));
        Rectangle constrained = constrainBounds(proposed, edge, contentAspect, deviceMetrics());
        pointer.setInt(0, constrained.x);
        pointer.setInt(4, constrained.y);
        pointer.setInt(8, constrained.x + constrained.width);
        pointer.setInt(12, constrained.y + constrained.height);
    }

    static Rectangle constrainBounds(Rectangle proposed, int edge, double aspect, DeviceMetrics metrics) {
        int contentWidth = Math.max(1, proposed.width - metrics.chromeWidth());
        int contentHeight = Math.max(1, proposed.height - metrics.chromeHeight());
        int minimumContentWidth = Math.max(1, metrics.minimumOuterWidth() - metrics.chromeWidth());
        int minimumContentHeight = Math.max(1, metrics.minimumOuterHeight() - metrics.chromeHeight());
        Dimension content = switch (edge) {
            case WMSZ_LEFT, WMSZ_RIGHT -> sizeFromWidth(
                    contentWidth, aspect, minimumContentWidth, minimumContentHeight);
            case WMSZ_TOP, WMSZ_BOTTOM -> sizeFromHeight(
                    contentHeight, aspect, minimumContentWidth, minimumContentHeight);
            default -> projectSize(
                    contentWidth, contentHeight, aspect, minimumContentWidth, minimumContentHeight);
        };
        int outerWidth = content.width + metrics.chromeWidth();
        int outerHeight = content.height + metrics.chromeHeight();
        int right = proposed.x + proposed.width;
        int bottom = proposed.y + proposed.height;
        int centerX = proposed.x + proposed.width / 2;
        int centerY = proposed.y + proposed.height / 2;

        return switch (edge) {
            case WMSZ_LEFT -> new Rectangle(right - outerWidth, centerY - outerHeight / 2,
                    outerWidth, outerHeight);
            case WMSZ_RIGHT -> new Rectangle(proposed.x, centerY - outerHeight / 2,
                    outerWidth, outerHeight);
            case WMSZ_TOP -> new Rectangle(centerX - outerWidth / 2, bottom - outerHeight,
                    outerWidth, outerHeight);
            case WMSZ_BOTTOM -> new Rectangle(centerX - outerWidth / 2, proposed.y,
                    outerWidth, outerHeight);
            case WMSZ_TOPLEFT -> new Rectangle(right - outerWidth, bottom - outerHeight,
                    outerWidth, outerHeight);
            case WMSZ_TOPRIGHT -> new Rectangle(proposed.x, bottom - outerHeight,
                    outerWidth, outerHeight);
            case WMSZ_BOTTOMLEFT -> new Rectangle(right - outerWidth, proposed.y,
                    outerWidth, outerHeight);
            case WMSZ_BOTTOMRIGHT -> new Rectangle(proposed.x, proposed.y,
                    outerWidth, outerHeight);
            default -> proposed;
        };
    }

    static int inferSizingEdge(Rectangle previous, Rectangle proposed) {
        int leftDelta = Math.abs(proposed.x - previous.x);
        int rightDelta = Math.abs(proposed.x + proposed.width - previous.x - previous.width);
        int topDelta = Math.abs(proposed.y - previous.y);
        int bottomDelta = Math.abs(proposed.y + proposed.height - previous.y - previous.height);
        int horizontal = leftDelta > rightDelta ? -1 : rightDelta > 0 ? 1 : 0;
        int vertical = topDelta > bottomDelta ? -1 : bottomDelta > 0 ? 1 : 0;
        if (horizontal < 0 && vertical < 0) {
            return WMSZ_TOPLEFT;
        }
        if (horizontal > 0 && vertical < 0) {
            return WMSZ_TOPRIGHT;
        }
        if (horizontal < 0 && vertical > 0) {
            return WMSZ_BOTTOMLEFT;
        }
        if (horizontal > 0 && vertical > 0) {
            return WMSZ_BOTTOMRIGHT;
        }
        if (horizontal < 0) {
            return WMSZ_LEFT;
        }
        if (horizontal > 0) {
            return WMSZ_RIGHT;
        }
        if (vertical < 0) {
            return WMSZ_TOP;
        }
        return WMSZ_BOTTOM;
    }

    private static boolean hasExpectedAspect(Rectangle bounds, double aspect, DeviceMetrics metrics) {
        int contentWidth = Math.max(1, bounds.width - metrics.chromeWidth());
        int contentHeight = Math.max(1, bounds.height - metrics.chromeHeight());
        return contentWidth == (int) Math.round(contentHeight * aspect);
    }

    static Dimension projectSize(int proposedWidth,
                                 int proposedHeight,
                                 double aspect,
                                 int minimumWidth,
                                 int minimumHeight) {
        double projectedHeight = (aspect * proposedWidth + proposedHeight) / (aspect * aspect + 1d);
        return normalizedSize((int) Math.round(projectedHeight), aspect, minimumWidth, minimumHeight);
    }

    private static Dimension sizeFromWidth(int proposedWidth,
                                           double aspect,
                                           int minimumWidth,
                                           int minimumHeight) {
        return normalizedSize((int) Math.round(proposedWidth / aspect),
                aspect, minimumWidth, minimumHeight);
    }

    private static Dimension sizeFromHeight(int proposedHeight,
                                            double aspect,
                                            int minimumWidth,
                                            int minimumHeight) {
        return normalizedSize(proposedHeight, aspect, minimumWidth, minimumHeight);
    }

    private static Dimension normalizedSize(int proposedHeight,
                                            double aspect,
                                            int minimumWidth,
                                            int minimumHeight) {
        int height = Math.max(Math.max(1, proposedHeight), minimumHeight);
        height = Math.max(height, (int) Math.ceil(minimumWidth / aspect));
        return new Dimension(Math.max(1, (int) Math.round(height * aspect)), height);
    }

    private DeviceMetrics deviceMetrics() {
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        AffineTransform transform = configuration == null
                ? new AffineTransform()
                : configuration.getDefaultTransform();
        return scaleToDevicePixels(
                logicalChromeWidth, logicalChromeHeight,
                logicalMinimumOuterWidth, logicalMinimumOuterHeight,
                transform.getScaleX(), transform.getScaleY());
    }

    static DeviceMetrics scaleToDevicePixels(int chromeWidth,
                                              int chromeHeight,
                                              int minimumOuterWidth,
                                              int minimumOuterHeight,
                                              double scaleX,
                                              double scaleY) {
        return new DeviceMetrics(
                scale(chromeWidth, scaleX),
                scale(chromeHeight, scaleY),
                Math.max(1, scale(minimumOuterWidth, scaleX)),
                Math.max(1, scale(minimumOuterHeight, scaleY)));
    }

    private static int scale(int value, double factor) {
        double usableFactor = factor > 0 && Double.isFinite(factor) ? factor : 1d;
        return Math.max(0, (int) Math.round(value * usableFactor));
    }

    private static boolean isSizingEdge(int edge) {
        return edge >= WMSZ_LEFT && edge <= WMSZ_BOTTOMRIGHT;
    }

    record DeviceMetrics(int chromeWidth,
                         int chromeHeight,
                         int minimumOuterWidth,
                         int minimumOuterHeight) {
    }
}
