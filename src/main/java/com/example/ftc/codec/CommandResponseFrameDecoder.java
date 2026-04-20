package com.example.ftc.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * CommandResponseFrameDecoder - 响应帧解码（出站 Channel 接收 Node B 的响应）
 *
 * Protocol:
 * - RESP_LENGTH: 8 bytes, long, Big-Endian
 * - RESP_BODY: N bytes, JSON
 *
 * ByteToMessageDecoder maintains per-channel state; must be instantiated per channel.
 */
public class CommandResponseFrameDecoder extends ByteToMessageDecoder {

    private static final int LENGTH_LENGTH = 8;

    private long bodyLength;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // Check if we have enough bytes for the length field
            if (in.readableBytes() < LENGTH_LENGTH) {
                return;
            }

            // Mark the current position to rollback if needed
            in.markReaderIndex();

            // Read body length
            bodyLength = in.readLong();

            // Validate body length (security check)
            if (bodyLength < 0 || bodyLength > 10 * 1024 * 1024) { // Max 10MB
                // Invalid length, close the connection
                ctx.close();
                return;
            }

            // Check if we have the full body
            if (in.readableBytes() < bodyLength) {
                // Not enough bytes yet, reset reader index and wait for more data
                in.resetReaderIndex();
                return;
            }

            // Read the body
            byte[] body = new byte[(int) bodyLength];
            in.readBytes(body);
            out.add(body);

        } catch (Exception e) {
            ctx.close();
        }
    }
}
