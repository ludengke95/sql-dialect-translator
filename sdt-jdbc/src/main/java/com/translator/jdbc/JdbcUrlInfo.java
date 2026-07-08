package com.translator.jdbc;

import java.util.Properties;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * JDBC URL 解析结果。
 */
public class JdbcUrlInfo {

    private final DialectType sourceDialect;
    private final DialectType targetDialect;
    private final String realUrl;
    private final Properties realProperties;
    private final TranslationConfig translationConfig;

    public JdbcUrlInfo(
            DialectType sourceDialect, DialectType targetDialect, String realUrl, Properties realProperties) {
        this(sourceDialect, targetDialect, realUrl, realProperties, null);
    }

    public JdbcUrlInfo(
            DialectType sourceDialect,
            DialectType targetDialect,
            String realUrl,
            Properties realProperties,
            TranslationConfig translationConfig) {
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
        this.realUrl = realUrl;
        this.realProperties = realProperties;
        this.translationConfig = translationConfig;
    }

    public DialectType getSourceDialect() {
        return sourceDialect;
    }

    public DialectType getTargetDialect() {
        return targetDialect;
    }

    public String getRealUrl() {
        return realUrl;
    }

    public Properties getRealProperties() {
        return realProperties;
    }

    public TranslationConfig getTranslationConfig() {
        return translationConfig;
    }
}
