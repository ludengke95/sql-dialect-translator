package com.translator.core.postprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * PostgreSQL 目标方言 SQL 后处理器。
 * 负责将时间运算算子与字符串字面量加法转换为 PostgreSQL 兼容的 INTERVAL 'N UNIT'。
 */
public class PostgresTargetPostProcessor implements TargetDialectPostProcessor {

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.POSTGRESQL);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // DATE_ADD/SUBDATE 改写后形如 CAST('...' AS TIMESTAMP) + 'N UNIT'，
        // PostgreSQL 不支持 timestamp 与字符串字面量直接相加，必须把右侧字符串包装为 INTERVAL 字面量
        return sql.replaceAll(
                "\\+\\s*'(-?\\d+\\s+(?:DAYS|MONTHS|YEARS|HOURS|MINUTES|SECONDS))'", "+ INTERVAL '$1'");
    }
}
