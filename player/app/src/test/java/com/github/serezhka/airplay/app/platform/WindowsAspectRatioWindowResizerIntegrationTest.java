package com.github.serezhka.airplay.app.platform;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsAspectRatioWindowResizerIntegrationTest {

    private static final int GWL_WNDPROC = -4;
    private static final int WM_SIZING = 0x0214;

    @Test
    void realFlatLafFrameConstrainsWmSizingSynchronouslyAndRestoresWindowProc() throws Exception {
        assumeTrue(Platform.isWindows(), "native integration test only runs on Windows");
        assertFalse(GraphicsEnvironment.isHeadless(),
                "Windows native-resize CI must provide a non-headless desktop session");

        FlatLightLaf.setup();
        JFrame frame = onEdt(() -> {
            JFrame created = new JFrame("native aspect integration test");
            created.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            created.setBounds(-10_000, -10_000, 620, 1050);
            created.setVisible(true);
            return created;
        });
        HWND hwnd = new HWND(Native.getWindowPointer(frame));
        long originalWindowProc = currentWindowProc(hwnd);
        int componentListenerCount = frame.getComponentListeners().length;
        WindowsAspectRatioWindowResizer resizer = null;

        try {
            resizer = onEdt(() -> WindowsAspectRatioWindowResizer.install(frame));
            WindowsAspectRatioWindowResizer installedResizer = resizer;
            onEdt(() -> {
                installedResizer.setVideoFormat(9, 16, 16, 60, new Dimension(436, 807));
                return null;
            });

            assertTrue(resizer.isNativeActive(), "the native WndProc hook must be active");
            assertNotEquals(originalWindowProc, currentWindowProc(hwnd),
                    "the hook must be at the head of the WndProc chain");
            assertEquals(componentListenerCount, frame.getComponentListeners().length,
                    "native resizing must not install a resize-correction listener");

            Rectangle first = sendSizing(hwnd, WindowsAspectRatioWindowResizer.WMSZ_RIGHT,
                    new Rectangle(100, 100, 620, 1050));
            assertNativeAspect(first, frame, 9d / 16d);

            onEdt(() -> {
                FlatDarkLaf.setup();
                SwingUtilities.updateComponentTreeUI(frame);
                return null;
            });
            assertTrue(resizer.isNativeActive(), "theme changes must not remove the hook");

            Rectangle afterThemeChange = sendSizing(hwnd, WindowsAspectRatioWindowResizer.WMSZ_BOTTOMLEFT,
                    new Rectangle(80, 90, 750, 930));
            assertNativeAspect(afterThemeChange, frame, 9d / 16d);

            WindowsAspectRatioWindowResizer resizerToClose = resizer;
            onEdt(() -> {
                resizerToClose.close();
                return null;
            });
            resizer = null;
            assertFalse(resizerToClose.isNativeActive());
            assertEquals(originalWindowProc, currentWindowProc(hwnd),
                    "close must restore the previous WndProc before the frame is destroyed");
        } finally {
            if (resizer != null) {
                WindowsAspectRatioWindowResizer resizerToClose = resizer;
                onEdt(() -> {
                    resizerToClose.close();
                    return null;
                });
            }
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void disposingTheFrameClearsTheNativeHookWithoutAnExplicitClose() throws Exception {
        assumeTrue(Platform.isWindows(), "native integration test only runs on Windows");
        assertFalse(GraphicsEnvironment.isHeadless(),
                "Windows native-resize CI must provide a non-headless desktop session");

        FlatLightLaf.setup();
        JFrame frame = onEdt(() -> {
            JFrame created = new JFrame("native aspect destroy test");
            created.setBounds(-10_000, -10_000, 620, 1050);
            created.setVisible(true);
            return created;
        });
        WindowsAspectRatioWindowResizer resizer = onEdt(() ->
                WindowsAspectRatioWindowResizer.install(frame));
        assertTrue(resizer.isNativeActive());

        onEdt(() -> {
            frame.dispose();
            return null;
        });

        assertFalse(resizer.isNativeActive(), "WM_DESTROY must release the native hook state");
    }

    private static Rectangle sendSizing(HWND hwnd, int edge, Rectangle proposed) {
        try (Memory rect = new Memory(16)) {
            rect.setInt(0, proposed.x);
            rect.setInt(4, proposed.y);
            rect.setInt(8, proposed.x + proposed.width);
            rect.setInt(12, proposed.y + proposed.height);

            LRESULT result = User32.INSTANCE.SendMessage(hwnd, WM_SIZING,
                    new WPARAM(edge), new LPARAM(Pointer.nativeValue(rect)));
            assertEquals(1, result.intValue(), "WM_SIZING must be handled synchronously");

            int left = rect.getInt(0);
            int top = rect.getInt(4);
            return new Rectangle(left, top, rect.getInt(8) - left, rect.getInt(12) - top);
        }
    }

    private static void assertNativeAspect(Rectangle bounds, JFrame frame, double aspect) {
        AffineTransform transform = frame.getGraphicsConfiguration().getDefaultTransform();
        WindowsAspectRatioWindowResizer.DeviceMetrics metrics =
                WindowsAspectRatioWindowResizer.scaleToDevicePixels(
                        16, 60, 436, 807, transform.getScaleX(), transform.getScaleY());
        int contentWidth = bounds.width - metrics.chromeWidth();
        int contentHeight = bounds.height - metrics.chromeHeight();
        assertEquals(contentWidth, (int) Math.round(contentHeight * aspect));
    }

    private static long currentWindowProc(HWND hwnd) {
        LONG_PTR pointer = User32.INSTANCE.GetWindowLongPtr(hwnd, GWL_WNDPROC);
        return pointer.longValue();
    }

    private static <T> T onEdt(EdtSupplier<T> supplier)
            throws InvocationTargetException, InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError("EDT operation failed", failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface EdtSupplier<T> {
        T get() throws Exception;
    }
}
