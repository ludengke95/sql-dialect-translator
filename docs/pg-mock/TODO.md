# PG 前端（PostgreSQL Frontend）开放问题清单

> 背景：本文件记录 SDT Proxy 新增「PostgreSQL 前端」能力时遇到的待定决策。
> 依据原始要求：遇到歧义时先用**最推荐方案**继续开发，再在此文件列出：
> - 需要用户输入什么
> - 有哪些可选方案
> - 本次选了哪个（标注 ✅）
>
> 模块状态：PG 前端协议、握手/认证、简单查询分发、系统目录最小集、SET 本地处理、
> TDD 测试（52 个用例全绿）已完成并提交。GitHub Actions（前端=PG / 后端=MySQL）
> 集成工作流与基准脚本已落地（smoke 冒烟集可跑通链路）。

---

## 1. pg_catalog / 系统目录模拟深度

**需要用户输入**：真实 PG 客户端（psql / ORM / 迁移工具）会查询哪些系统目录？
**现状**：`PgSystemCatalogProvider` 目前只拦截少量：
`version()`、`current_schema()`、`current_database()`、`current_user`、
`SELECT <常量>`、`pg_type`、`pg_database`、`pg_am`。

**可选方案**：
- A. 最小集：仅模拟上述高频项，遇到未知目录查询时回退后端并报错/空结果。✅（已选，性价比最高）
- B. 中集合：额外模拟 `pg_class`、`pg_attribute`、`pg_namespace`、`pg_proc` 等，
  覆盖 psql `\d*` 元命令与多数 ORM 的 introspection。
- C. 完整集：尽力逼近真实 `pg_catalog`，工作量与维护成本显著上升。

**建议**：保持 A，待真实客户端在 CI / 联调中暴露缺失项时按需补到 B。

---

## 2. 认证方式强度

**需要用户输入**：目标客户端是否强制 SCRAM-SHA-256？
**现状**：已实现 **MD5** 口令认证（`PgAuth.md5Password` / `verifyMd5`）。

**可选方案**：
- A. 仅 MD5：兼容老客户端与多数驱动，实现简单。✅（已选）
- B. MD5 + SCRAM-SHA-256：增加 SASL 交换，覆盖率更高。
- C. trust / 无认证：仅测试环境，生产不可用。

**建议**：A；若 CI 接入的官方 PG 镜像客户端（postgres:15-alpine psql）默认要求
SCRAM，再升级到 B。

---

## 3. 监听端口与「前端类型」配置

**需要用户输入**：PG 前端是否固定 5432，还是复用现有 `port` 字段？
**现状**：`ProxyConfig` 已有 `port` + 新增 `frontend: POSTGRESQL`，
CI 中通过 `docker run -p 5432:5432` 暴露，业务端口由配置决定。

**可选方案**：
- A. 复用 `port` 字段，前端类型由 `frontend` 枚举决定（MySQL / POSTGRESQL）。✅（已选）
- B. 前端专属端口（如 `pg-port` / `mysql-port`）双栈共存。

**建议**：A；若未来需要「同一进程同时接受 MySQL 与 PG 连接」，再演进到 B。

---

## 4. 前端协议抽象边界（FrontendProtocol SPI）

**需要用户输入**：是否需要 MySQL 与 PG 前端在**同一进程**并存？
**现状**：已抽象 `com.translator.proxy.core.frontend.FrontendProtocol`，
解耦「线协议编解码 / 握手 / 命令分发 / 响应写出 / 类型映射 / 系统目录」。
MySQL 与 PG 各自实现；后端执行链路不变。

**可选方案**：
- A. SPI 化，单进程按连接协商前端（由首字节/握手决定）。✅（已选，最灵活）
- B. 独立进程/独立模块，构建两个分发物。

**建议**：A；与现有 Proxy 单进程架构一致。

---

## 5. 基准测试客户端模式

**需要用户输入**：优先验证哪种 PG 客户端路径？
**现状**：可复用 `workflow_call` 的 job 支持两种 `test_mode`：
- `pg-client`：官方 `postgres:15-alpine` 自带 `psql`
- `python-pg`：容器化 `psycopg2`（`benchmark/python-pg/`）

**可选方案**：
- A. 两者都跑（CI 中 `pg-client` + `python-pg` 两个 matrix job）。✅（已选）
- B. 仅 `python-pg`（脚本化、易断言 JSON 结果）。
- C. 仅 `pg-client`（最贴近真实命令行用户）。

**建议**：A；两者覆盖「人类客户端」与「程序客户端」两类场景。

---

## 6. TPC-H / TPC-C 查询方言适配

**需要用户输入**：是否要把 `benchmark/tpch/queries`、`benchmark/tpcc/queries`
下的查询改写为 PG 方言以跑 `all` 全集？
**现状**：现有查询为 **MySQL 方言**。前端=PG 时，这些语句经 SDTP 翻译为 MySQL
未必等价（如 `LIMIT`/`GROUP BY`/函数差异）。当前 CI 仅用 `run-docker-test-pg.sh`
的**内置 smoke 冒烟集**（PG 方言：SELECT/JOIN/INSERT 到 `region`/`nation`）
验证 PG→MySQL 链路，`--benchmark smoke`。

**可选方案**：
- A. 暂用 smoke 验证链路，TPC 全集待 PG 方言查询就绪后切换 `--benchmark all`。✅（已选）
- B. 立即投入将 TPC 查询改写为 PG 方言（工作量较大，且依赖 #1 系统目录覆盖度）。
- C. 在 SDTP 内增加「源方言=PG」翻译分支，使 PG 方言查询也能被翻译为后端方言。

**建议**：A；B/C 列为后续里程碑，待 #1 提升至方案 B 后更稳妥。

---

## 7. 结果集类型映射（PG type OID）

**需要用户输入**：前端=PG 时，后端 JDBC 类型 → PG wire `type OID` 的映射范围？
**现状**：`PgResponseWriter` 构造 `RowDescription` 时需为每个列指定 PG type OID；
目前覆盖常用类型（int4/int8/numeric/text/varchar/char/date/timestamp/bool 等）。

**可选方案**：
- A. 覆盖常用类型，未知类型回退 `text`(OID 25)。✅（已选，够用且安全）
- B. 完整 OID 映射表（含数组、json、几何等）。

**建议**：A；出现类型错乱时再补。

---

## 8. search_path / schema 与后端 database 的映射

**需要用户输入**：PG 的 `search_path` 语义如何在「无 schema 概念」的 MySQL 后端落地？
**现状**：PG 连接 `database` 名用于选择 SDTP 后端（CI 中 `sdtp_db=tpch` → 后端 `tpch`）；
`SET search_path` 在 `PgCommandDispatcher` 中被本地处理（不转发后端），
默认 `public`。`PgHandshaker` 在 `channelActive` 时设置会话 `searchPath`。

**可选方案**：
- A. PG database → 后端 database 名；search_path 模拟为固定 `public`，本地吞掉 SET。✅（已选）
- B. search_path 映射到后端 `database.tablename` 前缀改写。

**建议**：A；够用。B 仅在需要多 schema 路由时考虑。

---

## 9. 离线构建辅助脚本 `run-mvn.sh`

**说明**：本仓库离线构建环境下，部分 Maven 插件本地仓库损坏，
`run-mvn.sh` 是封装 `test-compile` + 手动 `dependency:build-classpath` +
`org.junit.runner.JUnitCore` 的**临时绕过脚本**，含环境相关路径，
已加入 `.gitignore`，**不入库**。如需可移植的离线方案，建议修复本地仓库或改用 CI 构建。

---

## 待办（按优先级）

- [ ] **P1** 在真实 PG 客户端（psql 15 / psycopg2）联调中补齐缺失的 `pg_catalog` 项（见 #1）。
- [ ] **P1** 评估官方 PG 客户端是否强制 SCRAM，决定 #2 是否升级。
- [ ] **P2** 将 TPC-H / TPC-C 查询改写为 PG 方言或启用「源方言=PG」翻译（见 #6），
      随后把 CI 默认 `--benchmark` 由 `smoke` 切到 `all`。
- [ ] **P2** 扩充 PG type OID 映射（见 #7），覆盖更多列类型。
- [ ] **P3** 评估是否需要单进程双前端并存（见 #3/#4 方案 B）。
- [ ] **P3** 补充 PG 前端端到端文档（`docs/pg-mock/` 下的设计/配置说明）。
