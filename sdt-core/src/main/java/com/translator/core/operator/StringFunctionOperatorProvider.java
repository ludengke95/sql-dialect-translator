package com.translator.core.operator;

import java.util.Arrays;
import java.util.List;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;

/**
 * 字符串与通用条件处理函数算子提供者。
 */
public class StringFunctionOperatorProvider implements CustomOperatorProvider {

    private static final List<SqlOperator> OPERATORS = Arrays.asList(
            new SqlFunction(
                    "IFNULL",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0_NULLABLE,
                    null,
                    OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "SUBSTRING_INDEX",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "FIELD",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.INTEGER,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "INSTR",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.INTEGER,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM));

    @Override
    public List<SqlOperator> getOperators() {
        return OPERATORS;
    }
}
