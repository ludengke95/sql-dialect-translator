package com.translator.proxy.core.handler;

import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CapabilityFlags;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.util.BufferUtils;
import com.translator.proxy.protocol.util.MySQLAuth;
import com.translator.proxy.core.session.FrontendSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * 握手+认证集成测试：模拟客户端连接→握手→认证→命令的完整流程。
 */
public class HandshakeAuthTest {

    // 测试用的账密
    private static final String TEST_USER = "root";
    private static final String TEST_PASSWORD = "test123";

    @Test
    public void testFullHandshakeAndAuth() {
        // 创建服务端 EmbeddedChannel
        // 注意：AuthHandler 默认账密是 root/proxy_password，
        // 这里用自定义账密的 AuthHandler 替换
        EmbeddedChannel channel = new EmbeddedChannel(
                new MySQLPacketDecoder(),
                new MySQLPacketEncoder(),
                new TestHandshakeHandler()
        );

        // Step 1: 服务端应发送 HandshakeV10（Encoder 已编码为 ByteBuf）
        ByteBuf handshakeRaw = channel.readOutbound();
        assertNotNull("服务端应发送 HandshakeV10", handshakeRaw);

        // 解析 MySQL 包: 3字节长度 + 1字节seq + payload
        int handshakeLen = handshakeRaw.readUnsignedMediumLE();
        assertEquals("HandshakeV10 seq 应为 0", (byte) 0, handshakeRaw.readByte());
        ByteBuf handshakePayload = handshakeRaw.readBytes(handshakeLen);
        // 验证 protocol version = 0x0A
        assertEquals("协议版本应为 0x0A (10)", 0x0A, handshakePayload.readByte());

        // 跳过 server version (null-terminated)
        BufferUtils.readNullTerminatedString(handshakePayload);

        // 读取 connectionId
        int connectionId = handshakePayload.readIntLE();
        assertTrue("connectionId 应为正数", connectionId > 0);

        // 读取 auth-plugin-data-part-1 (8 bytes)
        byte[] scramblePart1 = new byte[8];
        handshakePayload.readBytes(scramblePart1);

        // 跳过 filler + lower cap + charset + status + upper cap + auth-len + reserved
        handshakePayload.skipBytes(1);  // filler
        handshakePayload.skipBytes(2);  // lower capability
        handshakePayload.skipBytes(1);  // charset
        handshakePayload.skipBytes(2);  // status
        handshakePayload.skipBytes(2);  // upper capability
        handshakePayload.skipBytes(1);  // auth len (21)
        handshakePayload.skipBytes(10); // reserved

        // auth-plugin-data-part-2 (13 bytes, last is NUL)
        byte[] scramblePart2 = new byte[12];
        handshakePayload.readBytes(scramblePart2);
        handshakePayload.skipBytes(1);  // NUL terminator

        // 拼接完整 scramble (20 bytes)
        byte[] scramble = new byte[20];
        System.arraycopy(scramblePart1, 0, scramble, 0, 8);
        System.arraycopy(scramblePart2, 0, scramble, 8, 12);

        // 读取 auth-plugin-name
        String authPlugin = BufferUtils.readNullTerminatedString(handshakePayload);
        assertEquals("auth plugin 应为 mysql_native_password", "mysql_native_password", authPlugin);

        // ========== Step 2: 模拟客户端发送 HandshakeResponse41 ==========
        // 客户端计算 auth token
        byte[] clientToken = MySQLAuth.scramble411(TEST_PASSWORD, scramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES,
                TEST_USER, clientToken, null);
        // 包装为完整 MySQL 包（4字节头 + payload），seq=1（客户端响应用 seq=1）
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // ========== Step 3: 服务端应回复 OK Packet ==========
        ByteBuf okRaw = channel.readOutbound();
        assertNotNull("服务端应发送 OK 包", okRaw);

        int okLen = okRaw.readUnsignedMediumLE();
        assertEquals("OK 包 seq 应为 2", (byte) 2, okRaw.readByte());
        ByteBuf okPayload = okRaw.readBytes(okLen);
        assertEquals("OK 包头应为 0x00", (byte) 0x00, okPayload.readByte());

        // ========== Step 4: 模拟客户端发送 COM_PING ==========
        ByteBuf pingPayload = Unpooled.buffer(1);
        pingPayload.writeByte(CommandType.COM_PING);

        ByteBuf pingPacket = buildClientPacket(pingPayload, (byte) 0);
        channel.writeInbound(pingPacket);

        // 服务端应回复 OK
        ByteBuf pingOkRaw = channel.readOutbound();
        assertNotNull("PING 应收到 OK 响应", pingOkRaw);
        int pingOkLen = pingOkRaw.readUnsignedMediumLE();
        assertEquals("PING 响应的 seq 应为 1", (byte) 1, pingOkRaw.readByte());

        // ========== Step 5: 模拟客户端发送 COM_QUIT ==========
        ByteBuf quitPayload = Unpooled.buffer(1);
        quitPayload.writeByte(CommandType.COM_QUIT);

        ByteBuf quitPacket = buildClientPacket(quitPayload, (byte) 0);
        channel.writeInbound(quitPacket);

        // 连接应被关闭
        assertFalse("COM_QUIT 后 channel 应关闭", channel.isActive());

        channel.finish();
    }

    @Test
    public void testAuthWithWrongPassword() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new MySQLPacketDecoder(),
                new MySQLPacketEncoder(),
                new TestHandshakeHandler()
        );

        // 读取 HandshakeV10（已编码）
        ByteBuf hpRaw = channel.readOutbound();
        assertNotNull(hpRaw);

        int hpLen = hpRaw.readUnsignedMediumLE();
        hpRaw.readByte(); // seq
        ByteBuf payload = hpRaw.readBytes(hpLen);
        payload.readByte(); // protocol version
        BufferUtils.readNullTerminatedString(payload); // server version
        payload.readIntLE(); // connectionId
        byte[] scramblePart1 = new byte[8];
        payload.readBytes(scramblePart1);
        payload.skipBytes(1 + 2 + 1 + 2 + 2 + 1 + 10);
        byte[] scramblePart2 = new byte[12];
        payload.readBytes(scramblePart2);

        byte[] scramble = new byte[20];
        System.arraycopy(scramblePart1, 0, scramble, 0, 8);
        System.arraycopy(scramblePart2, 0, scramble, 8, 12);

        // 用错误密码计算 token
        byte[] wrongToken = MySQLAuth.scramble411("wrong_password", scramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES,
                TEST_USER, wrongToken, null);
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // 服务端应回复 ERR Packet
        ByteBuf errRaw = channel.readOutbound();
        assertNotNull("错误密码应收到 ERR 包", errRaw);

        int errLen = errRaw.readUnsignedMediumLE();
        errRaw.readByte(); // seq
        ByteBuf errPayload = errRaw.readBytes(errLen);
        assertEquals("ERR 包头应为 0xFF", (byte) 0xFF, errPayload.readByte());

        channel.finish();
    }

    // ==================== Helper Methods ====================

    /**
     * 构造客户端发来的 MySQL 包（4 字节头 + payload）。
     */
    private ByteBuf buildClientPacket(ByteBuf payload, byte sequenceId) {
        int len = payload.readableBytes();
        ByteBuf packet = Unpooled.buffer(4 + len);
        packet.writeMediumLE(len);
        packet.writeByte(sequenceId);
        packet.writeBytes(payload);
        payload.release();
        return packet;
    }

    /**
     * 构造 HandshakeResponse41。
     */
    private ByteBuf buildHandshakeResponse41(int capabilities, String username,
                                              byte[] authResponse, String database) {
        ByteBuf buf = Unpooled.buffer(128);

        // 1. capability flags (4 bytes)
        buf.writeIntLE(capabilities);

        // 2. max packet size (4 bytes)
        buf.writeIntLE(0x01000000); // 16MB

        // 3. character set (1 byte) — utf8mb4 = 33
        buf.writeByte(33);

        // 4. reserved (23 bytes of 0x00)
        buf.writeZero(23);

        // 5. username (null-terminated)
        BufferUtils.writeNullTerminatedString(buf, username);

        // 6. auth-response (length-encoded or 1-byte len)
        if ((capabilities & CapabilityFlags.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) != 0) {
            // length-encoded
            BufferUtils.writeLengthEncodedInt(buf, authResponse.length);
            buf.writeBytes(authResponse);
        } else if ((capabilities & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0) {
            // 1 byte length + bytes
            buf.writeByte(authResponse.length);
            buf.writeBytes(authResponse);
        } else {
            buf.writeBytes(authResponse);
            buf.writeByte(0x00);
        }

        // 7. database (null-terminated, if CLIENT_CONNECT_WITH_DB)
        if ((capabilities & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            if (database != null) {
                BufferUtils.writeNullTerminatedString(buf, database);
            } else {
                // 未指定 database 时写一个空 NUL 终止符
                buf.writeByte(0x00);
            }
        }

        // 8. auth-plugin-name (null-terminated, if CLIENT_PLUGIN_AUTH)
        if ((capabilities & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0) {
            BufferUtils.writeNullTerminatedString(buf, "mysql_native_password");
        }

        return buf;
    }

    /**
     * 测试用的 HandshakeHandler 变体，使用测试账密。
     */
    private static class TestHandshakeHandler extends HandshakeHandler {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            long connectionId = 1;
            byte[] scramble = MySQLAuth.generateScramble();

            FrontendSession session = FrontendSession.create(ctx.channel(), connectionId, scramble);
            ctx.channel().attr(SessionAttribute.SESSION_KEY).set(session);

            ByteBuf handshake = HandshakeHandler.buildHandshakeV10(ctx.alloc(), connectionId, scramble);
            ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(handshake, (byte) 0));

            ctx.pipeline().replace(this, "authHandler",
                    new AuthHandler(TEST_USER, TEST_PASSWORD));
        }
    }
}
