package com.translator.jdbc;

import java.sql.*;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.metrics.ConnectionMetrics;

/**
 * 翻译驱动入口类。
 * 实现 java.sql.Driver，包装真实 JDBC 驱动并透明翻译 SQL。
 *
 * JDBC URL 格式：jdbc:translator:<源方言>:<目标方言>://<真实连接地址>
 * 示例：jdbc:translator:mysql:postgresql://localhost:5432/mydb
 */
public class TranslatorDriver implements Driver {

    private static final Logger log = LoggerFactory.getLogger(TranslatorDriver.class);

    /** 驱动前缀 */
    public static final String URL_PREFIX = "jdbc:translator:";

    /** JDBC 驱动版本 */
    private static final int MAJOR_VERSION = 1;

    private static final int MINOR_VERSION = 0;

    static {
        try {
            DriverManager.registerDriver(new TranslatorDriver());
            log.info("TranslatorDriver 已注册到 DriverManager");
        } catch (SQLException e) {
            log.error("TranslatorDriver 注册失败", e);
            throw new RuntimeException("TranslatorDriver 注册失败", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        log.info("TranslatorDriver 连接: {}", url);

        JdbcUrlInfo urlInfo = JdbcUrlParser.parse(url, info);

        // 获取真实驱动并创建连接
        Driver realDriver = DriverManager.getDriver(urlInfo.getRealUrl());
        Connection realConnection = realDriver.connect(urlInfo.getRealUrl(), urlInfo.getRealProperties());

        if (realConnection == null) {
            throw new SQLException("无法获取真实数据库连接: " + urlInfo.getRealUrl());
        }

        log.info("成功创建翻译连接: {} → {}", urlInfo.getSourceDialect(), urlInfo.getTargetDialect());

        ConnectionMetrics.onConnect();

        return new TranslatorConnection(
                realConnection, urlInfo.getSourceDialect(), urlInfo.getTargetDialect(), urlInfo.getTranslationConfig());
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return JdbcUrlParser.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger 暂不支持");
    }
}
