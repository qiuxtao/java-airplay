package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.AirPlayServerListener;
import com.github.serezhka.airplay.server.SessionInfo;
import com.github.serezhka.airplay.server.internal.handler.control.ControlHandler;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class ControlServer {

    private final SessionManager sessionManager;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Getter
    private int port;

    public ControlServer(AirPlayConfig airPlayConfig,
                         AirPlayConsumer airPlayConsumer,
                         AirPlayServerListener listener) {
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        sessionManager = new SessionManager(airPlayConsumer, listener);
    }

    public synchronized void start() throws InterruptedException {
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
                            channel.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.DEBUG, ByteBufFormat.SIMPLE),
                                    new ControlHandler(sessionManager, airPlayConfig, airPlayConsumer));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .bind()
                    .sync()
                    .channel();

            port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            log.info("AirPlay control server listening on port: {}", port);
        } catch (InterruptedException | RuntimeException error) {
            shutdownGroups();
            throw error;
        }
    }

    public synchronized void stop() {
        sessionManager.closeAll();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
        port = 0;
        log.info("AirPlay control server stopped");
    }

    public void disconnectActiveSession() {
        sessionManager.disconnectActiveSession();
    }

    public Optional<SessionInfo> activeSession() {
        return sessionManager.activeSession();
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return new NioEventLoopGroup();
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return NioServerSocketChannel.class;
    }
}
