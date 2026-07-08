package com.translator.jdbc;

import java.sql.*;

import org.junit.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * TranslatorDriver 集成测试。
 * 使用 Testcontainers 启动真实 PostgreSQL 容器，
 * 通过 TranslatorDriver 发送 MySQL 方言 SQL，验证透明翻译执行。
 *
 * 注意：需要本机安装并运行 Docker Desktop。
 * 可通过 mvn test -DskipIntegrationTests=false 运行。
 */
@Ignore("需要 Docker Desktop 环境，默认跳过。运行: mvn test -Dtest=TranslatorDriverIntegrationTest")
public class TranslatorDriverIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    private Connection translatorConnection;
    private Statement statement;

    @BeforeClass
    public static void startContainer() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @AfterClass
    public static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Before
    public void setUp() throws Exception {
        // 先连真实 PG 建表
        try (Connection rawConn =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement stmt = rawConn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS users");
                stmt.execute("CREATE TABLE users (" + "id INTEGER PRIMARY KEY, "
                        + "name VARCHAR(100), "
                        + "age INTEGER"
                        + ")");
                stmt.execute("INSERT INTO users VALUES (1, 'Alice', 30)");
                stmt.execute("INSERT INTO users VALUES (2, 'Bob', 25)");
                stmt.execute("INSERT INTO users VALUES (3, 'Charlie', 35)");
            }
        }

        // 通过 JDBC Wrapper 连接
        // URL 格式: jdbc:translator:<源方言>:<目标数据库子协议>://<真实PG地址>
        // postgresql 既是子协议也是方言组的 key
        String translatorUrl = "jdbc:translator:mysql:postgresql://"
                + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + postgres.getDatabaseName();

        translatorConnection =
                DriverManager.getConnection(translatorUrl, postgres.getUsername(), postgres.getPassword());
        statement = translatorConnection.createStatement();
    }

    @After
    public void tearDown() throws Exception {
        if (statement != null) {
            statement.close();
        }
        if (translatorConnection != null) {
            translatorConnection.close();
        }
    }

    @Test
    public void testSelectWithTranslation() throws Exception {
        // 发送 MySQL 方言 SQL → 在 PG 上执行
        ResultSet rs = statement.executeQuery("SELECT `id`, `name`, `age` FROM `users` ORDER BY `id`");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals("Alice", rs.getString("name"));
        Assert.assertEquals(30, rs.getInt("age"));
        Assert.assertTrue(rs.next());
        Assert.assertEquals(2, rs.getInt("id"));
        rs.close();
    }

    @Test
    public void testSelectWithWhere() throws Exception {
        ResultSet rs = statement.executeQuery("SELECT `name`, `age` FROM `users` WHERE `age` > 25 ORDER BY `age`");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Alice", rs.getInt("age"), 30);
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Charlie", rs.getInt("age"), 35);
        Assert.assertFalse(rs.next());
        rs.close();
    }

    @Test
    public void testSelectWithFunction() throws Exception {
        // IFNULL → COALESCE 在 PG 上执行
        ResultSet rs =
                statement.executeQuery("SELECT IFNULL(`name`, 'N/A') AS `name` FROM `users` ORDER BY `id` LIMIT 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Alice", rs.getString("name"));
        rs.close();
    }

    @Test
    public void testInsertAndSelect() throws Exception {
        // DML 翻译
        int updated = statement.executeUpdate("INSERT INTO `users` (`id`, `name`, `age`) VALUES (4, 'Diana', 28)");
        Assert.assertEquals(1, updated);

        ResultSet rs = statement.executeQuery("SELECT `name` FROM `users` WHERE `id` = 4");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Diana", rs.getString("name"));
        rs.close();
    }

    @Test
    public void testUpdateAndSelect() throws Exception {
        int updated = statement.executeUpdate("UPDATE `users` SET `age` = 31 WHERE `name` = 'Alice'");
        Assert.assertEquals(1, updated);

        ResultSet rs = statement.executeQuery("SELECT `age` FROM `users` WHERE `name` = 'Alice'");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(31, rs.getInt("age"));
        rs.close();
    }

    @Test
    public void testDeleteAndSelect() throws Exception {
        int deleted = statement.executeUpdate("DELETE FROM `users` WHERE `name` = 'Charlie'");
        Assert.assertEquals(1, deleted);

        ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS cnt FROM `users`");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(2, rs.getInt("cnt"));
        rs.close();
    }

    @Test
    public void testPreparedStatement() throws Exception {
        PreparedStatement pstmt =
                translatorConnection.prepareStatement("SELECT `name`, `age` FROM `users` WHERE `id` = ?");
        pstmt.setInt(1, 2);
        ResultSet rs = pstmt.executeQuery();
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Bob", rs.getString("name"));
        Assert.assertEquals(25, rs.getInt("age"));
        Assert.assertFalse(rs.next());
        rs.close();
        pstmt.close();
    }

    @Test
    public void testNativeSql() throws Exception {
        // nativeSQL 应返回翻译后的 SQL
        String translated = translatorConnection.nativeSQL("SELECT IFNULL(`name`, 'N/A') FROM `users` LIMIT 1");
        // 不应包含 IFNULL 或反引号
        Assert.assertFalse("不应包含 IFNULL", translated.toUpperCase().contains("IFNULL"));
        Assert.assertFalse("不应包含反引号", translated.contains("`"));
    }

    @Test
    public void testLimitOffsetPagination() throws Exception {
        // MySQL 风格分页
        ResultSet rs = statement.executeQuery("SELECT `name` FROM `users` ORDER BY `id` LIMIT 1 OFFSET 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Bob", rs.getString("name"));
        rs.close();
    }
}
