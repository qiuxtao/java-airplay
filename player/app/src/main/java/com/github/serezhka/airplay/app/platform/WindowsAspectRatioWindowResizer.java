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
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the video area proportional while Windows resizes the real outer window frame. */
public final class WindowsAspectRatioWindowResizer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowsAspectRatioWindowResizer.class);

    private static final int GWL_WNDPROC = -4;
    private static final int WM_DESTROY = 0x0002;
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
    private volatile boolean installed;
    private volatile Constraints constraints = Constraints.INACTIVE;
    private final AtomicBoolean callbackFailureLogged = new AtomicBoolean();

    private WindowsAspectRatioWindowResizer(Window window) {
        this.window = window;
    }

    public static WindowsAspectRatioWindowResizer install(Window window) {
        WindowsAspectRatioWindowResizer resizer = new WindowsAspectRatioWindowResizer(window);
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
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        AffineTransform transform = configuration == null
                ? new AffineTransform()
                : configuration.getDefaultTransform();
        DeviceMetrics metrics = scaleToDevicePixels(
                Math.max(0, chromeWidth), Math.max(0, chromeHeight),
                Math.max(1, minimumOuterSize.width), Math.max(1, minimumOuterSize.height),
                transform.getScaleX(), transform.getScaleY());
        constraints = new Constraints((double) width / height, metrics);
    }

    public void clearVideoFormat() {
        constraints = Constraints.INACTIVE;
    }

    /** Returns whether this instance currently owns the native window procedure hook. */
    public boolean isNativeActive() {
        HWND currentHandle = handle;
        WindowProc currentCallback = windowProc;
        if (!installed || currentHandle == null || currentCallback == null) {
            return false;
        }
        try {
            if (!User32.INSTANCE.IsWindow(currentHandle)) {
                clearNativeState();
                return false;
            }
            Pointer callbackPointer = CallbackReference.getFunctionPointer(currentCallback);
            return currentWindowProc(currentHandle) == Pointer.nativeValue(callbackPointer);
        } catch (RuntimeException | LinkageError error) {
            log.debug("Could not verify the native playback window procedure", error);
            return false;
        }
    }

    @Override
    public void close() {
        if (!installed) {
            return;
        }
        try {
            if (restoreWindowProc(handle)) {
                clearNativeState();
            }
        } catch (RuntimeException | LinkageError error) {
            log.debug("Could not restore the playback window procedure", error);
        }
    }

    private void installHook() {
        Pointer windowPointer = Native.getWindowPointer(window);
        if (windowPointer == null || Pointer.nativeValue(windowPointer) == 0) {
            throw new IllegalStateException("The playback window does not have a native handle");
        }
        HWND nativeHandle = new HWND(windowPointer);
        if (!User32.INSTANCE.IsWindow(nativeHandle)) {
            throw new IllegalStateException("The playback window handle is not valid");
        }

        WindowProc nativeWindowProc = this::windowProc;
        Pointer callbackPointer = CallbackReference.getFunctionPointer(nativeWindowProc);
        Native.setLastError(0);
        Pointer previous = User32.INSTANCE.SetWindowLongPtr(nativeHandle, GWL_WNDPROC, callbackPointer);
        int installError = Native.getLastError();
        if (previous == null) {
            throw new IllegalStateException("Windows rejected the playback window procedure (error "
                    + installError + ")");
        }

        long current = currentWindowProc(nativeHandle);
        if (current != Pointer.nativeValue(callbackPointer)) {
            User32.INSTANCE.SetWindowLongPtr(nativeHandle, GWL_WNDPROC, previous);
            throw new IllegalStateException("The playback window procedure hook was not installed at chain head");
        }

        handle = nativeHandle;
        previousWindowProc = previous;
        windowProc = nativeWindowProc;
        installed = true;
    }

    private LRESULT windowProc(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        try {
            Constraints currentConstraints = constraints;
            if (message == WM_SIZING && currentConstraints.active() && isSizingEdge(wParam.intValue())) {
                Pointer rect = pointerFrom(lParam);
                if (rect != null) {
                    constrainNativeRect(rect, wParam.intValue(), currentConstraints);
                    return new LRESULT(1);
                }
            }
            if (message == WM_DESTROY) {
                return destroyWindow(hwnd, message, wParam, lParam);
            }
            LRESULT result = callPrevious(hwnd, message, wParam, lParam);
            if (message == WM_NCDESTROY) {
                clearNativeState();
            }
            return result;
        } catch (Throwable error) {
            if (callbackFailureLogged.compareAndSet(false, true)) {
                log.warn("Native playback window message handling failed; proportional resizing is disabled",
                        error);
            }
            constraints = Constraints.INACTIVE;
            return callPrevious(hwnd, message, wParam, lParam);
        }
    }

    private LRESULT callPrevious(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        Pointer previous = previousWindowProc;
        return previous == null
                ? User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
                : User32.INSTANCE.CallWindowProc(previous, hwnd, message, wParam, lParam);
    }

    private LRESULT destroyWindow(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        Pointer previous = previousWindowProc;
        WindowProc callback = windowProc;
        boolean restored = false;
        try {
            restored = restoreWindowProc(hwnd);
            return previous == null
                    ? User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
                    : User32.INSTANCE.CallWindowProc(previous, hwnd, message, wParam, lParam);
        } finally {
            // Keep the callback strongly reachable until the native invocation has returned.
            if (callback != null && restored) {
                clearNativeState();
            }
        }
    }

    private boolean restoreWindowProc(HWND hwnd) {
        Pointer previous = previousWindowProc;
        WindowProc callback = windowProc;
        if (hwnd == null || previous == null || callback == null || !User32.INSTANCE.IsWindow(hwnd)) {
            return true;
        }
        Pointer callbackPointer = CallbackReference.getFunctionPointer(callback);
        if (currentWindowProc(hwnd) != Pointer.nativeValue(callbackPointer)) {
            log.warn("The native playback window procedure is no longer at the chain head; not restoring it");
            return false;
        }
        Native.setLastError(0);
        Pointer replaced = User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC, previous);
        int error = Native.getLastError();
        if (replaced == null && error != 0) {
            throw new IllegalStateException("Could not restore the playback window procedure (error " + error + ")");
        }
        if (currentWindowProc(hwnd) != Pointer.nativeValue(previous)) {
            throw new IllegalStateException("The playback window procedure was not restored");
        }
        return true;
    }

    private static long currentWindowProc(HWND hwnd) {
        Native.setLastError(0);
        long current = User32.INSTANCE.GetWindowLongPtr(hwnd, GWL_WNDPROC).longValue();
        int error = Native.getLastError();
        if (current == 0) {
            throw new IllegalStateException("Could not read the playback window procedure (error " + error + ")");
        }
        return current;
    }

    private void clearNativeState() {
        installed = false;
        handle = null;
        previousWindowProc = null;
        windowProc = null;
    }

    static Pointer pointerFrom(LPARAM lParam) {
        if (lParam == null || lParam.longValue() == 0) {
            return null;
        }
        return new Pointer(lParam.longValue());
    }

    private static void constrainNativeRect(Pointer pointer, int edge, Constraints constraints) {
        Rectangle proposed = new Rectangle(
                pointer.getInt(0),
                pointer.getInt(4),
                pointer.getInt(8) - pointer.getInt(0),
                pointer.getInt(12) - pointer.getInt(4));
        Rectangle constrained = constrainBounds(
                proposed, edge, constraints.contentAspect(), constraints.deviceMetrics());
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

    private record Constraints(double contentAspect, DeviceMetrics deviceMetrics) {
        private static final Constraints INACTIVE = new Constraints(0, new DeviceMetrics(0, 0, 1, 1));

        private boolean active() {
            return contentAspect > 0 && Double.isFinite(contentAspect);
        }
    }
}
