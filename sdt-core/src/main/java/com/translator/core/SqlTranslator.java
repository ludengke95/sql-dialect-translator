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
import com.translator.core.preprocessor.PreProcessorRegistry;
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
     * 根据源方言做预处理责任链（标识符引用转换、语法规范化等），然后解析。
     */
    private SqlNode parseSql(String sql) throws SqlParseException {
        // 预处理：通过 PreProcessorRegistry 运行源方言前置处理器责任链
        String normalizedSql = PreProcessorRegistry.process(sql, sourceDialect, targetDialect, config);
        SqlParser.Config parserConfig =
                SqlParser.config().withCaseSensitive(true).withConformance(getConformanceForSource(sourceDialect));
        SqlParser parser = SqlParser.create(normalizedSql, parserConfig);
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
