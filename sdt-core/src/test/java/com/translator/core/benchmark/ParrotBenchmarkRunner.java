package com.translator.core.benchmark;

import com.translator.core.DialectType;
import com.translator.core.SqlTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PARROT Benchmark 评测执行器
 */
public class ParrotBenchmarkRunner {
    private static final Logger log = LoggerFactory.getLogger(ParrotBenchmarkRunner.class);

    public ParrotBenchmarkRunner() {
    }

    /**
     * CLI 批量评估与导出入口
     * Usage: java -cp ... com.translator.core.benchmark.ParrotBenchmarkRunner <inputJsonPath> <outputJsonPath> [summaryOutputPath]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ParrotBenchmarkRunner <inputJsonPath> <outputJsonPath> [summaryOutputPath]");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];
        String summaryPath = args.length >= 3 ? args[2] : getSummaryPath(outputPath);

        try {
            System.out.println("Loading test cases from: " + inputPath);
            List<ParrotTestCase> testCases = ParrotDataLoader.loadFromFile(inputPath);
            System.out.println("Loaded " + testCases.size() + " test cases.");

            ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
            BenchmarkReport report = runner.runBenchmark(testCases);

            // 输出全量报告至控制台
            System.out.println(report.toMarkdownReport());

            // 导出精简汇总报告
            writeStringToFile(report.toSummaryReport(), summaryPath);
            System.out.println("Summary report written to: " + summaryPath);

            System.out.println("Exporting results to: " + outputPath);
            ParrotDataLoader.exportToJsonFile(report, outputPath);
            System.out.println("Export completed successfully!");
        } catch (Exception e) {
            log.error("Failed to run benchmark", e);
            System.exit(1);
        }
    }

    private static String getSummaryPath(String outputPath) {
        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null) {
            return new File(parent, "summary_output.log").getAbsolutePath();
        }
        return "summary_output.log";
    }

    private static void writeStringToFile(String content, String filePath) throws Exception {
        File file = new File(filePath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
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
     * 单个方言转换方向的统计数据实体
     */
    public static class DirectionStats {
        private final String direction;
        private final int totalCases;
        private final int successCases;
        private final double successRate;
        private final double avgLatencyUs;
        private final double p95LatencyUs;

        public DirectionStats(String direction, List<ParrotResult> results) {
            this.direction = direction;
            this.totalCases = results.size();
            int success = 0;
            long sumNs = 0;
            List<Long> latencies = new ArrayList<>();
            for (ParrotResult r : results) {
                if (r.isTranslationSuccess()) {
                    success++;
                }
                sumNs += r.getLatencyNs();
                latencies.add(r.getLatencyNs());
            }
            this.successCases = success;
            this.successRate = totalCases == 0 ? 0.0 : (success * 100.0) / totalCases;
            this.avgLatencyUs = totalCases == 0 ? 0.0 : (sumNs / 1000.0) / totalCases;

            if (latencies.isEmpty()) {
                this.p95LatencyUs = 0.0;
            } else {
                Collections.sort(latencies);
                int idx = (int) Math.ceil(0.95 * latencies.size()) - 1;
                idx = Math.max(0, Math.min(idx, latencies.size() - 1));
                this.p95LatencyUs = latencies.get(idx) / 1000.0;
            }
        }

        public String getDirection() {
            return direction;
        }

        public int getTotalCases() {
            return totalCases;
        }

        public int getSuccessCases() {
            return successCases;
        }

        public double getSuccessRate() {
            return successRate;
        }

        public double getAvgLatencyUs() {
            return avgLatencyUs;
        }

        public double getP95LatencyUs() {
            return p95LatencyUs;
        }
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

        /**
         * 获取按方言转换方向（如 MYSQL -> POSTGRESQL）分组的统计信息
         */
        public Map<String, DirectionStats> getDirectionStatsMap() {
            Map<String, List<ParrotResult>> grouped = new LinkedHashMap<>();
            for (ParrotResult r : results) {
                String src = r.getTestCase().getSourceDialect();
                String tgt = r.getTestCase().getTargetDialect();
                String dir = (src != null ? src.toUpperCase() : "UNKNOWN") + " -> " + (tgt != null ? tgt.toUpperCase() : "UNKNOWN");
                grouped.computeIfAbsent(dir, k -> new ArrayList<>()).add(r);
            }

            Map<String, DirectionStats> statsMap = new LinkedHashMap<>();
            for (Map.Entry<String, List<ParrotResult>> entry : grouped.entrySet()) {
                statsMap.put(entry.getKey(), new DirectionStats(entry.getKey(), entry.getValue()));
            }
            return statsMap;
        }

        /**
         * 生成精简的 Step Summary 统计汇总报告（不含逐条明细）
         */
        public String toSummaryReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("# 🦜 SDT - PARROT Benchmark 评测汇总报告\n\n");
            sb.append(String.format("- **总用例数**: %d\n", totalCases));
            sb.append(String.format("- **翻译成功数**: %d (%.2f%%)\n", getSuccessCases(), getSuccessRate()));
            sb.append(String.format("- **平均转换延迟**: %.2f μs (%.3f ms)\n", getAverageLatencyUs(), getAverageLatencyUs() / 1000.0));
            sb.append(String.format("- **P95 转换延迟**: %.2f μs (%.3f ms)\n", getP95LatencyUs(), getP95LatencyUs() / 1000.0));
            sb.append(String.format("- **总评估耗时**: %d ms\n\n", totalDurationMs));

            sb.append("### 📊 方言方向分组统计\n\n");
            sb.append("| 方言转换方向 | 用例总数 | 成功数 | 成功率 | 平均延迟 (μs) | P95 延迟 (μs) |\n");
            sb.append("| --- | --- | --- | --- | --- | --- |\n");

            Map<String, DirectionStats> dirMap = getDirectionStatsMap();
            for (Map.Entry<String, DirectionStats> entry : dirMap.entrySet()) {
                DirectionStats s = entry.getValue();
                sb.append(String.format("| %s | %d | %d | %.2f%% | %.1f | %.1f |\n",
                        s.getDirection(), s.getTotalCases(), s.getSuccessCases(), s.getSuccessRate(), s.getAvgLatencyUs(), s.getP95LatencyUs()));
            }

            // 最后一行为全量汇总行
            sb.append(String.format("| **全量汇总 (TOTAL)** | **%d** | **%d** | **%.2f%%** | **%.1f** | **%.1f** |\n",
                    totalCases, getSuccessCases(), getSuccessRate(), getAverageLatencyUs(), getP95LatencyUs()));

            return sb.toString();
        }

        /**
         * 生成全量报告（包含统计汇总表格及逐条用例明细）
         */
        public String toMarkdownReport() {
            StringBuilder sb = new StringBuilder();
            sb.append(toSummaryReport());
            sb.append("\n## 用例明细\n\n");
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
