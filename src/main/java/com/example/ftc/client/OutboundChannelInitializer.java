package com.example.ftc.client;

import com.example.ftc.codec.CommandResponseFrameDecoder;
import com.example.ftc.codec.CommandFrameEncoder;
import com.example.ftc.handler.impl.RecvFileResponseHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * OutboundChannelInitializer - 出站 Pipeline 初始化
 *
 * 初始化出站（Client）Pipeline
 */
@Component
public class OutboundChannelInitializer extends ChannelInitializer<SocketChannel> {

    @Autowired
    private CommandResponseFrameDecoder commandResponseFrameDecoder;

    @Autowired
    private CommandFrameEncoder commandFrameEncoder;

    @Autowired
    @Qualifier("outboundRecvHandler")
    private RecvFileResponseHandler recvFileResponseHandler;

    @Autowired
    private com.example.ftc.config.FtsProperties properties;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new ReadTimeoutHandler(properties.getOutboundReadTimeoutSeconds(), TimeUnit.SECONDS))
                // 出站：将 CommandFrame 编码为 COMMAND(8B) + LENGTH(8B) + BODY(NB)
                .addLast("frameEncoder", commandFrameEncoder)
                // 入站：将 Node B 的响应帧解码为 byte[]（LENGTH(8B) + BODY(NB)）
                .addLast("responseDecoder", commandResponseFrameDecoder)
                // 入站：处理 byte[]，resolve Promise
                .addLast("recvResponseHandler", recvFileResponseHandler);
    }
}
