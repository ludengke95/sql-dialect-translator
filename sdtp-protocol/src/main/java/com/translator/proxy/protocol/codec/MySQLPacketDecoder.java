package com.translator.proxy.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MySQL 协议拆包器。
 *
 * <p>MySQL 线协议包格式（4 字节头）：
 * <pre>
 *   ┌──────────────┬──────────────┬───────────────────┐
 *   │ payload_len  │ sequence_id  │     payload       │
 *   │  3 bytes LE  │  1 byte      │  payload_len bytes│
 *   └──────────────┴──────────────┴───────────────────┘
 * </pre>
 *
 * <p>注意：如果一个 MySQL 包超过 16MB（0xFFFFFF），会被拆成多个子包（splitting），
 * 每个子包有独立的 4 字节头。当前简化实现假设单包不超过 16MB。
 */
public class MySQLPacketDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(MySQLPacketDecoder.class);

    /** MySQL 包最大 payload 长度（3 字节 = 16MB - 1） */
    private static final int MAX_PAYLOAD_LENGTH = 0x00FFFFFF;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 至少需要 4 字节头
        if (in.readableBytes() < 4) {
            return;
        }

        // 标记读指针，以便 payload 不完整时回退
        in.markReaderIndex();

        // 读取 3 字节小端序 payload 长度
        int payloadLength = in.readUnsignedMediumLE();
        // 读取 1 字节 sequence ID
        byte sequenceId = in.readByte();

        // 检查 payload 是否已完整到达
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        // 读取 payload
        ByteBuf payload = in.readRetainedSlice(payloadLength);

        if (log.isTraceEnabled()) {
            log.trace("Decoded packet: seq={}, payloadLen={}", sequenceId, payloadLength);
        }

        // 将解析结果传递给下游 Handler
        out.add(new RawMySQLPacket(payload, sequenceId));
    }

    /**
     * 解码后的原始包（payload + sequenceId）。
     * 下游 Handler 根据 command 类型进一步解析具体报文。
     */
    public static class RawMySQLPacket {
        private final ByteBuf payload;
        private final byte sequenceId;

        public RawMySQLPacket(ByteBuf payload, byte sequenceId) {
            this.payload = payload;
            this.sequenceId = sequenceId;
        }

        public ByteBuf getPayload() {
            return payload;
        }

        public byte getSequenceId() {
            return sequenceId;
        }

        /**
         * 释放 payload 持有的 ByteBuf 引用。
         */
        public void release() {
            if (payload != null) {
                payload.release();
            }
        }

        @Override
        public String toString() {
            return "RawMySQLPacket{seq=" + sequenceId + ", len="
                    + (payload != null ? payload.readableBytes() : 0) + "}";
        }
    }
}
