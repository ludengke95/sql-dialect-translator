package com.translator.proxy.core.handler;

import static org.junit.Assert.*;

import org.junit.Test;

import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CapabilityFlags;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.util.BufferUtils;
import com.translator.proxy.protocol.util.MySQLAuth;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 握手+认证集成测试：模拟客户端连接→握手→认证→命令的完整流程。
 *
 * <p>覆盖两种认证插件：
 * <ul>
 *   <li>caching_sha2_password（MySQL 8.0 客户端）：SHA-256 → AuthMoreData → OK</li>
 *   <li>mysql_native_password（MySQL 5.7 客户端）：SHA-1 → OK</li>
 * </ul>
 */
public class HandshakeAuthTest {

    // 测试用的账密
    private static final String TEST_USER = "root";
    private static final String TEST_PASSWORD = "test123";

    // ==================== MySQL 8.0 caching_sha2_password → AuthSwitch → mysql_native_password 流程 ====================

    @Test
    public void testFullHandshakeAndAuth() {
        // 创建服务端 EmbeddedChannel
        EmbeddedChannel channel =
                new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder(), new TestHandshakeHandler());

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
        handshakePayload.skipBytes(1); // filler
        handshakePayload.skipBytes(2); // lower capability
        handshakePayload.skipBytes(1); // charset
        handshakePayload.skipBytes(2); // status
        handshakePayload.skipBytes(2); // upper capability
        handshakePayload.skipBytes(1); // auth len (21)
        handshakePayload.skipBytes(10); // reserved

        // auth-plugin-data-part-2 (13 bytes, last is NUL)
        byte[] scramblePart2 = new byte[12];
        handshakePayload.readBytes(scramblePart2);
        handshakePayload.skipBytes(1); // NUL terminator

        // 拼接完整 scramble (20 bytes)
        byte[] handshakeScramble = new byte[20];
        System.arraycopy(scramblePart1, 0, handshakeScramble, 0, 8);
        System.arraycopy(scramblePart2, 0, handshakeScramble, 8, 12);

        // 读取 auth-plugin-name — 服务端声明 mysql_native_password
        String authPlugin = BufferUtils.readNullTerminatedString(handshakePayload);
        assertEquals("auth plugin 应为 mysql_native_password", "mysql_native_password", authPlugin);

        // ========== Step 2: 模拟 MySQL 8.0 客户端发送 HandshakeResponse41（使用 caching_sha2_password） ==========
        // 客户端计算 auth token（SHA-256），但可能为空（需要 AuthSwitch）
        byte[] clientToken = MySQLAuth.scramble411Sha256(TEST_PASSWORD, handshakeScramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES, TEST_USER, clientToken, null, "caching_sha2_password");
        // 包装为完整 MySQL 包（4字节头 + payload），seq=1（客户端响应用 seq=1）
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // ========== Step 3: 如果 SHA-256 fast auth 失败 → 服务端发 AuthSwitchRequest ==========
        // 检查第一个响应是什么：AuthSwitchRequest (0xFE) 或 AuthMoreData (0x01)
        ByteBuf resp1Raw = channel.readOutbound();
        assertNotNull("服务端应发送响应", resp1Raw);
        int resp1Len = resp1Raw.readUnsignedMediumLE();
        int resp1Seq = resp1Raw.readUnsignedByte();
        ByteBuf resp1Payload = resp1Raw.readBytes(resp1Len);
        byte resp1Header = resp1Payload.readByte();

        if (resp1Header == 0x01) {
            // SHA-256 fast auth 成功 → AuthMoreData(0x03) → OK
            assertEquals("AuthMoreData seq 应为 2", 2, resp1Seq);
            assertEquals("AuthMoreData 应为 fast auth success (0x03)", (byte) 0x03, resp1Payload.readByte());
            ByteBuf okRaw = channel.readOutbound();
            assertNotNull("服务端应发送 OK 包", okRaw);
            int okLen = okRaw.readUnsignedMediumLE();
            assertEquals("OK 包 seq 应为 3", (byte) 3, okRaw.readByte());
            ByteBuf okPayload = okRaw.readBytes(okLen);
            assertEquals("OK 包头应为 0x00", (byte) 0x00, okPayload.readByte());
        } else if (resp1Header == (byte) 0xFE) {
            // SHA-256 fast auth 失败 → AuthSwitch → 需要回复 AuthSwitchResponse
            assertEquals("AuthSwitch seq 应为 2", 2, resp1Seq);
            // 读取 plugin name
            String switchPlugin = BufferUtils.readNullTerminatedString(resp1Payload);
            assertEquals("AuthSwitch plugin 应为 mysql_native_password", "mysql_native_password", switchPlugin);

            // 读取 AuthSwitch scramble (20 bytes + NUL)
            byte[] authSwitchScrambleRaw = new byte[20];
            resp1Payload.readBytes(authSwitchScrambleRaw);
            // 跳过 NUL terminator
            assertEquals("AuthSwitch scramble 后应为 NUL", (byte) 0x00, resp1Payload.readByte());

            // ========== Step 3a: 客户端用新 scramble 计算 SHA-1 token 并发送 AuthSwitchResponse ==========
            byte[] switchToken = MySQLAuth.scramble411(TEST_PASSWORD, authSwitchScrambleRaw);

            ByteBuf switchResponse = Unpooled.buffer(switchToken.length);
            switchResponse.writeBytes(switchToken);
            ByteBuf switchPacket = buildClientPacket(switchResponse, (byte) 3);
            channel.writeInbound(switchPacket);

            // ========== Step 3b: 服务端回复 OK ==========
            ByteBuf okRaw = channel.readOutbound();
            assertNotNull("AuthSwitch 后应收到 OK 包", okRaw);
            int okLen = okRaw.readUnsignedMediumLE();
            assertEquals("OK 包 seq 应为 4", (byte) 4, okRaw.readByte());
            ByteBuf okPayload = okRaw.readBytes(okLen);
            assertEquals("OK 包头应为 0x00", (byte) 0x00, okPayload.readByte());
        } else {
            fail("意外的响应头: 0x" + Integer.toHexString(resp1Header & 0xFF));
        }

        // ========== Step 4: 模拟客户端发送 COM_PING ==========
        ByteBuf pingPayload = Unpooled.buffer(1);
        pingPayload.writeByte(CommandType.COM_PING);

        ByteBuf pingPacket = buildClientPacket(pingPayload, (byte) 0);
        channel.writeInbound(pingPacket);

        // 服务端应回复 OK
        ByteBuf pingOkRaw = channel.readOutbound();
        assertNotNull("PING 应收到 OK 响应", pingOkRaw);

        // ========== Step 5: 模拟客户端发送 COM_QUIT ==========
        ByteBuf quitPayload = Unpooled.buffer(1);
        quitPayload.writeByte(CommandType.COM_QUIT);

        ByteBuf quitPacket = buildClientPacket(quitPayload, (byte) 0);
        channel.writeInbound(quitPacket);

        // 连接应被关闭
        assertFalse("COM_QUIT 后 channel 应关闭", channel.isActive());

        channel.finish();
    }

    // ==================== MySQL 5.7 mysql_native_password 流程 ====================

    @Test
    public void testMySQL57NativeAuth() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder(), new TestHandshakeHandler());

        // Step 1: 读取 HandshakeV10
        ByteBuf handshakeRaw = channel.readOutbound();
        assertNotNull("服务端应发送 HandshakeV10", handshakeRaw);

        int handshakeLen = handshakeRaw.readUnsignedMediumLE();
        assertEquals("HandshakeV10 seq 应为 0", (byte) 0, handshakeRaw.readByte());
        ByteBuf handshakePayload = handshakeRaw.readBytes(handshakeLen);
        assertEquals("协议版本应为 0x0A", 0x0A, handshakePayload.readByte());

        BufferUtils.readNullTerminatedString(handshakePayload); // server version
        handshakePayload.readIntLE(); // connectionId

        byte[] scramblePart1 = new byte[8];
        handshakePayload.readBytes(scramblePart1);
        handshakePayload.skipBytes(1 + 2 + 1 + 2 + 2 + 1 + 10);
        byte[] scramblePart2 = new byte[12];
        handshakePayload.readBytes(scramblePart2);

        byte[] scramble = new byte[20];
        System.arraycopy(scramblePart1, 0, scramble, 0, 8);
        System.arraycopy(scramblePart2, 0, scramble, 8, 12);

        // ========== Step 2: MySQL 5.7 客户端发送 HandshakeResponse41（使用 mysql_native_password） ==========
        // 使用 SHA-1 计算 token
        byte[] nativeToken = MySQLAuth.scramble411(TEST_PASSWORD, scramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES, TEST_USER, nativeToken, null, "mysql_native_password");
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // ========== Step 3: 服务端应直接回复 OK（mysql_native_password 流程） ==========
        ByteBuf okRaw = channel.readOutbound();
        assertNotNull("MySQL 5.7 认证应收到 OK 包", okRaw);

        int okLen = okRaw.readUnsignedMediumLE();
        assertEquals("OK 包 seq 应为 2", (byte) 2, okRaw.readByte());
        ByteBuf okPayload = okRaw.readBytes(okLen);
        assertEquals("OK 包头应为 0x00", (byte) 0x00, okPayload.readByte());

        // ========== Step 4: COM_PING 验证连接可用 ==========
        ByteBuf pingPayload = Unpooled.buffer(1);
        pingPayload.writeByte(CommandType.COM_PING);
        ByteBuf pingPacket = buildClientPacket(pingPayload, (byte) 0);
        channel.writeInbound(pingPacket);

        ByteBuf pingOkRaw = channel.readOutbound();
        assertNotNull("PING 应收到 OK 响应", pingOkRaw);

        channel.finish();
    }

    // ==================== MySQL 5.7 plugin==null 流程 ====================

    @Test
    public void testMySQL57NullPlugin() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder(), new TestHandshakeHandler());

        // 读取 HandshakeV10
        ByteBuf handshakeRaw = channel.readOutbound();
        assertNotNull(handshakeRaw);
        int handshakeLen = handshakeRaw.readUnsignedMediumLE();
        handshakeRaw.readByte(); // seq
        ByteBuf handshakePayload = handshakeRaw.readBytes(handshakeLen);
        handshakePayload.readByte(); // protocol version
        BufferUtils.readNullTerminatedString(handshakePayload); // server version
        handshakePayload.readIntLE(); // connectionId
        byte[] scramblePart1 = new byte[8];
        handshakePayload.readBytes(scramblePart1);
        handshakePayload.skipBytes(1 + 2 + 1 + 2 + 2 + 1 + 10);
        byte[] scramblePart2 = new byte[12];
        handshakePayload.readBytes(scramblePart2);
        byte[] scramble = new byte[20];
        System.arraycopy(scramblePart1, 0, scramble, 0, 8);
        System.arraycopy(scramblePart2, 0, scramble, 8, 12);

        // MySQL 5.7 旧版驱动可能不声明 plugin（plugin==null）
        // 构建不带 CLIENT_PLUGIN_AUTH 的 HandshakeResponse41
        int caps = CapabilityFlags.SERVER_DEFAULT_CAPABILITIES & ~CapabilityFlags.CLIENT_PLUGIN_AUTH;
        byte[] nativeToken = MySQLAuth.scramble411(TEST_PASSWORD, scramble);

        // 构建不包含 auth-plugin-name 的响应（模拟旧版客户端）
        ByteBuf authPayload = buildHandshakeResponse41NoPlugin(caps, TEST_USER, nativeToken, null);
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // 服务端应直接回复 OK（plugin==null 走 mysql_native_password 流程）
        ByteBuf okRaw = channel.readOutbound();
        assertNotNull("plugin==null 认证应收到 OK 包", okRaw);

        int okLen = okRaw.readUnsignedMediumLE();
        assertEquals("OK 包 seq 应为 2", (byte) 2, okRaw.readByte());
        ByteBuf okPayload = okRaw.readBytes(okLen);
        assertEquals("OK 包头应为 0x00", (byte) 0x00, okPayload.readByte());

        // COM_PING 验证
        ByteBuf pingPayload = Unpooled.buffer(1);
        pingPayload.writeByte(CommandType.COM_PING);
        ByteBuf pingPacket = buildClientPacket(pingPayload, (byte) 0);
        channel.writeInbound(pingPacket);
        ByteBuf pingOkRaw = channel.readOutbound();
        assertNotNull("PING 应收到 OK 响应", pingOkRaw);

        channel.finish();
    }

    // ==================== 错误密码测试 ====================

    @Test
    public void testCachingSha2WrongPassword() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder(), new TestHandshakeHandler());

        // 读取 HandshakeV10
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

        // 用错误密码计算 SHA-256 token
        byte[] wrongToken = MySQLAuth.scramble411Sha256("wrong_password", scramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES, TEST_USER, wrongToken, null, "caching_sha2_password");
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // SHA-256 fast auth 失败 → 回退到 AuthSwitch
        // 服务端发送 AuthSwitchRequest (header=0xFE)
        ByteBuf respRaw = channel.readOutbound();
        assertNotNull("错误密码应收到响应", respRaw);
        int respLen = respRaw.readUnsignedMediumLE();
        respRaw.readByte(); // seq
        ByteBuf respPayload = respRaw.readBytes(respLen);
        byte header = respPayload.readByte();

        if (header == (byte) 0xFE) {
            // AuthSwitch → 客户端可以继续 AuthSwitchResponse
            // 我们发一个错误的 AuthSwitchResponse
            String switchPlugin = BufferUtils.readNullTerminatedString(respPayload);
            assertEquals("mysql_native_password", switchPlugin);
            byte[] authSwitchScrambleRaw = new byte[20];
            respPayload.readBytes(authSwitchScrambleRaw);

            byte[] wrongSwitchToken = MySQLAuth.scramble411("wrong_password", authSwitchScrambleRaw);
            ByteBuf switchResponse = Unpooled.buffer(wrongSwitchToken.length);
            switchResponse.writeBytes(wrongSwitchToken);
            ByteBuf switchPacket = buildClientPacket(switchResponse, (byte) 3);
            channel.writeInbound(switchPacket);

            // AuthSwitch 也失败 → ERR
            ByteBuf errRaw = channel.readOutbound();
            assertNotNull("AuthSwitch 失败应收到 ERR 包", errRaw);
            int errLen = errRaw.readUnsignedMediumLE();
            errRaw.readByte(); // seq
            ByteBuf errPayload = errRaw.readBytes(errLen);
            assertEquals("ERR 包头应为 0xFF", (byte) 0xFF, errPayload.readByte());
        } else {
            // 直接 ERR（可能是 fast auth 直接拒绝）
            assertEquals("ERR 包头应为 0xFF", (byte) 0xFF, header);
        }

        channel.finish();
    }

    @Test
    public void testNativePasswordWrongPassword() {
        EmbeddedChannel channel =
                new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder(), new TestHandshakeHandler());

        // 读取 HandshakeV10
        ByteBuf hpRaw = channel.readOutbound();
        assertNotNull(hpRaw);
        int hpLen = hpRaw.readUnsignedMediumLE();
        hpRaw.readByte(); // seq
        ByteBuf payload = hpRaw.readBytes(hpLen);
        payload.readByte();
        BufferUtils.readNullTerminatedString(payload);
        payload.readIntLE();
        byte[] scramblePart1 = new byte[8];
        payload.readBytes(scramblePart1);
        payload.skipBytes(1 + 2 + 1 + 2 + 2 + 1 + 10);
        byte[] scramblePart2 = new byte[12];
        payload.readBytes(scramblePart2);
        byte[] scramble = new byte[20];
        System.arraycopy(scramblePart1, 0, scramble, 0, 8);
        System.arraycopy(scramblePart2, 0, scramble, 8, 12);

        // 用错误密码计算 SHA-1 token
        byte[] wrongToken = MySQLAuth.scramble411("wrong_password", scramble);

        ByteBuf authPayload = buildHandshakeResponse41(
                CapabilityFlags.SERVER_DEFAULT_CAPABILITIES, TEST_USER, wrongToken, null, "mysql_native_password");
        ByteBuf authRequest = buildClientPacket(authPayload, (byte) 1);
        channel.writeInbound(authRequest);

        // 服务端应回复 ERR Packet
        ByteBuf errRaw = channel.readOutbound();
        assertNotNull("错误密码（native）应收到 ERR 包", errRaw);
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
     * 构造 HandshakeResponse41（含 CLIENT_PLUGIN_AUTH）。
     */
    private ByteBuf buildHandshakeResponse41(
            int capabilities, String username, byte[] authResponse, String database, String authPluginName) {
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
            BufferUtils.writeLengthEncodedInt(buf, authResponse.length);
            buf.writeBytes(authResponse);
        } else if ((capabilities & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0) {
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
                buf.writeByte(0x00);
            }
        }

        // 8. auth-plugin-name (null-terminated, if CLIENT_PLUGIN_AUTH)
        if ((capabilities & CapabilityFlags.CLIENT_PLUGIN_AUTH) != 0) {
            BufferUtils.writeNullTerminatedString(buf, authPluginName);
        }

        return buf;
    }

    /**
     * 构造 HandshakeResponse41（不含 CLIENT_PLUGIN_AUTH，模拟旧版客户端）。
     */
    private ByteBuf buildHandshakeResponse41NoPlugin(
            int capabilities, String username, byte[] authResponse, String database) {
        ByteBuf buf = Unpooled.buffer(128);
        buf.writeIntLE(capabilities);
        buf.writeIntLE(0x01000000);
        buf.writeByte(33);
        buf.writeZero(23);
        BufferUtils.writeNullTerminatedString(buf, username);
        if ((capabilities & CapabilityFlags.CLIENT_SECURE_CONNECTION) != 0) {
            buf.writeByte(authResponse.length);
            buf.writeBytes(authResponse);
        } else {
            buf.writeBytes(authResponse);
            buf.writeByte(0x00);
        }
        if ((capabilities & CapabilityFlags.CLIENT_CONNECT_WITH_DB) != 0) {
            if (database != null) {
                BufferUtils.writeNullTerminatedString(buf, database);
            } else {
                buf.writeByte(0x00);
            }
        }
        // 不写 auth-plugin-name
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

            ctx.pipeline().replace(this, "authHandler", new AuthHandler(TEST_USER, TEST_PASSWORD));
        }
    }
}
