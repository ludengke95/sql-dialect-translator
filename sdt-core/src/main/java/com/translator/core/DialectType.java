package com.translator.core;

/**
 * 支持的数据库方言类型枚举。
 * 对应需求文档中的 4 大方言组。
 */
public enum DialectType {
    POSTGRESQL("postgresql", "PostgreSQL方言组"),
    MYSQL("mysql", "MySQL方言组"),
    ORACLE("oracle", "Oracle方言组"),
    SQLSERVER("sqlserver", "SQL Server方言组");

    private final String identifier;
    private final String displayName;

    DialectType(String identifier, String displayName) {
        this.identifier = identifier;
        this.displayName = displayName;
    }

    /**
     * 获取方言标识符（用于 JDBC URL 和配置）。
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * 获取方言显示名称。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 根据标识符查找方言类型，不区分大小写。
     *
     * @param identifier 方言标识符
     * @return 对应的方言类型
     * @throws IllegalArgumentException 如果标识符不匹配任何方言
     */
    public static DialectType fromIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("方言标识符不能为空");
        }
        String lower = identifier.toLowerCase().trim();
        for (DialectType type : values()) {
            if (type.identifier.equals(lower)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的方言标识符: " + identifier + "，支持的标识符: postgresql, mysql, oracle, sqlserver");
    }
}
