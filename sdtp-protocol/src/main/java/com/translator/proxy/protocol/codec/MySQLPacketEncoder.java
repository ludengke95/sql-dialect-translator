package com.translator.proxy.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL 协议封包器。
 *
 * <p>将 payload ByteBuf 封装为 MySQL 线协议包：
 * <pre>
 *   写入 3 字节小端序 payload 长度 + 1 字节 sequence ID + payload
 * </pre>
 */
public class MySQLPacketEncoder extends MessageToByteEncoder<MySQLPacketEncoder.OutgoingPacket> {

    private static final Logger log = LoggerFactory.getLogger(MySQLPacketEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, OutgoingPacket packet, ByteBuf out) {
        ByteBuf payload = packet.payload;
        byte sequenceId = packet.sequenceId;
        int payloadLen = payload.readableBytes();

        // 写入 3 字节小端序 payload 长度
        out.writeMediumLE(payloadLen);
        // 写入 1 字节 sequence ID
        out.writeByte(sequenceId);
        // 写入 payload
        out.writeBytes(payload);

        // 释放 payload（MessageToByteEncoder 只释放 OutgoingPacket 对象本身，不释放其持有的 ByteBuf）
        payload.release();

        if (log.isTraceEnabled()) {
            log.trace("Encoded packet: seq={}, payloadLen={}", sequenceId, payloadLen);
        }
    }

    /**
     * 待发送的 MySQL 包（payload + sequenceId）。
     * <p>调用者负责管理 payload ByteBuf 的生命周期（通常 writeAndFlush 后 Netty 会自动 release）。
     */
    public static class OutgoingPacket {
        private final ByteBuf payload;
        private final byte sequenceId;

        public OutgoingPacket(ByteBuf payload, byte sequenceId) {
            this.payload = payload;
            this.sequenceId = sequenceId;
        }

        public ByteBuf getPayload() {
            return payload;
        }

        public byte getSequenceId() {
            return sequenceId;
        }
    }
}
