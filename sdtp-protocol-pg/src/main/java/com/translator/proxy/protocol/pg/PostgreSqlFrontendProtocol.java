package com.translator.proxy.protocol.pg;

import com.translator.proxy.core.frontend.FrontendProtocol;
import com.translator.proxy.core.handler.BackendRouter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * PostgreSQL 前端协议实现（{@link FrontendProtocol}）。
 *
 * <p>使 SDT Proxy 伪装成 PostgreSQL 服务端：PG 线协议编解码、MD5 认证、命令分发，
 * 后端执行与 SQL 翻译复用既有 {@code sdtp-backend} / {@code sdt-core} 层。
 *
 * <p>与 MySQL 前端互不干扰：{@code ProxyBootstrap} 仅在 {@code frontendProtocol=POSTGRESQL}
 * 时启用本实现，其余情况走原 MySQL pipeline（零回归）。
 */
public class PostgreSqlFrontendProtocol implements FrontendProtocol {

    @Override
    public String id() {
        return "POSTGRESQL";
    }

    @Override
    public String getSourceDialect() {
        return "POSTGRESQL";
    }

    @Override
    public ChannelHandler newDecoder(long maxMessageSize) {
        return new PgMessageDecoder();
    }

    @Override
    public MessageToByteEncoder<?> newEncoder() {
        return new PgMessageEncoder();
    }

    @Override
    public ChannelHandler newHandshakeHandler(String user, String password, EventExecutorGroup biz, BackendRouter router) {
        return new PgHandshaker(user, password, biz, router);
    }

    @Override
    public int defaultPort() {
        return PgProtocol.DEFAULT_PORT;
    }
}
