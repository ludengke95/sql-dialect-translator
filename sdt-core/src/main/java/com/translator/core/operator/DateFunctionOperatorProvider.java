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
 * 日期函数算子提供者。
 */
public class DateFunctionOperatorProvider implements CustomOperatorProvider {

    private static final List<SqlOperator> OPERATORS = Arrays.asList(
            new SqlFunction(
                    "DATE_ADD",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "ADDDATE",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "DATE_SUB",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "SUBDATE",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "DATE_FORMAT",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "STR_TO_DATE",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "TIMESTAMPDIFF",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.INTEGER,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM)
    );

    @Override
    public List<SqlOperator> getOperators() {
        return OPERATORS;
    }
}
