package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioHandlerTest {

    private static final long SSRC = 0x12345678L;

    private final RecordingConsumer consumer = new RecordingConsumer();
    private final AudioHandler handler = new AudioHandler(new PassthroughAirPlay(), consumer);

    @Test
    void acceptsSequenceZeroAsFirstPacket() throws Exception {
        receive(0, SSRC, 7);

        assertEquals(List.of(7), consumer.audioMarkers);
    }

    @Test
    void keepsDeliveringAcrossSixteenBitSequenceWrap() throws Exception {
        receive(65534, SSRC, 1);
        receive(65535, SSRC, 2);
        receive(0, SSRC, 3);
        receive(1, SSRC, 4);

        assertEquals(List.of(1, 2, 3, 4), consumer.audioMarkers);
    }

    @Test
    void keepsDeliveringBeyondTheReportedTwoHundredTwentySecondBoundary() throws Exception {
        int initialSequence = 37974;
        int packetCount = 28000;

        for (int offset = 0; offset < packetCount; offset++) {
            receive((initialSequence + offset) & 0xFFFF, SSRC, offset);
        }

        assertEquals(packetCount, consumer.audioMarkers.size());
        assertEquals(0, consumer.audioMarkers.getFirst());
        assertEquals((packetCount - 1) & 0xFF, consumer.audioMarkers.getLast());
    }

    @Test
    void reordersPacketsAcrossSequenceWrap() throws Exception {
        receive(65534, SSRC, 1);
        receive(0, SSRC, 3);
        receive(65535, SSRC, 2);
        receive(1, SSRC, 4);

        assertEquals(List.of(1, 2, 3, 4), consumer.audioMarkers);
    }

    @Test
    void ignoresLateAndDuplicatePackets() throws Exception {
        receive(100, SSRC, 1);
        receive(100, SSRC, 9);
        receive(101, SSRC, 2);
        receive(101, SSRC, 8);

        assertEquals(List.of(1, 2), consumer.audioMarkers);
    }

    @Test
    void resumesAfterMissingPacketExceedsReorderWindow() throws Exception {
        receive(100, SSRC, 100);
        for (int sequence = 102; sequence <= 165; sequence++) {
            receive(sequence, SSRC, sequence);
        }

        List<Integer> expected = new ArrayList<>();
        expected.add(100);
        for (int sequence = 102; sequence <= 165; sequence++) {
            expected.add(sequence & 0xFF);
        }
        assertEquals(expected, consumer.audioMarkers);
    }

    @Test
    void ignoresUnexpectedSsrcUntilTheStreamIsExplicitlyReset() throws Exception {
        receive(300, SSRC, 1);
        receive(10, SSRC + 1, 2);
        handler.reset("new stream SETUP");
        receive(10, SSRC + 1, 3);

        assertEquals(List.of(1, 3), consumer.audioMarkers);
    }

    @Test
    void oneFarFuturePacketDoesNotForceTheStreamToSkipAhead() throws Exception {
        receive(100, SSRC, 1);
        receive(700, SSRC, 2);
        receive(101, SSRC, 3);

        assertEquals(List.of(1, 3), consumer.audioMarkers);
    }

    @Test
    void resumesAfterEightContinuousPacketsBeyondTheReorderWindow() throws Exception {
        receive(100, SSRC, 1);
        for (int sequence = 700; sequence <= 706; sequence++) {
            receive(sequence, SSRC, sequence);
        }
        assertEquals(List.of(1), consumer.audioMarkers);

        receive(707, SSRC, 707);

        assertEquals(List.of(1, 700 & 0xFF, 701 & 0xFF, 702 & 0xFF, 703 & 0xFF,
                704 & 0xFF, 705 & 0xFF, 706 & 0xFF, 707 & 0xFF), consumer.audioMarkers);
    }

    @Test
    void recoversFromMissingPacketAcrossSequenceWrap() throws Exception {
        receive(65533, SSRC, 1);
        receive(65535, SSRC, 3);
        receive(0, SSRC, 4);
        receive(1, SSRC, 5);
        receive(2, SSRC, 6);
        receive(3, SSRC, 7);
        receive(4, SSRC, 8);
        receive(5, SSRC, 9);
        receive(6, SSRC, 10);

        assertEquals(List.of(1, 3, 4, 5, 6, 7, 8, 9, 10), consumer.audioMarkers);
    }

    @Test
    void explicitResetAcceptsASequenceRestartWithSameSsrc() throws Exception {
        receive(300, SSRC, 1);
        receive(10, SSRC, 2);
        handler.reset("test reset");
        receive(10, SSRC, 3);

        assertEquals(List.of(1, 3), consumer.audioMarkers);
    }

    private void receive(int sequenceNumber, long ssrc, int marker) throws Exception {
        AudioPacket packet = AudioPacket.builder()
                .available(true)
                .sequenceNumber(sequenceNumber)
                .ssrc(ssrc)
                .encodedAudioSize(1)
                .build();
        packet.getEncodedAudio()[0] = (byte) marker;
        handler.channelRead(null, packet);
    }

    private static final class PassthroughAirPlay extends AirPlay {
        @Override
        public void decryptAudio(byte[] audio, int audioLength) {
            // Packet ordering is tested independently from FairPlay decryption.
        }
    }

    private static final class RecordingConsumer implements AirPlayConsumer {
        private final List<Integer> audioMarkers = new ArrayList<>();

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        }

        @Override
        public void onVideo(byte[] bytes) {
        }

        @Override
        public void onVideoSrcDisconnect() {
        }

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        }

        @Override
        public void onAudio(byte[] bytes) {
            audioMarkers.add(bytes[0] & 0xFF);
        }

        @Override
        public void onAudioSrcDisconnect() {
        }
    }
}
