package com.translator.core.preprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 源方言 SQL 前处理器责任链节点。
 */
public class PreProcessorChain implements SourceDialectPreProcessor {

    private final DialectType sourceDialect;
    private final List<SourceDialectPreProcessor> processors;

    public PreProcessorChain(DialectType sourceDialect, List<SourceDialectPreProcessor> processors) {
        this.sourceDialect = sourceDialect;
        this.processors = Collections.unmodifiableList(new ArrayList<>(processors));
    }

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(sourceDialect);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        String result = sql;
        for (SourceDialectPreProcessor processor : processors) {
            result = processor.process(result, sourceDialect, targetDialect, config);
        }
        return result;
    }

    public List<SourceDialectPreProcessor> getProcessors() {
        return processors;
    }
}
