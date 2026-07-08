package com.translator.core.config;

import java.util.Locale;

/**
 * SQL 翻译配置，控制翻译后 SQL 中关键词和标识符的大小写。
 * <p>
 * 可用于适配不同目标数据库的大小写习惯：
 * <ul>
 *   <li>PostgreSQL/Highgo 推荐关键字大写、标识符小写 ({@link IdentifierCase#LOWER})</li>
 *   <li>Oracle 推荐关键字大写、标识符大写 ({@link IdentifierCase#UPPER})</li>
 *   <li>MySQL 推荐使用原始大小写 ({@link IdentifierCase#UNCHANGED})</li>
 * </ul>
 */
public class TranslationConfig {

    /** 关键词大小写策略。 */
    public enum KeywordCase {
        UPPER,
        LOWER;

        public String apply(String keyword) {
            switch (this) {
                case LOWER:
                    return keyword.toLowerCase(Locale.ROOT);
                default:
                    return keyword.toUpperCase(Locale.ROOT);
            }
        }
    }

    /** 标识符（表名、字段名）大小写策略。 */
    public enum IdentifierCase {
        UPPER,
        LOWER,
        /** 保持原始大小写不变。 */
        UNCHANGED;

        public String apply(String identifier) {
            switch (this) {
                case UPPER:
                    return identifier.toUpperCase(Locale.ROOT);
                case LOWER:
                    return identifier.toLowerCase(Locale.ROOT);
                default:
                    return identifier;
            }
        }
    }

    /** 校验模式。 */
    public enum ValidationMode {
        /** 强校验模式，校验失败抛出异常阻断运行。 */
        STRICT,
        /** 警告模式，校验失败仅记录警告日志，降级为原始不校验翻译。 */
        WARN
    }

    /** 默认配置：关键词大写、标识符小写（适配 PostgreSQL/Highgo）。 */
    public static final TranslationConfig DEFAULT = new TranslationConfig();

    private KeywordCase keywordCase = KeywordCase.UPPER;
    private IdentifierCase identifierCase = IdentifierCase.LOWER;
    private boolean enableValidation = false;
    private ValidationMode validationMode = ValidationMode.STRICT;
    private int maxTables = 100;

    public TranslationConfig() {
    }

    public TranslationConfig(KeywordCase keywordCase, IdentifierCase identifierCase) {
        this.keywordCase = keywordCase;
        this.identifierCase = identifierCase;
    }

    public TranslationConfig(KeywordCase keywordCase, IdentifierCase identifierCase, boolean enableValidation) {
        this.keywordCase = keywordCase;
        this.identifierCase = identifierCase;
        this.enableValidation = enableValidation;
    }

    public TranslationConfig(KeywordCase keywordCase, IdentifierCase identifierCase, boolean enableValidation, ValidationMode validationMode, int maxTables) {
        this.keywordCase = keywordCase;
        this.identifierCase = identifierCase;
        this.enableValidation = enableValidation;
        this.validationMode = validationMode;
        this.maxTables = maxTables;
    }

    public KeywordCase getKeywordCase() {
        return keywordCase;
    }

    public void setKeywordCase(KeywordCase keywordCase) {
        this.keywordCase = keywordCase;
    }

    public IdentifierCase getIdentifierCase() {
        return identifierCase;
    }

    public void setIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
    }

    public boolean isEnableValidation() {
        return enableValidation;
    }

    public void setEnableValidation(boolean enableValidation) {
        this.enableValidation = enableValidation;
    }

    public ValidationMode getValidationMode() {
        return validationMode;
    }

    public void setValidationMode(ValidationMode validationMode) {
        this.validationMode = validationMode;
    }

    public int getMaxTables() {
        return maxTables;
    }

    public void setMaxTables(int maxTables) {
        this.maxTables = maxTables;
    }

    public TranslationConfig withKeywordCase(KeywordCase keywordCase) {
        this.keywordCase = keywordCase;
        return this;
    }

    public TranslationConfig withIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
        return this;
    }

    public TranslationConfig withEnableValidation(boolean enableValidation) {
        this.enableValidation = enableValidation;
        return this;
    }

    public TranslationConfig withValidationMode(ValidationMode validationMode) {
        this.validationMode = validationMode;
        return this;
    }

    public TranslationConfig withMaxTables(int maxTables) {
        this.maxTables = maxTables;
        return this;
    }

    @Override
    public String toString() {
        return "TranslationConfig{" +
                "keywordCase=" + keywordCase +
                ", identifierCase=" + identifierCase +
                ", enableValidation=" + enableValidation +
                ", validationMode=" + validationMode +
                ", maxTables=" + maxTables +
                '}';
    }
}
