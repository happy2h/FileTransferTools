package com.example.ftc.server;

import com.example.ftc.config.FtsProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Netty TCP Server Bootstrap
 *
 * Starts the Netty server on startup and shuts it down gracefully on exit
 */
@Component
public class NettyServerBootstrap implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NettyServerBootstrap.class);

    @Autowired
    private FtsProperties properties;

    @Autowired
    private FtsChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("Starting Netty Server on port {}", properties.getPort());

        // Boss group - accepts incoming connections
        bossGroup = new NioEventLoopGroup(1);

        // Worker group - handles I/O events for accepted connections
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());

        // Configure server
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(channelInitializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);

        // Bind and start
        ChannelFuture channelFuture = bootstrap.bind(properties.getPort()).sync();
        serverChannel = channelFuture.channel();

        log.info("Netty Server started successfully on port {}", properties.getPort());
    }

    @Override
    public void destroy() throws Exception {
        log.info("Shutting down Netty Server");

        if (serverChannel != null) {
            serverChannel.close().sync();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        log.info("Netty Server shut down complete");
    }
}
