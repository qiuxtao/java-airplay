package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class AudioControlServer {

    private EventLoopGroup workerGroup;
    private Channel channel;

    @Getter
    private int port;

    public synchronized void start() throws InterruptedException {
        if (channel != null && channel.isOpen()) {
            return;
        }
        workerGroup = eventLoopGroup();
        try {
            channel = new Bootstrap()
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(DatagramChannel channel) {
                            channel.pipeline().addLast("audioControlHandler", new AudioControlHandler());
                        }
                    })
                    .bind()
                    .sync()
                    .channel();
            port = ((InetSocketAddress) channel.localAddress()).getPort();
            log.info("AirPlay audio control server listening on port: {}", port);
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
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(1, TimeUnit.SECONDS);
            workerGroup = null;
        }
        port = 0;
    }

    private EventLoopGroup eventLoopGroup() {
        return new NioEventLoopGroup(1);
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return NioDatagramChannel.class;
    }
}
