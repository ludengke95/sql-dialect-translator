package com.translator.core.metadata;

/**
 * 数据库列元数据。
 */
public class ColumnMetadata {
    private final String columnName;
    private final int dataType; // java.sql.Types
    private final int precision;
    private final int scale;
    private final boolean nullable;

    public ColumnMetadata(String columnName, int dataType, int precision, int scale, boolean nullable) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.precision = precision;
        this.scale = scale;
        this.nullable = nullable;
    }

    public String getColumnName() {
        return columnName;
    }

    public int getDataType() {
        return dataType;
    }

    public int getPrecision() {
        return precision;
    }

    public int getScale() {
        return scale;
    }

    public boolean isNullable() {
        return nullable;
    }
}
