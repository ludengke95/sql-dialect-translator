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

    /** 默认配置：关键词大写、标识符小写（适配 PostgreSQL/Highgo）。 */
    public static final TranslationConfig DEFAULT = new TranslationConfig();

    private KeywordCase keywordCase = KeywordCase.UPPER;
    private IdentifierCase identifierCase = IdentifierCase.LOWER;

    public TranslationConfig() {
    }

    public TranslationConfig(KeywordCase keywordCase, IdentifierCase identifierCase) {
        this.keywordCase = keywordCase;
        this.identifierCase = identifierCase;
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

    public TranslationConfig withKeywordCase(KeywordCase keywordCase) {
        this.keywordCase = keywordCase;
        return this;
    }

    public TranslationConfig withIdentifierCase(IdentifierCase identifierCase) {
        this.identifierCase = identifierCase;
        return this;
    }

    @Override
    public String toString() {
        return "TranslationConfig{" +
                "keywordCase=" + keywordCase +
                ", identifierCase=" + identifierCase +
                '}';
    }
}
