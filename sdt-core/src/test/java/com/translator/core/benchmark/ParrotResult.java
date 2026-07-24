package com.translator.core.benchmark;

/**
 * 单条 PARROT Benchmark 评测结果
 */
public class ParrotResult {
    private final ParrotTestCase testCase;
    private final boolean translationSuccess;
    private final String translatedSql;
    private final String errorMessage;
    private final long latencyNs; // 翻译耗时（纳秒）

    public ParrotResult(
            ParrotTestCase testCase,
            boolean translationSuccess,
            String translatedSql,
            String errorMessage,
            long latencyNs) {
        this.testCase = testCase;
        this.translationSuccess = translationSuccess;
        this.translatedSql = translatedSql;
        this.errorMessage = errorMessage;
        this.latencyNs = latencyNs;
    }

    public ParrotTestCase getTestCase() {
        return testCase;
    }

    public boolean isTranslationSuccess() {
        return translationSuccess;
    }

    public String getTranslatedSql() {
        return translatedSql;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getLatencyNs() {
        return latencyNs;
    }

    public double getLatencyUs() {
        return latencyNs / 1000.0;
    }

    public double getLatencyMs() {
        return latencyNs / 1_000_000.0;
    }
}
