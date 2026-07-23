package com.translator.core.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PARROT 测试数据集加载工具。
 *
 * <p>支持两种 JSON 格式：
 * <ol>
 *   <li><b>Legacy 格式</b>（本地内置数据集）：每条记录含 {@code sourceDialect}、{@code targetDialect}、
 *       {@code sourceSql}、{@code expectedSql} 字段，表示一对明确的方言翻译用例。</li>
 *   <li><b>Native 格式</b>（官方 PARROT 数据集）：每条记录含多个方言列（{@code mysql}、
 *       {@code postgres}、{@code oracle}、{@code tsql} 等），每行代表同一 SQL 在多种方言下的版本。
 *       加载时会自动展开为所有可翻译的方言对测试用例。</li>
 * </ol>
 *
 * <p>{@link #loadFromFile(String)} 和 {@link #loadFromResource(String)} 会自动嗅探格式并选择解析路径，
 * 调用方无需关心底层格式差异。
 */
public class ParrotDataLoader {

    /** Jackson ObjectMapper，线程安全，复用同一实例 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ParrotDataLoader() {}

    // =========================================================================
    // 公共加载入口
    // =========================================================================

    /**
     * 从类路径资源加载 JSON 测试数据集（自动嗅探格式）。
     *
     * @param resourcePath 类路径资源路径，例如 {@code "parrot/parrot_full_cases.json"}
     * @return 解析后的测试用例列表
     * @throws Exception 若资源不存在或解析失败
     */
    public static List<ParrotTestCase> loadFromResource(String resourcePath) throws Exception {
        InputStream is = ParrotDataLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + resourcePath);
        }
        String json = readToString(is);
        return parse(json);
    }

    /**
     * 从绝对文件路径加载 JSON 测试数据集（自动嗅探格式）。
     *
     * @param filePath 文件绝对路径
     * @return 解析后的测试用例列表
     * @throws Exception 若文件不存在或解析失败
     */
    public static List<ParrotTestCase> loadFromFile(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        String json = readToString(new FileInputStream(file));
        return parse(json);
    }

    // =========================================================================
    // 格式嗅探与分发
    // =========================================================================

    /**
     * 自动嗅探 JSON 格式并解析。
     *
     * <p>嗅探规则：若 JSON 内容包含 {@code "sourceDialect"} 或 {@code "sourceSql"} 字段名，
     * 则认为是 Legacy 格式；否则认为是 Native 格式（官方 PARROT 数据集）。
     *
     * @param json JSON 字符串
     * @return 解析后的测试用例列表
     * @throws Exception 解析失败时抛出
     */
    static List<ParrotTestCase> parse(String json) throws Exception {
        if (isLegacyFormat(json)) {
            return parseLegacyJson(json);
        } else {
            return parseNativeJson(json);
        }
    }

    /**
     * 判断是否为 Legacy 格式（含 sourceDialect / sourceSql 字段）。
     */
    private static boolean isLegacyFormat(String json) {
        return json.contains("\"sourceDialect\"") || json.contains("\"sourceSql\"");
    }

    // =========================================================================
    // Native 格式解析（官方 PARROT 数据集，使用 Jackson）
    // =========================================================================

    /**
     * 解析官方 PARROT Native 格式 JSON，展开为测试用例列表。
     *
     * <p>解析后对每行 {@link ParrotNativeRow} 调用 {@link ParrotDialectMapping#expandToCases(ParrotNativeRow)}，
     * 生成所有可翻译方言对的 {@link ParrotTestCase}。
     *
     * @param json JSON 字符串
     * @return 展开后的测试用例列表
     * @throws Exception Jackson 解析异常
     */
    static List<ParrotTestCase> parseNativeJson(String json) throws Exception {
        List<ParrotNativeRow> rows = OBJECT_MAPPER.readValue(
                json, new TypeReference<List<ParrotNativeRow>>() {});
        return ParrotDialectMapping.expandAllRows(rows);
    }

    // =========================================================================
    // Legacy 格式解析（本地内置数据集，正则解析）
    // =========================================================================

    /**
     * 解析 Legacy 格式 JSON（本地内置数据集）。
     *
     * <p>使用正则表达式逐块提取 JSON 对象，无需外部依赖。
     * 若 SQL 字符串中包含双引号等特殊字符，正则可能匹配失败；建议本地数据集保持简单格式。
     *
     * @param json JSON 字符串
     * @return 解析后的测试用例列表
     */
    static List<ParrotTestCase> parseLegacyJson(String json) {
        List<ParrotTestCase> testCases = new ArrayList<ParrotTestCase>();

        // 正则提取 JSON 对象块 {...}
        Pattern objectPattern = Pattern.compile(
                "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                        + "\"sourceDialect\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                        + "\"targetDialect\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                        + "\"sourceSql\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                        + "\"expectedSql\"\\s*:\\s*\"([^\"]+)\"\\s*"
                        + "(?:,\\s*\"category\"\\s*:\\s*\"([^\"]+)\")?\\s*\\}",
                Pattern.DOTALL);
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

    // =========================================================================
    // 导出
    // =========================================================================

    /**
     * 导出评测结果为 JSON 文件（PARROT 评估格式）。
     *
     * @param report    评测报告
     * @param outputPath 输出文件路径
     * @throws Exception IO 异常
     */
    public static void exportToJsonFile(ParrotBenchmarkRunner.BenchmarkReport report, String outputPath)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        List<ParrotResult> results = report.getResults();
        for (int i = 0; i < results.size(); i++) {
            ParrotResult r = results.get(i);
            ParrotTestCase tc = r.getTestCase();
            sb.append("  {\n");
            sb.append(String.format("    \"id\": \"%s\",\n", escapeJson(tc.getId())));
            sb.append(String.format("    \"sourceDialect\": \"%s\",\n", escapeJson(tc.getSourceDialect())));
            sb.append(String.format("    \"targetDialect\": \"%s\",\n", escapeJson(tc.getTargetDialect())));
            sb.append(String.format("    \"sourceSql\": \"%s\",\n", escapeJson(tc.getSourceSql())));
            sb.append(String.format(
                    "    \"translatedSql\": \"%s\",\n",
                    escapeJson(r.getTranslatedSql() != null ? r.getTranslatedSql() : "")));
            sb.append(String.format("    \"success\": %b,\n", r.isTranslationSuccess()));
            sb.append(String.format("    \"latencyUs\": %.2f\n", r.getLatencyUs()));
            sb.append("  }");
            if (i < results.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]\n");

        File outFile = new File(outputPath);
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 导出评测明细结果为 CSV 格式文件。
     * 包含字段：id, sourceDialect, targetDialect, sourceSql, translatedSql, success
     * 使用 UTF-8 BOM 编码确保电子表格软件打开时不乱码。
     *
     * @param report        评测报告
     * @param csvOutputPath CSV 输出文件路径
     * @throws Exception IO 异常
     */
    public static void exportToCsvFile(ParrotBenchmarkRunner.BenchmarkReport report, String csvOutputPath)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        // 写入 UTF-8 BOM
        sb.append('\uFEFF');
        // 表头
        sb.append("id,sourceDialect,targetDialect,sourceSql,translatedSql,success\n");

        for (ParrotResult r : report.getResults()) {
            ParrotTestCase tc = r.getTestCase();
            sb.append(escapeCsv(tc.getId())).append(",");
            sb.append(escapeCsv(tc.getSourceDialect())).append(",");
            sb.append(escapeCsv(tc.getTargetDialect())).append(",");
            sb.append(escapeCsv(tc.getSourceSql())).append(",");
            sb.append(escapeCsv(r.getTranslatedSql() != null ? r.getTranslatedSql() : "")).append(",");
            sb.append(r.isTranslationSuccess()).append("\n");
        }

        File outFile = new File(csvOutputPath);
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // =========================================================================
    // 内部工具方法
    // =========================================================================

    /**
     * 将输入流读取为 UTF-8 字符串。
     */
    private static String readToString(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串（包含所有 ASCII 控制字符 < 0x20）
     */
    private static String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static String unescapeJson(String input) {
        if (input == null) return null;
        return input.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    /**
     * CSV 字段标准双引号包裹与内部双引号转义
     */
    private static String escapeCsv(String input) {
        if (input == null) {
            return "\"\"";
        }
        return "\"" + input.replace("\"", "\"\"") + "\"";
    }
}
