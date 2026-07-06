package com.translator.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

/**
 * SQL 翻译层指标：翻译请求数、成功/失败、直通、降级、耗时。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code target_dialect} — 目标方言（postgresql / oracle / sqlserver 等）</li>
 *   <li>{@code backend_name} — 后端名称</li>
 *   <li>{@code error_type} — 翻译失败类型（parse_error / translation_error）</li>
 * </ul>
 */
public final class TranslationMetrics {

    private TranslationMetrics() {}

    /** 翻译请求总数（按 target_dialect 和 backend_name 分 label） */
    public static final Counter REQUESTS = Counter.build()
            .name("sdt_translation_requests_total")
            .help("Total SQL translation requests")
            .labelNames("target_dialect", "backend_name")
            .register();

    /** 翻译成功次数 */
    public static final Counter SUCCESS = Counter.build()
            .name("sdt_translation_success_total")
            .help("Total successful translations")
            .register();

    /** 翻译失败次数（按 error_type 分 label） */
    public static final Counter FAILURES = Counter.build()
            .name("sdt_translation_failures_total")
            .help("Total translation failures")
            .labelNames("error_type")
            .register();

    /** 翻译失败后降级为原始 SQL 直通的次数 */
    public static final Counter FALLBACKS = Counter.build()
            .name("sdt_translation_fallbacks_total")
            .help("Total translation fallbacks (original SQL used)")
            .register();

    /** 直通标记（-- direct / * sdtp:direct *）跳过翻译的次数 */
    public static final Counter DIRECT = Counter.build()
            .name("sdt_translation_direct_total")
            .help("Total queries bypassing translation (direct pass-through)")
            .register();

    /** 同方言无需翻译直通次数 */
    public static final Counter DISABLED = Counter.build()
            .name("sdt_translation_disabled_total")
            .help("Total queries where translation is disabled (same dialect)")
            .register();

    /** 翻译耗时（秒），按 target_dialect 和 backend_name 分 label */
    public static final Histogram DURATION = Histogram.build()
            .name("sdt_translation_duration_seconds")
            .help("SQL translation duration in seconds")
            .labelNames("target_dialect", "backend_name")
            .buckets(0.0001, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0)
            .register();

    // ==================== 便捷方法 ====================

    public static void recordRequest(String targetDialect, String backendName) {
        REQUESTS.labels(targetDialect, backendName).inc();
    }

    public static void recordSuccess() {
        SUCCESS.inc();
    }

    public static void recordFailure(String errorType) {
        FAILURES.labels(errorType).inc();
    }

    public static void recordFallback() {
        FALLBACKS.inc();
    }

    public static void recordDirect() {
        DIRECT.inc();
    }

    public static void recordDisabled() {
        DISABLED.inc();
    }

    public static void recordDuration(String targetDialect, String backendName, double seconds) {
        DURATION.labels(targetDialect, backendName).observe(seconds);
    }

    /** 创建翻译耗时计时器 */
    public static Timer startTimer(String targetDialect) {
        return new Timer(targetDialect);
    }

    public static class Timer implements AutoCloseable {
        private final String targetDialect;
        private final long startNanos;

        Timer(String targetDialect) {
            this.targetDialect = targetDialect;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void close() {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            DURATION.labels(targetDialect).observe(seconds);
        }
    }
}
