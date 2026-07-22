package com.translator.core.postprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * 目标方言 SQL 后处理器。
 * 用于对 Calcite unparse 导出的目标 SQL 进行目标数据库专属的语法归一化与擦除。
 */
public interface TargetDialectPostProcessor {

    /**
     * 当前后处理器支持的目标方言集合。
     * 默认返回 emptySet()，代表对所有目标方言通用。
     *
     * @return 目标方言集合
     */
    default Set<DialectType> getTargetDialects() {
        return Collections.emptySet();
    }

    /**
     * (兼容方法) 获取对应的主目标方言。
     *
     * @return 目标方言类型，若为全方言通用则返回 null
     */
    default DialectType getTargetDialect() {
        Set<DialectType> set = getTargetDialects();
        return (set != null && !set.isEmpty()) ? set.iterator().next() : null;
    }

    /**
     * 断言判断：是否支持处理指定的源方言与目标方言组合。
     * 默认实现：检查 targetDialect 是否包含在 getTargetDialects() 集合中（若集合为空则全方言匹配）。
     *
     * @param sourceDialect 源方言
     * @param targetDialect 目标方言
     * @return true 代表生效
     */
    default boolean supports(DialectType sourceDialect, DialectType targetDialect) {
        Set<DialectType> targets = getTargetDialects();
        return targets == null || targets.isEmpty() || targets.contains(targetDialect);
    }

    /**
     * 后处理器执行优先级，数值越小越先执行。
     *
     * @return 顺序权重，默认为 0
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 对 SQL 进行目标方言后处理加工。
     *
     * @param sql           Calcite 导出的原始 SQL
     * @param sourceDialect 源方言类型
     * @return 加工后的目标 SQL
     */
    String process(String sql, DialectType sourceDialect);
}
