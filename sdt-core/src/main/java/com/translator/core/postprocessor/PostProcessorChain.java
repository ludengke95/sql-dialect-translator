package com.translator.core.postprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.translator.core.DialectType;

/**
 * 目标方言后处理器责任链/管道，按顺序依次执行组嵌的所有后处理器。
 */
public class PostProcessorChain implements TargetDialectPostProcessor {

    private final DialectType targetDialect;
    private final List<TargetDialectPostProcessor> processors;

    public PostProcessorChain(DialectType targetDialect, List<TargetDialectPostProcessor> processors) {
        this.targetDialect = targetDialect;
        this.processors = processors != null
                ? Collections.unmodifiableList(new ArrayList<>(processors))
                : Collections.emptyList();
    }

    @Override
    public DialectType getTargetDialect() {
        return targetDialect;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        String result = sql;
        for (TargetDialectPostProcessor processor : processors) {
            result = processor.process(result, sourceDialect);
        }
        return result;
    }

    public List<TargetDialectPostProcessor> getProcessors() {
        return processors;
    }
}
