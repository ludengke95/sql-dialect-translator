package com.translator.proxy.backend;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslationException;
import com.translator.core.SqlTranslator;
import com.translator.core.config.TranslationConfig;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.session.FrontendSession;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 翻译装饰器 —— 在执行前将源 SQL（MySQL 方言）翻译为目标方言 SQL。
 *
 * <p>包装一个 {@link CommandHandler.QueryProcessor}，在执行前调用 Calcite 翻译引擎。
 * 翻译失败时自动降级为原始 SQL 直接执行。
 *
 * <p>架构位置：
 * <pre>
 *   CommandHandler → TranslationQueryProcessor → JdbcBackendQueryProcessor → 目标数据库
 *                    (翻译 SQL)                  (执行翻译后的 SQL)
 * </pre>
 */
public class TranslationQueryProcessor implements CommandHandler.QueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(TranslationQueryProcessor.class);

    /** 被包装的实际后端处理器 */
    private final CommandHandler.QueryProcessor delegate;

    /** 源方言（始终为 MySQL——Proxy 对外伪装成 MySQL） */
    private static final DialectType SOURCE_DIALECT = DialectType.MYSQL;

    /** 目标方言 */
    private final DialectType targetDialect;

    /** 翻译配置 */
    private final TranslationConfig translationConfig;

    /** 是否启用翻译 */
    private final boolean enabled;

    /**
     * 创建翻译处理器。
     *
     * @param delegate        实际后端查询处理器
     * @param targetDialectId 目标方言标识符（如 "postgresql"）
     */
    public TranslationQueryProcessor(CommandHandler.QueryProcessor delegate,
                                      String targetDialectId) {
        this.delegate = delegate;
        this.enabled = !SOURCE_DIALECT.getIdentifier().equalsIgnoreCase(targetDialectId);

        if (enabled) {
            this.targetDialect = DialectType.fromIdentifier(targetDialectId);
            // 目标 PostgreSQL 默认：关键词大写、标识符小写（PostgreSQL 会把未引用标识符转为小写）
            this.translationConfig = TranslationConfig.DEFAULT;
            log.info("SQL translation enabled: {} → {}", SOURCE_DIALECT.getIdentifier(),
                    targetDialect.getIdentifier());
        } else {
            this.targetDialect = SOURCE_DIALECT;
            this.translationConfig = TranslationConfig.DEFAULT;
            log.info("SQL translation disabled (source == target: {})", targetDialectId);
        }
    }

    @Override
    public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        if (!enabled) {
            // 源和目标相同，无需翻译
            delegate.process(ctx, sql, session);
            return;
        }

        String translatedSql;
        try {
            translatedSql = translate(sql);
            log.debug("Translated: {} → {}", sql, translatedSql);
        } catch (Exception e) {
            log.warn("Translation failed for SQL: {}. Falling back to original. Error: {}",
                    sql, e.getMessage());
            translatedSql = sql; // 降级为原始 SQL
        }

        delegate.process(ctx, translatedSql, session);
    }

    /**
     * 翻译单条 SQL。
     */
    private String translate(String sql) {
        // 跳过空 SQL
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        try {
            SqlTranslator translator = new SqlTranslator(SOURCE_DIALECT, targetDialect, translationConfig);
            return translator.translate(sql);
        } catch (SqlTranslationException e) {
            // 翻译失败，记录日志并降级
            throw e;
        }
    }
}
