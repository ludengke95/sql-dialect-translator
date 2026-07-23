package com.translator.core.preprocessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 源方言 SQL 前处理器注册表与管理工厂。
 * 支持 Java SPI (ServiceLoader) 自动发现与责任链管道组装。
 */
public class PreProcessorRegistry {

    private static final Logger log = LoggerFactory.getLogger(PreProcessorRegistry.class);

    private static final Map<DialectType, SourceDialectPreProcessor> PROCESSORS = new ConcurrentHashMap<>();

    static {
        loadSpiPreProcessors();
    }

    /**
     * 通过 Java SPI ServiceLoader 自动加载所有注册的 SourceDialectPreProcessor，
     * 并按 sourceDialect 分组与 order 权重构建前处理责任链管道。
     */
    public static void loadSpiPreProcessors() {
        ServiceLoader<SourceDialectPreProcessor> loader = ServiceLoader.load(SourceDialectPreProcessor.class);
        Map<DialectType, List<SourceDialectPreProcessor>> dialectMap = new HashMap<>();

        for (SourceDialectPreProcessor processor : loader) {
            for (DialectType dialect : DialectType.values()) {
                if (processor.supports(dialect, null)) {
                    dialectMap.computeIfAbsent(dialect, k -> new ArrayList<>()).add(processor);
                }
            }
        }

        for (DialectType dialect : DialectType.values()) {
            List<SourceDialectPreProcessor> list = dialectMap.computeIfAbsent(dialect, k -> new ArrayList<>());
            list.sort(Comparator.comparingInt(SourceDialectPreProcessor::getOrder));
            PROCESSORS.put(dialect, new PreProcessorChain(dialect, list));
            log.info("Registered preprocessor chain for source dialect {}: {} processors", dialect, list.size());
        }
    }

    /**
     * 注册一个新的源方言前处理器。
     *
     * @param processor 前处理器实例
     */
    public static void register(SourceDialectPreProcessor processor) {
        if (processor == null) {
            return;
        }
        for (DialectType dialect : DialectType.values()) {
            if (processor.supports(dialect, null)) {
                PROCESSORS.compute(dialect, (d, existingChain) -> {
                    List<SourceDialectPreProcessor> list = new ArrayList<>();
                    if (existingChain instanceof PreProcessorChain) {
                        list.addAll(((PreProcessorChain) existingChain).getProcessors());
                    } else if (existingChain != null) {
                        list.add(existingChain);
                    }
                    if (!list.contains(processor)) {
                        list.add(processor);
                    }
                    list.sort(Comparator.comparingInt(SourceDialectPreProcessor::getOrder));
                    return new PreProcessorChain(d, list);
                });
            }
        }
    }

    /**
     * 对 SQL 进行源方言前置清洗与语法规整。
     *
     * @param sql           原始 SQL
     * @param sourceDialect 源方言
     * @param targetDialect 目标方言
     * @param config        翻译配置
     * @return 前置处理后的 SQL
     */
    public static String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        SourceDialectPreProcessor processor = PROCESSORS.get(sourceDialect);
        if (processor != null) {
            return processor.process(sql, sourceDialect, targetDialect, config);
        }
        return sql;
    }
}
