package com.translator.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.tools.Frameworks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.config.SqlDialectFactory;
import com.translator.core.config.TranslationConfig;
import com.translator.core.metadata.CalciteMetadataSchema;
import com.translator.core.metadata.MetadataProvider;
import com.translator.core.postprocessor.PostProcessorRegistry;
import com.translator.core.rewrite.SqlRewriteEngine;

/**
 * SQL 方言翻译器。
 * 核心入口：解析源 SQL → AST 改写 → 生成目标方言 SQL。
 */
public class SqlTranslator {
    private static final Logger log = LoggerFactory.getLogger(SqlTranslator.class);
    private final DialectType sourceDialect;
    private final DialectType targetDialect;
    private final SqlDialect targetSqlDialect;
    private final SqlRewriteEngine rewriteEngine;
    private final TranslationConfig config;
    private MetadataProvider metadataProvider;
    /** 默认构造器，使用 {@link TranslationConfig#DEFAULT}。 */
    public SqlTranslator(DialectType sourceDialect, DialectType targetDialect) {
        this(sourceDialect, targetDialect, TranslationConfig.DEFAULT);
    }
    /**
     * 创建 SQL 翻译器。
     *
     * @param sourceDialect 源方言类型
     * @param targetDialect 目标方言类型
     * @param config        翻译配置（大小写策略），null 时使用默认配置
     */
    public SqlTranslator(DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        this(sourceDialect, targetDialect, config, null);
    }
    /**
     * 创建 SQL 翻译器，并指定元数据提供者。
     *
     * @param sourceDialect    源方言类型
     * @param targetDialect    目标方言类型
     * @param config           翻译配置，null 时使用默认配置
     * @param metadataProvider 元数据提供者，用于校验
     */
    public SqlTranslator(
            DialectType sourceDialect,
            DialectType targetDialect,
            TranslationConfig config,
            MetadataProvider metadataProvider) {
        if (sourceDialect == null || targetDialect == null) {
            throw new IllegalArgumentException("源方言和目标方言不能为 null");
        }
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
        this.targetSqlDialect = SqlDialectFactory.getDialect(targetDialect);
        this.rewriteEngine = new SqlRewriteEngine(sourceDialect, targetDialect);
        this.config = config != null ? config : TranslationConfig.DEFAULT;
        this.metadataProvider = metadataProvider;
    }

    public MetadataProvider getMetadataProvider() {
        return metadataProvider;
    }

    public void setMetadataProvider(MetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }
    /**
     * 翻译 SQL 语句。
     *
     * @param sql 源方言的 SQL 语句
     * @return 目标方言的 SQL 语句
     * @throws SqlTranslationException 如果 SQL 解析或转换失败
     */
    public String translate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }
        if (SqlDialectFactory.isSameDialect(sourceDialect, targetDialect)) {
            log.debug("源方言和目标方言相同，无需转换: {}", sql);
            return sql;
        }
        // 去除末尾空白与尾随分号 ';'（Calcite AST parseStmt 不接受尾随分号）
        String cleanSql = sql.trim();
        while (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
        }
        if (cleanSql.isEmpty()) {
            return sql;
        }
        return translateSingle(cleanSql);
    }

    /**
     * 翻译单条 SQL 语句（不含多语句拆分）。解析失败时抛出 {@link SqlTranslationException}。
     */
    private String translateSingle(String sql) {
        long start = System.nanoTime();
        try {
            // 1. 解析 SQL → AST (SqlNode)
            SqlNode parsed = parseSql(sql);
            // 如果开启了校验并且提供了元数据提供者，对 SqlNode 进行校验和重写
            SqlNode validated = parsed;
            if (config.isEnableValidation() && metadataProvider != null) {
                try {
                    validated = validateSql(parsed, metadataProvider);
                } catch (Exception e) {
                    if (config.getValidationMode() == TranslationConfig.ValidationMode.STRICT) {
                        throw e; // STRICT 模式直接阻断
                    } else {
                        log.warn("SQL 校验失败，已自动降级为非校验 AST 进行翻译。校验异常: {}", e.getMessage());
                        validated = parsed; // WARN 模式仅记录日志并降级
                    }
                }
            }
            // 2. 改写 AST
            SqlNode rewritten = rewriteEngine.rewrite(validated);
            // 3. 使用目标方言生成 SQL（按配置控制关键词大小写）
            String result;
            if (config.getKeywordCase() == TranslationConfig.KeywordCase.LOWER) {
                result = rewritten
                        .toSqlString(c -> c.withDialect(targetSqlDialect)
                                .withKeywordsLowerCase(true)
                                .withQuoteAllIdentifiers(true))
                        .getSql();
            } else {
                result = rewritten
                        .toSqlString(c -> c.withDialect(targetSqlDialect).withQuoteAllIdentifiers(false))
                        .getSql();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.debug("SQL 翻译完成 ({}ms): [{}] → [{}]", elapsed, sql, result);
            // 目标方言专属 SQL 后处理 (后置语法擦除与归一化)
            result = PostProcessorRegistry.process(result, sourceDialect, targetDialect);
            return result;
        } catch (SqlTranslationException e) {
            throw e; // 已知翻译或校验异常，不重复包裹
        } catch (SqlParseException e) {
            throw new SqlTranslationException("SQL 解析失败: " + sql, e);
        } catch (Exception e) {
            throw new SqlTranslationException("SQL 翻译失败: [" + sql + "] " + sourceDialect + " → " + targetDialect, e);
        }
    }
    /**
     * 使用 Calcite SqlParser 解析 SQL。
     * 根据源方言做预处理（标识符引用转换），然后解析。
     */
    private SqlNode parseSql(String sql) throws SqlParseException {
        // 预处理：将源方言的特殊标识符引用转换为标准双引号引用
        String normalizedSql = normalizeIdentifierQuoting(sql, sourceDialect, config.getIdentifierCase());
        SqlParser.Config config =
                SqlParser.config().withCaseSensitive(true).withConformance(getConformanceForSource(sourceDialect));
        SqlParser parser = SqlParser.create(normalizedSql, config);
        return parser.parseStmt();
    }
    /**
     * 根据源方言类型选择合适的 Calcite SQL conformance 级别。
     * 不同方言使用不同的非标准语法，需要对应的 conformance 才能正确解析。
     */
    private static SqlConformanceEnum getConformanceForSource(DialectType sourceDialect) {
        switch (sourceDialect) {
            case MYSQL:
                return SqlConformanceEnum.MYSQL_5;
            case ORACLE:
                return SqlConformanceEnum.ORACLE_12;
            case POSTGRESQL:
                return SqlConformanceEnum.DEFAULT;
            case SQLSERVER:
                return SqlConformanceEnum.SQL_SERVER_2008;
            default:
                return SqlConformanceEnum.DEFAULT;
        }
    }
    /**
     * 预处理 SQL 中的标识符引用和方言特殊语法。
     * - MySQL 反引号 → 双引号
     * - SQL Server 方括号 → 双引号
     * - SQL Server TOP n → LIMIT n
     */
    private static String normalizeIdentifierQuoting(
            String sql, DialectType dialect, TranslationConfig.IdentifierCase identifierCase) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 预处理 PostgreSQL 等方言中 INTERVAL 'N UNIT' 为 Calcite 兼容的 INTERVAL 'N' UNIT 格式
        sql = sql.replaceAll("(?i)\\bINTERVAL\\s+'?(-?\\d+)'?\\s+(DAY|MONTH|YEAR|HOUR|MINUTE|SECOND)S?", "INTERVAL '$1' $2");

        // 去掉 -- 行注释，防止注释内容被 normalizeUnquotedIdentifiers 错误加引号
        String result = stripLineComments(sql);
        switch (dialect) {
            case MYSQL:
                // 将 `identifier` 转换为 "identifier"
                result = result.replace('`', '"');
                result = normalizeMySqlSyntax(result);
                break;
            case SQLSERVER:
                // 将 [identifier] 转换为 "identifier"
                result = result.replace('[', '"').replace(']', '"');
                // 处理 SELECT TOP n → LIMIT n
                result = normalizeSqlServerTop(result);
                break;
            default:
                break;
        }
        // 将未引用的 alias.column、alias.* 以及独立标识符补上双引号，
        // 并按配置转换大小写，防止 Calcite 在 unparse 时自动改变大小写。
        result = normalizeUnquotedIdentifiers(result, identifierCase);
        return result;
    }
    /**
     * 将 SQL Server 的 SELECT TOP n 转换为标准 SQL LIMIT n。
     * LIMIT 必须放在语句末尾（ORDER BY 之后）。
     */
    private static String normalizeSqlServerTop(String sql) {
        Pattern pattern = Pattern.compile("(?i)\\bSELECT\\s+(TOP\\s+(\\d+)\\s+)");
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        String topNum = matcher.group(2); // e.g. "10"
        // 去掉 "TOP n " 部分
        sql = sql.substring(0, matcher.start(1)) + sql.substring(matcher.end(1));
        // LIMIT n 放在末尾（Calcite parser 会正确处理 ORDER BY + LIMIT 的顺序）
        sql = sql.trim() + " LIMIT " + topNum;
        return sql;
    }
    /**
     * 规整 MySQL 特有语法表达。
     * 1. GROUP_CONCAT(expr SEPARATOR ',') -> GROUP_CONCAT(expr, ',')
     * 2. LIMIT offset, count -> LIMIT count OFFSET offset
     * 3. UPDATE t1 INNER JOIN t2 ON cond SET ... -> UPDATE t1 SET ... FROM t2 WHERE cond
     * 4. UPDATE t1, t2 SET ... -> UPDATE t1 SET ... FROM t2
     */
    private static String normalizeMySqlSyntax(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String result = sql.replaceAll("(?i)\\bGROUP_CONCAT\\s*\\(\\s*(.+?)\\s+SEPARATOR\\s+([^)]+)\\)", "GROUP_CONCAT($1, $2)");
        result = result.replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)", "LIMIT $2 OFFSET $1");

        // 处理 UPDATE t1 JOIN t2 ON cond SET assignments -> UPDATE t1 SET assignments FROM t2 WHERE cond
        result = result.replaceAll(
                "(?i)\\bUPDATE\\s+([a-zA-Z0-9_\"`]+)\\s+(?:AS\\s+([a-zA-Z0-9_\"`]+)\\s+)?(?:INNER|LEFT|RIGHT|CROSS)?\\s*JOIN\\s+([a-zA-Z0-9_\"`]+)\\s+(?:AS\\s+([a-zA-Z0-9_\"`]+)\\s+)?ON\\s+(.+?)\\s+SET\\s+(.+)",
                "UPDATE $1 SET $6 FROM $3 WHERE $5");

        // 处理 UPDATE t1, t2 SET assignments -> UPDATE t1 SET assignments FROM t2
        result = result.replaceAll(
                "(?i)\\bUPDATE\\s+([a-zA-Z0-9_\"`]+)\\s*,\\s*([a-zA-Z0-9_\"`]+)\\s+SET\\s+(.+)",
                "UPDATE $1 SET $3 FROM $2");

        return result;
    }
    /**
     * 去掉 SQL 中的 -- 行注释。
     * 防止注释内容被后续 normalizeUnquotedIdentifiers 过程错误加引号。
     */
    private static String stripLineComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            // 跳过单引号字符串（注释符号 -- 可能出现在字符串内）
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
            // 遇到 -- 行注释，跳过直到行尾
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // 去掉注释前的尾部空格
                while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                    sb.setLength(sb.length() - 1);
                }
                // 跳过 -- 直到行尾
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                // 保留换行符，维持行号一致性
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
    /** SQL 保留关键字集合，用于预处理时跳过不引用。 */
    private static final Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT",
            "FROM",
            "WHERE",
            "AND",
            "OR",
            "NOT",
            "IN",
            "AS",
            "ON",
            "JOIN",
            "LEFT",
            "RIGHT",
            "INNER",
            "OUTER",
            "CROSS",
            "FULL",
            "NATURAL",
            "ORDER",
            "BY",
            "GROUP",
            "HAVING",
            "DISTINCT",
            "UNION",
            "ALL",
            "ANY",
            "EXISTS",
            "BETWEEN",
            "LIKE",
            "IS",
            "NULL",
            "TRUE",
            "FALSE",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "ASC",
            "DESC",
            "LIMIT",
            "OFFSET",
            "SET",
            "INTO",
            "VALUES",
            "INSERT",
            "UPDATE",
            "DELETE",
            "CREATE",
            "TABLE",
            "DROP",
            "ALTER",
            "IF",
            "FOR",
            "SOME",
            "WITH",
            "USING",
            "PRIMARY",
            "KEY",
            "FOREIGN",
            "INDEX",
            "UNIQUE",
            "CHECK",
            "DEFAULT",
            "CONSTRAINT",
            "ADD",
            "COLUMN",
            "REFERENCES",
            "FETCH",
            "NEXT",
            "ROWS",
            "ONLY",
            "EXCEPT",
            "INTERSECT",
            "MINUS",
            // 聚合函数
            "COUNT",
            "SUM",
            "AVG",
            "MIN",
            "MAX",
            // 常用函数
            "COALESCE",
            "CAST",
            "CONVERT",
            "NULLIF",
            "IFNULL",
            "NVL",
            "ISNULL",
            "DECODE",
            "CONCAT",
            "SUBSTRING",
            "TRIM",
            "UPPER",
            "LOWER",
            "LENGTH",
            "REPLACE",
            "SUBSTR",
            "INSTR",
            "TO_CHAR",
            "TO_DATE",
            "TO_NUMBER",
            "TO_TIMESTAMP",
            "NOW",
            "SYSDATE",
            "GETDATE",
            "CURDATE",
            "CURTIME",
            "DATE_FORMAT",
            "DATE_ADD",
            "DATE_SUB",
            "DATEDIFF",
            "EXTRACT",
            "INTERVAL",
            "YEAR",
            "MONTH",
            "DAY",
            "HOUR",
            "MINUTE",
            "SECOND",
            // 其他
            "CURRENT_TIMESTAMP",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "CURRENT",
            "TIMESTAMP",
            "DATE",
            "TIME",
            "TOP",
            "BOTTOM",
            "BEGIN",
            "COMMIT",
            "ROLLBACK",
            "FUNCTION",
            "PROCEDURE",
            "LANGUAGE",
            "RETURNS",
            "CALL",
            "RETURNING",
            "DO",
            "WINDOW",
            "OVER",
            "PARTITION",
            "UNBOUNDED",
            "PRECEDING",
            "FOLLOWING",
            "TIES",
            "ROW",
            "ROWS",
            "GROUPS",
            "OTHERS"));
    /**
     * 将未引用或已引用的标识符按配置处理：补引号 + 大小写转换。
     * <p>
     * 处理三种模式：
     * <ol>
     *   <li>{@code alias.column} → {@code "ALIAS"."COLUMN"}（按 identifierCase 转大小写）</li>
     *   <li>{@code alias.*} → {@code "ALIAS".*}</li>
     *   <li>独立标识符（非 SQL 关键字） → {@code "IDENTIFIER"}</li>
     * </ol>
     * <p>
     * 已加双引号的标识符不受影响（负向后瞻跳过）。
     */
    private static String normalizeUnquotedIdentifiers(String sql, TranslationConfig.IdentifierCase identifierCase) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 1. 提取所有单引号字符串字面量，用占位符替换，防止正则匹配到字符串内部
        //    例如 'SAUDI ARABIA'、'F'、'don''t' 等
        List<String> stringLiterals = new ArrayList<>();
        String protectedSql = protectStringLiterals(sql, stringLiterals);
        // 2. 匹配 identifier.identifier、identifier.* 或 standalone identifier
        //    负向后瞻排除已引用或数字开头的；负向前瞻排除函数调用（后跟括号）和已引用标识符
        Pattern pattern = Pattern.compile(
                "(?<![\\w\"`])([a-zA-Z_][a-zA-Z0-9_]*)" + "(?:\\.(\\*|[a-zA-Z_][a-zA-Z0-9_]*))?" + "(?![\\w\"`(])");
        Matcher matcher = pattern.matcher(protectedSql);
        StringBuilder sb = new StringBuilder(protectedSql.length() + 64);
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(protectedSql, lastEnd, matcher.start());
            String part1 = matcher.group(1);
            String part2 = matcher.group(2);
            if (part2 != null) {
                // alias.column 或 alias.*
                sb.append('"').append(identifierCase.apply(part1)).append('"');
                if ("*".equals(part2)) {
                    sb.append(".*");
                } else {
                    sb.append('.')
                            .append('"')
                            .append(identifierCase.apply(part2))
                            .append('"');
                }
            } else {
                // 独立标识符 — 跳过 SQL 关键字
                if (SQL_KEYWORDS.contains(part1.toUpperCase(Locale.ROOT))) {
                    sb.append(part1);
                } else {
                    sb.append('"').append(identifierCase.apply(part1)).append('"');
                }
            }
            lastEnd = matcher.end();
        }
        sb.append(protectedSql.substring(lastEnd));
        // 3. 第二遍：修复 DML 语句中紧跟 ( 的表名（如 INSERT INTO test(...)）
        //    这些表名在第一遍被 (?![\\w\"\`(]) 跳过了
        String quoted = quoteDmlTableNames(sb.toString(), identifierCase);
        // 4. 还原字符串字面量
        return restoreStringLiterals(quoted, stringLiterals);
    }
    /**
     * 提取 SQL 中所有单引号字符串字面量，用不可见占位符替换。
     * 支持 SQL 标准的双单引号转义（'' 表示一个单引号字符）。
     *
     * @param sql    原始 SQL
     * @param out    输出列表，按索引存放提取出的字符串字面量（含外层单引号）
     * @return 替换占位符后的 SQL
     */
    private static String protectStringLiterals(String sql, List<String> out) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 进入字符串字面量
                int start = i;
                i++; // 跳过开头的单引号
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            // 转义的 '' → 跳过一个字符
                            i += 2;
                        } else {
                            // 字符串结束
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                // 占位符使用 \0 + 索引 + \0，\0 不会出现在正常 SQL 中
                int index = out.size();
                out.add(sql.substring(start, i));
                sb.append('\0').append(index).append('\0');
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
    /**
     * 将占位符还原为原始字符串字面量。
     */
    private static String restoreStringLiterals(String sql, List<String> stringLiterals) {
        if (stringLiterals.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\0') {
                // 找到占位符结束位置
                int end = sql.indexOf('\0', i + 1);
                if (end > i) {
                    int index = Integer.parseInt(sql.substring(i + 1, end));
                    sb.append(stringLiterals.get(index));
                    i = end + 1;
                } else {
                    // 异常情况，直接复制
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
    /**
     * 为 DML 语句中紧跟 ( 的表名加引号（正则第一遍会被函数调用前瞻误排除）。
     * 仅当表名前面是 INTO / FROM / UPDATE / JOIN / TABLE 等 DML 关键字时才处理。
     */
    private static String quoteDmlTableNames(String sql, TranslationConfig.IdentifierCase identifierCase) {
        Pattern dmlPattern = Pattern.compile("(?<![\\w\"`])([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
        Matcher m = dmlPattern.matcher(sql);
        StringBuilder sb = new StringBuilder(sql.length() + 16);
        int lastEnd = 0;
        while (m.find()) {
            String word = m.group(1);
            // 只处理前面是 DML 关键字的，排除普通函数调用
            if (!isDmlContextBefore(sql, m.start()) || SQL_KEYWORDS.contains(word.toUpperCase(Locale.ROOT))) {
                continue;
            }
            sb.append(sql, lastEnd, m.start());
            sb.append('"').append(identifierCase.apply(word)).append('"');
            sb.append('(');
            lastEnd = m.end();
        }
        sb.append(sql.substring(lastEnd));
        return sb.toString();
    }
    /** 检查标识符位置之前是否是 DML 上下文（INTO/FROM/UPDATE/JOIN/TABLE 等） */
    private static boolean isDmlContextBefore(String sql, int pos) {
        // 向前找最近的非空白 token
        int i = pos - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        if (i < 0) return false;
        // 找到 token 开始位置
        int tokenEnd = i + 1;
        while (i >= 0 && Character.isLetterOrDigit(sql.charAt(i))) {
            i--;
        }
        String token = sql.substring(i + 1, tokenEnd).toUpperCase(Locale.ROOT);
        return "INTO".equals(token)
                || "FROM".equals(token)
                || "UPDATE".equals(token)
                || "JOIN".equals(token)
                || "TABLE".equals(token)
                || "EXISTS".equals(token);
    }
    // --- 便捷静态方法 ---
    /**
     * 便捷方法：直接翻译 SQL，使用默认配置。
     *
     * @param sql            源 SQL
     * @param sourceDialect  源方言标识符
     * @param targetDialect  目标方言标识符
     * @return 翻译后的 SQL
     */
    public static String translate(String sql, String sourceDialect, String targetDialect) {
        DialectType source = DialectType.fromIdentifier(sourceDialect);
        DialectType target = DialectType.fromIdentifier(targetDialect);
        return new SqlTranslator(source, target).translate(sql);
    }
    /**
     * 便捷方法：直接翻译 SQL，使用默认配置。
     *
     * @param sql            源 SQL
     * @param sourceDialect  源方言类型
     * @param targetDialect  目标方言类型
     * @return 翻译后的 SQL
     */
    public static String translate(String sql, DialectType sourceDialect, DialectType targetDialect) {
        return new SqlTranslator(sourceDialect, targetDialect).translate(sql);
    }
    /**
     * 便捷方法：直接翻译 SQL，使用指定配置。
     *
     * @param sql            源 SQL
     * @param sourceDialect  源方言类型
     * @param targetDialect  目标方言类型
     * @param config         翻译配置（大小写策略）
     * @return 翻译后的 SQL
     */
    public static String translate(
            String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        return new SqlTranslator(sourceDialect, targetDialect, config).translate(sql);
    }

    public DialectType getSourceDialect() {
        return sourceDialect;
    }

    public DialectType getTargetDialect() {
        return targetDialect;
    }
    /**
     * 校验 SQL AST，并进行隐式列展开、类型推断和自动补全。
     */
    private SqlNode validateSql(SqlNode parsed, MetadataProvider metadataProvider) {
        if (metadataProvider == null) {
            return parsed;
        }
        try {
            // 1. 类型工厂
            RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
            // 2. 创建元数据 Schema 并添加到 root schema 路径中
            SchemaPlus rootSchema = Frameworks.createRootSchema(true);
            rootSchema.add("PUBLIC", new CalciteMetadataSchema(metadataProvider));
            // 3. 构建 CatalogReader，设置默认 Schema 搜索路径为 "PUBLIC"
            CalciteSchema calciteSchema = CalciteSchema.from(rootSchema);
            List<String> defaultSchemaPath = Collections.singletonList("PUBLIC");
            CalciteConnectionConfig connectionConfig = CalciteConnectionConfig.DEFAULT;
            CalciteCatalogReader catalogReader =
                    new CalciteCatalogReader(calciteSchema, defaultSchemaPath, typeFactory, connectionConfig);
            // 4. 构建联合算子表（标准 SQL 函数 + 方言专属函数 + 自定义方言函数如 IFNULL）
            SqlOperatorTable stdOpTable = SqlStdOperatorTable.instance();
            SqlOperatorTable libraryOpTable = SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(
                    SqlLibrary.MYSQL, SqlLibrary.ORACLE, SqlLibrary.POSTGRESQL);
            SqlOperatorTable customOpTable = new SqlOperatorTable() {
                private final List<SqlOperator> list = Arrays.asList(
                        new SqlFunction(
                                "IFNULL",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0_NULLABLE,
                                null,
                                OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "DATE_ADD",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "ADDDATE",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "DATE_SUB",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "SUBDATE",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "DATE_FORMAT",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "STR_TO_DATE",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "SUBSTRING_INDEX",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "GROUP_CONCAT",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "JSON_EXTRACT",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "JSON_UNQUOTE",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "JSON_OBJECTAGG",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "JSON_AGG",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "JSON_BUILD_OBJECT",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.ARG0,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "FIELD",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.INTEGER,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "INSTR",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.INTEGER,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM),
                        new SqlFunction(
                                "TIMESTAMPDIFF",
                                SqlKind.OTHER_FUNCTION,
                                ReturnTypes.INTEGER,
                                null,
                                OperandTypes.VARIADIC,
                                SqlFunctionCategory.SYSTEM));

                @Override
                public void lookupOperatorOverloads(
                        SqlIdentifier opName,
                        SqlFunctionCategory category,
                        SqlSyntax syntax,
                        List<SqlOperator> operatorList,
                        SqlNameMatcher nameMatcher) {
                    for (SqlOperator op : list) {
                        if (nameMatcher.matches(op.getName(), opName.getSimple())) {
                            operatorList.add(op);
                        }
                    }
                }

                @Override
                public List<SqlOperator> getOperatorList() {
                    return list;
                }
            };
            SqlOperatorTable chainOpTable = SqlOperatorTables.chain(stdOpTable, libraryOpTable, customOpTable);
            // 5. 设置符合度 Conformance 级别
            SqlConformance conformance = getConformanceForSource(sourceDialect);
            // 6. 创建验证器配置，支持标示符大小写敏感（Calcite 默认与 Conformance 相关，这里支持标识符展开）
            SqlValidator.Config validatorConfig = SqlValidator.Config.DEFAULT
                    .withConformance(conformance)
                    .withIdentifierExpansion(true)
                    .withCallRewrite(true);
            // 7. 创建验证器并校验
            SqlValidator validator =
                    SqlValidatorUtil.newValidator(chainOpTable, catalogReader, typeFactory, validatorConfig);
            return validator.validate(parsed);
        } catch (Exception e) {
            throw new SqlTranslationException("SQL 校验失败: " + e.getMessage(), e);
        }
    }
}
