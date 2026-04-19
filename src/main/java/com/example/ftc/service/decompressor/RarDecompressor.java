package com.example.ftc.service.decompressor;

import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.SendFileResponse;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Component
public class RarDecompressor implements Decompressor {

    private static final Logger log = LoggerFactory.getLogger(RarDecompressor.class);

    @Override
    public List<String> supportedExtensions() {
        return List.of("rar");
    }

    @Override
    public List<SendFileResponse.FileEntry> decompress(Path sourceFile, Path targetDir,
                                                        DecompressLimits limits) throws DecompressException {
        try {
            return extractWithJunrar(sourceFile, targetDir, limits);
        } catch (DecompressException e) {
            throw e;
        } catch (Exception e) {
            // RAR5 or encrypted archive – fall back to system 7z
            log.warn("junrar failed for {}, falling back to 7z: {}", sourceFile.getFileName(), e.getMessage());
            return extractWith7z(sourceFile, targetDir);
        }
    }

    private List<SendFileResponse.FileEntry> extractWithJunrar(Path sourceFile, Path targetDir,
                                                                 DecompressLimits limits) throws Exception {
        List<SendFileResponse.FileEntry> entries = new ArrayList<>();
        int entryCount = 0;
        long totalSize = 0;

        try (Archive archive = new Archive(sourceFile.toFile())) {
            for (FileHeader header : archive.getFileHeaders()) {
                if (header.isDirectory()) continue;

                if (++entryCount > limits.maxEntries()) {
                    throw new DecompressException("Entry count exceeded: " + limits.maxEntries());
                }

                String entryName = header.getFileName();
                Path outPath = Decompressor.safeResolve(targetDir, entryName);
                Files.createDirectories(outPath.getParent());

                try (OutputStream os = Files.newOutputStream(outPath)) {
                    archive.extractFile(header, os);
                }

                long entrySize = Files.size(outPath);
                if (entrySize > limits.maxEntrySizeBytes()) {
                    Files.deleteIfExists(outPath);
                    throw new DecompressException("Single entry too large: " + entryName);
                }
                totalSize += entrySize;
                if (totalSize > limits.maxTotalSizeBytes()) {
                    throw new DecompressException("Total decompressed size exceeded");
                }

                BasicFileAttributes attrs = Files.readAttributes(outPath, BasicFileAttributes.class);
                entries.add(new SendFileResponse.FileEntry(
                        outPath.toAbsolutePath().toString(),
                        outPath.getFileName().toString(),
                        attrs.size(),
                        attrs.lastModifiedTime().toMillis()
                ));
            }
        }
        return entries;
    }

    private List<SendFileResponse.FileEntry> extractWith7z(Path sourceFile, Path targetDir) throws DecompressException {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new DecompressException("Failed to create target directory: " + e.getMessage(), e);
        }

        // Array-form ProcessBuilder prevents shell injection
        ProcessBuilder pb = new ProcessBuilder(
                "7z", "x", sourceFile.toString(), "-o" + targetDir.toString(), "-y"
        );
        pb.redirectErrorStream(true);

        try {
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new DecompressException("7z extraction failed (exit " + exitCode + "): " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecompressException("Failed to run 7z: " + e.getMessage(), e);
        }

        return collectFiles(targetDir);
    }

    private List<SendFileResponse.FileEntry> collectFiles(Path dir) throws DecompressException {
        List<SendFileResponse.FileEntry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    entries.add(new SendFileResponse.FileEntry(
                            file.toAbsolutePath().toString(),
                            file.getFileName().toString(),
                            attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                    ));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new DecompressException("Failed to collect extracted files: " + e.getMessage(), e);
        }
        return entries;
    }
}
