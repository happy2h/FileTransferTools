package com.example.ftc.handler.impl;

import com.example.ftc.client.FtsClientBootstrapFactory;
import com.example.ftc.config.FtsProperties;
import com.example.ftc.handler.CommandHandler;
import com.example.ftc.model.AttributeKeys;
import com.example.ftc.model.ReceiveFileRequest;
import com.example.ftc.model.ReceiveFileResponse;
import com.example.ftc.model.SendFileRequest;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.service.FileScanner;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handler for SENDFILE command
 *
 * Supports both local scanning and P2P relay to another FTS node
 */
@Component
public class SendFileHandler implements CommandHandler<SendFileRequest, SendFileResponse> {

    private static final Logger log = LoggerFactory.getLogger(SendFileHandler.class);

    @Autowired
    private FileScanner fileScanner;

    @Autowired
    private FtsProperties properties;

    @Autowired
    private FtsClientBootstrapFactory clientFactory;

    @Override
    public String command() {
        return "SENDFILE";
    }

    @Override
    public Class<SendFileRequest> requestType() {
        return SendFileRequest.class;
    }

    @Override
    public SendFileResponse handle(SendFileRequest request, ChannelHandlerContext ctx) {
        long startTime = System.currentTimeMillis();

        try {
            // ── 路径一：无目标节点，本地扫描 ──────────────────────────────────────
            if (request.getDstFileServeIp() == null || request.getDstFileServeIp().isBlank()) {
                return handleLocalScan(request, startTime);
            }

            // ── 路径二：P2P 中继 ──────────────────────────────────────────────────
            return handleRelay(request, ctx);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Error handling SENDFILE command after {} ms", elapsed, e);
            return SendFileResponse.error("Internal error: " + e.getMessage());
        }
    }

    /**
     * 处理本地扫描
     */
    private SendFileResponse handleLocalScan(SendFileRequest request, long startTime) {
        try {
            // Validate request
            if (request.getScanDirectory() == null || request.getScanDirectory().isEmpty()) {
                log.warn("Invalid request: scan directory is empty");
                return SendFileResponse.error("Scan directory cannot be empty");
            }

            // Security: Check if directory is in allowed list
            Path requestedPath = Paths.get(request.getScanDirectory()).normalize().toAbsolutePath();
            boolean isAllowed = false;

            for (Path allowedBase : properties.getAllowedDirectoryPaths()) {
                if (requestedPath.startsWith(allowedBase)) {
                    isAllowed = true;
                    break;
                }
            }

            if (!isAllowed) {
                log.warn("Access denied: directory not in allowed list: {}", requestedPath);
                return SendFileResponse.error("Access denied: directory not in allowed list");
            }

            // Perform scan
            FileScanner.ScanResult result = fileScanner.scan(
                    request.getScanDirectory(),
                    request.getFilePattern(),
                    request.isRecursive(),
                    request.getMaxFileSizeBytes(),
                    properties.getMaxScanResults()
            );

            if (!result.isSuccess()) {
                log.warn("Scan failed: {}", result.getErrorMessage());
                return SendFileResponse.error(result.getErrorMessage());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Local scan completed successfully: {} files found in {} ms, truncated: {}",
                    result.getFiles().size(), elapsed, result.isTruncated());

            return SendFileResponse.success(result.getFiles(), result.isTruncated());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Error in local scan after {} ms", elapsed, e);
            return SendFileResponse.error("Internal error: " + e.getMessage());
        }
    }

    /**
     * 处理 P2P 中继到另一个节点
     */
    private SendFileResponse handleRelay(SendFileRequest request, ChannelHandlerContext inboundCtx) {
        String targetIp = request.getDstFileServeIp();
        int targetPort = request.getDstFileServePort() > 0 ? request.getDstFileServePort() : 7111;

        log.info("Relaying SENDFILE to Node B: {}/{}", targetIp, targetPort);

        // 1. 创建 Promise，绑定到 inbound Channel 所在的 EventLoop
        //    确保 Listener 回调在正确线程上执行，无需额外同步
        Promise<ReceiveFileResponse> promise = inboundCtx.executor().newPromise();

        // 2. 注册 Promise 完成监听：收到 Node B 响应后，写回 Remote Service
        promise.addListener((Future<ReceiveFileResponse> f) -> {
            SendFileResponse resp;
            if (f.isSuccess()) {
                resp = convertToSendFileResponse(f.getNow());
                log.info("Relay completed successfully, files={}", resp.getFiles() != null ? resp.getFiles().size() : 0);
            } else {
                resp = SendFileResponse.error("Relay failed: " + f.cause().getMessage());
                log.error("Relay failed", f.cause());
            }
            // 此 Listener 运行在 inbound Channel 的 EventLoop 上，直接 writeAndFlush 安全
            if (inboundCtx.channel().isActive()) {
                inboundCtx.writeAndFlush(resp);
            }
        });

        // 3. 构造出站请求
        ReceiveFileRequest recvReq = ReceiveFileRequest.from(request);

        // 4. 发起出站连接（异步，不阻塞当前 WorkerThread）
        clientFactory.bootstrap()
                .attr(AttributeKeys.PROMISE_KEY, promise)
                .attr(AttributeKeys.REQUEST_KEY, recvReq)
                .connect(targetIp, targetPort)
                .addListener((ChannelFuture cf) -> {
                    if (!cf.isSuccess()) {
                        // 连接失败，直接 fail Promise，触发上面的 Listener 写回错误响应
                        log.error("Failed to connect to Node B {}/{}: {}", targetIp, targetPort, cf.cause().getMessage());
                        promise.setFailure(cf.cause());
                    }
                    // 连接成功时，OutboundChannelInitializer 中的 channelActive
                    // 会自动发送 RECVFILE 指令
                });

        // 5. 返回 null，告知框架响应将异步写入，本次调用无需同步返回值
        return null;
    }

    /**
     * 将 ReceiveFileResponse 转换为 SendFileResponse
     */
    private SendFileResponse convertToSendFileResponse(ReceiveFileResponse recvResp) {
        SendFileResponse resp = new SendFileResponse();
        resp.setSuccess(recvResp.isSuccess());
        resp.setErrorMessage(recvResp.getErrorMessage());
        resp.setTruncated(recvResp.isTruncated());
        resp.setFiles(recvResp.getFiles());
        return resp;
    }
}
