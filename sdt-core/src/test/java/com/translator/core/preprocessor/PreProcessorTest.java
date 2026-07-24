package com.translator.core.preprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 细粒度源方言前置处理器单元测试。
 */
public class PreProcessorTest {

    @Test
    public void testLineCommentPreProcessor() {
        LineCommentPreProcessor processor = new LineCommentPreProcessor();
        String sql = "SELECT * FROM users -- this is comment\nWHERE id = 1";
        String result = processor.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT * FROM users\nWHERE id = 1", result);
    }

    @Test
    public void testBacktickQuotingPreProcessor() {
        BacktickQuotingPreProcessor processor = new BacktickQuotingPreProcessor();
        String sql = "SELECT `name`, `age` FROM `users`";
        String result = processor.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT \"name\", \"age\" FROM \"users\"", result);
    }

    @Test
    public void testBracketQuotingPreProcessor() {
        BracketQuotingPreProcessor processor = new BracketQuotingPreProcessor();
        String sql = "SELECT [name] FROM [users]";
        String result = processor.process(sql, DialectType.SQLSERVER, DialectType.MYSQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT \"name\" FROM \"users\"", result);
    }

    @Test
    public void testGroupConcatSeparatorPreProcessor() {
        GroupConcatSeparatorPreProcessor processor = new GroupConcatSeparatorPreProcessor();
        String sql = "SELECT GROUP_CONCAT(name SEPARATOR ',') FROM t";
        String result = processor.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT GROUP_CONCAT(name, ',') FROM t", result);
    }

    @Test
    public void testSqlServerTopPreProcessor() {
        SqlServerTopPreProcessor processor = new SqlServerTopPreProcessor();
        String sql = "SELECT TOP 10 * FROM users";
        String result =
                processor.process(sql, DialectType.SQLSERVER, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT * FROM users LIMIT 10", result);
    }

    @Test
    public void testLimitOffsetSyntaxPreProcessor() {
        LimitOffsetSyntaxPreProcessor processor = new LimitOffsetSyntaxPreProcessor();
        String sql = "SELECT * FROM users LIMIT 10, 20";
        String result = processor.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("SELECT * FROM users LIMIT 20 OFFSET 10", result);
    }

    @Test
    public void testUpdateJoinSyntaxPreProcessor() {
        UpdateJoinSyntaxPreProcessor processor = new UpdateJoinSyntaxPreProcessor();
        String sql = "UPDATE t1 INNER JOIN t2 ON t1.id = t2.id SET t1.name = t2.name";
        String result = processor.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertEquals("UPDATE t1 SET t1.name = t2.name FROM t2 WHERE t1.id = t2.id", result);
    }

    @Test
    public void testPreProcessorRegistry() {
        String sql = "SELECT `name` FROM `users` LIMIT 10, 5";
        String result =
                PreProcessorRegistry.process(sql, DialectType.MYSQL, DialectType.POSTGRESQL, TranslationConfig.DEFAULT);
        assertNotNull(result);
        assertEquals("SELECT \"name\" FROM \"users\" LIMIT 5 OFFSET 10", result);
    }
}
