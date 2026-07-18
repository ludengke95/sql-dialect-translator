package com.translator.proxy.protocol.pg;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * PostgreSQL 前端消息解码器。
 *
 * <p>连接首条消息为 StartupMessage（无类型字节，[int32 len][int32 proto][key/value...]），
 * 之后所有消息为常规格式（[byte type][int32 len][body]）。解码后产出 {@code Pg*} 消息 POJO。
 */
public class PgMessageDecoder extends ByteToMessageDecoder {

    /** 测试与内部共用的 ASCII 字符集 */
    public static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final int MAX_MESSAGE = 1_000_000;

    /** 是否已收到 StartupMessage（之后转入常规消息解析） */
    private boolean startupReceived;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (startupReceived) {
            decodeRegular(in, out);
            return;
        }
        // 首条消息类型尚未确定：探测是 StartupMessage 还是常规消息。
        // 真实客户端首包必为 StartupMessage（含协议号 196608，总字节 ≥ 8）；其余为带类型字节的常规消息。
        if (in.readableBytes() < 5) {
            return;
        }
        if (in.readableBytes() >= 8) {
            in.markReaderIndex();
            int len = in.readInt();
            int proto = in.readInt();
            in.resetReaderIndex();
            if (len >= 8 && len <= MAX_MESSAGE) {
                if (proto == PgProtocol.PROTOCOL_VERSION_3) {
                    decodeStartup(in, out);
                    return;
                }
                if (proto == PgProtocol.SSL_REQUEST_CODE) {
                    out.add(new PgSslRequest());
                    return;
                }
            }
        }
        // 不足 8 字节（如仅 5 字节的 Terminate/Flush/Sync）或无 startup 特征 → 按常规消息处理
        decodeRegular(in, out);
    }

    private void decodeStartup(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 8) {
            return;
        }
        in.markReaderIndex();
        int len = in.readInt();
        if (len < 8 || len > MAX_MESSAGE) {
            // 非法长度：跳过 1 字节避免死循环
            in.resetReaderIndex();
            in.skipBytes(1);
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex();
            return;
        }
        int proto = in.readInt();
        if (proto != PgProtocol.PROTOCOL_VERSION_3) {
            in.resetReaderIndex();
            in.skipBytes(1);
            return;
        }
        ByteBuf kv = in.readSlice(len - 8);
        Map<String, String> params = parseStartupParams(kv);
        startupReceived = true;
        out.add(new PgStartupMessage(params));
    }

    private void decodeRegular(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }
        in.markReaderIndex();
        byte type = in.readByte();
        int len = in.readInt(); // 长度包含自身 4 字节，不含类型字节
        if (len < 4 || len > MAX_MESSAGE) {
            in.resetReaderIndex();
            return;
        }
        if (in.readableBytes() < len - 4) {
            in.resetReaderIndex();
            return;
        }
        ByteBuf body = in.readSlice(len - 4);
        Object msg = parseRegular(type, body);
        if (msg != null) {
            startupReceived = true; // 已确认进入常规消息阶段（StartupMessage 只可能为首包）
            out.add(msg);
        }
    }

    private Object parseRegular(byte type, ByteBuf body) {
        switch (type) {
            case PgProtocol.MSG_QUERY:
                return new PgQueryMessage(readCString(body));
            case PgProtocol.MSG_PASSWORD_MESSAGE:
                return new PgPasswordMessage(readCString(body));
            case PgProtocol.MSG_TERMINATE:
                return new PgTerminateMessage();
            case PgProtocol.MSG_PARSE:
                return parseParse(body);
            case PgProtocol.MSG_BIND:
                return parseBind(body);
            case PgProtocol.MSG_EXECUTE:
                return new PgExecuteMessage(readCString(body), body.readInt());
            case PgProtocol.MSG_DESCRIBE:
                return new PgDescribeMessage(body.readByte(), readCString(body));
            case PgProtocol.MSG_SYNC:
                return new PgSyncMessage();
            case PgProtocol.MSG_FLUSH:
                return new PgFlushMessage();
            case PgProtocol.MSG_CLOSE:
                return new PgCloseMessage(body.readByte(), readCString(body));
            default:
                // 未识别消息类型：忽略（不产出）
                return null;
        }
    }

    private PgParseMessage parseParse(ByteBuf body) {
        String name = readCString(body);
        String query = readCString(body);
        int paramCount = body.readUnsignedShort();
        List<Integer> paramTypes = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            paramTypes.add(body.readInt());
        }
        return new PgParseMessage(name, query, paramTypes);
    }

    private PgBindMessage parseBind(ByteBuf body) {
        String portal = readCString(body);
        String statement = readCString(body);
        int paramFmtCount = body.readUnsignedShort();
        for (int i = 0; i < paramFmtCount; i++) {
            body.readShort();
        }
        int paramCount = body.readUnsignedShort();
        List<String> paramValues = new ArrayList<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            int paramLen = body.readInt();
            if (paramLen == -1) {
                paramValues.add(null);
            } else {
                byte[] bytes = new byte[paramLen];
                body.readBytes(bytes);
                paramValues.add(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        int resultFmtCount = body.readUnsignedShort();
        List<Integer> resultFormatCodes = new ArrayList<>(resultFmtCount);
        for (int i = 0; i < resultFmtCount; i++) {
            resultFormatCodes.add((int) body.readShort());
        }
        return new PgBindMessage(portal, statement, paramValues, resultFormatCodes);
    }

    private static Map<String, String> parseStartupParams(ByteBuf kv) {
        Map<String, String> params = new HashMap<>();
        while (kv.isReadable()) {
            String key = readCString(kv);
            if (key.isEmpty()) {
                break; // 双 null 终止
            }
            String value = readCString(kv);
            params.put(key, value);
        }
        return params;
    }

    private static String readCString(ByteBuf buf) {
        if (!buf.isReadable()) {
            return "";
        }
        int start = buf.readerIndex();
        int end = start;
        int max = buf.writerIndex();
        while (end < max && buf.getByte(end) != 0) {
            end++;
        }
        int len = end - start;
        String s = buf.toString(start, len, StandardCharsets.UTF_8);
        buf.readerIndex(end + 1); // 跳过内容 + 终止 null
        return s;
    }
}
