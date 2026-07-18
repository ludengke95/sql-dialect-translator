package com.translator.proxy.protocol.pg;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * PostgreSQL 前端响应编码器。
 *
 * <p>入站消息为 {@link PgOutbound}：
 * <ul>
 *   <li>framed 消息：content 为已包含 [类型字节][body] 的 ByteBuf，
 *       本编码器按 PG 线格式补充 [int32 长度] 字段：
 *       <pre>Byte1(type) + Int32(4 + bodyLen) + body</pre></li>
 *   <li>raw 消息：content 原样写出（如 SSL 协商的 {@code 'N'} 单字节应答）。</li>
 * </ul>
 */
public class PgMessageEncoder extends MessageToByteEncoder<PgOutbound> {

    @Override
    protected void encode(ChannelHandlerContext ctx, PgOutbound msg, ByteBuf out) {
        try {
            if (msg.raw) {
                out.writeBytes(msg.content);
                return;
            }
            byte type = msg.content.readByte();
            int bodyLen = msg.content.readableBytes();
            out.writeByte(type);
            out.writeInt(4 + bodyLen);
            out.writeBytes(msg.content, msg.content.readerIndex(), bodyLen);
        } finally {
            // MessageToByteEncoder 仅释放 ReferenceCounted 的 PgOutbound（无操作），
            // 这里手动释放被包装的 ByteBuf，避免内存泄漏。
            msg.content.release();
        }
    }
}

