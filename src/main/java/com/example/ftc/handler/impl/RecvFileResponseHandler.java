package com.example.ftc.handler.impl;

import com.example.ftc.model.AttributeKeys;
import com.example.ftc.model.CommandFrame;
import com.example.ftc.model.ReceiveFileRequest;
import com.example.ftc.model.ReceiveFileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * RecvFileResponseHandler - 出站 Channel 的响应处理，resolve Promise
 */
@Component
public class RecvFileResponseHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(RecvFileResponseHandler.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接 Node B 成功后，立即发送 RECVFILE 指令
        ReceiveFileRequest req = ctx.channel().attr(AttributeKeys.REQUEST_KEY).get();
        if (req != null) {
            try {
                byte[] reqBytes = objectMapper.writeValueAsBytes(req);
                CommandFrame frame = new CommandFrame("RECVFILE", reqBytes);
                ctx.writeAndFlush(frame);
                log.info("Sent RECVFILE to Node B: {}", ctx.channel().remoteAddress());
            } catch (Exception e) {
                log.error("Failed to send RECVFILE", e);
                ctx.close();
            }
        } else {
            log.warn("No ReceiveFileRequest found in channel attributes");
            ctx.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] responseBody) {
        Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
        if (promise == null) {
            log.warn("No Promise found in channel attributes");
            ctx.close();
            return;
        }

        try {
            ReceiveFileResponse resp = objectMapper.readValue(responseBody, ReceiveFileResponse.class);
            promise.setSuccess(resp);
            log.info("ReceivedRECVFILE response from Node B: success={}, files={}",
                    resp.isSuccess(), resp.getFiles() != null ? resp.getFiles().size() : 0);
        } catch (Exception e) {
            log.error("Failed to decode ReceiveFileResponse", e);
            promise.setFailure(e);
        } finally {
            // 出站连接用完即关，不复用
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
        if (promise != null && !promise.isDone()) {
            promise.setFailure(new IOException("Connection to Node B closed unexpectedly"));
            log.warn("Connection to Node B closed unexpectedly");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
        if (promise != null && !promise.isDone()) {
            promise.setFailure(cause);
        }
        log.error("Exception on outbound channel", cause);
        ctx.close();
    }
}
