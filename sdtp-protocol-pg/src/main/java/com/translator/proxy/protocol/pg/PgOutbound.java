package com.translator.proxy.protocol.pg;

import io.netty.buffer.ByteBuf;

/**
 * PG 出站消息包装。
 *
 * <p>PG 线协议出站消息有两种形态：
 * <ul>
 *   <li>{@code framed=false}：标准后端消息帧，内容为 {@code [类型字节][body]}，
 *       由 {@link PgMessageEncoder} 补充 {@code [int32 长度]} 字段。</li>
 *   <li>{@code raw=true}：无需长度帧的原始字节（如 SSL 协商阶段应答的单字节 {@code 'N'}），
 *       由 {@link PgMessageEncoder} 原样写出。</li>
 * </ul>
 */
public final class PgOutbound {

    public final ByteBuf content;

    public final boolean raw;

    private PgOutbound(ByteBuf content, boolean raw) {
        this.content = content;
        this.raw = raw;
    }

    /** 标准帧消息：content 以类型字节开头 */
    public static PgOutbound framed(ByteBuf content) {
        return new PgOutbound(content, false);
    }

    /** 原始字节消息：不经过长度帧封装 */
    public static PgOutbound raw(ByteBuf content) {
        return new PgOutbound(content, true);
    }
}
