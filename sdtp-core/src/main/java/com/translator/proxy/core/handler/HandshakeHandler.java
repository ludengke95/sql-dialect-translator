package com.translator.proxy.core.handler;

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

import java.util.concurrent.atomic.AtomicLong;

/**
 * 握手处理器 —— MySQL 连接建立后的第一个 Handler。
 *
 * <p>流程：
 * <ol>
 *   <li>生成 20 字节 scramble（认证挑战码）</li>
 *   <li>创建 FrontendSession 并绑定到 channel</li>
 *   <li>构造 HandshakeV10 包发送给客户端</li>
 *   <li>将自己从 pipeline 中移除，加入 AuthHandler</li>
 * </ol>
 */
public class HandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);

    /** 伪装的服务端版本 */
    private static final String SERVER_VERSION = "5.7.38-proxy";

    /** 默认字符集 utf8mb4_general_ci → collation id 33 */
    private static final int CHARSET_UTF8MB4 = 33;

    private static final AtomicLong CONNECTION_ID_GENERATOR = new AtomicLong(1);

    private final String authUser;
    private final String authPassword;

    /**
     * 使用默认账密。
     */
    public HandshakeHandler() {
        this("root", "proxy_password");
    }

    /**
     * 使用自定义账密。
     */
    public HandshakeHandler(String authUser, String authPassword) {
        this.authUser = authUser;
        this.authPassword = authPassword;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        long connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();

        // 生成 20 字节随机 scramble，追加一个 NUL 凑成 21 字节
        // JDBC 驱动（mysql-connector-java）使用 authDataLen 作为 seed 数组大小
        // authDataLen=21（8+13），所以 seed 是 21 字节（含尾部 NUL）
        byte[] scramble20 = MySQLAuth.generateScramble();
        byte[] scramble21 = new byte[21];
        System.arraycopy(scramble20, 0, scramble21, 0, 20);
        // scramble21[20] = 0x00 (already default)

        // 创建会话并绑定到 channel 属性
        FrontendSession session = FrontendSession.create(ctx.channel(), connectionId, scramble21);
        ctx.channel().attr(SessionAttribute.SESSION_KEY).set(session);

        // 构造 HandshakeV10 包
        ByteBuf handshake = buildHandshakeV10(ctx.alloc(), connectionId, scramble20);

        log.info("Sending handshake to {} (connectionId={})", ctx.channel().remoteAddress(), connectionId);
        log.debug("Handshake scramble (20 bytes): {}", MySQLAuth.bytesToHex(scramble20));

        // 注意：handshake 是连接发起的第一个包，sequenceId = 0
        // 但 Encoder 不负责管理 sequenceId，由调用者传入。这里固定 seq=0
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(handshake, (byte) 0));

        // Pipeline 切换：移除自身，加入 AuthHandler（传入配置的账密）
        ctx.pipeline().replace(this, "authHandler", new AuthHandler(authUser, authPassword));
    }

    /**
     * 构造 HandshakeV10 报文。
     */
    static ByteBuf buildHandshakeV10(ByteBufAllocator alloc, long connectionId, byte[] scramble) {
        ByteBuf buf = alloc.buffer(128);

        // 1. protocol version
        buf.writeByte(0x0A);

        // 2. server version (null-terminated)
        BufferUtils.writeNullTerminatedString(buf, SERVER_VERSION);

        // 3. connection id (4 bytes LE)
        buf.writeIntLE((int) connectionId);

        // 4. auth-plugin-data-part-1 (8 bytes)
        buf.writeBytes(scramble, 0, 8);

        // 5. filler (0x00)
        buf.writeByte(0x00);

        // 6. capability flags (lower 2 bytes)
        int capabilities = CapabilityFlags.SERVER_DEFAULT_CAPABILITIES;
        buf.writeShortLE(capabilities & 0xFFFF);

        // 7. character set (1 byte)
        buf.writeByte(CHARSET_UTF8MB4);

        // 8. status flags (2 bytes)
        buf.writeShortLE(0x0002); // SERVER_STATUS_AUTOCOMMIT

        // 9. capability flags (upper 2 bytes)
        buf.writeShortLE((capabilities >> 16) & 0xFFFF);

        // 10. length of auth-plugin-data
        // 总是 21（8+13，因为 CLIENT_SECURE_CONNECTION）
        // 注意：某些 JDBC 驱动（如 mysql-connector-java）将此值用作 seed 数组大小
        // 因此必须填 21 确保客户端解析正确
        buf.writeByte(21);

        // 11. reserved (10 bytes of 0x00)
        buf.writeZero(10);

        // 12. auth-plugin-data-part-2 (13 bytes, 最后一个是 NUL)
        // scramble[8..19] 共 12 字节，再加 NUL terminator 凑成 13 字节
        buf.writeBytes(scramble, 8, 12);
        buf.writeByte(0x00);

        // 13. auth-plugin-name (null-terminated)
        BufferUtils.writeNullTerminatedString(buf, "mysql_native_password");

        return buf;
    }
}
