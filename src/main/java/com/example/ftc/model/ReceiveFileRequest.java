package com.example.ftc.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RECVFILE Command Request (Node A → Node B)
 *
 * 这是 SendFileRequest 去掉路由字段后的子集
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveFileRequest {
    private String scanDirectory;      // Directory to scan
    private String filePattern;        // Filename pattern (optional, e.g., "*.log")
    private boolean recursive = false;  // Recursively scan subdirectories
    private long maxFileSizeBytes = 0;  // Max file size filter (0 = unlimited)

    /**
     * 从 SendFileRequest 创建 ReceiveFileRequest（中继时自动转换）
     */
    public static ReceiveFileRequest from(SendFileRequest sendFileRequest) {
        ReceiveFileRequest req = new ReceiveFileRequest();
        req.setScanDirectory(sendFileRequest.getScanDirectory());
        req.setFilePattern(sendFileRequest.getFilePattern());
        req.setRecursive(sendFileRequest.isRecursive());
        req.setMaxFileSizeBytes(sendFileRequest.getMaxFileSizeBytes());
        return req;
    }
}
