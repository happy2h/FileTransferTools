package com.example.ftc.codec;

import com.example.ftc.model.CommandFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * CommandFrameEncoder - 出站帧编码（Node A → Node B）
 *
 * Encodes CommandFrame to binary format:
 * - COMMAND: 8 bytes, ASCII (right-padded with spaces)
 * - BODY_LENGTH: 8 bytes, long, Big-Endian
 * - BODY: N bytes, JSON
 */
@Component
@ChannelHandler.Sharable
public class CommandFrameEncoder extends MessageToByteEncoder<CommandFrame> {

    private static final int COMMAND_LENGTH = 8;

    @Override
    protected void encode(ChannelHandlerContext ctx, CommandFrame frame, ByteBuf out) {
        // COMMAND：固定 8 字节，右侧补空格
        byte[] cmdBytes = new byte[COMMAND_LENGTH];
        Arrays.fill(cmdBytes, (byte) ' ');
        byte[] src = frame.getCommand().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, cmdBytes, 0, Math.min(src.length, COMMAND_LENGTH));
        out.writeBytes(cmdBytes);

        // BODY_LENGTH：8 字节 Big-Endian long
        out.writeLong(frame.getBody().length);

        // BODY
        out.writeBytes(frame.getBody());
    }
}
