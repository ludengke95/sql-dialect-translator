package com.translator.core;

/**
 * SQL 翻译异常。
 * 当 SQL 解析或转换失败时抛出。
 */
public class SqlTranslationException extends RuntimeException {

    public SqlTranslationException(String message) {
        super(message);
    }

    public SqlTranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
