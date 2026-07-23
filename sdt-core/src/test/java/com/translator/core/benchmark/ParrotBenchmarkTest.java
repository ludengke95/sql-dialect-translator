package com.translator.core.benchmark;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * PARROT Benchmark 单元测试套件
 */
public class ParrotBenchmarkTest {

    @Test
    public void testParrotBenchmarkSampleDataset() throws Exception {
        // 1. 加载示例测试数据集（Legacy 格式，自动嗅探）
        List<ParrotTestCase> testCases = ParrotDataLoader.loadFromResource("parrot/parrot_sample_cases.json");
        Assert.assertNotNull(testCases);
        Assert.assertFalse("测试用例集不能为空", testCases.isEmpty());

        // 2. 运行基准测试评测
        ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
        ParrotBenchmarkRunner.BenchmarkReport report = runner.runBenchmark(testCases);

        // 3. 断言及输出评测报告
        System.out.println("=== Sample Dataset Benchmark Summary Report ===");
        System.out.println(report.toSummaryReport());

        Assert.assertTrue("PARROT 转换成功率应在 50% 以上", report.getSuccessRate() >= 50.0);
        Assert.assertTrue("平均转换延迟应小于 50ms (50000μs)", report.getAverageLatencyUs() < 50000.0);
    }

    @Test
    public void testParrotBenchmarkFullDataset() throws Exception {
        // 1. 加载全量/扩展测试数据集（Legacy 格式，自动嗅探）
        List<ParrotTestCase> testCases = ParrotDataLoader.loadFromResource("parrot/parrot_full_cases.json");
        Assert.assertNotNull(testCases);
        Assert.assertFalse("全量测试用例集不能为空", testCases.isEmpty());

        // 2. 运行全量评测
        ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
        ParrotBenchmarkRunner.BenchmarkReport report = runner.runBenchmark(testCases);

        // 3. 断言及输出评测报告
        System.out.println("=== Full Dataset Benchmark Report ===");
        System.out.println(report.toMarkdownReport());

        // 验证分组统计与汇总报告
        String summary = report.toSummaryReport();
        Assert.assertTrue("Summary 报告应包含分组统计标题", summary.contains("方言方向分组统计"));
        Assert.assertTrue("Summary 报告应包含全量汇总行", summary.contains("全量汇总 (TOTAL)"));
        Assert.assertFalse("Summary 报告不应包含逐条用例明细表", summary.contains("## 用例明细"));

        Map<String, ParrotBenchmarkRunner.DirectionStats> statsMap = report.getDirectionStatsMap();
        Assert.assertFalse("分组统计信息不能为空", statsMap.isEmpty());
        Assert.assertTrue("应包含 POSTGRESQL -> MYSQL 分组", statsMap.containsKey("POSTGRESQL -> MYSQL"));

        Assert.assertTrue("全量 PARROT 转换成功率应在 50% 以上", report.getSuccessRate() >= 50.0);
        Assert.assertTrue("全量平均转换延迟应小于 50ms (50000μs)", report.getAverageLatencyUs() < 50000.0);
    }

    @Test
    public void testExportToCsvFile() throws Exception {
        List<ParrotTestCase> testCases = ParrotDataLoader.loadFromResource("parrot/parrot_sample_cases.json");
        ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
        ParrotBenchmarkRunner.BenchmarkReport report = runner.runBenchmark(testCases);

        File tempCsv = File.createTempFile("parrot_test_output", ".csv");
        tempCsv.deleteOnExit();

        ParrotDataLoader.exportToCsvFile(report, tempCsv.getAbsolutePath());
        Assert.assertTrue("导出的 CSV 文件必须存在且非空", tempCsv.exists() && tempCsv.length() > 0);

        List<String> lines = Files.readAllLines(tempCsv.toPath());
        Assert.assertFalse("CSV 文件行不能为空", lines.isEmpty());

        // 第一行应当为 BOM + 表头 id,sourceDialect,targetDialect,sourceSql,translatedSql,success,syntaxPass
        String header = lines.get(0);
        Assert.assertTrue("表头必须包含源方言与目标方言", header.contains("sourceDialect") && header.contains("targetDialect"));
        Assert.assertTrue("表头必须包含源 SQL 与转换后 SQL", header.contains("sourceSql") && header.contains("translatedSql"));
        Assert.assertTrue("表头必须包含翻译成功状态与 ANTLR 语法通过状态", header.contains("success") && header.contains("syntaxPass"));

        // 校验行数（表头 + 用例数）
        Assert.assertTrue("CSV 行数应当大于表头行", lines.size() > 1);
    }

    @Test
    public void testDmlFiltering() throws Exception {
        // 验证 DML 工具检测函数
        Assert.assertTrue("SELECT 应识别为 DML", ParrotDataLoader.isDmlQuery("SELECT * FROM users"));
        Assert.assertTrue("带注释的 SELECT 应识别为 DML", ParrotDataLoader.isDmlQuery("/* comment */ SELECT 1"));
        Assert.assertTrue("INSERT 应识别为 DML", ParrotDataLoader.isDmlQuery("INSERT INTO t VALUES(1)"));
        Assert.assertTrue("UPDATE 应识别为 DML", ParrotDataLoader.isDmlQuery("UPDATE t SET a = 1"));
        Assert.assertTrue("DELETE 应识别为 DML", ParrotDataLoader.isDmlQuery("DELETE FROM t"));
        Assert.assertTrue("WITH 应识别为 DML", ParrotDataLoader.isDmlQuery("WITH cte AS (SELECT 1) SELECT * FROM cte"));

        Assert.assertFalse("CREATE TABLE 应被排除", ParrotDataLoader.isDmlQuery("CREATE TABLE t (id INT)"));
        Assert.assertFalse("ALTER TABLE 应被排除", ParrotDataLoader.isDmlQuery("ALTER TABLE t ADD COLUMN c INT"));
        Assert.assertFalse("DROP TABLE 应被排除", ParrotDataLoader.isDmlQuery("DROP TABLE t"));

        // 测试加载 DDL 和 DML 混合的 Native JSON
        String mixedJson = "[\n"
                + "  {\n"
                + "    \"id\": \"dml_row\",\n"
                + "    \"mysql\": \"SELECT 1\",\n"
                + "    \"postgres\": \"SELECT 1\"\n"
                + "  },\n"
                + "  {\n"
                + "    \"id\": \"ddl_row\",\n"
                + "    \"mysql\": \"CREATE TABLE t (x int)\",\n"
                + "    \"postgres\": \"CREATE TABLE t (x int)\"\n"
                + "  }\n"
                + "]";

        List<ParrotTestCase> cases = ParrotDataLoader.parse(mixedJson);
        Assert.assertEquals("DDL 行应被自动过滤，仅保留 1 行 DML (2 个方言对)", 2, cases.size());
        Assert.assertTrue("保留的用例应为 DML", cases.get(0).getId().startsWith("dml_row"));
    }

    @Test
    public void testNativeFormatParsing() throws Exception {
        // 验证 Native 格式解析：模拟官方 PARROT 数据集的一行
        String nativeJson = "[\n"
                + "  {\n"
                + "    \"id\": \"native_test_001\",\n"
                + "    \"norm\": \"SELECT id FROM users\",\n"
                + "    \"mysql\": \"SELECT id FROM users\",\n"
                + "    \"postgres\": \"SELECT id FROM users\",\n"
                + "    \"oracle\": \"SELECT id FROM users\",\n"
                + "    \"tsql\": \"SELECT id FROM users\",\n"
                + "    \"sqlite\": \"SELECT id FROM users\"\n"
                + "  }\n"
                + "]";

        List<ParrotTestCase> cases = ParrotDataLoader.parse(nativeJson);

        // 4 种方言支持：MYSQL, POSTGRESQL, ORACLE, SQLSERVER
        // 全排列对数 = 4 * 3 = 12
        Assert.assertFalse("Native 格式应能解析出测试用例", cases.isEmpty());
        Assert.assertEquals("一行 native row 含 4 种支持方言，应展开为 12 个翻译对", 12, cases.size());

        // 验证字段格式
        for (ParrotTestCase tc : cases) {
            Assert.assertNotNull("id 不能为空", tc.getId());
            Assert.assertTrue("id 应含原始 row id", tc.getId().startsWith("native_test_001"));
            Assert.assertNotNull("sourceDialect 不能为空", tc.getSourceDialect());
            Assert.assertNotNull("targetDialect 不能为空", tc.getTargetDialect());
            Assert.assertNotNull("sourceSql 不能为空", tc.getSourceSql());
            Assert.assertNotNull("expectedSql 不能为空", tc.getExpectedSql());
            Assert.assertNotEquals("sourceDialect 和 targetDialect 不能相同",
                    tc.getSourceDialect(), tc.getTargetDialect());
        }
    }

    @Test
    public void testNativeFormatWithPartialDialects() throws Exception {
        // 验证：若某行只有 mysql 和 postgres 两种方言
        String nativeJson = "[\n"
                + "  {\n"
                + "    \"id\": \"partial_001\",\n"
                + "    \"norm\": \"SELECT 1\",\n"
                + "    \"mysql\": \"SELECT 1\",\n"
                + "    \"postgres\": \"SELECT 1\"\n"
                + "  }\n"
                + "]";

        List<ParrotTestCase> cases = ParrotDataLoader.parse(nativeJson);

        // 2 种方言，展开 2*1=2 个翻译对
        Assert.assertEquals("两种方言应展开为 2 个翻译对", 2, cases.size());

        List<String> directions = Arrays.asList(
                cases.get(0).getSourceDialect() + "->" + cases.get(0).getTargetDialect(),
                cases.get(1).getSourceDialect() + "->" + cases.get(1).getTargetDialect());
        Assert.assertTrue("应包含 MYSQL->POSTGRESQL", directions.contains("MYSQL->POSTGRESQL"));
        Assert.assertTrue("应包含 POSTGRESQL->MYSQL", directions.contains("POSTGRESQL->MYSQL"));
    }

    @Test
    public void testNativeFormatRunBenchmark() throws Exception {
        // 端到端验证：Native 格式加载 + 翻译运行
        String nativeJson = "[\n"
                + "  {\n"
                + "    \"id\": \"e2e_001\",\n"
                + "    \"norm\": \"SELECT COALESCE(x, 0) FROM t\",\n"
                + "    \"mysql\": \"SELECT IFNULL(x, 0) FROM t\",\n"
                + "    \"postgres\": \"SELECT COALESCE(x, 0) FROM t\"\n"
                + "  }\n"
                + "]";

        List<ParrotTestCase> cases = ParrotDataLoader.parse(nativeJson);
        Assert.assertEquals(2, cases.size());

        ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
        ParrotBenchmarkRunner.BenchmarkReport report = runner.runBenchmark(cases);

        System.out.println("=== Native Format E2E Summary Report ===");
        System.out.println(report.toSummaryReport());

        Assert.assertEquals("总用例数应为 2", 2, report.getTotalCases());
        Assert.assertTrue("平均延迟应在合理范围内", report.getAverageLatencyUs() < 100000.0);
    }

    @Test
    public void testFormatSniffing() throws Exception {
        // 验证格式嗅探：Legacy 格式
        String legacyJson = "[\n"
                + "  {\n"
                + "    \"id\": \"L001\",\n"
                + "    \"sourceDialect\": \"MYSQL\",\n"
                + "    \"targetDialect\": \"POSTGRESQL\",\n"
                + "    \"sourceSql\": \"SELECT IFNULL(x, 0) FROM t\",\n"
                + "    \"expectedSql\": \"SELECT COALESCE(x, 0) FROM t\",\n"
                + "    \"category\": \"test\"\n"
                + "  }\n"
                + "]";

        List<ParrotTestCase> cases = ParrotDataLoader.parse(legacyJson);
        Assert.assertEquals("Legacy 格式应解析出 1 条用例", 1, cases.size());
        Assert.assertEquals("MYSQL", cases.get(0).getSourceDialect());
        Assert.assertEquals("POSTGRESQL", cases.get(0).getTargetDialect());
    }
}
