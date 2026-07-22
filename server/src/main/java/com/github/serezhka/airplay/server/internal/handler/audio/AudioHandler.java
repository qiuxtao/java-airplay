package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
@RequiredArgsConstructor
public class AudioHandler extends ChannelInboundHandlerAdapter {

    private static final int SEQUENCE_MASK = 0xFFFF;
    private static final int HALF_SEQUENCE_RANGE = 0x8000;
    private static final int MAX_BUFFERED_REORDER_PACKETS = 8;
    private static final int BUFFER_SIZE = 512;

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;

    private final AudioPacket[] buffer = new AudioPacket[BUFFER_SIZE];
    private final AudioPacket[] resyncCandidates = new AudioPacket[MAX_BUFFERED_REORDER_PACKETS];

    private boolean sequenceInitialized;
    private int nextSequenceNumber;
    private long streamSsrc;
    private int packetsInBuffer;
    private long receivedPackets;
    private long deliveredPackets;
    private long lateOrDuplicatePackets;
    private long skippedPackets;
    private long outOfWindowPackets;
    private long unexpectedSsrcPackets;
    private int resyncCandidateCount;
    private int lastResyncCandidateSequence;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        AudioPacket packet = (AudioPacket) msg;
        receivedPackets++;

        int sequenceNumber = packet.getSequenceNumber() & SEQUENCE_MASK;
        if (!sequenceInitialized) {
            initializeSequence(sequenceNumber, packet.getSsrc());
        } else if (packet.getSsrc() != streamSsrc) {
            clearResyncCandidates();
            unexpectedSsrcPackets++;
            if (unexpectedSsrcPackets == 1 || unexpectedSsrcPackets % 128 == 0) {
                log.warn("Ignoring audio RTP packet from unexpected SSRC {} while receiving SSRC {} "
                                + "(ignored={})",
                        Long.toUnsignedString(packet.getSsrc()), Long.toUnsignedString(streamSsrc),
                        unexpectedSsrcPackets);
            }
            return;
        }

        int distance = forwardDistance(nextSequenceNumber, sequenceNumber);
        if (distance >= HALF_SEQUENCE_RANGE) {
            clearResyncCandidates();
            lateOrDuplicatePackets++;
            log.debug("Ignoring late or duplicate audio RTP packet: sequence={}, expected={}, distance={}",
                    sequenceNumber, nextSequenceNumber, distance);
            return;
        }

        if (distance >= buffer.length) {
            handleOutOfWindowPacket(packet, sequenceNumber, distance);
            return;
        }
        clearResyncCandidates();

        if (!store(packet, sequenceNumber)) {
            return;
        }

        drainContiguousPackets();
        if (packetsInBuffer >= MAX_BUFFERED_REORDER_PACKETS) {
            recoverFromGap();
            drainContiguousPackets();
        }
    }

    /**
     * Resets RTP ordering for an existing UDP listener. Must run on the audio channel event loop.
     */
    public void reset(String reason) {
        if (sequenceInitialized || receivedPackets > 0) {
            log.info("Resetting audio RTP packet order ({}): received={}, delivered={}, skipped={}, "
                            + "lateOrDuplicate={}, outOfWindow={}, unexpectedSsrc={}, buffered={}, "
                            + "resyncBuffered={}",
                    reason, receivedPackets, deliveredPackets, skippedPackets,
                    lateOrDuplicatePackets, outOfWindowPackets, unexpectedSsrcPackets,
                    packetsInBuffer, resyncCandidateCount);
        }
        clearOrderingState();
        receivedPackets = 0;
        deliveredPackets = 0;
        lateOrDuplicatePackets = 0;
        skippedPackets = 0;
        outOfWindowPackets = 0;
        unexpectedSsrcPackets = 0;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        reset("audio server stopped");
        super.handlerRemoved(ctx);
    }

    private void initializeSequence(int sequenceNumber, long ssrc) {
        sequenceInitialized = true;
        nextSequenceNumber = sequenceNumber;
        streamSsrc = ssrc;
        log.info("Audio RTP stream started: sequence={}, SSRC={}",
                sequenceNumber, Long.toUnsignedString(ssrc));
    }

    private boolean store(AudioPacket packet, int sequenceNumber) {
        int index = sequenceNumber % buffer.length;
        AudioPacket buffered = buffer[index];
        if (buffered != null && buffered.isAvailable()) {
            if ((buffered.getSequenceNumber() & SEQUENCE_MASK) == sequenceNumber) {
                lateOrDuplicatePackets++;
                log.debug("Ignoring duplicate buffered audio RTP packet: sequence={}", sequenceNumber);
                return false;
            }
            log.warn("Replacing stale audio RTP packet in reorder buffer: oldSequence={}, newSequence={}",
                    buffered.getSequenceNumber() & SEQUENCE_MASK, sequenceNumber);
            buffered.available(false);
            packetsInBuffer--;
        }
        buffer[index] = packet;
        packetsInBuffer++;
        return true;
    }

    private void handleOutOfWindowPacket(AudioPacket packet,
                                         int sequenceNumber,
                                         int distance) throws Exception {
        outOfWindowPackets++;
        if (outOfWindowPackets == 1 || outOfWindowPackets % 128 == 0) {
            log.warn("Audio RTP packet is beyond the reorder window: expected={}, received={}, "
                            + "distance={}, observed={}; waiting for a continuous stream before resyncing",
                    nextSequenceNumber, sequenceNumber, distance, outOfWindowPackets);
        }

        if (resyncCandidateCount > 0
                && sequenceNumber != addSequence(lastResyncCandidateSequence, 1)) {
            clearResyncCandidates();
        }

        resyncCandidates[resyncCandidateCount++] = packet;
        lastResyncCandidateSequence = sequenceNumber;
        if (resyncCandidateCount < resyncCandidates.length) {
            return;
        }

        AudioPacket[] confirmedPackets = Arrays.copyOf(resyncCandidates, resyncCandidateCount);
        int resumeSequence = confirmedPackets[0].getSequenceNumber() & SEQUENCE_MASK;
        int skippedDistance = forwardDistance(nextSequenceNumber, resumeSequence);
        Arrays.fill(resyncCandidates, null);
        resyncCandidateCount = 0;
        lastResyncCandidateSequence = 0;

        skippedPackets += skippedDistance;
        log.warn("Audio RTP stream resumed beyond the reorder window: expected={}, resumingAt={}; "
                        + "skipping {} packet(s) after {} continuous packets confirmed the stream",
                nextSequenceNumber, resumeSequence, skippedDistance, confirmedPackets.length);

        clearBufferedPackets();
        nextSequenceNumber = resumeSequence;
        for (AudioPacket confirmedPacket : confirmedPackets) {
            store(confirmedPacket, confirmedPacket.getSequenceNumber() & SEQUENCE_MASK);
        }
        drainContiguousPackets();
    }

    private void recoverFromGap() {
        for (int offset = 1; offset < buffer.length; offset++) {
            int candidateSequence = addSequence(nextSequenceNumber, offset);
            AudioPacket candidate = buffer[candidateSequence % buffer.length];
            if (isPacket(candidate, candidateSequence)) {
                skippedPackets += offset;
                log.warn("Audio RTP packet gap did not recover: expected={}, resumingAt={}; "
                                + "skipping {} missing packet(s)",
                        nextSequenceNumber, candidateSequence, offset);
                nextSequenceNumber = candidateSequence;
                return;
            }
        }
    }

    private void drainContiguousPackets() throws Exception {
        while (true) {
            int index = nextSequenceNumber % buffer.length;
            AudioPacket packet = buffer[index];
            if (!isPacket(packet, nextSequenceNumber)) {
                return;
            }

            int deliveredSequence = nextSequenceNumber;
            airPlay.decryptAudio(packet.getEncodedAudio(), packet.getEncodedAudioSize());
            dataConsumer.onAudio(Arrays.copyOfRange(
                    packet.getEncodedAudio(), 0, packet.getEncodedAudioSize()));

            packet.available(false);
            buffer[index] = null;
            packetsInBuffer--;
            deliveredPackets++;
            nextSequenceNumber = addSequence(nextSequenceNumber, 1);

            if (deliveredSequence == SEQUENCE_MASK) {
                log.info("Audio RTP sequence wrapped normally from 65535 to 0: received={}, delivered={}, "
                                + "skipped={}, lateOrDuplicate={}",
                        receivedPackets, deliveredPackets, skippedPackets, lateOrDuplicatePackets);
            }
        }
    }

    private boolean isPacket(AudioPacket packet, int sequenceNumber) {
        return packet != null
                && packet.isAvailable()
                && (packet.getSequenceNumber() & SEQUENCE_MASK) == sequenceNumber;
    }

    private void clearOrderingState() {
        clearBufferedPackets();
        clearResyncCandidates();
        sequenceInitialized = false;
        nextSequenceNumber = 0;
        streamSsrc = 0;
    }

    private void clearBufferedPackets() {
        for (AudioPacket packet : buffer) {
            if (packet != null) {
                packet.available(false);
            }
        }
        Arrays.fill(buffer, null);
        packetsInBuffer = 0;
    }

    private void clearResyncCandidates() {
        for (int index = 0; index < resyncCandidateCount; index++) {
            AudioPacket packet = resyncCandidates[index];
            if (packet != null) {
                packet.available(false);
            }
            resyncCandidates[index] = null;
        }
        resyncCandidateCount = 0;
        lastResyncCandidateSequence = 0;
    }

    private static int forwardDistance(int from, int to) {
        return (to - from) & SEQUENCE_MASK;
    }

    private static int addSequence(int sequenceNumber, int amount) {
        return (sequenceNumber + amount) & SEQUENCE_MASK;
    }
}
