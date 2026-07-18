package com.translator.proxy.backend;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslationException;
import com.translator.core.SqlTranslator;
import com.translator.core.config.TranslationConfig;
import com.translator.metrics.TranslationMetrics;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.handler.SqlTranslationContext;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.channel.ChannelHandlerContext;

/**
 * SQL 翻译装饰器 —— 在执行前将源 SQL（MySQL 方言）翻译为目标方言 SQL。
 *
 * <p>包装一个 {@link CommandHandler.QueryProcessor}，在执行前调用 Calcite 翻译引擎。
 * 翻译失败时自动降级为原始 SQL 直接执行。
 *
 * <p>跳过翻译（直通模式）：在 SQL 开头加注释标记即可绕过翻译引擎：
 * <pre>
 *   -- direct
 *   SELECT now(), version()
 *
 *   -- sdtp:direct
 *   SELECT some_vendor_specific_func()
 *
 *   /* sdtp:direct *{@literal /}
 *   SELECT pg_backend_pid()
 * </pre>
 *
 * <p>架构位置：
 * <pre>
 *   CommandHandler → TranslationQueryProcessor → JdbcBackendQueryProcessor → 目标数据库
 *                    (翻译 SQL / 直通)           (执行 SQL)
 * </pre>
 */
public class TranslationQueryProcessor implements CommandHandler.QueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(TranslationQueryProcessor.class);

    /** 直通标记：-- direct 或 -- sdtp:direct */
    private static final Pattern DIRECT_LINE_HINT =
            Pattern.compile("^\\s*--\\s*(?:sdtp:)?direct\\s*\\n?(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 直通标记：/* sdtp:direct *{@literal /} */
    private static final Pattern DIRECT_BLOCK_HINT = Pattern.compile(
            "^\\s*/\\*\\s*(?:sdtp:)?direct\\s*\\*/\\s*\\n?(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 被包装的实际后端处理器 */
    private final CommandHandler.QueryProcessor delegate;

    /** 源方言（由前端协议驱动：MySQL 前端=MYSQL，PostgreSQL 前端=POSTGRESQL） */
    private final DialectType sourceDialect;

    /** 目标方言 */
    private final DialectType targetDialect;

    /** 后端名称（用于指标打点） */
    private volatile String backendName = "unknown";

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
    public TranslationQueryProcessor(CommandHandler.QueryProcessor delegate, String targetDialectId) {
        this(delegate, targetDialectId, TranslationConfig.DEFAULT, null);
    }

    /**
     * 创建翻译处理器（带自定义大小写配置，不指定后端名）。
     */
    public TranslationQueryProcessor(
            CommandHandler.QueryProcessor delegate, String targetDialectId, TranslationConfig translationConfig) {
        this(delegate, targetDialectId, translationConfig, null, DialectType.MYSQL.name());
    }

    /**
     * 创建翻译处理器（带自定义大小写配置）。
     *
     * @param delegate          实际后端查询处理器
     * @param targetDialectId   目标方言标识符
     * @param translationConfig 翻译配置（关键词/标识符大小写策略）
     * @param backendName       后端名称（用于指标打点），可为 null
     */
    public TranslationQueryProcessor(
            CommandHandler.QueryProcessor delegate,
            String targetDialectId,
            TranslationConfig translationConfig,
            String backendName) {
        this(delegate, targetDialectId, translationConfig, backendName, DialectType.MYSQL.name());
    }

    /**
     * 创建翻译处理器（带自定义大小写配置 + 源方言）。
     *
     * @param delegate          实际后端查询处理器
     * @param targetDialectId   目标方言标识符
     * @param translationConfig 翻译配置（关键词/标识符大小写策略）
     * @param backendName       后端名称（用于指标打点），可为 null
     * @param sourceDialectId   源方言标识符（前端协议决定：MYSQL / POSTGRESQL）
     */
    public TranslationQueryProcessor(
            CommandHandler.QueryProcessor delegate,
            String targetDialectId,
            TranslationConfig translationConfig,
            String backendName,
            String sourceDialectId) {
        this.delegate = delegate;
        this.sourceDialect = sourceDialectId != null
                ? DialectType.fromIdentifier(sourceDialectId)
                : DialectType.MYSQL;
        this.enabled = !sourceDialect.getIdentifier().equalsIgnoreCase(targetDialectId);
        this.translationConfig = translationConfig != null ? translationConfig : TranslationConfig.DEFAULT;
        if (backendName != null) {
            this.backendName = backendName;
        }

        if (enabled) {
            this.targetDialect = DialectType.fromIdentifier(targetDialectId);
            log.info(
                    "SQL translation enabled: {} → {} (config: {}, backend: {})",
                    sourceDialect.getIdentifier(),
                    targetDialect.getIdentifier(),
                    this.translationConfig,
                    this.backendName);
        } else {
            this.targetDialect = sourceDialect;
            log.info("SQL translation disabled (source == target: {}, backend: {})", targetDialectId, this.backendName);
        }
    }

    @Override
    public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        log.info("SQL: {}", formatSqlForLog(sql));

        // 安全保护：如果 SQL 大于 1MB，自动作为直通模式处理，免于送进 Calcite 翻译引擎导致 OOM
        if (sql != null && sql.length() > 1024 * 1024) {
            log.info("SQL length ({}) exceeds 1MB. Auto pass-through without Calcite translation.", sql.length());
            TranslationMetrics.recordDirect();
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, sql);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, sql, session);
            return;
        }

        if (!enabled) {
            TranslationMetrics.recordDisabled();
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, sql);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, sql, session);
            return;
        }

        TranslationMetrics.recordRequest(targetDialect.getIdentifier(), backendName);

        // 检查直通标记：-- direct / -- sdtp:direct / /* sdtp:direct */
        String stripped = stripDirectHint(sql);
        if (stripped != null) {
            // 去掉标记注释后的 SQL 直通执行（不翻译）
            log.debug("Direct pass-through: {}", formatSqlForLog(stripped));
            TranslationMetrics.recordDirect();
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, stripped);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, stripped, session);
            return;
        }

        String translatedSql;
        try {
            long startNanos = System.nanoTime();
            translatedSql = translate(sql);
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            TranslationMetrics.recordSuccess();
            TranslationMetrics.recordDuration(targetDialect.getIdentifier(), backendName, seconds);
            log.info("Translated: {} → {}", formatSqlForLog(sql), formatSqlForLog(translatedSql));
        } catch (Exception e) {
            log.warn(
                    "Translation failed for SQL: {}. Falling back to original. Error: {}",
                    formatSqlForLog(sql),
                    e.getMessage());
            TranslationMetrics.recordFallback();
            translatedSql = sql;
        }

        SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, translatedSql);
        ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
        delegate.process(ctx, translatedSql, session);
    }

    /**
     * 带绑定参数的翻译入口（PostgreSQL 扩展查询协议）。
     * 剥离直通标记 → 翻译 → 转发给后端处理器（携带参数）。
     */
    @Override
    public void process(ChannelHandlerContext ctx, String sql, List<String> params, FrontendSession session) {
        log.info("SQL(params={}): {}", params.size(), formatSqlForLog(sql));

        // 安全保护：超长 SQL 直接直通
        if (sql != null && sql.length() > 1024 * 1024) {
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, sql);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, sql, params, session);
            return;
        }

        if (!enabled) {
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, sql);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, sql, params, session);
            return;
        }

        TranslationMetrics.recordRequest(targetDialect.getIdentifier(), backendName);

        String stripped = stripDirectHint(sql);
        if (stripped != null) {
            log.debug("Direct pass-through: {}", formatSqlForLog(stripped));
            TranslationMetrics.recordDirect();
            SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, stripped);
            ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
            delegate.process(ctx, stripped, params, session);
            return;
        }

        String translatedSql;
        try {
            long startNanos = System.nanoTime();
            translatedSql = translate(sql);
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            TranslationMetrics.recordSuccess();
            TranslationMetrics.recordDuration(targetDialect.getIdentifier(), backendName, seconds);
            log.info("Translated: {} → {}", formatSqlForLog(sql), formatSqlForLog(translatedSql));
        } catch (Exception e) {
            log.warn(
                    "Translation failed for SQL: {}. Falling back to original. Error: {}",
                    formatSqlForLog(sql),
                    e.getMessage());
            TranslationMetrics.recordFallback();
            translatedSql = sql;
        }

        SqlTranslationContext sqlCtx = new SqlTranslationContext(sql, translatedSql);
        ctx.channel().attr(SessionAttribute.SQL_CONTEXT_KEY).set(sqlCtx);
        delegate.process(ctx, translatedSql, params, session);
    }

    /**
     * 检测并剥离直通标记。
     *
     * @return 剥离后的 SQL（如果检测到直通标记），否则返回 null
     */
    static String stripDirectHint(String sql) {
        if (sql == null) return null;

        Matcher m = DIRECT_LINE_HINT.matcher(sql);
        if (m.matches()) {
            String remaining = m.group(1);
            return remaining != null ? remaining.trim() : "";
        }

        m = DIRECT_BLOCK_HINT.matcher(sql);
        if (m.matches()) {
            String remaining = m.group(1);
            return remaining != null ? remaining.trim() : "";
        }

        return null;
    }

    /**
     * 设置后端名称（用于指标打点）。
     */
    public void setBackendName(String backendName) {
        this.backendName = backendName;
    }

    /**
     * 获取被包装的底层处理器。
     */
    public CommandHandler.QueryProcessor getDelegate() {
        return delegate;
    }

    @Override
    public void commit(ChannelHandlerContext ctx, FrontendSession session) throws Exception {
        delegate.commit(ctx, session);
    }

    @Override
    public void rollback(ChannelHandlerContext ctx, FrontendSession session) throws Exception {
        delegate.rollback(ctx, session);
    }

    @Override
    public void closeSessionConnection(ChannelHandlerContext ctx, FrontendSession session) {
        delegate.closeSessionConnection(ctx, session);
    }

    @Override
    public void close() {
        delegate.close();
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
            SqlTranslator translator = new SqlTranslator(sourceDialect, targetDialect, translationConfig);
            return translator.translate(sql);
        } catch (SqlTranslationException e) {
            // 翻译失败，记录日志并降级
            throw e;
        }
    }

    private String formatSqlForLog(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("[\\r\\n\\s]+", " ").trim();
    }
}
