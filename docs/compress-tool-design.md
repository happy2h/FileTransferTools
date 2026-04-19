# 文件解压功能设计方案

## 1. 背景与目标

在现有 `SENDFILE` 指令处理流程中集成解压能力。客户端在发送 `SENDFILE` 请求时，可在请求体内附带解压配置（`decompressConfig`）；`SendFileHandler` 在本地扫描完文件后，对匹配到的压缩文件执行解压，并将解压后的文件列表写入响应。

**解压能力以工具类（`FileDecompressor`）形式实现**，不引入新的 COMMAND，不改变协议帧结构。

### 支持的格式

| 格式 | 扩展名 | 实现来源 |
|------|--------|----------|
| ZIP  | `.zip` | JDK `java.util.zip` |
| GZIP（单文件压缩） | `.gz` | JDK `java.util.zip.GZIPInputStream` |
| TAR | `.tar` | Apache Commons Compress |
| TAR + GZIP | `.tar.gz` / `.tgz` | Apache Commons Compress |
| TAR + BZIP2 | `.tar.bz2` | Apache Commons Compress |
| TAR + XZ | `.tar.xz` | Apache Commons Compress + XZ for Java |
| RAR（RAR4） | `.rar` | junrar |
| RAR5 / 加密格式 / 7-Zip | `.rar5` / `.7z` | 降级调用系统 `7z` 命令 |

---

## 2. 变更范围

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `model/SendFileRequest.java` | 新增字段 | 加入 `DecompressConfig decompressConfig`（可选，为 null 时不解压） |
| `model/DecompressConfig.java` | 新增 | 解压配置 POJO，内嵌于请求体 |
| `model/SendFileResponse.java` | 不变 | `files` 字段直接承载解压后的条目列表 |
| `service/FileDecompressor.java` | 新增 | 解压工具类（Spring `@Component`），对外暴露单一入口方法 |
| `service/decompressor/` | 新增目录 | 各格式策略实现 |
| `handler/impl/SendFileHandler.java` | 小幅修改 | `handleLocalScan()` 在扫描后追加解压步骤 |
| `config/FtsProperties.java` | 新增嵌套类 | `Decompress` 全局默认限制配置 |
| `pom.xml` | 新增依赖 | commons-compress / xz / junrar |

---

## 3. 数据模型

### 3.1 DecompressConfig（新增）

```java
@Data
public class DecompressConfig {

    /**
     * 需要解压的文件名 glob 模式列表，与 SendFileRequest.filePattern 独立匹配。
     * 示例：["*.zip", "*.tar.gz", "backup-*.rar"]
     * 为空时对所有扫描到的压缩文件执行解压。
     */
    private List<String> compressPatterns = List.of("*.zip", "*.tar.gz", "*.tgz",
            "*.tar.bz2", "*.tar.xz", "*.gz", "*.rar", "*.7z");

    /**
     * 解压输出目录（绝对路径）。
     * 为 null 时默认解压到压缩文件所在目录的同名子目录。
     * 必须在 allowedDirectories 范围内。
     */
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
```

### 3.2 SendFileRequest（新增字段）

```java
@Data
public class SendFileRequest {
    private String scanDirectory;
    private String filePattern;
    private boolean recursive = false;
    private long maxFileSizeBytes = 0;
    private String dstFileServeIp;
    private int dstFileServePort = 7111;

    // ── 新增 ──────────────────────────────────────────────
    /** 不为 null 时，对扫描结果中匹配 compressPatterns 的文件执行解压 */
    private DecompressConfig decompressConfig;
}
```

---

## 4. 处理流程

### 4.1 集成点：handleLocalScan()

```
SendFileHandler.handleLocalScan(request)
  │
  ├─ 1. 路径校验（allowedDirectories）
  ├─ 2. fileScanner.scan(...)  →  ScanResult（原始文件列表）
  │
  ├─ 3. if (request.getDecompressConfig() != null)
  │       │
  │       ├─ 从 ScanResult.files 中过滤匹配 compressPatterns 的条目
  │       ├─ 校验 targetDirectory 在 allowedDirectories 内
  │       ├─ 对每个匹配文件调用 fileDecompressor.decompress(file, config)
  │       │     → 返回 List<FileEntry>（解压后的文件条目）
  │       ├─ 汇总所有解压结果，合并到最终 files 列表
  │       │   （是否保留原压缩文件条目由 deleteSourceAfterExtract 决定）
  │       └─ 统计 truncated 标志
  │
  └─ 4. 构建 SendFileResponse（files = 解压后列表 或 原始列表）
```

### 4.2 修改后的 handleLocalScan 伪代码

```java
private SendFileResponse handleLocalScan(SendFileRequest request, long startTime) {
    // ... 现有路径校验 ...

    FileScanner.ScanResult result = fileScanner.scan(...);
    if (!result.isSuccess()) return SendFileResponse.error(result.getErrorMessage());

    // ── 新增解压步骤 ─────────────────────────────────────
    DecompressConfig cfg = request.getDecompressConfig();
    if (cfg != null) {
        List<FileEntry> finalFiles = new ArrayList<>();
        boolean truncated = result.isTruncated();

        for (FileEntry entry : result.getFiles()) {
            if (matchesCompressPatterns(entry.getFileName(), cfg.getCompressPatterns())) {
                // 对压缩文件执行解压，收集提取出的文件条目
                List<FileEntry> extracted = fileDecompressor.decompress(entry, cfg, properties);
                finalFiles.addAll(extracted);
                if (!cfg.isDeleteSourceAfterExtract()) {
                    finalFiles.add(entry); // 保留原压缩文件条目
                }
            } else {
                finalFiles.add(entry);    // 非压缩文件直接保留
            }
        }
        return SendFileResponse.success(finalFiles, truncated);
    }
    // ── 原有逻辑不变 ──────────────────────────────────────
    return SendFileResponse.success(result.getFiles(), result.isTruncated());
}
```

---

## 5. FileDecompressor 工具类设计

### 5.1 对外接口

```java
@Component
public class FileDecompressor {

    private final Map<String, Decompressor> registry;
    private final FtsProperties properties;

    /** Spring 自动注入所有 Decompressor 实现，按扩展名建立注册表 */
    public FileDecompressor(List<Decompressor> decompressors, FtsProperties properties) { ... }

    /**
     * 对单个压缩文件执行解压。
     *
     * @param entry    待解压的文件条目（含绝对路径）
     * @param cfg      本次请求的解压配置
     * @param props    全局属性（含全局默认限制、allowedDirectories）
     * @return         解压后生成的文件条目列表
     * @throws DecompressException  格式不支持、安全检查失败、IO 错误时抛出
     */
    public List<FileEntry> decompress(FileEntry entry, DecompressConfig cfg,
                                      FtsProperties props) throws DecompressException { ... }
}
```

### 5.2 Decompressor 策略接口

```java
public interface Decompressor {
    /** 该实现支持的扩展名（小写），复合扩展名如 "tar.gz" */
    List<String> supportedExtensions();

    /**
     * 执行解压，解压前后均需经过安全检查（由 FileDecompressor 统一调用）。
     */
    List<FileEntry> decompress(Path sourceFile, Path targetDir,
                               DecompressLimits limits) throws DecompressException;
}
```

`DecompressLimits` 是从 `DecompressConfig` 与全局配置合并后的最终限制值，避免各实现重复合并逻辑。

### 5.3 策略实现清单

| 类名 | 扩展名 | 核心 API |
|------|--------|----------|
| `ZipDecompressor` | `zip` | `java.util.zip.ZipInputStream` |
| `GzipDecompressor` | `gz` | `java.util.zip.GZIPInputStream` |
| `TarDecompressor` | `tar` / `tar.gz` / `tgz` / `tar.bz2` / `tar.xz` | `org.apache.commons.compress.archivers.tar.TarArchiveInputStream` |
| `RarDecompressor` | `rar` | `com.github.junrar.Junrar`；RAR5/加密降级 `7z` 命令 |
| `SevenZipDecompressor` | `7z` | `org.apache.commons.compress.archivers.sevenz.SevenZArchiveFile` |

### 5.4 格式识别

```java
// FileDecompressor 内部
private String detectExtension(String fileName, String formatHint) {
    if (formatHint != null) return formatHint.toLowerCase();
    String lower = fileName.toLowerCase();
    // 优先匹配复合扩展名
    for (String ext : List.of("tar.gz", "tar.bz2", "tar.xz", "tgz")) {
        if (lower.endsWith("." + ext)) return ext;
    }
    int dot = lower.lastIndexOf('.');
    return dot >= 0 ? lower.substring(dot + 1) : "";
}
```

---

## 6. 安全设计

### 6.1 Path Traversal（Zip Slip）防御

每个 Decompressor 实现在写出文件前必须调用统一校验方法：

```java
// 每个实现均调用，targetDir 为规范化后的解压根目录
static Path safeResolve(Path targetDir, String entryName) throws DecompressException {
    Path resolved = targetDir.resolve(entryName).normalize();
    if (!resolved.startsWith(targetDir)) {
        throw new DecompressException("Path traversal detected in entry: " + entryName);
    }
    return resolved;
}
```

### 6.2 ZIP Bomb 防御

`DecompressLimits` 三重限制，在每次写入文件时累计检查：

```java
// 解压每个条目时检查
if (++entryCount > limits.maxEntries()) throw new DecompressException("Entry count exceeded");
if (entrySize > limits.maxEntrySizeBytes()) throw new DecompressException("Single entry too large");
totalSize += entrySize;
if (totalSize > limits.maxTotalSizeBytes()) throw new DecompressException("Total size exceeded");
```

### 6.3 目标目录白名单

`targetDirectory` 在 `SendFileHandler` 中统一校验，必须位于 `allowedDirectories` 内，不在工具类内重复校验：

```java
if (cfg.getTargetDirectory() != null) {
    Path target = Path.of(cfg.getTargetDirectory()).normalize().toAbsolutePath();
    boolean allowed = properties.getAllowedDirectoryPaths().stream()
            .anyMatch(target::startsWith);
    if (!allowed) return SendFileResponse.error("Target directory not in allowed list");
}
```

### 6.4 系统命令调用（7z 降级）防御命令注入

参数通过数组方式传入，文件路径不拼接到 shell 字符串：

```java
// 正确：数组形式，OS 不经 shell 解释参数
ProcessBuilder pb = new ProcessBuilder(
    "7z", "x", sourceFile.toString(), "-o" + targetDir.toString(), "-y"
);
pb.redirectErrorStream(true);
Process proc = pb.start();
```

---

## 7. 全局默认配置

在 `FtsProperties` 中新增嵌套配置类，提供全局兜底值：

```java
@Data
public static class Decompress {
    private boolean fallbackEnabled = true;   // 允许降级调用系统 7z
    private int defaultMaxEntries = 10_000;
    private long defaultMaxEntrySizeMb = 500;
    private long defaultMaxTotalSizeGb = 2;
}
```

`application.yml` 对应配置：

```yaml
fts:
  decompress:
    fallback-enabled: true
    default-max-entries: 10000
    default-max-entry-size-mb: 500
    default-max-total-size-gb: 2
```

`DecompressLimits` 的最终值 = 请求中 `DecompressConfig` 的值（非零时） OR 全局默认值：

```java
DecompressLimits limits = new DecompressLimits(
    cfg.getMaxEntries() > 0 ? cfg.getMaxEntries() : props.getDecompress().getDefaultMaxEntries(),
    cfg.getMaxEntrySizeBytes() > 0 ? cfg.getMaxEntrySizeBytes()
            : props.getDecompress().getDefaultMaxEntrySizeMb() * 1024 * 1024,
    cfg.getMaxTotalSizeBytes() > 0 ? cfg.getMaxTotalSizeBytes()
            : props.getDecompress().getDefaultMaxTotalSizeGb() * 1024 * 1024 * 1024
);
```

---

## 8. 依赖新增（pom.xml）

```xml
<!-- TAR / TAR.GZ / TAR.BZ2 / TAR.XZ / 7Z -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-compress</artifactId>
    <version>1.26.1</version>
</dependency>

<!-- TAR.XZ 解压支持 -->
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.9</version>
</dependency>

<!-- RAR4 纯 Java 解压 -->
<dependency>
    <groupId>com.github.junrar</groupId>
    <artifactId>junrar</artifactId>
    <version>7.5.5</version>
</dependency>
```

ZIP / GZIP 使用 JDK 内置实现，无需额外依赖。

---

## 9. 安全要求汇总

| 威胁 | 防御位置 | 防御措施 |
|------|----------|----------|
| Zip Slip（路径穿越） | 各 Decompressor 实现 | `safeResolve()` 规范化并校验条目路径 |
| ZIP Bomb（条目数爆炸） | 各 Decompressor 实现 | `maxEntries` 计数检查 |
| ZIP Bomb（单文件膨胀） | 各 Decompressor 实现 | `maxEntrySizeBytes` 写入时检查 |
| ZIP Bomb（总量爆炸） | 各 Decompressor 实现 | `maxTotalSizeBytes` 累计检查 |
| 越权目标目录 | `SendFileHandler` | `targetDirectory` 必须在 `allowedDirectories` 内 |
| 命令注入（7z 降级） | `RarDecompressor` | `ProcessBuilder` 数组传参，不拼接 shell 字符串 |

---

## 10. 测试计划

### 单元测试

- `ZipDecompressor`：正常 ZIP、含子目录、Zip Slip 条目（应抛异常）、超出 maxEntries
- `TarDecompressor`：`.tar` / `.tar.gz` / `.tar.bz2` / `.tar.xz` 各格式
- `GzipDecompressor`：正常 `.gz` 单文件
- `RarDecompressor`：RAR4 正常；RAR5 触发降级路径（mock ProcessBuilder）
- `SevenZipDecompressor`：正常 `.7z`
- `FileDecompressor`：扩展名识别（含复合扩展名）、格式不支持时的错误响应
- `SendFileHandler`：`decompressConfig == null` 时不触发解压（回归验证）

### 集成测试

```bash
# 1. 准备测试压缩文件
cd /tmp && zip test.zip *.txt

# 2. 启动节点
java -jar target/file-transfer-client-1.0.0.jar

# 3. 发送带 decompressConfig 的 SENDFILE 请求
# 请求体示例：
{
  "scanDirectory": "/tmp",
  "filePattern": "*.zip",
  "recursive": false,
  "decompressConfig": {
    "compressPatterns": ["*.zip"],
    "targetDirectory": "/tmp/extracted",
    "deleteSourceAfterExtract": false,
    "maxEntries": 1000
  }
}
# 预期：响应 files 列表包含 /tmp/extracted/ 下的解压条目
```

---

## 11. 实现步骤

1. `pom.xml` 新增三项依赖（commons-compress / xz / junrar）
2. 新增 `model/DecompressConfig.java`
3. `model/SendFileRequest.java` 增加 `decompressConfig` 字段
4. `config/FtsProperties.java` 新增 `Decompress` 嵌套配置类，`application.yml` 补充配置
5. 新增 `service/decompressor/Decompressor.java` 接口 + `DecompressLimits.java` record
6. 新增各格式实现：`ZipDecompressor` / `GzipDecompressor` / `TarDecompressor` / `RarDecompressor` / `SevenZipDecompressor`（均标注 `@Component`）
7. 新增 `service/FileDecompressor.java`（`@Component`，注入所有 Decompressor，按扩展名建注册表）
8. 修改 `handler/impl/SendFileHandler.java`：注入 `FileDecompressor`，在 `handleLocalScan()` 尾部追加解压步骤
9. 编写单元测试 & 集成测试
