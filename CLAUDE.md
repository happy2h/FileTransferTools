# CLAIDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Compile the project
mvn clean compile

# Run the application
mvn spring-boot:run

# Package without tests
mvn clean package -DskipTests

# Run the packaged JAR
java -jar target/file-transfer-client-1.0.0.jar

# Run with a specific config profile
java -jar target/file-transfer-client-1.0.0.jar --spring.profiles.active=node2

# Run test client
java -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout)" com.example.ftc.FileTransferClientTest
```

## Architecture Overview

This is a dual-role file transfer node built with Spring Boot 3.2.0 and Netty 4.1.x. Each node can operate as both a **Server** (passive, accepts connections from remote services) and a **Client** (active, connects to other FTS nodes for P2P relay).

### Key Architectural Patterns

**Dual Role Model**: A single process hosts two independent Netty pipelines:
- **Inbound Pipeline** (`FtsChannelInitializer`): Handles requests from remote services, routes to `CommandDispatchHandler`
- **Outbound Pipeline** (`OutboundChannelInitializer`): Used by `SendFileHandler` to relay requests to other FTS nodes

**Shared EventLoopGroup**: Both server and client bootstrap share the same `NioEventLoopGroup` via `HandlerConfig.workerGroup()`, avoiding thread pool duplication.

**Async Relay via Promises**: When `SendFileHandler` detects `dstFileServeIp` in the request, it:
1. Creates a `Promise<ReceiveFileResponse>` bound to the inbound channel's EventLoop
2. Opens an outbound connection to the target node
3. Returns `null` from `handle()` to signal async processing
4. `RecvFileResponseHandler` resolves the promise when Node B responds
5. Promise listener writes the response back to the original caller

**Command Registry Pattern**: All `CommandHandler<REQ, RESP>` implementations are auto-discovered via Spring dependency injection and registered in `CommandRegistry`. Adding a new command requires only:
1. Create request/response POJOs
2. Implement `CommandHandler` with `@Component`
3. No framework code changes needed

### Pipeline Flow

**Inbound (Server Role)**:
```
Client Socket
  → ReadTimeoutHandler
  → CommandFrameDecoder (extracts COMMAND + BODY)
  → CommandDispatchHandler (routes to handler)
  → ResponseEncoder (writes response back)
```

**Outbound (Client Role)**:
```
Socket to Node B
  → ReadTimeoutHandler
  → CommandFrameEncoder (writes COMMAND + BODY)
  → CommandResponseFrameDecoder (extracts response)
  → RecvFileResponseHandler (resolves promise)
```

## Protocol

All communication uses a binary protocol:

**Request Frame**:
- COMMAND: 8 bytes, ASCII, space-padded
- BODY_LENGTH: 8 bytes, long, Big-Endian
- BODY: N bytes, JSON

**Response Frame**:
- RESP_LENGTH: 8 bytes, long, Big-Endian
- RESP_BODY: N bytes, JSON

## Important Implementation Details

**CommandHandler Contract**: Return `null` from `handle()` to signal async response processing (as in relay scenarios). `CommandDispatchHandler` checks for null and skips `writeAndFlush`.

**Channel Attributes**: Use `AttributeKeys` for passing context between handlers (e.g., `PROMISE_KEY`, `REQUEST_KEY`). Outbound channel carries these attributes to connect request data with its Promise.

**Configuration**: `FtsProperties` is bound from `application.yml` with prefix `fts`. Key settings:
- `port`: Server listening port (default 7111)
- `worker-threads`: Shared EventLoopGroup size
- `outbound-read-timeout-seconds`: Outbound connection timeout (10s)
- `connect-timeout-millis`: Outbound connection establishment timeout (5s)
- `allowed-directories`: Security whitelist for file scanning

**FileScanner Service**: Shared by both `SendFileHandler` (local) and `ReceiveFileHandler` (relay). Implements:
- Pattern matching via glob syntax
- Size filtering
- Recursive directory traversal
- Result limiting to prevent DoS

## Adding New Commands

To add a new command (e.g., `DELETEFILE`):

1. Create request POJO in `model/`:
```java
@Data
public class DeleteFileRequest {
    private String filePath;
}
```

2. Create response POJO:
```java
@Data
public class DeleteFileResponse {
    private boolean success;
    private String errorMessage;
}
```

3. Implement handler in `handler/impl/`:
```java
@Component
public class DeleteFileHandler implements CommandHandler<DeleteFileRequest, DeleteFileResponse> {
    @Override public String command() { return "DELETEFILE"; }
    @Override public Class<DeleteFileRequest> requestType() { return DeleteFileRequest.class; }
    @Override public DeleteFileResponse handle(DeleteFileRequest req, ChannelHandlerContext ctx) {
        // implementation
    }
}
```

The handler is automatically registered at startup. No routing table updates needed.

## Testing P2P Relay

To test the relay functionality between two nodes:

1. Start Node A on port 7111:
```bash
java -jar target/file-transfer-client-1.0.0.jar
```

2. Start Node B on port 7112 (using `application-node2.yml`):
```bash
java -jar target/file-transfer-client-1.0.0.jar --spring.profiles.active=node2
```

3. Send a SENDFILE request to Node A with `dstFileServeIp` pointing to Node B. Node A will relay the request to Node B and return Node B's scan results.

## Spring Bean Management

**Circular Dependency Resolution**: The `HandlerConfig` class provides the shared `workerGroup` as a separate bean to break the circular dependency chain between `NettyServerBootstrap` → `FtsChannelInitializer` → `CommandDispatchHandler` → `CommandRegistry` → `SendFileHandler` → `FtsClientBootstrapFactory`. Both server and client inject the same workerGroup bean.
