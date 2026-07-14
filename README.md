# SDT — SQL Dialect Translator

**SQL 方言透明转换中间件** — 让业务系统用一套 SQL 就能透明访问多种不同数据库，无需修改应用代码。

**两种产品形态：**

| 形态 | 适用场景 | 接入方式 |
|------|---------|---------|
| **JDBC Wrapper 驱动** | Java 应用 | 更换 JDBC URL + 驱动类名 |
| **SDT Proxy（SDTP）** | 任意语言/框架 | 连接 TCP 端口，对业务透明 |

---

## 项目状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | 翻译核心：Calcite 解析→改写→生成流程 | ✅ |
| Phase 2 | JDBC Wrapper 驱动 + 集成测试 | ✅ |
| Phase 3 | Oracle/SQL Server 方言扩展 + DML | ✅ |
| Phase 4 | 组内兼容性测试（HighGo、Kingbase、Doris） | 📅 待规划 |
| Phase 5 | **SDT Proxy**：MySQL 协议代理，Netty+Calcite+HikariCP | ✅ |

---

## 快速开始

### 1. 构建

```bash
mvn clean package -DskipTests
```

### 2a. JDBC Wrapper 驱动（Java 应用）

```java
// 驱动类: com.translator.jdbc.TranslatorDriver
// URL:    jdbc:translator:mysql:postgresql://host:5432/db

Connection conn = DriverManager.getConnection(
    "jdbc:translator:mysql:postgresql://localhost:5432/mydb", "user", "password");
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT IFNULL(name, 'N/A') FROM users LIMIT 10");
// 实际在 PostgreSQL 上执行: SELECT COALESCE("name", 'N/A') FROM "users" FETCH NEXT 10 ROWS ONLY
```

### 2b. SDT Proxy（任意语言，TCP 代理）

```bash
# 1. 解压
tar -xzf sdtp-server/target/sdtp-server-*-SNAPSHOT.tar.gz
cd sdtp-*

# 2. 修改配置
vim config/proxy-config.yml

# 3. 启动
./bin/start.sh start          # Linux 后台
./bin/start.sh start-fg       # Linux 前台（Docker）
bin\start.bat                 # Windows 前台
```

```bash
# 4. 客户端连接（和连接普通 MySQL 一样）
mysql -h 127.0.0.1 -P 3306 -u root -pproxy_password mydb
```

> 📖 **完整文档**：JDBC 驱动 → [对接手册](docs/INTEGRATION_GUIDE.md) | SDT Proxy → [Proxy 手册](docs/PROXY_GUIDE.md)

---

## 支持的数据库

| 方言组 | 数据库 |
|--------|--------|
| PostgreSQL | PostgreSQL、HighGo、KingbaseES、PolarDB PG、Greenplum、IvorySQL、KaiwuDB |
| MySQL | MySQL、Doris、PolarDB MySQL |
| Oracle | Oracle、Oracle RAC、Oracle Spatial、DM（达梦） |
| SQL Server | SQL Server |

## SQL 转换能力

| 类型 | 说明 |
|------|------|
| SELECT 查询 | JOIN、子查询、UNION、GROUP BY、HAVING、ORDER BY |
| DML 语句 | INSERT、UPDATE、DELETE |
| 分页语法 | `LIMIT/OFFSET` ↔ `OFFSET FETCH` |
| 空值函数 | `IFNULL`/`NVL`/`ISNULL` ↔ `COALESCE` |
| 日期时间 | `NOW()`/`GETDATE()`/`SYSDATE()` ↔ `CURRENT_TIMESTAMP` |
| 条件函数 | `DECODE`(Oracle) ↔ `CASE WHEN ... END` |
| TOP 语法 | `SELECT TOP n` ↔ `SELECT ... LIMIT n` |
| 标识符引用 | `` ` `` ↔ `""` ↔ `[]` 自动互转 |
| 大小写控制 | 关键词 UPPER/LOWER，标识符 LOWER/UPPER/UNCHANGED |

### 直通模式（跳过翻译）

在 SQL 开头加 `-- direct` 注释，该条 SQL 原样直达目标数据库：

```sql
-- direct
SELECT pg_sleep(1), pg_backend_pid()   -- PostgreSQL 专有，不翻译
```

也支持 `-- sdtp:direct` 和 `/* sdtp:direct */`。

### 多后端模式下的事务与切换数据库原则

在多后端模式下，客户端可能会在开启事务的连接状态下切换数据库（例如：使用 `USE <new_db>` 语句或 `COM_INIT_DB` 报文）。为了保证底层物理连接的事务一致性并防止连接泄漏或事务状态失效残留，SDT Proxy 遵循以下设计原则：
* **隐式回滚（Implicit Rollback）**：一旦发生切换数据库的操作，如果当前会话在原数据库后端上持有未提交的活跃事务连接，Proxy 会**自动且隐式地在旧连接上执行回滚（`rollback`）**并将其释放归还连接池，清除通道事务状态，随后再安全地切换到新数据库。

### 暂不支持
- 存储过程、复杂 DDL
- 窗口函数、递归 CTE
- 数据库专有特殊语法

---

## 模块说明

| 模块 | 说明 |
|------|------|
| `sdt-core` | 翻译引擎核心：SQL 解析 → AST 改写 → 目标方言生成 |
| `sdt-jdbc` | JDBC Wrapper 驱动：Driver、Connection、Statement |
| `sdt-test` | 单元测试（70+）+ 集成测试（Testcontainers） |
| `sdtp-protocol` | MySQL 线协议编解码：Packet、Decoder、Encoder |
| `sdtp-core` | 会话管理、握手认证、命令分发、系统变量拦截 |
| `sdtp-backend` | JDBC 执行器、ResultSet 流式映射、翻译集成 |
| `sdtp-server` | Netty 启动引导、YAML 配置加载、分发包 |

---

## 架构

### JDBC Wrapper 模式

```
业务应用 → TranslatorDriver → TranslatorStatement
                                   │ 1. 捕获 SQL
                                   │ 2. Calcite 翻译
                                   │ 3. 真实 JDBC 执行
                                   ▼
                              目标数据库
```

### SDT Proxy 模式

```
任意客户端 ──TCP:3306──▶ Netty IO ──▶ MySQL 协议解码
     ▲                                      │
     │                              Handshake + 认证
     │                                      │
     │                              CommandHandler
     │                              ┌───────┴────────┐
     │                              │ 系统变量/SET    │ 伪造响应
     │                              │ -- direct 标记  │ 直通
     │                              │ 普通 SQL        │ Calcite 翻译
     │                              └───────┬────────┘
     │                                      │
     └──────── MySQL Row 包 ◀──── ResultSetEncoder ◀── JDBC 执行
                                                          │
                                                    目标数据库
```

---

## 构建与测试

```bash
mvn clean package -DskipTests    # 全量打包（含分发包）
mvn test                          # 运行全部测试（111+）
```

## 许可证

本项目仅供学习和参考。