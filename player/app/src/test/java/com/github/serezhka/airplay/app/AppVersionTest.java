package com.github.serezhka.airplay.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppVersionTest {

    @Test
    void alwaysProvidesAVisibleVersion() {
        assertFalse(AppVersion.current().isBlank());
        assertTrue(AppVersion.display().startsWith("v"));
    }
}
