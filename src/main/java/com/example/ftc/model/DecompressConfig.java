package com.example.ftc.model;

import lombok.Data;

import java.util.List;

@Data
public class DecompressConfig {

    private List<String> compressPatterns = List.of("*.zip", "*.tar.gz", "*.tgz",
            "*.tar.bz2", "*.tar.xz", "*.gz", "*.rar", "*.7z");

    /** 解压输出目录（绝对路径）。null 时默认解压到压缩文件所在目录的同名子目录 */
    private String targetDirectory;

    /** 解压完成后是否删除原压缩文件，默认 false */
    private boolean deleteSourceAfterExtract = false;

    /** 最大解压条目数（防 ZIP Bomb），0 = 使用全局默认 */
    private int maxEntries = 0;

    /** 单文件解压后最大字节数，0 = 使用全局默认 */
    private long maxEntrySizeBytes = 0;

    /** 所有文件解压后总大小上限，0 = 使用全局默认 */
    private long maxTotalSizeBytes = 0;
}
