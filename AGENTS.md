# SDT — SQL Dialect Translator

透明 SQL 方言转换 SDK & JDBC Wrapper 驱动（Java 应用嵌入使用）。

## 项目

- **技术栈**：Java 8、Maven 多模块、Apache Calcite（SQL 解析/改写/生成）、SLF4J+Logback、JUnit 4 + Testcontainers。
- **入口点**：
  - JDBC 驱动：`com.translator.jdbc.TranslatorDriver` — SPI 注册的 `java.sql.Driver`（`sdt-jdbc` 模块）
  - 核心翻译器：`com.translator.SqlTranslator`（`sdt-core` 模块）

## 命令

| 操作 | 命令 |
|------|------|
| 全量构建 & 安装 | `mvn clean install -DskipTests` |
| 运行所有测试 | `mvn test` |
| 运行单个测试类 | `mvn test -Dtest=SqlTranslatorTest` |
| 运行单个模块 | `mvn test -pl sdt-core -am` |

## 架构

3 个核心 Maven 模块：

| 模块 | 职责 |
|------|------|
| **sdt-core** | 翻译引擎：`SqlTranslator` 通过 Calcite 解析源 SQL → AST 改写（`SqlRewriteEngine`、`FunctionRewriteVisitor`）→ 生成目标方言 SQL。`DialectRegistry` + `DialectType` 管理已注册方言。 |
| **sdt-jdbc** | JDBC 包装器：`TranslatorDriver`（SPI）、`TranslatorConnection`、`TranslatorStatement`/`PreparedStatement`/`CallableStatement` — 拦截 SQL，通过 sdt-core 翻译，委托给真实 JDBC 执行。 |
| **sdt-metrics** | SDK 性能监控与指标暴露。 |

数据流：应用程序 → SDT JDBC Driver (TranslatorConnection) → sdt-core AST 翻译改写 → 真实 JDBC Driver (MySQL / PostgreSQL / Oracle / H2 等) → 目标数据库。

## 约定

- **包结构**：`com.translator.core`、`com.translator.jdbc`、`com.translator.metrics`。
- **日志**：`private static final Logger log = LoggerFactory.getLogger(ClassName.class);`，使用 SLF4J。
- **异常**：翻译失败抛出 `SqlTranslationException extends RuntimeException`；checked 异常已包装。
- **测试**：JUnit 4（`@Test`、`Assert.*`），集成测试使用 Testcontainers，测试方法命名描述性强。
- **SQL 直通模式**：SQL 前加 `-- direct`、`-- sdtp:direct` 或 `/* sdtp:direct */` 可跳过翻译。
- **JDBC URL 格式**：`jdbc:translator:<源方言>:<目标方言>://<主机>:<端口>/<数据库>`。
- **代码风格**：兼容 Java 8（不使用 records、`var`），接口方法加 `@Override`，公有类/方法写 Javadoc。
