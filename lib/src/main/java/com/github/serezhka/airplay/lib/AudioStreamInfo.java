package com.github.serezhka.airplay.lib;

public class AudioStreamInfo implements MediaStreamInfo {

    private final CompressionType compressionType;
    private final AudioFormat audioFormat;
    private final int samplesPerFrame;

    private AudioStreamInfo(CompressionType compressionType, AudioFormat audioFormat, int samplesPerFrame) {
        this.compressionType = compressionType;
        this.audioFormat = audioFormat;
        this.samplesPerFrame = samplesPerFrame;
    }

    @Override
    public StreamType getStreamType() {
        return StreamType.AUDIO;
    }

    public CompressionType getCompressionType() {
        return compressionType;
    }

    public AudioFormat getAudioFormat() {
        return audioFormat;
    }

    public int getSamplesPerFrame() {
        return samplesPerFrame;
    }

    public enum CompressionType {
        LPCM(1),
        ALAC(2),
        AAC(4),
        AAC_ELD(8),
        OPUS(32);

        private final int code;

        CompressionType(int code) {
            this.code = code;
        }

        public static CompressionType fromCode(long code) {
            for (CompressionType type : CompressionType.values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown compression type with code: " + code);
        }
    }

    public enum AudioFormat {
        PCM_8000_16_1(0x4, 8000, 16, 1),
        PCM_8000_16_2(0x8, 8000, 16, 2),
        PCM_16000_16_1(0x10, 16000, 16, 1),
        PCM_16000_16_2(0x20, 16000, 16, 2),
        PCM_24000_16_1(0x40, 24000, 16, 1),
        PCM_24000_16_2(0x80, 24000, 16, 2),
        PCM_32000_16_1(0x100, 32000, 16, 1),
        PCM_32000_16_2(0x200, 32000, 16, 2),
        PCM_44100_16_1(0x400, 44100, 16, 1),
        PCM_44100_16_2(0x800, 44100, 16, 2),
        PCM_44100_24_1(0x1000, 44100, 24, 1),
        PCM_44100_24_2(0x2000, 44100, 24, 2),
        PCM_48000_16_1(0x4000, 48000, 16, 1),
        PCM_48000_16_2(0x8000, 48000, 16, 2),
        PCM_48000_24_1(0x10000, 48000, 24, 1),
        PCM_48000_24_2(0x20000, 48000, 24, 2),
        ALAC_44100_16_2(0x40000, 44100, 16, 2),
        ALAC_44100_24_2(0x80000, 44100, 24, 2),
        ALAC_48000_16_2(0x100000, 48000, 16, 2),
        ALAC_48000_24_2(0x200000, 48000, 24, 2),
        AAC_LC_44100_2(0x400000, 44100, 16, 2),
        AAC_LC_48000_2(0x800000, 48000, 16, 2),
        AAC_ELD_44100_2(0x1000000, 44100, 16, 2),
        AAC_ELD_48000_2(0x2000000, 48000, 16, 2),
        AAC_ELD_16000_1(0x4000000, 16000, 16, 1),
        AAC_ELD_24000_1(0x8000000, 24000, 16, 1),
        OPUS_16000_1(0x10000000, 16000, 16, 1),
        OPUS_24000_1(0x20000000, 24000, 16, 1),
        OPUS_48000_1(0x40000000, 48000, 16, 1),
        AAC_ELD_44100_1(0x80000000L, 44100, 16, 1),
        AAC_ELD_48000_1(0x100000000L, 48000, 16, 1);

        private final long code;
        private final int sampleRate;
        private final int sampleSize;
        private final int channels;

        AudioFormat(long code, int sampleRate, int sampleSize, int channels) {
            this.code = code;
            this.sampleRate = sampleRate;
            this.sampleSize = sampleSize;
            this.channels = channels;
        }

        public static AudioFormat fromCode(long code) {
            for (AudioFormat format : AudioFormat.values()) {
                if (format.code == code) {
                    return format;
                }
            }
            throw new IllegalArgumentException("Unknown audio format with code: " + code);
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public int getSampleSize() {
            return sampleSize;
        }

        public int getChannels() {
            return channels;
        }
    }

    public static final class AudioStreamInfoBuilder {
        private AudioFormat audioFormat;
        private CompressionType compressionType;
        private int samplesPerFrame;

        public AudioStreamInfoBuilder audioFormat(AudioFormat audioFormat) {
            this.audioFormat = audioFormat;
            return this;
        }

        public AudioStreamInfoBuilder compressionType(CompressionType compressionType) {
            this.compressionType = compressionType;
            return this;
        }

        public AudioStreamInfoBuilder samplesPerFrame(int samplesPerFrame) {
            this.samplesPerFrame = samplesPerFrame;
            return this;
        }

        public AudioStreamInfo build() {
            return new AudioStreamInfo(compressionType, audioFormat, samplesPerFrame);
        }
    }

    @Override
    public String toString() {
        return "AudioStreamInfo{" +
                "compressionType=" + compressionType +
                ", audioFormat=" + audioFormat +
                ", samplesPerFrame=" + samplesPerFrame +
                '}';
    }
}
