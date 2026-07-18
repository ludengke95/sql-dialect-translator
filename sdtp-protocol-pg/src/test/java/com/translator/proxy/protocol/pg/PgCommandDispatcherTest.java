package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

/**
 * {@link PgCommandDispatcher} 单元测试：系统目录拦截、事务控制、SET 本地应答、简单/扩展查询转发。
 */
public class PgCommandDispatcherTest {

    /** 记录后端处理器调用情况的伪处理器。 */
    private static final class RecordingProcessor implements CommandHandler.QueryProcessor {
        String lastSql;
        List<String> lastParams;
        boolean committed;
        boolean rolledBack;

        @Override
        public void process(ChannelHandlerContext ctx, String sql, FrontendSession s) {
            lastSql = sql;
        }

        @Override
        public void process(ChannelHandlerContext ctx, String sql, List<String> params, FrontendSession s) {
            lastSql = sql;
            lastParams = params;
        }

        @Override
        public void commit(ChannelHandlerContext ctx, FrontendSession s) {
            committed = true;
        }

        @Override
        public void rollback(ChannelHandlerContext ctx, FrontendSession s) {
            rolledBack = true;
        }
    }

    /** 测试夹具：capture 处理器 + dispatcher + 绑定 session。 */
    private static final class Harness {
        EmbeddedChannel ch;
        PgTestSupport.CaptureHandler cap;
        FrontendSession session;
    }

    private Harness newHarness(CommandHandler.QueryProcessor proc) {
        Harness h = new Harness();
        h.ch = new EmbeddedChannel();
        h.cap = new PgTestSupport.CaptureHandler();
        h.ch.pipeline().addFirst("capture", h.cap);
        h.ch.pipeline().addLast("dispatcher", new PgCommandDispatcher(session -> proc));
        h.session = new FrontendSession();
        h.ch.attr(SessionAttribute.SESSION_KEY).set(h.session);
        return h;
    }

    private List<PgTestSupport.Frame> frames(Harness h) {
        return PgTestSupport.drain(h.ch, h.cap);
    }

    @Test
    public void versionQueryIsAnsweredFromCatalogWithoutBackend() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.ch.writeInbound(new PgQueryMessage("SELECT version()"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(4, frames.size());
        assertEquals(PgProtocol.MSG_ROW_DESCRIPTION, frames.get(0).type);
        assertEquals(PgProtocol.MSG_DATA_ROW, frames.get(1).type);
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(2).type);
        assertEquals(PgProtocol.MSG_READY_FOR_QUERY, frames.get(3).type);
        assertEquals("SELECT", PgTestSupport.readCString(frames.get(2).body, 0));
        assertTrue(new String(frames.get(1).body, java.nio.charset.StandardCharsets.UTF_8).contains("PostgreSQL 15.0"));
        // 系统目录不应转发到后端
        assertNull(proc.lastSql);
    }

    @Test
    public void currentSchemaQueryIsAnsweredFromCatalog() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.ch.writeInbound(new PgQueryMessage("SELECT current_schema()"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(4, frames.size());
        assertEquals(PgProtocol.MSG_ROW_DESCRIPTION, frames.get(0).type);
        assertEquals("public", PgTestSupport.readLengthPrefixed(frames.get(1).body, 2));
        assertNull(proc.lastSql);
    }

    @Test
    public void setStatementIsHandledLocally() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.ch.writeInbound(new PgQueryMessage("SET search_path = public"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(2, frames.size());
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(0).type);
        assertEquals("SET", PgTestSupport.readCString(frames.get(0).body, 0));
        assertEquals(PgProtocol.MSG_READY_FOR_QUERY, frames.get(1).type);
        assertNull(proc.lastSql);
    }

    @Test
    public void beginMarksSessionInTransaction() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.ch.writeInbound(new PgQueryMessage("BEGIN"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(0).type);
        assertEquals("BEGIN", PgTestSupport.readCString(frames.get(0).body, 0));
        assertTrue(h.session.isInTransaction());
        assertFalse(proc.committed);
    }

    @Test
    public void commitCallsBackendAndClearsTransaction() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.session.setInTransaction(true);
        h.ch.writeInbound(new PgQueryMessage("COMMIT"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(0).type);
        assertEquals("COMMIT", PgTestSupport.readCString(frames.get(0).body, 0));
        assertTrue(proc.committed);
        assertFalse(h.session.isInTransaction());
    }

    @Test
    public void simpleSelectIsForwardedToBackend() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        // 注意：纯 "SELECT 1" 被系统目录探测视为连通性检查并直接合成应答，这里用非拦截查询
        h.ch.writeInbound(new PgQueryMessage("SELECT 1+1"));

        List<PgTestSupport.Frame> frames = frames(h);
        assertEquals(PgProtocol.MSG_READY_FOR_QUERY, frames.get(frames.size() - 1).type);
        assertEquals("SELECT 1+1", proc.lastSql);
    }

    @Test
    public void extendedQueryParseBindExecuteAnswersCatalog() {
        RecordingProcessor proc = new RecordingProcessor();
        Harness h = newHarness(proc);
        h.ch.writeInbound(new PgParseMessage("stmt1", "SELECT version()", Collections.<Integer>emptyList()));
        h.ch.writeInbound(new PgBindMessage("portal1", "stmt1", Collections.<String>emptyList(), Collections.<Integer>emptyList()));
        h.ch.writeInbound(new PgExecuteMessage("portal1", 0));
        h.ch.writeInbound(new PgSyncMessage());

        List<PgTestSupport.Frame> frames = frames(h);
        // ParseComplete '1', BindComplete '2', RowDescription 'T', DataRow 'D', CommandComplete 'C', ReadyForQuery 'Z'
        assertEquals(6, frames.size());
        assertEquals(PgProtocol.MSG_PARSE_COMPLETE, frames.get(0).type);
        assertEquals(PgProtocol.MSG_BIND_COMPLETE, frames.get(1).type);
        assertEquals(PgProtocol.MSG_ROW_DESCRIPTION, frames.get(2).type);
        assertEquals(PgProtocol.MSG_DATA_ROW, frames.get(3).type);
        assertEquals(PgProtocol.MSG_COMMAND_COMPLETE, frames.get(4).type);
        assertEquals(PgProtocol.MSG_READY_FOR_QUERY, frames.get(5).type);
        assertNull(proc.lastSql);
    }
}
