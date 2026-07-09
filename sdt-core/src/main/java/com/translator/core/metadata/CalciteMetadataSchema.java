package com.translator.core.metadata;

import java.sql.Types;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.SchemaVersion;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * 适配 Calcite 的 Schema，从自定义的 {@link MetadataProvider} 中按需加载表元数据。
 * 直接实现 {@link Schema} 接口以完全摆脱 {@link AbstractSchema}
 * 中的 final 方法限制，保障 Lazy-loading 和大小写自适应的完美运作。
 */
public class CalciteMetadataSchema implements Schema {
    private final MetadataProvider metadataProvider;
    private final Map<String, Table> tableCache = new ConcurrentHashMap<>();

    public CalciteMetadataSchema(MetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    @Override
    public Table getTable(String name) {
        if (name == null) {
            return null;
        }
        Table cached = tableCache.get(name);
        if (cached != null) {
            return cached;
        }
        // 校验该表名是否在我们的所有表名列表中（自适应各种大小写）
        if (!getTableNames().contains(name)) {
            return null;
        }
        TableMetadata tableMeta = metadataProvider.getTable(name);
        if (tableMeta == null) {
            return null;
        }
        Table table = new CalciteTableAdapter(tableMeta);
        tableCache.put(name, table);
        return table;
    }

    @Override
    public Set<String> getTableNames() {
        return metadataProvider.getTableNames();
    }

    @Override
    public RelProtoDataType getType(String name) {
        return null;
    }

    @Override
    public Set<String> getTypeNames() {
        return Collections.emptySet();
    }

    @Override
    public Collection<Function> getFunctions(String name) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getFunctionNames() {
        return Collections.emptySet();
    }

    @Override
    public Schema getSubSchema(String name) {
        return null;
    }

    @Override
    public Set<String> getSubSchemaNames() {
        return Collections.emptySet();
    }

    @Override
    public Expression getExpression(SchemaPlus parentSchema, String name) {
        return Schemas.subSchemaExpression(parentSchema, name, getClass());
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Schema snapshot(SchemaVersion version) {
        return this;
    }
    /**
     * 适配 Calcite Table 的包装器。
     */
    private static class CalciteTableAdapter extends AbstractTable {
        private final TableMetadata metadata;

        public CalciteTableAdapter(TableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            RelDataTypeFactory.Builder builder = typeFactory.builder();
            for (ColumnMetadata col : metadata.getColumns()) {
                SqlTypeName sqlTypeName = mapJdbcTypeToCalcite(col.getDataType());
                RelDataType type;
                // 根据数据类型处理精度和标度
                if (sqlTypeName == SqlTypeName.VARCHAR || sqlTypeName == SqlTypeName.CHAR) {
                    int precision = col.getPrecision() > 0 ? col.getPrecision() : 255;
                    type = typeFactory.createSqlType(sqlTypeName, precision);
                } else if (sqlTypeName == SqlTypeName.DECIMAL) {
                    int precision = col.getPrecision() > 0 ? col.getPrecision() : 10;
                    int scale = col.getScale() >= 0 ? col.getScale() : 0;
                    type = typeFactory.createSqlType(sqlTypeName, precision, scale);
                } else {
                    type = typeFactory.createSqlType(sqlTypeName);
                }
                // 处理可空性
                if (col.isNullable()) {
                    type = typeFactory.createTypeWithNullability(type, true);
                }
                builder.add(col.getColumnName(), type);
            }
            return builder.build();
        }

        private SqlTypeName mapJdbcTypeToCalcite(int jdbcType) {
            switch (jdbcType) {
                case Types.TINYINT:
                    return SqlTypeName.TINYINT;
                case Types.SMALLINT:
                    return SqlTypeName.SMALLINT;
                case Types.INTEGER:
                    return SqlTypeName.INTEGER;
                case Types.BIGINT:
                    return SqlTypeName.BIGINT;
                case Types.FLOAT:
                case Types.REAL:
                    return SqlTypeName.FLOAT;
                case Types.DOUBLE:
                    return SqlTypeName.DOUBLE;
                case Types.NUMERIC:
                case Types.DECIMAL:
                    return SqlTypeName.DECIMAL;
                case Types.CHAR:
                    return SqlTypeName.CHAR;
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                    return SqlTypeName.VARCHAR;
                case Types.DATE:
                    return SqlTypeName.DATE;
                case Types.TIME:
                    return SqlTypeName.TIME;
                case Types.TIMESTAMP:
                    return SqlTypeName.TIMESTAMP;
                case Types.BOOLEAN:
                case Types.BIT:
                    return SqlTypeName.BOOLEAN;
                case Types.BLOB:
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                    return SqlTypeName.BINARY;
                default:
                    return SqlTypeName.ANY;
            }
        }
    }
}
