package com.translator.core.postprocessor;

import java.util.Set;

import com.translator.core.DialectType;

/**
 * 目标方言后处理器抽象装饰器。
 */
public abstract class AbstractPostProcessorDecorator implements TargetDialectPostProcessor {

    protected final TargetDialectPostProcessor delegate;

    public AbstractPostProcessorDecorator(TargetDialectPostProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<DialectType> getTargetDialects() {
        return delegate != null ? delegate.getTargetDialects() : java.util.Collections.emptySet();
    }

    @Override
    public boolean supports(DialectType sourceDialect, DialectType targetDialect) {
        return delegate == null || delegate.supports(sourceDialect, targetDialect);
    }

    @Override
    public int getOrder() {
        return delegate != null ? delegate.getOrder() : 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        String result = (delegate != null) ? delegate.process(sql, sourceDialect) : sql;
        if (result == null || result.isEmpty()) {
            return result;
        }
        return doProcess(result, sourceDialect);
    }

    /**
     * 当前装饰器的具体加工扩展点。
     *
     * @param sql           经被装饰者处理后的 SQL
     * @param sourceDialect 源方言
     * @return 加工后的目标 SQL
     */
    protected abstract String doProcess(String sql, DialectType sourceDialect);
}
