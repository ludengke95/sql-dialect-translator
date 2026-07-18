package com.translator.proxy.backend;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.translator.proxy.backend.mapper.ResultSetEncoder;
import com.translator.proxy.core.handler.AuthHandler;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * MySQL 前端的结果/错误写出器（{@link BackendResultWriter} 的 MySQL 实现）。
 *
 * <p>委托 {@link ResultSetEncoder} 的静态方法完成 ResultSet → MySQL 文本协议包的编码，
 * 错误包通过 {@link AuthHandler#buildErrPacket} 构造。
 * 作为 {@link JdbcBackendQueryProcessor} 的默认 {@code resultWriter}，
 * 也可由 {@code ProxyBootstrap} 在 PG 前端场景下替换为 PG 实现，从而复用同一执行层。
 */
public class MysqlResultWriter implements BackendResultWriter {

    @Override
    public void encodeAndWrite(
            ChannelHandlerContext ctx, ResultSet rs, ResultSetEncoder.SeqGenerator seqGen, String backendName)
            throws SQLException {
        ResultSetEncoder.encodeAndWrite(ctx, rs, seqGen, backendName);
    }

    @Override
    public void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId) {
        ResultSetEncoder.encodeEmpty(ctx, affectedRows, lastInsertId);
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
        ByteBuf err = AuthHandler.buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
    }
}
