package com.translator.proxy.protocol.pg;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.backend.BackendResultWriter;
import com.translator.proxy.backend.mapper.ResultSetEncoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;

/**
 * PostgreSQL 前端的后端结果写出器（{@link BackendResultWriter} 的 PG 实现）。
 *
 * <p>将 JDBC 结果集编码为 PG 简单/扩展查询协议的响应序列：
 * <pre>
 *   RowDescription('T') → N*DataRow('D') → CommandComplete('C')
 * </pre>
 * 错误响应为 ErrorResponse('E')。{@code ReadyForQuery('Z')} 由
 * {@link PgCommandDispatcher} 在 {@code process} 返回后统一发送（简单查询与
 * 扩展查询的 Sync 阶段各发送一次），因此本写出器不负责发送 ReadyForQuery。
 *
 * <p>CommandComplete 的命令标签取自 channel 属性 {@link PgProtocol#PG_COMMAND_TAG}：
 * 由 {@link PgCommandDispatcher} 在调用处理器前按 SQL 动词设置；写 ResultSet 时取
 * "SELECT"，写空结果（DML/DDL）时按动词拼出 {@code INSERT 0 n}/{@code UPDATE n}/...。
 */
public class PgResponseWriter implements BackendResultWriter {

    private static final Logger log = LoggerFactory.getLogger(PgResponseWriter.class);

    @Override
    public void encodeAndWrite(
            ChannelHandlerContext ctx, ResultSet rs, ResultSetEncoder.SeqGenerator seqGen, String backendName)
            throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        log.info("PG encode ResultSet: {} columns", columnCount);

        // 1. RowDescription
        ctx.write(PgOutbound.framed(buildRowDescription(ctx.alloc(), meta, columnCount)));

        // 2. DataRow*
        int rowCount = 0;
        while (rs.next()) {
            ctx.write(PgOutbound.framed(buildDataRow(ctx.alloc(), rs, columnCount, meta)));
            rowCount++;
        }
        log.info("PG encoded {} rows", rowCount);

        // 3. CommandComplete
        ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), commandTag(ctx, "SELECT"))));
        // 注意：不发送 ReadyForQuery，交由 PgCommandDispatcher 发送
    }

    @Override
    public void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId) {
        ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), emptyCommandTag(ctx, affectedRows))));
    }

    @Override
    public void writeError(ChannelHandlerContext ctx, SQLException e) {
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();
        String message = e.getMessage();
        writeError(
                ctx,
                errorCode != 0 ? errorCode : 1105,
                sqlState != null ? sqlState : "HY000",
                message != null ? message : "Unknown SQL error");
    }

    @Override
    public void writeError(ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), sqlState, message)));
    }

    // ==================== 消息构造 ====================

    private static ByteBuf buildRowDescription(ByteBufAllocator alloc, ResultSetMetaData meta, int columnCount)
            throws SQLException {
        ByteBuf b = alloc.buffer(64 + columnCount * 32);
        b.writeByte(PgProtocol.MSG_ROW_DESCRIPTION);
        b.writeShort(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            writeCString(b, meta.getColumnLabel(i));
            b.writeInt(0); // tableOID
            b.writeShort(0); // columnAttrNumber
            int jdbcType = meta.getColumnType(i);
            PgColumnType ct = PgTypeMapper.jdbcToPg(jdbcType);
            b.writeInt(ct.oid); // typeOID
            b.writeShort((short) ct.typeLen); // typeLen
            b.writeInt(ct.typeMod); // typeMod
            b.writeShort(0); // formatCode = 0 (text)
        }
        return b;
    }

    private static ByteBuf buildDataRow(ByteBufAllocator alloc, ResultSet rs, int columnCount, ResultSetMetaData meta)
            throws SQLException {
        ByteBuf b = alloc.buffer(64 + columnCount * 32);
        b.writeByte(PgProtocol.MSG_DATA_ROW);
        b.writeShort(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            boolean wasNull = rs.wasNull();
            if (wasNull || value == null) {
                b.writeInt(-1); // NULL
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                b.writeInt(bytes.length);
                b.writeBytes(bytes);
            }
        }
        return b;
    }

    private static ByteBuf buildCommandComplete(ByteBufAllocator alloc, String tag) {
        ByteBuf b = alloc.buffer(32);
        b.writeByte(PgProtocol.MSG_COMMAND_COMPLETE);
        writeCString(b, tag);
        return b;
    }

    private static ByteBuf buildErrorResponse(ByteBufAllocator alloc, String sqlState, String message) {
        ByteBuf b = alloc.buffer(64 + (message != null ? message.length() : 0));
        b.writeByte(PgProtocol.MSG_ERROR_RESPONSE);
        writeField(b, 'S', "ERROR"); // severity
        writeField(b, 'V', "ERROR"); // non-localized severity
        writeField(b, 'C', sqlState != null && sqlState.length() == 5 ? sqlState : "HY000"); // code
        writeField(b, 'M', message != null ? message : "Unknown error"); // message
        b.writeByte(0); // terminator
        return b;
    }

    // ==================== 辅助 ====================

    private static String commandTag(ChannelHandlerContext ctx, String dflt) {
        String tag = ctx.channel().attr(PgProtocol.PG_COMMAND_TAG).get();
        return tag != null ? tag : dflt;
    }

    private static String emptyCommandTag(ChannelHandlerContext ctx, long affected) {
        String verb = ctx.channel().attr(PgProtocol.PG_COMMAND_TAG).get();
        if (verb == null) {
            return "INSERT 0 " + affected;
        }
        if ("INSERT".equalsIgnoreCase(verb)) {
            return "INSERT 0 " + affected;
        }
        if ("UPDATE".equalsIgnoreCase(verb)) {
            return "UPDATE " + affected;
        }
        if ("DELETE".equalsIgnoreCase(verb)) {
            return "DELETE " + affected;
        }
        // DDL / BEGIN / COMMIT / SET 等：直接以动词作为命令标签
        return verb;
    }

    private static void writeCString(ByteBuf b, String s) {
        if (s == null) {
            s = "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.writeBytes(bytes);
        b.writeByte(0);
    }

    private static void writeField(ByteBuf b, char fieldType, String value) {
        b.writeByte((byte) fieldType);
        writeCString(b, value);
    }
}
