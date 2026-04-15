package com.example.ftc.client;

import com.example.ftc.config.FtsProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FtsClientBootstrapFactory - 出站 Bootstrap 工厂
 *
 * 提供出站 Bootstrap，复用 workerGroup
 */
@Component
public class FtsClientBootstrapFactory {

    private static final Logger log = LoggerFactory.getLogger(FtsClientBootstrapFactory.class);

    @Autowired
    private EventLoopGroup workerGroup;

    @Autowired
    private OutboundChannelInitializer outboundInitializer;

    @Autowired
    private FtsProperties properties;

    /**
     * 每次调用返回一个新的 Bootstrap 实例（Bootstrap 是轻量对象，可复用 workerGroup）
     */
    public Bootstrap bootstrap() {
        return new Bootstrap()
                .group(workerGroup)                          // 复用，不新建线程池
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(outboundInitializer);
    }
}
