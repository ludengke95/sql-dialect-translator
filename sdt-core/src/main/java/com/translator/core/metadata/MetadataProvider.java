package com.translator.core.metadata;

import java.util.Set;

/**
 * 数据库元数据提供者接口。
 * 用于在 SQL 校验阶段按需提供表和列的结构信息。
 */
public interface MetadataProvider {
    /**
     * 根据表名获取表的元数据信息。
     *
     * @param tableName 表名
     * @return 表的元数据，如果表不存在则返回 null
     */
    TableMetadata getTable(String tableName);

    /**
     * 获取当前数据库下所有的表名列表（支持大小写自适应）。
     *
     * @return 表名集合
     */
    Set<String> getTableNames();
}
