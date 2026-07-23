package com.translator.core.postprocessor;

import com.translator.core.DialectType;

/**
 * 默认通用目标方言 SQL 后处理器。
 * 当目标方言未注册专用后处理器时使用，仅执行公共后处理逻辑。
 */
public class DefaultTargetPostProcessor extends AbstractTargetPostProcessor {

    @Override
    public DialectType getTargetDialect() {
        return null;
    }

    @Override
    protected String doProcess(String sql, DialectType sourceDialect) {
        return sql;
    }
}
