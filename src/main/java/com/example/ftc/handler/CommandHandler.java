package com.example.ftc.handler;

import io.netty.channel.ChannelHandlerContext;

/**
 * Generic interface for all command handlers
 *
 * @param <REQ>  Request type
 * @param <RESP> Response type
 */
public interface CommandHandler<REQ, RESP> {

    /**
     * Get the COMMAND name this handler processes (trimmed and uppercase)
     */
    String command();

    /**
     * Get the request class for JSON deserialization
     */
    Class<REQ> requestType();

    /**
     * Execute business logic
     *
     * @param request Request object
     * @param ctx     ChannelHandlerContext
     * @return Response object, or null if response will be written asynchronously (e.g., relay scenario)
     */
    RESP handle(REQ request, ChannelHandlerContext ctx);
}
