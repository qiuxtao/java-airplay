package com.github.serezhka.airplay.app.platform;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.Rectangle;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsAspectRatioWindowResizerTest {

    private static final Rectangle PROPOSED = new Rectangle(100, 120, 733, 997);

    @Test
    void lParamAddressCreatesDereferenceableRectPointer() {
        try (Memory rect = new Memory(16)) {
            rect.setInt(0, 10);
            rect.setInt(4, 20);
            rect.setInt(8, 610);
            rect.setInt(12, 1020);

            LPARAM lParam = new LPARAM(Pointer.nativeValue(rect));
            Pointer pointer = WindowsAspectRatioWindowResizer.pointerFrom(lParam);

            assertEquals(Pointer.nativeValue(rect), Pointer.nativeValue(pointer));
            assertEquals(10, pointer.getInt(0));
            assertEquals(20, pointer.getInt(4));
            assertEquals(610, pointer.getInt(8));
            assertEquals(1020, pointer.getInt(12));

            pointer.setInt(8, 777);
            assertEquals(777, rect.getInt(8));
        }
    }

    @Test
    void nullLParamAddressIsRejectedBeforeDereference() {
        assertNull(WindowsAspectRatioWindowResizer.pointerFrom(null));
        assertNull(WindowsAspectRatioWindowResizer.pointerFrom(new LPARAM(0)));
    }

    @ParameterizedTest(name = "{0}, edge {1}, scale {2}")
    @MethodSource("geometryScenarios")
    void allBordersAndCornersPreserveAspectAndTheirOppositeAnchor(
            String orientation,
            int edge,
            double scale,
            double aspect,
            WindowsAspectRatioWindowResizer.DeviceMetrics logicalMetrics) {
        WindowsAspectRatioWindowResizer.DeviceMetrics metrics =
                WindowsAspectRatioWindowResizer.scaleToDevicePixels(
                        logicalMetrics.chromeWidth(),
                        logicalMetrics.chromeHeight(),
                        logicalMetrics.minimumOuterWidth(),
                        logicalMetrics.minimumOuterHeight(),
                        scale,
                        scale);

        Rectangle resized = WindowsAspectRatioWindowResizer.constrainBounds(
                PROPOSED, edge, aspect, metrics);

        assertVideoAspect(resized, aspect, metrics);
        assertMinimumSize(resized, metrics);
        assertOppositeAnchor(PROPOSED, resized, edge);
    }

    @Test
    void invalidDpiScaleFallsBackToOneHundredPercent() {
        WindowsAspectRatioWindowResizer.DeviceMetrics expected =
                new WindowsAspectRatioWindowResizer.DeviceMetrics(16, 60, 436, 807);

        assertEquals(expected,
                WindowsAspectRatioWindowResizer.scaleToDevicePixels(16, 60, 436, 807, 0, Double.NaN));
    }

    private static Stream<Arguments> geometryScenarios() {
        WindowsAspectRatioWindowResizer.DeviceMetrics portrait =
                new WindowsAspectRatioWindowResizer.DeviceMetrics(16, 60, 436, 807);
        WindowsAspectRatioWindowResizer.DeviceMetrics landscape =
                new WindowsAspectRatioWindowResizer.DeviceMetrics(18, 64, 820, 520);
        return Stream.of(
                        Arguments.of("portrait", 9d / 16d, portrait),
                        Arguments.of("landscape", 16d / 9d, landscape))
                .flatMap(format -> Stream.of(1d, 1.5d, 2d)
                        .flatMap(scale -> Stream.iterate(
                                        WindowsAspectRatioWindowResizer.WMSZ_LEFT,
                                        edge -> edge <= WindowsAspectRatioWindowResizer.WMSZ_BOTTOMRIGHT,
                                        edge -> edge + 1)
                                .map(edge -> Arguments.of(
                                        format.get()[0], edge, scale, format.get()[1], format.get()[2]))));
    }

    private static void assertVideoAspect(
            Rectangle outerBounds,
            double aspect,
            WindowsAspectRatioWindowResizer.DeviceMetrics metrics) {
        int videoWidth = outerBounds.width - metrics.chromeWidth();
        int videoHeight = outerBounds.height - metrics.chromeHeight();
        assertEquals(videoWidth, (int) Math.round(videoHeight * aspect),
                "content aspect must be exact to the nearest physical pixel");
    }

    private static void assertMinimumSize(
            Rectangle outerBounds,
            WindowsAspectRatioWindowResizer.DeviceMetrics metrics) {
        assertTrue(outerBounds.width >= metrics.minimumOuterWidth(), "minimum outer width");
        assertTrue(outerBounds.height >= metrics.minimumOuterHeight(), "minimum outer height");
    }

    private static void assertOppositeAnchor(Rectangle proposed, Rectangle resized, int edge) {
        int proposedRight = proposed.x + proposed.width;
        int proposedBottom = proposed.y + proposed.height;
        int resizedRight = resized.x + resized.width;
        int resizedBottom = resized.y + resized.height;

        switch (edge) {
            case WindowsAspectRatioWindowResizer.WMSZ_LEFT -> {
                assertEquals(proposedRight, resizedRight, "right edge");
                assertEquals(centerY(proposed), centerY(resized), "vertical center");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_RIGHT -> {
                assertEquals(proposed.x, resized.x, "left edge");
                assertEquals(centerY(proposed), centerY(resized), "vertical center");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_TOP -> {
                assertEquals(proposedBottom, resizedBottom, "bottom edge");
                assertEquals(centerX(proposed), centerX(resized), "horizontal center");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_BOTTOM -> {
                assertEquals(proposed.y, resized.y, "top edge");
                assertEquals(centerX(proposed), centerX(resized), "horizontal center");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_TOPLEFT -> {
                assertEquals(proposedRight, resizedRight, "right edge");
                assertEquals(proposedBottom, resizedBottom, "bottom edge");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_TOPRIGHT -> {
                assertEquals(proposed.x, resized.x, "left edge");
                assertEquals(proposedBottom, resizedBottom, "bottom edge");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_BOTTOMLEFT -> {
                assertEquals(proposedRight, resizedRight, "right edge");
                assertEquals(proposed.y, resized.y, "top edge");
            }
            case WindowsAspectRatioWindowResizer.WMSZ_BOTTOMRIGHT -> {
                assertEquals(proposed.x, resized.x, "left edge");
                assertEquals(proposed.y, resized.y, "top edge");
            }
            default -> throw new AssertionError("Unexpected sizing edge: " + edge);
        }
    }

    private static int centerX(Rectangle rectangle) {
        return rectangle.x + rectangle.width / 2;
    }

    private static int centerY(Rectangle rectangle) {
        return rectangle.y + rectangle.height / 2;
    }
}
