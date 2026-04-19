package com.example.ftc.service.decompressor;

import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.SendFileResponse;

import java.nio.file.Path;
import java.util.List;

public interface Decompressor {

    /** 该实现支持的扩展名（小写），复合扩展名如 "tar.gz" */
    List<String> supportedExtensions();

    List<SendFileResponse.FileEntry> decompress(Path sourceFile, Path targetDir,
                                                 DecompressLimits limits) throws DecompressException;

    static Path safeResolve(Path targetDir, String entryName) throws DecompressException {
        Path resolved = targetDir.resolve(entryName).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new DecompressException("Path traversal detected in entry: " + entryName);
        }
        return resolved;
    }
}
