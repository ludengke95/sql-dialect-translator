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
 * 聚合函数算子提供者。
 */
public class AggregateFunctionOperatorProvider implements CustomOperatorProvider {

    private static final List<SqlOperator> OPERATORS = Arrays.asList(
            new SqlFunction(
                    "GROUP_CONCAT",
                    SqlKind.OTHER_FUNCTION,
                    ReturnTypes.ARG0,
                    null,
                    OperandTypes.VARIADIC,
                    SqlFunctionCategory.SYSTEM),
            new SqlFunction(
                    "STRING_AGG",
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
