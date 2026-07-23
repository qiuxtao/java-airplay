package com.github.serezhka.airplay.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;

import static org.assertj.core.api.Assertions.assertThat;

class AppIconsTest {

    @ParameterizedTest
    @ValueSource(ints = {16, 20, 24, 32, 40, 48, 64, 92, 128, 256})
    void loadsEveryApplicationIconAtItsNativeSize(int size) {
        BufferedImage image = AppIcons.image(size);

        assertThat(image.getWidth()).isEqualTo(size);
        assertThat(image.getHeight()).isEqualTo(size);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getRGB(0, 0) >>> 24).isLessThanOrEqualTo(4);
        assertThat(image.getRGB(size / 2, size / 2) >>> 24).isEqualTo(0xFF);
    }

    @Test
    void trayIconContainsNativeScaleVariants() {
        Image trayIcon = AppIcons.trayIcon();

        assertThat(trayIcon).isInstanceOf(MultiResolutionImage.class);
        assertThat(((MultiResolutionImage) trayIcon).getResolutionVariants())
                .extracting(image -> image.getWidth(null))
                .containsExactly(16, 20, 24, 32, 40, 48, 64);
    }
}
