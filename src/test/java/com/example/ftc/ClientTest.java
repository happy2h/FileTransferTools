package com.example.ftc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Simple test client for FileTransferServer
 */
public class ClientTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        // Create test request
        SendFileRequest request = new SendFileRequest();
        request.setScanDirectory("/tmp");
        request.setRecursive(false);
        request.setMaxFileSizeBytes(0);

        // Connect to server
        try (Socket socket = new Socket("localhost", 7111)) {
            System.out.println("Connected to server");

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Serialize request to JSON
            byte[] bodyBytes = objectMapper.writeValueAsBytes(request);
            System.out.println("Request body length: " + bodyBytes.length);

            // Send command (8 bytes, left-aligned, space-padded)
            byte[] commandBytes = "SENDFILE".getBytes(StandardCharsets.US_ASCII);
            if (commandBytes.length > 8) {
                throw new IOException("Command too long");
            }
            byte[] paddedCommand = new byte[8];
            System.arraycopy(commandBytes, 0, paddedCommand, 0, commandBytes.length);
            out.write(paddedCommand);

            // Send body length (8 bytes, Big-Endian long)
            out.writeLong(bodyBytes.length);

            // Send body
            out.write(bodyBytes);
            out.flush();

            System.out.println("Request sent, waiting for response...");

            // Read response length (8 bytes, Big-Endian long)
            long respLength = in.readLong();
            System.out.println("Response body length: " + respLength);

            // Read response body
            byte[] respBytes = new byte[(int) respLength];
            in.readFully(respBytes);

            // Deserialize response
            SendFileResponse response = objectMapper.readValue(respBytes, SendFileResponse.class);
            System.out.println("Response received:");
            System.out.println("  Success: " + response.isSuccess());
            if (response.getErrorMessage() != null) {
                System.out.println("  Error: " + response.getErrorMessage());
            }
            System.out.println("  Files found: " + response.getFiles().size());
            System.out.println("  Truncated: " + response.isTruncated());

            for (SendFileResponse.FileEntry file : response.getFiles()) {
                System.out.println("    - " + file.getFileName() + " (" + file.getFileSize() + " bytes)");
            }
        }

        System.out.println("Test completed");
    }

    // Simple POJO for request
    public static class SendFileRequest {
        private String scanDirectory;
        private String filePattern;
        private boolean recursive;
        private long maxFileSizeBytes;

        public String getScanDirectory() {
            return scanDirectory;
        }

        public void setScanDirectory(String scanDirectory) {
            this.scanDirectory = scanDirectory;
        }

        public String getFilePattern() {
            return filePattern;
        }

        public void setFilePatternFilePattern(String filePattern) {
            this.filePattern = filePattern;
        }

        public boolean isRecursive() {
            return recursive;
        }

        public void setRecursive(boolean recursive) {
            this.recursive = recursive;
        }

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }
    }

    // Simple POJO for response
    public static class SendFileResponse {
        private boolean success;
        private String errorMessage;
        private java.util.List<FileEntry> files;
        private boolean truncated;

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public java.util.List<FileEntry> getFiles() {
            return files;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    public static class FileEntry {
        private String absolutePath;
        private String fileName;
        private long fileSize;
        private long lastModified;

        public String getFileName() {
            return fileName;
        }
    }
}
