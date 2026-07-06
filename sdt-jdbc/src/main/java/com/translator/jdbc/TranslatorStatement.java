package com.translator.jdbc;

import com.translator.metrics.BackendMetrics;
import com.translator.metrics.TranslationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.List;

/**
 * 翻译 Statement 实现。
 * 在 execute/executeQuery/executeUpdate 等方法拦截 SQL，经翻译引擎转换后执行。
 */
public class TranslatorStatement implements Statement {

    private static final Logger log = LoggerFactory.getLogger(TranslatorStatement.class);

    protected final Statement realStatement;
    protected final TranslatorConnection translatorConnection;

    public TranslatorStatement(Statement realStatement,
                               TranslatorConnection translatorConnection) {
        this.realStatement = realStatement;
        this.translatorConnection = translatorConnection;
    }

    /** JDBC 驱动默认后端名 */
    private static final String BACKEND_NAME = "jdbc";

    /**
     * 翻译 SQL 并返回翻译后的语句。
     */
    protected String translateSql(String sql) {
        return translatorConnection.translateSql(sql);
    }

    /** 获取目标方言标识（用于指标打点） */
    private String targetDialect() {
        return translatorConnection.getTranslator().getTargetDialect().getIdentifier();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        TranslationMetrics.recordRequest(targetDialect(), BACKEND_NAME);
        long transStart = System.nanoTime();
        String translatedSql = translateSql(sql);
        double transSec = (System.nanoTime() - transStart) / 1_000_000_000.0;
        TranslationMetrics.recordSuccess();
        TranslationMetrics.recordDuration(targetDialect(), BACKEND_NAME, transSec);
        log.debug("executeQuery: [{}] → [{}]", sql, translatedSql);

        BackendMetrics.recordQuery(BACKEND_NAME, BackendMetrics.classifyQueryType(sql));
        long execStart = System.nanoTime();
        try {
            ResultSet rs = realStatement.executeQuery(translatedSql);
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            return rs;
        } catch (SQLException e) {
            BackendMetrics.recordError(BACKEND_NAME, e.getSQLState() != null ? e.getSQLState() : "HY000");
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            throw e;
        }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        TranslationMetrics.recordRequest(targetDialect(), BACKEND_NAME);
        long transStart = System.nanoTime();
        String translatedSql = translateSql(sql);
        double transSec = (System.nanoTime() - transStart) / 1_000_000_000.0;
        TranslationMetrics.recordSuccess();
        TranslationMetrics.recordDuration(targetDialect(), BACKEND_NAME, transSec);
        log.debug("executeUpdate: [{}] → [{}]", sql, translatedSql);

        BackendMetrics.recordQuery(BACKEND_NAME, BackendMetrics.classifyQueryType(sql));
        long execStart = System.nanoTime();
        try {
            int result = realStatement.executeUpdate(translatedSql);
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            BackendMetrics.observeAffectedRows(BACKEND_NAME, Math.max(result, 0));
            return result;
        } catch (SQLException e) {
            BackendMetrics.recordError(BACKEND_NAME, e.getSQLState() != null ? e.getSQLState() : "HY000");
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            throw e;
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        TranslationMetrics.recordRequest(targetDialect(), BACKEND_NAME);
        long transStart = System.nanoTime();
        String translatedSql = translateSql(sql);
        double transSec = (System.nanoTime() - transStart) / 1_000_000_000.0;
        TranslationMetrics.recordSuccess();
        TranslationMetrics.recordDuration(targetDialect(), BACKEND_NAME, transSec);
        log.debug("execute: [{}] → [{}]", sql, translatedSql);

        BackendMetrics.recordQuery(BACKEND_NAME, BackendMetrics.classifyQueryType(sql));
        long execStart = System.nanoTime();
        try {
            boolean result = realStatement.execute(translatedSql);
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            return result;
        } catch (SQLException e) {
            BackendMetrics.recordError(BACKEND_NAME, e.getSQLState() != null ? e.getSQLState() : "HY000");
            double execSec = (System.nanoTime() - execStart) / 1_000_000_000.0;
            BackendMetrics.recordQueryDuration(BACKEND_NAME, execSec);
            throw e;
        }
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeUpdate(translatedSql, autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeUpdate(translatedSql, columnIndexes);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeUpdate(translatedSql, columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.execute(translatedSql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.execute(translatedSql, columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.execute(translatedSql, columnNames);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        String translatedSql = translateSql(sql);
        realStatement.addBatch(translatedSql);
    }

    // ===== 其他方法直接委托 =====

    @Override
    public ResultSet getResultSet() throws SQLException {
        return realStatement.getResultSet();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return realStatement.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return realStatement.getMoreResults();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return realStatement.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        realStatement.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return realStatement.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        realStatement.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        realStatement.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return realStatement.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        realStatement.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        realStatement.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return realStatement.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        realStatement.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        realStatement.setCursorName(name);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return realStatement.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return realStatement.getGeneratedKeys();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return realStatement.executeBatch();
    }

    @Override
    public void clearBatch() throws SQLException {
        realStatement.clearBatch();
    }

    @Override
    public void close() throws SQLException {
        realStatement.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return realStatement.isClosed();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        realStatement.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return realStatement.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        realStatement.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return realStatement.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return realStatement.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return realStatement.getResultSetType();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        realStatement.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return realStatement.isPoolable();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return translatorConnection;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        realStatement.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return realStatement.isCloseOnCompletion();
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return realStatement.getResultSetHoldability();
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        return realStatement.getLargeUpdateCount();
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        realStatement.setLargeMaxRows(max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        return realStatement.getLargeMaxRows();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        return realStatement.executeLargeBatch();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeLargeUpdate(translatedSql);
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeLargeUpdate(translatedSql, autoGeneratedKeys);
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeLargeUpdate(translatedSql, columnIndexes);
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        String translatedSql = translateSql(sql);
        return realStatement.executeLargeUpdate(translatedSql, columnNames);
    }



    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }
        return realStatement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass()) || realStatement.isWrapperFor(iface);
    }

    @Override
    public String toString() {
        return "TranslatorStatement wrapping " + realStatement.toString();
    }
}
