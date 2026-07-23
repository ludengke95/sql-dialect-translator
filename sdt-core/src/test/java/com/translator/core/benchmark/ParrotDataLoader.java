package com.translator.core.benchmark;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PARROT 测试数据集加载工具
 */
public class ParrotDataLoader {

    /**
     * 从类路径资源加载 JSON 测试数据集
     */
    public static List<ParrotTestCase> loadFromResource(String resourcePath) throws Exception {
        InputStream is = ParrotDataLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        return parseJsonStream(is);
    }

    /**
     * 解析包含 JSON 数组的输入流
     */
    public static List<ParrotTestCase> parseJsonStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        String json = sb.toString();
        List<ParrotTestCase> testCases = new ArrayList<>();

        // 正则提取 JSON 对象块 {...}
        Pattern objectPattern = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sourceDialect\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"targetDialect\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sourceSql\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"expectedSql\"\\s*:\\s*\"([^\"]+)\"\\s*(?:,\\s*\"category\"\\s*:\\s*\"([^\"]+)\")?\\s*\\}", Pattern.DOTALL);
        Matcher matcher = objectPattern.matcher(json);

        while (matcher.find()) {
            String id = matcher.group(1);
            String srcDialect = matcher.group(2);
            String targetDialect = matcher.group(3);
            String sourceSql = unescapeJson(matcher.group(4));
            String expectedSql = unescapeJson(matcher.group(5));
            String category = matcher.group(6) != null ? matcher.group(6) : "general";

            testCases.add(new ParrotTestCase(id, srcDialect, targetDialect, sourceSql, expectedSql, category));
        }

        return testCases;
    }

    private static String unescapeJson(String input) {
        if (input == null) return null;
        return input.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
