package com.translator.core.postprocessor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * 通用目标方言后处理器：剥离目标数据库不支持的 BETWEEN ASYMMETRIC / BETWEEN SYMMETRIC 关键字，归一化为标准的 BETWEEN。
 */
public class BetweenAsymmetricPostProcessor implements TargetDialectPostProcessor {

    private static final Set<DialectType> TARGET_DIALECTS = new HashSet<>(
            Arrays.asList(DialectType.MYSQL, DialectType.POSTGRESQL, DialectType.ORACLE, DialectType.SQLSERVER));

    @Override
    public Set<DialectType> getTargetDialects() {
        return TARGET_DIALECTS;
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bBETWEEN\\s+(?:ASYMMETRIC|SYMMETRIC)\\b", "BETWEEN");
    }
}
