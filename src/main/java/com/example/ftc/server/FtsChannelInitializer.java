package com.example.ftc.server;

import com.example.ftc.codec.CommandFrameDecoder;
import com.example.ftc.codec.ResponseEncoder;
import com.example.ftc.config.FtsProperties;
import com.example.ftc.handler.CommandDispatchHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Netty Channel Pipeline Initializer
 *
 * Sets up the pipeline for each incoming connection
 */
@Component
public class FtsChannelInitializer extends ChannelInitializer<SocketChannel> {

    @Autowired
    private FtsProperties properties;

    @Autowired
    private CommandDispatchHandler commandDispatchHandler;

    @Autowired
    private ResponseEncoder responseEncoder;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Read timeout handler - closes connection if no data for specified time
        pipeline.addLast("readTimeout", new ReadTimeoutHandler(
                properties.getReadTimeoutSeconds(), TimeUnit.SECONDS));

        // Custom frame decoder - handles TCP packet fragmentation/merging
        pipeline.addLast("frameDecoder", new CommandFrameDecoder());

        // Response encoder - encodes response objects to binary format
        // Must be before commandDispatcher so outbound messages pass through it
        pipeline.addLast("responseEncoder", responseEncoder);

        // Command dispatcher - routes commands to appropriate handlers
        pipeline.addLast("commandDispatcher", commandDispatchHandler);
    }
}
