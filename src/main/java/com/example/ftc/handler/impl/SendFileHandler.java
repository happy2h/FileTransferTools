package com.example.ftc.handler.impl;

import com.example.ftc.client.FtsClientBootstrapFactory;
import com.example.ftc.config.FtsProperties;
import com.example.ftc.exception.DecompressException;
import com.example.ftc.handler.CommandHandler;
import com.example.ftc.model.AttributeKeys;
import com.example.ftc.model.DecompressConfig;
import com.example.ftc.model.ReceiveFileRequest;
import com.example.ftc.model.ReceiveFileResponse;
import com.example.ftc.model.SendFileRequest;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.service.FileDecompressor;
import com.example.ftc.service.FileScanner;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for SENDFILE command.
 * Supports both local scanning (with optional decompression) and P2P relay to another FTS node.
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

    @Autowired
    private FileDecompressor fileDecompressor;

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
            if (request.getDstFileServeIp() == null || request.getDstFileServeIp().isBlank()) {
                return handleLocalScan(request, startTime);
            }
            return handleRelay(request, ctx);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Error handling SENDFILE command after {} ms", elapsed, e);
            return SendFileResponse.error("Internal error: " + e.getMessage());
        }
    }

    private SendFileResponse handleLocalScan(SendFileRequest request, long startTime) {
        try {
            if (request.getScanDirectory() == null || request.getScanDirectory().isEmpty()) {
                log.warn("Invalid request: scan directory is empty");
                return SendFileResponse.error("Scan directory cannot be empty");
            }

            Path requestedPath = Paths.get(request.getScanDirectory()).normalize().toAbsolutePath();
            boolean isAllowed = properties.getAllowedDirectoryPaths().stream()
                    .anyMatch(requestedPath::startsWith);

            if (!isAllowed) {
                log.warn("Access denied: directory not in allowed list: {}", requestedPath);
                return SendFileResponse.error("Access denied: directory not in allowed list");
            }

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

            // ── 解压步骤 ──────────────────────────────────────────────────────────
            DecompressConfig cfg = request.getDecompressConfig();
            if (cfg != null) {
                // 校验 targetDirectory 在 allowedDirectories 内
                if (cfg.getTargetDirectory() != null && !cfg.getTargetDirectory().isBlank()) {
                    Path target = Path.of(cfg.getTargetDirectory()).normalize().toAbsolutePath();
                    boolean targetAllowed = properties.getAllowedDirectoryPaths().stream()
                            .anyMatch(target::startsWith);
                    if (!targetAllowed) {
                        return SendFileResponse.error("Target directory not in allowed list: " + cfg.getTargetDirectory());
                    }
                }

                List<SendFileResponse.FileEntry> finalFiles = new ArrayList<>();
                for (SendFileResponse.FileEntry entry : result.getFiles()) {
                    if (matchesCompressPatterns(entry.getFileName(), cfg.getCompressPatterns())) {
                        try {
                            List<SendFileResponse.FileEntry> extracted =
                                    fileDecompressor.decompress(entry, cfg, properties);
                            finalFiles.addAll(extracted);
                            if (!cfg.isDeleteSourceAfterExtract()) {
                                finalFiles.add(entry);
                            } else {
                                java.nio.file.Files.deleteIfExists(Path.of(entry.getAbsolutePath()));
                            }
                        } catch (DecompressException e) {
                            log.error("Failed to decompress {}: {}", entry.getAbsolutePath(), e.getMessage());
                            finalFiles.add(entry); // keep original on error
                        }
                    } else {
                        finalFiles.add(entry);
                    }
                }

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Local scan + decompress completed: {} entries in {} ms", finalFiles.size(), elapsed);
                return SendFileResponse.success(finalFiles, result.isTruncated());
            }
            // ── 原有逻辑 ──────────────────────────────────────────────────────────

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Local scan completed: {} files in {} ms, truncated: {}",
                    result.getFiles().size(), elapsed, result.isTruncated());
            return SendFileResponse.success(result.getFiles(), result.isTruncated());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Error in local scan after {} ms", elapsed, e);
            return SendFileResponse.error("Internal error: " + e.getMessage());
        }
    }

    private boolean matchesCompressPatterns(String fileName, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return true;
        Path fileNamePath = Path.of(fileName);
        for (String pattern : patterns) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            if (matcher.matches(fileNamePath)) return true;
        }
        return false;
    }

    private SendFileResponse handleRelay(SendFileRequest request, ChannelHandlerContext inboundCtx) {
        String targetIp = request.getDstFileServeIp();
        int targetPort = request.getDstFileServePort() > 0 ? request.getDstFileServePort() : 7111;

        log.info("Relaying SENDFILE to Node B: {}/{}", targetIp, targetPort);

        Promise<ReceiveFileResponse> promise = inboundCtx.executor().newPromise();

        promise.addListener((Future<ReceiveFileResponse> f) -> {
            SendFileResponse resp;
            if (f.isSuccess()) {
                resp = convertToSendFileResponse(f.getNow());
                log.info("Relay completed successfully, files={}", resp.getFiles() != null ? resp.getFiles().size() : 0);
            } else {
                resp = SendFileResponse.error("Relay failed: " + f.cause().getMessage());
                log.error("Relay failed", f.cause());
            }
            if (inboundCtx.channel().isActive()) {
                inboundCtx.writeAndFlush(resp);
            }
        });

        ReceiveFileRequest recvReq = ReceiveFileRequest.from(request);

        clientFactory.bootstrap()
                .attr(AttributeKeys.PROMISE_KEY, promise)
                .attr(AttributeKeys.REQUEST_KEY, recvReq)
                .connect(targetIp, targetPort)
                .addListener((ChannelFuture cf) -> {
                    if (!cf.isSuccess()) {
                        log.error("Failed to connect to Node B {}/{}: {}", targetIp, targetPort, cf.cause().getMessage());
                        promise.setFailure(cf.cause());
                    }
                });

        return null;
    }

    private SendFileResponse convertToSendFileResponse(ReceiveFileResponse recvResp) {
        SendFileResponse resp = new SendFileResponse();
        resp.setSuccess(recvResp.isSuccess());
        resp.setErrorMessage(recvResp.getErrorMessage());
        resp.setTruncated(recvResp.isTruncated());
        resp.setFiles(recvResp.getFiles());
        return resp;
    }
}
