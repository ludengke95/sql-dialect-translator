package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将 SQL Server 特有的方括号 `[identifier]` 转换为标准双引号 `"identifier"`。
 */
public class BracketQuotingPreProcessor implements SourceDialectPreProcessor {

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.SQLSERVER);
    }

    @Override
    public int getOrder() {
        return 15;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        return sql.replace('[', '"').replace(']', '"');
    }
}
