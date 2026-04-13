package com.example.ftc.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SENDFILE Command Response
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendFileResponse {
    private boolean success;
    private String errorMessage;
    private List<FileEntry> files = new ArrayList<>();
    private boolean truncated = false;  // Whether result was truncated due to max limit

    /**
     * Create success response
     */
    public static SendFileResponse success(List<FileEntry> files, boolean truncated) {
        return new SendFileResponse(true, null, files, truncated);
    }

    /**
     * Create error response
     */
    public static SendFileResponse error(String errorMessage) {
        return new SendFileResponse(false, errorMessage, new ArrayList<>(), false);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEntry {
        private String absolutePath;
        private String fileName;
        private long fileSize;
        private long lastModified;  // epoch millis
    }
}
