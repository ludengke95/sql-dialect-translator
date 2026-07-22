package com.translator.core.postprocessor;

import com.translator.core.DialectType;

/**
 * MySQL 目标方言 SQL 后处理器。
 * 负责将时间间隔表达式归一化为 MySQL 兼容的语法：INTERVAL N UNIT。
 */
public class MysqlTargetPostProcessor extends AbstractTargetPostProcessor {

    @Override
    public DialectType getTargetDialect() {
        return DialectType.MYSQL;
    }

    @Override
    protected String doProcess(String sql, DialectType sourceDialect) {
        // 1. 归一化 INTERVAL 'N' DAYS / INTERVAL 'N' DAY 为标准 MySQL 语法 INTERVAL N DAY
        String result = sql.replaceAll(
                "(?i)\\bINTERVAL\\s+'(-?\\d+)'\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?\\b", "INTERVAL $1 $2");

        // 2. 归一化 INTERVAL 'N DAY' / INTERVAL 'N DAYS' 为标准 MySQL 语法 INTERVAL N DAY
        result = result.replaceAll(
                "(?i)\\bINTERVAL\\s+'(-?\\d+)\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?'", "INTERVAL $1 $2");

        return result;
    }
}
