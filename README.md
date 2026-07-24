# SDT — SQL Dialect Translator SDK

`sdt` (SQL Dialect Translator) 是一个透明的 SQL 方言转换 Java SDK 及 JDBC Wrapper 驱动。通过 Apache Calcite 解析 SQL AST 语法树并重写生成目标数据库方言 SQL，能够无缝帮助 Java 应用程序实现跨数据库方言平滑迁移。

## 模块结构

- **sdt-core**: 核心 SQL 解析与方言 AST 改写引擎
- **sdt-jdbc**: 符合 JDBC 标准规范 (`java.sql.Driver`) 的透明包装驱动
- **sdt-metrics**: 客户端统计指标模块
- **demo/sdt-spring-boot-jdbc-demo**: Spring Boot 集成 SDT JDBC Driver 示例项目

## 使用方式

1. 引入 Maven 依赖：
```xml
<dependency>
    <groupId>com.draven.sql.translator</groupId>
    <artifactId>sdt-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

2. 在 JDBC 连接配置中使用 SDT URL 前缀：
`jdbc:translator:mysql:postgresql://localhost:5432/mydb`
