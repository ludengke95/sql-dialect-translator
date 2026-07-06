package com.translator.jdbc;

import java.sql.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 
 */
public class JavaJdbcTest {

    private Connection connection;
    private Statement statement;

    @Before
    public void setUp() throws Exception {
        // 通过 JDBC Wrapper 连接
        // URL 格式: jdbc:translator:<源方言>:<目标数据库子协议>://<真实PG地址>
        // postgresql 既是子协议也是方言组的 key
        String jdbcUrl = "jdbc:mysql://"
                + "127.0.0.1" + ":" + 3307
                + "/tes"
        // +"?defaultAuthenticationPlugin=caching_sha2_password"
        ;

        connection = DriverManager.getConnection(
                jdbcUrl, "root", "proxy_password");
        statement = connection.createStatement();
    }

    @After
    public void tearDown() throws Exception {
        if (statement != null) {
            statement.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void testConnection() throws Exception {
        ResultSet rs = statement.executeQuery("SELECT 1");
        if (rs.next()) {
            System.out.println(rs.getInt(1));
        }
    }
}