package com.example.ftc.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encodes response objects to binary format
 *
 * Protocol:
 * - RESP_LENGTH: 8 bytes, long, Big-Endian
 * - RESP_BODY: N bytes, JSON
 */
@Component
@ChannelHandler.Sharable
public class ResponseEncoder extends MessageToByteEncoder<Object> {

    private static final Logger log = LoggerFactory.getLogger(ResponseEncoder.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        try {
            byte[] json = objectMapper.writeValueAsBytes(msg);
            out.writeLong(json.length);
            out.writeBytes(json);

            log.trace("Encoded response: {} bytes", json.length);
        } catch (Exception e) {
            log.error("Error encoding response", e);
            throw e;
        }
    }

    @Override
    public boolean acceptOutboundMessage(Object msg) {
        return true;
    }
}
