package com.translator.core.preprocessor;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：剥离 SQL 中的 `--` 行注释（跳过单引号字符串内部）。
 */
public class LineCommentPreProcessor implements SourceDialectPreProcessor {

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                sb.append(c);
                i++;
                while (i < sql.length()) {
                    char sc = sql.charAt(i);
                    sb.append(sc);
                    if (sc == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            sb.append(sql.charAt(i + 1));
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.setLength(sb.length() - 1);
                }
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                if (i < sql.length()) {
                    sb.append(sql.charAt(i));
                    i++;
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
