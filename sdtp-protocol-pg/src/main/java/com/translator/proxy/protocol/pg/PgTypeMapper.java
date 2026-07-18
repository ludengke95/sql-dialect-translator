package com.translator.proxy.protocol.pg;

import java.sql.Types;

/**
 * JDBC 类型 ↔ PostgreSQL OID 映射。
 *
 * <p>SDT Proxy 统一走文本协议（与 MySQL 文本协议一致），因此所有值以字符串形式
 * 在客户端与后端之间传递；本类仅用于构造 RowDescription 中的类型元数据，
 * 让 psql / libpq 等客户端能正确解释列类型。
 */
public final class PgTypeMapper {

    private PgTypeMapper() {}

    // ==================== 常用 OID ====================
    public static final int OID_BOOL = 16;
    public static final int OID_BYTEA = 17;
    public static final int OID_INT2 = 21;
    public static final int OID_INT4 = 23;
    public static final int OID_INT8 = 20;
    public static final int OID_TEXT = 25;
    public static final int OID_BPCHAR = 1042;
    public static final int OID_VARCHAR = 1043;
    public static final int OID_DATE = 1082;
    public static final int OID_TIME = 1083;
    public static final int OID_TIMESTAMP = 1114;
    public static final int OID_TIMESTAMPTZ = 1184;
    public static final int OID_FLOAT4 = 700;
    public static final int OID_FLOAT8 = 701;
    public static final int OID_NUMERIC = 1700;

    /**
     * JDBC 类型 → PG OID。未知类型回退到 text(25)。
     */
    public static int jdbcToOid(int jdbcType) {
        switch (jdbcType) {
            case Types.BOOLEAN:
            case Types.BIT:
                return OID_BOOL;
            case Types.TINYINT:
            case Types.SMALLINT:
                return OID_INT2;
            case Types.INTEGER:
                return OID_INT4;
            case Types.BIGINT:
                return OID_INT8;
            case Types.REAL:
            case Types.FLOAT:
                return OID_FLOAT4;
            case Types.DOUBLE:
                return OID_FLOAT8;
            case Types.NUMERIC:
            case Types.DECIMAL:
                return OID_NUMERIC;
            case Types.CHAR:
                return OID_BPCHAR;
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return OID_VARCHAR;
            case Types.CLOB:
                return OID_TEXT;
            case Types.DATE:
                return OID_DATE;
            case Types.TIME:
                return OID_TIME;
            case Types.TIMESTAMP:
                return OID_TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return OID_TIMESTAMPTZ;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return OID_BYTEA;
            case Types.NULL:
            case Types.OTHER:
            default:
                return OID_TEXT;
        }
    }

    /**
     * JDBC 类型 → PG 列类型元数据（使用默认长度/修饰符）。
     */
    public static PgColumnType jdbcToPg(int jdbcType) {
        int oid = jdbcToOid(jdbcType);
        switch (oid) {
            case OID_BOOL:
                return new PgColumnType(oid, "bool", 1, -1);
            case OID_INT2:
                return new PgColumnType(oid, "int2", 2, -1);
            case OID_INT4:
                return new PgColumnType(oid, "int4", 4, -1);
            case OID_INT8:
                return new PgColumnType(oid, "int8", 8, -1);
            case OID_FLOAT4:
                return new PgColumnType(oid, "float4", 4, -1);
            case OID_FLOAT8:
                return new PgColumnType(oid, "float8", 8, -1);
            case OID_NUMERIC:
                return new PgColumnType(oid, "numeric", -1, -1);
            case OID_BPCHAR:
                return new PgColumnType(oid, "bpchar", -1, -1);
            case OID_VARCHAR:
                return new PgColumnType(oid, "varchar", -1, -1);
            case OID_DATE:
                return new PgColumnType(oid, "date", 4, -1);
            case OID_TIME:
                return new PgColumnType(oid, "time", 8, -1);
            case OID_TIMESTAMP:
                return new PgColumnType(oid, "timestamp", 8, -1);
            case OID_TIMESTAMPTZ:
                return new PgColumnType(oid, "timestamptz", 8, -1);
            case OID_BYTEA:
                return new PgColumnType(oid, "bytea", -1, -1);
            case OID_TEXT:
            default:
                return new PgColumnType(OID_TEXT, "text", -1, -1);
        }
    }

    /**
     * OID → 类型名称。未知 OID 回退到 text。
     */
    public static String oidToName(int oid) {
        switch (oid) {
            case OID_BOOL:
                return "bool";
            case OID_BYTEA:
                return "bytea";
            case OID_INT2:
                return "int2";
            case OID_INT4:
                return "int4";
            case OID_INT8:
                return "int8";
            case OID_FLOAT4:
                return "float4";
            case OID_FLOAT8:
                return "float8";
            case OID_NUMERIC:
                return "numeric";
            case OID_BPCHAR:
                return "bpchar";
            case OID_VARCHAR:
                return "varchar";
            case OID_DATE:
                return "date";
            case OID_TIME:
                return "time";
            case OID_TIMESTAMP:
                return "timestamp";
            case OID_TIMESTAMPTZ:
                return "timestamptz";
            case OID_TEXT:
            default:
                return "text";
        }
    }
}
