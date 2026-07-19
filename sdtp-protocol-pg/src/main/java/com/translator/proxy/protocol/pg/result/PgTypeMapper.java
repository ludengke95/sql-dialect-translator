package com.translator.proxy.protocol.pg.result;

import java.sql.Types;

import com.translator.proxy.protocol.frontend.TypeMapper;
import com.translator.proxy.protocol.pg.result.PgOid;

/**
 * PostgreSQL 类型映射器 —— 将 JDBC 类型映射为 PG OID。
 *
 * <p>实现 {@link TypeMapper} 接口，用于 RowDescription 消息中的列类型 OID 字段。
 */
public class PgTypeMapper implements TypeMapper {

    @Override
    public int jdbcToProtocolType(int jdbcType, String typeName) {
        return jdbcToPgOid(jdbcType, typeName);
    }

    /**
     * 将 JDBC 类型映射为 PG OID。
     */
    public static int jdbcToPgOid(int jdbcType, String typeName) {
        // 优先根据类型名称判断
        if (typeName != null) {
            String upper = typeName.toUpperCase();
            if (upper.contains("JSONB")) return PgOid.JSONB;
            if (upper.contains("JSON")) return PgOid.JSON;
            if (upper.contains("UUID")) return PgOid.UUID;
            if (upper.contains("TIMESTAMP") && upper.contains("TIME ZONE")) return PgOid.TIMESTAMPTZ;
        }

        switch (jdbcType) {
            case Types.BOOLEAN:
            case Types.BIT:
                return PgOid.BOOL;

            case Types.TINYINT:
            case Types.SMALLINT:
                return PgOid.INT2;

            case Types.INTEGER:
                return PgOid.INT4;

            case Types.BIGINT:
                return PgOid.INT8;

            case Types.REAL:
            case Types.FLOAT:
                return PgOid.FLOAT4;

            case Types.DOUBLE:
                return PgOid.FLOAT8;

            case Types.DECIMAL:
            case Types.NUMERIC:
                return PgOid.NUMERIC;

            case Types.CHAR:
                return PgOid.CHAR;

            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
                return PgOid.VARCHAR;

            case Types.DATE:
                return PgOid.DATE;

            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return PgOid.TIME;

            case Types.TIMESTAMP:
                return PgOid.TIMESTAMP;

            case Types.TIMESTAMP_WITH_TIMEZONE:
                return PgOid.TIMESTAMPTZ;

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return PgOid.BYTEA;

            case Types.NULL:
                return PgOid.TEXT;

            default:
                return PgOid.TEXT;
        }
    }
}
