package com.github.serezhka.airplay.app.platform;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsAspectRatioWindowResizerTest {

    private static final double ASPECT = 9d / 16d;
    private static final WindowsAspectRatioWindowResizer.DeviceMetrics METRICS =
            new WindowsAspectRatioWindowResizer.DeviceMetrics(16, 60, 436, 807);

    @Test
    void allRealWindowBordersAndCornersKeepTheVideoAreaProportional() {
        for (int edge = WindowsAspectRatioWindowResizer.WMSZ_LEFT;
             edge <= WindowsAspectRatioWindowResizer.WMSZ_BOTTOMRIGHT;
             edge++) {
            Rectangle resized = WindowsAspectRatioWindowResizer.constrainBounds(
                    new Rectangle(100, 100, 620, 1050), edge, ASPECT, METRICS);
            assertVideoAspect(resized);
        }
    }

    @Test
    void rightBorderKeepsLeftEdgeAndMovesTopAndBottomAroundCenter() {
        Rectangle proposed = new Rectangle(100, 100, 620, 1050);
        Rectangle resized = WindowsAspectRatioWindowResizer.constrainBounds(
                proposed, WindowsAspectRatioWindowResizer.WMSZ_RIGHT, ASPECT, METRICS);

        assertEquals(proposed.x, resized.x);
        assertEquals(proposed.y + proposed.height / 2, resized.y + resized.height / 2);
        assertVideoAspect(resized);
    }

    @Test
    void bottomBorderKeepsTopEdgeAndMovesLeftAndRightAroundCenter() {
        Rectangle proposed = new Rectangle(100, 100, 620, 1050);
        Rectangle resized = WindowsAspectRatioWindowResizer.constrainBounds(
                proposed, WindowsAspectRatioWindowResizer.WMSZ_BOTTOM, ASPECT, METRICS);

        assertEquals(proposed.y, resized.y);
        assertEquals(proposed.x + proposed.width / 2, resized.x + resized.width / 2);
        assertVideoAspect(resized);
    }

    @Test
    void convertsSwingMeasurementsToNativePixelsAtScaledDpi() {
        WindowsAspectRatioWindowResizer.DeviceMetrics metrics =
                WindowsAspectRatioWindowResizer.scaleToDevicePixels(16, 60, 436, 807, 1.5, 1.5);

        assertEquals(new WindowsAspectRatioWindowResizer.DeviceMetrics(24, 90, 654, 1211), metrics);
    }

    private void assertVideoAspect(Rectangle outerBounds) {
        int videoWidth = outerBounds.width - METRICS.chromeWidth();
        int videoHeight = outerBounds.height - METRICS.chromeHeight();
        assertEquals(videoWidth, (int) Math.round(videoHeight * ASPECT));
    }
}
