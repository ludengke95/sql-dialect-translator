package com.translator.core.rewrite.rule;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.Collections;
import java.util.Set;

/**
 * TODAY() → CURRENT_DATE 改写规则（SQL Server → 其他方言）。
 * 适用于所有方言互转。
 */
public class TodayToCurrentDateRule extends FunctionRewriteRule {

    @Override
    protected Set<String> getFunctionNames() {
        return Collections.singleton("TODAY");
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
        return SqlStdOperatorTable.CURRENT_DATE.createCall(SqlParserPos.ZERO);
    }
}
