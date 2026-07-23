package com.translator.core.operator;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.calcite.sql.SqlOperator;

import com.translator.core.DialectType;

/**
 * 自定义 Calcite 算子提供者 SPI 接口。
 * 用于注册 Calcite 缺少的源方言/目标方言系统算子与扩展函数。
 */
public interface CustomOperatorProvider {

    /**
     * 获取当前提供者注册的 Calcite SqlOperator 列表。
     *
     * @return 算子列表
     */
    List<SqlOperator> getOperators();

    /**
     * 适用的源/目标方言集合（默认 emptySet 代表全方言通用）。
     *
     * @return 方言集合
     */
    default Set<DialectType> getDialects() {
        return Collections.emptySet();
    }

    /**
     * 断言判断：是否支持当前方言环境。
     *
     * @param source 源方言
     * @param target 目标方言
     * @return true 代表生效
     */
    default boolean supports(DialectType source, DialectType target) {
        Set<DialectType> set = getDialects();
        return set == null || set.isEmpty()
                || (source != null && set.contains(source))
                || (target != null && set.contains(target));
    }
}
