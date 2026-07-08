package com.translator.proxy.core.handler;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.translator.proxy.core.intercept.SystemVariableInterceptor;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.constant.CommandType;
import com.translator.proxy.protocol.util.MySQLAuth;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * CommandHandler 测试：系统变量拦截、SET 语句、COM_PING/QUIT 等。
 */
public class CommandHandlerTest {

    private EmbeddedChannel channel;
    private byte[] scramble;

    @Before
    public void setUp() {
        channel = new EmbeddedChannel(new MySQLPacketDecoder(), new MySQLPacketEncoder());
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
        raw.readByte(); // seq
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
        SystemVariableInterceptor.InterceptResult ir = SystemVariableInterceptor.intercept("SELECT DATABASE()", null);
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
        SystemVariableInterceptor.InterceptResult ir = SystemVariableInterceptor.intercept("SELECT * FROM users", null);
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

    // ==================== 多列系统变量查询 ====================

    /**
     * 测试单变量带 @@session. 前缀 —— 应返回单列结果（非多列模式）。
     */
    @Test
    public void testSelectSessionVariableWithPrefix() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@session.autocommit", null);
        assertNotNull("应拦截 @@session.autocommit", ir);
        // 单变量查询应返回单列结果，不是多列模式
        assertFalse("应为单列结果", ir.isMultiColumn());
        assertEquals("@@autocommit", ir.colName1);
        assertEquals("1", ir.value1);
    }

    /**
     * 测试单变量带 @@session. 前缀但未知变量 —— 应不拦截。
     */
    @Test
    public void testSelectSessionUnknownVariableNotIntercepted() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@session.nonexistent_var", null);
        assertNull("未知系统变量不应被拦截", ir);
    }

    /**
     * 测试双变量查询。
     */
    @Test
    public void testSelectTwoVariables() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@version, @@autocommit", null);
        assertNotNull("应拦截多变量查询", ir);
        assertTrue("应为多列模式", ir.isMultiColumn());
        assertEquals(2, ir.columns.size());
        assertEquals("version", ir.columns.get(0).columnName);
        assertEquals("autocommit", ir.columns.get(1).columnName);
    }

    /**
     * 测试完整的多列查询（模拟 MySQL Connector/J 8.0.33 的 SQL）。
     */
    @Test
    public void testSelectMultiVariablesConnectorJ80() {
        // 模拟 MySQL Connector/J 8.0.33 在连接建立时发送的查询
        String sql = "SELECT @@session.auto_increment_increment AS auto_increment_increment, "
                + "@@character_set_client AS character_set_client, "
                + "@@character_set_connection AS character_set_connection, "
                + "@@character_set_results AS character_set_results, "
                + "@@character_set_server AS character_set_server";

        SystemVariableInterceptor.InterceptResult ir = SystemVariableInterceptor.intercept(sql, null);
        assertNotNull("应拦截 Connector/J 多变量查询", ir);
        assertTrue("应为多列模式", ir.isMultiColumn());
        assertEquals(5, ir.columns.size());
        assertEquals("auto_increment_increment", ir.columns.get(0).columnName);
        assertEquals("1", ir.columns.get(0).value);
        assertEquals("character_set_client", ir.columns.get(1).columnName);
        assertEquals("utf8mb4", ir.columns.get(1).value);
    }

    /**
     * 测试未知系统变量返回空值（多列查询中）。
     */
    @Test
    public void testMultiVariablesUnknownVarReturnsEmpty() {
        SystemVariableInterceptor.InterceptResult ir =
                SystemVariableInterceptor.intercept("SELECT @@version, @@unknown_var_xyz", null);
        assertNotNull("应拦截查询", ir);
        assertTrue("应为多列模式", ir.isMultiColumn());
        assertEquals(2, ir.columns.size());
        assertEquals("version", ir.columns.get(0).columnName);
        assertEquals("unknown_var_xyz", ir.columns.get(1).columnName);
        assertEquals("", ir.columns.get(1).value); // 未知变量返回空字符串
    }

    /**
     * 测试混合前缀（部分 @@session.，部分 @@）。
     */
    @Test
    public void testMixedPrefixVariables() {
        String sql = "SELECT @@session.version, @@autocommit";
        SystemVariableInterceptor.InterceptResult ir = SystemVariableInterceptor.intercept(sql, null);
        assertNotNull("应拦截混合前缀查询", ir);
        assertTrue("应为多列模式", ir.isMultiColumn());
        assertEquals(2, ir.columns.size());
        // session. 前缀应被标准化移除
        assertEquals("version", ir.columns.get(0).columnName);
        assertEquals("autocommit", ir.columns.get(1).columnName);
    }

    /**
     * 测试新增的系统变量（auto_increment_increment 等）。
     */
    @Test
    public void testNewSystemVariables() {
        // 测试 auto_increment_increment
        SystemVariableInterceptor.InterceptResult ir1 =
                SystemVariableInterceptor.intercept("SELECT @@auto_increment_increment", null);
        assertNotNull("应拦截 auto_increment_increment", ir1);
        assertEquals("1", ir1.value1);

        // 测试多列包含新增变量
        String sql = "SELECT @@auto_increment_increment, @@net_write_timeout, @@time_zone";
        SystemVariableInterceptor.InterceptResult ir2 = SystemVariableInterceptor.intercept(sql, null);
        assertNotNull("应拦截多列查询", ir2);
        assertTrue("应为多列模式", ir2.isMultiColumn());
        assertEquals(3, ir2.columns.size());
        assertEquals("1", ir2.columns.get(0).value); // auto_increment_increment
        assertEquals("60", ir2.columns.get(1).value); // net_write_timeout
        assertEquals("SYSTEM", ir2.columns.get(2).value); // time_zone
    }

    @After
    public void tearDown() {
        channel.finish();
    }
}
