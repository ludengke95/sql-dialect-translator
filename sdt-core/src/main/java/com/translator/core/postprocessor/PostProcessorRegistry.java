package com.translator.core.postprocessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.translator.core.DialectType;

/**
 * 目标方言 SQL 后处理器注册表与管理工厂。
 */
public class PostProcessorRegistry {

    private static final Map<DialectType, TargetDialectPostProcessor> PROCESSORS = new ConcurrentHashMap<>();
    private static final TargetDialectPostProcessor DEFAULT_PROCESSOR = new DefaultTargetPostProcessor();

    static {
        register(new MysqlTargetPostProcessor());
        register(new PostgresTargetPostProcessor());
    }

    /**
     * 注册一个新的目标方言后处理器。
     *
     * @param processor 后处理器实例
     */
    public static void register(TargetDialectPostProcessor processor) {
        if (processor != null && processor.getTargetDialect() != null) {
            PROCESSORS.put(processor.getTargetDialect(), processor);
        }
    }

    /**
     * 根据目标方言获取对应后处理器。
     *
     * @param targetDialect 目标方言
     * @return 对应的后处理器，若未匹配到则返回默认后处理器
     */
    public static TargetDialectPostProcessor getProcessor(DialectType targetDialect) {
        if (targetDialect == null) {
            return DEFAULT_PROCESSOR;
        }
        return PROCESSORS.getOrDefault(targetDialect, DEFAULT_PROCESSOR);
    }

    /**
     * 对 SQL 进行目标方言后处理。
     *
     * @param sql           原始转译 SQL
     * @param sourceDialect 源方言
     * @param targetDialect 目标方言
     * @return 后处理后的 SQL
     */
    public static String process(String sql, DialectType sourceDialect, DialectType targetDialect) {
        return getProcessor(targetDialect).process(sql, sourceDialect);
    }
}
