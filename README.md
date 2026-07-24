# 🔄 SDT — SQL 方言翻译官

> **写一套 SQL，跑遍所有数据库。** 不改业务代码，不改 SQL 语法，SDT 在中间帮你自动"翻译"。

**SDT（SQL Dialect Translator）** 是一个透明的 SQL 方言转换中间件。你的应用照常写 MySQL 风格 SQL，SDT 在底层把它翻译成 PostgreSQL / Oracle / SQL Server 能懂的方言——**业务代码零改动**。

---

## ✨ 一句话看懂它是干嘛的

```
你写的 SQL（MySQL 风格）          SDT 自动翻译后（实际执行）
IFNULL(name, 'N/A')    ───────▶  COALESCE("name", 'N/A')
LIMIT 10               ───────▶  FETCH NEXT 10 ROWS ONLY
NOW()                  ───────▶  CURRENT_TIMESTAMP
```

---

## 🚀 两种玩法，挑一个最适合你的

| 形态 | 给谁用 | 怎么接 |
|------|--------|--------|
| **JDBC 驱动** | Java 应用 | 换 JDBC URL + 驱动类，零代码改动 |
| **SDT Proxy** | 任意语言 / 框架 | 当成一个 MySQL 连上去，完全无感 |

### 玩法 A：Java 应用（JDBC Wrapper 驱动）

只换连接串，代码里还是 MySQL 写法：

```java
// 驱动类: com.translator.jdbc.TranslatorDriver
// URL 含义: 我写 mysql 方言 → 实际连 postgresql
Connection conn = DriverManager.getConnection(
    "jdbc:translator:mysql:postgresql://localhost:5432/mydb", "user", "pwd");

// 下面这条仍是 MySQL 语法，SDT 自动翻成 PG 语法执行
ResultSet rs = conn.createStatement()
    .executeQuery("SELECT IFNULL(name, 'N/A') FROM users LIMIT 10");
```

### 玩法 B：任意语言（SDT Proxy 代理）

把它当普通 MySQL 连，Python / Go / Node 啥都不用改：

```bash
# 1. 构建并解压分发包
mvn clean package -DskipTests
tar -xzf sdtp-server/target/sdtp-server-*-SNAPSHOT.tar.gz && cd sdtp-*

# 2. 启动（默认监听 7788，配置见 config/proxy-config.yml）
./bin/start.sh start          # Linux 后台
# bin\start.bat               # Windows 前台
```

```bash
# 3. 客户端完全无感，跟连普通 MySQL 一模一样
mysql -h 127.0.0.1 -P 7788 -u root -pproxy_password mydb
```

---

## 🌍 支持哪些数据库

在 **PostgreSQL / MySQL / Oracle / SQL Server** 四大方言组之间互译，覆盖：

| 方言组 | 数据库 |
|--------|--------|
| PostgreSQL | PostgreSQL、HighGo、KingbaseES、PolarDB-PG、Greenplum、IvorySQL、KaiwuDB |
| MySQL | MySQL、Doris、PolarDB-MySQL |
| Oracle | Oracle、Oracle RAC、DM（达梦） |
| SQL Server | SQL Server |

---

## 🧩 都能翻译点啥

- ✅ **SELECT**：JOIN、子查询、UNION、GROUP BY、HAVING、ORDER BY
- ✅ **DML**：INSERT、UPDATE、DELETE
- ✅ **分页**：`LIMIT/OFFSET` ↔ `OFFSET FETCH`
- ✅ **空值函数**：`IFNULL / NVL / ISNULL` ↔ `COALESCE`
- ✅ **日期时间**：`NOW() / GETDATE() / SYSDATE()` ↔ `CURRENT_TIMESTAMP`
- ✅ **条件函数**：`DECODE`(Oracle) ↔ `CASE WHEN ... END`
- ✅ **标识符引用**：`` ` `` ↔ `""` ↔ `[]` 自动互转
- ⚡️ **直通模式**：SQL 前加 `-- direct`，原样透传，专治数据库私有语法

> ⚠️ 暂不支持：存储过程、复杂 DDL、窗口函数、递归 CTE、数据库专有特殊语法。

---

## 🏗️ 架构速览

```
业务应用 / 任意客户端
        │
        ▼
┌──────────────────────┐
│     SDT 中间件        │   捕获 SQL → Calcite 解析改写 → 生成目标方言
│  (JDBC 驱动 / Proxy)  │
└──────────┬───────────┘
           ▼
     目标数据库（PG / Oracle / MySQL / SQL Server）
```

---

## 📚 想深入了解？

- JDBC 驱动对接手册 → [docs/INTEGRATION_GUIDE.md](docs/INTEGRATION_GUIDE.md)
- SDT Proxy 使用手册 → [docs/PROXY_GUIDE.md](docs/PROXY_GUIDE.md)

---

## 📊 项目进度

- ✅ 翻译核心（Calcite 解析 → 改写 → 生成）
- ✅ JDBC Wrapper 驱动 + 集成测试
- ✅ Oracle / SQL Server 方言扩展 + DML
- ✅ SDT Proxy（Netty + Calcite + HikariCP）
- 📅 组内兼容性测试（HighGo、Kingbase、Doris）

---

## 🛠️ 构建与测试

```bash
mvn clean package -DskipTests    # 全量打包（含分发包）
mvn test                          # 运行全部测试（111+）
```

---

📄 本项目仅供学习和参考。
