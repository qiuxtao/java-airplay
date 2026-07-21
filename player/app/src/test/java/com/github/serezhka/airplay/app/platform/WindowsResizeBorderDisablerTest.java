package com.github.serezhka.airplay.app.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsResizeBorderDisablerTest {

    @Test
    void disablesEveryNativeResizeDirection() {
        for (int hitTest = 10; hitTest <= 17; hitTest++) {
            assertTrue(WindowsResizeBorderDisabler.isResizeHit(hitTest));
        }
        assertFalse(WindowsResizeBorderDisabler.isResizeHit(1));
        assertFalse(WindowsResizeBorderDisabler.isResizeHit(9));
    }
}
