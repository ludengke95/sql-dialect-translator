package com.translator.proxy.core.handler;

import com.translator.proxy.core.intercept.SystemVariableInterceptor;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.util.BufferUtils;
import com.translator.proxy.protocol.util.MySQLAuth;
import com.translator.proxy.core.session.FrontendSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * CommandHandler 测试：系统变量拦截、SET 语句、COM_PING/QUIT 等。
 */
public class CommandHandlerTest {

    private EmbeddedChannel channel;
    private byte[] scramble;

    @Before
    public void setUp() {
        channel = new EmbeddedChannel(
                new MySQLPacketDecoder(),
                new MySQLPacketEncoder()
        );
        scramble = MySQLAuth.generateScramble();
    }

    // ==================== 辅助方法 ====================

    /** 发送 COM_QUERY 包到 channel */
    private void sendQuery(String sql, byte seq) {
        ByteBuf payload = Unpooled.buffer();
        payload.writeByte(CommandType.COM_QUERY);
        payload.writeBytes(sql.getBytes(StandardCharsets.UTF_8));

        ByteBuf packet = wrapPacket(payload, seq);
        channel.writeInbound(packet);
    }

    /** 发送 COM_PING 包 */
    private void sendPing(byte seq) {
        ByteBuf payload = Unpooled.buffer(1);
        payload.writeByte(CommandType.COM_PING);
        channel.writeInbound(wrapPacket(payload, seq));
    }

    /** 发送 COM_QUIT 包 */
    private void sendQuit(byte seq) {
        ByteBuf payload = Unpooled.buffer(1);
        payload.writeByte(CommandType.COM_QUIT);
        channel.writeInbound(wrapPacket(payload, seq));
    }

    /** 包装为完整 MySQL 包 */
    private ByteBuf wrapPacket(ByteBuf payload, byte seq) {
        int len = payload.readableBytes();
        ByteBuf pkt = Unpooled.buffer(4 + len);
        pkt.writeMediumLE(len);
        pkt.writeByte(seq);
        pkt.writeBytes(payload);
        payload.release();
        return pkt;
    }

    /** 读取 outbound 中的 OK/ERR 包头标识 */
    private int readResponseHeader() {
        ByteBuf raw = channel.readOutbound();
        if (raw == null) return -2; // no response
        raw.readUnsignedMediumLE(); // packet length
        raw.readByte();             // seq
        int header = raw.readUnsignedByte();
        raw.release();
        return header;
    }

    /** 读取 outbound 并跳过 n 个包，返回最后一个包的 payload header */
    private int readLastResponseHeader(int skipCount) {
        ByteBuf raw = null;
        for (int i = 0; i <= skipCount; i++) {
            raw = channel.readOutbound();
            if (raw == null) return -2;
            if (i < skipCount) {
                raw.release();
            }
        }
        if (raw == null) return -2;
        raw.readUnsignedMediumLE();
        raw.readByte();
        int h = raw.readUnsignedByte();
        raw.release();
        return h;
    }

    // ==================== 系统变量拦截测试 ====================

    @Test
    public void testSelectVersion() {
        // 设置 session
        FrontendSession session = FrontendSession.create(channel, 1, scramble);
        channel.attr(SessionAttribute.SESSION_KEY).set(session);

        // 直接测试拦截器
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@version_comment LIMIT 1", null);
        assertNotNull("应拦截 @@version_comment", ir);
        assertFalse("应为单列结果", ir.twoColumns);
        assertEquals("@@version_comment", ir.colName1);
    }

    @Test
    public void testSelectDatabase() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT DATABASE()", "testdb");
        assertNotNull("应拦截 SELECT DATABASE()", ir);
        assertEquals("testdb", ir.value1);
    }

    @Test
    public void testSelectDatabaseNull() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT DATABASE()", null);
        assertNotNull(ir);
        assertEquals("NULL", ir.value1);
    }

    @Test
    public void testShowVariablesLike() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SHOW VARIABLES LIKE 'version_comment'", null);
        assertNotNull("应拦截 SHOW VARIABLES LIKE", ir);
        assertTrue("应为双列结果", ir.twoColumns);
        assertEquals("version_comment", ir.value1);
    }

    @Test
    public void testNonSystemQueryNotIntercepted() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT * FROM users", null);
        assertNull("普通查询不应被拦截", ir);
    }

    @Test
    public void testIsSetStatement() {
        assertTrue(SystemVariableInterceptor.isSetStatement("SET NAMES utf8mb4"));
        assertTrue(SystemVariableInterceptor.isSetStatement("SET autocommit=1"));
        assertTrue(SystemVariableInterceptor.isSetStatement("SET @@session.autocommit = 1"));
        assertFalse(SystemVariableInterceptor.isSetStatement("SELECT 1"));
    }

    @Test
    public void testUseStatementExtraction() {
        assertEquals("testdb", SystemVariableInterceptor.extractUseDatabase("USE testdb"));
        assertEquals("mydb", SystemVariableInterceptor.extractUseDatabase("use `mydb`"));
        assertNull(SystemVariableInterceptor.extractUseDatabase("SELECT 1"));
    }

    // ==================== 未知系统变量 ====================

    @Test
    public void testUnknownSystemVariableNotIntercepted() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@nonexistent_var", null);
        assertNull("未知系统变量不应被拦截", ir);
    }

    @After
    public void tearDown() {
        channel.finish();
    }
}
