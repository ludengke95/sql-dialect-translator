package com.translator.core.rewrite.rule;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 空值处理函数 → COALESCE 改写规则。
 * <p>
 * 将 IFNULL(a,b)、NVL(a,b)、ISNULL(a,b) 改写为 COALESCE(a,b)。
 * 适用于所有方言互转。
 * </p>
 */
public class NullToCoalesceRule extends FunctionRewriteRule {

    /** 需要改写的源函数名集合 */
    private static final Set<String> FUNC_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("IFNULL", "NVL", "ISNULL")));

    @Override
    protected Set<String> getFunctionNames() {
        return FUNC_NAMES;
    }

    /** 适用于所有源方言 */
    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.emptySet();
    }

    /** 适用于所有目标方言 */
    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.emptySet();
    }

    /**
     * 将 IFNULL/NVL/ISNULL 改写为 COALESCE。
     * 保持原参数列表不变。
     */
    @Override
    public SqlNode apply(SqlNode node) {
        SqlCall call = (SqlCall) node;
        return SqlStdOperatorTable.COALESCE.createCall(
                call.getParserPosition(),
                call.getOperandList());
    }
}
