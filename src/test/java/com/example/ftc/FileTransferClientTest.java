package com.example.ftc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test client for FileTransferClient
 *
 * Supports both local SENDFILE and P2P relay with dstFileServeIp
 */
public class FileTransferClientTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws IOException, InterruptedException {
        // Test 1: Local SENDFILE (no dstFileServeIp)
        System.out.println("=== Test 1: Local SENDFILE ===");
        SendFileRequest localRequest = new SendFileRequest();
        localRequest.setScanDirectory("/tmp");
        localRequest.setRecursive(false);
        localRequest.setMaxFileSizeBytes(0);
        localRequest.setDstFileServeIp(null); // Local scan

        SendFileResponse localResponse = sendSendFileRequest("localhost", 7111, localRequest);
        printResponse(localResponse);

        System.out.println();

        // Test 2: P2P Relay SENDFILE (with dstFileServeIp)
        // Note: This requires another FTS node running on port 7112
        // Uncomment to test P2P relay
        /*
        System.out.println("=== Test 2: P2P Relay SENDFILE ===");
        SendFileRequest relayRequest = new SendFileRequest();
        relayRequest.setScanDirectory("/tmp");
        relayRequest.setRecursive(false);
        relayRequest.setMaxFileSizeBytes(0);
        relayRequest.setDstFileServeIp("127.0.0.1"); // Relay to another node
        relayRequest.setDstFileServePort(7112);

        SendFileResponse relayResponse = sendSendFileRequest("localhost", 7111, relayRequest);
        printResponse(relayResponse);
        */

        System.out.println("Test completed");
    }

    private static SendFileResponse sendSendFileRequest(String host, int port, SendFileRequest request)
            throws IOException, InterruptedException {

        System.out.println("Connecting to " + host + ":" + port);

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(30000); // 30s timeout
            System.out.println("Connected to server");

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Serialize request to JSON
            byte[] bodyBytes = objectMapper.writeValueAsBytes(request);
            System.out.println("Request body length: " + bodyBytes.length);

            // Send command (8 bytes, space-padded)
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

            // Wait a bit for relay scenarios
            Thread.sleep(100);

            // Read response length (8 bytes, Big-Endian long)
            long respLength = in.readLong();
            System.out.println("Response body length: " + respLength);

            if (respLength > 10 * 1024 * 1024) {
                throw new IOException("Response too large: " + respLength);
            }

            // Read response body
            byte[] respBytes = new byte[(int) respLength];
            in.readFully(respBytes);

            // Deserialize response
            SendFileResponse response = objectMapper.readValue(respBytes, SendFileResponse.class);
            return response;
        }
    }

    private static void printResponse(SendFileResponse response) {
        System.out.println("Response received:");
        System.out.println("  Success: " + response.isSuccess());
        if (response.getErrorMessage() != null) {
            System.out.println("  Error: " + response.getErrorMessage());
        }
        System.out.println("  Files found: " + (response.getFiles() != null ? response.getFiles().size() : 0));
        System.out.println("  Truncated: " + response.isTruncated());

        if (response.getFiles() != null) {
            for (FileEntry file : response.getFiles()) {
                System.out.println("    - " + file.getFileName() + " (" + file.getFileSize() + " bytes)");
            }
        }
    }

    // Request POJO
    public static class SendFileRequest {
        private String scanDirectory;
        private String filePattern;
        private boolean recursive;
        private long maxFileSizeBytes;
        private String dstFileServeIp;
        private int dstFileServePort = 7111;

        public String getScanDirectory() {
            return scanDirectory;
        }

        public void setScanDirectory(String scanDirectory) {
            this.scanDirectory = scanDirectory;
        }

        public String getFilePattern() {
            return filePattern;
        }

        public void setFilePattern(String filePattern) {
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

        public String getDstFileServeIp() {
            return dstFileServeIp;
        }

        public void setDstFileServeIp(String dstFileServeIp) {
            this.dstFileServeIp = dstFileServeIp;
        }

        public int getDstFileServePort() {
            return dstFileServePort;
        }

        public void setDstFileServePort(int dstFileServePort) {
            this.dstFileServePort = dstFileServePort;
        }
    }

    // Response POJO
    public static class SendFileResponse {
        private boolean success;
        private String errorMessage;
        private List<FileEntry> files = new ArrayList<>();
        private boolean truncated;

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public List<FileEntry> getFiles() {
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

        public long getFileSize() {
            return fileSize;
        }
    }
}
