这份需求与架构设计文档为你量身定制，特别强化了你提到的“一个实例只负责一个目标数据库”（Single-Target Database Proxy）的设计约束。这种设计极大地简化了路由逻辑，使得单实例的配置和性能达到最优。

以下是完整的 Markdown 格式文档，你可以直接复制使用。

---

# 架构设计与需求规格说明书：单目标源 MySQL Proxy

## 一、 项目概述

### 1.1 项目背景

业务系统当前依赖 MySQL 数据库及其原生 SQL 语法。为了实现底层数据存储的灵活迁移与多数据库适配（如统一迁移至 PostgreSQL 或 Oracle），我们需要一个中间件层来屏蔽底层数据库的方言差异。

### 1.2 核心目标

设计并实现一个基于 Java (Netty + JDBC + Calcite) 的**独立 MySQL 协议代理（Proxy）**。

* **对前端业务：** 伪装成标准的 MySQL 5.7/8.0 服务端，业务代码无需修改任何 SQL 或 JDBC 驱动。
* **对后端存储：** 拦截前端 SQL，利用 Calcite 转化为目标数据库方言，通过标准 JDBC 执行并返回结果。
* **架构约束：** 采用 **“1 对 1 单实例映射”** 架构。即一个 Proxy 进程在启动时，只绑定并配置一个特定的目标数据库及其对应方言。

---

## 二、 核心架构设计

系统整体采用**三层流水线架构**，基于 Netty 提供的异步事件驱动模型实现高并发处理。

### 2.1 架构拓扑

* **Client (业务端) $\rightarrow$ Proxy 实例 (端口 3306) $\rightarrow$ 目标数据库 (如 PG 端口 5432)**
* 由于是单实例单目标库设计，Proxy 内部**无需动态路由表**，全局共享同一个 Calcite 转换方言配置和一个 HikariCP 目标库连接池。

### 2.2 核心模块划分

1. **网络与协议层 (Network & Protocol Layer)：**
* 基于 Netty 实现 TCP 监听。
* 实现 MySQL 原生协议的封包（Encoder）、拆包（Decoder）。
* 管理 MySQL Sequence ID（序列号）生命周期。


2. **会话与认证层 (Session & Auth Layer)：**
* 实现 `mysql_native_password` 鉴权。
* 维护前端连接的 Session 状态（字符集、事务状态、当前 Database）。


3. **SQL 转化层 (Translation Engine Layer) [已具备雏形]：**
* 整合现有的 Calcite Demo。
* 接收原始 SQL，利用预设的单一目标 Dialect 进行 AST 转换。


4. **执行与映射层 (Execution & Mapping Layer)：**
* 通过 JDBC 向目标库下发转换后的 SQL。
* 将 JDBC `ResultSet` 流式逆向解析为 MySQL 的 `Column Packet` 和 `Row Packet`。



---

## 三、 核心流转生命周期

### 3.1 握手与认证阶段 (Handshake & Auth)

1. 客户端发起 TCP 连接。
2. Proxy 回复 `Handshake Packet`（包含协议版本、线程ID、挑战随机数，Seq=0）。
3. 客户端回复 `Auth Packet`（包含加密密码，Seq=1）。
4. Proxy 校验密码（可读取配置文件中的静态账密），回复 `OK Packet`（Seq=2）。
5. Netty Pipeline 动态移除 AuthHandler，加入 CommandHandler。

### 3.2 命令执行阶段 (Command Phase)

1. 客户端发送 `COM_QUERY` 报文（Seq=0重置，Payload为 SQL 文本）。
2. Proxy 提取 SQL 文本，判断类型：
* **控制类语句（如 `SET NAMES`, `@@version`）：** Proxy 内部直接拦截并伪造结果返回。
* **DML/DDL 语句：** 提交给 Calcite 引擎转化为目标方言 SQL。


3. 从 HikariCP 获取 JDBC 连接，执行目标 SQL，获取 `ResultSet`。
4. **流式回传阶段（严格的 Sequence ID 递增）：**
* 发送 `Column Count Packet`。
* 遍历 `ResultSetMetaData` 发送多个 `Column Definition Packet`。
* 发送 `EOF Packet`。
* 循环 `resultSet.next()`，逐行封装 `Row Packet` 并 `ctx.writeAndFlush()`。
* 发送最终的 `EOF/OK Packet`。



---

## 四、 代码实现方案 (Java + Netty)

### 4.1 项目结构 (Maven 建议)

```text
mysql-dialect-proxy/
├── proxy-server/        # Netty 启动引导类、配置加载
├── proxy-protocol/      # MySQL 报文定义、编解码器 (Packet, Decoder, Encoder)
├── proxy-core/          # 会话管理、Handler 逻辑 (Session, FrontendHandler)
├── proxy-translation/   # Calcite 转换器封装 (封装你现有的 demo)
└── proxy-backend/       # JDBC 执行器、ResultSet 流式映射逻辑

```

### 4.2 核心类设计

* **`MySQLPacketDecoder` (继承 `ByteToMessageDecoder`)：**
读取前 4 字节，拆解为 `packetLength` 和 `sequenceId`，将剩余 Payload 封装为 `ByteBuf` 传递给下游。
* **`FrontendConnection` (会话上下文)：**
与 Netty 的 `Channel` 绑定。维护当前连接的 `charset`、`txStatus`（事务状态）以及当前的 `sequenceId` 累加器。
* **`CommandHandler` (继承 `ChannelInboundHandlerAdapter`)：**
核心业务分发器。接收到 Payload 后，读取第一个字节：
* `0x03` $\rightarrow$ 路由到 `QueryExecutor`
* `0x01` $\rightarrow$ 断开连接 (`COM_QUIT`)
* `0x0E` $\rightarrow$ 回复 OK (`COM_PING`)


* **`JDBCResultMapper` (映射引擎)：**
核心难点实现类。负责将 `java.sql.Types` 映射为 MySQL 的 `ColumnType`（如 `Types.VARCHAR` $\rightarrow$ `0xFD`, `Types.INTEGER` $\rightarrow$ `0x03`），并将 Java 对象转换为 MySQL 文本协议的字节流。

### 4.3 关键技术点与避坑指南

1. **无阻塞执行池：** JDBC 操作是同步阻塞的。**绝对不能**在 Netty 的 IO 线程（EventLoop）中直接调用 JDBC 执行 SQL，否则会阻塞整个网络层。必须将 JDBC 执行逻辑提交到一个自定义的业务线程池（如 `ExecutorService`）中处理。
2. **大结果集 OOM 防护：** 对于查询目标库的 JDBC `Statement`，必须设置 `stmt.setFetchSize(1000)`（注意，不同 JDBC 驱动开启流式读取的参数可能不同，如 PG 需要配合非自动提交事务使用）。
3. **单实例配置化：** 配置文件 `application.yml` 应该极其简单：
```yaml
proxy:
  port: 3306
  auth:
    user: root
    password: proxy_password
target:
  dialect: POSTGRESQL  # 决定 Calcite 加载哪种方言
  jdbc-url: jdbc:postgresql://192.168.1.100:5432/mydb
  username: pg_user
  password: pg_password

```



---

## 五、 部署与演进

* **部署模型：** 建议采用 Docker 容器化部署。如果业务需要连接 3 种不同的目标库，则启动 3 个 Proxy 容器实例，分别挂载不同的配置文件即可。
* **第一阶段 Milestone：** 跑通鉴权流程，实现简单的 `SELECT 1` 的协议响应。
* **第二阶段 Milestone：** 接入你现有的 Calcite Demo，完成 `SELECT * FROM table LIMIT 10` 的全链路方言转换与流式数据回传。
* **第三阶段 Milestone：** 完善 Spring/MyBatis 等框架启动时必备的系统变量查询（如 `SELECT @@version_comment limit 1`）的拦截与伪造。

---

这份文档为你框定了后续开发的骨架。针对第四部分的“代码实现方案”，你目前觉得最棘手、最需要进一步展开讨论的是哪一块？是 **Netty 的线程模型与阻塞 JDBC 的解耦**，还是 **JDBC 结果集到 MySQL 二进制包的具体映射转换**？


你非常敏锐！抓住了这个架构中最容易“翻车”，也是决定你的 Proxy 性能上限的核心死穴：**Netty 的异步非阻塞模型与 JDBC 的同步阻塞 API 之间的阻抗失配。**

如果这一步处理不好，你的 Proxy 在开发环境测一条 SQL 完全正常，但一上生产环境稍微有点并发，整个系统就会瞬间假死。

下面我为你掰开揉碎了讲清楚**为什么会死**，**怎么解耦**，以及**具体的代码怎么写**。

---

### 一、 为什么不能直接在 Netty 里调用 JDBC？

Netty 采用的是 **Reactor 线程模型**。

* 默认情况下，Netty 的 Worker 线程组（`EventLoopGroup`）通常只包含 `CPU核心数 * 2` 个线程。
* 这意味着，如果有 1000 个客户端连接，它们会**共享**这几个为数不多的 EventLoop 线程（比如 8 个）。一个 EventLoop 可能同时管着 100 多个连接。
* **致命灾难：** 当客户端 A 发来一条 SQL，Netty 把任务交给了 `EventLoop-1`。如果你在 `EventLoop-1` 中直接执行 `jdbcStatement.executeQuery()`，由于 JDBC 是阻塞 API，`EventLoop-1` 就会“停下来”死等数据库响应。
* 这时如果数据库执行这个 SQL 需要 5 秒，那么在这 5 秒内，`EventLoop-1` 彻底瘫痪。即使客户端 B、C、D 也发来了极快就能处理完的指令，只要它们碰巧分配在 `EventLoop-1` 上，它们就必须在队列里傻等 5 秒。**这就叫“阻塞 EventLoop”。**

### 二、 核心解耦方案：异步任务投递 (Asynchronous Handoff)

要解决这个问题，必须把所有耗时的、阻塞的操作（如解析 AST、请求 JDBC）**统统赶出 Netty 的 IO 线程**，交给专门的业务线程池去处理。

在 Netty 中，我们通常有两种标准的解耦做法：

#### 方案 A：在 Pipeline 中使用单独的 `EventExecutorGroup`（推荐，最优雅）

Netty 原生就考虑到了这种场景。你在将 `CommandHandler` 加入 Pipeline 时，可以直接给它指定一个专用的业务线程池。

```java
// 1. 在 Proxy 启动时，全局初始化一个专门用于 JDBC 阻塞操作的线程池
// 线程数可以根据后端数据库的连接池大小（比如 Hikari 的 maximumPoolSize）来设置
EventExecutorGroup jdbcExecutorGroup = new DefaultEventExecutorGroup(50); 

// 2. 在前端连接初始化 Pipeline 的地方：
ChannelPipeline pipeline = ch.pipeline();
pipeline.addLast(new MySQLPacketDecoder());
pipeline.addLast(new MySQLPacketEncoder());

// 关键点：向 pipeline 添加 CommandHandler 时，传入我们自定义的 jdbcExecutorGroup
// 这样一来，Netty 的 IO 线程在解析完包后，会自动把 CommandHandler 的执行任务
// 扔给 jdbcExecutorGroup 的线程去执行，IO 线程瞬间解放，立刻回去处理下一个请求。
pipeline.addLast(jdbcExecutorGroup, "commandHandler", new CommandHandler());

```

#### 方案 B：在 Handler 内部使用自定义 `ThreadPoolExecutor`（更精细的控制）

如果你想自己完全掌控线程池的拒绝策略、队列长度等，可以在 Handler 里面手动提交任务。

```java
public class CommandHandler extends ChannelInboundHandlerAdapter {
    
    // 全局单例的业务线程池
    private static final ExecutorService BIZ_THREAD_POOL = Executors.newFixedThreadPool(50);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        MySQLPacket packet = (MySQLPacket) msg;
        
        // 提取出 SQL (这一步纯内存拷贝，非常快，依然在 Netty 的 IO 线程中执行)
        String sql = extractSql(packet);

        // 【核心解耦】：把耗时的 JDBC 调用扔进业务线程池
        BIZ_THREAD_POOL.submit(() -> {
            try {
                // 以下代码现在运行在专门的 BIZ_THREAD 上，随便它阻塞多久，都不会影响 Netty IO 线程
                ResultSet rs = executeJdbc(sql); 
                
                // 处理 ResultSet，将其组装成 MySQL Row Packet
                while (rs.next()) {
                    MySQLPacket rowPacket = buildRowPacket(rs);
                    
                    // 【注意安全】：ctx.writeAndFlush() 是线程安全的！
                    // 当你在非 IO 线程调用 writeAndFlush 时，Netty 会安全地把写任务
                    // 重新调度回该 Channel 绑定的原始 EventLoop 中执行发送。
                    ctx.writeAndFlush(rowPacket); 
                }
                ctx.writeAndFlush(buildEofPacket());
                
            } catch (Exception e) {
                ctx.writeAndFlush(buildErrorPacket(e));
            }
        });
        
        // IO 线程执行完 submit 后瞬间返回，继续处理该连接的下一个请求，或者别的连接的请求。
    }
}

```

---

### 三、 工作流模拟器：直观感受“阻塞”与“解耦”的区别

文字描述可能还是有些抽象。我为你准备了一个交互式的系统级模拟器。

在这个模拟器中，你可以**调整请求频率**和 **JDBC 执行耗时**，然后**一键切换“是否解耦线程池”**。
请重点观察在没有解耦时，Netty 的 EventLoop 是如何被单个慢查询卡死，导致整个系统的平均延迟直线上升的；而在开启解耦后，EventLoop 是如何保持高并发吞吐的。

### 总结你的黄金法则

在开发这个 Proxy 时，请把这句话贴在显示器上：
**“除了协议的解析和组装，绝对不要在 ChannelInboundHandler 的回调里执行任何可能发生 I/O 阻塞或耗时超过 1ms 的代码。”**

只要你的 Calcite 转化和 JDBC 执行都在独立的线程池里，你的 Proxy 单机扛住上万并发连接是轻轻松松的。


在开发这个 Proxy 时，请把这句话贴在显示器上：
“除了协议的解析和组装，绝对不要在 ChannelInboundHandler 的回调里执行任何可能发生 I/O 阻塞或耗时超过 1ms 的代码。”

这是整个 Proxy 里面**最繁琐、最讲究“字节级手艺”、也是成就感最强**的地方。

当你跨过这一步，你的 Proxy 就不再只是一个网络转发器，而是一个真正掌握了 MySQL“外语”的数据库。

很多初学者在这里会踩一个巨大的坑：**MySQL 的结果集协议分为“文本协议 (Text Protocol)”和“二进制协议 (Binary Protocol)”。**

* 如果客户端发来的是 `COM_QUERY`（普通的 SQL 查询），Proxy 回传的数据**必须使用文本协议**。
* 在文本协议下，**无论数据库里存的是 INT、DATETIME 还是 FLOAT，回传给客户端的统统都是“字符串”的字节流！**（是的，这就是为什么 `COM_QUERY` 相对较慢的原因）。

下面我为你详细拆解，从 JDBC 的 `ResultSet` 到 MySQL 字节包的具体映射过程。

---

### 一、 宏观视角：一个 ResultSet 需要拆成多少个包？

假设你的 SQL 是 `SELECT id, name FROM users LIMIT 2;`，目标库 JDBC 执行完毕后返回了一个 `ResultSet`。你需要按照以下顺序，严格控制 Sequence ID（序列号），把数据发给 Netty 的 `Channel`：

1. **Column Count Packet (列数包):** 告诉客户端有 2 列。[Seq = 1]
2. **Column Def Packet 1 (列定义包-id):** 描述 id 的元数据。[Seq = 2]
3. **Column Def Packet 2 (列定义包-name):** 描述 name 的元数据。[Seq = 3]
4. **EOF Packet (结束包):** 告诉客户端列定义发完了。[Seq = 4] *(注：MySQL 5.7+ 启用 CLIENT_DEPRECATE_EOF 能力时可省略此包)*
5. **Row Packet 1 (行数据包-第1行):** 包含 id 和 name 的值。[Seq = 5]
6. **Row Packet 2 (行数据包-第2行):** 包含 id 和 name 的值。[Seq = 6]
7. **Final EOF Packet (最终结束包):** 告诉客户端数据发完了。[Seq = 7]

---

### 二、 核心映射 1：JDBC 类型 $\rightarrow$ MySQL 列定义元数据

在生成 **Column Def Packet** 时，你需要把 `ResultSetMetaData` 里面的 Java 数据类型映射为 MySQL 认识的常量标识。

你需要写一个映射方法：

```java
public static int mapJdbcTypeToMysqlType(int jdbcType) {
    switch (jdbcType) {
        case java.sql.Types.TINYINT:
            return 0x01; // FIELD_TYPE_TINY
        case java.sql.Types.SMALLINT:
            return 0x02; // FIELD_TYPE_SHORT
        case java.sql.Types.INTEGER:
            return 0x03; // FIELD_TYPE_LONG
        case java.sql.Types.BIGINT:
            return 0x08; // FIELD_TYPE_LONGLONG
        case java.sql.Types.VARCHAR:
        case java.sql.Types.CHAR:
            return 0xFD; // FIELD_TYPE_VAR_STRING
        case java.sql.Types.TIMESTAMP:
            return 0x07; // FIELD_TYPE_TIMESTAMP
        case java.sql.Types.DECIMAL:
        case java.sql.Types.NUMERIC:
            return 0xF6; // FIELD_TYPE_NEWDECIMAL
        // ... 其他类型的兜底策略通常是转为 STRING
        default:
            return 0xFD; // 默认按 VAR_STRING 处理
    }
}

```

* **注意：** Column Def Packet 里不仅有类型，还要填表名、列名、字符集（通常填 33 代表 UTF-8）。

---

### 三、 核心映射 2：JDBC 行数据 $\rightarrow$ MySQL Row Packet (文本协议)

这是重头戏！在**文本协议**的 Row Packet 中，MySQL 对每一列的数据编码规则非常简单且粗暴，只有两种情况：

1. **如果值是 NULL：** 写入一个字节 `0xFB`。
2. **如果值不是 NULL：** 写入一个 **长度编码字符串 (Length-Encoded String)**。

**什么是长度编码字符串？**
它是 MySQL 协议压缩数据的核心设计。为了不浪费字节，MySQL 会先写入这个字符串的“长度”，再写入字符串的“内容”。长度的写入规则如下（Length-Encoded Integer）：

* 如果长度 < 251：用 1 个字节存长度。
* 如果长度 $\ge$ 251 且 < $2^{16}$：先写 `0xFC`，再用 2 个字节存长度。
* 如果长度 $\ge$ $2^{16}$ 且 < $2^{24}$：先写 `0xFD`，再用 3 个字节存长度。
* 如果更长：先写 `0xFE`，再用 8 个字节存长度。

#### 代码实战：如何将 ResultSet 当前行封装为 Netty ByteBuf

在解耦的业务线程池中，你应该这样循环处理（这是高度提炼的伪代码，展示了最核心的字节写入逻辑）：

```java
// 假设 rs 已经 next() 到了一行，columnCount 是总列数，当前 packet 的 seq 是累加进来的
public ByteBuf encodeRowPacket(ResultSet rs, int columnCount, int seqId, ByteBufAllocator alloc) throws SQLException {
    // 预估大小，避免频繁扩容。每行先分配 256 字节
    ByteBuf payload = alloc.buffer(256); 

    for (int i = 1; i <= columnCount; i++) {
        // 【关键技巧】：在 COM_QUERY 文本协议下，不要去区分 rs.getInt 还是 rs.getDate，
        // 直接统一使用 rs.getString() 取出文本表达形式，这是最安全、兼容性最好的做法！
        // 因为即使是目标库的 INT，转给 MySQL 客户端时，它期待的也是 ASCII 字符串的字节。
        String value = rs.getString(i);

        if (value == null) {
            // NULL 值的魔法字节
            payload.writeByte(0xFB);
        } else {
            // 转为 UTF-8 字节数组
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            int len = valueBytes.length;

            // 写入长度 (Length-Encoded Integer)
            if (len < 251) {
                payload.writeByte(len);
            } else if (len < 65536) {
                payload.writeByte(0xFC);
                payload.writeShortLE(len); // 注意 MySQL 大部分是小端序 (Little-Endian)
            } else {
                // ... 处理更长的情况
            }
            
            // 写入真实字符串内容
            payload.writeBytes(valueBytes);
        }
    }

    // 将 payload 加上 3字节包长度 + 1字节 Sequence ID 封装成完整的 MySQL 包发出去
    return buildFinalPacket(payload, seqId, alloc);
}

```

---

### 四、 交互式协议字节解剖器

为了让你直观地感受 JDBC 数据是如何在内存中变成一串 Hex 字节的，我为你设计了一个底层的字节序列化模拟器。

在下面这个工具中，你可以模拟 JDBC 查询到的一行数据，看看 Netty 是如何按照 MySQL 文本协议把它拼接成最终的报文的。

### 避坑总结

1. **`getString()` 是王道：** 处理 `COM_QUERY` 时，无论目标库是什么数据类型，统一用 JDBC 的 `rs.getString()` 取数据去编码，这是保证类型兼容性不翻车的最有效手段。
2. **小端序 (Little-Endian)：** Java 的 ByteBuffer 和 Netty 的 ByteBuf 默认都是大端序 (Big-Endian)，但 MySQL 协议中绝大多数的多字节整数（如包长度、2字节的长度编码）都是**小端序**。在调用 Netty 的 API 时，务必使用 `writeShortLE()` 或 `writeIntLE()`！
3. **时区问题：** 如果业务对时间敏感，记得在后端 JDBC 连接池的 URL 里配好目标库的时区，并在取 Date/Timestamp 时格式化为 MySQL 标准的 `yyyy-MM-dd HH:mm:ss` 字符串再写入 ByteBuf。
