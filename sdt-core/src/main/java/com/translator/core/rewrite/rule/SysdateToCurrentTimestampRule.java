package com.translator.core.rewrite.rule;

import java.util.Collections;
import java.util.Set;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;

/**
 * SYSDATE → CURRENT_TIMESTAMP 改写规则（Oracle → 其他方言）。
 * 适用于所有方言互转。
 */
public class SysdateToCurrentTimestampRule extends FunctionRewriteRule {

    @Override
    protected Set<String> getFunctionNames() {
        return Collections.singleton("SYSDATE");
    }

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.emptySet();
    }

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.emptySet();
    }

    @Override
    public SqlNode apply(SqlNode node) {
        return SqlStdOperatorTable.CURRENT_TIMESTAMP.createCall(SqlParserPos.ZERO);
    }
}
