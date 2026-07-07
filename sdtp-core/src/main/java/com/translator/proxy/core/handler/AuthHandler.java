package com.translator.proxy.core.handler;

import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CapabilityFlags;
import com.translator.proxy.protocol.util.BufferUtils;
import com.translator.proxy.protocol.util.MySQLAuth;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.metrics.ConnectionMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 认证处理器 —— 处理客户端发来的 HandshakeResponse41 和 AuthSwitchResponse。
 *
 * <p>流程：
 * <ol>
 *   <li>服务端声明 mysql_native_password（HandshakeV10）</li>
 *   <li>客户端请求 mysql_native_password → 直接 SHA-1 验证 → OK</li>
 *   <li>客户端请求 caching_sha2_password → AuthSwitch 到 mysql_native_password → 验证 → OK</li>
 * </ol>
 */
public class AuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    private final String expectedUser;
    private final String expectedPassword;

    /** 是否在等待 AuthSwitchResponse（caching_sha2_password 第二步） */
    private boolean expectingAuthSwitchResponse;

    /** AuthSwitch 第二阶段使用的 scramble */
    private byte[] authSwitchScramble;

    /** HandshakeResponse41 中携带的 database 名（AuthSwitch 成功后设置） */
    private String pendingDatabase;

    public AuthHandler() {
        this("root", "proxy_password");
    }

    public AuthHandler(String expectedUser, String expectedPassword) {
        this.expectedUser = expectedUser;
        this.expectedPassword = expectedPassword;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MySQLPacketDecoder.RawMySQLPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        MySQLPacketDecoder.RawMySQLPacket raw = (MySQLPacketDecoder.RawMySQLPacket) msg;
        ByteBuf payload = raw.getPayload();

        try {
            FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
            if (session == null) {
                log.error("No session found for channel {}", ctx.channel());
                writeErrorAndClose(ctx, 1045, "HY000", "Internal error: no session", (byte) 2);
                return;
            }

            if (expectingAuthSwitchResponse) {
                handleAuthSwitchResponse(ctx, session, payload);
            } else {
                handleHandshakeResponse(ctx, session, payload);
            }
        } catch (Exception e) {
            log.error("Error processing auth", e);
            writeErrorAndClose(ctx, 1047, "HY000", "Unknown error during authentication",
                    expectingAuthSwitchResponse ? (byte) 4 : (byte) 2);
        } finally {
            raw.release();
        }
    }

    // ==================== HandshakeResponse41 ====================

    private void handleHandshakeResponse(ChannelHandlerContext ctx, FrontendSession session,
                                          ByteBuf payload) {
        // Hex dump 整个 payload（调试偏移问题）
        int savedReaderIndex = payload.readerIndex();
        byte[] rawBytes = new byte[payload.readableBytes()];
        payload.getBytes(savedReaderIndex, rawBytes);
        log.debug("HandshakeResponse41 raw ({} bytes): {}", rawBytes.length,
                MySQLAuth.bytesToHex(rawBytes));

        HandshakeResponse resp = parseHandshakeResponse41(payload);

        log.info("Auth request from {}: user={}, db={}, plugin={} capFlags=0x{}",
                ctx.channel().remoteAddress(), resp.username, resp.database,
                resp.authPluginName != null ? resp.authPluginName : "(none)",
                Integer.toHexString(resp.capabilityFlags));

        // 验证用户名
        if (!expectedUser.equals(resp.username)) {
            log.warn("Auth failed: unknown user '{}'", resp.username);
            ConnectionMetrics.onAuthFailure("wrong_user");
            writeErrorAndClose(ctx, 1045, "28000",
                    "Access denied for user '" + resp.username + "'", (byte) 2);
            return;
        }

        String plugin = resp.authPluginName;

        if ("caching_sha2_password".equals(plugin)) {
            // ===== MySQL 8.0 客户端：先尝试 SHA-256 快速认证 =====
            // 如果 auth_response 为 0 字节，说明客户端缓存了空密码 → 需要 AuthSwitch
            if (resp.authResponse.length > 0) {
                // fast auth: 客户端用 HandshakeV10 的 scramble 计算 SHA-256 token
                if (MySQLAuth.verifySha256(expectedPassword, session.getScramble(), resp.authResponse)) {
                    log.info("Auth success (sha256 fast) for user '{}'", resp.username);
                    applyDatabase(session, resp.database);
                    writeAuthMoreDataFastSuccess(ctx, (byte) 2);
                    writeOk(ctx, (byte) 3);
                    switchToCommandHandler(ctx);
                    return;
                }
                // SHA-256 快速认证失败，回退到 AuthSwitch
                log.info("SHA-256 fast auth failed, falling back to AuthSwitch");
            } else {
                log.info("Client sent empty auth_response for caching_sha2_password, starting AuthSwitch");
            }

            // 发起 AuthSwitch 切换为 mysql_native_password
            pendingDatabase = resp.database;
            authSwitchScramble = MySQLAuth.generateScramble();
            writeAuthSwitchRequest(ctx, authSwitchScramble, "mysql_native_password");
            expectingAuthSwitchResponse = true;

        } else if ("mysql_native_password".equals(plugin) || plugin == null) {
            // ===== MySQL 5.7 或旧版客户端：SHA-1 认证 =====
            // 尝试直接验证（用 HandshakeV10 的 scramble 和 SHA-1 算法）
            if (!MySQLAuth.verify(expectedPassword, session.getScramble(), resp.authResponse)) {
                log.warn("Auth failed (native): wrong password for user '{}'", resp.username);
                ConnectionMetrics.onAuthFailure("wrong_password");
                log.debug("Password: '{}'", expectedPassword);
                log.debug("Scramble: {}", MySQLAuth.bytesToHex(session.getScramble()));
                log.debug("Expected: {}", MySQLAuth.bytesToHex(
                        MySQLAuth.scramble411(expectedPassword, session.getScramble())));
                log.debug("Client:   {}", MySQLAuth.bytesToHex(resp.authResponse));
                writeErrorAndClose(ctx, 1045, "28000",
                        "Access denied for user '" + resp.username + "' (using password: YES)", (byte) 2);
                return;
            }
            log.info("Auth success (native) for user '{}'", resp.username);
            ConnectionMetrics.onAuthSuccess("native");
            applyDatabase(session, resp.database);
            writeOk(ctx, (byte) 2);
            switchToCommandHandler(ctx);

        } else {
            log.warn("Auth failed: unsupported plugin '{}' from user '{}'", plugin, resp.username);
            ConnectionMetrics.onAuthFailure("unsupported_plugin");
            writeErrorAndClose(ctx, 1045, "28000",
                    "Authentication plugin '" + plugin + "' is not supported.", (byte) 2);
        }
    }

    private void applyDatabase(FrontendSession session, String database) {
        if (database != null && !database.isEmpty()) {
            session.setDatabase(database);
        }
    }

    // ==================== AuthSwitchResponse ====================

    private void handleAuthSwitchResponse(ChannelHandlerContext ctx, FrontendSession session,
                                           ByteBuf payload) {
        expectingAuthSwitchResponse = false;

        // AuthSwitchResponse 的 payload 就是 raw auth token 字节
        byte[] clientToken = new byte[payload.readableBytes()];
        payload.readBytes(clientToken);

        log.info("AuthSwitchResponse received ({} bytes)", clientToken.length);
        log.debug("AuthSwitch scramble: {}", MySQLAuth.bytesToHex(authSwitchScramble));

        // 使用 SHA-1 验证（mysql_native_password）
        if (!MySQLAuth.verify(expectedPassword, authSwitchScramble, clientToken)) {
            log.warn("Auth failed (native auth switch): wrong password");
            log.debug("Expected: {}", MySQLAuth.bytesToHex(
                    MySQLAuth.scramble411(expectedPassword, authSwitchScramble)));
            log.debug("Client:   {}", MySQLAuth.bytesToHex(clientToken));
            writeErrorAndClose(ctx, 1045, "28000",
                    "Access denied for user '" + expectedUser + "' (using password: YES)", (byte) 4);
            return;
        }

        log.info("Auth success (sha256) for user '{}'", expectedUser);
        ConnectionMetrics.onAuthSuccess("sha256");
        applyDatabase(session, pendingDatabase);
        pendingDatabase = null;
        authSwitchScramble = null;

        // AuthSwitch 成功 → OK
        writeOk(ctx, (byte) 4);
        switchToCommandHandler(ctx);
    }

    private void switchToCommandHandler(ChannelHandlerContext ctx) {
        ctx.pipeline().replace(this, "commandHandler", new CommandHandler());
    }

    // ==================== Packet Writers ====================

    /**
     * 发送 AuthSwitchRequest 包。
     * <p>格式：header=0xFE + auth_plugin_name(NUL) + auth_plugin_data(20字节scramble + NUL)
     *
     * @param ctx Netty 上下文
     * @param scramble 20 字节的认证挑战码
     * @param pluginName 目标认证插件名称
     */
    static void writeAuthSwitchRequest(ChannelHandlerContext ctx, byte[] scramble, String pluginName) {
        ByteBuf buf = ctx.alloc().buffer(64);
        buf.writeByte(0xFE);  // EOF header (same as AuthSwitchRequest)
        BufferUtils.writeNullTerminatedString(buf, pluginName);
        buf.writeBytes(scramble);  // 20 bytes scramble
        buf.writeByte(0x00);       // NUL terminator
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(buf, (byte) 2));
    }

    static void writeOk(ChannelHandlerContext ctx, byte seq) {
        ByteBuf ok = buildOkPacket(ctx.alloc(), 0, 0, 0, 0, "");
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, seq));
    }

    static void writeAuthMoreDataFastSuccess(ChannelHandlerContext ctx, byte seq) {
        ByteBuf buf = ctx.alloc().buffer(2);
        buf.writeByte(0x01);  // AuthMoreData header
        buf.writeByte(0x03);  // fast auth success
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(buf, seq));
    }

    private void writeErrorAndClose(ChannelHandlerContext ctx, int errorCode,
                                     String sqlState, String message, byte seq) {
        ByteBuf err = buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, seq))
                .addListener(future -> ctx.close());
    }

    // ==================== Packet Builders ====================

    public static ByteBuf buildOkPacket(ByteBufAllocator alloc,
                                         long affectedRows, long lastInsertId,
                                         int statusFlags, int warnings,
                                         String info) {
        ByteBuf buf = alloc.buffer(32);
        buf.writeByte(0x00);
        BufferUtils.writeLengthEncodedInt(buf, affectedRows);
        BufferUtils.writeLengthEncodedInt(buf, lastInsertId);
        buf.writeShortLE(statusFlags);
        buf.writeShortLE(warnings);
        if (info != null && !info.isEmpty()) {
            BufferUtils.writeLengthEncodedString(buf, info);
        }
        return buf;
    }

    public static ByteBuf buildErrPacket(ByteBufAllocator alloc,
                                          int errorCode, String sqlState,
                                          String message) {
        ByteBuf buf = alloc.buffer(64);
        buf.writeByte(0xFF);
        buf.writeShortLE(errorCode);
        buf.writeByte('#');
        if (sqlState != null && sqlState.length() == 5) {
            buf.writeBytes(sqlState.getBytes(StandardCharsets.UTF_8));
        } else {
            buf.writeBytes("HY000".getBytes(StandardCharsets.UTF_8));
        }
        if (message != null) {
            buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        }
        return buf;
    }

    // ==================== HandshakeResponse41 解析 ====================

    static HandshakeResponse parseHandshakeResponse41(ByteBuf payload) {
        HandshakeResponse resp = new HandshakeResponse();

        resp.capabilityFlags = (int) payload.readUnsignedIntLE();
        resp.maxPacketSize = payload.readIntLE();
        resp.characterSet = payload.readUnsignedByte();
        payload.skipBytes(23);
        resp.username = BufferUtils.readNullTerminatedString(payload);

        boolean usePluginAuthLenenc =
                (resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0;
        boolean useSecureConnection =
                (resp.capabilityFlags & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0;

        if (usePluginAuthLenenc) {
            long len = BufferUtils.readLengthEncodedInt(payload);
            if (len > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Auth response too long: " + len);
            }
            resp.authResponse = new byte[(int) len];
            payload.readBytes(resp.authResponse);
        } else if (useSecureConnection) {
            int len = payload.readUnsignedByte();
            resp.authResponse = new byte[len];
            payload.readBytes(resp.authResponse);
        } else {
            resp.authResponse = BufferUtils.readNullTerminatedString(payload)
                    .getBytes(StandardCharsets.UTF_8);
        }

        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            resp.database = BufferUtils.readNullTerminatedString(payload);
        }
        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0) {
            resp.authPluginName = BufferUtils.readNullTerminatedString(payload);
        }

        return resp;
    }

    static class HandshakeResponse {
        int capabilityFlags;
        int maxPacketSize;
        int characterSet;
        String username;
        byte[] authResponse;
        String database;
        String authPluginName;
    }
}
