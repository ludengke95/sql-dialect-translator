package com.translator.core;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Set;

/**
 * DialectRegistry 方言注册表测试。
 */
public class DialectRegistryTest {

    @Test
    public void testGetDialectForProduct() {
        Assert.assertEquals(DialectType.POSTGRESQL, DialectRegistry.getDialectForProduct("PostgreSQL"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectRegistry.getDialectForProduct("postgresql"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectRegistry.getDialectForProduct("HighGo"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectRegistry.getDialectForProduct("KingbaseES"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectRegistry.getDialectForProduct("Greenplum"));

        Assert.assertEquals(DialectType.MYSQL, DialectRegistry.getDialectForProduct("MySQL"));
        Assert.assertEquals(DialectType.MYSQL, DialectRegistry.getDialectForProduct("Doris"));

        Assert.assertEquals(DialectType.ORACLE, DialectRegistry.getDialectForProduct("Oracle"));
        Assert.assertEquals(DialectType.ORACLE, DialectRegistry.getDialectForProduct("DM"));

        Assert.assertEquals(DialectType.SQLSERVER, DialectRegistry.getDialectForProduct("SQL Server"));
    }

    @Test
    public void testGetDialectForProductUnknown() {
        Assert.assertNull(DialectRegistry.getDialectForProduct("SQLite"));
        Assert.assertNull(DialectRegistry.getDialectForProduct("DB2"));
        Assert.assertNull(DialectRegistry.getDialectForProduct(null));
    }

    @Test
    public void testGetProductsForDialect() {
        List<String> pgProducts = DialectRegistry.getProductsForDialect(DialectType.POSTGRESQL);
        Assert.assertTrue(pgProducts.contains("PostgreSQL"));
        Assert.assertTrue(pgProducts.contains("HighGo"));
        Assert.assertTrue(pgProducts.contains("KingbaseES"));

        List<String> mysqlProducts = DialectRegistry.getProductsForDialect(DialectType.MYSQL);
        Assert.assertTrue(mysqlProducts.contains("MySQL"));
        Assert.assertTrue(mysqlProducts.contains("Doris"));
    }

    @Test
    public void testGetAllDialects() {
        Set<DialectType> dialects = DialectRegistry.getAllDialects();
        Assert.assertEquals(4, dialects.size());
        Assert.assertTrue(dialects.contains(DialectType.POSTGRESQL));
        Assert.assertTrue(dialects.contains(DialectType.MYSQL));
        Assert.assertTrue(dialects.contains(DialectType.ORACLE));
        Assert.assertTrue(dialects.contains(DialectType.SQLSERVER));
    }

    @Test
    public void testGetJdbcUrlPrefix() {
        // 默认前缀：jdbc:<子协议>:（用户地址部分自带 ://）
        Assert.assertEquals("jdbc:postgresql:", DialectRegistry.getJdbcUrlPrefix("postgresql"));
        Assert.assertEquals("jdbc:highgo:", DialectRegistry.getJdbcUrlPrefix("highgo"));
        Assert.assertEquals("jdbc:mysql:", DialectRegistry.getJdbcUrlPrefix("mysql"));
        Assert.assertEquals("jdbc:dm:", DialectRegistry.getJdbcUrlPrefix("dm"));
        // Oracle Thin 特殊前缀
        Assert.assertEquals("jdbc:oracle:thin:@", DialectRegistry.getJdbcUrlPrefix("oracle"));
        // null 兜底
        Assert.assertEquals("jdbc:", DialectRegistry.getJdbcUrlPrefix(null));
    }
}
