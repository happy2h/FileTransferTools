package com.example.ftc.config;

import com.example.ftc.handler.impl.RecvFileResponseHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Handler Bean Configuration
 *
 * Provides named beans for handlers that need to be referenced by name
 */
@Configuration
public class HandlerConfig {

    /**
     * Shared EventLoopGroup for both server and client
     */
    @Bean
    @Primary
    public EventLoopGroup workerGroup(FtsProperties properties) {
        return new NioEventLoopGroup(properties.getWorkerThreads());
    }

    @Bean("outboundRecvHandler")
    public RecvFileResponseHandler outboundRecvHandler(RecvFileResponseHandler recvFileResponseHandler) {
        return recvFileResponseHandler;
    }
}
