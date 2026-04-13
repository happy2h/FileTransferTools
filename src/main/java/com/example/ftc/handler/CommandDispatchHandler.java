package com.example.ftc.handler;

import com.example.ftc.config.FtsProperties;
import com.example.ftc.model.CommandFrame;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.registry.CommandRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 *   Routes commands to their respective handlers
 */
@Component
@io.netty.channel.ChannelHandler.Sharable
public class CommandDispatchHandler extends SimpleChannelInboundHandler<CommandFrame> {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatchHandler.class);

    @Autowired
    private CommandRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FtsProperties properties;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CommandFrame frame) {
        String command = frame.getCommand();
        byte[] body = frame.getBody();

        log.info("Received command: {}, body length: {} bytes", command, body.length);

        try {
            // Validate body size
            if (body.length > properties.getMaxBodySize()) {
                log.warn("Body size exceeds limit: {} > {}", body.length, properties.getMaxBodySize());
                ctx.writeAndFlush(SendFileResponse.error("Body size exceeds maximum allowed"))
                   .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            // Get handler
            CommandHandler<?, ?> handler = registry.getHandler(command);
            if (handler == null) {
                log.warn("Unknown command: {}", command);
                ctx.writeAndFlush(SendFileResponse.error("Unknown command: " + command))
                   .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            // Deserialize request and execute handler
            executeHandler(handler, body, ctx);

            log.info("Command {} processed successfully", command);

        } catch (Exception e) {
            log.error("Error processing command: {}", command, e);
            ctx.writeAndFlush(SendFileResponse.error("Internal server error: " + e.getMessage()))
               .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeHandler(CommandHandler<?, ?> handler, byte[] body, ChannelHandlerContext ctx)
            throws Exception {
        // Deserialize request
        Object request = objectMapper.readValue(body, handler.requestType());

        // Execute handler - raw type is intentional here due to generic type erasure
        CommandHandler<Object, Object> typedHandler = (CommandHandler<Object, Object>) handler;
        Object response = typedHandler.handle(request, ctx);
        ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ReadTimeoutException) {
            log.warn("Read timeout on channel: {}", ctx.channel().remoteAddress());
        } else if (cause instanceof DecoderException) {
            log.error("Decoder error on channel: {}", ctx.channel().remoteAddress(), cause);
        } else {
            log.error("Exception on channel: {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }

    @Override
       public void channelActive(ChannelHandlerContext ctx) {
        log.info("Client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Client disconnected: {}", ctx.channel().remoteAddress());
    }
}
