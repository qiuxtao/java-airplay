package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.lib.MediaStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.SessionInfo;
import com.github.serezhka.airplay.server.internal.AudioControlServer;
import com.github.serezhka.airplay.server.internal.AudioServer;
import com.github.serezhka.airplay.server.internal.VideoServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class Session {

    private final String id;
    private final InetSocketAddress remoteAddress;
    private final AirPlay airPlay = new AirPlay();
    private final VideoServer videoServer = new VideoServer(airPlay);
    private final AudioServer audioServer = new AudioServer(airPlay);
    private final AudioControlServer audioControlServer = new AudioControlServer();
    private final Map<String, ChannelHandlerContext> reverseContexts = new ConcurrentHashMap<>();
    private final Map<String, ChannelHandlerContext> playlistRequestContexts = new ConcurrentHashMap<>();
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    private final Set<MediaStreamInfo.StreamType> streams = Collections.synchronizedSet(
            EnumSet.noneOf(MediaStreamInfo.StreamType.class));

    Session(String id, InetSocketAddress remoteAddress) {
        this.id = id;
        this.remoteAddress = remoteAddress;
    }

    public SessionInfo info() {
        synchronized (streams) {
            return new SessionInfo(id, remoteAddress, Set.copyOf(streams));
        }
    }

    public void addChannel(Channel channel) {
        channels.add(channel);
    }

    public void addStream(MediaStreamInfo.StreamType streamType) {
        streams.add(streamType);
    }

    public void removeStream(MediaStreamInfo.StreamType streamType) {
        streams.remove(streamType);
    }

    public boolean hasStreams() {
        return !streams.isEmpty();
    }

    public void stop(AirPlayConsumer consumer, boolean closeChannels) {
        if (streams.remove(MediaStreamInfo.StreamType.AUDIO)) {
            consumer.onAudioSrcDisconnect();
        }
        if (streams.remove(MediaStreamInfo.StreamType.VIDEO)) {
            consumer.onVideoSrcDisconnect();
        }
        audioServer.stop();
        audioControlServer.stop();
        videoServer.stop();
        if (closeChannels) {
            channels.forEach(Channel::close);
        }
        channels.clear();
        reverseContexts.clear();
        playlistRequestContexts.clear();
    }
}
