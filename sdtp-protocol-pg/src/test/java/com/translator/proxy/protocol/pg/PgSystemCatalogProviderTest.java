package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * PgSystemCatalogProvider 单元测试：pg_catalog 系统查询的合成应答。
 */
public class PgSystemCatalogProviderTest {

    @Test
    public void answersVersion() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT version()");
        assertNotNull(r);
        assertEquals(1, r.getColumns().size());
        assertEquals("version", r.getColumns().get(0));
        assertTrue(r.getRows().get(0).get(0).contains("PostgreSQL"));
    }

    @Test
    public void answersCurrentSchema() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("select current_schema()");
        assertNotNull(r);
        assertEquals("public", r.getRows().get(0).get(0));
    }

    @Test
    public void answersCurrentDatabase() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT current_database()");
        assertNotNull(r);
        assertEquals("current_database", r.getColumns().get(0));
    }

    @Test
    public void answersConstantOne() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT 1");
        assertNotNull(r);
        assertEquals("?column?", r.getColumns().get(0));
        assertEquals("1", r.getRows().get(0).get(0));
    }

    @Test
    public void answersPgTypeCatalog() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT oid, typname FROM pg_catalog.pg_type");
        assertNotNull(r);
        List<String> cols = r.getColumns();
        assertTrue(cols.contains("oid"));
        assertTrue(cols.contains("typname"));
        assertTrue(r.getRows().size() > 0);
    }

    @Test
    public void ignoresRealUserQuery() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT * FROM orders WHERE id = 1");
        assertNull(r);
    }

    @Test
    public void ignoresNonCatalogQuery() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("UPDATE accounts SET balance = 0");
        assertNull(r);
    }

    @Test
    public void trailingSemicolonIsIgnored() {
        PgSyntheticResult r = PgSystemCatalogProvider.answer("SELECT version();");
        assertNotNull(r);
        assertEquals("version", r.getColumns().get(0));
    }
}
