package com.translator.jdbc;

import com.translator.core.DialectRegistry;
import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC URL 解析器。
 * 解析格式：jdbc:translator:<源方言>:<目标数据库子协议>:<真实地址>
 * 示例：jdbc:translator:mysql:highgo://10.110.60.187:5866/highgo
 * 示例：jdbc:translator:mysql:oracle://localhost:1521/XE
 * 示例：jdbc:translator:oracle:mysql://localhost:3306/mydb
 *
 * 目标数据库子协议同时用于：
 * 1. 构造真实 JDBC URL（通过 DialectRegistry.getJdbcUrlPrefix 获取前缀，
 *    默认 jdbc:<子协议>:，用户地址部分自带 ://）
 * 2. 通过 DialectRegistry 查找对应的方言组
 */
public class JdbcUrlParser {

    private static final Logger log = LoggerFactory.getLogger(JdbcUrlParser.class);

    /**
     * URL 格式：jdbc:translator:<source_dialect>:<target_subprotocol>:<real_address>
     * group(1) = 源方言标识符
     * group(2) = 目标数据库子协议（同时也是 DialectRegistry 查询 key）
     * group(3) = 真实连接地址（如 //host:port/db?params、TNSName 等，原样透传）
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^jdbc:translator:([a-zA-Z0-9_]+):([a-zA-Z0-9_]+):(.+)$"
    );

    /**
     * 解析 JDBC URL。
     *
     * @param url        JDBC URL
     * @param properties 连接属性
     * @return 解析结果
     * @throws IllegalArgumentException 如果 URL 格式不正确或目标数据库产品未知
     */
    public static JdbcUrlInfo parse(String url, Properties properties) {
        if (url == null || !url.startsWith("jdbc:translator:")) {
            throw new IllegalArgumentException("URL 必须以 jdbc:translator: 开头: " + url);
        }

        Matcher matcher = URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("URL 格式不正确，期望: " +
                    "jdbc:translator:<源方言>:<目标数据库子协议>:<真实地址>, 实际: " + url);
        }

        // 提取源方言标识符
        String sourceId = matcher.group(1);
        DialectType sourceDialect = DialectType.fromIdentifier(sourceId);

        // 提取目标数据库子协议，构造真实 JDBC URL
        String targetProduct = matcher.group(2);
        // 通过注册表获取 JDBC URL 前缀（默认 jdbc:<子协议>://，Oracle 为 jdbc:oracle:thin:@）
        String jdbcPrefix = DialectRegistry.getJdbcUrlPrefix(targetProduct);
        // group(3) = 真实地址，如 //host:port/db 或 @host:1521:orcl 等，直接拼接
        String realUrl = jdbcPrefix + matcher.group(3);

        // 通过注册表查找目标数据库对应的方言组
        DialectType targetDialect = DialectRegistry.getDialectForProduct(targetProduct);
        if (targetDialect == null) {
            throw new IllegalArgumentException("未知的目标数据库子协议: " + targetProduct
                    + "，支持的数据库: PostgreSQL/HighGo/瀚高, MySQL/Doris, Oracle/DM/达梦, SQL Server");
        }

        log.debug("解析 JDBC URL: source={}, targetProduct={}, targetDialect={}, realUrl={}",
                sourceDialect, targetProduct, targetDialect, realUrl);

        // 从 URL 查询参数中提取翻译配置
        TranslationConfig translationConfig = parseTranslationConfig(
                matcher.group(3), properties);

        return new JdbcUrlInfo(sourceDialect, targetDialect, realUrl,
                properties, translationConfig);
    }

    /**
     * 从 JDBC URL 的查询参数或 Properties 中提取 {@link TranslationConfig}。
     * <p>
     * URL 示例: {@code jdbc:translator:mysql:postgresql://host:5432/db?keywordCase=LOWER&identifierCase=UPPER}
     * <p>
     * 参数优先级：URL 查询参数 > Properties
     *
     * @param address    URL 中协议后的地址部分（含查询参数）
     * @param properties 连接属性
     * @return 翻译配置，未设置任何参数时返回 null
     */
    private static TranslationConfig parseTranslationConfig(String address, Properties properties) {
        // 从 URL 查询字符串中提取参数
        Properties params = new Properties();
        if (address != null) {
            int queryIdx = address.indexOf('?');
            if (queryIdx >= 0) {
                String query = address.substring(queryIdx + 1);
                String[] pairs = query.split("&");
                for (String pair : pairs) {
                    int eqIdx = pair.indexOf('=');
                    if (eqIdx > 0) {
                        params.setProperty(
                                pair.substring(0, eqIdx),
                                pair.substring(eqIdx + 1));
                    }
                }
            }
        }

        String keywordCaseStr = params.getProperty("keywordCase");
        if (keywordCaseStr == null && properties != null) {
            keywordCaseStr = properties.getProperty("keywordCase");
        }
        String identifierCaseStr = params.getProperty("identifierCase");
        if (identifierCaseStr == null && properties != null) {
            identifierCaseStr = properties.getProperty("identifierCase");
        }

        if (keywordCaseStr == null && identifierCaseStr == null) {
            return null; // 使用默认配置
        }

        TranslationConfig config = new TranslationConfig();
        if (keywordCaseStr != null) {
            config.setKeywordCase(TranslationConfig.KeywordCase.valueOf(
                    keywordCaseStr.toUpperCase(java.util.Locale.ROOT)));
        }
        if (identifierCaseStr != null) {
            config.setIdentifierCase(TranslationConfig.IdentifierCase.valueOf(
                    identifierCaseStr.toUpperCase(java.util.Locale.ROOT)));
        }
        log.debug("翻译配置: {}", config);
        return config;
    }

    /**
     * 检查 URL 是否由本驱动处理。
     */
    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith("jdbc:translator:");
    }
}
