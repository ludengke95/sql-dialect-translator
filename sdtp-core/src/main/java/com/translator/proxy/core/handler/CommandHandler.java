package com.translator.proxy.core.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.ConnectionMetrics;
import com.translator.proxy.core.intercept.SystemVariableInterceptor;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.metrics.CommandMetrics;
import com.translator.proxy.metrics.NettyMetrics;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.constant.ServerStatus;
import com.translator.proxy.protocol.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 命令处理器 —— 认证完成后处理客户端各类 MySQL 命令。
 *
 * <p>支持的命令：
 * <ul>
 *   <li>COM_QUERY (0x03) — SQL 查询，分发到系统变量拦截或后端执行</li>
 *   <li>COM_PING (0x0E) — 心跳</li>
 *   <li>COM_QUIT (0x01) — 断开连接</li>
 *   <li>COM_INIT_DB (0x02) — 切换数据库</li>
 *   <li>COM_FIELD_LIST (0x04) — 表字段列表（暂返回错误）</li>
 *   <li>COM_STMT_PREPARE/EXECUTE/CLOSE — 预编译语句（暂返回错误）</li>
 * </ul>
 *
 * <p>线程模型：此 Handler 运行在 Netty 的自定义 EventExecutorGroup 中，
 * 但其内部的 JDBC 调用应进一步委托到专用的业务线程池。
 */
public class CommandHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);
    /** SHOW DATABASES / SHOW SCHEMAS [LIKE 'xxx'] */
    private static final Pattern SHOW_DATABASES = Pattern.compile(
            "^\\s*SHOW\\s+(DATABASES|SCHEMAS)(?:\\s+LIKE\\s+'([^']*)')?\\s*$", Pattern.CASE_INSENSITIVE);
    /** 事务控制语句匹配 */
    private static final Pattern SET_AUTOCOMMIT_PATTERN = Pattern.compile(
            "^\\s*SET\\s+(?:@@(?:session\\.)?)?autocommit\\s*=\\s*(\\d)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BEGIN_PATTERN =
            Pattern.compile("^\\s*(?:BEGIN|START\\s+TRANSACTION)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^\\s*COMMIT\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("^\\s*ROLLBACK\\s*$", Pattern.CASE_INSENSITIVE);
    /** 后端路由器（多后端模式下按数据库名路由） */
    private static volatile BackendRouter backendRouter = null;
    /** 默认后端查询处理器（向后兼容：无路由器时使用） */
    private static volatile QueryProcessor queryProcessor = QueryProcessor.NOOP;
    /**
     * 设置后端路由器（多后端模式）。
     */
    public static void setBackendRouter(BackendRouter router) {
        backendRouter = router;
    }
    /**
     * 设置全局查询处理器（单后端模式，兼容旧版）。
     */
    public static void setQueryProcessor(QueryProcessor processor) {
        queryProcessor = processor != null ? processor : QueryProcessor.NOOP;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySQLPacketDecoder.RawMySQLPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }
        MySQLPacketDecoder.RawMySQLPacket raw = (MySQLPacketDecoder.RawMySQLPacket) msg;
        ByteBuf payload = raw.getPayload();
        try {
            int command = payload.readUnsignedByte();
            String cmdName = CommandType.nameOf(command);
            log.debug("Command: {} (seq={})", cmdName, raw.getSequenceId());
            CommandMetrics.recordCommand(cmdName);
            // 每个命令开始时，客户端重置 seq 为 0
            FrontendSession session =
                    ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
            switch (command) {
                case CommandType.COM_QUERY:
                    handleQuery(ctx, payload, session);
                    break;
                case CommandType.COM_PING:
                    handlePing(ctx);
                    break;
                case CommandType.COM_QUIT:
                    handleQuit(ctx);
                    break;
                case CommandType.COM_INIT_DB:
                    handleInitDb(ctx, payload, session);
                    break;
                case CommandType.COM_FIELD_LIST:
                    handleFieldList(ctx, payload);
                    break;
                default:
                    handleUnsupported(ctx, CommandType.nameOf(command));
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling command", e);
            CommandMetrics.recordError();
            writeErr(ctx, 1105, "HY000", "Internal error: " + e.getMessage());
        } finally {
            raw.release();
        }
    }
    // ==================== COM_QUERY ====================
    private void handleQuery(ChannelHandlerContext ctx, ByteBuf payload, FrontendSession session) {
        String sql = payload.toString(StandardCharsets.UTF_8);
        log.debug("SQL: {}", sql);
        // 1. 检查是否 USE 语句
        String useDb = SystemVariableInterceptor.extractUseDatabase(sql);
        if (useDb != null) {
            if (!useDb.equalsIgnoreCase(session.getDatabase())) {
                rollbackActiveTransaction(ctx, session);
            }
            session.setDatabase(useDb);
            writeOk(ctx, 0, 0, "");
            return;
        }
        // 1.5. 检查是否 SHOW DATABASES / SHOW SCHEMAS（返回配置的后端名称列表）
        if (isShowDatabases(sql)) {
            handleShowDatabases(ctx, sql);
            return;
        }
        // 1.8 拦截事务控制相关语句
        String trimmedSql = sql.trim();
        Matcher autocommitMatcher = SET_AUTOCOMMIT_PATTERN.matcher(trimmedSql);
        if (autocommitMatcher.matches()) {
            int val = Integer.parseInt(autocommitMatcher.group(1));
            boolean targetAutoCommit = (val == 1);
            try {
                if (session.isAutoCommit() != targetAutoCommit) {
                    if (!session.isAutoCommit() && targetAutoCommit) {
                        QueryProcessor processor = resolveProcessor(session);
                        processor.commit(ctx, session);
                    }
                    session.setAutoCommit(targetAutoCommit);
                }
                writeOk(ctx, 0, 0, "");
            } catch (Exception e) {
                log.error("Failed to set autocommit", e);
                writeErr(ctx, 1105, "HY000", "Failed to set autocommit: " + e.getMessage());
            }
            return;
        }
        if (BEGIN_PATTERN.matcher(trimmedSql).matches()) {
            session.setInTransaction(true);
            writeOk(ctx, 0, 0, "");
            return;
        }
        if (COMMIT_PATTERN.matcher(trimmedSql).matches()) {
            try {
                session.setInTransaction(false);
                QueryProcessor processor = resolveProcessor(session);
                processor.commit(ctx, session);
                writeOk(ctx, 0, 0, "");
            } catch (Exception e) {
                log.error("Commit failed", e);
                writeErr(ctx, 1105, "HY000", "Commit failed: " + e.getMessage());
            }
            return;
        }
        if (ROLLBACK_PATTERN.matcher(trimmedSql).matches()) {
            try {
                session.setInTransaction(false);
                QueryProcessor processor = resolveProcessor(session);
                processor.rollback(ctx, session);
                writeOk(ctx, 0, 0, "");
            } catch (Exception e) {
                log.error("Rollback failed", e);
                writeErr(ctx, 1105, "HY000", "Rollback failed: " + e.getMessage());
            }
            return;
        }
        // 2. 检查是否 SET 语句（Proxy 内部处理）
        if (SystemVariableInterceptor.isSetStatement(sql)) {
            log.debug("Handling SET statement locally: {}", sql);
            CommandMetrics.recordSystemVarInterception();
            writeOk(ctx, 0, 0, "");
            return;
        }
        // 3. 检查是否为系统变量查询
        SystemVariableInterceptor.InterceptResult ir = SystemVariableInterceptor.intercept(sql, session.getDatabase());
        if (ir != null) {
            log.debug("Intercepted system variable query: {}", sql);
            CommandMetrics.recordSystemVarInterception();
            writeInterceptedResult(ctx, ir);
            return;
        }
        // 4. 根据会话 database 选择后端处理器并执行
        QueryProcessor processor = resolveProcessor(session);
        processor.process(ctx, sql, session);
    }
    /**
     * 根据会话信息解析出应使用的后端处理器。
     */
    private QueryProcessor resolveProcessor(FrontendSession session) {
        if (backendRouter != null) {
            return backendRouter.resolve(session);
        }
        return queryProcessor;
    }
    // ==================== SHOW DATABASES / SHOW SCHEMAS ====================
    private boolean isShowDatabases(String sql) {
        return sql != null && SHOW_DATABASES.matcher(sql.trim()).matches();
    }

    private void handleShowDatabases(ChannelHandlerContext ctx, String sql) {
        // 获取配置的后端名称列表
        Set<String> names;
        if (backendRouter != null) {
            names = backendRouter.getBackendNames();
        } else {
            names = Collections.emptySet();
        }
        // 检查 LIKE 过滤模式
        Matcher m = SHOW_DATABASES.matcher(sql.trim());
        if (m.matches()) {
            String likePattern = m.group(2);
            if (likePattern != null && !likePattern.isEmpty()) {
                Pattern likeRegex = sqlLikeToRegex(likePattern);
                List<String> filtered = new ArrayList<>();
                for (String name : names) {
                    if (likeRegex.matcher(name).matches()) {
                        filtered.add(name);
                    }
                }
                names = new LinkedHashSet<>(filtered);
            }
        }
        writeShowDatabasesResult(ctx, names);
    }

    private void writeShowDatabasesResult(ChannelHandlerContext ctx, Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        // Column Count Packet (1 column)
        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        BufferUtils.writeLengthEncodedInt(colCountBuf, 1);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;
        // Column Def: Database
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                buildColumnDef(ctx.alloc(), "def", "", "Database", "Database", 255, 0xFD, 33), seq++));
        // EOF after columns
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq++));
        // Rows
        for (String name : sorted) {
            ByteBuf row = buildTextRow(ctx.alloc(), new String[] {name});
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
        }
        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq));
        ctx.flush();
    }
    /**
     * 将 SQL LIKE 模式（% _ 通配符）转换为 Java 正则表达式。
     */
    private static Pattern sqlLikeToRegex(String likePattern) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else if (c == '\\' && i + 1 < likePattern.length()) {
                // 转义字符：\% → % , \_ → _
                sb.append(Pattern.quote(String.valueOf(likePattern.charAt(i + 1))));
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
    // ==================== COM_PING / COM_QUIT ====================
    private void handlePing(ChannelHandlerContext ctx) {
        writeOk(ctx, 0, 0, "");
    }

    private void handleQuit(ChannelHandlerContext ctx) {
        log.debug("Client sent COM_QUIT, closing");
        ctx.close();
    }
    // ==================== COM_INIT_DB ====================
    private void handleInitDb(ChannelHandlerContext ctx, ByteBuf payload, FrontendSession session) {
        String dbName = BufferUtils.readEofString(payload);
        log.debug("COM_INIT_DB: {}", dbName);
        if (dbName != null && !dbName.equalsIgnoreCase(session.getDatabase())) {
            rollbackActiveTransaction(ctx, session);
        }
        // 多后端模式下验证该数据库名是否有对应的后端
        // 即使没有匹配的后端，也允许设置，SQL 执行时会解析到默认后端
        session.setDatabase(dbName);
        writeOk(ctx, 0, 0, "");
    }

    private void rollbackActiveTransaction(ChannelHandlerContext ctx, FrontendSession session) {
        Connection conn = ctx.channel().attr(SessionAttribute.BACKEND_CONN_KEY).get();
        if (conn != null) {
            log.info("Implicit rollback of active transaction due to database switch");
            try {
                QueryProcessor processor = resolveProcessor(session);
                processor.rollback(ctx, session);
            } catch (Exception e) {
                log.error("Failed to implicitly rollback transaction during database switch", e);
            }
        }
    }
    // ==================== COM_FIELD_LIST / Unsupported ====================
    private void handleFieldList(ChannelHandlerContext ctx, ByteBuf payload) {
        // COM_FIELD_LIST: table name + field wildcard (EOF-terminated)
        // 暂不支持，返回错误
        writeErr(ctx, 1105, "HY000", "COM_FIELD_LIST not supported");
    }

    private void handleUnsupported(ChannelHandlerContext ctx, String cmdName) {
        writeErr(ctx, 1105, "HY000", "Command not supported: " + cmdName);
    }
    // ==================== 系统变量拦截结果 → MySQL 结果集包 ====================
    private void writeInterceptedResult(ChannelHandlerContext ctx, SystemVariableInterceptor.InterceptResult ir) {
        // 检查是否为多列模式
        if (ir.isMultiColumn()) {
            writeMultiColumnResult(ctx, ir);
            return;
        }
        // 原有的单列、双列、三列逻辑
        int colCount = ir.twoColumns ? 2 : (ir.colName3 != null ? 3 : 1);
        boolean isEmpty = ir.empty;
        // Column Count Packet
        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        BufferUtils.writeLengthEncodedInt(colCountBuf, colCount);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;
        // Column Def 1 — 使用 FIELD_TYPE_VAR_STRING (0xFD) 而非 FIELD_TYPE_VARCHAR (0x0F)
        // Python 客户端（如 mysql-connector-python）对 columnType 校验严格，0x0F 会导致 "Malformed packet"
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                buildColumnDef(ctx.alloc(), "def", "", ir.colName1, ir.colName1, 255, 0xFD, 33), seq++));
        // Column Def 2
        if (ir.twoColumns || ir.colName3 != null) {
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                    buildColumnDef(ctx.alloc(), "def", "", ir.colName2, ir.colName2, 255, 0xFD, 33), seq++));
        }
        // Column Def 3 (SHOW WARNINGS 三列)
        if (ir.colName3 != null) {
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                    buildColumnDef(ctx.alloc(), "def", "", ir.colName3, ir.colName3, 255, 0xFD, 33), seq++));
        }
        // EOF (after columns)
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq++));
        // Row (skip if empty)
        if (!isEmpty) {
            if (ir.twoColumns) {
                ByteBuf row = buildTextRow(ctx.alloc(), new String[] {ir.value1, ir.value2});
                ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
            } else {
                ByteBuf row = buildTextRow(ctx.alloc(), new String[] {ir.value1});
                ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
            }
        }
        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq));
        ctx.flush();
    }
    /**
     * 写入多列系统变量查询结果。
     *
     * <p>用于拦截 MySQL Connector/J 发送的多变量查询（如 SELECT @@var1, @@var2, ...）。
     *
     * @param ctx Netty ChannelHandlerContext
     * @param ir   拦截结果（多列模式）
     */
    private void writeMultiColumnResult(ChannelHandlerContext ctx, SystemVariableInterceptor.InterceptResult ir) {
        List<SystemVariableInterceptor.ColumnInfo> columns = ir.columns;
        int colCount = columns.size();
        // Column Count Packet
        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        BufferUtils.writeLengthEncodedInt(colCountBuf, colCount);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;
        // 写入所有列定义
        for (SystemVariableInterceptor.ColumnInfo col : columns) {
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(
                    buildColumnDef(ctx.alloc(), "def", "", col.columnName, col.columnName, 255, 0xFD, 33), seq++));
        }
        // EOF (after columns)
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq++));
        // 构造一行数据（包含所有列值）
        String[] values = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            values[i] = columns.get(i).value;
        }
        ByteBuf row = buildTextRow(ctx.alloc(), values);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx), seq));
        ctx.flush();
    }
    // ==================== Packet Builders ====================
    /**
     * 构造 ColumnDefinition41 包。
     */
    static ByteBuf buildColumnDef(
            ByteBufAllocator alloc,
            String catalog,
            String schema,
            String table,
            String name,
            int columnLength,
            int columnType,
            int charset) {
        ByteBuf buf = alloc.buffer(64);
        BufferUtils.writeLengthEncodedString(buf, "def"); // catalog
        BufferUtils.writeLengthEncodedString(buf, schema); // schema
        BufferUtils.writeLengthEncodedString(buf, table); // table
        BufferUtils.writeLengthEncodedString(buf, table); // org_table
        BufferUtils.writeLengthEncodedString(buf, name); // name
        BufferUtils.writeLengthEncodedString(buf, name); // org_name
        buf.writeByte(0x0C); // length of fixed-length fields (12)
        buf.writeShortLE(charset); // character set
        buf.writeIntLE(columnLength); // column length
        buf.writeByte(columnType); // column type
        buf.writeShortLE(0); // flags
        buf.writeByte(0); // decimals
        buf.writeZero(2); // filler
        return buf;
    }
    /**
     * 构造 Text Protocol Row Packet。
     */
    static ByteBuf buildTextRow(ByteBufAllocator alloc, String[] values) {
        ByteBuf buf = alloc.buffer(256);
        for (String val : values) {
            if (val == null) {
                buf.writeByte(0xFB);
            } else {
                BufferUtils.writeLengthEncodedString(buf, val);
            }
        }
        return buf;
    }

    static ByteBuf buildEof(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer(5);
        buf.writeByte(0xFE); // EOF header
        buf.writeShortLE(0); // warnings
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        int statusFlags = ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        if (session != null) {
            statusFlags = 0;
            if (session.isAutoCommit()) {
                statusFlags |= ServerStatus.SERVER_STATUS_AUTOCOMMIT;
            }
            if (session.isInTransaction() || !session.isAutoCommit()) {
                statusFlags |= ServerStatus.SERVER_STATUS_IN_TRANS;
            }
        }
        buf.writeShortLE(statusFlags); // status_flags
        return buf;
    }

    void writeOk(ChannelHandlerContext ctx, long affectedRows, long lastInsertId, String info) {
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        int statusFlags = ServerStatus.SERVER_STATUS_AUTOCOMMIT;
        if (session != null) {
            statusFlags = 0;
            if (session.isAutoCommit()) {
                statusFlags |= ServerStatus.SERVER_STATUS_AUTOCOMMIT;
            }
            if (session.isInTransaction() || !session.isAutoCommit()) {
                statusFlags |= ServerStatus.SERVER_STATUS_IN_TRANS;
            }
        }
        ByteBuf ok = AuthHandler.buildOkPacket(ctx.alloc(), affectedRows, lastInsertId, statusFlags, 0, info);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, (byte) 1));
    }

    void writeErr(ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        ByteBuf err = AuthHandler.buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (isConnectionReset(cause)) {
            // 客户端主动断开（RST），正常现象，DEBUG 级别
            log.debug(
                    "Client {} disconnected: {}",
                    ctx.channel().remoteAddress(),
                    cause.getMessage() != null ? cause.getMessage() : "connection reset");
        } else {
            log.error("Exception in CommandHandler", cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        FrontendSession session =
                ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        if (session != null) {
            try {
                QueryProcessor processor = resolveProcessor(session);
                processor.closeSessionConnection(ctx, session);
            } catch (Exception e) {
                log.error("Error closing session bound connection in channelInactive", e);
            }
        }
        ConnectionMetrics.onDisconnect();
        NettyMetrics.onChannelInactive();
        ctx.fireChannelInactive();
    }
    /** 判断是否为客户端主动断连（非服务端异常） */
    private static boolean isConnectionReset(Throwable cause) {
        if (cause instanceof IOException) {
            String msg = cause.getMessage();
            return msg != null
                    && (msg.contains("reset by peer")
                            || msg.contains("connection reset")
                            || msg.contains("中止了一个已建立的连接")
                            || msg.contains("abort"));
        }
        return false;
    }
    // ==================== 查询处理器接口 ====================
    /**
     * 后端查询处理器接口。
     * proxy-backend 模块实现此接口，注入到 CommandHandler。
     */
    public interface QueryProcessor {
        /** 空实现（Phase 3 占位，PostgreSQL 后端在 Phase 4 中实现） */
        QueryProcessor NOOP = (ctx, sql, session) -> {
            ByteBuf err = AuthHandler.buildErrPacket(ctx.alloc(), 1105, "HY000", "Backend not configured");
            ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
        };
        /**
         * 处理一条 SQL 查询。
         *
         * @param ctx     Netty 上下文
         * @param sql     原始 SQL（文本）
         * @param session 当前会话
         */
        void process(ChannelHandlerContext ctx, String sql, FrontendSession session);

        /**
         * 处理一条带绑定参数的 SQL 查询（用于 PostgreSQL 扩展查询协议 {@code $1} 占位符）。
         *
         * <p>默认实现不做参数绑定（不支持时抛出 {@link UnsupportedOperationException}）。
         * 文本协议（MySQL / PG 简单查询）以空参数列表调用本方法，等价于 {@link #process}。
         *
         * @param ctx     Netty 上下文
         * @param sql     原始 SQL（文本，可能含 {@code $1} 占位符）
         * @param params  按序号绑定的文本参数值（扩展查询协议）；文本协议传空列表
         * @param session 当前会话
         */
        default void process(ChannelHandlerContext ctx, String sql, List<String> params, FrontendSession session) {
            throw new UnsupportedOperationException("Parameterized execution is not supported by this processor");
        }
        /**
         * 提交当前会话绑定的事务。
         */
        default void commit(ChannelHandlerContext ctx, FrontendSession session) throws Exception {}
        /**
         * 回滚当前会话绑定的事务。
         */
        default void rollback(ChannelHandlerContext ctx, FrontendSession session) throws Exception {}
        /**
         * 强制关闭并清理当前绑定的连接（异常断连时调用）。
         */
        default void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {}
        /**
         * 关闭后端连接池（默认空实现，实现类按需重写）。
         */
        default void close() {}
    }
}
