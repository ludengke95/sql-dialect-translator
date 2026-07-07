package com.translator.core.rewrite;

import com.translator.core.DialectType;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;

import java.util.*;

/**
 * 函数改写 Visitor。
 * 基于 Calcite SqlShuttle，将源方言中的函数调用改写为目标方言的等价形式。
 */
public class FunctionRewriteVisitor extends SqlShuttle {

    /** 单个函数映射规则：将源函数调用改写为目标函数调用 */
    private interface FunctionMapper {
        /**
         * 改写函数调用。
         *
         * @param call 源函数调用
         * @return 改写后的函数调用，如果规则不适用则返回 null
         */
        SqlCall apply(SqlCall call);
    }

    /** 规则映射：源函数名（大写） → 改写规则 */
    private final Map<String, FunctionMapper> rules = new LinkedHashMap<>();

    /**
     * 根据源方言和目标方言构建函数映射规则。
     */
    public FunctionRewriteVisitor(DialectType source, DialectType target) {
        if (source == target) {
            return; // 同方言无需转换
        }
        initRules(source, target);
    }

    private void initRules(DialectType source, DialectType target) {
        // —— 通用空值处理函数映射 ——

        // IFNULL(a, b) → COALESCE(a, b) (MySQL → PG/Oracle)
        addFunctionMapper("IFNULL", this::rewriteToCoalesce);
        // NVL(a, b) → COALESCE(a, b) (Oracle → PG/MySQL)
        addFunctionMapper("NVL", this::rewriteToCoalesce);
        // ISNULL(a, b) → COALESCE(a, b) (SQL Server → PG/MySQL/Oracle)
        addFunctionMapper("ISNULL", this::rewriteToCoalesce);

        // —— 日期时间函数映射 ——

        // NOW() → CURRENT_TIMESTAMP (MySQL → PG/Oracle)
        addFunctionMapper("NOW", call ->
                SqlStdOperatorTable.CURRENT_TIMESTAMP.createCall(SqlParserPos.ZERO)
        );
        // GETDATE() → CURRENT_TIMESTAMP (SQL Server → PG/MySQL)
        addFunctionMapper("GETDATE", call ->
                SqlStdOperatorTable.CURRENT_TIMESTAMP.createCall(SqlParserPos.ZERO)
        );
        // SYSDATE → CURRENT_TIMESTAMP (Oracle → PG/MySQL)
        addFunctionMapper("SYSDATE", call ->
                SqlStdOperatorTable.CURRENT_TIMESTAMP.createCall(SqlParserPos.ZERO)
        );
        // TODAY() → CURRENT_DATE (SQL Server → PG)
        addFunctionMapper("TODAY", call ->
                SqlStdOperatorTable.CURRENT_DATE.createCall(SqlParserPos.ZERO)
        );

        // —— Oracle DECODE → CASE WHEN ——
        addFunctionMapper("DECODE", this::rewriteDecodeToCase);

        // CONCAT(a, b) → a || b (MySQL → PG/Oracle)
        // 注意：这依赖于 Calcite 的 dialect 处理操作符的 unparse 方式
        // Calcite 的 PG dialect 已经能正确输出 CONCAT 或 ||

        // —— MySQL SUBSTR/SUBSTRING 第一个参数 → PG CAST AS VARCHAR ——
        // PG 的 SUBSTR 第一个参数必须是 text 类型，MySQL 可隐式转换
        // 无条件将第一个参数包一层 CAST(... AS VARCHAR)，即使是字符串列也安全（幂等）
        if (target == DialectType.POSTGRESQL && source == DialectType.MYSQL) {
            addFunctionMapper("SUBSTR", this::rewriteSubstrToVarchar);
            addFunctionMapper("SUBSTRING", this::rewriteSubstrToVarchar);
        }
    }

    /**
     * IFNULL/NVL/ISNULL → COALESCE 通用改写。
     */
    private SqlCall rewriteToCoalesce(SqlCall call) {
        SqlNode[] operands = call.getOperandList().toArray(new SqlNode[0]);
        return new SqlBasicCall(
                SqlStdOperatorTable.COALESCE,
                operands,
                call.getParserPosition(),
                call.getFunctionQuantifier()
        );
    }

    /**
     * Oracle DECODE → CASE WHEN 改写。
     * DECODE(expr, search1, result1, search2, result2, ..., default)
     * →
     * CASE expr WHEN search1 THEN result1 WHEN search2 THEN result2 ELSE default END
     */
    private SqlCall rewriteDecodeToCase(SqlCall call) {
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
            whenList.add(operands.get(i));     // search
            thenList.add(operands.get(i + 1)); // result
        }
        if (hasDefault) {
            elseExpr = operands.get(operands.size() - 1);
        }

        SqlNodeList when = new SqlNodeList(whenList, SqlParserPos.ZERO);
        SqlNodeList then = new SqlNodeList(thenList, SqlParserPos.ZERO);

        return new SqlCase(SqlParserPos.ZERO, value, when, then, elseExpr);
    }

    /**
     * SUBSTR/SUBSTRING 第一个参数 → CAST AS VARCHAR（MySQL → PG）。
     *
     * MySQL 允许 SUBSTR 的第一个参数为任意可隐式转换为字符串的类型（整数、数字等），
     * 而 PG 的 SUBSTR 要求第一个参数必须为 text/varchar 类型。
     * 因此对于 MySQL → PG 翻译，无条件将第一个参数包装为 CAST(expr AS VARCHAR)。
     *
     * 示例：SUBSTR(12312312313, 1, 3) → SUBSTR(CAST(12312312313 AS VARCHAR), 1, 3)
     *
     * 即使第一个参数已经是字符串类型，CAST AS VARCHAR 在 PG 中也是安全的（幂等）。
     */
    private SqlCall rewriteSubstrToVarchar(SqlCall call) {
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
                        new SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, SqlParserPos.ZERO),
                        SqlParserPos.ZERO
                )
        );

        // 构建新的操作数列表：第一个替换为 CAST 节点，其余不变
        List<SqlNode> newOperands = new ArrayList<>(operands);
        newOperands.set(0, castNode);

        return call.getOperator().createCall(
                call.getParserPosition(),
                newOperands
        );
    }

    private void addFunctionMapper(String functionName, FunctionMapper mapper) {
        rules.put(functionName.toUpperCase(), mapper);
    }

    @Override
    public SqlNode visit(SqlCall call) {
        // 先深度遍历子节点
        SqlCall visited = (SqlCall) super.visit(call);

        // 按函数名查找改写规则（不限制 operator 类型，因为 SUBSTR 等可能不是 SqlFunction 实例）
        SqlOperator operator = visited.getOperator();
        String name = operator.getName();
        FunctionMapper mapper = rules.get(name.toUpperCase());
        if (mapper != null) {
            SqlCall rewritten = mapper.apply(visited);
            if (rewritten != null) {
                return rewritten;
            }
        }
        return visited;
    }
}
