package com.translator.core.operator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDT 统一算子表管理工厂与聚合器。
 * 通过 Java SPI (ServiceLoader) 自动装载发现所有 {@link CustomOperatorProvider}，
 * 并与 Calcite 标准算子表及 SqlLibrary 算子表链接。
 */
public class SdtOperatorTable implements SqlOperatorTable {

    private static final Logger log = LoggerFactory.getLogger(SdtOperatorTable.class);
    private static final SdtOperatorTable INSTANCE = new SdtOperatorTable();

    private final List<SqlOperator> customOperators = new ArrayList<>();
    private final SqlOperatorTable chainedTable;

    private SdtOperatorTable() {
        loadSpiOperators();
        SqlOperatorTable stdOpTable = SqlStdOperatorTable.instance();
        SqlOperatorTable libraryOpTable = SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(
                SqlLibrary.MYSQL, SqlLibrary.ORACLE, SqlLibrary.POSTGRESQL);
        this.chainedTable = SqlOperatorTables.chain(stdOpTable, libraryOpTable, this);
    }

    public static SdtOperatorTable instance() {
        return INSTANCE;
    }

    /**
     * 获取全量链接算子表（Standard + Library + Custom）。
     *
     * @return 链接算子表
     */
    public SqlOperatorTable getChainedTable() {
        return chainedTable;
    }

    private void loadSpiOperators() {
        ServiceLoader<CustomOperatorProvider> loader = ServiceLoader.load(CustomOperatorProvider.class);
        int count = 0;
        for (CustomOperatorProvider provider : loader) {
            List<SqlOperator> ops = provider.getOperators();
            if (ops != null && !ops.isEmpty()) {
                customOperators.addAll(ops);
                count += ops.size();
            }
        }
        log.info("Registered {} custom operators via CustomOperatorProvider SPI", count);
    }

    @Override
    public void lookupOperatorOverloads(
            SqlIdentifier opName,
            SqlFunctionCategory category,
            SqlSyntax syntax,
            List<SqlOperator> operatorList,
            SqlNameMatcher nameMatcher) {
        for (SqlOperator op : customOperators) {
            if (nameMatcher.matches(op.getName(), opName.getSimple())) {
                operatorList.add(op);
            }
        }
    }

    @Override
    public List<SqlOperator> getOperatorList() {
        return Collections.unmodifiableList(customOperators);
    }
}
