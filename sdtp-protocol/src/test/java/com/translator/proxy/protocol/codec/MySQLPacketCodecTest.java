package com.translator.proxy.protocol.codec;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.translator.proxy.protocol.util.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * MySQL 编解码器测试：验证 Encoder/Decoder 的往返正确性。
 */
public class MySQLPacketCodecTest {

    // ==================== Decoder 测试 ====================

    @Test
    public void testDecodeCompletePacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 构造完整包：payload "Hello", seq=0
        String payload = "Hello";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuf in = Unpooled.buffer();
        in.writeMediumLE(payloadBytes.length); // 3 字节长度
        in.writeByte(0); // seq=0
        in.writeBytes(payloadBytes); // payload

        channel.writeInbound(in);

        MySQLPacketDecoder.RawMySQLPacket packet = channel.readInbound();
        assertNotNull("应该解码出一个包", packet);
        assertEquals("seq 应为 0", (byte) 0, packet.getSequenceId());
        assertEquals("payload 长度应匹配", payloadBytes.length, packet.getPayload().readableBytes());

        byte[] decoded = new byte[payloadBytes.length];
        packet.getPayload().readBytes(decoded);
        assertArrayEquals("payload 内容应正确", payloadBytes, decoded);

        packet.release();
        channel.finish();
    }

    @Test
    public void testDecodeMultiPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 构造两个连续包
        ByteBuf in = Unpooled.buffer();

        // 包 1: payload "AB", seq=0
        in.writeMediumLE(2);
        in.writeByte(0);
        in.writeBytes(new byte[] {'A', 'B'});

        // 包 2: payload "CDE", seq=1
        in.writeMediumLE(3);
        in.writeByte(1);
        in.writeBytes(new byte[] {'C', 'D', 'E'});

        channel.writeInbound(in);

        // 读包 1
        MySQLPacketDecoder.RawMySQLPacket p1 = channel.readInbound();
        assertNotNull(p1);
        assertEquals(0, p1.getSequenceId());
        assertEquals(2, p1.getPayload().readableBytes());
        p1.release();

        // 读包 2
        MySQLPacketDecoder.RawMySQLPacket p2 = channel.readInbound();
        assertNotNull(p2);
        assertEquals(1, p2.getSequenceId());
        assertEquals(3, p2.getPayload().readableBytes());
        p2.release();

        channel.finish();
    }

    @Test
    public void testDecodeIncompleteHeader() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 只发 2 字节（不够 4 字节头）
        ByteBuf in = Unpooled.buffer();
        in.writeShort(0x0005);
        channel.writeInbound(in);

        // 应该没有任何包被解码
        assertNull(channel.readInbound());

        channel.finish();
    }

    @Test
    public void testDecodeIncompletePayload() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketDecoder());

        // 头发了 4 字节说 payload 有 10 字节，但实际只有 5 字节
        ByteBuf in = Unpooled.buffer();
        in.writeMediumLE(10);
        in.writeByte(0);
        in.writeBytes(new byte[] {1, 2, 3, 4, 5});
        channel.writeInbound(in);

        // payload 不完整，不应解码
        assertNull(channel.readInbound());

        // 补发剩余 5 字节
        ByteBuf more = Unpooled.buffer();
        more.writeBytes(new byte[] {6, 7, 8, 9, 10});
        channel.writeInbound(more);

        // 现在应该解码出来了
        MySQLPacketDecoder.RawMySQLPacket packet = channel.readInbound();
        assertNotNull(packet);
        assertEquals(10, packet.getPayload().readableBytes());
        packet.release();

        channel.finish();
    }

    // ==================== Encoder 测试 ====================

    @Test
    public void testEncodeSinglePacket() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketEncoder());

        ByteBuf payload = Unpooled.copiedBuffer("Hello", StandardCharsets.UTF_8);
        channel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(payload, (byte) 2));

        ByteBuf encoded = channel.readOutbound();
        assertNotNull("应该编码出一个包", encoded);

        // 验证头
        int len = encoded.readUnsignedMediumLE();
        assertEquals("长度应等于 payload 长度", 5, len);
        assertEquals("seq 应为 2", (byte) 2, encoded.readByte());

        // 验证 payload
        byte[] payloadBytes = new byte[5];
        encoded.readBytes(payloadBytes);
        assertArrayEquals("payload 内容应正确", "Hello".getBytes(StandardCharsets.UTF_8), payloadBytes);

        encoded.release();
        channel.finish();
    }

    // ==================== 往返测试 ====================

    @Test
    public void testRoundTrip() {
        EmbeddedChannel serverChannel = new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder());

        // 模拟客户端发送包
        ByteBuf clientIn = Unpooled.buffer();
        clientIn.writeMediumLE(3);
        clientIn.writeByte(5);
        clientIn.writeBytes(new byte[] {10, 20, 30});
        serverChannel.writeInbound(clientIn);

        // 服务端解码
        MySQLPacketDecoder.RawMySQLPacket decoded = serverChannel.readInbound();
        assertNotNull(decoded);
        assertEquals(5, decoded.getSequenceId());

        // 服务端构造响应包
        ByteBuf response = Unpooled.buffer();
        response.writeBytes(new byte[] {99, 98, 97});
        serverChannel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(response, (byte) 6));

        // 读取编码后的输出
        ByteBuf encoded = serverChannel.readOutbound();
        assertNotNull(encoded);
        int respLen = encoded.readUnsignedMediumLE();
        assertEquals(3, respLen);
        assertEquals(6, encoded.readByte());
        byte[] respPayload = new byte[3];
        encoded.readBytes(respPayload);
        assertArrayEquals(new byte[] {99, 98, 97}, respPayload);

        decoded.release();
        encoded.release();
        serverChannel.finish();
    }

    @Test
    public void testEncodeEmptyPayload() {
        EmbeddedChannel channel = new EmbeddedChannel(new MySQLPacketEncoder());

        ByteBuf empty = Unpooled.buffer(0);
        channel.writeOutbound(new MySQLPacketEncoder.OutgoingPacket(empty, (byte) 0));

        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);
        assertEquals(0, encoded.readUnsignedMediumLE()); // payload 长度 = 0
        assertEquals(0, encoded.readByte()); // seq = 0
        assertEquals(0, encoded.readableBytes()); // 没有多余数据

        encoded.release();
        channel.finish();
    }

    // ==================== BufferUtils 测试 ====================

    @Test
    public void testLengthEncodedInt() {
        // 测试 0-250 单字节
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeLengthEncodedInt(buf, 0);
        BufferUtils.writeLengthEncodedInt(buf, 250);
        BufferUtils.writeLengthEncodedInt(buf, 251);
        BufferUtils.writeLengthEncodedInt(buf, 65535);
        BufferUtils.writeLengthEncodedInt(buf, 65536);

        assertEquals(0, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(250, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(251, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(65535, BufferUtils.readLengthEncodedInt(buf));
        assertEquals(65536, BufferUtils.readLengthEncodedInt(buf));

        buf.release();
    }

    @Test
    public void testLengthEncodedString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeLengthEncodedString(buf, "Hello World");
        BufferUtils.writeLengthEncodedString(buf, null); // NULL 标记
        BufferUtils.writeLengthEncodedString(buf, ""); // 空串

        assertEquals("Hello World", BufferUtils.readLengthEncodedString(buf));
        assertNull(BufferUtils.readLengthEncodedString(buf));
        assertEquals("", BufferUtils.readLengthEncodedString(buf));

        buf.release();
    }

    @Test
    public void testNullTerminatedString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeNullTerminatedString(buf, "test_db");

        assertEquals("test_db", BufferUtils.readNullTerminatedString(buf));
        buf.release();
    }

    @Test
    public void testFixedLengthString() {
        ByteBuf buf = Unpooled.buffer();
        BufferUtils.writeFixedLengthString(buf, "root", 16); // 填到 16 字节

        byte[] raw = new byte[16];
        buf.readBytes(raw);
        assertEquals('r', raw[0]);
        assertEquals('o', raw[1]);
        assertEquals('o', raw[2]);
        assertEquals('t', raw[3]);
        // 剩余应为 NUL
        for (int i = 4; i < 16; i++) {
            assertEquals(0x00, raw[i]);
        }
        buf.release();
    }
}
