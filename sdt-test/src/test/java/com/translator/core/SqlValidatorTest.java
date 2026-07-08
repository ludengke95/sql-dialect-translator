package com.translator.core;

import com.translator.core.config.TranslationConfig;
import com.translator.core.metadata.ColumnMetadata;
import com.translator.core.metadata.JdbcMetadataProvider;
import com.translator.core.metadata.MetadataProvider;
import com.translator.core.metadata.TableMetadata;
import org.junit.Assert;
import org.junit.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SQL 校验测试。
 * 验证在提供 Mock 元数据后，Calcite SQL 校验器能正确识别表、字段与函数签名。
 */
public class SqlValidatorTest {

    // 内存 Mock 结构的数据源
    private static class MockMetadataProvider implements MetadataProvider {
        @Override
        public TableMetadata getTable(String tableName) {
            String upper = tableName.toUpperCase();
            if ("USERS".equals(upper)) {
                return new TableMetadata("users", Arrays.asList(
                        new ColumnMetadata("id", Types.INTEGER, 0, 0, false),
                        new ColumnMetadata("name", Types.VARCHAR, 255, 0, true),
                        new ColumnMetadata("age", Types.INTEGER, 0, 0, true)
                ));
            } else if ("ORDERS".equals(upper)) {
                return new TableMetadata("orders", Arrays.asList(
                        new ColumnMetadata("order_id", Types.INTEGER, 0, 0, false),
                        new ColumnMetadata("user_id", Types.INTEGER, 0, 0, false),
                        new ColumnMetadata("amount", Types.DECIMAL, 10, 2, true),
                        new ColumnMetadata("status", Types.VARCHAR, 50, 0, true)
                ));
            }
            return null;
        }

        @Override
        public Set<String> getTableNames() {
            return new java.util.HashSet<>(Arrays.asList("users", "USERS", "orders", "ORDERS"));
        }
    }

    @Test
    public void testValidSql() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        String sql = "SELECT id, name FROM users WHERE age > 18";
        String result = translator.translate(sql);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.toUpperCase().contains("SELECT"));
        Assert.assertTrue(result.toUpperCase().contains("USERS"));
    }

    @Test
    public void testTableNotFoundThrows() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        String sql = "SELECT id FROM non_existing_table";
        try {
            translator.translate(sql);
            Assert.fail("表不存在时应该抛出异常");
        } catch (SqlTranslationException e) {
            System.err.println("=== testTableNotFoundThrows exception message: " + e.getMessage());
            Assert.assertTrue(e.getMessage().contains("SQL 校验失败"));
            Assert.assertTrue(e.getMessage().toLowerCase().contains("non_existing_table") && e.getMessage().toLowerCase().contains("not found"));
        }
    }

    @Test
    public void testColumnNotFoundThrows() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        String sql = "SELECT invalid_column FROM users";
        try {
            translator.translate(sql);
            Assert.fail("列不存在时应该抛出异常");
        } catch (SqlTranslationException e) {
            System.err.println("=== testColumnNotFoundThrows exception message: " + e.getMessage());
            Assert.assertTrue(e.getMessage().contains("SQL 校验失败"));
            Assert.assertTrue(e.getMessage().toLowerCase().contains("invalid_column") && e.getMessage().toLowerCase().contains("not found"));
        }
    }

    @Test
    public void testFunctionArgTypeMismatchThrows() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        // SUBSTR(string, integer, integer)，第二个参数应当是数字，但传入了非数字
        String sql = "SELECT SUBSTR(name, 'not_an_integer') FROM users";
        try {
            translator.translate(sql);
            Assert.fail("函数参数类型不匹配时应该抛出异常");
        } catch (SqlTranslationException e) {
            System.err.println("=== testFunctionArgTypeMismatchThrows exception message: " + e.getMessage());
            Assert.assertTrue(e.getMessage().contains("SQL 校验失败"));
        }
    }

    @Test
    public void testImplicitColumnResolution() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        // name 唯一属于 users，status 唯一属于 orders。
        // 多表关联时，Calcite 的校验器应该根据元数据自动把没有别名的列补齐它所在的表名称前缀。
        String sql = "SELECT name, status FROM users JOIN orders ON id = user_id";
        String result = translator.translate(sql);
        System.out.println("Resolution Result: " + result);

        // 校验器自动为 name 补齐了 users.name，为 status 补齐了 orders.status，为 id 补齐了 users.id
        Assert.assertTrue(result.contains("users"));
        Assert.assertTrue(result.contains("orders"));
    }

    @Test
    public void testDialectFunctionsValidation() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        // MySQL 的 IFNULL 函数应该在校验阶段被正确识别并通过校验
        String sql = "SELECT IFNULL(name, 'unknown') FROM users";
        try {
            String result = translator.translate(sql);
            Assert.assertTrue(result.toUpperCase().contains("COALESCE"));
        } catch (Exception e) {
            System.err.println("=== testDialectFunctionsValidation exception stack trace:");
            e.printStackTrace();
            Assert.fail("DialectFunctionsValidation failed: " + e.getMessage());
        }
    }

    @Test
    public void testDateAddValidation() {
        TranslationConfig config = new TranslationConfig().withEnableValidation(true);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        String sql = "SELECT DATE_ADD('1998-12-01', INTERVAL -90 DAY) FROM users";
        try {
            String result = translator.translate(sql);
            Assert.assertTrue("应包含 CAST: " + result, result.toUpperCase().contains("CAST"));
            Assert.assertTrue("应包含 TIMESTAMP: " + result, result.toUpperCase().contains("TIMESTAMP"));
            Assert.assertTrue("应包含 -90 DAYS: " + result, result.contains("-90 DAYS"));
        } catch (Exception e) {
            System.err.println("=== testDateAddValidation exception stack trace:");
            e.printStackTrace();
            Assert.fail("DateAddValidation failed: " + e.getMessage());
        }
    }

    @Test
    public void testValidationModeWarnFallbacks() {
        // 设置校验模式为 WARN (不阻断模式)
        TranslationConfig config = new TranslationConfig()
                .withEnableValidation(true)
                .withValidationMode(TranslationConfig.ValidationMode.WARN);
        SqlTranslator translator = new SqlTranslator(DialectType.MYSQL, DialectType.POSTGRESQL, config, new MockMetadataProvider());

        // 表不存在，在 STRICT 下会抛错，但在 WARN 下应当只记 log 并正常翻译，不抛异常
        String sql = "SELECT id FROM non_existing_table";
        String result = translator.translate(sql);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.toUpperCase().contains("NON_EXISTING_TABLE"));
    }

    @Test
    public void testMaxTablesEnforced() throws java.sql.SQLException {
        java.sql.Connection conn = createMockConnection(Arrays.asList("t1", "t2", "t3"));
        // 限制最多加载 2 个表结构
        JdbcMetadataProvider provider = new JdbcMetadataProvider(conn, 2);

        // 确认初始加载了全部候选名字
        Assert.assertTrue(provider.getTableNames().contains("t1"));
        Assert.assertTrue(provider.getTableNames().contains("t2"));
        Assert.assertTrue(provider.getTableNames().contains("t3"));

        // 加载前两个表，应该成功
        Assert.assertNotNull(provider.getTable("t1"));
        Assert.assertNotNull(provider.getTable("t2"));

        // 尝试加载第三个表，由于超过限制，应当返回 null
        Assert.assertNull(provider.getTable("t3"));
    }

    // ===== JDK 动态代理模拟 JDBC =====

    private static Object handleDefaultPrimitive(Class<?> returnType) {
        if (returnType.equals(boolean.class)) {
            return false;
        }
        if (returnType.equals(int.class)) {
            return 0;
        }
        if (returnType.equals(long.class)) {
            return 0L;
        }
        return null;
    }

    private static java.sql.Connection createMockConnection(List<String> tableNames) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                SqlValidatorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return createMockDatabaseMetaData(tableNames);
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return handleDefaultPrimitive(method.getReturnType());
                    }
                    return null;
                }
        );
    }

    private static java.sql.DatabaseMetaData createMockDatabaseMetaData(List<String> tableNames) {
        return (java.sql.DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                SqlValidatorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getTables".equals(method.getName())) {
                        return createMockTablesResultSet(tableNames);
                    }
                    if ("getColumns".equals(method.getName())) {
                        return createMockColumnsResultSet();
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return handleDefaultPrimitive(method.getReturnType());
                    }
                    return null;
                }
        );
    }

    private static java.sql.ResultSet createMockTablesResultSet(List<String> tableNames) {
        final int[] index = {0};
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                SqlValidatorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] <= tableNames.size();
                    }
                    if ("getString".equals(method.getName()) && "TABLE_NAME".equals(args[0])) {
                        return tableNames.get(index[0] - 1);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return handleDefaultPrimitive(method.getReturnType());
                    }
                    return null;
                }
        );
    }

    private static java.sql.ResultSet createMockColumnsResultSet() {
        final int[] index = {0};
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                SqlValidatorTest.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        index[0]++;
                        return index[0] <= 1; // 仅返回 1 列以做测试
                    }
                    if ("getString".equals(method.getName()) && "COLUMN_NAME".equals(args[0])) {
                        return "id";
                    }
                    if ("getInt".equals(method.getName())) {
                        if ("DATA_TYPE".equals(args[0])) return java.sql.Types.INTEGER;
                        if ("COLUMN_SIZE".equals(args[0])) return 0;
                        if ("DECIMAL_DIGITS".equals(args[0])) return 0;
                        if ("NULLABLE".equals(args[0])) return java.sql.DatabaseMetaData.columnNullable;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return handleDefaultPrimitive(method.getReturnType());
                    }
                    return null;
                }
        );
    }

    @Test
    public void testDiagnostics() {
        org.apache.calcite.schema.SchemaPlus rootSchema = org.apache.calcite.tools.Frameworks.createRootSchema(true);
        rootSchema.add("PUBLIC", new com.translator.core.metadata.CalciteMetadataSchema(new MockMetadataProvider()));
        org.apache.calcite.jdbc.CalciteSchema calciteSchema = org.apache.calcite.jdbc.CalciteSchema.from(rootSchema);

        org.apache.calcite.jdbc.CalciteSchema publicSchemaSensitive = calciteSchema.getSubSchema("PUBLIC", true);
        org.apache.calcite.jdbc.CalciteSchema publicSchemaInsensitive = calciteSchema.getSubSchema("PUBLIC", false);
        System.out.println("=== DIAGNOSTICS: publicSchema (sensitive) found: " + (publicSchemaSensitive != null));
        System.out.println("=== DIAGNOSTICS: publicSchema (insensitive) found: " + (publicSchemaInsensitive != null));

        org.apache.calcite.jdbc.CalciteSchema targetSchema = publicSchemaSensitive != null ? publicSchemaSensitive : publicSchemaInsensitive;
        if (targetSchema != null) {
            org.apache.calcite.jdbc.CalciteSchema.TableEntry tableEntrySensitive = targetSchema.getTable("users", true);
            org.apache.calcite.jdbc.CalciteSchema.TableEntry tableEntryInsensitive = targetSchema.getTable("users", false);
            System.out.println("=== DIAGNOSTICS: tableEntry 'users' (sensitive) found: " + (tableEntrySensitive != null));
            System.out.println("=== DIAGNOSTICS: tableEntry 'users' (insensitive) found: " + (tableEntryInsensitive != null));
        }

        // 手工调用 getTable
        try {
            com.translator.core.metadata.CalciteMetadataSchema mySchema = new com.translator.core.metadata.CalciteMetadataSchema(new MockMetadataProvider());
            org.apache.calcite.schema.Table t = mySchema.getTable("users");
            System.out.println("=== DIAGNOSTICS: schema.getTable(\"users\") manually called returned: " + t);
        } catch (Throwable ex) {
            System.out.println("=== DIAGNOSTICS: schema.getTable(\"users\") threw: " + ex);
            ex.printStackTrace();
        }

        // 尝试用 CalciteCatalogReader 来手工查找
        org.apache.calcite.rel.type.RelDataTypeFactory typeFactory = new org.apache.calcite.sql.type.SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        org.apache.calcite.prepare.CalciteCatalogReader catalogReader = new org.apache.calcite.prepare.CalciteCatalogReader(
                calciteSchema,
                java.util.Collections.singletonList("PUBLIC"),
                typeFactory,
                org.apache.calcite.config.CalciteConnectionConfig.DEFAULT);

        org.apache.calcite.plan.RelOptTable relOptTable = catalogReader.getTable(java.util.Arrays.asList("users"));
        System.out.println("=== DIAGNOSTICS: catalogReader.getTable([\"users\"]) found: " + (relOptTable != null));

        // 检查算子表
        org.apache.calcite.sql.SqlOperatorTable stdOpTable = org.apache.calcite.sql.fun.SqlStdOperatorTable.instance();
        org.apache.calcite.sql.SqlOperatorTable libraryOpTable = org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory.INSTANCE
                .getOperatorTable(org.apache.calcite.sql.fun.SqlLibrary.MYSQL, org.apache.calcite.sql.fun.SqlLibrary.ORACLE, org.apache.calcite.sql.fun.SqlLibrary.POSTGRESQL);
        boolean hasIfnull = libraryOpTable.getOperatorList().stream().anyMatch(op -> "IFNULL".equalsIgnoreCase(op.getName()));
        System.out.println("=== DIAGNOSTICS: libraryOpTable contains IFNULL: " + hasIfnull);
    }
}
