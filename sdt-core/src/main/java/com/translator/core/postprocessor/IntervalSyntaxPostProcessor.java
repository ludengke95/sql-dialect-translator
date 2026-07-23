package com.translator.core.postprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * 通用目标方言后处理器：将时间间隔表达式归一化为兼容的标准语法：INTERVAL N UNIT。
 */
public class IntervalSyntaxPostProcessor implements TargetDialectPostProcessor {

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 1. 归一化 INTERVAL 'N' DAYS / INTERVAL 'N' DAY 为标准 INTERVAL N DAY
        String result = sql.replaceAll(
                "(?i)\\bINTERVAL\\s+'(-?\\d+)'\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?\\b", "INTERVAL $1 $2");

        // 2. 归一化 INTERVAL 'N DAY' / INTERVAL 'N DAYS' 为标准 INTERVAL N DAY
        return result.replaceAll(
                "(?i)\\bINTERVAL\\s+'(-?\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?'", "INTERVAL $1 $2");
    }
}
