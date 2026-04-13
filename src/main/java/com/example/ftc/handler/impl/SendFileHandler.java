package com.example.ftc.handler.impl;

import com.example.ftc.config.FtsProperties;
import com.example.ftc.handler.CommandHandler;
import com.example.ftc.model.SendFileRequest;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.service.FileScanner;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Handler for SENDFILE command
 *
 * Scans a directory and returns a list of matching files
 */
@Component
public class SendFileHandler implements CommandHandler<SendFileRequest, SendFileResponse> {

    private static final Logger log = LoggerFactory.getLogger(SendFileHandler.class);

    @Autowired
    private FileScanner fileScanner;

    @Autowired
    private FtsProperties properties;

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

            // Validate recursive flag
            boolean recursive = request.isRecursive();

            // Get file pattern (optional)
            String filePattern = request.getFilePattern();

            // Get max file size
            long maxFileSize = request.getMaxFileSizeBytes();

            // Get max scan results
            int maxResults = properties.getMaxScanResults();

            // Perform scan
            FileScanner.ScanResult result = fileScanner.scan(
                    request.getScanDirectory(),
                    filePattern,
                    recursive,
                    maxFileSize,
                    maxResults
            );

            if (!result.isSuccess()) {
                log.warn("Scan failed: {}", result.getErrorMessage());
                return SendFileResponse.error(result.getErrorMessage());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Scan completed successfully: {} files found in {} ms, truncated: {}",
                    result.getFiles().size(), elapsed, result.isTruncated());

            return SendFileResponse.success(result.getFiles(), result.isTruncated());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Error handling SENDFILE command after {} ms", elapsed, e);
            return SendFileResponse.error("Internal error: " + e.getMessage());
        }
    }
}
