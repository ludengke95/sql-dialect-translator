package com.translator.jdbc;

import com.translator.core.DialectType;
import org.junit.Assert;
import org.junit.Test;

import java.util.Properties;

/**
 * JdbcUrlParser 测试。
 * URL 格式：jdbc:translator:<源方言>:<目标子协议>:<真实地址>
 * 真实地址由用户携带 :// 或 Oracle 的其他格式
 */
public class JdbcUrlParserTest {

    @Test
    public void testParseMySqlToPg() {
        // 用户地址部分自带 ://
        String url = "jdbc:translator:mysql:postgresql://localhost:5432/mydb";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.POSTGRESQL, info.getTargetDialect());
        Assert.assertEquals("jdbc:postgresql://localhost:5432/mydb", info.getRealUrl());
    }

    @Test
    public void testParseMySqlToHighGo() {
        String url = "jdbc:translator:mysql:highgo://localhost:5866/highgo";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.POSTGRESQL, info.getTargetDialect());
        Assert.assertEquals("jdbc:highgo://localhost:5866/highgo", info.getRealUrl());
    }

    @Test
    public void testParseOracleToMySql() {
        String url = "jdbc:translator:oracle:mysql://localhost:3306/mydb";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.ORACLE, info.getSourceDialect());
        Assert.assertEquals(DialectType.MYSQL, info.getTargetDialect());
        Assert.assertEquals("jdbc:mysql://localhost:3306/mydb", info.getRealUrl());
    }

    @Test
    public void testParseMySqlToDm() {
        // DM/达梦 属于 Oracle 方言组
        String url = "jdbc:translator:mysql:dm://localhost:5236/mydb";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.ORACLE, info.getTargetDialect());
        Assert.assertEquals("jdbc:dm://localhost:5236/mydb", info.getRealUrl());
    }

    // ==================== Oracle Thin 四种格式 ====================

    @Test
    public void testParseMySqlToOracleThinServiceName() {
        // Service Name 格式（推荐）：oracle://host:1521/XE
        String url = "jdbc:translator:mysql:oracle://localhost:1521/XE";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.ORACLE, info.getTargetDialect());
        // realUrl = jdbc:oracle:thin:@ + //localhost:1521/XE
        Assert.assertEquals("jdbc:oracle:thin:@//localhost:1521/XE", info.getRealUrl());
    }

    @Test
    public void testParseMySqlToOracleThinSid() {
        // SID 格式（旧式）：oracle:host:1521:orcl
        // @ 已在前缀 jdbc:oracle:thin:@ 中，地址部分不带 @
        String url = "jdbc:translator:mysql:oracle:localhost:1521:ORCL";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.ORACLE, info.getTargetDialect());
        Assert.assertEquals("jdbc:oracle:thin:@localhost:1521:ORCL", info.getRealUrl());
    }

    @Test
    public void testParseMySqlToOracleThinTnsAlias() {
        // TNS Alias 格式：oracle:TNSName
        String url = "jdbc:translator:mysql:oracle:GL";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.ORACLE, info.getTargetDialect());
        Assert.assertEquals("jdbc:oracle:thin:@GL", info.getRealUrl());
    }

    @Test
    public void testParseMySqlToOracleThinTnsDescriptor() {
        // TNS Descriptor 格式：oracle:(DESCRIPTION=...)
        String url = "jdbc:translator:mysql:oracle:(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=192.168.1.1)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=orcl)))";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.MYSQL, info.getSourceDialect());
        Assert.assertEquals(DialectType.ORACLE, info.getTargetDialect());
        Assert.assertEquals("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=192.168.1.1)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=orcl)))",
                info.getRealUrl());
    }

    @Test
    public void testParseOracleToPg() {
        String url = "jdbc:translator:oracle:postgresql://localhost:5432/mydb";
        JdbcUrlInfo info = JdbcUrlParser.parse(url, new Properties());

        Assert.assertEquals(DialectType.ORACLE, info.getSourceDialect());
        Assert.assertEquals(DialectType.POSTGRESQL, info.getTargetDialect());
        Assert.assertEquals("jdbc:postgresql://localhost:5432/mydb", info.getRealUrl());
    }

    // ==================== acceptsUrl / 异常测试 ====================

    @Test
    public void testAcceptsUrl() {
        Assert.assertTrue(JdbcUrlParser.acceptsUrl("jdbc:translator:mysql:postgresql://localhost:5432/db"));
        Assert.assertFalse(JdbcUrlParser.acceptsUrl("jdbc:mysql://localhost:3306/db"));
        Assert.assertFalse(JdbcUrlParser.acceptsUrl(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidUrlNoPrefix() {
        JdbcUrlParser.parse("jdbc:mysql://localhost:3306/db", new Properties());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidUrlFormat() {
        // 缺少目标子协议和地址
        JdbcUrlParser.parse("jdbc:translator:invalid", new Properties());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownTargetProduct() {
        // 目标数据库子协议未在 DialectRegistry 中注册
        JdbcUrlParser.parse("jdbc:translator:mysql:unknown://localhost:5432/db", new Properties());
    }
}
