package com.translator.core.postprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * 通用目标方言后处理器：剥离目标数据库不支持的 BETWEEN ASYMMETRIC 关键字，归一化为 BETWEEN。
 */
public class BetweenAsymmetricPostProcessor implements TargetDialectPostProcessor {

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.MYSQL);
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
        return sql.replaceAll("(?i)\\bBETWEEN\\s+ASYMMETRIC\\b", "BETWEEN");
    }
}
