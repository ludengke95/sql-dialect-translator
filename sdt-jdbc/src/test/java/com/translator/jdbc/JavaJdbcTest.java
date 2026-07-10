package com.translator.jdbc;

import java.sql.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
@Ignore("Requires local MySQL instance running on port 3306")
public class JavaJdbcTest {

    private Connection connection;
    private Statement statement;

    @Before
    public void setUp() throws Exception {
        // 通过 JDBC Wrapper 连接
        // URL 格式: jdbc:translator:<源方言>:<目标数据库子协议>://<真实PG地址>
        // postgresql 既是子协议也是方言组的 key
        String jdbcUrl = "jdbc:mysql://" + "127.0.0.1" + ":" + 3306 + "/tes"
                // +"?defaultAuthenticationPlugin=caching_sha2_password"
                ;

        connection = DriverManager.getConnection(jdbcUrl, "root", "123");
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

    /**
     * 测试 MySQL 5.7 JDBC 兼容性（使用 mysql_native_password 认证插件）
     */
    @Test
    public void testMySQL57Connection() throws Exception {
        // MySQL 5.7 JDBC 默认使用 mysql_native_password 认证插件
        String jdbcUrl = "jdbc:mysql://"
                + "127.0.0.1" + ":" + 3306
                + "/tpch"
                + "?defaultAuthenticationPlugin=mysql_native_password";

        Connection conn57 = DriverManager.getConnection(jdbcUrl, "root", "123");
        Statement stmt57 = conn57.createStatement();

        ResultSet rs = stmt57.executeQuery(
                "WITH revenue AS (    SELECT        l_suppkey AS supplier_no,        SUM(l_extendedprice * (1 - l_discount)) AS total_revenue    FROM        lineitem    WHERE        l_shipdate >= '1996-01-01'        AND l_shipdate < DATE_ADD('1996-01-01', INTERVAL 3 MONTH)    GROUP BY        l_suppkey) SELECT    s_suppkey,    s_name,    s_address,    s_phone,    total_revenue FROM    supplier,    revenue WHERE    s_suppkey = supplier_no    AND total_revenue = (SELECT MAX(total_revenue) FROM revenue)ORDER BY s_suppkey;");
        if (rs.next()) {
            System.out.println("MySQL 5.7 JDBC test: " + rs.getInt(1));
        }

        stmt57.close();
        conn57.close();
    }
}
