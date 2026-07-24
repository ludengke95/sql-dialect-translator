package com.translator.core.preprocessor;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将源 SQL 中的 INTERVAL 表达式（如 `INTERVAL '90 DAY'` 或 `INTERVAL 90 DAY`）
 * 统一规整为 Calcite Parser 规范的 `INTERVAL '90' DAY` 形式。
 */
public class IntervalSyntaxPreProcessor implements SourceDialectPreProcessor {

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 1. 处理 INTERVAL '90 DAY' -> INTERVAL '90' DAY
        String result = sql.replaceAll(
                "(?i)\\bINTERVAL\\s+'(-?\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?'", "INTERVAL '$1' $2");

        // 2. 处理 INTERVAL 90 DAY -> INTERVAL '90' DAY
        result = result.replaceAll(
                "(?i)\\bINTERVAL\\s+(\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?", "INTERVAL '$1' $2");

        return result;
    }
}
