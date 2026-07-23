package com.translator.core.rewrite.rule;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import com.translator.core.DialectType;
import com.translator.core.rewrite.SqlRewriteRule;

/**
 * PostgreSQL 日期算术表达式改写规则 (POSTGRESQL → MYSQL)。
 * 
 * <p>拦截 AST 中的二元 +/- 运算符：
 * <ul>
 *   <li>date '1998-12-01' - interval '90 day'  → DATE_SUB('1998-12-01', INTERVAL 90 DAY)</li>
 *   <li>date '1994-01-01' + interval '1 year' → DATE_ADD('1994-01-01', INTERVAL 1 YEAR)</li>
 * </ul>
 */
public class PgDateArithmeticRewriteRule implements SqlRewriteRule {

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.POSTGRESQL);
    }

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    /**
     * 判断当前 AST 节点是否匹配该规则。
     */
    @Override
    public boolean matches(SqlNode node) {
        if (!(node instanceof SqlCall)) {
            return false;
        }

        SqlCall call = (SqlCall) node;
        SqlKind kind = call.getKind();

        // 1. 仅拦截二元减法 (MINUS) 或二元加法 (PLUS)
        if (kind != SqlKind.MINUS && kind != SqlKind.PLUS) {
            return false;
        }

        List<SqlNode> operands = call.getOperandList();
        if (operands.size() != 2) {
            return false;
        }

        // 2. 检查右操作数是否为 INTERVAL 时间间隔节点
        SqlNode rightNode = operands.get(1);
        return isIntervalNode(rightNode);
    }

    private static final SqlFunction DATE_ADD_FUNC = new SqlFunction(
            "DATE_ADD",
            SqlKind.OTHER_FUNCTION,
            null,
            null,
            null,
            org.apache.calcite.sql.SqlFunctionCategory.TIMEDATE);

    private static final SqlFunction DATE_SUB_FUNC = new SqlFunction(
            "DATE_SUB",
            SqlKind.OTHER_FUNCTION,
            null,
            null,
            null,
            org.apache.calcite.sql.SqlFunctionCategory.TIMEDATE);

    /**
     * 针对匹配到的 SqlNode 执行改写逻辑。
     */
    @Override
    public SqlNode apply(SqlNode node) {
        SqlCall call = (SqlCall) node;
        SqlKind kind = call.getKind();
        List<SqlNode> operands = call.getOperandList();

        SqlNode leftNode = operands.get(0);
        SqlNode rightNode = operands.get(1);

        // 减法匹配 DATE_SUB，加法匹配 DATE_ADD
        SqlFunction targetFunc = (kind == SqlKind.MINUS) ? DATE_SUB_FUNC : DATE_ADD_FUNC;

        // 构建新的 SqlCall: DATE_SUB(leftNode, rightNode) 或 DATE_ADD(leftNode, rightNode)
        return targetFunc.createCall(call.getParserPosition(), leftNode, rightNode);
    }

    /**
     * 判断 SqlNode 是否代表一个 INTERVAL 节点。
     */
    private boolean isIntervalNode(SqlNode node) {
        if (node instanceof SqlIntervalLiteral) {
            return true;
        }
        if (node != null) {
            String sqlString = node.toString().toUpperCase();
            return sqlString.contains("INTERVAL");
        }
        return false;
    }
}
