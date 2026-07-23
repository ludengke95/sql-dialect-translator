package com.translator.core.benchmark;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * PARROT Benchmark 单元测试套件
 */
public class ParrotBenchmarkTest {

    @Test
    public void testParrotBenchmarkSampleDataset() throws Exception {
        // 1. 加载测试数据集
        List<ParrotTestCase> testCases = ParrotDataLoader.loadFromResource("parrot/parrot_sample_cases.json");
        Assert.assertNotNull(testCases);
        Assert.assertFalse("测试用例集不能为空", testCases.isEmpty());

        // 2. 运行基准测试评测
        ParrotBenchmarkRunner runner = new ParrotBenchmarkRunner();
        ParrotBenchmarkRunner.BenchmarkReport report = runner.runBenchmark(testCases);

        // 3. 断言及输出评测报告
        System.out.println(report.toMarkdownReport());

        Assert.assertTrue("PARROT 转换成功率应在 50% 以上", report.getSuccessRate() >= 50.0);
        Assert.assertTrue("平均转换延迟应小于 50ms (50000μs)", report.getAverageLatencyUs() < 50000.0);
    }
}
