package com.translator.core.postprocessor;

import com.translator.core.DialectType;

/**
 * 目标方言 SQL 后处理器。
 * 用于对 Calcite unparse 导出的目标 SQL 进行目标数据库专属的语法归一化与擦除。
 */
public interface TargetDialectPostProcessor {

    /**
     * 该后处理器对应的目标方言。
     *
     * @return 目标方言类型
     */
    DialectType getTargetDialect();

    /**
     * 对 SQL 进行目标方言后处理加工。
     *
     * @param sql           Calcite 导出的原始 SQL
     * @param sourceDialect 源方言类型
     * @return 加工后的目标 SQL
     */
    String process(String sql, DialectType sourceDialect);
}
