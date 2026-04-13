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
}
