package com.github.serezhka.airplay.app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybackWindowTest {

    @Test
    void constrainsPortraitResizeByWidth() {
        Dimension constrained = PlaybackWindow.constrainVideoSize(1080, 1920, 540, 800, 450, 800);

        assertEquals(new Dimension(540, 960), constrained);
    }

    @Test
    void constrainsPortraitResizeByHeight() {
        Dimension constrained = PlaybackWindow.constrainVideoSize(1080, 1920, 450, 1000, 450, 800);

        assertEquals(new Dimension(563, 1000), constrained);
    }

    @Test
    void constrainsLandscapeResizeByWidth() {
        Dimension constrained = PlaybackWindow.constrainVideoSize(1920, 1080, 1280, 640, 960, 640);

        assertEquals(new Dimension(1280, 720), constrained);
    }
}
