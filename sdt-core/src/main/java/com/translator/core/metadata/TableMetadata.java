package com.translator.core.metadata;

import java.util.List;

/**
 * 数据库表元数据。
 */
public class TableMetadata {
    private final String tableName;
    private final List<ColumnMetadata> columns;

    public TableMetadata(String tableName, List<ColumnMetadata> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }
}
