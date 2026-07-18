package com.translator.proxy.backend;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.translator.proxy.backend.mapper.ResultSetEncoder;

import io.netty.channel.ChannelHandlerContext;

/**
 * 后端执行结果写出抽象。
 *
 * <p>把「JDBC 结果集 / 错误信息如何写成前端线协议包」从 MySQL 硬编码抽离：
 * <ul>
 *   <li>MySQL 实现：{@link ResultSetEncoder}（ColumnCount/ColDef/EOF/Row，MySQL ERR 包）</li>
 *   <li>PostgreSQL 实现：{@code PgResultEncoder}（RowDescription/DataRow/CommandComplete，PG ErrorResponse）</li>
 * </ul>
 *
 * <p>{@link JdbcBackendQueryProcessor} 持有本接口的实例（默认 {@link ResultSetEncoder}），
 * 由 {@code ProxyBootstrap} 按前端协议注入对应实现，从而 MySQL 行为保持不变。
 */
public interface BackendResultWriter {

    /**
     * 将 JDBC ResultSet 流式编码并写入 Channel。
     *
     * @param ctx         Netty 上下文
     * @param rs          JDBC 结果集
     * @param seqGen      sequence ID 生成器（PG 等无 seq 协议可忽略）
     * @param backendName 后端名称（指标打点），可为 null
     */
    void encodeAndWrite(ChannelHandlerContext ctx, ResultSet rs, ResultSetEncoder.SeqGenerator seqGen, String backendName)
            throws SQLException;

    /** 发送空结果集（UPDATE/INSERT/DELETE/DDL 等无结果集语句）。 */
    void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId);

    /** 将 SQLException 写成前端协议错误响应。 */
    void writeError(ChannelHandlerContext ctx, SQLException e);

    /** 将错误码/SQLState/消息写成前端协议错误响应。 */
    void writeError(ChannelHandlerContext ctx, int errorCode, String sqlState, String message);
}
