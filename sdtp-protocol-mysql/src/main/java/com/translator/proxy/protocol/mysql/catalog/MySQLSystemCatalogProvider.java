package com.translator.proxy.protocol.mysql.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.translator.proxy.protocol.frontend.SystemCatalogProvider;
import com.translator.proxy.protocol.mysql.auth.MySQLAuthHandler;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;
import com.translator.proxy.protocol.mysql.util.SystemVariableInterceptor;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;

/**
 * MySQL 系统目录提供者 —— 包装 {@link SystemVariableInterceptor} 的逻辑，
 * 模拟 MySQL 系统变量查询。
 *
 * <p>实现 {@link SystemCatalogProvider} 接口，拦截常见的
 * {@code SELECT @@xxx}、{@code SHOW VARIABLES}、{@code SELECT DATABASE()} 等查询。
 */
public class MySQLSystemCatalogProvider implements SystemCatalogProvider {

    /** SELECT DATABASE() */
    private static final Pattern SELECT_DATABASE =
            Pattern.compile("^\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean canHandle(String sql) {
        if (sql == null) {
            return false;
        }
        // 委托给 SystemVariableInterceptor 检查
        if (SystemVariableInterceptor.isSetStatement(sql)) {
            return true;
        }
        if (SystemVariableInterceptor.intercept(sql, null) != null) {
            return true;
        }
        if (SystemVariableInterceptor.extractUseDatabase(sql) != null) {
            return true;
        }
        if (SELECT_DATABASE.matcher(sql.trim()).matches()) {
            return true;
        }
        return false;
    }

    @Override
    public void handleQuery(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        String database = session != null ? session.getDatabase() : null;

        // 检查 USE 语句
        String useDb = SystemVariableInterceptor.extractUseDatabase(sql);
        if (useDb != null) {
            session.setDatabase(useDb);
            MySQLResponseWriter responseWriter = new MySQLResponseWriter();
            responseWriter.writeOk(ctx, 0, 0,
                    MySQLAuthHandler.getStatusFlags(session), 0, "");
            return;
        }

        // 检查 SET 语句
        if (SystemVariableInterceptor.isSetStatement(sql)) {
            MySQLResponseWriter responseWriter = new MySQLResponseWriter();
            responseWriter.writeOk(ctx, 0, 0,
                    MySQLAuthHandler.getStatusFlags(session), 0, "");
            return;
        }

        // 检查系统变量查询
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept(sql, database);
        if (ir != null) {
            writeInterceptedResult(ctx, ir, session);
            return;
        }

        // 兜底：不处理
    }

    /**
     * 将拦截结果写入 Channel。
     */
    private void writeInterceptedResult(ChannelHandlerContext ctx,
            SystemVariableInterceptor.InterceptResult ir, FrontendSession session) {
        if (ir.isMultiColumn()) {
            writeMultiColumnResult(ctx, ir, session);
            return;
        }

        int colCount = ir.twoColumns ? 2 : (ir.colName3 != null ? 3 : 1);
        boolean isEmpty = ir.empty;

        int statusFlags = MySQLAuthHandler.getStatusFlags(session);

        // Column Count Packet
        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        com.translator.proxy.protocol.mysql.util.BufferUtils.writeLengthEncodedInt(colCountBuf, colCount);
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;

        // Column Def 1
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildColumnDef(
                        ctx.alloc(), "def", "", ir.colName1, ir.colName1, 255, 0xFD, 33),
                seq++));

        // Column Def 2
        if (ir.twoColumns || ir.colName3 != null) {
            ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                    MySQLResponseWriter.buildColumnDef(
                            ctx.alloc(), "def", "", ir.colName2, ir.colName2, 255, 0xFD, 33),
                    seq++));
        }

        // Column Def 3
        if (ir.colName3 != null) {
            ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                    MySQLResponseWriter.buildColumnDef(
                            ctx.alloc(), "def", "", ir.colName3, ir.colName3, 255, 0xFD, 33),
                    seq++));
        }

        // EOF
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq++));

        // Row
        if (!isEmpty) {
            if (ir.twoColumns) {
                ByteBuf row = MySQLResponseWriter.buildTextRow(
                        ctx.alloc(), new String[] {ir.value1, ir.value2});
                ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(row, seq++));
            } else {
                ByteBuf row = MySQLResponseWriter.buildTextRow(
                        ctx.alloc(), new String[] {ir.value1});
                ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(row, seq++));
            }
        }

        // Final EOF
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq));
        ctx.flush();
    }

    private void writeMultiColumnResult(ChannelHandlerContext ctx,
            SystemVariableInterceptor.InterceptResult ir, FrontendSession session) {
        java.util.List<SystemVariableInterceptor.ColumnInfo> columns = ir.columns;
        int colCount = columns.size();
        int statusFlags = MySQLAuthHandler.getStatusFlags(session);

        ByteBuf colCountBuf = ctx.alloc().buffer(2);
        com.translator.proxy.protocol.mysql.util.BufferUtils.writeLengthEncodedInt(colCountBuf, colCount);
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(colCountBuf, (byte) 1));
        byte seq = 2;

        for (SystemVariableInterceptor.ColumnInfo col : columns) {
            ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                    MySQLResponseWriter.buildColumnDef(
                            ctx.alloc(), "def", "", col.columnName, col.columnName, 255, 0xFD, 33),
                    seq++));
        }

        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq++));

        String[] values = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            values[i] = columns.get(i).value;
        }
        ByteBuf row = MySQLResponseWriter.buildTextRow(ctx.alloc(), values);
        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(row, seq++));

        ctx.write(new com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder.OutgoingPacket(
                MySQLResponseWriter.buildEof(ctx.alloc(), statusFlags), seq));
        ctx.flush();
    }

    @Override
    public Map<String, String> getVariables() {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        vars.put("version_comment", "MySQL Proxy 5.7.38");
        vars.put("version", "5.7.38-proxy");
        vars.put("version_compile_os", "Linux");
        vars.put("version_compile_machine", "x86_64");
        vars.put("character_set_client", "utf8mb4");
        vars.put("character_set_connection", "utf8mb4");
        vars.put("character_set_results", "utf8mb4");
        vars.put("character_set_server", "utf8mb4");
        vars.put("collation_connection", "utf8mb4_general_ci");
        vars.put("collation_server", "utf8mb4_general_ci");
        vars.put("tx_isolation", "READ-COMMITTED");
        vars.put("transaction_isolation", "READ-COMMITTED");
        vars.put("autocommit", "1");
        vars.put("max_allowed_packet", "16777216");
        vars.put("wait_timeout", "28800");
        vars.put("interactive_timeout", "28800");
        vars.put("sql_mode",
                "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION");
        vars.put("lower_case_table_names", "1");
        vars.put("license", "GPL");
        vars.put("innodb_version", "5.7.38");
        vars.put("protocol_version", "10");
        vars.put("ssl_cipher", "");
        vars.put("have_ssl", "DISABLED");
        vars.put("auto_increment_increment", "1");
        vars.put("net_write_timeout", "60");
        vars.put("performance_schema", "0");
        vars.put("query_cache_size", "0");
        vars.put("query_cache_type", "OFF");
        vars.put("system_time_zone", "UTC");
        vars.put("time_zone", "SYSTEM");
        vars.put("init_connect", "");
        vars.put("transaction_read_only", "0");
        vars.put("tx_read_only", "0");
        return Collections.unmodifiableMap(vars);
    }
}
