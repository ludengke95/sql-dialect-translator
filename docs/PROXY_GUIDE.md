# SDT Proxy 使用手册

> SDT Proxy（简称 **SDTP**）是一个 MySQL 协议代理。它对外伪装成 MySQL 5.7 服务端，接收客户端的 MySQL 协议请求，将 SQL 翻译为目标数据库方言后执行，结果以 MySQL 协议格式返回。客户端无需修改任何代码或驱动。

---

## 目录

1. [快速开始](#1-快速开始)
2. [配置文件](#2-配置文件)
3. [启动与管理](#3-启动与管理)
4. [SQL 翻译配置](#4-sql-翻译配置)
5. [直通模式](#5-直通模式)
6. [系统变量](#6-系统变量)
7. [Docker 部署](#7-docker-部署)
8. [架构与线程模型](#8-架构与线程模型)
9. [常见问题](#9-常见问题)

---

## 1. 快速开始

### 1.1 构建

```bash
mvn clean package -DskipTests
```

产物在 `sdtp-server/target/`：
- `sdtp-server-*.zip` — Windows 分发包
- `sdtp-server-*.tar.gz` — Linux/macOS 分发包

### 1.2 解压并配置

```bash
tar -xzf sdtp-server-1.0.0-SNAPSHOT.tar.gz
cd sdtp-1.0.0-SNAPSHOT
vim config/proxy-config.yml
```

最小配置（连接 PostgreSQL）：

```yaml
proxy:
  port: 3306
  auth:
    user: root
    password: proxy_password

target:
  dialect: POSTGRESQL
  jdbc-url: jdbc:postgresql://192.168.1.100:5432/mydb
  username: pg_user
  password: pg_password
```

### 1.3 启动

```bash
# Linux / macOS
./bin/start.sh start        # 后台启动
./bin/start.sh start-fg     # 前台启动（Docker / systemd）

# Windows
bin\start.bat               # 前台启动
bin\start.bat start          # 后台启动
```

### 1.4 客户端连接

```bash
# 和连接普通 MySQL 完全一样
mysql -h 127.0.0.1 -P 3306 -u root -pproxy_password mydb

# 或 JDBC URL
jdbc:mysql://127.0.0.1:3306/mydb
```

---

## 2. 配置文件

完整配置项说明：

```yaml
proxy:
  # 监听端口（默认 3306）
  port: 3306

  # 客户端认证——客户端用此账密连接 Proxy
  auth:
    user: root
    password: proxy_password

target:
  # 目标数据库方言: POSTGRESQL | MYSQL | ORACLE | SQLSERVER
  dialect: POSTGRESQL

  # JDBC URL
  # PostgreSQL:  jdbc:postgresql://主机:5432/数据库
  # MySQL:       jdbc:mysql://主机:3306/数据库
  # Oracle:      jdbc:oracle:thin:@主机:1521:SID
  # SQL Server:  jdbc:sqlserver://主机:1433;databaseName=数据库
  jdbc-url: jdbc:postgresql://localhost:5432/mydb

  # 目标库账密
  username: pg_user
  password: pg_password

  # HikariCP 连接池
  max-pool-size: 20
  min-idle: 2

# SQL 翻译配置（仅 dialect ≠ MYSQL 时生效）
translation:
  keyword-case: UPPER        # UPPER | LOWER
  identifier-case: LOWER     # LOWER | UPPER | UNCHANGED
```

### 通过环境变量覆盖配置文件路径

```bash
export SDTP_CONFIG=/path/to/my-config.yml
./bin/start.sh start
```

---

## 3. 启动与管理

### Linux / macOS

| 命令 | 说明 |
|------|------|
| `./bin/start.sh start` | 后台启动（nohup），PID 写入 `sdtp.pid` |
| `./bin/start.sh start-fg` | 前台启动，日志输出到控制台 |
| `./bin/start.sh stop` | 优雅停止（SIGTERM，30s 超时后 SIGKILL） |
| `./bin/start.sh restart` | 重启 |
| `./bin/start.sh status` | 查看运行状态 |

### Windows

| 命令 | 说明 |
|------|------|
| `bin\start.bat` | 前台启动（双击即可） |
| `bin\start.bat start` | 后台启动（javaw） |
| `bin\start.bat stop` | 停止 |
| `bin\start.bat status` | 查看状态 |

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SDTP_CONFIG` | `config/proxy-config.yml` | 配置文件路径 |
| `JAVA_HOME` | 系统 PATH 中的 java | JDK 安装目录 |
| `JAVA_OPTS` | `-Xms256m -Xmx1024m -XX:+UseG1GC` | JVM 参数 |

### 日志

- **控制台**：前台模式直接输出，后台模式重定向到 `logs/console.log`
- **文件**：`logs/proxy.log`，每天滚动，保留 30 天
- 可通过 `logback.xml`（jar 内 `logback.xml`）自定义

---

## 4. SQL 翻译配置

TranslationConfig 控制翻译后 SQL 的关键词和标识符大小写。

### 配置项

```yaml
translation:
  keyword-case: UPPER      # UPPER（默认）| LOWER
  identifier-case: LOWER   # LOWER（默认）| UPPER | UNCHANGED
```

### 各数据库推荐配置

| 目标数据库 | keyword-case | identifier-case | 原因 |
|-----------|-------------|----------------|------|
| PostgreSQL / HighGo | `UPPER` | `LOWER` ✅ | PG 自动将未引用标识符转小写 |
| Oracle / DM | `UPPER` | `UPPER` | Oracle 默认标识符大写 |
| SQL Server | `UPPER` | `UNCHANGED` | SQL Server 保留原始大小写 |
| MySQL | — | — | 翻译自动禁用（源=目标） |

### 示例

```yaml
# PostgreSQL 目标
translation:
  keyword-case: UPPER
  identifier-case: LOWER
```

输入：
```sql
SELECT Name, CreatedAt FROM Users WHERE Status != 'deleted'
```

输出（发往 PostgreSQL）：
```sql
SELECT "name", "createdat" FROM "users" WHERE "status" <> 'deleted'
```

---

## 5. 直通模式

某些 SQL 不需要翻译（如目标库专有函数、性能分析语句），可以在 SQL 开头加注释标记跳过翻译引擎。

### 标记格式

| 格式 | 示例 |
|------|------|
| `-- direct` | `-- direct\nSELECT pg_sleep(1)` |
| `-- sdtp:direct` | `-- sdtp:direct\nSELECT now()` |
| `/* direct */` | `/* direct */ SELECT version()` |
| `/* sdtp:direct */` | `/* sdtp:direct */ EXPLAIN ANALYZE SELECT ...` |

### 行为

1. 检测到标记 → **剥离标记注释** → 原 SQL 直通目标库
2. 未检测到 → 正常走 Calcite 翻译
3. 标记大小写不敏感：`-- DIRECT`、`/* SDTP:DIRECT */` 均可
4. 前导空格不影响

### 典型场景

```sql
-- PostgreSQL 专有函数，不需要翻译
-- direct
SELECT pg_backend_pid(), pg_postmaster_start_time()

-- 执行计划分析
/* direct */ EXPLAIN ANALYZE SELECT * FROM large_table WHERE id > 1000

-- 目标库特有的系统函数
-- sdtp:direct
SELECT oid, relname FROM pg_class WHERE relkind = 'r'
```

---

## 6. 系统变量

Proxy 拦截常见 MySQL 系统变量查询并返回伪造值，不会转发到目标库。

| 变量 | 返回值 |
|------|--------|
| `@@version` | `5.7.38-proxy` |
| `@@version_comment` | `MySQL Proxy 5.7.38` |
| `@@character_set_client` | `utf8mb4` |
| `@@autocommit` | `1` |
| `@@tx_isolation` | `READ-COMMITTED` |
| `@@max_allowed_packet` | `16777216` |
| `SELECT DATABASE()` | 当前选择的 database |

同时拦截 `SET NAMES`、`SET autocommit` 等命令并在 Proxy 侧直接返回 OK。

---

## 7. Docker 部署

```bash
# 一键启动（Proxy + PostgreSQL）
docker-compose up -d

# 查看日志
docker logs -f sdtp-proxy

# 客户端连接
mysql -h 127.0.0.1 -P 3306 -u root -pproxy_password
```

`docker-compose.yml` 包含：
- PostgreSQL 15 目标库（端口 5432）
- SDT Proxy（端口 3306）

Docker 专用配置：`sdtp-server/src/main/resources/proxy-config-docker.yml`

---

## 8. 架构与线程模型

### 模块结构

```
sdtp-protocol     MySQL 线协议：Packet 定义、Decoder、Encoder、BufferUtils、认证
sdtp-core         会话管理、HandshakeHandler、AuthHandler、CommandHandler、系统变量拦截
sdtp-backend      TypeMapper、ResultSetEncoder、JdbcBackendQueryProcessor(HikariCP)
sdtp-server       ProxyBootstrap(Netty)、ConfigLoader(YAML)、分发包
```

### 线程模型

```
┌─ Boss EventLoop (1 thread) ─────────────────────┐
│  接受 TCP 连接                                    │
└──────────────────────┬───────────────────────────┘
                       │
┌─ Worker EventLoop (N threads) ──────────────────┐
│  MySQLPacketDecoder → Encoder                    │
│  HandshakeHandler → AuthHandler（IO 线程完成）     │
└──────────────────────┬───────────────────────────┘
                       │ auth 成功，pipeline 替换
┌─ Biz ExecutorGroup (M threads) ─────────────────┐
│  CommandHandler（非 IO 线程）                      │
│    ├─ 系统变量 → 伪造响应                          │
│    ├─ -- direct → 直通                            │
│    └─ 普通 SQL → TranslationQueryProcessor       │
│                   ├─ Calcite 翻译                 │
│                   └─ JdbcBackendQueryProcessor   │
│                       └─ HikariCP → JDBC 执行     │
└──────────────────────────────────────────────────┘
```

**关键原则**：IO 线程（EventLoop）只做协议解析和组装。JDBC 阻塞调用在 Biz ExecutorGroup 中执行，不会阻塞网络层。

---

## 9. 常见问题

### Q: 翻译后的 SQL 不对，怎么排查？

查看日志 `logs/proxy.log`，搜索 `Translated:` 可看到翻译前后的 SQL 对比。

### Q: 某些 SQL 不想翻译怎么办？

使用[直通模式](#5-直通模式)，在 SQL 开头加 `-- direct`。

### Q: 如何修改日志级别？

修改 jar 内 `logback.xml`，或启动时指定：

```bash
JAVA_OPTS="-Dlog.level=DEBUG" ./bin/start.sh start
```

### Q: 连接池多大合适？

建议 `max-pool-size` 设为目标库 `max_connections` 的 50%~80%。默认 20 适用于中等并发。

### Q: 支持 SSL/TLS 吗？

当前版本暂不支持客户端 SSL 连接。

### Q: 性能如何？

单机可承载 10,000+ 并发连接（受限于 Netty IO 线程数），实际吞吐取决于目标库性能。翻译开销约 1-5ms/SQL。
