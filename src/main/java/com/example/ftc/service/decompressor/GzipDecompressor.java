package com.example.ftc.service.decompressor;

import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.SendFileResponse;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Component
public class GzipDecompressor implements Decompressor {

    @Override
    public List<String> supportedExtensions() {
        return List.of("gz");
    }

    @Override
    public List<SendFileResponse.FileEntry> decompress(Path sourceFile, Path targetDir,
                                                        DecompressLimits limits) throws DecompressException {
        String sourceName = sourceFile.getFileName().toString();
        String outName = sourceName.endsWith(".gz")
                ? sourceName.substring(0, sourceName.length() - 3)
                : sourceName + ".out";

        Path outPath;
        try {
            outPath = Decompressor.safeResolve(targetDir, outName);
            Files.createDirectories(outPath.getParent());
        } catch (DecompressException e) {
            throw e;
        } catch (IOException e) {
            throw new DecompressException("Failed to prepare output directory: " + e.getMessage(), e);
        }

        long entrySize = 0;
        try (GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(sourceFile)));
             OutputStream os = Files.newOutputStream(outPath)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = gis.read(buf)) != -1) {
                entrySize += len;
                if (entrySize > limits.maxEntrySizeBytes()) {
                    throw new DecompressException("Decompressed file too large: " + outName);
                }
                if (entrySize > limits.maxTotalSizeBytes()) {
                    throw new DecompressException("Total decompressed size exceeded");
                }
                os.write(buf, 0, len);
            }
        } catch (DecompressException e) {
            throw e;
        } catch (IOException e) {
            throw new DecompressException("Failed to decompress GZIP: " + e.getMessage(), e);
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(outPath, BasicFileAttributes.class);
            return List.of(new SendFileResponse.FileEntry(
                    outPath.toAbsolutePath().toString(),
                    outPath.getFileName().toString(),
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis()
            ));
        } catch (IOException e) {
            throw new DecompressException("Failed to read output file attributes: " + e.getMessage(), e);
        }
    }
}
