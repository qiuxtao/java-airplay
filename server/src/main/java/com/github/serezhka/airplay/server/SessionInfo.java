package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.MediaStreamInfo;

import java.net.InetSocketAddress;
import java.util.Set;

public record SessionInfo(String id,
                          InetSocketAddress remoteAddress,
                          Set<MediaStreamInfo.StreamType> streams) {

    public SessionInfo {
        streams = Set.copyOf(streams);
    }
}
