package com.translator.proxy.backend.mapper;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.BackendMetrics;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.ServerStatus;
import com.translator.proxy.protocol.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * JDBC ResultSet → MySQL 线协议包编码器。
 *
 * <p>将 JDBC 查询结果流式转换为 MySQL 文本协议的结果集包序列：
 * <pre>
 *   ColumnCount → N*ColumnDef → EOF → N*Row → EOF/OK
 * </pre>
 *
 * <p>关键原则：
 * <ul>
 *   <li>COM_QUERY 使用文本协议 → 所有值统一用 rs.getString() 取</li>
 *   <li>大结果集流式发送：逐行 write()（不 flush），最后 flush()</li>
 * </ul>
 */
public final class ResultSetEncoder {

    private static final Logger log = LoggerFactory.getLogger(ResultSetEncoder.class);

    /** 单次 flush 的最大行数（用于大结果集分批 flush） */
    private static final int FLUSH_BATCH_SIZE = 100;

    private ResultSetEncoder() {}

    /**
     * 将 ResultSet 流式编码并写入 Netty Channel。
     *
     * @param ctx         Netty 上下文
     * @param rs          JDBC 结果集
     * @param seqGen      sequence ID 生成器（从包 1 开始，每次递增）
     * @param backendName 后端名称（用于指标打点），可为 null
     */
    public static void encodeAndWrite(ChannelHandlerContext ctx, ResultSet rs, SeqGenerator seqGen, String backendName)
            throws SQLException {

        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        log.info("Encoding ResultSet: {} columns", columnCount);

        // 1. Column Count Packet [seq=1]
        ByteBuf colCount = ctx.alloc().buffer(9);
        BufferUtils.writeLengthEncodedInt(colCount, columnCount);
        log.debug("ColumnCount packet: {} bytes (seq={})", colCount.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(colCount, seqGen.next()));

        // 2. Column Definition Packets
        for (int i = 1; i <= columnCount; i++) {
            ByteBuf colDef = buildColumnDef(ctx, meta, i);
            log.debug(
                    "ColDef[{}] packet: {} bytes (seq={})",
                    meta.getColumnLabel(i),
                    colDef.readableBytes(),
                    seqGen.peek());
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(colDef, seqGen.next()));
        }

        // 3. EOF after columns
        ByteBuf eof1 = buildEof(ctx);
        log.debug("EOF(after columns): {} bytes (seq={})", eof1.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(eof1, seqGen.next()));

        // 4. Row Packets
        int rowCount = 0;
        while (rs.next()) {
            ByteBuf row = buildTextRow(ctx, rs, columnCount, meta);
            if (rowCount == 0) {
                log.debug("First row packet: {} bytes (seq={})", row.readableBytes(), seqGen.peek());
            }
            ctx.write(new MySQLPacketEncoder.OutgoingPacket(row, seqGen.next()));
            rowCount++;
            if (rowCount % FLUSH_BATCH_SIZE == 0) {
                ctx.flush();
            }
        }

        log.info("Encoded {} rows", rowCount);

        if (backendName != null) {
            BackendMetrics.observeResultRows(backendName, rowCount);
        }

        // 5. Final EOF
        ByteBuf eof2 = buildEof(ctx);
        log.debug("EOF(after rows): {} bytes (seq={})", eof2.readableBytes(), seqGen.peek());
        ctx.write(new MySQLPacketEncoder.OutgoingPacket(eof2, seqGen.next()));

        ctx.flush();
    }

    /**
     * 将 ResultSet 流式编码并写入 Netty Channel（便捷方法，不带指标打点）。
     */
    public static void encodeAndWrite(ChannelHandlerContext ctx, ResultSet rs, SeqGenerator seqGen)
            throws SQLException {
        encodeAndWrite(ctx, rs, seqGen, null);
    }

    /**
     * 发送空结果集（只有 Column Count = 0 + OK）。
     */
    public static void encodeEmpty(ChannelHandlerContext ctx, long affectedRows, long lastInsertId) {
        ByteBuf ok = ctx.alloc().buffer(16);
        ok.writeByte(0x00); // OK header (not 0xFE which is EOF)
        BufferUtils.writeLengthEncodedInt(ok, affectedRows);
        BufferUtils.writeLengthEncodedInt(ok, lastInsertId);
        ok.writeShortLE(ServerStatus.SERVER_STATUS_AUTOCOMMIT);
        ok.writeShortLE(0); // warnings
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, (byte) 1));
    }

    // ==================== Column Definition Builder ====================

    private static ByteBuf buildColumnDef(ChannelHandlerContext ctx, ResultSetMetaData meta, int colIndex)
            throws SQLException {
        ByteBuf buf = ctx.alloc().buffer(128);

        String schema = meta.getSchemaName(colIndex);
        String table = meta.getTableName(colIndex);
        String name = meta.getColumnLabel(colIndex); // 用 label（别名优先）
        String orgName = meta.getColumnName(colIndex);

        int jdbcType = meta.getColumnType(colIndex);
        int mysqlType = TypeMapper.jdbcToMysql(jdbcType);
        int colLen = meta.getColumnDisplaySize(colIndex);
        if (colLen <= 0) colLen = TypeMapper.defaultColumnLength(jdbcType);
        int charset = TypeMapper.isBinary(jdbcType) ? TypeMapper.CHARSET_BINARY : TypeMapper.CHARSET_UTF8MB4;
        int decimals = meta.getScale(colIndex);

        // catalog
        BufferUtils.writeLengthEncodedString(buf, "def");
        // schema
        BufferUtils.writeLengthEncodedString(buf, schema != null ? schema : "");
        // table (virtual)
        BufferUtils.writeLengthEncodedString(buf, table != null ? table : "");
        // org_table
        BufferUtils.writeLengthEncodedString(buf, table != null ? table : "");
        // name
        BufferUtils.writeLengthEncodedString(buf, name);
        // org_name
        BufferUtils.writeLengthEncodedString(buf, orgName != null ? orgName : name);
        // length of fixed-length fields (always 0x0C = 12)
        buf.writeByte(0x0C);
        // charset
        buf.writeShortLE(charset);
        // column length
        buf.writeIntLE(Math.min(colLen, Integer.MAX_VALUE));
        // column type
        buf.writeByte(mysqlType);
        // flags
        buf.writeShortLE(0);
        // decimals
        buf.writeByte(decimals);
        // filler
        buf.writeZero(2);

        return buf;
    }

    // ==================== Text Row Builder ====================

    private static ByteBuf buildTextRow(
            ChannelHandlerContext ctx, ResultSet rs, int columnCount, ResultSetMetaData meta) throws SQLException {
        ByteBuf buf = ctx.alloc().buffer(256);

        for (int i = 1; i <= columnCount; i++) {
            String value = rs.getString(i);
            boolean wasNull = rs.wasNull();

            if (wasNull || value == null) {
                buf.writeByte(0xFB); // NULL marker
            } else {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                BufferUtils.writeLengthEncodedInt(buf, bytes.length);
                buf.writeBytes(bytes);
            }
        }

        return buf;
    }

    private static ByteBuf buildEof(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer(5);
        buf.writeByte(0xFE); // EOF header
        buf.writeShortLE(0); // warnings
        buf.writeShortLE(ServerStatus.SERVER_STATUS_AUTOCOMMIT); // status_flags
        return buf;
    }

    // ==================== Sequence ID Generator ====================

    /**
     * Sequence ID 生成器（线程安全，从 1 开始每次递增）。
     */
    public static class SeqGenerator {
        private int seq;

        public SeqGenerator() {
            this.seq = 1;
        }

        public byte next() {
            return (byte) ((seq++) & 0xFF);
        }

        /** 查看下一个 seq 值（不递增），用于调试日志 */
        public byte peek() {
            return (byte) (seq & 0xFF);
        }
    }
}
