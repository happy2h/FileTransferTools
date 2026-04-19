package com.example.ftc.service;

import com.example.ftc.config.FtsProperties;
import com.example.ftc.exception.DecompressException;
import com.example.ftc.model.DecompressConfig;
import com.example.ftc.model.SendFileResponse;
import com.example.ftc.service.decompressor.Decompressor;
import com.example.ftc.service.decompressor.DecompressLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.clearInvocations;

@ExtendWith(MockitoExtension.class)
class FileDecompressorTest {

    // ── Mock Decompressor 实现 ────────────────────────────────────────────────

    @Mock
    private Decompressor zipDecompressor;

    @Mock
    private Decompressor tarDecompressor;

    @Mock
    private Decompressor gzipDecompressor;

    // ── 被测类 & 全局属性 ──────────────────────────────────────────────────────

    private FileDecompressor fileDecompressor;
    private FtsProperties properties;

    /** 全局默认限制（在 application.yml 中配置的典型值） */
    private static final int    GLOBAL_MAX_ENTRIES       = 10_000;
    private static final long   GLOBAL_MAX_ENTRY_SIZE_MB = 500L;
    private static final long   GLOBAL_MAX_TOTAL_SIZE_GB = 2L;

    /** 解压成功后模拟返回的文件条目 */
    private static final List<SendFileResponse.FileEntry> EXTRACTED_FILES = List.of(
            new SendFileResponse.FileEntry("/out/readme.txt", "readme.txt", 200, 0L),
            new SendFileResponse.FileEntry("/out/data.csv",   "data.csv",   800, 0L)
    );

    @BeforeEach
    void setUp() {
        when(zipDecompressor.supportedExtensions()).thenReturn(List.of("zip"));
        when(tarDecompressor.supportedExtensions()).thenReturn(
                List.of("tar", "tar.gz", "tgz", "tar.bz2", "tar.xz"));
        when(gzipDecompressor.supportedExtensions()).thenReturn(List.of("gz"));

        fileDecompressor = new FileDecompressor(
                List.of(zipDecompressor, tarDecompressor, gzipDecompressor));
        // 清除构造期间 supportedExtensions() 产生的交互记录，避免影响后续 verifyNoInteractions
        clearInvocations(zipDecompressor, tarDecompressor, gzipDecompressor);

        FtsProperties.Decompress dc = new FtsProperties.Decompress();
        dc.setDefaultMaxEntries(GLOBAL_MAX_ENTRIES);
        dc.setDefaultMaxEntrySizeMb(GLOBAL_MAX_ENTRY_SIZE_MB);
        dc.setDefaultMaxTotalSizeGb(GLOBAL_MAX_TOTAL_SIZE_GB);

        properties = new FtsProperties();
        properties.setDecompress(dc);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. 格式识别 & 分发
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("格式识别 & Decompressor 分发")
    class DispatchTest {

        @Test
        @DisplayName("zip 文件 → 调用 zipDecompressor")
        void zip_routesToZipDecompressor() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry("/tmp/archive.zip"), defaultCfg(), properties);

            verify(zipDecompressor).decompress(any(), any(), any());
            verifyNoInteractions(tarDecompressor, gzipDecompressor);
        }

        @Test
        @DisplayName("gz 文件（非 tar.gz）→ 调用 gzipDecompressor")
        void gz_routesToGzipDecompressor() throws Exception {
            when(gzipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry("/tmp/report.gz"), defaultCfg(), properties);

            verify(gzipDecompressor).decompress(any(), any(), any());
            verifyNoInteractions(zipDecompressor, tarDecompressor);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("TAR 系列格式 → 调用 tarDecompressor")
        @ValueSource(strings = {
                "/tmp/backup.tar",
                "/tmp/backup.tar.gz",
                "/tmp/backup.tgz",
                "/tmp/backup.tar.bz2",
                "/tmp/backup.tar.xz"
        })
        void tarVariants_routeToTarDecompressor(String filePath) throws Exception {
            when(tarDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry(filePath), defaultCfg(), properties);

            verify(tarDecompressor).decompress(any(), any(), any());
            verifyNoInteractions(zipDecompressor, gzipDecompressor);
        }

        @Test
        @DisplayName("复合扩展名优先匹配：backup.tar.gz 识别为 tar.gz，而非 gz")
        void compoundExtension_matchesTarGzNotGz() throws Exception {
            when(tarDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry("/tmp/backup.tar.gz"), defaultCfg(), properties);

            verify(tarDecompressor).decompress(any(), any(), any());
            verifyNoInteractions(gzipDecompressor);
        }

        @Test
        @DisplayName("不支持的扩展名 → 抛出 DecompressException")
        void unsupportedExtension_throwsDecompressException() {
            assertThatThrownBy(() ->
                    fileDecompressor.decompress(entry("/tmp/file.lzma"), defaultCfg(), properties))
                    .isInstanceOf(DecompressException.class)
                    .hasMessageContaining("lzma");
        }

        @Test
        @DisplayName("无扩展名 → 抛出 DecompressException")
        void noExtension_throwsDecompressException() {
            assertThatThrownBy(() ->
                    fileDecompressor.decompress(entry("/tmp/noextfile"), defaultCfg(), properties))
                    .isInstanceOf(DecompressException.class);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. DecompressLimits 合并逻辑
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DecompressLimits 合并")
    class LimitsMergingTest {

        @Test
        @DisplayName("config 全为 0 → 使用全局默认值")
        void allZeroInConfig_usesGlobalDefaults() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg(); // maxEntries=0, maxEntrySizeBytes=0, maxTotalSizeBytes=0
            fileDecompressor.decompress(entry("/tmp/a.zip"), cfg, properties);

            DecompressLimits captured = captureLimits(zipDecompressor);
            assertThat(captured).isEqualTo(new DecompressLimits(
                    GLOBAL_MAX_ENTRIES,
                    GLOBAL_MAX_ENTRY_SIZE_MB * 1024 * 1024,
                    GLOBAL_MAX_TOTAL_SIZE_GB * 1024L * 1024 * 1024
            ));
        }

        @Test
        @DisplayName("config 全不为 0 → 使用 config 指定值，忽略全局默认")
        void allSetInConfig_usesConfigValues() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg();
            cfg.setMaxEntries(100);
            cfg.setMaxEntrySizeBytes(1_000_000L);
            cfg.setMaxTotalSizeBytes(5_000_000L);

            fileDecompressor.decompress(entry("/tmp/a.zip"), cfg, properties);

            DecompressLimits captured = captureLimits(zipDecompressor);
            assertThat(captured).isEqualTo(new DecompressLimits(100, 1_000_000L, 5_000_000L));
        }

        @Test
        @DisplayName("config 部分为 0 → 0 的字段回退全局默认，非 0 字段使用 config 值")
        void partialConfig_mixesConfigAndGlobal() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg();
            cfg.setMaxEntries(50);          // 非 0 → 用 config
            cfg.setMaxEntrySizeBytes(0);    // 0 → 用全局
            cfg.setMaxTotalSizeBytes(0);    // 0 → 用全局

            fileDecompressor.decompress(entry("/tmp/a.zip"), cfg, properties);

            DecompressLimits captured = captureLimits(zipDecompressor);
            assertThat(captured.maxEntries()).isEqualTo(50);
            assertThat(captured.maxEntrySizeBytes()).isEqualTo(GLOBAL_MAX_ENTRY_SIZE_MB * 1024 * 1024);
            assertThat(captured.maxTotalSizeBytes()).isEqualTo(GLOBAL_MAX_TOTAL_SIZE_GB * 1024L * 1024 * 1024);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. 目标目录解析
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("目标目录解析")
    class TargetDirectoryTest {

        @Test
        @DisplayName("config.targetDirectory 不为空 → 使用指定目录")
        void explicitTargetDir_usedDirectly() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg();
            cfg.setTargetDirectory("/explicit/output");

            fileDecompressor.decompress(entry("/tmp/archive.zip"), cfg, properties);

            Path targetDir = captureTargetDir(zipDecompressor);
            assertThat(targetDir).isEqualTo(Path.of("/explicit/output"));
        }

        @Test
        @DisplayName("config.targetDirectory 为 null → 默认解压到同级同名子目录")
        void nullTargetDir_defaultsToSiblingSubdir() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg(); // targetDirectory = null

            fileDecompressor.decompress(entry("/tmp/archive.zip"), cfg, properties);

            Path targetDir = captureTargetDir(zipDecompressor);
            assertThat(targetDir).isEqualTo(Path.of("/tmp/archive"));
        }

        @Test
        @DisplayName("复合扩展名文件名去除整个后缀：backup.tar.gz → 子目录 backup")
        void tarGzFile_stripsCompoundExtension() throws Exception {
            when(tarDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry("/data/backup.tar.gz"), defaultCfg(), properties);

            Path targetDir = captureTargetDir(tarDecompressor);
            assertThat(targetDir).isEqualTo(Path.of("/data/backup"));
        }

        @Test
        @DisplayName("config.targetDirectory 为空字符串 → 同样回退默认路径")
        void blankTargetDir_defaultsToSiblingSubdir() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            DecompressConfig cfg = defaultCfg();
            cfg.setTargetDirectory("   ");

            fileDecompressor.decompress(entry("/tmp/report.zip"), cfg, properties);

            Path targetDir = captureTargetDir(zipDecompressor);
            assertThat(targetDir).isEqualTo(Path.of("/tmp/report"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. 返回值 & 异常透传
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("返回值 & 异常处理")
    class ResultAndExceptionTest {

        @Test
        @DisplayName("返回底层 Decompressor 产出的文件条目列表")
        void returnsEntriesFromDecompressor() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            List<SendFileResponse.FileEntry> result =
                    fileDecompressor.decompress(entry("/tmp/a.zip"), defaultCfg(), properties);

            assertThat(result).isEqualTo(EXTRACTED_FILES);
        }

        @Test
        @DisplayName("底层 Decompressor 抛出 DecompressException 时原样向上传播")
        void decompressorThrows_exceptionPropagates() throws Exception {
            DecompressException cause = new DecompressException("Path traversal detected in entry: ../evil");
            when(zipDecompressor.decompress(any(), any(), any())).thenThrow(cause);

            assertThatThrownBy(() ->
                    fileDecompressor.decompress(entry("/tmp/a.zip"), defaultCfg(), properties))
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("底层 Decompressor 抛出 ZIP Bomb 异常时原样向上传播")
        void zipBombDetected_exceptionPropagates() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any()))
                    .thenThrow(new DecompressException("Entry count exceeded: 10000"));

            assertThatThrownBy(() ->
                    fileDecompressor.decompress(entry("/tmp/bomb.zip"), defaultCfg(), properties))
                    .isInstanceOf(DecompressException.class)
                    .hasMessageContaining("Entry count exceeded");
        }

        @Test
        @DisplayName("sourceFile 路径直接来源于 FileEntry.absolutePath")
        void sourceFilePath_matchesEntryAbsolutePath() throws Exception {
            when(zipDecompressor.decompress(any(), any(), any())).thenReturn(EXTRACTED_FILES);

            fileDecompressor.decompress(entry("/tmp/myarchive.zip"), defaultCfg(), properties);

            ArgumentCaptor<Path> sourceCaptor = ArgumentCaptor.forClass(Path.class);
            verify(zipDecompressor).decompress(sourceCaptor.capture(), any(), any());
            assertThat(sourceCaptor.getValue()).isEqualTo(Path.of("/tmp/myarchive.zip"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ═════════════════════════════════════════════════════════════════════════

    private static SendFileResponse.FileEntry entry(String absolutePath) {
        Path p = Path.of(absolutePath);
        return new SendFileResponse.FileEntry(absolutePath, p.getFileName().toString(), 1024L, 0L);
    }

    private static DecompressConfig defaultCfg() {
        DecompressConfig cfg = new DecompressConfig();
        cfg.setTargetDirectory(null);
        cfg.setMaxEntries(0);
        cfg.setMaxEntrySizeBytes(0);
        cfg.setMaxTotalSizeBytes(0);
        return cfg;
    }

    private static DecompressLimits captureLimits(Decompressor mock) throws Exception {
        ArgumentCaptor<DecompressLimits> captor = ArgumentCaptor.forClass(DecompressLimits.class);
        verify(mock).decompress(any(), any(), captor.capture());
        return captor.getValue();
    }

    private static Path captureTargetDir(Decompressor mock) throws Exception {
        ArgumentCaptor<Path> captor = ArgumentCaptor.forClass(Path.class);
        verify(mock).decompress(any(), captor.capture(), any());
        return captor.getValue();
    }
}
