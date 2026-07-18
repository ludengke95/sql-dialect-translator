package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;

import java.sql.Types;

import org.junit.Test;

/**
 * PgTypeMapper 单元测试：JDBC 类型 ↔ PostgreSQL OID 映射。
 */
public class PgTypeMapperTest {

    @Test
    public void jdbcIntegerMapsToInt4() {
        assertEquals(PgTypeMapper.OID_INT4, PgTypeMapper.jdbcToOid(Types.INTEGER));
    }

    @Test
    public void jdbcBigintMapsToInt8() {
        assertEquals(PgTypeMapper.OID_INT8, PgTypeMapper.jdbcToOid(Types.BIGINT));
    }

    @Test
    public void jdbcSmallintMapsToInt2() {
        assertEquals(PgTypeMapper.OID_INT2, PgTypeMapper.jdbcToOid(Types.SMALLINT));
    }

    @Test
    public void jdbcVarcharMapsToVarchar() {
        assertEquals(PgTypeMapper.OID_VARCHAR, PgTypeMapper.jdbcToOid(Types.VARCHAR));
    }

    @Test
    public void jdbcCharMapsToBpchar() {
        assertEquals(PgTypeMapper.OID_BPCHAR, PgTypeMapper.jdbcToOid(Types.CHAR));
    }

    @Test
    public void jdbcTimestampMapsToTimestamp() {
        assertEquals(PgTypeMapper.OID_TIMESTAMP, PgTypeMapper.jdbcToOid(Types.TIMESTAMP));
    }

    @Test
    public void jdbcTimestampWithTzMapsToTimestamptz() {
        assertEquals(PgTypeMapper.OID_TIMESTAMPTZ, PgTypeMapper.jdbcToOid(Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    public void jdbcDateMapsToDate() {
        assertEquals(PgTypeMapper.OID_DATE, PgTypeMapper.jdbcToOid(Types.DATE));
    }

    @Test
    public void jdbcBooleanMapsToBool() {
        assertEquals(PgTypeMapper.OID_BOOL, PgTypeMapper.jdbcToOid(Types.BOOLEAN));
    }

    @Test
    public void jdbcDoubleMapsToFloat8() {
        assertEquals(PgTypeMapper.OID_FLOAT8, PgTypeMapper.jdbcToOid(Types.DOUBLE));
    }

    @Test
    public void jdbcFloatMapsToFloat4() {
        assertEquals(PgTypeMapper.OID_FLOAT4, PgTypeMapper.jdbcToOid(Types.FLOAT));
    }

    @Test
    public void jdbcDecimalAndNumericMapToNumeric() {
        assertEquals(PgTypeMapper.OID_NUMERIC, PgTypeMapper.jdbcToOid(Types.DECIMAL));
        assertEquals(PgTypeMapper.OID_NUMERIC, PgTypeMapper.jdbcToOid(Types.NUMERIC));
    }

    @Test
    public void jdbcBlobMapsToBytea() {
        assertEquals(PgTypeMapper.OID_BYTEA, PgTypeMapper.jdbcToOid(Types.BLOB));
    }

    @Test
    public void unknownJdbcTypeFallsBackToText() {
        assertEquals(PgTypeMapper.OID_TEXT, PgTypeMapper.jdbcToOid(Types.OTHER));
        assertEquals(PgTypeMapper.OID_TEXT, PgTypeMapper.jdbcToOid(99999));
    }

    @Test
    public void oidToNameRoundTripsCommonTypes() {
        assertEquals("int4", PgTypeMapper.oidToName(PgTypeMapper.OID_INT4));
        assertEquals("int8", PgTypeMapper.oidToName(PgTypeMapper.OID_INT8));
        assertEquals("varchar", PgTypeMapper.oidToName(PgTypeMapper.OID_VARCHAR));
        assertEquals("bool", PgTypeMapper.oidToName(PgTypeMapper.OID_BOOL));
        assertEquals("text", PgTypeMapper.oidToName(PgTypeMapper.OID_TEXT));
    }

    @Test
    public void jdbcToPgReturnsColumnMetadata() {
        PgColumnType ct = PgTypeMapper.jdbcToPg(Types.INTEGER);
        assertEquals(PgTypeMapper.OID_INT4, ct.oid);
        assertEquals("int4", ct.name);
        assertEquals(4, ct.typeLen);
    }
}
