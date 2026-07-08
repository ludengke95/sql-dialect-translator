package com.translator.core.config;

import java.util.EnumMap;
import java.util.Map;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.*;

import com.translator.core.DialectType;

/**
 * SQL 方言工厂。
 * 根据 DialectType 获取 Calcite 对应的 SqlDialect 实例。
 */
public class SqlDialectFactory {

    private static final Map<DialectType, SqlDialect> DIALECT_MAP = new EnumMap<>(DialectType.class);

    static {
        DIALECT_MAP.put(DialectType.POSTGRESQL, PostgresqlSqlDialect.DEFAULT);
        DIALECT_MAP.put(DialectType.MYSQL, MysqlSqlDialect.DEFAULT);
        DIALECT_MAP.put(DialectType.ORACLE, OracleSqlDialect.DEFAULT);
        DIALECT_MAP.put(DialectType.SQLSERVER, MssqlSqlDialect.DEFAULT);
    }

    /**
     * 获取指定方言类型的 Calcite SqlDialect 实例。
     *
     * @param dialectType 方言类型
     * @return Calcite SqlDialect 实例
     * @throws IllegalArgumentException 如果不支持的方言类型
     */
    public static SqlDialect getDialect(DialectType dialectType) {
        SqlDialect dialect = DIALECT_MAP.get(dialectType);
        if (dialect == null) {
            throw new IllegalArgumentException("不支持的方言类型: " + dialectType);
        }
        return dialect;
    }

    /**
     * 判断两个方言类型是否相同。
     */
    public static boolean isSameDialect(DialectType source, DialectType target) {
        return source == target;
    }
}
