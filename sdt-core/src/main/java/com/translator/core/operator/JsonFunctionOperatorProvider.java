package com.translator.core.operator;

import java.util.Arrays;
import java.util.List;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

/**
 * JSON 函数算子提供者。
 */
public class JsonFunctionOperatorProvider implements CustomOperatorProvider {

    private static final List<SqlOperator> OPERATORS = Arrays.asList(
            new SqlFunction(
                    "JSON_EXTRACT",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "JSON_UNQUOTE",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "JSON_OBJECTAGG",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "JSON_AGG",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "JSON_BUILD_OBJECT",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM)
    );

    @Override
    public List<SqlOperator> getOperators() {
        return OPERATORS;
    }
}
