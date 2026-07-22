package com.translator.core.postprocessor;

import com.translator.core.DialectType;

/**
 * 通用目标方言后处理器：擦除函数名误加的双引号（如 "SUBSTR"( -> SUBSTR( ）。
 */
public class QuotedFunctionNamePostProcessor implements TargetDialectPostProcessor {

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE; // 最优先执行
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        return sql.replaceAll("\"([A-Za-z_][A-Za-z0-9_]*)\"\\(", "$1(");
    }
}
