package com.translator.core.postprocessor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * MySQL 目标方言 SQL 后处理器。
 * 组合了 Interval 归一化与 Between Asymmetric 擦除。
 */
public class MysqlTargetPostProcessor implements TargetDialectPostProcessor {

    private final PostProcessorChain chain = new PostProcessorChain(
            DialectType.MYSQL,
            Arrays.asList(new IntervalSyntaxPostProcessor(), new BetweenAsymmetricPostProcessor()));

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        return chain.process(sql, sourceDialect);
    }
}
