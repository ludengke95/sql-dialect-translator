package com.translator.core;

import org.junit.Assert;
import org.junit.Test;

/**
 * DialectType 枚举测试。
 */
public class DialectTypeTest {

    @Test
    public void testFromIdentifier() {
        Assert.assertEquals(DialectType.POSTGRESQL, DialectType.fromIdentifier("postgresql"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectType.fromIdentifier("PostgreSQL"));
        Assert.assertEquals(DialectType.POSTGRESQL, DialectType.fromIdentifier("POSTGRESQL"));

        Assert.assertEquals(DialectType.MYSQL, DialectType.fromIdentifier("mysql"));
        Assert.assertEquals(DialectType.MYSQL, DialectType.fromIdentifier("MySQL"));

        Assert.assertEquals(DialectType.ORACLE, DialectType.fromIdentifier("oracle"));
        Assert.assertEquals(DialectType.SQLSERVER, DialectType.fromIdentifier("sqlserver"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromIdentifierInvalid() {
        DialectType.fromIdentifier("unknown");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromIdentifierNull() {
        DialectType.fromIdentifier(null);
    }

    @Test
    public void testGetIdentifier() {
        Assert.assertEquals("postgresql", DialectType.POSTGRESQL.getIdentifier());
        Assert.assertEquals("mysql", DialectType.MYSQL.getIdentifier());
        Assert.assertEquals("oracle", DialectType.ORACLE.getIdentifier());
        Assert.assertEquals("sqlserver", DialectType.SQLSERVER.getIdentifier());
    }
}
