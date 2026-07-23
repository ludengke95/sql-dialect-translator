package com.translator.core.operator;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.junit.Test;

/**
 * SdtOperatorTable 自定义算子表单元测试。
 */
public class SdtOperatorTableTest {

    @Test
    public void testSdtOperatorTableSpiLoading() {
        SdtOperatorTable table = SdtOperatorTable.instance();
        List<SqlOperator> operators = table.getOperatorList();
        assertNotNull(operators);
        assertTrue("Custom operators should be loaded via SPI", operators.size() > 0);
    }

    @Test
    public void testOperatorLookup() {
        SdtOperatorTable table = SdtOperatorTable.instance();
        List<SqlOperator> list = new ArrayList<>();
        table.lookupOperatorOverloads(
                new SqlIdentifier("DATE_FORMAT", org.apache.calcite.sql.parser.SqlParserPos.ZERO),
                SqlFunctionCategory.SYSTEM,
                SqlSyntax.FUNCTION,
                list,
                SqlNameMatchers.withCaseSensitive(false));

        assertTrue("DATE_FORMAT should be found in custom operator table", list.size() > 0);
    }

    @Test
    public void testChainedTable() {
        SqlOperatorTable chained = SdtOperatorTable.instance().getChainedTable();
        assertNotNull(chained);
    }
}
