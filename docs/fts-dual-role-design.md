# FTS 双角色节点设计方案

> **技术栈**：JDK 17 · Spring Boot 2.7.6 · Netty 4.1.x  
> **版本**：v2.0（新增节点间 P2P 中继能力）  
> **日期**：2026-04-14

---

## 目录

1. [背景与设计目标](#1-背景与设计目标)
2. [整体架构：双角色节点模型](#2-整体架构双角色节点模型)
3. [通信协议设计](#3-通信协议设计)
4. [完整交互时序](#4-完整交互时序)
5. [核心类设计](#5-核心类设计)
6. [Netty 双 Pipeline 设计](#6-netty-双-pipeline-设计)
7. [异步中继：Promise 桥接模型](#7-异步中继promise-桥接模型)
8. [指令扩展设计（CommandRegistry）](#8-指令扩展设计commandregistry)
9. [关键代码骨架](#9-关键代码骨架)
10. [关键实施细节与注意事项](#10-关键实施细节与注意事项)
11. [项目结构](#11-项目结构)
12. [配置说明](#12-配置说明)
13. [错误处理策略](#13-错误处理策略)
14. [测试建议](#14-测试建议)

---

## 1. 背景与设计目标

### 背景

在 v1.0 中，FTS 节点仅承担被动角色：监听 7111 端口，接收远端服务的指令并执行本地操作后返回结果。

v2.0 引入了**节点间 P2P 传输**能力：当远端服务在请求中指定目标节点 IP（`dstFileServeIp`），FTS 节点需要主动向另一个 FTS 节点发起连接，代理执行指令并将结果中继回远端服务。

### 设计目标

- 每个 FTS 节点同时具备 **Server 角色**（监听 7111，接受连接）和 **Client 角色**（主动连接其他节点）
- 两种角色在**同一进程**内共存，**复用同一套协议和编解码逻辑**
- 中继过程**全程异步**，不阻塞 Netty WorkerThread
- 新增指令类型时，节点间协议**自动支持**，无需修改框架代码

### 角色说明

> 本文将监听 7111 端口的 FTS 工具统称为 **FTS 节点**。在一次 SENDFILE 中继调用中，接收远端服务请求的节点称为 **Node A**，被 Node A 主动连接的目标节点称为 **Node B**。每个 FTS 节点的代码完全相同，角色由运行时上下文决定。

---

## 2. 整体架构：双角色节点模型

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Remote Service (调用方)                          │
└────────────────────────────────┬─────────────────────────────────────────┘
                                 │ TCP :7111
                    ① SENDFILE + SendFileRequest(dstFileServeIp=NodeB)
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                           FTS Node A                                     │
│                                                                          │
│  ┌────────────────────────────────────┐                                  │
│  │   Inbound Server Channel (被动)    │  ← 接收 Remote Service 的请求   │
│  │   CommandFrameDecoder              │                                  │
│  │   CommandDispatchHandler           │                                  │
│  │   SendFileHandler ──────────────── ┼──► 检测到 dstFileServeIp 非空   │
│  └────────────────────────────────────┘         │                        │
│                                                 │ 创建 Promise           │
│  ┌────────────────────────────────────┐         │ 发起出站连接           │
│  │   Outbound Client Channel (主动)   │ ◄───────┘                        │
│  │   CommandFrameEncoder              │  ② RECVFILE + ReceiveFileRequest │
│  │   ResponseFrameDecoder             │  ──────────────────────────────► │
│  │   RecvFileResponseHandler          │  ◄── ReceiveFileResponse ────── │
│  └────────────────────────────────────┘         │                        │
│                                                 │ Promise.setSuccess()   │
│                                            ③ 封装 SendFileResponse       │
│                                            写回 Remote Service           │
└──────────────────────────────────────────────────────────────────────────┘
                                 │ TCP :7111
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                           FTS Node B                                     │
│  接收 RECVFILE → ReceiveFileHandler → FileScanner → 返回响应             │
└──────────────────────────────────────────────────────────────────────────┘
```

### 关键设计决策

**两种 Channel 共享 workerGroup**：`ServerBootstrap` 和出站 `Bootstrap` 使用同一个 `NioEventLoopGroup`，不额外创建线程池，节省资源。Netty 内部对同 EventLoop 上的 Channel 通信有优化。

**全程异步，不阻塞 WorkerThread**：`SendFileHandler` 发起出站连接后立即返回，通过 `Promise` 回调在 Node B 响应到达时异步写回 Remote Service，整个过程零阻塞。

**协议对称复用**：节点间通信使用与服务端-工具端**完全相同的二进制协议**，`CommandFrameDecoder`、`ResponseEncoder` 等编解码器无需重写，仅需在出站 Pipeline 中镜像组装。

---

## 3. 通信协议设计

所有通信方向（Remote Service→Node A、Node A→Node B）统一使用同一协议规范。

### 3.1 请求帧格式

```
┌───────────────────────────────────────────────────────────────┐
│  FIELD        │  SIZE    │  类型           │  说明             │
├───────────────┼──────────┼─────────────────┼───────────────────┤
│  COMMAND      │  8 bytes │  ASCII, 右补空格 │  如 "SENDFILE"   │
│  BODY_LENGTH  │  8 bytes │  long, Big-Endian│  BODY 字节数     │
│  BODY         │  N bytes │  UTF-8 JSON      │  请求对象        │
└───────────────────────────────────────────────────────────────┘
```

### 3.2 响应帧格式

```
┌───────────────────────────────────────────────────────────────┐
│  FIELD        │  SIZE    │  类型           │  说明             │
├───────────────┼──────────┼─────────────────┼───────────────────┤
│  RESP_LENGTH  │  8 bytes │  long, Big-Endian│  响应 JSON 字节数│
│  RESP_BODY    │  N bytes │  UTF-8 JSON      │  响应对象        │
└───────────────────────────────────────────────────────────────┘
```

### 3.3 SENDFILE 指令（Remote Service → Node A）

**SendFileRequest**

```java
public class SendFileRequest {
    private String scanDirectory;       // 要扫描的目录路径
    private String filePattern;         // 文件名匹配模式（可选，如 "*.log"）
    private boolean recursive;          // 是否递归子目录
    private long maxFileSizeBytes;      // 文件大小上限（0 = 不限）
    private String dstFileServeIp;      // 目标节点 IP（非空则触发 P2P 中继）
    private int dstFileServePort;       // 目标节点端口（默认 7111）
}
```

**SendFileResponse**

```java
public class SendFileResponse {
    private boolean success;
    private String errorMessage;
    private boolean truncated;          // 结果是否被截断
    private List<FileEntry> files;

    public static class FileEntry {
        private String absolutePath;
        private String fileName;
        private long fileSize;
        private long lastModified;      // epoch millis
    }
}
```

### 3.4 RECVFILE 指令（Node A → Node B）

**ReceiveFileRequest**

```java
public class ReceiveFileRequest {
    private String scanDirectory;
    private String filePattern;
    private boolean recursive;
    private long maxFileSizeBytes;
}
```

**ReceiveFileResponse**

```java
public class ReceiveFileResponse {
    private boolean success;
    private String errorMessage;
    private boolean truncated;
    private List<SendFileResponse.FileEntry> files;
}
```

> `ReceiveFileRequest` 是 `SendFileRequest` 去掉路由字段后的子集，由 Node A 在中继时自动转换填充。

---

## 4. 完整交互时序

### 4.1 有 dstFileServeIp 的中继调用

```
Remote Service          Node A (Server+Client)          Node B
     │                          │                          │
     │─── TCP connect :7111 ────▶│                          │
     │                          │                          │
     │─── COMMAND(8B)  ──────────▶│                          │
     │─── LENGTH(8B)   ──────────▶│                          │
     │─── JSON(SendFileRequest) ─▶│                          │
     │    (含 dstFileServeIp)     │                          │
     │                          │                          │
     │                          │ [创建 Promise]            │
     │                          │ [Bootstrap.connect()]    │
     │                          │─── TCP connect :7111 ────▶│
     │                          │                          │
     │                          │─── COMMAND(RECVFILE) ────▶│
     │                          │─── LENGTH + JSON ─────────▶│
     │                          │    (ReceiveFileRequest)   │
     │                          │                          │
     │                          │                          │ [FileScanner.scan()]
     │                          │                          │
     │                          │◀── LENGTH(8B) ────────────│
     │                          │◀── JSON(ReceiveFileResp) ─│
     │                          │                          │
     │                          │ [Promise.setSuccess()]    │
     │                          │ [转换为 SendFileResponse] │
     │                          │                          │
     │◀── LENGTH(8B) ────────────│                          │
     │◀── JSON(SendFileResponse)─│                          │
     │                          │                          │
     │─── TCP close（可选）───────▶│                          │
     │                          │─── TCP close ─────────────▶│
```

### 4.2 无 dstFileServeIp 的本地调用（降级路径）

```
Remote Service          Node A
     │                    │
     │─── SENDFILE ────────▶│
     │                    │ [FileScanner.scan() in businessExecutor]
     │◀── SendFileResponse ─│
```

两条路径由 `SendFileHandler` 内部分支决定，对 Remote Service 完全透明。

---

## 5. 核心类设计

### 5.1 类职责总览

| 类 / 接口 | 包 | 职责 |
|---|---|---|
| `NettyServerBootstrap` | `server` | Spring Bean，启动/关闭 Netty Server，持有 bossGroup/workerGroup |
| `FtsServerChannelInitializer` | `server` | 初始化入站（Server）Pipeline |
| `FtsClientBootstrapFactory` | `client` | 提供出站 Bootstrap，复用 workerGroup |
| `CommandFrameDecoder` | `codec` | 入站帧解码，处理 TCP 粘包/拆包 |
| `CommandFrameEncoder` | `codec` | 出站帧编码，将 CommandFrame 序列化为二进制 |
| `ResponseFrameDecoder` | `codec` | 出站 Channel 的响应帧解码（LENGTH + JSON） |
| `ResponseEncoder` | `codec` | 入站 Channel 的响应编码（写回 Remote Service） |
| `CommandDispatchHandler` | `handler` | 路由 COMMAND 到对应 Handler |
| `CommandHandler<REQ,RESP>` | `handler` | 泛型接口，所有指令 Handler 实现此接口 |
| `SendFileHandler` | `handler.impl` | SENDFILE 实现，含本地扫描和 P2P 中继两条路径 |
| `ReceiveFileHandler` | `handler.impl` | RECVFILE 实现，Node B 侧扫描逻辑 |
| `RecvFileResponseHandler` | `handler.impl` | 出站 Channel 的响应处理，resolve Promise |
| `OutboundChannelInitializer` | `client` | 初始化出站（Client）Pipeline |
| `CommandRegistry` | `registry` | 自动收集所有 CommandHandler Bean，提供路由映射 |
| `FileScanner` | `service` | 目录扫描业务逻辑，供 SendFileHandler/ReceiveFileHandler 共用 |
| `FtsProperties` | `config` | 配置属性（端口、线程数、目录白名单等）|
| `CommandFrame` | `model` | 解码后的帧对象，含 command + bodyBytes |
| `AttributeKeys` | `model` | 统一管理 Channel AttributeKey 常量 |

### 5.2 CommandHandler 接口

```java
public interface CommandHandler<REQ, RESP> {
    /** 处理的 COMMAND 名称（大写，trim 后匹配） */
    String command();

    /** 请求体 Class，用于 JSON 反序列化 */
    Class<REQ> requestType();

    /**
     * 执行业务逻辑。
     * 同步返回 RESP 时，框架自动写回响应。
     * 若返回 null，表示 Handler 将自行异步写回（中继场景）。
     */
    RESP handle(REQ request, ChannelHandlerContext ctx);
}
```

### 5.3 AttributeKeys — Channel 上下文传递

```java
public final class AttributeKeys {
    /** 出站 Channel 携带的 Promise，用于接收 Node B 的响应 */
    public static final AttributeKey<Promise<ReceiveFileResponse>> PROMISE_KEY =
        AttributeKey.valueOf("recvFilePromise");

    /** 出站 Channel 携带的请求体 */
    public static final AttributeKey<ReceiveFileRequest> REQUEST_KEY =
        AttributeKey.valueOf("recvFileRequest");

    private AttributeKeys() {}
}
```

---

## 6. Netty 双 Pipeline 设计

### 6.1 入站 Server Pipeline（接收 Remote Service 请求）

```
[SocketChannel — 来自 Remote Service]
         │
         ▼
┌─────────────────────────────────────────┐
│  ReadTimeoutHandler (30s)               │
├─────────────────────────────────────────┤
│  CommandFrameDecoder                    │  ByteBuf → CommandFrame
│  (InboundHandler, ByteToMessage)        │
├─────────────────────────────────────────┤
│  CommandDispatchHandler                 │  CommandFrame → 路由 → 写回响应
│  (@Sharable, InboundHandler)            │
├─────────────────────────────────────────┤
│  ResponseEncoder                        │  Object → ByteBuf (LENGTH + JSON)
│  (@Sharable, OutboundHandler)           │
├─────────────────────────────────────────┤
│  ExceptionHandler                       │  统一异常处理
└─────────────────────────────────────────┘
```

### 6.2 出站 Client Pipeline（Node A 主动连接 Node B）

```
[SocketChannel — 连接至 Node B]
         │
         ▼
┌─────────────────────────────────────────┐
│  ReadTimeoutHandler (10s)               │  出站连接超时更短
├─────────────────────────────────────────┤
│  CommandFrameEncoder                    │  CommandFrame → ByteBuf
│  (OutboundHandler, MessageToBytes)      │
├─────────────────────────────────────────┤
│  ResponseFrameDecoder                   │  ByteBuf → byte[] (Node B 的响应)
│  (InboundHandler, ByteToMessage)        │
├─────────────────────────────────────────┤
│  RecvFileResponseHandler                │  解析响应 → resolve Promise
│  (InboundHandler, SimpleChannel)        │
└─────────────────────────────────────────┘
```

### 6.3 CommandFrameDecoder 状态机

```
State.READ_COMMAND   → 累积 8 bytes → 解析 COMMAND（trim）
         ↓
State.READ_LENGTH    → 累积 8 bytes → readLong() → bodyLength
         ↓ (bodyLength == 0 时跳过 READ_BODY，直接输出空 Body 帧)
State.READ_BODY      → 累积 bodyLength bytes → 构造 CommandFrame 输出
         ↓
    重置为 READ_COMMAND（等待下一帧）
```

> **注意**：每个状态进入时必须先检查 `in.readableBytes() >= 所需字节数`，不足则直接 `return`，由 Netty 在下次数据到达时重新调用 `decode`。

---

## 7. 异步中继：Promise 桥接模型

这是 v2.0 最核心的设计。`SendFileHandler` 同时涉及两条 Channel，必须用 `Promise` 将二者的异步回调串联，绝不能阻塞 WorkerThread。

### 7.1 完整代码骨架

```java
@Component
public class SendFileHandler implements CommandHandler<SendFileRequest, SendFileResponse> {

    @Autowired
    private FtsClientBootstrapFactory clientFactory;

    @Autowired
    private FileScanner fileScanner;

    @Override
    public String command() { return "SENDFILE"; }

    @Override
    public Class<SendFileRequest> requestType() { return SendFileRequest.class; }

    @Override
    public SendFileResponse handle(SendFileRequest req, ChannelHandlerContext inboundCtx) {
        
        // ── 路径一：无目标节点，本地扫描 ──────────────────────────────────────
        if (req.getDstFileServeIp() == null || req.getDstFileServeIp().isBlank()) {
            List<FileEntry> files = fileScanner.scan(req);
            return SendFileResponse.success(files);
        }

        // ── 路径二：P2P 中继 ──────────────────────────────────────────────────
        
        // 1. 创建 Promise，绑定到 inbound Channel 所在的 EventLoop
        //    确保 Listener 回调在正确线程上执行，无需额外同步
        Promise<ReceiveFileResponse> promise = inboundCtx.executor().newPromise();

        // 2. 注册 Promise 完成监听：收到 Node B 响应后，写回 Remote Service
        promise.addListener((Future<ReceiveFileResponse> f) -> {
            SendFileResponse resp;
            if (f.isSuccess()) {
                resp = convertToSendFileResponse(f.getNow());
            } else {
                resp = SendFileResponse.error("Relay failed: " + f.cause().getMessage());
            }
            // 此 Listener 运行在 inbound Channel 的 EventLoop 上，直接 writeAndFlush 安全
            inboundCtx.writeAndFlush(resp);
        });

        // 3. 构造出站请求
        ReceiveFileRequest recvReq = ReceiveFileRequest.from(req);
        int port = req.getDstFileServePort() > 0 ? req.getDstFileServePort() : 7111;

        // 4. 发起出站连接（异步，不阻塞当前 WorkerThread）
        clientFactory.bootstrap()
            .attr(AttributeKeys.PROMISE_KEY, promise)
            .attr(AttributeKeys.REQUEST_KEY, recvReq)
            .connect(req.getDstFileServeIp(), port)
            .addListener((ChannelFuture cf) -> {
                if (!cf.isSuccess()) {
                    // 连接失败，直接 fail Promise，触发上面的 Listener 写回错误响应
                    promise.setFailure(cf.cause());
                }
                // 连接成功时，OutboundChannelInitializer 中的 channelActive 
                // 会自动发送 RECVFILE 指令
            });

        // 5. 返回 null，告知框架响应将异步写入，本次调用无需同步返回值
        return null;
    }

    private SendFileResponse convertToSendFileResponse(ReceiveFileResponse recvResp) {
        SendFileResponse resp = new SendFileResponse();
        resp.setSuccess(recvResp.isSuccess());
        resp.setErrorMessage(recvResp.getErrorMessage());
        resp.setTruncated(recvResp.isTruncated());
        resp.setFiles(recvResp.getFiles());
        return resp;
    }
}
```

### 7.2 CommandDispatchHandler 对 null 返回值的处理

```java
@ChannelHandler.Sharable
@Component
public class CommandDispatchHandler extends SimpleChannelInboundHandler<CommandFrame> {

    @Autowired
    private CommandRegistry registry;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CommandFrame frame) throws Exception {
        CommandHandler handler = registry.getHandler(frame.getCommand());
        if (handler == null) {
            ctx.writeAndFlush(buildErrorResponse("Unknown command: " + frame.getCommand()))
               .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        Object request = objectMapper.readValue(frame.getBody(), handler.requestType());
        Object response = handler.handle(request, ctx);
        
        // response == null 表示 Handler 自行处理异步写回（如中继场景），框架不介入
        if (response != null) {
            ctx.writeAndFlush(response);
        }
    }
}
```

### 7.3 RecvFileResponseHandler

```java
public class RecvFileResponseHandler extends SimpleChannelInboundHandler<byte[]> {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 连接 Node B 成功后，立即发送 RECVFILE 指令
        ReceiveFileRequest req = ctx.channel().attr(AttributeKeys.REQUEST_KEY).get();
        ctx.writeAndFlush(CommandFrame.of("RECVFILE", objectMapper.writeValueAsBytes(req)));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] responseBody) throws Exception {
        Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
        try {
            ReceiveFileResponse resp = objectMapper.readValue(responseBody, ReceiveFileResponse.class);
            promise.setSuccess(resp);
        } catch (Exception e) {
            promise.setFailure(e);
        } finally {
            // 出站连接用完即关，不复用
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
        if (promise != null && !promise.isDone()) {
            promise.setFailure(cause);
        }
        ctx.close();
    }
}
```

---

## 8. 指令扩展设计（CommandRegistry）

### 8.1 自动注册机制

```java
@Component
public class CommandRegistry {
    private final Map<String, CommandHandler<?, ?>> handlers = new ConcurrentHashMap<>();

    @Autowired
    public CommandRegistry(List<CommandHandler<?, ?>> handlerList) {
        for (CommandHandler<?, ?> h : handlerList) {
            String key = h.command().toUpperCase(Locale.ROOT).trim();
            handlers.put(key, h);
            log.info("Registered CommandHandler: {} -> {}", key, h.getClass().getSimpleName());
        }
    }

    public CommandHandler<?, ?> getHandler(String command) {
        return handlers.get(command.toUpperCase(Locale.ROOT).trim());
    }
}
```

Spring 启动时自动收集所有 `CommandHandler` Bean，无需手动注册。

### 8.2 扩展新指令的步骤

以新增 `DELFILE` 指令为例，只需三步，零改动已有代码：

1. 新建 `DeleteFileRequest` / `DeleteFileResponse` POJO
2. 新建 `DeleteFileHandler implements CommandHandler<DeleteFileRequest, DeleteFileResponse>`，加 `@Component`
3. 若需节点间中继，在 `DeleteFileHandler.handle()` 内复用 v2.0 的 Promise 桥接模式

### 8.3 已有指令列表

| COMMAND | 发送方向 | Handler | 说明 |
|---|---|---|---|
| `SENDFILE` | Remote Service → Node A | `SendFileHandler` | 含本地扫描和 P2P 中继两条路径 |
| `RECVFILE` | Node A → Node B | `ReceiveFileHandler` | 纯本地扫描，返回结果给 Node A |

---

## 9. 关键代码骨架

### 9.1 FtsClientBootstrapFactory

```java
@Component
public class FtsClientBootstrapFactory {

    // 注入由 NettyServerBootstrap 创建并管理的 workerGroup
    @Autowired
    private NioEventLoopGroup workerGroup;

    @Autowired
    private OutboundChannelInitializer outboundInitializer;

    /**
     * 每次调用返回一个新的 Bootstrap 实例（Bootstrap 是轻量对象，可复用 workerGroup）
     */
    public Bootstrap bootstrap() {
        return new Bootstrap()
            .group(workerGroup)                          // 复用，不新建线程池
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(outboundInitializer);
    }
}
```

### 9.2 OutboundChannelInitializer

```java
@Component
@ChannelHandler.Sharable
public class OutboundChannelInitializer extends ChannelInitializer<SocketChannel> {

    @Autowired
    private RecvFileResponseHandler recvFileResponseHandler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
            .addLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
            // 出站：将 CommandFrame 编码为 COMMAND(8B) + LENGTH(8B) + BODY(NB)
            .addLast(new CommandFrameEncoder())
            // 入站：将 Node B 的响应帧解码为 byte[]（LENGTH(8B) + BODY(NB)）
            .addLast(new ResponseFrameDecoder())
            // 入站：处理 byte[]，resolve Promise
            .addLast(recvFileResponseHandler);
    }
}
```

### 9.3 NettyServerBootstrap（含 workerGroup 暴露）

```java
@Component
public class NettyServerBootstrap implements InitializingBean, DisposableBean {

    @Autowired
    private FtsProperties props;
    @Autowired
    private FtsServerChannelInitializer serverInitializer;

    private NioEventLoopGroup bossGroup;
    // workerGroup 声明为 Bean，供 FtsClientBootstrapFactory 注入
    @Bean
    public NioEventLoopGroup workerGroup() {
        return new NioEventLoopGroup(props.getWorkerThreads());
    }

    private Channel serverChannel;
    private ChannelGroup allChannels;

    @Override
    public void afterPropertiesSet() throws Exception {
        bossGroup  = new NioEventLoopGroup(1);
        allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup())
         .channel(NioServerSocketChannel.class)
         .option(ChannelOption.SO_BACKLOG, 256)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childHandler(serverInitializer);

        serverChannel = b.bind(props.getPort()).sync().channel();
        log.info("FTS Node listening on port {}", props.getPort());
    }

    @Override
    public void destroy() throws Exception {
        serverChannel.close().sync();
        allChannels.close().awaitUninterruptibly();
        bossGroup.shutdownGracefully();
        workerGroup().shutdownGracefully();
    }
}
```

### 9.4 CommandFrameEncoder（出站，Node A → Node B）

```java
public class CommandFrameEncoder extends MessageToByteEncoder<CommandFrame> {

    private static final int COMMAND_LEN = 8;

    @Override
    protected void encode(ChannelHandlerContext ctx, CommandFrame frame, ByteBuf out) {
        // COMMAND：固定 8 字节，右侧补空格
        byte[] cmdBytes = new byte[COMMAND_LEN];
        Arrays.fill(cmdBytes, (byte) ' ');
        byte[] src = frame.getCommand().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, cmdBytes, 0, Math.min(src.length, COMMAND_LEN));
        out.writeBytes(cmdBytes);
        // BODY_LENGTH：8 字节 Big-Endian long
        out.writeLong(frame.getBody().length);
        // BODY
        out.writeBytes(frame.getBody());
    }
}
```

### 9.5 ReceiveFileHandler（Node B 侧）

```java
@Component
public class ReceiveFileHandler implements CommandHandler<ReceiveFileRequest, ReceiveFileResponse> {

    @Autowired
    private FileScanner fileScanner;

    @Override
    public String command() { return "RECVFILE"; }

    @Override
    public Class<ReceiveFileRequest> requestType() { return ReceiveFileRequest.class; }

    @Override
    public ReceiveFileResponse handle(ReceiveFileRequest req, ChannelHandlerContext ctx) {
        try {
            List<FileEntry> files = fileScanner.scan(
                req.getScanDirectory(), req.getFilePattern(), req.isRecursive());
            return ReceiveFileResponse.success(files);
        } catch (Exception e) {
            return ReceiveFileResponse.error(e.getMessage());
        }
    }
}
```

因为 `CommandRegistry` 在启动时自动发现所有 Handler Bean，Node B 与 Node A 部署**完全相同的 jar**，无需任何区分配置。

---

## 10. 关键实施细节与注意事项

### 10.1 线程模型与 EventLoop 绑定

Promise 必须在**正确的 EventLoop** 上创建和回调，否则会引入并发问题：

```java
// ✅ 正确：绑定到 inbound Channel 的 EventLoop
Promise<ReceiveFileResponse> promise = inboundCtx.executor().newPromise();

// ❌ 错误：使用通用 executor，可能跨线程回调 inboundCtx
Promise<ReceiveFileResponse> promise = ImmediateEventExecutor.INSTANCE.newPromise();
```

`promise.addListener` 回调会在 Promise 所属的 EventLoop 线程上执行（即 inbound Channel 的线程），直接调用 `inboundCtx.writeAndFlush()` 是线程安全的，无需额外同步。

### 10.2 `@Sharable` 使用规范

| Handler | 可否 Sharable | 原因 |
|---|---|---|
| `CommandFrameDecoder` | **不可** | 维护每连接的状态机（currentState、currentCommand、bodyLength） |
| `ResponseFrameDecoder` | **不可** | 同上，维护解码状态 |
| `CommandDispatchHandler` | **可以** | 无连接级别状态，依赖注入的都是无状态 Bean |
| `ResponseEncoder` / `CommandFrameEncoder` | **可以** | 纯无状态编码 |
| `RecvFileResponseHandler` | 视实现 | 若无成员变量存储连接状态则可以 |

### 10.3 TCP 粘包/拆包处理要点

`CommandFrameDecoder` 使用状态机而非 `LengthFieldBasedFrameDecoder` 的原因是：请求帧的 COMMAND 段（8B）和 LENGTH 段（8B）是分离的两个字段，不符合标准的"length-prepended"格式，需要手动分阶段读取。

核心规则：

```java
// 每个状态进入时的防守模式
case READ_LENGTH:
    if (in.readableBytes() < 8) return;  // 字节不足，等下次回调
    bodyLength = in.readLong();           // Netty ByteBuf 默认 Big-Endian
    state = State.READ_BODY;
    if (bodyLength == 0) {
        // 无 BODY 的指令：直接输出，重置状态
        out.add(new CommandFrame(command, new byte[0]));
        state = State.READ_COMMAND;
        return;
    }
    // fall through to READ_BODY
```

### 10.4 超时控制（两层）

```java
// 层一：出站连接建立超时
clientBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

// 层二：等待 Node B 响应的读超时（在 OutboundChannelInitializer 中）
ch.pipeline().addFirst(new ReadTimeoutHandler(10, TimeUnit.SECONDS));
```

若 `ReadTimeoutHandler` 触发，会调用 `exceptionCaught`，在 `RecvFileResponseHandler.exceptionCaught` 中 fail Promise，触发向 Remote Service 写回错误响应。

### 10.5 出站连接不复用 vs 连接池

初期方案：**每次 SENDFILE 中继新建一条出站连接，用完即关**。这是最简单可靠的选择。

若同一对节点间调用频率极高（如每秒数百次），可考虑引入连接池：

```java
// 使用 commons-pool2 包装 Bootstrap
// 但要注意：池化的 Channel 必须在归还前确认状态正常（isActive() && isWritable()）
// 实现复杂度较高，建议在性能测试出现瓶颈后再引入
```

### 10.6 安全：目录白名单校验

两个 Handler（`SendFileHandler` 本地路径 + `ReceiveFileHandler`）都必须做路径合法性校验：

```java
public void validateDirectory(String requestedDir) {
    Path requested = Paths.get(requestedDir).normalize().toAbsolutePath();
    boolean allowed = props.getAllowedDirectories().stream()
        .map(d -> Paths.get(d).normalize().toAbsolutePath())
        .anyMatch(requested::startsWith);
    if (!allowed) {
        throw new SecurityException("Directory not in allowlist: " + requestedDir);
    }
}
```

### 10.7 防止 Promise 泄漏

若出站 Channel 在完成 Promise 之前意外断开，`channelInactive` 也必须 fail Promise：

```java
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    Promise<ReceiveFileResponse> promise = ctx.channel().attr(AttributeKeys.PROMISE_KEY).get();
    if (promise != null && !promise.isDone()) {
        promise.setFailure(new IOException("Connection to Node B closed unexpectedly"));
    }
}
```

### 10.8 资源释放

- `ByteToMessageDecoder`（`CommandFrameDecoder`、`ResponseFrameDecoder`）会自动 release 未消费的 ByteBuf
- 手动 `buf.readBytes(n)` 产生的新 ByteBuf 使用完毕后必须调用 `ReferenceCountUtil.release()`
- 开发阶段开启泄漏检测：`-Dio.netty.leakDetection.level=ADVANCED`

### 10.9 优雅关机顺序

```
1. 关闭 serverChannel（停止接受新的入站连接）
2. 等待已有入站请求处理完成（ChannelGroup.close()）
3. bossGroup.shutdownGracefully()
4. workerGroup.shutdownGracefully()（出站连接也由此 EventLoopGroup 管理，一并关闭）
5. businessExecutor.shutdown()（如果有独立的业务线程池）
```

### 10.10 日志规范

```
连接建立：INFO  [Node A] Accepted connection from {remoteAddr}
收到指令：INFO  [Node A] COMMAND={cmd} bodyLength={n} from {remoteAddr}
发起中继：INFO  [Node A] Relaying SENDFILE to Node B {ip}:{port}
中继完成：INFO  [Node A] Relay completed, files={count} elapsed={ms}ms
中继失败：ERROR [Node A] Relay failed: {cause}
连接断开：INFO  [Node A] Connection closed: {remoteAddr}
```

不记录 Body 明文（可能含敏感路径）；不记录文件内容；错误日志记录完整堆栈。

---

## 11. 项目结构

```
file-transfer-client/
├── pom.xml
└── src/main/java/com/example/ftc/
    ├── FtcApplication.java
    ├── config/
    │   └── FtsProperties.java
    ├── server/
    │   ├── NettyServerBootstrap.java          # Server 启停，暴露 workerGroup Bean
    │   └── FtsServerChannelInitializer.java   # 入站 Pipeline
    ├── client/
    │   ├── FtsClientBootstrapFactory.java     # 出站 Bootstrap 工厂
    │   └── OutboundChannelInitializer.java    # 出站 Pipeline
    ├── codec/
    │   ├── CommandFrameDecoder.java           # 入站：帧解码（状态机）
    │   ├── CommandFrameEncoder.java           # 出站：帧编码
    │   ├── ResponseFrameDecoder.java          # 出站 Channel 响应帧解码
    │   └── ResponseEncoder.java              # 入站 Channel 响应编码
    ├── model/
    │   ├── CommandFrame.java
    │   ├── AttributeKeys.java
    │   ├── SendFileRequest.java
    │   ├── SendFileResponse.java
    │   ├── ReceiveFileRequest.java
    │   └── ReceiveFileResponse.java
    ├── handler/
    │   ├── CommandHandler.java               # 接口
    │   ├── CommandDispatchHandler.java       # 路由（入站）
    │   └── impl/
    │       ├── SendFileHandler.java          # 含 P2P 中继逻辑
    │       ├── ReceiveFileHandler.java       # Node B 侧扫描
    │       └── RecvFileResponseHandler.java  # 出站响应处理
    ├── registry/
    │   └── CommandRegistry.java
    └── service/
        └── FileScanner.java
```

---

## 12. 配置说明

```yaml
# application.yml
fts:
  port: 7111
  worker-threads: 8           # Netty WorkerGroup 线程数，建议 CPU * 2
  business-threads: 16        # 业务线程池（本地文件扫描使用）
  read-timeout-seconds: 30    # 入站读超时
  outbound-read-timeout-seconds: 10  # 出站（Node A→B）读超时
  connect-timeout-millis: 5000       # 出站连接建立超时
  max-scan-results: 10000     # 单次扫描最大返回文件数
  allowed-directories:        # 允许扫描的目录白名单（两个节点均适用）
    - /data/files
    - /opt/transfer
```

---

## 13. 错误处理策略

| 场景 | 处理方式 |
|---|---|
| dstFileServeIp 无法连接 | Promise.setFailure → 写回 SendFileResponse(error) |
| Node B 返回 JSON 反序列化失败 | Promise.setFailure → 写回错误响应 |
| 出站读超时（ReadTimeoutHandler） | exceptionCaught → Promise.setFailure |
| Node B 连接中途断开 | channelInactive → Promise.setFailure |
| Promise 在响应写回前 Channel 断开 | addListener 中检查 channel().isActive() |
| 目录不在白名单 | 返回 success=false + errorMessage，不关闭连接 |
| Body 长度超过安全阈值（防 OOM） | 关闭连接，记录告警日志 |
| 未知 COMMAND | 返回错误响应，关闭连接 |

---

## 14. 测试建议

### 14.1 单元测试

- `CommandFrameDecoder` + `CommandFrameEncoder`：用 `EmbeddedChannel` 验证编解码对称性，模拟粘包（多次碎片写入）和拆包（一次写入多帧）场景
- `SendFileHandler`：Mock `FtsClientBootstrapFactory`，分别验证本地路径和中继路径的 Promise 流转逻辑
- `RecvFileResponseHandler`：Mock `Promise`，验证正常响应和异常两条路径是否正确 set Success/Failure

### 14.2 集成测试

在同一 JVM 中启动两个 FTS 节点（端口 7111、7112），用 `java.net.Socket` 编写简单测试客户端：

1. 连接 Node A（7111），发送含 `dstFileServeIp=127.0.0.1, dstFileServePort=7112` 的 SENDFILE 请求
2. 验证 Node A 是否正确连接 Node B（7112）并转发 RECVFILE
3. 验证最终 SendFileResponse 内容是否为 Node B 扫描结果
4. 验证 Node B 断开时 Node A 是否正确回写错误响应

### 14.3 故障注入测试

- Node B 启动后立即关闭（连接建立后断开）：验证 `channelInactive` 是否正确 fail Promise
- Node B 响应延迟超过 10s：验证 `ReadTimeoutHandler` 是否触发，Remote Service 是否收到错误响应
- 同时发起 100 个中继请求：验证 Promise 不会相互干扰（每个 Channel 独立携带自己的 AttributeKey）

---

*文档结束*
