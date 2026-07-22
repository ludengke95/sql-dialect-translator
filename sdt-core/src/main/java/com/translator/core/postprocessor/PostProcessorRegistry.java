package com.translator.core.postprocessor;

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

/**
 * 目标方言 SQL 后处理器注册表与管理工厂。
 * 支持 Java SPI (ServiceLoader) 自动抓取与后处理器责任链管道组装。
 */
public class PostProcessorRegistry {

    private static final Logger log = LoggerFactory.getLogger(PostProcessorRegistry.class);

    private static final Map<DialectType, TargetDialectPostProcessor> PROCESSORS = new ConcurrentHashMap<>();
    private static final TargetDialectPostProcessor DEFAULT_PROCESSOR = new DefaultTargetPostProcessor();

    static {
        loadSpiPostProcessors();
    }

    /**
     * 通过 Java SPI ServiceLoader 自动抓取所有注册的 TargetDialectPostProcessor，
     * 并按 targetDialect 分组与按 order 权重构建后处理责任链管道。
     */
    public static void loadSpiPostProcessors() {
        ServiceLoader<TargetDialectPostProcessor> loader = ServiceLoader.load(TargetDialectPostProcessor.class);
        Map<DialectType, List<TargetDialectPostProcessor>> dialectMap = new HashMap<>();

        for (TargetDialectPostProcessor processor : loader) {
            // 自动为每一个满足 supports 断言的目标方言注入对应的后处理器
            for (DialectType dialect : DialectType.values()) {
                if (processor.supports(null, dialect)) {
                    dialectMap.computeIfAbsent(dialect, k -> new ArrayList<>()).add(processor);
                }
            }
        }

        // 为每一个方言管道按 order 权重排序并注册责任链
        for (DialectType dialect : DialectType.values()) {
            List<TargetDialectPostProcessor> list = dialectMap.computeIfAbsent(dialect, k -> new ArrayList<>());
            list.sort(Comparator.comparingInt(TargetDialectPostProcessor::getOrder));
            PROCESSORS.put(dialect, new PostProcessorChain(dialect, list));
            log.info("Registered post processor chain for dialect {}: {} processors", dialect, list.size());
        }
    }

    /**
     * 注册一个新的目标方言后处理器。
     *
     * @param processor 后处理器实例
     */
    public static void register(TargetDialectPostProcessor processor) {
        if (processor == null) {
            return;
        }
        for (DialectType dialect : DialectType.values()) {
            if (processor.supports(null, dialect)) {
                PROCESSORS.compute(dialect, (d, existingChain) -> {
                    List<TargetDialectPostProcessor> list = new ArrayList<>();
                    if (existingChain instanceof PostProcessorChain) {
                        list.addAll(((PostProcessorChain) existingChain).getProcessors());
                    } else if (existingChain != null) {
                        list.add(existingChain);
                    }
                    if (!list.contains(processor)) {
                        list.add(processor);
                    }
                    list.sort(Comparator.comparingInt(TargetDialectPostProcessor::getOrder));
                    return new PostProcessorChain(d, list);
                });
            }
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
