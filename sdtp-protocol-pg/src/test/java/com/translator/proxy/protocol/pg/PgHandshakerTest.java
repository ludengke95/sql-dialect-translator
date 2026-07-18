package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

/**
 * {@link PgHandshaker} 单元测试：SSL 协商、MD5 认证成功/失败流程。
 */
public class PgHandshakerTest {

    /** 返回一个仅记录调用、不做真实后端交互的路由/处理器。 */
    private BackendRouter fakeRouter() {
        return session -> CommandHandler.QueryProcessor.NOOP;
    }

    private EmbeddedChannel newChannel(PgTestSupport.CaptureHandler[] outCap) {
        EmbeddedChannel ch = new EmbeddedChannel();
        PgTestSupport.CaptureHandler cap = new PgTestSupport.CaptureHandler();
        ch.pipeline().addFirst("capture", cap);
        ch.pipeline().addLast("handshaker", new PgHandshaker("postgres", "secret", null, fakeRouter()));
        outCap[0] = cap;
        // EmbeddedChannel 不会自动触发 channelActive，手动触发以建立 FrontendSession（与真实服务端一致）
        ch.pipeline().fireChannelActive();
        return ch;
    }

    @Test
    public void respondsNoSslToSslRequest() {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        EmbeddedChannel ch = newChannel(cap);
        ch.writeInbound(new PgSslRequest());

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(1, frames.size());
        // raw 消息 type=0，body 为原始字节
        assertEquals(0, frames.get(0).type);
        assertEquals('N', frames.get(0).body[0]);
    }

    @Test
    public void md5AuthSuccessSendsParameterStatusAndReadyForQuery() {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        EmbeddedChannel ch = newChannel(cap);

        Map<String, String> params = new HashMap<>();
        params.put("user", "postgres");
        params.put("database", "testdb");
        ch.writeInbound(new PgStartupMessage(params));

        // 首帧 AuthenticationMD5Password 的 body = [type][int32 subtype(5)][4 字节 salt]
        // 用 getBytes（不推进 readerIndex）读取，避免提前释放缓冲
        byte[] authBody = peekAuthBody(cap[0]);
        assertEquals(PgProtocol.MSG_AUTH_REQUEST, authBody[0]);
        assertEquals(PgProtocol.AUTH_MD5, PgTestSupport.readInt(authBody, 1));
        byte[] salt = new byte[4];
        System.arraycopy(authBody, 5, salt, 0, 4);

        // 用服务端下发的 salt 计算客户端应发送的 MD5 token
        String token = PgAuth.md5Password("postgres", "secret", salt);
        ch.writeInbound(new PgPasswordMessage(token));

        // 一次性解析全部出站帧（首帧 AuthRequest + 8 ParameterStatus + 1 BackendKeyData + 1 ReadyForQuery）
        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        assertEquals(11, frames.size());

        long paramStatusCount = frames.stream().filter(f -> f.type == PgProtocol.MSG_PARAMETER_STATUS).count();
        assertEquals(8, paramStatusCount);
        assertEquals(1, frames.stream().filter(f -> f.type == PgProtocol.MSG_BACKEND_KEY_DATA).count());

        PgTestSupport.Frame last = frames.get(frames.size() - 1);
        assertEquals(PgProtocol.MSG_READY_FOR_QUERY, last.type);
        assertEquals(PgProtocol.TX_IDLE, last.body[0]);
    }

    @Test
    public void md5AuthFailureSendsErrorResponse() {
        PgTestSupport.CaptureHandler[] cap = new PgTestSupport.CaptureHandler[1];
        EmbeddedChannel ch = newChannel(cap);

        Map<String, String> params = new HashMap<>();
        params.put("user", "postgres");
        ch.writeInbound(new PgStartupMessage(params));

        // 发送错误的口令；认证失败后服务端会关闭连接，EmbeddedChannel 可能在关闭后
        // 于 writeInbound 抛出 ClosedChannelException，但错误帧已在关闭前被捕获处理器同步记录
        try {
            ch.writeInbound(new PgPasswordMessage("md5deadbeef"));
        } catch (Exception ignored) {
            // 连接已关闭，忽略
        }

        List<PgTestSupport.Frame> frames = PgTestSupport.drain(ch, cap[0]);
        boolean hasError = frames.stream().anyMatch(f -> f.type == PgProtocol.MSG_ERROR_RESPONSE
                && new String(f.body, java.nio.charset.StandardCharsets.UTF_8).contains("28P01"));
        assertTrue("expected ErrorResponse with 28P01", hasError);
    }

    /** 不推进 readerIndex 地读取首帧完整 body（含类型字节）。 */
    private static byte[] peekAuthBody(PgTestSupport.CaptureHandler cap) {
        ByteBuf c = cap.captured.get(0).content;
        byte[] body = new byte[c.readableBytes()];
        c.getBytes(0, body);
        return body;
    }
}
