package com.translator.proxy.backend;

import com.translator.core.config.TranslationConfig;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.session.FrontendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多后端连接池管理器。
 *
 * <p>根据配置的 backends 列表初始化多个 HikariCP 连接池，
 * 每个后端有一个 {@link CommandHandler.QueryProcessor}（含翻译装饰器）。
 * 实现 {@link BackendRouter} 接口，按会话中记录的 database 名称路由。
 */
public class BackendPoolManager implements BackendRouter {

    private static final Logger log = LoggerFactory.getLogger(BackendPoolManager.class);

    /** 后端名称 → QueryProcessor 映射 */
    private final Map<String, CommandHandler.QueryProcessor> processorMap = new LinkedHashMap<>();

    /** 默认后端（列表中的第一个） */
    private volatile CommandHandler.QueryProcessor defaultProcessor;

    /**
     * 创建管理器并初始化所有后端连接池。
     *
     * @param backends           后端配置列表
     * @param defaultTranslationConfig 全局默认翻译配置（后端未指定时使用）
     */
    public BackendPoolManager(List<BackendEntry> backends, TranslationConfig defaultTranslationConfig) {
        for (BackendEntry be : backends) {
            String name = be.getName();
            if (name == null || name.isEmpty()) {
                log.warn("Skipping backend with empty name");
                continue;
            }

            // 创建原始 JDBC 后端处理器（带后端名称用于指标打点）
            JdbcBackendQueryProcessor jdbcProcessor = JdbcBackendQueryProcessor.create(
                    name, be.getJdbcUrl(), be.getUsername(), be.getPassword(),
                    be.getMaxPoolSize(), be.getMinIdle());

            // 确定此后端的翻译配置：优先使用后端自带的，否则使用全局默认
            TranslationConfig tc;
            if (be.getKeywordCase() != null || be.getIdentifierCase() != null) {
                String kw = be.getKeywordCase() != null ? be.getKeywordCase() : defaultTranslationConfig.getKeywordCase().name();
                String id = be.getIdentifierCase() != null ? be.getIdentifierCase() : defaultTranslationConfig.getIdentifierCase().name();
                tc = new TranslationConfig()
                        .withKeywordCase(TranslationConfig.KeywordCase.valueOf(kw))
                        .withIdentifierCase(TranslationConfig.IdentifierCase.valueOf(id));
            } else {
                tc = defaultTranslationConfig;
            }

            // 包装翻译装饰器
            CommandHandler.QueryProcessor processor;
            if (be.getDialect() != null && !be.getDialect().equalsIgnoreCase("MYSQL")) {
                processor = new TranslationQueryProcessor(jdbcProcessor, be.getDialect(), tc);
            } else {
                processor = jdbcProcessor;
            }

            processorMap.put(name, processor);
            log.info("Backend '{}': {} ({}), pool={}, kw={}, id={}",
                    name, be.getJdbcUrl(), be.getDialect(), be.getMaxPoolSize(),
                    tc.getKeywordCase(), tc.getIdentifierCase());
        }

        if (!processorMap.isEmpty()) {
            defaultProcessor = processorMap.values().iterator().next();
            log.info("Default backend: '{}'", processorMap.keySet().iterator().next());
        } else {
            log.warn("No backends configured, using NOOP processor");
            defaultProcessor = CommandHandler.QueryProcessor.NOOP;
        }
    }

    @Override
    public CommandHandler.QueryProcessor resolve(FrontendSession session) {
        String database = session.getDatabase();
        if (database != null && processorMap.containsKey(database)) {
            return processorMap.get(database);
        }
        return defaultProcessor;
    }

    public CommandHandler.QueryProcessor getProcessor(String databaseName) {
        if (databaseName != null && processorMap.containsKey(databaseName)) {
            return processorMap.get(databaseName);
        }
        return defaultProcessor;
    }

    public CommandHandler.QueryProcessor getDefaultProcessor() {
        return defaultProcessor;
    }

    public Set<String> getBackendNames() {
        return processorMap.keySet();
    }

    /**
     * 关闭所有连接池。
     */
    public void close() {
        for (CommandHandler.QueryProcessor processor : processorMap.values()) {
            processor.close();
        }
        log.info("All backend pools closed");
    }
}
