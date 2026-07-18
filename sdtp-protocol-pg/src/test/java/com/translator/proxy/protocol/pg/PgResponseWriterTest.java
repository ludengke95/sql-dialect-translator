package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import com.translator.proxy.backend.mapper.ResultSetEncoder.SeqGenerator;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

/**
 * {@link PgResponseWriter} 单元测试：将 JDBC 结果集 / 空结果 / 错误编码为 PG 响应帧。
 */
public class PgResponseWriterTest {

    /** 构造带 capture 处理器的 EmbeddedChannel，并通过 out 返回可用于 writer 的 ctx。 */
    private EmbeddedChannel newChannel(PgTestSupport.CaptureHandler[] outCap, ChannelHandlerContext[] outCtx) {
        EmbeddedChannel ch = new EmbeddedChannel();
        PgTestSupport.CaptureHandler cap = new PgTestSupport.CaptureHandler();
        // capture 必须位于 ctx 源（trigger）与 head 之间，才能拦截其出站消息
        ch.pipeline().addFirst("capture", cap);
        ch.pipeline().addLast("trigger", new ChannelInboundHandlerAdapter());
        outCap[0] = cap;
        outCtx[0] = ch.pipeline().context("trigger");
        return ch;
    }

    @Test
    public void encodesResultSetAsRowDescriptionDataRowsCommandComplete() throws Exception {
        String[] cols = {"id", "name"};
        int[] types = {Types.INTEGER, Types.VARCHAR};
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("1", "alice"),
                Arrays.asList("2", "bob"));
        java.sql.ResultSet rs = PgTestSupport.fakeResultSet(cols, types, rows);

        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        ChannelHandlerContext[] ctx = new ChannelHandlerContext[1];
        EmbeddedChannel ch = newChannel(cap, ctx);

        PgResponseWriter writer = new PgResponseWriter();
        writer.encodeAndWrite(ctx[0], rs, new SeqGenerator(), "mysql");

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(4, frames.size());
        assertEquals(PgProtocol.MSG_ROW_DESCRIPTION, frames.get(0).type);
        assertEquals(PgProtocol.MSG_DATA_ROW, frames.get(1).type);
        assertEquals(PgProtocol.MSG_DATA_ROW, frames.get(2).type);
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(3).type);

        // RowDescription 的列数 + 首列标签
        assertEquals(2, PgTestSupport.readShort(frames.get(0).body, 0));
        assertEquals("id", PgTestSupport.readCString(frames.get(0).body, 2));

        // 两个 DataRow 的内容（body = [short colCount][int len][bytes]...）
        // 第 1 列长度前缀在 offset 2（值 "1"/"2"），第 2 列长度前缀在 2+4+1 = 7
        assertEquals("1", PgTestSupport.readLengthPrefixed(frames.get(1).body, 2));
        assertEquals("alice", PgTestSupport.readLengthPrefixed(frames.get(1).body, 7));
        assertEquals("2", PgTestSupport.readLengthPrefixed(frames.get(2).body, 2));
        assertEquals("bob", PgTestSupport.readLengthPrefixed(frames.get(2).body, 7));

        // CommandComplete 标签为 SELECT（结果集默认）
        assertEquals("SELECT", PgTestSupport.readCString(frames.get(3).body, 0));
    }

    @Test
    public void encodesEmptyResultSetWithInsertTag() throws Exception {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        ChannelHandlerContext[] ctx = new ChannelHandlerContext[1];
        EmbeddedChannel ch = newChannel(cap, ctx);
        ch.attr(PgProtocol.PG_COMMAND_TAG).set("INSERT");

        PgResponseWriter writer = new PgResponseWriter();
        writer.encodeEmpty(ctx[0], 5, 0);

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(1, frames.size());
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(0).type);
        assertEquals("INSERT 0 5", PgTestSupport.readCString(frames.get(0).body, 0));
    }

    @Test
    public void encodeEmptyDefaultsToInsertForNullVerb() throws Exception {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        ChannelHandlerContext[] ctx = new ChannelHandlerContext[1];
        EmbeddedChannel ch = newChannel(cap, ctx);

        PgResponseWriter writer = new PgResponseWriter();
        writer.encodeEmpty(ctx[0], 0, 0);

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(1, frames.size());
        assertEquals("INSERT 0 0", PgTestSupport.readCString(frames.get(0).body, 0));
    }

    @Test
    public void writeErrorEncodesErrorResponseFromSQLException() throws Exception {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        ChannelHandlerContext[] ctx = new ChannelHandlerContext[1];
        EmbeddedChannel ch = newChannel(cap, ctx);

        PgResponseWriter writer = new PgResponseWriter();
        SQLException e = new SQLException("relation not found", "42P01", 1234);
        writer.writeError(ctx[0], e);

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(1, frames.size());
        assertEquals(PgProtocol.MSG_ERROR_RESPONSE, frames.get(0).type);
        String body = new String(frames.get(0).body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("42P01"));
        assertTrue(body.contains("relation not found"));
    }

    @Test
    public void writeErrorEncodesErrorResponseFromCode() throws Exception {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        ChannelHandlerContext[] ctx = new ChannelHandlerContext[1];
        EmbeddedChannel ch = newChannel(cap, ctx);

        PgResponseWriter writer = new PgResponseWriter();
        writer.writeError(ctx[0], 1105, "HY000", "boom");

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(1, frames.size());
        assertEquals(PgProtocol.MSG_ERROR_RESPONSE, frames.get(0).type);
        String body = new String(frames.get(0).body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("HY000"));
        assertTrue(body.contains("boom"));
    }
}
