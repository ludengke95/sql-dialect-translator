package com.translator.core.rewrite.rule;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;

/**
 * MySQL DATE_ADD/ADDDATE/DATE_SUB/SUBDATE 改写为 PostgreSQL TIMESTAMP + INTERVAL 形式。
 * <p>
 * 示例：DATE_ADD('1998-12-01', INTERVAL -90 DAY) → CAST('1998-12-01' AS TIMESTAMP) + '-90 DAYS'
 * </p>
 */
public class DateAddRewriteRule extends FunctionRewriteRule {

    private static final Set<String> FUNC_NAMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("DATE_ADD", "ADDDATE", "DATE_SUB", "SUBDATE")));

    private static final Pattern INTERVAL_PATTERN =
            Pattern.compile("INTERVAL?\\s+(-?\\s*\\d+)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    @Override
    protected Set<String> getFunctionNames() {
        return FUNC_NAMES;
    }

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.POSTGRESQL);
    }

    @Override
    public SqlNode apply(SqlNode node) {
        SqlCall call = (SqlCall) node;
        List<SqlNode> operands = call.getOperandList();
        if (operands.size() < 2) {
            return call;
        }

        SqlNode firstOperand = operands.get(0);
        SqlNode secondOperand = operands.get(1);

        // 1. 将第一个参数包装为 CAST(firstOperand AS TIMESTAMP)
        SqlNode castNode = SqlStdOperatorTable.CAST.createCall(
                SqlParserPos.ZERO,
                firstOperand,
                new SqlDataTypeSpec(
                        new SqlBasicTypeNameSpec(SqlTypeName.TIMESTAMP, SqlParserPos.ZERO), SqlParserPos.ZERO));

        // 2. 将第二个参数转换为 PostgreSQL 对应的字面量字符串
        SqlNode rightNode = null;
        boolean isSub = call.getOperator().getName().toUpperCase().contains("SUB");

        // 无论 secondOperand 内部是何种 AST 结构（SqlIntervalLiteral 或是带一元负号的 SqlCall），
        // 都可以直接转为 SQL 字符串进行正则模式清洗提取，极具强壮性。
        try {
            String sql = secondOperand
                    .toSqlString(c -> c.withDialect(AnsiSqlDialect.DEFAULT))
                    .getSql();
            sql = sql.replaceAll("['\"]", "").replaceAll("\\s+", " ").trim();

            Matcher matcher = INTERVAL_PATTERN.matcher(sql);
            if (matcher.find()) {
                String valueStr = matcher.group(1).replaceAll("\\s+", ""); // 消除如 "- 90" 里的空格
                String unit = matcher.group(2).toUpperCase();

                // 处理 SUB DATE 取反符号
                if (isSub) {
                    if (valueStr.startsWith("-")) {
                        valueStr = valueStr.substring(1);
                    } else {
                        valueStr = "-" + valueStr;
                    }
                }

                if (!unit.endsWith("S")) {
                    unit = unit + "S";
                }

                String combined = valueStr + " " + unit;
                rightNode = SqlLiteral.createCharString(combined, SqlParserPos.ZERO);
            }
        } catch (Exception e) {
            // 忽略异常，降级使用原始节点
        }

        if (rightNode == null) {
            rightNode = secondOperand;
        }

        // 3. 构建二元 PLUS 节点
        return SqlStdOperatorTable.PLUS.createCall(call.getParserPosition(), castNode, rightNode);
    }
}
