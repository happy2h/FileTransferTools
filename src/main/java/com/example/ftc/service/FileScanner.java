package com.example.ftc.service;

import com.example.ftc.model.SendFileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * File scanning service
 */
@Service
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    /**
     * Scan directory for files matching the specified criteria
     *
     * @param scanDirectory   Directory to scan
     * @param filePattern     File name pattern (glob), null means all files
     * @param recursive       Whether to scan recursively
     * @param maxFileSizeBytes Maximum file size filter (0 = unlimited)
     * @param maxResults      Maximum number of results to return
     * @return Scan result
     */
    public ScanResult scan(String scanDirectory, String filePattern, boolean recursive,
                           long maxFileSizeBytes, int maxResults) {
        List<SendFileResponse.FileEntry> files = new ArrayList<>();
        Path dirPath = Paths.get(scanDirectory).normalize().toAbsolutePath();

        log.info("Scanning directory: {}, pattern: {}, recursive: {}, maxSize: {}, maxResults: {}",
                 scanDirectory, filePattern, recursive, maxFileSizeBytes, maxResults);

        if (!Files.exists(dirPath)) {
            log.warn("Directory does not exist: {}", dirPath);
            return new ScanResult(false, "Directory does not exist: " + dirPath, files, false);
        }

        if (!Files.isDirectory(dirPath)) {
            log.warn("Path is not a directory: {}", dirPath);
            return new ScanResult(false, "Path is not a directory: " + dirPath, files, false);
        }

        try {
            final PathMatcher pathMatcher;
            Predicate<Path> pathPredicate;
            if (filePattern != null && !filePattern.isEmpty()) {
                pathMatcher = dirPath.getFileSystem().getPathMatcher("glob:" + filePattern);
                pathPredicate = path -> pathMatcher.matches(path.getFileName());
            } else {
                pathMatcher = null;
            }

            FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Check if we've reached max results
                    if (files.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }

                    // Apply pattern filter
                    if (pathMatcher != null && !pathMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Apply size filter
                    if (maxFileSizeBytes > 0 && attrs.size() > maxFileSizeBytes) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Add file to results
                    files.add(new SendFileResponse.FileEntry(
                            file.toAbsolutePath().toString(),
                            file.getFileName().toString(),
                            attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                    ));

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip hidden directories
                    if (dir.getFileName() != null && dir.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            };

            int maxDepth = recursive ? Integer.MAX_VALUE : 1;
            Set<FileVisitOption> options = recursive ? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class);
            Files.walkFileTree(dirPath, options, maxDepth, visitor);

            boolean truncated = files.size() >= maxResults;
            log.info("Scan completed: {} files found, truncated: {}", files.size(), truncated);

            return new ScanResult(true, null, files, truncated);

        } catch (IOException e) {
            log.error("Error scanning directory: {}", dirPath, e);
            return new ScanResult(false, "Error scanning directory: " + e.getMessage(), files, false);
        }
    }

    /**
     * Result of file scan operation
     */
    public static class ScanResult {
        private final boolean success;
        private final String errorMessage;
        private final List<SendFileResponse.FileEntry> files;
        private final boolean truncated;

        public ScanResult(boolean success, String errorMessage,
                         List<SendFileResponse.FileEntry> files, boolean truncated) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.files = files;
            this.truncated = truncated;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public List<SendFileResponse.FileEntry> getFiles() {
            return files;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }
}
