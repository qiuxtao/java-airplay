package com.github.serezhka.airplay.app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AspectRatioResizeGlassPaneTest {

    @Test
    void bottomRightDragChangesBothAxesAndKeepsPortraitAspect() {
        Rectangle resized = AspectRatioResizeGlassPane.resizeBounds(
                new Rectangle(100, 100, 500, 900),
                new Point(760, 1150),
                AspectRatioResizeGlassPane.Corner.BOTTOM_RIGHT,
                16,
                60,
                9d / 16d,
                new Dimension(436, 807));

        assertEquals(100, resized.x);
        assertEquals(100, resized.y);
        assertContentAspect(resized, 16, 60, 9d / 16d);
    }

    @Test
    void topLeftDragKeepsOppositeCornerAnchored() {
        Rectangle initial = new Rectangle(300, 200, 500, 900);
        Rectangle resized = AspectRatioResizeGlassPane.resizeBounds(
                initial,
                new Point(180, 80),
                AspectRatioResizeGlassPane.Corner.TOP_LEFT,
                16,
                60,
                9d / 16d,
                new Dimension(436, 807));

        assertEquals(initial.x + initial.width, resized.x + resized.width);
        assertEquals(initial.y + initial.height, resized.y + resized.height);
        assertContentAspect(resized, 16, 60, 9d / 16d);
    }

    @Test
    void horizontalAndVerticalPointerMovementBothAffectTheProportionalSize() {
        Rectangle initial = new Rectangle(100, 100, 500, 900);
        Rectangle baseline = resizeBottomRight(initial, new Point(760, 1150));
        Rectangle horizontalChange = resizeBottomRight(initial, new Point(820, 1150));
        Rectangle verticalChange = resizeBottomRight(initial, new Point(760, 1210));

        assertNotEquals(baseline.width, horizontalChange.width);
        assertNotEquals(baseline.height, horizontalChange.height);
        assertNotEquals(baseline.width, verticalChange.width);
        assertNotEquals(baseline.height, verticalChange.height);
        assertContentAspect(baseline, 16, 60, 9d / 16d);
        assertContentAspect(horizontalChange, 16, 60, 9d / 16d);
        assertContentAspect(verticalChange, 16, 60, 9d / 16d);
    }

    private Rectangle resizeBottomRight(Rectangle initial, Point pointer) {
        return AspectRatioResizeGlassPane.resizeBounds(
                initial,
                pointer,
                AspectRatioResizeGlassPane.Corner.BOTTOM_RIGHT,
                16,
                60,
                9d / 16d,
                new Dimension(436, 807));
    }

    private void assertContentAspect(Rectangle bounds, int chromeWidth, int chromeHeight, double aspect) {
        int contentWidth = bounds.width - chromeWidth;
        int contentHeight = bounds.height - chromeHeight;
        assertEquals(contentWidth, (int) Math.round(contentHeight * aspect));
    }
}
