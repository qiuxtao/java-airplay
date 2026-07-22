package com.github.serezhka.airplay.app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackWindowPlacementTest {

    @Test
    void initialWindowSizeExactlyMatchesThePhoneAspect() {
        Dimension window = PlaybackWindow.fitWindowSize(1179, 2556, 1884, 1004);

        assertEquals(1004, window.height);
        assertEquals(window.width, (int) Math.round(window.height * (1179d / 2556d)));
    }

    @Test
    void placesTallPhoneWindowAtRightWithEqualVerticalGaps() {
        Rectangle bounds = PlaybackWindow.sideWindowBounds(
                new Dimension(500, 1004),
                new Rectangle(0, 0, 1920, 1040),
                18);

        assertEquals(new Rectangle(1402, 18, 500, 1004), bounds);
    }
}
