# SDT — SQL Dialect Translator

透明 SQL 方言转换中间件：JDBC Wrapper 驱动（Java 应用）和 SDT Proxy（MySQL 协议 TCP 代理，支持任意语言客户端）。

## 项目

- **技术栈**：Java 8、Maven 多模块、Apache Calcite（SQL 解析/改写/生成）、Netty 4.1（代理）、HikariCP（连接池）、SLF4J+Logback、JUnit 4 + Testcontainers、SnakeYAML。
- **入口点**：
  - JDBC 驱动：`com.translator.jdbc.TranslatorDriver` — SPI 注册的 `java.sql.Driver`（`sdt-jdbc` 模块）
  - SDT Proxy：`com.translator.proxy.server.ProxyBootstrap` — Netty `main()`（`sdtp-server` 模块）
- **Docker**：`docker` 目录 `Dockerfile`，`docker-compose.yml` 用于本地启动 Proxy + 数据库。

## 命令

| 操作 | 命令 |
|------|------|
| 全量构建 | `mvn clean package -DskipTests` |
| 运行所有测试 | `mvn test` |
| 运行单个测试类 | `mvn test -Dtest=SqlTranslatorTest` |
| 运行单个模块 | `mvn test -pl sdtp-backend -am` |
| Docker 构建 | `docker compose -f docker/docker-compose.yml build` |
| Docker 启动 | `docker compose -f docker/docker-compose.yml up` |

## 架构

7 个 Maven 模块，全部位于 `com.translator.*`（核心）和 `com.translator.proxy.*`（代理）包下：

| 模块 | 职责 |
|------|------|
| **sdt-core** | 翻译引擎：`SqlTranslator` 通过 Calcite 解析源 SQL → AST 改写（`SqlRewriteEngine`、`FunctionRewriteVisitor`）→ 生成目标方言 SQL。`DialectRegistry` + `DialectType` 管理已注册方言。 |
| **sdt-jdbc** | JDBC 包装器：`TranslatorDriver`（SPI）、`TranslatorConnection`、`TranslatorStatement`/`PreparedStatement`/`CallableStatement` — 拦截 SQL，通过 sdt-core 翻译，委托给真实 JDBC 执行。 |
| **sdt-test** | 包含 sdt-core（单元测试 70+）和 sdt-jdbc（基于 Testcontainers 的集成测试）的测试模块。 |
| **sdtp-protocol** | MySQL 线协议编解码：`MySQLPacketDecoder`/`Encoder`、常量（`CapabilityFlags`、`CommandType`、`ColumnType`）、认证（`MySQLAuth`）。 |
| **sdtp-core** | 会话、认证、命令分发：`HandshakeHandler` → `AuthHandler` → `CommandHandler`。系统变量/SET/USE 模拟已合并进 `sdtp-protocol-mysql` 的 `MySQLSystemCatalogProvider`。 |
| **sdtp-backend** | JDBC 执行 + 翻译集成：`TranslationQueryProcessor`（调用 sdt-core）、`JdbcBackendQueryProcessor`（裸 JDBC）、`ResultSetEncoder`（将 JDBC ResultSet 映射为 MySQL 协议行）。 |
| **sdtp-server** | Netty 启动引导、YAML 配置（`ConfigLoader`、`ProxyConfig`）、分发包打包。 |

数据流（Proxy）：客户端 TCP → Netty IO → MySQLPacketDecoder → HandshakeHandler → AuthHandler → CommandHandler → (─direct 标记？裸 JDBC : Calcite 翻译) → ResultSetEncoder → MySQLPacketEncoder → 客户端。

## 约定

- **包结构**：`com.translator.{core,jdbc,proxy.*}`；代理子包与模块名一致（`com.translator.proxy.protocol`、`.core`、`.backend`、`.server`）。
- **日志**：`private static final Logger log = LoggerFactory.getLogger(ClassName.class);`，使用 SLF4J。
- **异常**：翻译失败抛出 `SqlTranslationException extends RuntimeException`；checked 异常已包装。
- **测试**：JUnit 4（`@Test`、`Assert.*`），集成测试使用 Testcontainers，测试方法命名描述性强（`testIfnullToCoalesce`）。
- **SQL 直通模式**：SQL 前加 `-- direct`、`-- sdtp:direct` 或 `/* sdtp:direct */` 可跳过翻译。
- **JDBC URL 格式**：`jdbc:translator:<源方言>:<目标方言>://<主机>:<端口>/<数据库>`。
- **配置**：通过 SnakeYAML 加载 YAML（`ProxyConfig`/`ConfigLoader`）。
- **代码风格**：兼容 Java 8（不使用 records、`var`），接口方法加 `@Override`，公有类/方法写 Javadoc（中文或英文均可）。

## 备注

对话及思考过程使用中文处理，除非对话特别指定用英文回答
