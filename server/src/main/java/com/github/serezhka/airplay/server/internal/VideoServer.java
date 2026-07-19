package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.decoder.VideoDecoder;
import com.github.serezhka.airplay.server.internal.handler.video.VideoHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public final class VideoServer {

    private final AirPlay airPlay;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Getter
    private int port;

    public synchronized void start(AirPlayConsumer consumer) throws InterruptedException {
        if (serverChannel != null && serverChannel.isOpen()) {
            return;
        }
        bossGroup = eventLoopGroup();
        workerGroup = eventLoopGroup();
        try {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast("videoDecoder", new VideoDecoder());
                            channel.pipeline().addLast("videoHandler", new VideoHandler(airPlay, consumer));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .bind()
                    .sync()
                    .channel();
            port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            log.info("AirPlay video server listening on port: {}", port);
        } catch (InterruptedException | RuntimeException error) {
            stop();
            throw error;
        }
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
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

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return NioServerSocketChannel.class;
    }
}
