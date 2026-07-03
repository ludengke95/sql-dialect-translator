package com.translator.proxy.core.handler;

import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CapabilityFlags;
import com.translator.proxy.protocol.util.BufferUtils;
import com.translator.proxy.protocol.util.MySQLAuth;
import com.translator.proxy.core.session.FrontendSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 认证处理器 —— 处理客户端发来的 HandshakeResponse41。
 *
 * <p>流程：
 * <ol>
 *   <li>收到客户端发来的 auth 响应包</li>
 *   <li>解析能力标志、用户名、加密密码等</li>
 *   <li>用存储的密码和 scramble 验证客户端 token</li>
 *   <li>验证通过 → 发送 OK 包，加入 CommandHandler</li>
 *   <li>验证失败 → 发送 ERR 包，关闭连接</li>
 * </ol>
 */
public class AuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    /** 暂存的账密——实际项目中从配置读取 */
    private final String expectedUser;
    private final String expectedPassword;

    /**
     * 使用默认账密（后续替换为配置注入）。
     */
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
                writeErrorAndClose(ctx, 1045, "HY000", "Internal error: no session");
                return;
            }

            // 解析 HandshakeResponse41
            HandshakeResponse resp = parseHandshakeResponse41(payload);

            log.info("Auth request from {}: user={}, db={}",
                    ctx.channel().remoteAddress(), resp.username, resp.database);

            // 验证账密
            if (!expectedUser.equals(resp.username)) {
                log.warn("Auth failed: unknown user '{}'", resp.username);
                writeErrorAndClose(ctx, 1045, "28000",
                        "Access denied for user '" + resp.username + "'");
                return;
            }

            if (!MySQLAuth.verify(expectedPassword, session.getScramble(), resp.authResponse)) {
                log.warn("Auth failed: wrong password for user '{}'", resp.username);
                writeErrorAndClose(ctx, 1045, "28000",
                        "Access denied for user '" + resp.username + "' (using password: YES)");
                return;
            }

            // 记录客户端选择的 database（如果有）
            if (resp.database != null && !resp.database.isEmpty()) {
                session.setDatabase(resp.database);
            }

            // 认证成功 → 发送 OK 包
            log.info("Auth success for user '{}' from {}", resp.username, ctx.channel().remoteAddress());
            writeOk(ctx);

            // Pipeline 切换：移除自身，加入 CommandHandler
            ctx.pipeline().replace(this, "commandHandler",
                    new CommandHandler());

        } catch (Exception e) {
            log.error("Error processing auth", e);
            writeErrorAndClose(ctx, 1047, "HY000", "Unknown error during authentication");
        } finally {
            raw.release();
        }
    }

    /**
     * 发送 OK 包（认证成功响应）。
     */
    static void writeOk(ChannelHandlerContext ctx) {
        ByteBuf ok = buildOkPacket(ctx.alloc(), 0, 0, 0, 0, "");
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(ok, (byte) 2));
    }

    /**
     * 发送 ERR 包并关闭连接。
     */
    private void writeErrorAndClose(ChannelHandlerContext ctx, int errorCode,
                                     String sqlState, String message) {
        ByteBuf err = buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 2))
                .addListener(future -> ctx.close());
    }

    // ==================== Packet Builders ====================

    /**
     * 构造 MySQL OK_Packet。
     */
    public static ByteBuf buildOkPacket(ByteBufAllocator alloc,
                                         long affectedRows, long lastInsertId,
                                         int statusFlags, int warnings,
                                         String info) {
        ByteBuf buf = alloc.buffer(32);
        buf.writeByte(0x00); // header = OK (0x00) or OK_Packet (0xFE)
        BufferUtils.writeLengthEncodedInt(buf, affectedRows);
        BufferUtils.writeLengthEncodedInt(buf, lastInsertId);
        buf.writeShortLE(statusFlags);
        buf.writeShortLE(warnings);
        if (info != null && !info.isEmpty()) {
            BufferUtils.writeLengthEncodedString(buf, info);
        }
        return buf;
    }

    /**
     * 构造 MySQL ERR_Packet。
     */
    public static ByteBuf buildErrPacket(ByteBufAllocator alloc,
                                          int errorCode, String sqlState,
                                          String message) {
        ByteBuf buf = alloc.buffer(64);
        buf.writeByte(0xFF); // header = ERR
        buf.writeShortLE(errorCode);

        // SQL state marker '#'
        buf.writeByte('#');

        // SQL state (5 bytes)
        if (sqlState != null && sqlState.length() == 5) {
            buf.writeBytes(sqlState.getBytes(StandardCharsets.UTF_8));
        } else {
            buf.writeBytes("HY000".getBytes(StandardCharsets.UTF_8));
        }

        // Error message
        if (message != null) {
            buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        }

        return buf;
    }

    // ==================== HandshakeResponse41 解析 ====================

    /**
     * 解析客户端发来的 HandshakeResponse41。
     */
    static HandshakeResponse parseHandshakeResponse41(ByteBuf payload) {
        HandshakeResponse resp = new HandshakeResponse();

        // 1. capability flags (4 bytes)
        resp.capabilityFlags = (int) payload.readUnsignedIntLE();

        // 2. max packet size (4 bytes)
        resp.maxPacketSize = payload.readIntLE();

        // 3. character set (1 byte)
        resp.characterSet = payload.readUnsignedByte();

        // 4. reserved (23 bytes of 0x00)
        payload.skipBytes(23);

        // 5. username (null-terminated)
        resp.username = BufferUtils.readNullTerminatedString(payload);

        // 6. auth-response (length-encoded if CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA,
        //    else if CLIENT_SECURE_CONNECTION: 1-byte length + bytes)
        boolean useSecureConnection = (resp.capabilityFlags & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0;
        boolean usePluginAuthLenenc = (resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0;

        if (usePluginAuthLenenc) {
            // Length-Encoded Integer 方式读取（auth response 是二进制字节，不是字符串！）
            long len = BufferUtils.readLengthEncodedInt(payload);
            if (len > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Auth response too long: " + len);
            }
            resp.authResponse = new byte[(int) len];
            payload.readBytes(resp.authResponse);
        } else if (useSecureConnection) {
            // 1 字节长度 + N 字节
            int len = payload.readUnsignedByte();
            resp.authResponse = new byte[len];
            payload.readBytes(resp.authResponse);
        } else {
            // 老版本：null-terminated
            resp.authResponse = BufferUtils.readNullTerminatedString(payload).getBytes(StandardCharsets.UTF_8);
        }

        // 7. database (null-terminated, if CLIENT_CONNECT_WITH_DB)
        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            resp.database = BufferUtils.readNullTerminatedString(payload);
        }

        // 8. auth-plugin-name (null-terminated, if CLIENT_PLUGIN_AUTH)
        if ((resp.capabilityFlags & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0) {
            resp.authPluginName = BufferUtils.readNullTerminatedString(payload);
        }

        return resp;
    }

    /**
     * 客户端握手响应数据结构。
     */
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
