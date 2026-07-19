package com.translator.proxy.protocol.mysql;

import com.translator.proxy.protocol.frontend.AuthConfig;
import com.translator.proxy.protocol.frontend.FrontendProtocol;
import com.translator.proxy.protocol.frontend.ResponseWriter;
import com.translator.proxy.protocol.frontend.SystemCatalogProvider;
import com.translator.proxy.protocol.frontend.TypeMapper;
import com.translator.proxy.protocol.mysql.auth.MySQLHandshakeHandler;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.mysql.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.mysql.command.MySQLCommandHandler;
import com.translator.proxy.protocol.mysql.catalog.MySQLSystemCatalogProvider;
import com.translator.proxy.protocol.mysql.result.MySQLResponseWriter;
import com.translator.proxy.protocol.mysql.result.MySQLTypeMapper;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * MySQL 前端协议的 SPI 实现。
 *
 * <p>通过 Java {@link java.util.ServiceLoader} 注册，ID 为 "MYSQL"。
 * 工厂方法创建所有 MySQL 协议相关的 Handler、编解码器、响应写入器等。
 */
public class MySQLFrontendProtocol implements FrontendProtocol {

    @Override
    public String id() {
        return "MYSQL";
    }

    @Override
    public String getSourceDialect() {
        return "MYSQL";
    }

    @Override
    public ByteToMessageDecoder newDecoder(long maxPacketSize) {
        return new MySQLPacketDecoder((int) maxPacketSize);
    }

    @Override
    public ChannelHandler newEncoder() {
        return new MySQLPacketEncoder();
    }

    @Override
    public ChannelHandler newHandshakeHandler(AuthConfig authConfig, EventLoopGroup executor) {
        return new MySQLHandshakeHandler(authConfig.getUsername(), authConfig.getPassword(), executor);
    }

    @Override
    public ChannelHandler newCommandHandler() {
        return new MySQLCommandHandler();
    }

    @Override
    public ResponseWriter newResponseWriter() {
        return new MySQLResponseWriter();
    }

    @Override
    public TypeMapper newTypeMapper() {
        return new MySQLTypeMapper();
    }

    @Override
    public SystemCatalogProvider newSystemCatalog() {
        return new MySQLSystemCatalogProvider();
    }

    @Override
    public int defaultPort() {
        return 3306;
    }
}
