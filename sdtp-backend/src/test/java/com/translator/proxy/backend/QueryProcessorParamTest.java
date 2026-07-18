package com.translator.proxy.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

/**
 * 参数化执行路由测试：验证 QueryProcessor 的参数化 {@code process} 路径，
 * 以及 ReloadableQueryProcessor 在 ACTIVE 状态下把 params 透传给 delegate。
 */
public class QueryProcessorParamTest {

    /** 记录最后一次调用的参数化处理器 */
    static class RecordingQueryProcessor implements CommandHandler.QueryProcessor {
        String lastSql;
        List<String> lastParams;
        boolean paramCall;

        @Override
        public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
            this.lastSql = sql;
            this.lastParams = Collections.emptyList();
            this.paramCall = false;
        }

        @Override
        public void process(ChannelHandlerContext ctx, String sql, List<String> params, FrontendSession session) {
            this.lastSql = sql;
            this.lastParams = params;
            this.paramCall = true;
        }

        @Override
        public void commit(ChannelHandlerContext ctx, FrontendSession session) {}

        @Override
        public void rollback(ChannelHandlerContext ctx, FrontendSession session) {}

        @Override
        public void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {}

        @Override
        public void close() {}
    }

    private static ChannelHandlerContext newCtx() {
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        return ch.pipeline().firstContext();
    }

    @Test
    public void reloadableActiveRoutesParamsToDelegate() {
        RecordingQueryProcessor delegate = new RecordingQueryProcessor();
        ReloadableQueryProcessor rp = new ReloadableQueryProcessor("be", delegate, 16, 1000);
        ChannelHandlerContext ctx = newCtx();
        FrontendSession session = new FrontendSession();
        List<String> params = Arrays.asList("42", "hello");
        rp.process(ctx, "SELECT $1, $2", params, session);
        assertTrue(delegate.paramCall);
        assertEquals("SELECT $1, $2", delegate.lastSql);
        assertEquals(params, delegate.lastParams);
    }

    @Test
    public void reloadableActiveRoutesEmptyParamsViaSimpleOverload() {
        RecordingQueryProcessor delegate = new RecordingQueryProcessor();
        ReloadableQueryProcessor rp = new ReloadableQueryProcessor("be", delegate, 16, 1000);
        ChannelHandlerContext ctx = newCtx();
        FrontendSession session = new FrontendSession();
        rp.process(ctx, "SELECT 1", session);
        assertEquals(false, delegate.paramCall);
        assertEquals("SELECT 1", delegate.lastSql);
        assertNotNull(delegate.lastParams);
        assertTrue(delegate.lastParams.isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void defaultParamMethodThrowsWhenNotOverridden() {
        // 仅覆盖 3 参的简单 process，未覆盖 4 参 → 应抛 UnsupportedOperationException（SPI 默认行为）
        CommandHandler.QueryProcessor onlySimple = new CommandHandler.QueryProcessor() {
            @Override
            public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {}

            @Override
            public void commit(ChannelHandlerContext ctx, FrontendSession session) {}

            @Override
            public void rollback(ChannelHandlerContext ctx, FrontendSession session) {}

            @Override
            public void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {}

            @Override
            public void close() {}
        };
        onlySimple.process(newCtx(), "SELECT 1", Collections.singletonList("x"), new FrontendSession());
    }
}
