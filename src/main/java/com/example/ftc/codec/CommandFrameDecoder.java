package com.example.ftc.codec;

import com.example.ftc.model.CommandFrame;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Custom frame decoder for handling TCP packet fragmentation/merging
 *
 * Protocol:
 * - COMMAND: 8 bytes, ASCII string (right-padded with spaces)
 * - BODY_LENGTH: 8 bytes, long, Big-Endian
 * - BODY: N bytes, JSON
 */
public class CommandFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(CommandFrameDecoder.class);

    private static final int COMMAND_LENGTH = 8;
    private static final int LENGTH_LENGTH = 8;

    private enum State {
        READ_COMMAND,
        READ_LENGTH,
        READ_BODY
    }

    private State state = State.READ_COMMAND;
    private String command;
    private long bodyLength;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            switch (state) {
                case READ_COMMAND:
                    if (!readCommand(in, out)) {
                        return;
                    }
                    // Fall through to READ_LENGTH

                case READ_LENGTH:
                    if (!readLength(in, out)) {
                        return;
                    }
                    // If body length is 0, no need to go to READ_BODY
                    if (bodyLength == 0) {
                        out.add(new CommandFrame(command, new byte[0]));
                        resetState();
                        return;
                    }
                    // Fall through to READ_BODY

                case READ_BODY:
                    if (!readBody(in, out)) {
                        return;
                    }
                    break;
            }
        } catch (Exception e) {
            log.error("Error decoding frame", e);
            ctx.close();
        }
    }

    private boolean readCommand(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < COMMAND_LENGTH) {
            return false;
        }

        byte[] cmdBytes = new byte[COMMAND_LENGTH];
        in.readBytes(cmdBytes);
        command = new String(cmdBytes, StandardCharsets.US_ASCII).trim();
        state = State.READ_LENGTH;

        log.trace("Read command: {}", command);
        return true;
    }

    private boolean readLength(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < LENGTH_LENGTH) {
            return false;
        }

        bodyLength = in.readLong();
        state = State.READ_BODY;

        log.trace("Read body length: {}", bodyLength);
        return true;
    }

    private boolean readBody(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < bodyLength) {
            return false;
        }

        byte[] body = new byte[(int) bodyLength];
        in.readBytes(body);
        out.add(new CommandFrame(command, body));
        resetState();

        log.trace("Read body: {} bytes", body.length);
        return true;
    }

    private void resetState() {
        state = State.READ_COMMAND;
        command = null;
        bodyLength = 0;
    }
}
