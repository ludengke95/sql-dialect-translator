package com.translator.core.postprocessor;

import com.translator.core.DialectType;

/**
 * 目标方言后处理器抽象基类。
 * 提供全方言通用的后处理逻辑（如函数名脱去强制双引号），并为特定目标方言留出扩展点。
 */
public abstract class AbstractTargetPostProcessor implements TargetDialectPostProcessor {

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 1. 公共擦除逻辑：消除由于 withQuoteAllIdentifiers(true) 导致的函数名被误加双引号问题（如 "SUBSTR"( → SUBSTR( ）
        String result = cleanQuotedFunctionNames(sql);

        // 2. 执行特定目标方言的专属加工
        return doProcess(result, sourceDialect);
    }

    /**
     * 特定目标方言的具体后处理扩展点。
     *
     * @param sql           已完成公共处理的 SQL
     * @param sourceDialect 源方言类型
     * @return 最终的目标 SQL
     */
    protected abstract String doProcess(String sql, DialectType sourceDialect);

    /**
     * 消除函数名被误加双引号的问题。
     *
     * @param sql 输入 SQL
     * @return 消除双引号后的 SQL
     */
    protected String cleanQuotedFunctionNames(String sql) {
        return sql.replaceAll("\"([A-Za-z_][A-Za-z0-9_]*)\"\\(", "$1(");
    }
}
