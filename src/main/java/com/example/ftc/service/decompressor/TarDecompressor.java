package com.example.ftc.service.decompressor;

import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.SendFileResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Component
public class TarDecompressor implements Decompressor {

    @Override
    public List<String> supportedExtensions() {
        return List.of("tar", "tar.gz", "tgz", "tar.bz2", "tar.xz");
    }

    @Override
    public List<SendFileResponse.FileEntry> decompress(Path sourceFile, Path targetDir,
                                                        DecompressLimits limits) throws DecompressException {
        List<SendFileResponse.FileEntry> entries = new ArrayList<>();
        String ext = detectExt(sourceFile.getFileName().toString());

        try (TarArchiveInputStream tais = openTarStream(sourceFile, ext)) {
            int entryCount = 0;
            long totalSize = 0;
            TarArchiveEntry entry;
            while ((entry = tais.getNextEntry()) != null) {
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
                    while ((len = tais.read(buf)) != -1) {
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
            throw new DecompressException("Failed to decompress TAR: " + e.getMessage(), e);
        }

        return entries;
    }

    private TarArchiveInputStream openTarStream(Path sourceFile, String ext) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(sourceFile));
        return switch (ext) {
            case "tar.gz", "tgz" -> new TarArchiveInputStream(new GzipCompressorInputStream(raw));
            case "tar.bz2"       -> new TarArchiveInputStream(new BZip2CompressorInputStream(raw));
            case "tar.xz"        -> new TarArchiveInputStream(new XZCompressorInputStream(raw));
            default              -> new TarArchiveInputStream(raw);
        };
    }

    private String detectExt(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : List.of("tar.gz", "tar.bz2", "tar.xz", "tgz")) {
            if (lower.endsWith("." + ext)) return ext;
        }
        return "tar";
    }
}
