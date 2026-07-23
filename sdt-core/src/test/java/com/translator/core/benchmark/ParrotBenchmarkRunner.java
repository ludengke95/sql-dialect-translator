package com.translator.core.benchmark;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PARROT Benchmark 评测执行器
 */
public class ParrotBenchmarkRunner {
    private static final Logger log = LoggerFactory.getLogger(ParrotBenchmarkRunner.class);

    public ParrotBenchmarkRunner() {
    }

    /**
     * CLI 批量评估与导出入口
     * Usage: java -cp ... com.translator.core.benchmark.ParrotBenchmarkRunner <inputJsonPath> <outputJsonPath>
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ParrotBenchmarkRunner <inputJsonPath> <outputJsonPath>");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        try {
            System.out.println("Loading test cases from: " + inputPath);
            List<ParrotTestCase> testCases = ParrotDataLoader.loadFromFile(inputPath);
            System.out.println("Loaded " + testCases.size() + " test cases.");

            ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
            BenchmarkReport report = runner.runBenchmark(testCases);

            System.out.println(report.toMarkdownReport());

            System.out.println("Exporting results to: " + outputPath);
            ParrotDataLoader.exportToJsonFile(report, outputPath);
            System.out.println("Export completed successfully!");
        } catch (Exception e) {
            log.error("Failed to run benchmark", e);
            System.exit(1);
        }
    }

    /**
     * 运行批量的 PARROT 测试用例并返回聚合报告
     */
    public BenchmarkReport runBenchmark(List<ParrotTestCase> testCases) {
        List<ParrotResult> results = new ArrayList<>();

        // 预热预热（Warmup）防止 JIT 偏差
        for (int i = 0; i < Math.min(5, testCases.size()); i++) {
            ParrotTestCase tc = testCases.get(i);
            try {
                DialectType src = DialectType.valueOf(tc.getSourceDialect().toUpperCase());
                DialectType target = DialectType.valueOf(tc.getTargetDialect().toUpperCase());
                new SqlTranslator(src, target).translate(tc.getSourceSql());
            } catch (Exception ignored) {
            }
        }

        long totalStartTime = System.currentTimeMillis();

        for (ParrotTestCase tc : testCases) {
            long startNs = System.nanoTime();
            try {
                DialectType src = DialectType.valueOf(tc.getSourceDialect().toUpperCase());
                DialectType target = DialectType.valueOf(tc.getTargetDialect().toUpperCase());
                SqlTranslator translator = new SqlTranslator(src, target);
                String translated = translator.translate(tc.getSourceSql());
                long latencyNs = System.nanoTime() - startNs;

                results.add(new ParrotResult(tc, true, translated, null, latencyNs));
            } catch (Exception e) {
                long latencyNs = System.nanoTime() - startNs;
                results.add(new ParrotResult(tc, false, null, e.getMessage(), latencyNs));
            }
        }

        long totalDurationMs = System.currentTimeMillis() - totalStartTime;
        return new BenchmarkReport(testCases.size(), results, totalDurationMs);
    }

    /**
     * 聚合报告内部类
     */
    public static class BenchmarkReport {
        private final int totalCases;
        private final List<ParrotResult> results;
        private final long totalDurationMs;

        public BenchmarkReport(int totalCases, List<ParrotResult> results, long totalDurationMs) {
            this.totalCases = totalCases;
            this.results = results;
            this.totalDurationMs = totalDurationMs;
        }

        public int getTotalCases() {
            return totalCases;
        }

        public int getSuccessCases() {
            int success = 0;
            for (ParrotResult r : results) {
                if (r.isTranslationSuccess()) {
                    success++;
                }
            }
            return success;
        }

        public double getSuccessRate() {
            if (totalCases == 0) return 0.0;
            return (getSuccessCases() * 100.0) / totalCases;
        }

        public double getAverageLatencyUs() {
            if (results.isEmpty()) return 0.0;
            long sumNs = 0;
            for (ParrotResult r : results) {
                sumNs += r.getLatencyNs();
            }
            return (sumNs / 1000.0) / results.size();
        }

        public double getP95LatencyUs() {
            if (results.isEmpty()) return 0.0;
            List<Long> latencies = new ArrayList<>();
            for (ParrotResult r : results) {
                latencies.add(r.getLatencyNs());
            }
            Collections.sort(latencies);
            int idx = (int) Math.ceil(0.95 * latencies.size()) - 1;
            idx = Math.max(0, Math.min(idx, latencies.size() - 1));
            return latencies.get(idx) / 1000.0;
        }

        public List<ParrotResult> getResults() {
            return results;
        }

        public String toMarkdownReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("# 🦜 SDT - PARROT Benchmark 评测报告\n\n");
            sb.append(String.format("- **总用例数**: %d\n", totalCases));
            sb.append(String.format("- **翻译成功数**: %d (%.2f%%)\n", getSuccessCases(), getSuccessRate()));
            sb.append(String.format("- **平均转换延迟**: %.2f μs (%.3f ms)\n", getAverageLatencyUs(), getAverageLatencyUs() / 1000.0));
            sb.append(String.format("- **P95 转换延迟**: %.2f μs (%.3f ms)\n", getP95LatencyUs(), getP95LatencyUs() / 1000.0));
            sb.append(String.format("- **总评估耗时**: %d ms\n\n", totalDurationMs));

            sb.append("## 用例明细\n\n");
            sb.append("| ID | 方言方向 | 状态 | 耗时 (μs) | 错误信息 |\n");
            sb.append("| --- | --- | --- | --- | --- |\n");

            for (ParrotResult r : results) {
                ParrotTestCase tc = r.getTestCase();
                String status = r.isTranslationSuccess() ? "✅ 成功" : "❌ 失败";
                String errMsg = r.getErrorMessage() != null ? r.getErrorMessage().replace("\n", " ") : "-";
                if (errMsg.length() > 50) {
                    errMsg = errMsg.substring(0, 47) + "...";
                }
                sb.append(String.format("| %s | %s -> %s | %s | %.1f | %s |\n",
                        tc.getId(), tc.getSourceDialect(), tc.getTargetDialect(), status, r.getLatencyUs(), errMsg));
            }

            return sb.toString();
        }
    }
}
