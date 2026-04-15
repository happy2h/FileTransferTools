package com.example.ftc.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * RECVFILE Command Response (Node B → Node A)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveFileResponse {
    private boolean success;
    private String errorMessage;
    private boolean truncated = false;
    private List<SendFileResponse.FileEntry> files = new ArrayList<>();

    /**
     * Create success response
     */
    public static ReceiveFileResponse success(List<SendFileResponse.FileEntry> files, boolean truncated) {
        return new ReceiveFileResponse(true, null, truncated, files);
    }

    /**
     * Create error response
     */
    public static ReceiveFileResponse error(String errorMessage) {
        return new ReceiveFileResponse(false, errorMessage, false, new ArrayList<>());
    }
}
