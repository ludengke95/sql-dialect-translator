package com.translator.core.rewrite.rule;

import java.util.*;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.parser.SqlParserPos;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;

/**
 * Oracle DECODE → CASE WHEN 改写规则。
 * <p>
 * DECODE(expr, search1, result1, search2, result2, ..., default)
 * → CASE expr WHEN search1 THEN result1 WHEN search2 THEN result2 ELSE default END
 * </p>
 * 适用于所有方言互转。
 */
public class DecodeToCaseRule extends FunctionRewriteRule {

    @Override
    protected Set<String> getFunctionNames() {
        return Collections.singleton("DECODE");
    }

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.emptySet();
    }

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.emptySet();
    }

    /**
     * DECODE 改写为 CASE WHEN。
     * DECODE 至少需要 3 个参数: expr, search1, result1
     */
    @Override
    public SqlNode apply(SqlNode node) {
        SqlCall call = (SqlCall) node;
        List<SqlNode> operands = call.getOperandList();
        if (operands.size() < 3) {
            // DECODE 至少需要 expr, search1, result1
            return call;
        }

        SqlNode value = operands.get(0); // expr
        List<SqlNode> whenList = new ArrayList<>();
        List<SqlNode> thenList = new ArrayList<>();
        SqlNode elseExpr = null;

        // 剩余参数: [search1, result1, search2, result2, ..., default?]
        int remaining = operands.size() - 1;
        // 如果剩余参数是奇数，最后一个为 default
        boolean hasDefault = remaining % 2 == 1;

        int pairEnd = hasDefault ? remaining - 1 : remaining;
        for (int i = 1; i <= pairEnd; i += 2) {
            whenList.add(operands.get(i)); // search
            thenList.add(operands.get(i + 1)); // result
        }
        if (hasDefault) {
            elseExpr = operands.get(operands.size() - 1);
        }

        SqlNodeList when = new SqlNodeList(whenList, SqlParserPos.ZERO);
        SqlNodeList then = new SqlNodeList(thenList, SqlParserPos.ZERO);

        return new SqlCase(SqlParserPos.ZERO, value, when, then, elseExpr);
    }
}
