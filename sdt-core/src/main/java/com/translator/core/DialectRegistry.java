package com.translator.core;

import java.util.*;

/**
 * 方言注册表。
 * 维护需求文档中各组数据库产品到方言类型的映射关系。
 */
public class DialectRegistry {

    /** 数据库产品名 → 方言类型映射 */
    private static final Map<String, DialectType> PRODUCT_TO_DIALECT = new LinkedHashMap<>();

    /** 方言类型 → 支持的数据库产品列表映射 */
    private static final Map<DialectType, List<String>> DIALECT_TO_PRODUCTS = new LinkedHashMap<>();

    /** JDBC 子协议 → JDBC URL 前缀（仅需注册非标准格式的数据库） */
    private static final Map<String, String> JDBC_URL_PREFIX = new HashMap<>();

    static {
        // PostgreSQL 方言组（10 个库）
        register(
                DialectType.POSTGRESQL,
                "PostgreSQL",
                "HighGo",
                "瀚高",
                "KingbaseES",
                "PolarDB PG",
                "Greenplum",
                "TimescaleDB",
                "PostGIS",
                "IvorySQL",
                "QuestDB",
                "KaiwuDB");

        // MySQL 方言组（3 个库）
        register(DialectType.MYSQL, "MySQL", "Doris", "PolarDB MySQL");

        // Oracle 方言组（4 个库）
        register(DialectType.ORACLE, "Oracle", "Oracle RAC", "Oracle Spatial", "DM", "达梦");

        // SQL Server 方言组（1 个库）
        register(DialectType.SQLSERVER, "SQL Server", "Microsoft SQL Server");

        // JDBC URL 前缀覆盖（仅非标准格式需注册）
        // 默认前缀：jdbc:<子协议>://
        // Oracle Thin 驱动统一使用 jdbc:oracle:thin:@ 前缀，
        // 用户根据实际格式在 translator URL 的 :// 后填入对应内容：
        //   Service Name:  jdbc:translator:mysql:oracle:////host:1521/service
        //   SID:           jdbc:translator:mysql:oracle://host:1521:orcl
        //   TNS Alias:     jdbc:translator:mysql:oracle://TNSName
        //   TNS Descriptor: jdbc:translator:mysql:oracle://(DESCRIPTION=...)
        JDBC_URL_PREFIX.put("oracle", "jdbc:oracle:thin:@");
    }

    private static void register(DialectType dialect, String... products) {
        List<String> list = Arrays.asList(products);
        DIALECT_TO_PRODUCTS.put(dialect, list);
        for (String product : products) {
            PRODUCT_TO_DIALECT.put(product.toLowerCase(), dialect);
            // 也注册不带空格的 key
            PRODUCT_TO_DIALECT.put(product.toLowerCase().replace(" ", ""), dialect);
        }
    }

    /**
     * 根据 JDBC 子协议获取真实的 JDBC URL 前缀。
     * 默认返回 jdbc:<子协议>:（用户地址部分携带 :// 前缀），
     * Oracle 等特殊驱动返回定制前缀。
     *
     * @param subProtocol JDBC 子协议名（从 URL 解析出的原始值）
     * @return JDBC URL 前缀
     */
    public static String getJdbcUrlPrefix(String subProtocol) {
        if (subProtocol == null) {
            return "jdbc:";
        }
        String key = subProtocol.toLowerCase().trim();
        return JDBC_URL_PREFIX.getOrDefault(key, "jdbc:" + key + ":");
    }

    /**
     * 根据数据库产品名称获取对应的方言类型。
     *
     * @param productName 数据库产品名称，不区分大小写
     * @return 对应的方言类型，如果未找到返回 null
     */
    public static DialectType getDialectForProduct(String productName) {
        if (productName == null) {
            return null;
        }
        return PRODUCT_TO_DIALECT.get(productName.toLowerCase().trim());
    }

    /**
     * 获取指定方言组支持的所有数据库产品名称。
     *
     * @param dialect 方言类型
     * @return 数据库产品名称列表
     */
    public static List<String> getProductsForDialect(DialectType dialect) {
        return DIALECT_TO_PRODUCTS.getOrDefault(dialect, Collections.emptyList());
    }

    /**
     * 获取所有注册的方言类型。
     */
    public static Set<DialectType> getAllDialects() {
        return DIALECT_TO_PRODUCTS.keySet();
    }
}
