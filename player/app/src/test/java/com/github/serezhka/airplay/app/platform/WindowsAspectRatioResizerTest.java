package com.github.serezhka.airplay.app.platform;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsAspectRatioResizerTest {

    @Test
    void projectsPointerMovementOntoPortraitRatio() {
        Dimension constrained = WindowsAspectRatioResizer.projectContentSize(540, 900, 9d / 16d, 420, 747);

        assertEquals(constrained.width, (int) Math.round(constrained.height * 9d / 16d));
    }

    @Test
    void preservesRatioWhenMinimumSizeApplies() {
        Dimension constrained = WindowsAspectRatioResizer.projectContentSize(200, 200, 16d / 9d, 640, 360);

        assertEquals(new Dimension(640, 360), constrained);
    }

    @Test
    void disablesOnlySingleAxisResizeBorders() {
        assertTrue(WindowsAspectRatioResizer.isSingleAxisResizeHit(10));
        assertTrue(WindowsAspectRatioResizer.isSingleAxisResizeHit(15));
        assertFalse(WindowsAspectRatioResizer.isSingleAxisResizeHit(13));
        assertFalse(WindowsAspectRatioResizer.isSingleAxisResizeHit(17));
    }
}
