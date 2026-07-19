package com.translator.proxy.protocol.pg;

import com.translator.proxy.protocol.frontend.AuthConfig;
import com.translator.proxy.protocol.frontend.FrontendProtocol;
import com.translator.proxy.protocol.frontend.ResponseWriter;
import com.translator.proxy.protocol.frontend.SystemCatalogProvider;
import com.translator.proxy.protocol.frontend.TypeMapper;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.ByteToMessageDecoder;
import com.translator.proxy.protocol.pg.auth.PgHandshakeHandler;
import com.translator.proxy.protocol.pg.catalog.PgSystemCatalogProvider;
import com.translator.proxy.protocol.pg.codec.PgPacketDecoder;
import com.translator.proxy.protocol.pg.codec.PgPacketEncoder;
import com.translator.proxy.protocol.pg.command.PgCommandHandler;
import com.translator.proxy.protocol.pg.result.PgResponseWriter;
import com.translator.proxy.protocol.pg.result.PgTypeMapper;

/**
 * PostgreSQL 前端协议的 SPI 实现。
 *
 * <p>通过 Java {@link java.util.ServiceLoader} 注册，ID 为 "POSTGRESQL"。
 * 工厂方法创建所有 PG 协议相关的 Handler、编解码器、响应写入器等。
 */
public class PostgreSQLFrontendProtocol implements FrontendProtocol {

    @Override
    public String id() {
        return "POSTGRESQL";
    }

    @Override
    public String getSourceDialect() {
        return "POSTGRESQL";
    }

    @Override
    public ByteToMessageDecoder newDecoder(long maxPacketSize) {
        return new PgPacketDecoder(maxPacketSize);
    }

    @Override
    public ChannelHandler newEncoder() {
        return new PgPacketEncoder();
    }

    @Override
    public ChannelHandler newHandshakeHandler(AuthConfig authConfig, EventLoopGroup executor) {
        return new PgHandshakeHandler(authConfig, executor);
    }

    @Override
    public ChannelHandler newCommandHandler() {
        return new com.translator.proxy.protocol.pg.command.PgCommandHandler();
    }

    @Override
    public ResponseWriter newResponseWriter() {
        return new PgResponseWriter();
    }

    @Override
    public TypeMapper newTypeMapper() {
        return new PgTypeMapper();
    }

    @Override
    public SystemCatalogProvider newSystemCatalog() {
        return new PgSystemCatalogProvider();
    }

    @Override
    public int defaultPort() {
        return 5432;
    }
}
