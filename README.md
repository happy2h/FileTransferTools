# FileTransferClient

基于设计文档实现的文件传输客户端工具，使用 Spring Boot 3.2.0 + Netty 4.1.100 构建。

## 项目结构

```
file-transfer-client/
├── pom.xml                                    # Maven 配置
├── src/main/java/com/example/ftc/
│   ├── FtcApplication.java                    # Spring Boot 启动类
│   ├── config/
│   │   ├── FtsProperties.java                 # 配置属性
│   │   └── JacksonConfig.java                 # Jackson 配置
│   ├── server/
│   │   ├── NettyServerBootstrap.java          # Netty 服务器启动/关闭
│   │   └── FtsChannelInitializer.java         # Pipeline 初始化
│   ├── codec/
│   │   ├── CommandFrameDecoder.java           # 帧解码器（TCP 粘包处理）
│   │   └── ResponseEncoder.java              # 响应编码器
│   ├── model/
│   │   ├── CommandFrame.java                  # 命令帧对象
│   │   ├── SendFileRequest.java              # SENDFILE 请求
│   │   └── SendFileResponse.java             # SENDFILE 响应
│   ├── handler/
│   │   ├── CommandHandler.java               # 命令处理器接口
│   │   ├── CommandDispatchHandler.java       # 命令分发器
│   │   └── impl/
│   │       └── SendFileHandler.java          # SENDFILE 实现
│   ├── registry/
│   │   └── CommandRegistry.java              # 命令注册表
│   └── service/
│       └── FileScanner.java                   # 文件扫描服务
├── src/main/resources/
│   └── application.yml                        # 应用配置
└── src/test/java/com/example/ftc/
    └── ClientTest.java                        # 简单测试客户端
```

## 功能特性

- **Netty TCP Server**: 监听 7111 端口，支持多并发连接
- **自定义二进制协议**: COMMAND(8字节) + LENGTH(8字节) + BODY(N字节)
- **SENDFILE 命令**: 扫描目录并返回文件列表
- **命令路由**: 支持横向扩展新增命令
- **安全防护**: 目录白名单验证、路径规范化、大小限制
- **优雅关机**: 与 Spring Boot 生命周期整合

## 配置说明

```yaml
fts:
  port: 7111                        # 监听端口
  worker-threads: 8                  # Netty Worker 线程数
  business-threads: 16               # 业务线程池大小
  read-timeout-seconds: 30           # 读超时时间
  max-scan-results: 10000            # 单次扫描最大返回文件数
  max-body-size: 10485760            # 最大 Body 大小 (10MB)
  allowed-directories:               # 允许扫描的目录白名单
    - /data/files
    - /opt/transfer
```

## 编译运行

```bash
# 编译项目
mvn clean compile

# 运行服务器
mvn spring-boot:run

# 或打包后运行
mvn clean package
java -jar target/file-transfer-client-1.0.0.jar
```

## 测试

```bash
# 编译并运行测试客户端
mvn test-compile
java -cp target/classes:target/test-classes:$(mvn dependency:build-classpath -DincludeScope=test -q) com.example.ftc.ClientTest
```

## 通信协议

### 请求格式 (Remote Service → FTS)

```
┌─────────────────────────────────────────────────────────────────┐
│  FIELD        │  SIZE    │  TYPE          │  说明                │
├───────────────┼──────────┼────────────────┼──────────────────────┤
│  COMMAND      │  8 bytes │  ASCII (8)     │  左对齐，空格填充    │
│  BODY_LENGTH  │  8 bytes │  long (BE)     │  后续 body 的字节数  │
│  BODY         │  N bytes │  JSON bytes    │  序列化的请求对象    │
└─────────────────────────────────────────────────────────────────┘
```

### 响应格式 (FTS → Remote Service)

```
┌─────────────────────────────────────────────────────────────────┐
│  FIELD          │  SIZE    │  TYPE      │  说明                  │
├─────────────────┼──────────┼────────────┼────────────────────────┤
│  RESP_LENGTH    │  8 bytes │  long (BE) │  后续响应 JSON 的字节数 │
│  RESP_BODY      │  N bytes │  JSON bytes│  序列化的响应对象       │
└─────────────────────────────────────────────────────────────────┘
```

## SENDFILE 命令

### 请求体

```json
{
  "scanDirectory": "/path/to/scan",
  "filePattern": "*.log",
  "recursive": true,
  "maxFileSizeBytes": 0
}
```

### 响应体

```json
{
  "success": true,
  "errorMessage": null,
  "files": [
    {
      "absolutePath": "/path/to/file.log",
      "fileName": "file.log",
      "fileSize": 1024,
      "lastModified": 1712697600000
    }
  ],
  "truncated": false
}
```

## 扩展新命令

1. 创建请求和响应 POJO 类
2. 实现 `CommandHandler<REQ, RESP>` 接口
3. 使用 `@Component` 注解标记
4. 无需修改其他代码，自动注册

## 技术栈

- JDK 17
- Spring Boot 3.2.0
- Netty 4.1.100.Final
- Lombok
