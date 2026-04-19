package com.example.ftc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FileTransferServer Configuration Properties
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "fts")
public class FtsProperties {

    private int port = 7111;
    private int workerThreads = 8;
    private int businessThreads = 16;
    private int readTimeoutSeconds = 30;
    private int outboundReadTimeoutSeconds = 10;
    private int connectTimeoutMillis = 5000;
    private int maxScanResults = 10000;
    private long maxBodySize = 10485760;  // 10MB
    private List<String> allowedDirectories;
    private Decompress decompress = new Decompress();

    /**
     * Get allowed directory paths as Path objects
     */
    public List<Path> getAllowedDirectoryPaths() {
        if (allowedDirectories == null) {
            return java.util.Collections.emptyList();
        }
        return allowedDirectories.stream()
                .map(Paths::get)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    @Data
    public static class Decompress {
        private boolean fallbackEnabled = true;
        private int defaultMaxEntries = 10_000;
        private long defaultMaxEntrySizeMb = 500;
        private long defaultMaxTotalSizeGb = 2;
    }
}
