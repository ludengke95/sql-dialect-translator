package com.translator.proxy.protocol.pg;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * PostgreSQL 前端握手/认证处理器（连接建立后的第一个 Handler）。
 *
 * <p>流程（对照 PostgreSQL v3 协议）：
 * <ol>
 *   <li>收到 {@link PgSslRequest} → 应答原始字节 {@code 'N'}（不支持 SSL，客户端转明文）</li>
 *   <li>收到 {@link PgStartupMessage} → 解析 user/database，发送 AuthenticationMD5Password（含 4 字节 salt）</li>
 *   <li>收到 {@link PgPasswordMessage} → 校验（MD5 或明文），成功则发送
 *       ParameterStatus* + BackendKeyData + ReadyForQuery，并将自身替换为 {@link PgCommandDispatcher}</li>
 * </ol>
 */
public class PgHandshaker extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PgHandshaker.class);

    private static final AtomicLong CONNECTION_ID_GENERATOR = new AtomicLong(1);

    private final String authUser;
    private final String authPassword;
    private final EventExecutorGroup bizExecutorGroup;
    private final BackendRouter router;

    private final byte[] salt = new byte[4];
    private final Random random = new SecureRandom();

    public PgHandshaker(String authUser, String authPassword, EventExecutorGroup bizExecutorGroup, BackendRouter router) {
        this.authUser = authUser;
        this.authPassword = authPassword;
        this.bizExecutorGroup = bizExecutorGroup;
        this.router = router;
        random.nextBytes(salt);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        long connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();
        FrontendSession session = new FrontendSession(ctx.channel(), connectionId);
        session.setSearchPath("public");
        ctx.channel().attr(SessionAttribute.SESSION_KEY).set(session);
        log.info("PG connection active from {} (connectionId={})", ctx.channel().remoteAddress(), connectionId);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof PgSslRequest) {
            // 不支持 SSL，告知客户端降级为明文
            ctx.writeAndFlush(PgOutbound.raw(Unpooled.wrappedBuffer(new byte[] {'N'})));
            return;
        }
        if (msg instanceof PgStartupMessage) {
            handleStartup(ctx, (PgStartupMessage) msg);
            return;
        }
        if (msg instanceof PgPasswordMessage) {
            handlePassword(ctx, (PgPasswordMessage) msg);
            return;
        }
        if (msg instanceof PgTerminateMessage) {
            ctx.close();
            return;
        }
        log.debug("PG handshaker ignoring unexpected message: {}", msg.getClass().getSimpleName());
    }

    private void handleStartup(ChannelHandlerContext ctx, PgStartupMessage startup) {
        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        if (session == null) {
            log.error("No session on channel {}", ctx.channel());
            ctx.close();
            return;
        }
        String user = startup.getUser();
        String database = startup.getDatabase();
        if (database != null && !database.isEmpty()) {
            session.setDatabase(database);
        }
        if (user != null) {
            session.setCharset(StandardCharsets.UTF_8);
        }
        log.info("PG startup: user={}, database={}, app={}", user, database, startup.getApplicationName());
        ctx.writeAndFlush(PgOutbound.framed(PgAuth.buildAuthenticationMd5(ctx.alloc(), salt)));
    }

    private void handlePassword(ChannelHandlerContext ctx, PgPasswordMessage passwordMsg) {
        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        String token = passwordMsg.getToken();
        boolean ok = false;
        if (token != null && token.startsWith(PgAuth.MD5_PREFIX)) {
            ok = PgAuth.verifyMd5(token, authUser, authPassword, salt);
        } else {
            // 明文回退：客户端直接发送密码原文
            ok = authPassword != null && authPassword.equals(token);
        }

        if (!ok) {
            log.warn("PG auth failed for user '{}'", authUser);
            ByteBuf err = buildErrorResponse(ctx.alloc(), "FATAL", "28P01", "password authentication failed for user \"" + authUser + "\"");
            ctx.writeAndFlush(PgOutbound.framed(err)).addListener(future -> ctx.close());
            return;
        }

        log.info("PG auth success for user '{}'", authUser);
        sendParameterStatus(ctx);
        sendBackendKeyData(ctx);
        ctx.writeAndFlush(PgOutbound.framed(buildReadyForQuery(ctx.alloc(), PgProtocol.TX_IDLE)));
        switchToCommandHandler(ctx);
    }

    private void sendParameterStatus(ChannelHandlerContext ctx) {
        String[][] params = {
            {"server_version", "15.0 (SDT Proxy)"},
            {"server_encoding", "UTF8"},
            {"client_encoding", "UTF8"},
            {"DateStyle", "ISO, MDY"},
            {"integer_datetimes", "on"},
            {"standard_conforming_strings", "on"},
            {"TimeZone", "UTC"},
            {"application_name", ""},
        };
        for (String[] p : params) {
            ctx.write(PgOutbound.framed(buildParameterStatus(ctx.alloc(), p[0], p[1])));
        }
    }

    private void sendBackendKeyData(ChannelHandlerContext ctx) {
        ByteBuf b = ctx.alloc().buffer(12);
        b.writeByte(PgProtocol.MSG_BACKEND_KEY_DATA);
        b.writeInt((int) (CONNECTION_ID_GENERATOR.get() & 0x7FFFFFFF));
        b.writeInt(random.nextInt() & 0x7FFFFFFF);
        ctx.write(PgOutbound.framed(b));
    }

    private void switchToCommandHandler(ChannelHandlerContext ctx) {
        if (bizExecutorGroup != null) {
            ctx.pipeline().addAfter(bizExecutorGroup, "handshakeHandler", "commandHandler", new PgCommandDispatcher(router));
            ctx.pipeline().remove(this);
        } else {
            ctx.pipeline().replace(this, "commandHandler", new PgCommandDispatcher(router));
        }
    }

    // ==================== 消息构造 ====================

    private static ByteBuf buildParameterStatus(ByteBufAllocator alloc, String name, String value) {
        ByteBuf b = alloc.buffer(16 + name.length() + value.length());
        b.writeByte(PgProtocol.MSG_PARAMETER_STATUS);
        writeCString(b, name);
        writeCString(b, value);
        return b;
    }

    private static ByteBuf buildReadyForQuery(ByteBufAllocator alloc, byte txStatus) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_READY_FOR_QUERY);
        b.writeByte(txStatus);
        return b;
    }

    private static ByteBuf buildErrorResponse(ByteBufAllocator alloc, String severity, String sqlState, String message) {
        ByteBuf b = alloc.buffer(64 + message.length());
        b.writeByte(PgProtocol.MSG_ERROR_RESPONSE);
        writeField(b, 'S', severity);
        writeField(b, 'V', severity);
        writeField(b, 'C', sqlState);
        writeField(b, 'M', message);
        b.writeByte(0);
        return b;
    }

    private static void writeCString(ByteBuf b, String s) {
        if (s == null) {
            s = "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.writeBytes(bytes);
        b.writeByte(0);
    }

    private static void writeField(ByteBuf b, char fieldType, String value) {
        b.writeByte((byte) fieldType);
        writeCString(b, value);
    }
}
