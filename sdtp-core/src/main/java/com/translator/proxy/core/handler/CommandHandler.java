package com.translator.proxy.core.handler;

import com.translator.proxy.core.intercept.SystemVariableInterceptor;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.constant.ServerStatus;
import com.translator.proxy.protocol.util.BufferUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

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
            log.debug("Command: {} (seq={})", CommandType.nameOf(command), raw.getSequenceId());

            // 每个命令开始时，客户端重置 seq 为 0
            FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();

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
            session.setDatabase(useDb);
            writeOk(ctx, 0, 0, "");
            return;
        }

        // 2. 检查是否 SET 语句（Proxy 内部处理）
        if (SystemVariableInterceptor.isSetStatement(sql)) {
            log.debug("Handling SET statement locally: {}", sql);
            writeOk(ctx, 0, 0, "");
            return;
        }

        // 3. 检查是否为系统变量查询
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept(sql, session.getDatabase());
        if (ir != null) {
            log.debug("Intercepted system variable query: {}", sql);
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
        String dbName = BufferUtils.readNullTerminatedString(payload);
        log.debug("COM_INIT_DB: {}", dbName);

        // 多后端模式下验证该数据库名是否有对应的后端
        // 即使没有匹配的后端，也允许设置，SQL 执行时会解析到默认后端
        session.setDatabase(dbName);
        writeOk(ctx, 0, 0, "");
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

    private void writeInterceptedResult(ChannelHandlerContext ctx,
                                         SystemVariableInterceptor.InterceptResult ir) {
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
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), seq++));

        // Row (skip if empty)
        if (!isEmpty) {
            if (ir.twoColumns) {
                ByteBuf row = buildTextRow(ctx.alloc(), new String[]{ir.value1, ir.value2});
                ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
            } else {
                ByteBuf row = buildTextRow(ctx.alloc(), new String[]{ir.value1});
                ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seq++));
            }
        }

        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), seq));

        ctx.flush();
    }

    // ==================== Packet Builders ====================

    /**
     * 构造 ColumnDefinition41 包。
     */
    static ByteBuf buildColumnDef(ByteBufAllocator alloc,
                                   String catalog, String schema, String table,
                                   String name, int columnLength, int columnType,
                                   int charset) {
        ByteBuf buf = alloc.buffer(64);
        BufferUtils.writeLengthEncodedString(buf, "def");  // catalog
        BufferUtils.writeLengthEncodedString(buf, schema); // schema
        BufferUtils.writeLengthEncodedString(buf, table);  // table
        BufferUtils.writeLengthEncodedString(buf, table);  // org_table
        BufferUtils.writeLengthEncodedString(buf, name);   // name
        BufferUtils.writeLengthEncodedString(buf, name);   // org_name
        buf.writeByte(0x0C);                               // length of fixed-length fields (12)
        buf.writeShortLE(charset);                         // character set
        buf.writeIntLE(columnLength);                      // column length
        buf.writeByte(columnType);                         // column type
        buf.writeShortLE(0);                               // flags
        buf.writeByte(0);                                  // decimals
        buf.writeZero(2);                                  // filler
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

    static ByteBuf buildEof(ByteBufAllocator alloc) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(0xFE);                                    // EOF header
        buf.writeShortLE(0);                                    // warnings
        buf.writeShortLE(ServerStatus.SERVER_STATUS_AUTOCOMMIT);// status_flags
        return buf;
    }

    void writeOk(ChannelHandlerContext ctx, long affectedRows, long lastInsertId, String info) {
        ByteBuf ok = AuthHandler.buildOkPacket(ctx.alloc(), affectedRows, lastInsertId,
                ServerStatus.SERVER_STATUS_AUTOCOMMIT, 0, info);
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
            log.debug("Client {} disconnected: {}", ctx.channel().remoteAddress(),
                    cause.getMessage() != null ? cause.getMessage() : "connection reset");
        } else {
            log.error("Exception in CommandHandler", cause);
        }
        ctx.close();
    }

    /** 判断是否为客户端主动断连（非服务端异常） */
    private static boolean isConnectionReset(Throwable cause) {
        if (cause instanceof java.io.IOException) {
            String msg = cause.getMessage();
            return msg != null && (msg.contains("reset by peer")
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
            ByteBuf err = AuthHandler.buildErrPacket(ctx.alloc(),
                    1105, "HY000", "Backend not configured");
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
         * 关闭后端连接池（默认空实现，实现类按需重写）。
         */
        default void close() {}
    }
}
