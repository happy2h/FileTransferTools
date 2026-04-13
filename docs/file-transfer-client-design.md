# 文件传输客户端工具 — 详细设计文档

> **技术栈**：JDK 8 · Spring Boot 2.7.6 · Netty 4.1.x  
> **版本**：v1.0  
> **日期**：2026-04-13

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [整体架构](#2-整体架构)
3. [通信协议设计](#3-通信协议设计)
4. [核心类设计](#4-核心类设计)
5. [Netty Pipeline 设计](#5-netty-pipeline-设计)
6. [SENDFILE 指令完整流程](#6-sendfile-指令完整流程)
7. [扩展性设计（COMMAND 横向扩展）](#7-扩展性设计command-横向扩展)
8. [关键实施细节与注意事项](#8-关键实施细节与注意事项)
9. [项目结构](#9-项目结构)
10. [关键代码骨架](#10-关键代码骨架)
11. [配置说明](#11-配置说明)
12. [错误处理策略](#12-错误处理策略)
13. [测试建议](#13-测试建议)

---

## 1. 背景与目标

后台服务（服务端）需要主动连接本工具（客户端）的 **TCP 7111 端口**，通过自定义二进制协议发送指令，客户端解析指令并执行对应操作后将结果返回给服务端。

**角色说明（注意）**

> 本工具在网络层面是 **TCP Server**（监听 7111 端口等待连接），但在业务层面是"客户端工具"（被服务端调用）。下文统一称本工具为 **FileTransferServer（FTS）**，称调用方为 **远端服务（Remote Service）**。

**目标：**

- 实现高性能、稳定的文件传输基础设施
- 支持多并发连接，每个连接独立处理
- COMMAND 体系可横向扩展，新增指令无需修改已有代码
- 与 Spring Boot 生命周期整合，优雅启停

---

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                     Remote Service (调用方)                   │
│  connect to FTS:7111 → send COMMAND → send payload → recv result │
└────────────────────────────┬─────────────────────────────────┘
                             │ TCP
                             ▼
┌──────────────────────────────────────────────────────────────┐
│               FileTransferServer (本工具, Netty TCP Server)   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                   Netty ChannelPipeline               │   │
│  │  [LengthFieldBasedFrameDecoder / 自定义分段解码器]      │   │
│  │  [CommandDecoder]  →  [CommandRouter]                 │   │
│  │  [CommandHandler: SendFileHandler / ...]              │   │
│  │  [ResponseEncoder]                                    │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌─────────────────┐   ┌────────────────────────────────┐   │
│  │ CommandRegistry │   │ FileScanner / Other Services   │   │
│  └─────────────────┘   └────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 通信协议设计

### 3.1 请求方向（Remote Service → FTS）

```
┌─────────────────────────────────────────────────────────────────┐
│  FIELD        │  SIZE    │  TYPE          │  说明                │
├───────────────┼──────────┼────────────────┼──────────────────────┤
│  COMMAND      │  8 bytes │  ASCII String  │  左对齐，空格填充    │
│  BODY_LENGTH  │  8 bytes │  long (BE)     │  后续 body 的字节数  │
│  BODY         │  N bytes │  JSON bytes    │  序列化的请求对象    │
└─────────────────────────────────────────────────────────────────┘
```

- **COMMAND**：固定 8 字节 ASCII，如 `SENDFILE`（恰好 8 位），不足 8 位右侧补空格，读取后 `trim()` 即可匹配。
- **BODY_LENGTH**：8 字节有符号长整型，**Big-Endian**，表示 BODY 的字节长度。若无 BODY，长度为 0。
- **BODY**：JSON 序列化的请求对象，字节长度由 BODY_LENGTH 指定。

### 3.2 响应方向（FTS → Remote Service）

```
┌─────────────────────────────────────────────────────────────────┐
│  FIELD          │  SIZE    │  TYPE      │  说明                  │
├─────────────────┼──────────┼────────────┼────────────────────────┤
│  RESP_LENGTH    │  8 bytes │  long (BE) │  后续响应 JSON 的字节数 │
│  RESP_BODY      │  N bytes │  JSON bytes│  序列化的响应对象       │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 SENDFILE 请求体（SendFileRequest）

```java
public class SendFileRequest {
    private String scanDirectory;   // 要扫描的目录路径
    private String filePattern;     // 文件名匹配模式（可选，如 "*.log"）
    private boolean recursive;      // 是否递归子目录
    private long maxFileSizeBytes;  // 文件大小上限过滤（0 表示不限）
}
```

### 3.4 SENDFILE 响应体（SendFileResponse）

```java
public class SendFileResponse {
    private boolean success;
    private String errorMessage;          // 失败时的错误信息
    private List<FileEntry> files;        // 扫描到的文件列表

    public static class FileEntry {
        private String absolutePath;
        private String fileName;
        private long fileSize;
        private long lastModified;        // epoch millis
    }
}
```

---

## 4. 核心类设计

### 4.1 类职责总览

| 类 / 接口 | 包 | 职责 |
|---|---|---|
| `NettyServerBootstrap` | `server` | Spring Bean，启动/关闭 Netty Server |
| `FtsChannelInitializer` | `server` | 初始化每条连接的 Pipeline |
| `CommandFrameDecoder` | `codec` | 自定义帧解码，拆分 COMMAND+LENGTH+BODY |
| `CommandDispatchHandler` | `handler` | 根据 COMMAND 路由到对应 Handler |
| `CommandHandler<REQ, RESP>` | `handler` | 泛型接口，所有指令 Handler 实现此接口 |
| `SendFileHandler` | `handler.impl` | 实现 SENDFILE 指令逻辑 |
| `ResponseEncoder` | `codec` | 将响应对象序列化并写入 Channel |
| `CommandRegistry` | `registry` | 维护 COMMAND → Handler 的映射 |
| `FileScanner` | `service` | 目录扫描业务逻辑 |
| `SendFileRequest` | `model` | SENDFILE 请求体 POJO |
| `SendFileResponse` | `model` | SENDFILE 响应体 POJO |
| `FtsProperties` | `config` | 配置属性（端口、线程数、目录白名单等）|

### 4.2 CommandHandler 接口

```java
public interface CommandHandler<REQ, RESP> {
    /** 该 Handler 处理的 COMMAND 名称（trim 后大写） */
    String command();

    /** 请求体的 Class，用于 JSON 反序列化 */
    Class<REQ> requestType();

    /** 执行业务逻辑 */
    RESP handle(REQ request, ChannelHandlerContext ctx);
}
```

---

## 5. Netty Pipeline 设计

```
[SocketChannel]
     │
     ▼
┌─────────────────────────────────┐
│  ReadTimeoutHandler (30s)       │  ← 防止连接长时间无数据
├─────────────────────────────────┤
│  CommandFrameDecoder            │  ← 解决 TCP 粘包/拆包
│  (InboundHandler, ByteToMsg)    │     输出: CommandFrame 对象
├─────────────────────────────────┤
│  CommandDispatchHandler         │  ← 路由至对应 CommandHandler
│  (InboundHandler, SimpleChannel │     输出: 业务响应对象
│   MessageHandler<CommandFrame>) │
├─────────────────────────────────┤
│  ResponseEncoder                │  ← 将响应对象编码为二进制
│  (OutboundHandler, MsgToBytes)  │     写入 Channel
├─────────────────────────────────┤
│  ExceptionHandler               │  ← 统一异常处理
└─────────────────────────────────┘
```

### 5.1 CommandFrameDecoder 状态机

```
State.READ_COMMAND   → 累积 8 bytes → 解析 COMMAND 字符串
         ↓
State.READ_LENGTH    → 累积 8 bytes → 解析 long BODY_LENGTH
         ↓
State.READ_BODY      → 累积 BODY_LENGTH bytes → 构造 CommandFrame
         ↓
         → 重置状态 → State.READ_COMMAND（等待下一帧）
```

> **注意**：Netty 的 `ByteToMessageDecoder` 会自动处理字节积累，**不要**在 decode 方法中调用 `buf.readBytes` 后不重置 readerIndex，务必使用 `markReaderIndex` / `resetReaderIndex` 或提前检查 `readableBytes`。

---

## 6. SENDFILE 指令完整流程

```
Remote Service                           FTS (Netty Server)
     │                                          │
     │──── TCP connect to :7111 ────────────────▶│
     │                                          │
     │──── "SENDFILE" (8 bytes) ────────────────▶│
     │──── body_length (8 bytes, long BE) ───────▶│
     │──── JSON(SendFileRequest) (N bytes) ───────▶│
     │                                          │ CommandFrameDecoder 组帧
     │                                          │ CommandDispatchHandler 路由
     │                                          │ SendFileHandler.handle()
     │                                          │   └─ FileScanner.scan()
     │                                          │
     │◀─── resp_length (8 bytes, long BE) ───────│
     │◀─── JSON(SendFileResponse) (M bytes) ──────│
     │                                          │
     │──── TCP close (可选，由业务决定) ─────────▶│
```

---

## 7. 扩展性设计（COMMAND 横向扩展）

### 7.1 CommandRegistry 自动注册

利用 Spring 的依赖注入，`CommandRegistry` 在启动时自动收集所有 `CommandHandler` Bean：

```java
@Component
public class CommandRegistry {
    private final Map<String, CommandHandler<?, ?>> handlers = new ConcurrentHashMap<>();

    @Autowired
    public CommandRegistry(List<CommandHandler<?, ?>> handlerList) {
        for (CommandHandler<?, ?> h : handlerList) {
            handlers.put(h.command().toUpperCase().trim(), h);
        }
    }

    public CommandHandler<?, ?> getHandler(String command) {
        return handlers.get(command.toUpperCase().trim());
    }
}
```

### 7.2 新增指令步骤（以 DELFILE 为例）

1. 新增 `DelFileRequest` / `DelFileResponse` POJO
2. 新增 `DelFileHandler implements CommandHandler<DelFileRequest, DelFileResponse>`，标注 `@Component`
3. 无需修改其他任何代码，Spring 自动注入，`CommandRegistry` 自动发现

---

## 8. 关键实施细节与注意事项

### 8.1 TCP 粘包 / 拆包

**这是最核心的实现难点。** Netty 的 `ByteToMessageDecoder` 提供字节累积，但需要严格按状态机读取：

- 每个状态先检查 `buf.readableBytes() >= 所需字节数`，不足则直接 `return`，等下次回调
- 读取前务必 `buf.markReaderIndex()`，读取失败时 `buf.resetReaderIndex()`
- BODY 长度可能为 0（无 body 的指令），需特殊处理，不能进入 `READ_BODY` 状态死等

### 8.2 字节序（Endianness）

- **BODY_LENGTH** 和 **RESP_LENGTH** 均使用 **Big-Endian**（网络字节序）
- 读取：`buf.readLong()`（Netty 默认 Big-Endian）
- 写入：`buf.writeLong(length)`
- 与 Java `DataInputStream.readLong()` 兼容

### 8.3 字符编码

- COMMAND 字段：**US-ASCII**，读取后 `new String(bytes, StandardCharsets.US_ASCII).trim()`
- JSON Body：**UTF-8**，`new String(bodyBytes, StandardCharsets.UTF_8)`
- 响应 JSON：`objectMapper.writeValueAsBytes(response)`（Jackson 默认 UTF-8）

### 8.4 COMMAND 字段长度与对齐

- 固定 8 字节，读取后 trim 匹配，避免空格导致路由失败
- 若 COMMAND 长度不足 8 字节（如 `DELETE` = 6），服务端发送时右侧补空格

### 8.5 线程模型

```
BossGroup (1 thread)    → 只负责 accept 新连接
WorkerGroup (N threads) → 处理已连接 Channel 的 I/O 事件
                          N 建议 = CPU核心数 * 2
BusinessExecutor        → 若文件扫描耗时，应将 FileScanner.scan()
                          提交到独立线程池，避免阻塞 WorkerGroup
```

**强烈建议**：在 `SendFileHandler.handle()` 中将耗时操作提交到 `BusinessExecutor`，并通过 `ctx.writeAndFlush()` 异步回写结果。

### 8.6 ChannelHandler 的 @Sharable 使用

- `CommandDispatchHandler`、`ResponseEncoder` 若不持有连接级别状态，可标注 `@ChannelHandler.Sharable`，节省对象创建开销
- **`CommandFrameDecoder` 不可 Sharable**，因为它维护了每个连接的解码状态（已读字节、当前状态机状态），每次 `channelRegistered` 必须是新实例

### 8.7 资源释放与内存泄漏

- `ByteToMessageDecoder` 会自动 release 未消费的 ByteBuf，但手动 `buf.readBytes(n)` 产生的新 ByteBuf 必须在使用完毕后调用 `ReferenceCountUtil.release()`
- 开启 Netty 的资源泄漏检测：`-Dio.netty.leakDetection.level=ADVANCED`（开发阶段）

### 8.8 连接管理

- 设置 `SO_KEEPALIVE = true`，保持长连接活跃
- 设置 `TCP_NODELAY = true`，禁用 Nagle 算法，减少小包延迟
- `ReadTimeoutHandler`：建议 30s，超时后关闭连接，防止僵尸连接积累
- 可维护一个 `ChannelGroup`，在应用关闭时统一关闭所有活跃连接

### 8.9 JSON 序列化

- 使用 Jackson `ObjectMapper`，配置为 Spring Bean（单例），线程安全
- 建议配置：`FAIL_ON_UNKNOWN_PROPERTIES = false`，兼容协议版本演进
- `SendFileRequest` 中的 `scanDirectory` 务必做路径合法性校验（见 8.10）

### 8.10 安全：目录遍历防护

```java
// 白名单校验
Path requestedPath = Paths.get(request.getScanDirectory()).normalize().toAbsolutePath();
for (Path allowedBase : config.getAllowedDirectories()) {
    if (requestedPath.startsWith(allowedBase)) {
        // 合法
        break;
    }
}
// 不在白名单内则拒绝，返回 success=false + errorMessage
```

- 配置文件中维护 `fts.allowed-directories` 白名单
- 禁止包含 `..` 的路径（`normalize()` 后再校验 `startsWith`）

### 8.11 大文件列表的内存控制

- 若目录下文件极多（数万+），一次性序列化到内存可能 OOM
- 建议配置 `fts.max-scan-results`（默认 10000），超出时截断并在响应中标注 `truncated=true`

### 8.12 Spring Boot 优雅关机

```java
@PreDestroy
public void shutdown() {
    channelFuture.channel().close().sync();   // 关闭监听端口
    allChannels.close().awaitUninterruptibly(); // 关闭所有活跃连接
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
    businessExecutor.shutdown();
}
```

### 8.13 日志规范

- 每条连接建立/断开记录 `remoteAddress`
- 每次 COMMAND 路由记录 COMMAND 名称、Body 长度，**不记录 Body 明文**（可能含敏感路径）
- 文件扫描完成记录耗时和文件数量
- 异常一律记录完整堆栈

---

## 9. 项目结构

```
file-transfer-client/
├── pom.xml
└── src/main/java/com/example/ftc/
    ├── FtcApplication.java                    # Spring Boot 启动类
    ├── config/
    │   └── FtsProperties.java                 # 配置属性
    ├── server/
    │   ├── NettyServerBootstrap.java          # Netty 服务器启动/关闭
    │   └── FtsChannelInitializer.java         # Pipeline 初始化
    ├── codec/
    │   ├── CommandFrameDecoder.java           # 入站：帧解码（粘包处理）
    │   └── ResponseEncoder.java              # 出站：响应编码
    ├── model/
    │   ├── CommandFrame.java                  # 解码后的帧对象
    │   ├── SendFileRequest.java
    │   └── SendFileResponse.java
    ├── handler/
    │   ├── CommandHandler.java               # 接口
    │   ├── CommandDispatchHandler.java       # COMMAND 路由
    │   └── impl/
    │       └── SendFileHandler.java          # SENDFILE 实现
    ├── registry/
    │   └── CommandRegistry.java
    └── service/
        └── FileScanner.java
```

---

## 10. 关键代码骨架

### 10.1 pom.xml 关键依赖

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
        <version>4.1.100.Final</version>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

### 10.2 NettyServerBootstrap

```java
@Component
public class NettyServerBootstrap implements InitializingBean, DisposableBean {

    @Autowired private FtsProperties props;
    @Autowired private FtsChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Override
    public void afterPropertiesSet() throws Exception {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(props.getWorkerThreads());
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .option(ChannelOption.SO_BACKLOG, 128)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childHandler(channelInitializer);

        serverChannel = b.bind(props.getPort()).sync().channel();
        log.info("FTS listening on port {}", props.getPort());
    }

    @Override
    public void destroy() throws Exception {
        serverChannel.close().sync();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
```

### 10.3 CommandFrameDecoder（状态机核心）

```java
public class CommandFrameDecoder extends ByteToMessageDecoder {

    private enum State { READ_COMMAND, READ_LENGTH, READ_BODY }

    private State state = State.READ_COMMAND;
    private String command;
    private long bodyLength;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state) {
            case READ_COMMAND:
                if (in.readableBytes() < 8) return;
                byte[] cmdBytes = new byte[8];
                in.readBytes(cmdBytes);
                command = new String(cmdBytes, StandardCharsets.US_ASCII).trim();
                state = State.READ_LENGTH;
                // fall through

            case READ_LENGTH:
                if (in.readableBytes() < 8) return;
                bodyLength = in.readLong();
                state = State.READ_BODY;
                if (bodyLength == 0) {
                    out.add(new CommandFrame(command, new byte[0]));
                    state = State.READ_COMMAND;
                    return;
                }
                // fall through

            case READ_BODY:
                if (in.readableBytes() < bodyLength) return;
                byte[] body = new byte[(int) bodyLength];
                in.readBytes(body);
                out.add(new CommandFrame(command, body));
                state = State.READ_COMMAND;
                break;
        }
    }
}
```

### 10.4 CommandDispatchHandler

```java
@ChannelHandler.Sharable
@Component
public class CommandDispatchHandler extends SimpleChannelInboundHandler<CommandFrame> {

    @Autowired private CommandRegistry registry;
    @Autowired private ObjectMapper objectMapper;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CommandFrame frame) throws Exception {
        CommandHandler handler = registry.getHandler(frame.getCommand());
        if (handler == null) {
            // 未知命令，返回错误响应后关闭连接
            ctx.writeAndFlush(buildErrorResponse("Unknown command: " + frame.getCommand()))
               .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        Object request = objectMapper.readValue(frame.getBody(), handler.requestType());
        Object response = handler.handle(request, ctx);
        ctx.writeAndFlush(response);
    }
}
```

### 10.5 ResponseEncoder

```java
public class ResponseEncoder extends MessageToByteEncoder<Object> {

    @Autowired private ObjectMapper objectMapper;

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(msg);
        out.writeLong(json.length);   // 8 bytes, Big-Endian
        out.writeBytes(json);
    }
}
```

---

## 11. 配置说明

```yaml
# application.yml
fts:
  port: 7111
  worker-threads: 8          # Netty WorkerGroup 线程数，建议 CPU * 2
  business-threads: 16       # 业务线程池大小
  read-timeout-seconds: 30   # 读超时时间
  max-scan-results: 10000    # 单次扫描最大返回文件数
  allowed-directories:       # 允许扫描的目录白名单
    - /data/files
    - /opt/transfer
```

---

## 12. 错误处理策略

| 场景 | 处理方式 |
|---|---|
| 未知 COMMAND | 返回错误响应，关闭连接 |
| JSON 反序列化失败 | 返回错误响应，关闭连接 |
| 目录不在白名单 | 返回 `success=false` + 错误信息 |
| 目录不存在/无权限 | 返回 `success=false` + 错误信息 |
| 读超时（ReadTimeoutHandler） | 关闭连接，记录日志 |
| Handler 抛出未捕获异常 | `ExceptionCaughtHandler` 捕获，返回通用错误响应，关闭连接 |
| Body 长度超过安全阈值 | 拒绝解析，关闭连接（防止 OOM 攻击）|

---

## 13. 测试建议

### 13.1 单元测试

- `CommandFrameDecoder`：使用 `EmbeddedChannel` 模拟粘包（分多次 writeInbound 碎片字节）、拆包（一次写入多帧）场景
- `SendFileHandler`：Mock `FileScanner`，验证请求转换与响应构造逻辑
- `CommandRegistry`：验证注册和路由正确性

### 13.2 集成测试

- 使用 `java.net.Socket` 编写简单测试客户端，手动构造二进制帧，验证端到端流程
- 测试目录白名单拦截是否生效
- 测试 `ReadTimeoutHandler` 是否按预期关闭连接

### 13.3 压力测试

- 使用 JMeter 或自定义多线程工具，模拟 100+ 并发连接，观察：
  - WorkerGroup 线程是否被业务操作阻塞
  - 内存占用是否稳定（无 ByteBuf 泄漏）
  - 响应延迟分布（P99）

---

*文档结束*
