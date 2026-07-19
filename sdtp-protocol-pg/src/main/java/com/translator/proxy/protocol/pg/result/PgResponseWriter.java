package com.translator.proxy.protocol.pg.result;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.protocol.frontend.ResponseWriter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import com.translator.proxy.protocol.pg.codec.PgMessage;
import com.translator.proxy.protocol.pg.codec.PgWire;
import com.translator.proxy.protocol.pg.result.PgTypeMapper;

/**
 * PostgreSQL 响应写入器 —— 将 JDBC ResultSet 编码为 PG 协议消息。
 *
 * <p>实现 {@link ResponseWriter} 接口，将结果编码为：
 * <pre>
 *   RowDescription → N*DataRow → CommandComplete → ReadyForQuery
 * </pre>
 */
public class PgResponseWriter implements ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(PgResponseWriter.class);

    private final PgTypeMapper typeMapper = new PgTypeMapper();

    @Override
    public void writeOk(ChannelHandlerContext ctx, long affectedRows, long lastInsertId, int statusFlags, int warnings,
            String info) {
        // PG 用 CommandComplete + ReadyForQuery 代替 OK
        String tag = "";
        if (info != null && !info.isEmpty()) {
            tag = info;
        } else if (affectedRows >= 0) {
            tag = "OK " + affectedRows;
        } else {
            tag = "OK";
        }
        sendCommandComplete(ctx, tag);
        sendReadyForQuery(ctx, PgWire.TXN_IDLE);
    }

    @Override
    public void writeErr(ChannelHandlerContext ctx, int errorCode, String sqlState, String message) {
        sendError(ctx, "ERROR", sqlState, message);
        sendReadyForQuery(ctx, PgWire.TXN_IDLE);
    }

    @Override
    public void writeColumnDef(ChannelHandlerContext ctx, ResultSetMetaData metaData, int columnIndex)
            throws SQLException {
        // PG 使用 RowDescription 消息（一次发送所有列定义）
        throw new UnsupportedOperationException(
                "PG protocol uses RowDescription for all columns at once. Use writeRowDescription instead.");
    }

    @Override
    public void writeEof(ChannelHandlerContext ctx, int statusFlags) {
        // PG 不使用 EOF，空实现
    }

    @Override
    public void writeTextRow(ChannelHandlerContext ctx, ResultSet rs, int columnCount) throws SQLException {
        // PG 使用 DataRow 消息
        sendDataRow(ctx, rs, columnCount);
    }

    // ==================== PG 特定的写入方法 ====================

    /**
     * 写入 RowDescription 消息（所有列的元数据）。
     */
    public void writeRowDescription(ChannelHandlerContext ctx, ResultSetMetaData meta) throws SQLException {
        int columnCount = meta.getColumnCount();
        ByteBuf payload = ctx.alloc().buffer(128);
        payload.writeShort(columnCount);

        for (int i = 1; i <= columnCount; i++) {
            String colName = meta.getColumnLabel(i);
            int jdbcType = meta.getColumnType(i);
            String typeName = meta.getColumnTypeName(i);
            int pgOid = typeMapper.jdbcToProtocolType(jdbcType, typeName);
            int colLen = meta.getColumnDisplaySize(i);
            if (colLen <= 0) colLen = -1;
            int typeModifier = -1;

            PgWire.cstr(payload, colName);
            payload.writeInt(0);       // table OID
            payload.writeShort(0);     // column attribute number
            payload.writeInt(pgOid);   // data type OID
            payload.writeShort(colLen > 0 ? colLen : 255); // type size
            payload.writeInt(typeModifier); // type modifier
            payload.writeShort(0);     // format code (0 = text)
        }

        ctx.write(new PgMessage(PgWire.MSG_ROW_DESCRIPTION, payload));
    }

    /**
     * 发送 CommandComplete 消息。
     */
    public void sendCommandComplete(ChannelHandlerContext ctx, String tag) {
        ByteBuf payload = ctx.alloc().buffer(64);
        PgWire.cstr(payload, tag);
        ctx.write(new PgMessage(PgWire.MSG_COMMAND_COMPLETE, payload));
    }

    /**
     * 发送 ReadyForQuery 消息。
     */
    public void sendReadyForQuery(ChannelHandlerContext ctx, byte txnStatus) {
        ByteBuf payload = ctx.alloc().buffer(1);
        payload.writeByte(txnStatus);
        ctx.writeAndFlush(new PgMessage(PgWire.MSG_READY_FOR_QUERY, payload));
    }

    /**
     * 发送 ErrorResponse 消息。
     */
    public void sendError(ChannelHandlerContext ctx, String severity, String code, String message) {
        ByteBuf payload = ctx.alloc().buffer(128);
        PgWire.cstr(payload, "S");
        PgWire.cstr(payload, severity);
        PgWire.cstr(payload, "C");
        PgWire.cstr(payload, code);
        PgWire.cstr(payload, "M");
        PgWire.cstr(payload, message != null ? message : "unknown error");
        payload.writeByte(0x00);
        ctx.write(new PgMessage(PgWire.MSG_ERROR_RESPONSE, payload));
    }

    /**
     * 发送 DataRow 消息。
     */
    public void sendDataRow(ChannelHandlerContext ctx, ResultSet rs, int columnCount) throws SQLException {
        ByteBuf payload = ctx.alloc().buffer(256);
        payload.writeShort(columnCount);

        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            if (rs.wasNull() || value == null) {
                payload.writeInt(-1); // NULL
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                payload.writeInt(bytes.length);
                payload.writeBytes(bytes);
            }
        }

        ctx.write(new PgMessage(PgWire.MSG_DATA_ROW, payload));
    }

    /**
     * 发送空查询响应。
     */
    public void sendEmptyQuery(ChannelHandlerContext ctx) {
        ByteBuf payload = ctx.alloc().buffer(0);
        ctx.write(new PgMessage(PgWire.MSG_EMPTY_QUERY_RESPONSE, payload));
    }
}
