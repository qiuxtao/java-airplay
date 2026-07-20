package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GstPlayerAudioFormatTest {

    @Test
    void usesNegotiatedFortyEightKhzFormatAndFrameDuration() {
        AudioStreamInfo stream = stream(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.AAC_ELD_48000_2,
                480);

        GstPlayer.AudioFormatSpec format = GstPlayer.audioFormatSpec(stream);

        assertEquals(48000, format.sampleRate());
        assertEquals(2, format.channels());
        assertEquals(10_000_000L, format.frameDurationNs());
        assertEquals("f8e65000", GstPlayer.aacEldCodecData(format));
    }

    @Test
    void buildsAlacCookieFromNegotiatedFormat() {
        AudioStreamInfo stream = stream(
                AudioStreamInfo.CompressionType.ALAC,
                AudioStreamInfo.AudioFormat.ALAC_48000_24_2,
                352);

        GstPlayer.AudioFormatSpec format = GstPlayer.audioFormatSpec(stream);

        assertEquals(
                "00000024616c616300000000000001600018280a0e0200ff00000000000000000000bb80",
                GstPlayer.alacCodecData(format));
    }

    @Test
    void preservesLegacyAacEldCookieForFortyFourPointOneKhzStereo() {
        AudioStreamInfo stream = stream(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_2,
                480);

        assertEquals("f8e85000", GstPlayer.aacEldCodecData(GstPlayer.audioFormatSpec(stream)));
    }

    private static AudioStreamInfo stream(AudioStreamInfo.CompressionType compression,
                                          AudioStreamInfo.AudioFormat format,
                                          int samplesPerFrame) {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(compression)
                .audioFormat(format)
                .samplesPerFrame(samplesPerFrame)
                .build();
    }
}
