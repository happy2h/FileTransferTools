package com.example.ftc.service;

import com.example.ftc.config.FtsProperties;
import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.DecompressConfig;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.service.decompressor.Decompressor;
import com.example.ftc.service.decompressor.DecompressLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileDecompressor {

    private static final Logger log = LoggerFactory.getLogger(FileDecompressor.class);

    private final Map<String, Decompressor> registry;

    /** Spring 自动注入所有 Decompressor 实现，按扩展名建立注册表 */
    public FileDecompressor(List<Decompressor> decompressors) {
        this.registry = new HashMap<>();
        for (Decompressor d : decompressors) {
            for (String ext : d.supportedExtensions()) {
                registry.put(ext.toLowerCase(), d);
            }
        }
        log.info("FileDecompressor initialized, supported extensions: {}", registry.keySet());
    }

    public List<SendFileResponse.FileEntry> decompress(SendFileResponse.FileEntry entry,
                                                        DecompressConfig cfg,
                                                        FtsProperties props) throws DecompressException {
        String ext = detectExtension(entry.getFileName());
        Decompressor decompressor = registry.get(ext);
        if (decompressor == null) {
            throw new DecompressException("Unsupported compression format: '" + ext + "' for file: " + entry.getFileName());
        }

        Path sourceFile = Path.of(entry.getAbsolutePath());
        Path targetDir = resolveTargetDir(sourceFile, entry.getFileName(), ext, cfg);

        FtsProperties.Decompress decompress = props.getDecompress();
        DecompressLimits limits = new DecompressLimits(
                cfg.getMaxEntries() > 0 ? cfg.getMaxEntries() : decompress.getDefaultMaxEntries(),
                cfg.getMaxEntrySizeBytes() > 0 ? cfg.getMaxEntrySizeBytes()
                        : decompress.getDefaultMaxEntrySizeMb() * 1024 * 1024,
                cfg.getMaxTotalSizeBytes() > 0 ? cfg.getMaxTotalSizeBytes()
                        : decompress.getDefaultMaxTotalSizeGb() * 1024L * 1024 * 1024
        );

        log.info("Decompressing {} via {} into {}", entry.getAbsolutePath(),
                decompressor.getClass().getSimpleName(), targetDir);
        return decompressor.decompress(sourceFile, targetDir, limits);
    }

    private Path resolveTargetDir(Path sourceFile, String fileName, String ext, DecompressConfig cfg) {
        String targetDirStr = cfg.getTargetDirectory();
        if (targetDirStr != null && !targetDirStr.isBlank()) {
            return Path.of(targetDirStr).normalize().toAbsolutePath();
        }
        // Default: same-name subdirectory next to the archive
        String baseName = stripExtension(fileName, ext);
        return sourceFile.getParent().resolve(baseName).normalize().toAbsolutePath();
    }

    private String detectExtension(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : List.of("tar.gz", "tar.bz2", "tar.xz", "tgz")) {
            if (lower.endsWith("." + ext)) return ext;
        }
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot + 1) : "";
    }

    private String stripExtension(String fileName, String ext) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith("." + ext)) {
            return fileName.substring(0, fileName.length() - ext.length() - 1);
        }
        return fileName;
    }
}
