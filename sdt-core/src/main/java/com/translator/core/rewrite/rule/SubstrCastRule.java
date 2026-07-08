package com.translator.core.rewrite.rule;

import java.util.*;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;

/**
 * MySQL SUBSTR/SUBSTRING 第一个参数 → CAST AS VARCHAR 改写规则。
 * <p>
 * 仅适用于 MySQL → PostgreSQL 转换。
 * PG 的 SUBSTR 第一个参数必须是 text 类型，MySQL 可隐式转换，
 * 因此无条件将第一个参数包装为 CAST(expr AS VARCHAR)。
 * </p>
 * <p>
 * 示例：SUBSTR(12312312313, 1, 3) → SUBSTR(CAST(12312312313 AS VARCHAR), 1, 3)
 * </p>
 */
public class SubstrCastRule extends FunctionRewriteRule {

    /** 需要处理的函数名集合 */
    private static final Set<String> FUNC_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("SUBSTR", "SUBSTRING")));

    @Override
    protected Set<String> getFunctionNames() {
        return FUNC_NAMES;
    }

    /** 仅适用于 MySQL 源方言 */
    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    /** 仅适用于 PostgreSQL 目标方言 */
    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.POSTGRESQL);
    }

    /**
     * 将 SUBSTR/SUBSTRING 的第一个操作数包装为 CAST(operand AS VARCHAR)。
     * 其余操作数保持不变。
     */
    @Override
    public SqlNode apply(SqlNode node) {
        SqlCall call = (SqlCall) node;
        List<SqlNode> operands = call.getOperandList();
        if (operands.isEmpty()) {
            return call;
        }

        // 将第一个操作数包装为 CAST(firstOperand AS VARCHAR)
        SqlNode firstOperand = operands.get(0);
        SqlNode castNode = SqlStdOperatorTable.CAST.createCall(
                SqlParserPos.ZERO,
                firstOperand,
                new SqlDataTypeSpec(
                        new SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, SqlParserPos.ZERO), SqlParserPos.ZERO));

        // 构建新的操作数列表：第一个替换为 CAST 节点，其余不变
        List<SqlNode> newOperands = new ArrayList<>(operands);
        newOperands.set(0, castNode);

        return call.getOperator().createCall(call.getParserPosition(), newOperands);
    }
}
