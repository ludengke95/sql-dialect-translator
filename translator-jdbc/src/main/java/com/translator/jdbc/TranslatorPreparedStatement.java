package com.translator.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;

/**
 * 翻译 PreparedStatement 实现。
 * 在 setXxx 方法上委托给真实 PreparedStatement（参数值不变），
 * 在 execute 系列方法中执行翻译后的 SQL。
 */
public class TranslatorPreparedStatement extends TranslatorStatement
        implements PreparedStatement {

    private static final Logger log = LoggerFactory.getLogger(TranslatorPreparedStatement.class);

    private final PreparedStatement realPreparedStatement;
    private final String originalSql;
    private final String translatedSql;

    public TranslatorPreparedStatement(PreparedStatement realPreparedStatement,
                                       TranslatorConnection translatorConnection,
                                       String originalSql,
                                       String translatedSql) {
        super(realPreparedStatement, translatorConnection);
        this.realPreparedStatement = realPreparedStatement;
        this.originalSql = originalSql;
        this.translatedSql = translatedSql;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        log.debug("PreparedStatement.executeQuery: [{}] → [{}]", originalSql, translatedSql);
        return realPreparedStatement.executeQuery();
    }

    @Override
    public int executeUpdate() throws SQLException {
        log.debug("PreparedStatement.executeUpdate: [{}] → [{}]", originalSql, translatedSql);
        return realPreparedStatement.executeUpdate();
    }

    @Override
    public boolean execute() throws SQLException {
        log.debug("PreparedStatement.execute: [{}] → [{}]", originalSql, translatedSql);
        return realPreparedStatement.execute();
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        return realPreparedStatement.executeLargeUpdate();
    }

    // ===== 设置参数方法：直接委托 =====

    @Override public void setNull(int parameterIndex, int sqlType) throws SQLException {
        realPreparedStatement.setNull(parameterIndex, sqlType);
    }
    @Override public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        realPreparedStatement.setBoolean(parameterIndex, x);
    }
    @Override public void setByte(int parameterIndex, byte x) throws SQLException {
        realPreparedStatement.setByte(parameterIndex, x);
    }
    @Override public void setShort(int parameterIndex, short x) throws SQLException {
        realPreparedStatement.setShort(parameterIndex, x);
    }
    @Override public void setInt(int parameterIndex, int x) throws SQLException {
        realPreparedStatement.setInt(parameterIndex, x);
    }
    @Override public void setLong(int parameterIndex, long x) throws SQLException {
        realPreparedStatement.setLong(parameterIndex, x);
    }
    @Override public void setFloat(int parameterIndex, float x) throws SQLException {
        realPreparedStatement.setFloat(parameterIndex, x);
    }
    @Override public void setDouble(int parameterIndex, double x) throws SQLException {
        realPreparedStatement.setDouble(parameterIndex, x);
    }
    @Override public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        realPreparedStatement.setBigDecimal(parameterIndex, x);
    }
    @Override public void setString(int parameterIndex, String x) throws SQLException {
        realPreparedStatement.setString(parameterIndex, x);
    }
    @Override public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        realPreparedStatement.setBytes(parameterIndex, x);
    }
    @Override public void setDate(int parameterIndex, Date x) throws SQLException {
        realPreparedStatement.setDate(parameterIndex, x);
    }
    @Override public void setTime(int parameterIndex, Time x) throws SQLException {
        realPreparedStatement.setTime(parameterIndex, x);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        realPreparedStatement.setTimestamp(parameterIndex, x);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        realPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }
    @Override @SuppressWarnings("deprecation")
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        realPreparedStatement.setUnicodeStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        realPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        realPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void clearParameters() throws SQLException {
        realPreparedStatement.clearParameters();
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        realPreparedStatement.setObject(parameterIndex, x, targetSqlType);
    }
    @Override public void setObject(int parameterIndex, Object x) throws SQLException {
        realPreparedStatement.setObject(parameterIndex, x);
    }
    @Override public void setRef(int parameterIndex, Ref x) throws SQLException {
        realPreparedStatement.setRef(parameterIndex, x);
    }
    @Override public void setBlob(int parameterIndex, Blob x) throws SQLException {
        realPreparedStatement.setBlob(parameterIndex, x);
    }
    @Override public void setClob(int parameterIndex, Clob x) throws SQLException {
        realPreparedStatement.setClob(parameterIndex, x);
    }
    @Override public void setArray(int parameterIndex, Array x) throws SQLException {
        realPreparedStatement.setArray(parameterIndex, x);
    }
    @Override public ResultSetMetaData getMetaData() throws SQLException {
        return realPreparedStatement.getMetaData();
    }
    @Override public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        realPreparedStatement.setDate(parameterIndex, x, cal);
    }
    @Override public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        realPreparedStatement.setTime(parameterIndex, x, cal);
    }
    @Override public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        realPreparedStatement.setTimestamp(parameterIndex, x, cal);
    }
    @Override public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        realPreparedStatement.setNull(parameterIndex, sqlType, typeName);
    }
    @Override public void setURL(int parameterIndex, URL x) throws SQLException {
        realPreparedStatement.setURL(parameterIndex, x);
    }
    @Override public ParameterMetaData getParameterMetaData() throws SQLException {
        return realPreparedStatement.getParameterMetaData();
    }
    @Override public void setRowId(int parameterIndex, RowId x) throws SQLException {
        realPreparedStatement.setRowId(parameterIndex, x);
    }
    @Override public void setNString(int parameterIndex, String value) throws SQLException {
        realPreparedStatement.setNString(parameterIndex, value);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        realPreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }
    @Override public void setNClob(int parameterIndex, NClob value) throws SQLException {
        realPreparedStatement.setNClob(parameterIndex, value);
    }
    @Override public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        realPreparedStatement.setClob(parameterIndex, reader, length);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        realPreparedStatement.setBlob(parameterIndex, inputStream, length);
    }
    @Override public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        realPreparedStatement.setNClob(parameterIndex, reader, length);
    }
    @Override public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        realPreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }
    @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        realPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        realPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        realPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        realPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }
    @Override public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        realPreparedStatement.setAsciiStream(parameterIndex, x);
    }
    @Override public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        realPreparedStatement.setBinaryStream(parameterIndex, x);
    }
    @Override public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        realPreparedStatement.setCharacterStream(parameterIndex, reader);
    }
    @Override public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        realPreparedStatement.setNCharacterStream(parameterIndex, value);
    }
    @Override public void setClob(int parameterIndex, Reader reader) throws SQLException {
        realPreparedStatement.setClob(parameterIndex, reader);
    }
    @Override public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        realPreparedStatement.setBlob(parameterIndex, inputStream);
    }
    @Override public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        realPreparedStatement.setNClob(parameterIndex, reader);
    }

    @Override
    public void addBatch() throws SQLException {
        realPreparedStatement.addBatch();
    }
}
