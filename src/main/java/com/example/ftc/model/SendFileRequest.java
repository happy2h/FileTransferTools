package com.example.ftc.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SENDFILE Command Request
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendFileRequest {
    private String scanDirectory;      // Directory to scan
    private String filePattern;        // Filename pattern (optional, e.g., "*.log")
    private boolean recursive = false;  // Recursively scan subdirectories
    private long maxFileSizeBytes = 0;  // Max file size filter (0 = unlimited)

    // 双角色节点相关字段
    private String dstFileServeIp;      // 目标节点 IP（非空则触发 P2P 中继）
    private int dstFileServePort = 7111; // 目标节点端口（默认 7111）

    /** 不为 null 时，对扫描结果中匹配 compressPatterns 的文件执行解压 */
    private DecompressConfig decompressConfig;
}
