package com.example.ftc.model;

import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

/**
 * AttributeKeys - Channel 上下文传递的 Key 常量
 */
public final class AttributeKeys {

    /** 出站 Channel 携带的 Promise，用于接收 Node B 的响应 */
    public static final AttributeKey<Promise<ReceiveFileResponse>> PROMISE_KEY =
            AttributeKey.valueOf("recvFilePromise");

    /** 出站 Channel 携带的请求体 */
    public static final AttributeKey<ReceiveFileRequest> REQUEST_KEY =
            AttributeKey.valueOf("recvFileRequest");

    private AttributeKeys() {}
}
