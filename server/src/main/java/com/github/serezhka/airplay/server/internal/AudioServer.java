package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public final class AudioServer {

    private final AirPlay airPlay;
    private EventLoopGroup workerGroup;
    private Channel channel;

    @Getter
    private int port;

    public synchronized void start(AirPlayConsumer consumer) throws InterruptedException {
        if (channel != null && channel.isOpen()) {
            return;
        }
        workerGroup = eventLoopGroup();
        try {
            channel = new Bootstrap()
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(DatagramChannel channel) {
                            channel.pipeline().addLast("audioDecoder", new DatagramPacketDecoder(new AudioDecoder()));
                            channel.pipeline().addLast("audioHandler", new AudioHandler(airPlay, consumer));
                        }
                    })
                    .bind()
                    .sync()
                    .channel();
            port = ((InetSocketAddress) channel.localAddress()).getPort();
            log.info("AirPlay audio server listening on port: {}", port);
        } catch (InterruptedException | RuntimeException error) {
            stop();
            throw error;
        }
    }

    public synchronized void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        port = 0;
    }

    private EventLoopGroup eventLoopGroup() {
        return new NioEventLoopGroup();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return NioDatagramChannel.class;
    }
}
