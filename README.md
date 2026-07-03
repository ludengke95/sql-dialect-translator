# SQL Dialect Translator

**SQL 方言透明转换中间件** — 让业务系统用一套 SQL 就能透明访问多种不同数据库，无需修改应用代码。

产品形态：**JDBC Wrapper 驱动**，应用只需更换 JDBC URL 和驱动类名，即可自动完成 SQL 方言转换。

---

## 项目状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | 翻译核心：Calcite 解析→改写→生成流程，PG↔MySQL 基础 SELECT | ✅ |
| Phase 2 | JDBC Wrapper 驱动 + 集成测试框架 | ✅ |
| Phase 3 | Oracle/SQL Server 方言扩展 + DML 支持 | ✅ |
| Phase 4 | 组内兼容性测试（HighGo、Kingbase、Doris 等） | 📅 待规划 |

## 快速开始

### 1. 构建项目

```bash
mvn clean install -DskipTests
```

### 2. 引入依赖

```xml
<dependency>
    <groupId>com.sql.translator</groupId>
    <artifactId>translator-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.sql.translator</groupId>
    <artifactId>translator-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. 程序化使用

```java
// 直接翻译 SQL
String result = SqlTranslator.translate(
    "SELECT IFNULL(name, 'unknown') FROM users",
    "mysql",          // 源方言
    "postgresql"      // 目标方言
);
// 结果: SELECT COALESCE(name, 'unknown') FROM users
```

### 3b. 带配置的翻译（控制输出大小写）

```java
import com.translator.core.config.TranslationConfig;

// 创建自定义配置：关键词小写 + 标识符大写
TranslationConfig config = new TranslationConfig()
    .withKeywordCase(TranslationConfig.KeywordCase.LOWER)
    .withIdentifierCase(TranslationConfig.IdentifierCase.UPPER);

// 传入配置进行翻译
String result = SqlTranslator.translate(
    "SELECT category FROM `integrated_data_resource` WHERE category != ''",
    DialectType.MYSQL,
    DialectType.POSTGRESQL,
    config
);
// 结果: select "CATEGORY" from "INTEGRATED_DATA_RESOURCE" where "CATEGORY" <> ''

// 默认配置：关键词大写 + 标识符小写（适配 PostgreSQL/Highgo）
String defaultResult = SqlTranslator.translate(
    "SELECT category FROM `integrated_data_resource` WHERE category != ''",
    DialectType.MYSQL,
    DialectType.POSTGRESQL
);
// 结果: SELECT "category" FROM "integrated_data_resource" WHERE "category" <> ''
```

### 3c. 通过 JDBC URL 控制大小写（Spring Boot 适用）

JDBC URL 支持查询参数 `keywordCase` 和 `identifierCase`，无需修改代码：

```bash
# 关键词大写 + 标识符小写（PostgreSQL/Highgo 默认推荐）
jdbc:translator:mysql:postgresql://localhost:5432/mydb

# 关键词小写 + 标识符大写
jdbc:translator:mysql:postgresql://localhost:5432/mydb?keywordCase=LOWER&identifierCase=UPPER

# 关键词大写 + 标识符保持原始大小写
jdbc:translator:mysql:postgresql://localhost:5432/mydb?identifierCase=UNCHANGED
```

**Spring Boot application.yml 示例：**

```yaml
spring:
  datasource:
    # 默认：关键词大写 + 标识符小写
    url: jdbc:translator:mysql:postgresql://localhost:5432/mydb
    driver-class-name: com.translator.jdbc.TranslatorDriver
    username: dbuser
    password: dbpass

---
spring:
  datasource:
    # 自定义：关键词小写 + 标识符大写
    url: jdbc:translator:mysql:postgresql://localhost:5432/mydb?keywordCase=LOWER&identifierCase=UPPER
    driver-class-name: com.translator.jdbc.TranslatorDriver
```

### 4. 通过 JDBC 驱动使用

```java
// 只需更换 JDBC URL 和驱动类
// 驱动类: com.translator.jdbc.TranslatorDriver
// URL: jdbc:translator:mysql:postgresql://localhost:5432/mydb

Connection conn = DriverManager.getConnection(
    "jdbc:translator:mysql:postgresql://localhost:5432/mydb",
    "user", "password"
);

// 发送 MySQL 方言的 SQL，驱动会自动翻译为 PostgreSQL 方言
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery(
    "SELECT `id`, `name` FROM `users` WHERE `age` > 18 LIMIT 10"
);
// 实际在 PostgreSQL 上执行的是翻译后的 SQL
```

> 📖 **完整对接指南** → 详见 [对接手册](docs/INTEGRATION_GUIDE.md)

---

## 支持的数据库

### PostgreSQL 方言组（10 个库）
PostgreSQL、HighGo（瀚高）、KingbaseES、PolarDB PG、Greenplum、TimescaleDB、PostGIS、IvorySQL、QuestDB、KaiwuDB

### MySQL 方言组（3 个库）
MySQL、Doris、PolarDB MySQL

### Oracle 方言组（4 个库）
Oracle、Oracle RAC、Oracle Spatial、DM（达梦）

### SQL Server 方言组（1 个库）
SQL Server

## 核心能力

### 已支持的 SQL 转换

| 类型 | 说明 |
|------|------|
| SELECT 查询 | JOIN、子查询、UNION、GROUP BY、HAVING、ORDER BY |
| DML 语句 | INSERT、UPDATE、DELETE |
| 分页语法 | `LIMIT/OFFSET` ↔ `OFFSET FETCH`（通 过 Calcite 方言自动处理） |
| 空值函数 | `IFNULL` ↔ `COALESCE`、`NVL` ↔ `COALESCE`、`ISNULL` ↔ `COALESCE` |
| 日期时间函数 | `NOW()` ↔ `CURRENT_TIMESTAMP`、`GETDATE()` ↔ `CURRENT_TIMESTAMP`、`SYSDATE()` ↔ `CURRENT_TIMESTAMP` |
| 条件函数 | `DECODE`（Oracle） ↔ `CASE WHEN ... END` |
| SQL Server TOP | `SELECT TOP n` ↔ `SELECT ... LIMIT n` |
| 标识符引用 | MySQL 反引号 `` ` ``、SQL Server 方括号 `[]`、PG/Oracle 双引号 `""` 自动互转 |
| **输出大小写控制** | 通过 `TranslationConfig` 配置关键词大写/小写、标识符大写/小写/保持原样 |
| **未引用标识符** | 自动检测未引用的表名/列名并补充引号，避免 PostgreSQL 大小写敏感错误 |

### 暂不支持
- 存储过程、复杂 DDL
- 窗口函数、递归 CTE
- 数据库专有特殊语法（如 PG 的 `ON CONFLICT`、Oracle 的 `CONNECT BY`）

## 架构概述

```
业务应用
   │
   ▼
DriverManager.getConnection("jdbc:translator:mysql:postgresql://...")
   │
   ▼
TranslatorDriver → TranslatorConnection → TranslatorStatement
   │                                              │
   │                                  在 execute() 内部：
   │                                    1. 捕获原始 SQL
   │                                    2. 调用翻译引擎 → 目标 SQL
   │                                    3. 通过真实 Statement 执行
   │                                    4. 返回真实 ResultSet
   │
   ▼
真实 JDBC 驱动（如 PostgreSQL Driver）
```

## 模块说明

| 模块 | 说明 |
|------|------|
| `translator-core` | 翻译引擎核心：SQL 解析 → AST 改写 → 目标方言生成 |
| `translator-jdbc` | JDBC Wrapper 驱动：Driver、Connection、Statement、PreparedStatement |
| `translator-test` | 单元测试（70+）和集成测试（基于 Testcontainers） |

## 构建与测试

```bash
# 构建全部模块
mvn clean install -DskipTests

# 运行单元测试
mvn test

# 运行集成测试（需要 Docker Desktop）
mvn test -Dtest=TranslatorDriverIntegrationTest
```

## 许可证

本项目仅供学习和参考。
