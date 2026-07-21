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

import java.awt.Window;

/** Disables Windows' independent edge and corner sizing for the playback window. */
public final class WindowsResizeBorderDisabler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowsResizeBorderDisabler.class);

    private static final int GWL_WNDPROC = -4;
    private static final int WM_NCDESTROY = 0x0082;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int HTCLIENT = 1;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOP = 12;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15;
    private static final int HTBOTTOMLEFT = 16;
    private static final int HTBOTTOMRIGHT = 17;

    private final Window window;
    private HWND handle;
    private Pointer previousWindowProc;
    private WindowProc windowProc;
    private boolean installed;
    private volatile boolean resizeDisabled;

    private WindowsResizeBorderDisabler(Window window) {
        this.window = window;
    }

    public static WindowsResizeBorderDisabler install(Window window) {
        WindowsResizeBorderDisabler disabler = new WindowsResizeBorderDisabler(window);
        if (!Platform.isWindows() || !window.isDisplayable()) {
            return disabler;
        }
        try {
            disabler.installHook();
        } catch (RuntimeException | LinkageError error) {
            log.warn("Could not disable native playback window resizing", error);
        }
        return disabler;
    }

    public void setResizeDisabled(boolean disabled) {
        resizeDisabled = disabled;
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
                return resizeDisabled && isResizeHit(result.intValue()) ? new LRESULT(HTCLIENT) : result;
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
            log.debug("Native playback window hit testing failed", error);
            return callPrevious(hwnd, message, wParam, lParam);
        }
    }

    private LRESULT callPrevious(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
        Pointer previous = previousWindowProc;
        return previous == null
                ? User32.INSTANCE.DefWindowProc(hwnd, message, wParam, lParam)
                : User32.INSTANCE.CallWindowProc(previous, hwnd, message, wParam, lParam);
    }

    static boolean isResizeHit(int hitTest) {
        return hitTest == HTLEFT || hitTest == HTRIGHT || hitTest == HTTOP || hitTest == HTBOTTOM
                || hitTest == HTTOPLEFT || hitTest == HTTOPRIGHT
                || hitTest == HTBOTTOMLEFT || hitTest == HTBOTTOMRIGHT;
    }
}
