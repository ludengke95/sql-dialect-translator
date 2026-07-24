package com.translator.core.rewrite.rule;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.util.NlsString;

import com.translator.core.DialectType;
import com.translator.core.rewrite.FunctionRewriteRule;

/**
 * MySQL -> PostgreSQL 特有函数与表达式 AST 改写规则。
 */
public class MySqlToPgFunctionRewriteRule extends FunctionRewriteRule {

    private static final SqlFunction TO_CHAR_FUNC = new SqlFunction(
            "TO_CHAR",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0,
            null,
            OperandTypes.VARIADIC,
            SqlFunctionCategory.SYSTEM);

    private static final SqlFunction SPLIT_PART_FUNC = new SqlFunction(
            "SPLIT_PART",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0,
            null,
            OperandTypes.VARIADIC,
            SqlFunctionCategory.SYSTEM);

    private static final SqlFunction STRING_AGG_FUNC = new SqlFunction(
            "STRING_AGG",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0,
            null,
            OperandTypes.VARIADIC,
            SqlFunctionCategory.SYSTEM);

    private static final SqlFunction JSON_EXTRACT_PATH_TEXT_FUNC = new SqlFunction(
            "JSON_EXTRACT_PATH_TEXT",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.ARG0,
            null,
            OperandTypes.VARIADIC,
            SqlFunctionCategory.SYSTEM);

    private static final Set<String> TARGET_FUNC_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("DATE_FORMAT", "SUBSTRING_INDEX", "GROUP_CONCAT", "JSON_EXTRACT")));

    @Override
    protected Set<String> getFunctionNames() {
        return TARGET_FUNC_NAMES;
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
        if (!(node instanceof SqlCall)) {
            return node;
        }

        SqlCall call = (SqlCall) node;
        String funcName = call.getOperator().getName().toUpperCase();

        switch (funcName) {
            case "DATE_FORMAT":
                return rewriteDateFormat(call);
            case "SUBSTRING_INDEX":
                return rewriteSubstringIndex(call);
            case "GROUP_CONCAT":
                return rewriteGroupConcat(call);
            case "JSON_EXTRACT":
                return rewriteJsonExtract(call);
            default:
                return node;
        }
    }

    private SqlNode rewriteDateFormat(SqlCall call) {
        if (call.operandCount() < 2) return call;
        SqlNode dateNode = call.operand(0);
        SqlNode formatNode = call.operand(1);

        SqlNode convertedFormatNode = formatNode;
        if (formatNode instanceof SqlLiteral) {
            SqlLiteral lit = (SqlLiteral) formatNode;
            if (lit.getValue() instanceof NlsString) {
                String mysqlFmt = ((NlsString) lit.getValue()).getValue();
                String pgFmt = convertDateFormatPattern(mysqlFmt);
                convertedFormatNode = SqlLiteral.createCharString(pgFmt, call.getParserPosition());
            }
        }

        return new SqlBasicCall(TO_CHAR_FUNC, new SqlNode[] {dateNode, convertedFormatNode}, call.getParserPosition());
    }

    private SqlNode rewriteSubstringIndex(SqlCall call) {
        if (call.operandCount() < 2) return call;
        SqlNode str = call.operand(0);
        SqlNode delim = call.operand(1);
        SqlNode count = call.operandCount() > 2
                ? call.operand(2)
                : SqlLiteral.createExactNumeric("1", call.getParserPosition());

        return new SqlBasicCall(SPLIT_PART_FUNC, new SqlNode[] {str, delim, count}, call.getParserPosition());
    }

    private SqlNode rewriteGroupConcat(SqlCall call) {
        if (call.operandCount() == 0) return call;
        SqlNode expr = call.operand(0);
        SqlNode delim =
                call.operandCount() > 1 ? call.operand(1) : SqlLiteral.createCharString(",", call.getParserPosition());

        return new SqlBasicCall(STRING_AGG_FUNC, new SqlNode[] {expr, delim}, call.getParserPosition());
    }

    private SqlNode rewriteJsonExtract(SqlCall call) {
        if (call.operandCount() < 2) return call;
        SqlNode doc = call.operand(0);
        SqlNode pathNode = call.operand(1);

        SqlNode cleanPathNode = pathNode;
        if (pathNode instanceof SqlLiteral) {
            SqlLiteral lit = (SqlLiteral) pathNode;
            if (lit.getValue() instanceof NlsString) {
                String pathStr = ((NlsString) lit.getValue()).getValue();
                if (pathStr.startsWith("$.")) {
                    pathStr = pathStr.substring(2);
                }
                cleanPathNode = SqlLiteral.createCharString(pathStr, call.getParserPosition());
            }
        }

        return new SqlBasicCall(
                JSON_EXTRACT_PATH_TEXT_FUNC, new SqlNode[] {doc, cleanPathNode}, call.getParserPosition());
    }

    private static String convertDateFormatPattern(String mysqlFmt) {
        if (mysqlFmt == null) return "";
        return mysqlFmt.replace("%Y", "YYYY")
                .replace("%y", "YY")
                .replace("%m", "MM")
                .replace("%d", "DD")
                .replace("%H", "HH24")
                .replace("%h", "HH12")
                .replace("%i", "MI")
                .replace("%s", "SS")
                .replace("%S", "SS")
                .replace("%uay", "Day");
    }
}
