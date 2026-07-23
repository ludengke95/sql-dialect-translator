package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将 MySQL 特有的多表更新语法
 * 1. `UPDATE t1 INNER/LEFT JOIN t2 ON cond SET assignments`
 * 2. `UPDATE t1, t2 SET assignments`
 * 预处理重构为 ANSI / PostgreSQL 规范的 `UPDATE t1 SET assignments FROM t2 WHERE cond` 形式。
 */
public class UpdateJoinSyntaxPreProcessor implements SourceDialectPreProcessor {

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 1. UPDATE t1 JOIN t2 ON cond SET assignments -> UPDATE t1 SET assignments FROM t2 WHERE cond
        String result = sql.replaceAll(
                "(?i)\\bUPDATE\\s+([a-zA-Z0-9_\"`]+)\\s+(?:AS\\s+([a-zA-Z0-9_\"`]+)\\s+)?(?:INNER|LEFT|RIGHT|CROSS)?\\s*JOIN\\s+([a-zA-Z0-9_\"`]+)\\s+(?:AS\\s+([a-zA-Z0-9_\"`]+)\\s+)?ON\\s+(.+?)\\s+SET\\s+(.+)",
                "UPDATE $1 SET $6 FROM $3 WHERE $5");

        // 2. UPDATE t1, t2 SET assignments -> UPDATE t1 SET assignments FROM t2
        result = result.replaceAll(
                "(?i)\\bUPDATE\\s+([a-zA-Z0-9_\"`]+)\\s*,\\s*([a-zA-Z0-9_\"`]+)\\s+SET\\s+(.+)",
                "UPDATE $1 SET $3 FROM $2");

        return result;
    }
}
