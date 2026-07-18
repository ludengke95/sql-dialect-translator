package com.translator.proxy.protocol.pg;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * PostgreSQL 前端单元测试公共辅助。
 *
 * <p>核心思路：在 pipeline 中放置一个 {@link CaptureHandler} 直接拦截
 * {@link PgOutbound} 出站消息（不经过 PgMessageEncoder 长度帧封装，隔离测试
 * 编解码器与写出逻辑），再经 {@link #toFrames(List)} 解析为可读的帧。
 *
 * <p>同时提供：
 * <ul>
 *   <li>{@link #fakeResultSet(String[], int[], List)}：用 JDK 动态代理伪造 ResultSet（无需数据库）；</li>
 *   <li>字节解析助手 {@code readShort}/{@code readInt}/{@code readCString}/{@code readLengthPrefixed}。</li>
 * </ul>
 */
final class PgTestSupport {

    private PgTestSupport() {}

    /** 单条已解析的 PG 后端消息帧（类型字节 + body；raw 消息 type=0）。 */
    static final class Frame {
        final byte type;
        final byte[] body;

        Frame(byte type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    /** 拦截并保存所有 PgOutbound 出站消息的捕获处理器（不向下传递，避免编码/释放干扰）。 */
    static final class CaptureHandler extends ChannelOutboundHandlerAdapter {
        final List<PgOutbound> captured = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof PgOutbound) {
                captured.add((PgOutbound) msg);
            }
            promise.setSuccess();
        }
    }

    /**
     * 驱动 EmbeddedChannel 执行所有被推迟的出站写任务（{@code ctx.write} 在测试线程上会被延迟到事件循环），
     * 再解析已捕获的帧。必须在读取 {@link CaptureHandler#captured} 前调用，否则非 flush 的写可能尚未被拦截。
     */
    static List<Frame> drain(io.netty.channel.embedded.EmbeddedChannel ch, CaptureHandler cap) {
        ch.runPendingTasks();
        return toFrames(cap.captured);
    }

    /** 将捕获的 PgOutbound 列表解析为帧（body 为去除类型字节后的内容；raw 消息 body 为原始字节）。 */
    static List<Frame> toFrames(List<PgOutbound> outbounds) {
        List<Frame> frames = new ArrayList<>();
        for (PgOutbound o : outbounds) {
            ByteBuf c = o.content;
            byte type;
            byte[] body;
            if (o.raw) {
                type = 0;
                body = new byte[c.readableBytes()];
                c.readBytes(body);
            } else {
                type = c.readByte();
                int len = c.readableBytes();
                body = new byte[len];
                c.readBytes(body);
            }
            frames.add(new Frame(type, body));
            c.release();
        }
        return frames;
    }

    /** 用列定义 + 行数据构造一个最小化的 {@link ResultSet}（动态代理，无需数据库）。 */
    static ResultSet fakeResultSet(String[] columns, int[] jdbcTypes, List<List<String>> rows) {
        InvocationHandler metaHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getColumnCount":
                    return columns.length;
                case "getColumnLabel":
                case "getColumnName":
                    return columns[(int) args[0] - 1];
                case "getColumnType":
                    return jdbcTypes[(int) args[0] - 1];
                default:
                    throw new UnsupportedOperationException("meta:" + method.getName());
            }
        };
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                PgTestSupport.class.getClassLoader(),
                new Class[] {ResultSetMetaData.class},
                metaHandler);

        AtomicInteger idx = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean(false);
        InvocationHandler rsHandler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getMetaData":
                    return meta;
                case "next":
                    idx.incrementAndGet();
                    return idx.get() < rows.size();
                case "getString": {
                    List<String> row = rows.get(idx.get());
                    String v = row.get((int) args[0] - 1);
                    wasNull.set(v == null);
                    return v;
                }
                case "wasNull":
                    return wasNull.get();
                default:
                    throw new UnsupportedOperationException("rs:" + method.getName());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
                PgTestSupport.class.getClassLoader(),
                new Class[] {ResultSet.class},
                rsHandler);
    }

    static int readShort(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    /** 读取 NUL 结尾的 C 字符串（类型 'S'/'C' 等字段、RowDescription 标签）。 */
    static String readCString(byte[] b, int off) {
        int i = off;
        while (i < b.length && b[i] != 0) {
            i++;
        }
        return new String(b, off, i - off, StandardCharsets.UTF_8);
    }

    /** 读取 4 字节长度前缀的字符串（DataRow 单元格）。 */
    static String readLengthPrefixed(byte[] b, int off) {
        int len = readInt(b, off);
        return new String(b, off + 4, len, StandardCharsets.UTF_8);
    }
}
