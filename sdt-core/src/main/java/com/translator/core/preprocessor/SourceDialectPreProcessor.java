package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 源方言 SQL 前处理器接口。
 * 用于在 Calcite SqlParser 解析之前对源 SQL 进行清洗、转义和单项语法规整。
 */
public interface SourceDialectPreProcessor {

    /**
     * 当前前处理器支持的源方言集合。
     * 默认返回 emptySet()，代表对所有源方言通用。
     *
     * @return 源方言集合
     */
    default Set<DialectType> getSourceDialects() {
        return Collections.emptySet();
    }

    /**
     * 断言判断：是否支持处理指定的源方言与目标方言组合。
     * 默认实现：检查 sourceDialect 是否包含在 getSourceDialects() 集合中（若集合为空则全方言匹配）。
     *
     * @param sourceDialect 源方言
     * @param targetDialect 目标方言
     * @return true 代表生效
     */
    default boolean supports(DialectType sourceDialect, DialectType targetDialect) {
        Set<DialectType> sources = getSourceDialects();
        return sources == null || sources.isEmpty() || sources.contains(sourceDialect);
    }

    /**
     * 前处理器执行优先级，数值越小越先执行。
     *
     * @return 顺序权重，默认为 0
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 对 SQL 进行源方言前置清洗与语法规整。
     *
     * @param sql           原始 SQL
     * @param sourceDialect 源方言类型
     * @param targetDialect 目标方言类型
     * @param config        翻译配置
     * @return 前置处理后的 SQL
     */
    String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config);
}
