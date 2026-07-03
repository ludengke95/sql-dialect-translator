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

    /** 后端查询处理器（Phase 4 中由 proxy-backend 模块注入） */
    private static volatile QueryProcessor queryProcessor = QueryProcessor.NOOP;

    /**
     * 设置全局查询处理器（启动时由 proxy-server 注入）。
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

        // 4. 否则委托给后端查询处理器
        queryProcessor.process(ctx, sql, session);
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
        if (ir.twoColumns) {
            writeTwoColumnResult(ctx, ir.colName1, ir.colName2, ir.value1, ir.value2);
        } else {
            writeOneColumnResult(ctx, ir.colName1, ir.value1);
        }
    }

    private void writeOneColumnResult(ChannelHandlerContext ctx, String colName, String value) {
        // Column Count Packet (1列)
        ByteBuf colCount = ctx.alloc().buffer(1);
        colCount.writeByte(1);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCount, (byte) 1));

        // Column Definition Packet
        ByteBuf colDef = buildColumnDef(ctx.alloc(), "def", "", colName, colName,
                255, 15, 33); // VARCHAR, charset utf8mb4
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef, (byte) 2));

        // EOF (column def end) — with CLIENT_DEPRECATE_EOF, send OK instead
        // 为简单起见，发送 EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), (byte) 3));

        // Row Packet (1 行)
        ByteBuf row = buildTextRow(ctx.alloc(), new String[]{value});
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, (byte) 4));

        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), (byte) 5));

        ctx.flush();
    }

    private void writeTwoColumnResult(ChannelHandlerContext ctx,
                                       String col1, String col2, String val1, String val2) {
        // Column Count
        ByteBuf colCount = ctx.alloc().buffer(1);
        colCount.writeByte(2);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCount, (byte) 1));

        // Column Def 1
        ByteBuf colDef1 = buildColumnDef(ctx.alloc(), "def", "", col1, col1,
                255, 15, 33);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef1, (byte) 2));

        // Column Def 2
        ByteBuf colDef2 = buildColumnDef(ctx.alloc(), "def", "", col2, col2,
                255, 15, 33);
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef2, (byte) 3));

        // EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), (byte) 4));

        // Row
        ByteBuf row = buildTextRow(ctx.alloc(), new String[]{val1, val2});
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, (byte) 5));

        // Final EOF
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(buildEof(ctx.alloc()), (byte) 6));

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

    /**
     * 构造 EOF Packet（列定义结束/结果集结束）。
     */
    static ByteBuf buildEof(ByteBufAllocator alloc) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(0xFE);                        // EOF header
        buf.writeShortLE(0);                        // warnings
        buf.writeShortLE(ServerStatus.SERVER_STATUS_AUTOCOMMIT); // status flags
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
        log.error("Exception in CommandHandler", cause);
        ctx.close();
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
    }
}
