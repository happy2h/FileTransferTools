package com.example.ftc.service.decompressor;

import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.SendFileResponse;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Component
public class SevenZipDecompressor implements Decompressor {

    @Override
    public List<String> supportedExtensions() {
        return List.of("7z");
    }

    @Override
    public List<SendFileResponse.FileEntry> decompress(Path sourceFile, Path targetDir,
                                                        DecompressLimits limits) throws DecompressException {
        List<SendFileResponse.FileEntry> entries = new ArrayList<>();
        int entryCount = 0;
        long totalSize = 0;

        try (SevenZFile sevenZFile = SevenZFile.builder().setFile(sourceFile.toFile()).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                if (++entryCount > limits.maxEntries()) {
                    throw new DecompressException("Entry count exceeded: " + limits.maxEntries());
                }

                Path outPath = Decompressor.safeResolve(targetDir, entry.getName());
                Files.createDirectories(outPath.getParent());

                long entrySize = 0;
                try (OutputStream os = Files.newOutputStream(outPath)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = sevenZFile.read(buf)) != -1) {
                        entrySize += len;
                        if (entrySize > limits.maxEntrySizeBytes()) {
                            throw new DecompressException("Single entry too large: " + entry.getName());
                        }
                        totalSize += len;
                        if (totalSize > limits.maxTotalSizeBytes()) {
                            throw new DecompressException("Total decompressed size exceeded");
                        }
                        os.write(buf, 0, len);
                    }
                }

                BasicFileAttributes attrs = Files.readAttributes(outPath, BasicFileAttributes.class);
                entries.add(new SendFileResponse.FileEntry(
                        outPath.toAbsolutePath().toString(),
                        outPath.getFileName().toString(),
                        attrs.size(),
                        attrs.lastModifiedTime().toMillis()
                ));
            }
        } catch (DecompressException e) {
            throw e;
        } catch (IOException e) {
            throw new DecompressException("Failed to decompress 7z: " + e.getMessage(), e);
        }

        return entries;
    }
}
