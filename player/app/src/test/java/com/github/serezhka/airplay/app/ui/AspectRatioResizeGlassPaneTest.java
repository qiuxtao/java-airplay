package com.github.serezhka.airplay.app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AspectRatioResizeGlassPaneTest {

    private static final double PHONE_ASPECT = 9d / 16d;
    private static final Dimension MINIMUM_WINDOW = new Dimension(420, 747);

    @Test
    void exposesAllFourVisibleBordersAndCornersAsResizeHandles() {
        assertEquals(AspectRatioResizeGlassPane.Handle.TOP_LEFT,
                AspectRatioResizeGlassPane.handleAt(2, 2, 500, 900));
        assertEquals(AspectRatioResizeGlassPane.Handle.RIGHT,
                AspectRatioResizeGlassPane.handleAt(498, 450, 500, 900));
        assertEquals(AspectRatioResizeGlassPane.Handle.BOTTOM,
                AspectRatioResizeGlassPane.handleAt(250, 898, 500, 900));
        assertEquals(AspectRatioResizeGlassPane.Handle.LEFT,
                AspectRatioResizeGlassPane.handleAt(2, 450, 500, 900));
        assertNull(AspectRatioResizeGlassPane.handleAt(250, 450, 500, 900));
    }

    @Test
    void bottomRightDragChangesBothAxesAndKeepsTheWholeWindowAspect() {
        Rectangle resized = resize(
                new Rectangle(100, 100, 500, 900),
                new Point(760, 1150),
                AspectRatioResizeGlassPane.Handle.BOTTOM_RIGHT);

        assertEquals(100, resized.x);
        assertEquals(100, resized.y);
        assertWindowAspect(resized);
    }

    @Test
    void topLeftDragKeepsOppositeCornerAnchored() {
        Rectangle initial = new Rectangle(300, 200, 500, 900);
        Rectangle resized = resize(
                initial,
                new Point(180, 80),
                AspectRatioResizeGlassPane.Handle.TOP_LEFT);

        assertEquals(initial.x + initial.width, resized.x + resized.width);
        assertEquals(initial.y + initial.height, resized.y + resized.height);
        assertWindowAspect(resized);
    }

    @Test
    void horizontalAndVerticalPointerMovementBothAffectTheProportionalSize() {
        Rectangle initial = new Rectangle(100, 100, 500, 900);
        Rectangle baseline = resize(initial, new Point(760, 1150),
                AspectRatioResizeGlassPane.Handle.BOTTOM_RIGHT);
        Rectangle horizontalChange = resize(initial, new Point(820, 1150),
                AspectRatioResizeGlassPane.Handle.BOTTOM_RIGHT);
        Rectangle verticalChange = resize(initial, new Point(760, 1210),
                AspectRatioResizeGlassPane.Handle.BOTTOM_RIGHT);

        assertNotEquals(baseline.width, horizontalChange.width);
        assertNotEquals(baseline.height, horizontalChange.height);
        assertNotEquals(baseline.width, verticalChange.width);
        assertNotEquals(baseline.height, verticalChange.height);
        assertWindowAspect(baseline);
        assertWindowAspect(horizontalChange);
        assertWindowAspect(verticalChange);
    }

    @Test
    void rightBorderKeepsLeftEdgeAndResizesVerticallyAroundTheCenter() {
        Rectangle initial = new Rectangle(100, 100, 500, 900);
        Rectangle resized = resize(
                initial,
                new Point(760, 550),
                AspectRatioResizeGlassPane.Handle.RIGHT);

        assertEquals(initial.x, resized.x);
        assertEquals(initial.y + initial.height / 2, resized.y + resized.height / 2);
        assertWindowAspect(resized);
    }

    @Test
    void bottomBorderKeepsTopEdgeAndResizesHorizontallyAroundTheCenter() {
        Rectangle initial = new Rectangle(100, 100, 500, 900);
        Rectangle resized = resize(
                initial,
                new Point(350, 1150),
                AspectRatioResizeGlassPane.Handle.BOTTOM);

        assertEquals(initial.y, resized.y);
        assertEquals(initial.x + initial.width / 2, resized.x + resized.width / 2);
        assertWindowAspect(resized);
    }

    private Rectangle resize(Rectangle initial, Point pointer, AspectRatioResizeGlassPane.Handle handle) {
        return AspectRatioResizeGlassPane.resizeBounds(
                initial, pointer, handle, PHONE_ASPECT, MINIMUM_WINDOW);
    }

    private void assertWindowAspect(Rectangle bounds) {
        assertEquals(bounds.width, (int) Math.round(bounds.height * PHONE_ASPECT));
    }
}
