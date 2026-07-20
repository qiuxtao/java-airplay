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
import java.awt.Window;

/**
 * Applies an aspect ratio while Windows is sizing a window and disables the
 * four single-axis resize borders. The native hook avoids a second Swing
 * resize after the mouse button is released.
 */
public final class WindowsAspectRatioResizer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowsAspectRatioResizer.class);

    private static final int GWL_WNDPROC = -4;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_SIZING = 0x0214;

    private static final int HTCLIENT = 1;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOP = 12;
    private static final int HTBOTTOM = 15;

    static final int WMSZ_TOPLEFT = 4;
    static final int WMSZ_TOPRIGHT = 5;
    static final int WMSZ_BOTTOMLEFT = 7;
    static final int WMSZ_BOTTOMRIGHT = 8;

    private final Window window;
    private HWND handle;
    private Pointer previousWindowProc;
    private WindowProc windowProc;
    private boolean installed;
    private volatile double contentAspect;
    private volatile int chromeWidth;
    private volatile int chromeHeight;
    private volatile int minimumOuterWidth;
    private volatile int minimumOuterHeight;

    private WindowsAspectRatioResizer(Window window) {
        this.window = window;
    }

    public static WindowsAspectRatioResizer install(Window window) {
        WindowsAspectRatioResizer resizer = new WindowsAspectRatioResizer(window);
        if (!Platform.isWindows() || !window.isDisplayable()) {
            return resizer;
        }
        try {
            resizer.installHook();
        } catch (RuntimeException | LinkageError error) {
            log.warn("Could not install the native proportional resize hook", error);
        }
        return resizer;
    }

    public void setVideoFormat(int width,
                               int height,
                               int windowChromeWidth,
                               int windowChromeHeight,
                               Dimension minimumOuterSize) {
        if (width <= 0 || height <= 0) {
            clearVideoFormat();
            return;
        }
        contentAspect = (double) width / height;
        chromeWidth = Math.max(0, windowChromeWidth);
        chromeHeight = Math.max(0, windowChromeHeight);
        minimumOuterWidth = Math.max(1, minimumOuterSize.width);
        minimumOuterHeight = Math.max(1, minimumOuterSize.height);
    }

    public void clearVideoFormat() {
        contentAspect = 0;
    }

    @Override
    public void close() {
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

    private LRESULT windowProc(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        try {
            if (message == WM_NCHITTEST) {
                LRESULT result = callPrevious(hwnd, message, wParam, lParam);
                return isSingleAxisResizeHit(result.intValue()) ? new LRESULT(HTCLIENT) : result;
            }
            if (message == WM_SIZING && contentAspect > 0 && isCorner(wParam.intValue())) {
                constrainSizingRect(lParam.toPointer(), wParam.intValue());
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

    private void constrainSizingRect(Pointer rect, int edge) {
        int left = rect.getInt(0);
        int top = rect.getInt(4);
        int right = rect.getInt(8);
        int bottom = rect.getInt(12);
        int proposedContentWidth = Math.max(1, right - left - chromeWidth);
        int proposedContentHeight = Math.max(1, bottom - top - chromeHeight);
        Dimension content = projectContentSize(
                proposedContentWidth,
                proposedContentHeight,
                contentAspect,
                Math.max(1, minimumOuterWidth - chromeWidth),
                Math.max(1, minimumOuterHeight - chromeHeight));
        int outerWidth = content.width + chromeWidth;
        int outerHeight = content.height + chromeHeight;

        switch (edge) {
            case WMSZ_TOPLEFT -> {
                rect.setInt(0, right - outerWidth);
                rect.setInt(4, bottom - outerHeight);
            }
            case WMSZ_TOPRIGHT -> {
                rect.setInt(4, bottom - outerHeight);
                rect.setInt(8, left + outerWidth);
            }
            case WMSZ_BOTTOMLEFT -> {
                rect.setInt(0, right - outerWidth);
                rect.setInt(12, top + outerHeight);
            }
            case WMSZ_BOTTOMRIGHT -> {
                rect.setInt(8, left + outerWidth);
                rect.setInt(12, top + outerHeight);
            }
            default -> {
                // WM_SIZING is handled only for the four corners.
            }
        }
    }

    static Dimension projectContentSize(int proposedWidth,
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
        return new Dimension(
                Math.max(1, (int) Math.round(projectedWidth)),
                Math.max(1, (int) Math.round(projectedHeight)));
    }

    static boolean isSingleAxisResizeHit(int hitTest) {
        return hitTest == HTLEFT || hitTest == HTRIGHT || hitTest == HTTOP || hitTest == HTBOTTOM;
    }

    private static boolean isCorner(int sizingEdge) {
        return sizingEdge == WMSZ_TOPLEFT || sizingEdge == WMSZ_TOPRIGHT
                || sizingEdge == WMSZ_BOTTOMLEFT || sizingEdge == WMSZ_BOTTOMRIGHT;
    }
}
