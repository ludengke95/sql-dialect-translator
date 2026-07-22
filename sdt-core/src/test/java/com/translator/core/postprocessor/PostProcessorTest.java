package com.translator.core.postprocessor;

import org.junit.Assert;
import org.junit.Test;

import com.translator.core.DialectType;

/**
 * 目标方言后处理器 PostProcessor 单元测试。
 */
public class PostProcessorTest {

    @Test
    public void testMysqlTargetPostProcessor() {
        TargetDialectPostProcessor processor = PostProcessorRegistry.getProcessor(DialectType.MYSQL);
        Assert.assertEquals(DialectType.MYSQL, processor.getTargetDialect());

        String input = "SELECT DATE '1998-12-01' - INTERVAL '90' DAY";
        String output = processor.process(input, DialectType.POSTGRESQL);
        Assert.assertEquals("SELECT DATE '1998-12-01' - INTERVAL 90 DAY", output);

        String input2 = "SELECT DATE '1998-12-01' - INTERVAL '90 DAYS'";
        String output2 = processor.process(input2, DialectType.POSTGRESQL);
        Assert.assertEquals("SELECT DATE '1998-12-01' - INTERVAL 90 DAY", output2);
    }

    @Test
    public void testPostgresTargetPostProcessor() {
        TargetDialectPostProcessor processor = PostProcessorRegistry.getProcessor(DialectType.POSTGRESQL);
        Assert.assertEquals(DialectType.POSTGRESQL, processor.getTargetDialect());

        String input = "SELECT CAST('1998-12-01' AS TIMESTAMP) + '90 DAYS'";
        String output = processor.process(input, DialectType.MYSQL);
        Assert.assertEquals("SELECT CAST('1998-12-01' AS TIMESTAMP) + INTERVAL '90 DAYS'", output);
    }

    @Test
    public void testCleanQuotedFunctionNames() {
        TargetDialectPostProcessor processor = PostProcessorRegistry.getProcessor(DialectType.MYSQL);
        String input = "SELECT \"SUBSTR\"(c_name, 1, 2) FROM users";
        String output = processor.process(input, DialectType.MYSQL);
        Assert.assertEquals("SELECT SUBSTR(c_name, 1, 2) FROM users", output);
    }
}
