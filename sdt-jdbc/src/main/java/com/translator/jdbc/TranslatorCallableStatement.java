package com.translator.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Map;

/**
 * 翻译 CallableStatement 实现。
 * 委托给真实的 CallableStatement，SQL 在 prepareCall 时已翻译。
 */
public class TranslatorCallableStatement extends TranslatorPreparedStatement
        implements CallableStatement {

    private final CallableStatement realCallableStatement;

    public TranslatorCallableStatement(CallableStatement realCallableStatement,
                                       TranslatorConnection translatorConnection,
                                       String originalSql,
                                       String translatedSql) {
        super(realCallableStatement, translatorConnection, originalSql, translatedSql);
        this.realCallableStatement = realCallableStatement;
    }

    @Override public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        realCallableStatement.registerOutParameter(parameterIndex, sqlType);
    }
    @Override public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        realCallableStatement.registerOutParameter(parameterIndex, sqlType, scale);
    }
    @Override public boolean wasNull() throws SQLException {
        return realCallableStatement.wasNull();
    }
    @Override public String getString(int parameterIndex) throws SQLException {
        return realCallableStatement.getString(parameterIndex);
    }
    @Override public boolean getBoolean(int parameterIndex) throws SQLException {
        return realCallableStatement.getBoolean(parameterIndex);
    }
    @Override public byte getByte(int parameterIndex) throws SQLException {
        return realCallableStatement.getByte(parameterIndex);
    }
    @Override public short getShort(int parameterIndex) throws SQLException {
        return realCallableStatement.getShort(parameterIndex);
    }
    @Override public int getInt(int parameterIndex) throws SQLException {
        return realCallableStatement.getInt(parameterIndex);
    }
    @Override public long getLong(int parameterIndex) throws SQLException {
        return realCallableStatement.getLong(parameterIndex);
    }
    @Override public float getFloat(int parameterIndex) throws SQLException {
        return realCallableStatement.getFloat(parameterIndex);
    }
    @Override public double getDouble(int parameterIndex) throws SQLException {
        return realCallableStatement.getDouble(parameterIndex);
    }
    @Override @Deprecated public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        return realCallableStatement.getBigDecimal(parameterIndex, scale);
    }
    @Override public byte[] getBytes(int parameterIndex) throws SQLException {
        return realCallableStatement.getBytes(parameterIndex);
    }
    @Override public Date getDate(int parameterIndex) throws SQLException {
        return realCallableStatement.getDate(parameterIndex);
    }
    @Override public Time getTime(int parameterIndex) throws SQLException {
        return realCallableStatement.getTime(parameterIndex);
    }
    @Override public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        return realCallableStatement.getTimestamp(parameterIndex);
    }
    @Override public Object getObject(int parameterIndex) throws SQLException {
        return realCallableStatement.getObject(parameterIndex);
    }
    @Override public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        return realCallableStatement.getBigDecimal(parameterIndex);
    }
    @Override public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        return realCallableStatement.getObject(parameterIndex, map);
    }
    @Override public Ref getRef(int parameterIndex) throws SQLException {
        return realCallableStatement.getRef(parameterIndex);
    }
    @Override public Blob getBlob(int parameterIndex) throws SQLException {
        return realCallableStatement.getBlob(parameterIndex);
    }
    @Override public Clob getClob(int parameterIndex) throws SQLException {
        return realCallableStatement.getClob(parameterIndex);
    }
    @Override public Array getArray(int parameterIndex) throws SQLException {
        return realCallableStatement.getArray(parameterIndex);
    }
    @Override public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        return realCallableStatement.getDate(parameterIndex, cal);
    }
    @Override public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        return realCallableStatement.getTime(parameterIndex, cal);
    }
    @Override public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        return realCallableStatement.getTimestamp(parameterIndex, cal);
    }
    @Override public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        realCallableStatement.registerOutParameter(parameterIndex, sqlType, typeName);
    }
    @Override public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
        realCallableStatement.registerOutParameter(parameterName, sqlType);
    }
    @Override public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        realCallableStatement.registerOutParameter(parameterName, sqlType, scale);
    }
    @Override public void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        realCallableStatement.registerOutParameter(parameterName, sqlType, typeName);
    }
    @Override public URL getURL(int parameterIndex) throws SQLException {
        return realCallableStatement.getURL(parameterIndex);
    }
    @Override public void setURL(String parameterName, URL val) throws SQLException {
        realCallableStatement.setURL(parameterName, val);
    }
    @Override public void setNull(String parameterName, int sqlType) throws SQLException {
        realCallableStatement.setNull(parameterName, sqlType);
    }
    @Override public void setBoolean(String parameterName, boolean x) throws SQLException {
        realCallableStatement.setBoolean(parameterName, x);
    }
    @Override public void setByte(String parameterName, byte x) throws SQLException {
        realCallableStatement.setByte(parameterName, x);
    }
    @Override public void setShort(String parameterName, short x) throws SQLException {
        realCallableStatement.setShort(parameterName, x);
    }
    @Override public void setInt(String parameterName, int x) throws SQLException {
        realCallableStatement.setInt(parameterName, x);
    }
    @Override public void setLong(String parameterName, long x) throws SQLException {
        realCallableStatement.setLong(parameterName, x);
    }
    @Override public void setFloat(String parameterName, float x) throws SQLException {
        realCallableStatement.setFloat(parameterName, x);
    }
    @Override public void setDouble(String parameterName, double x) throws SQLException {
        realCallableStatement.setDouble(parameterName, x);
    }
    @Override public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        realCallableStatement.setBigDecimal(parameterName, x);
    }
    @Override public void setString(String parameterName, String x) throws SQLException {
        realCallableStatement.setString(parameterName, x);
    }
    @Override public void setBytes(String parameterName, byte[] x) throws SQLException {
        realCallableStatement.setBytes(parameterName, x);
    }
    @Override public void setDate(String parameterName, Date x) throws SQLException {
        realCallableStatement.setDate(parameterName, x);
    }
    @Override public void setTime(String parameterName, Time x) throws SQLException {
        realCallableStatement.setTime(parameterName, x);
    }
    @Override public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
        realCallableStatement.setTimestamp(parameterName, x);
    }
    @Override public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
        realCallableStatement.setAsciiStream(parameterName, x, length);
    }
    @Override public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
        realCallableStatement.setBinaryStream(parameterName, x, length);
    }
    @Override public void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {
        realCallableStatement.setObject(parameterName, x, targetSqlType, scale);
    }
    @Override public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
        realCallableStatement.setObject(parameterName, x, targetSqlType);
    }
    @Override public void setObject(String parameterName, Object x) throws SQLException {
        realCallableStatement.setObject(parameterName, x);
    }
    @Override public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
        realCallableStatement.setCharacterStream(parameterName, reader, length);
    }
    @Override public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
        realCallableStatement.setDate(parameterName, x, cal);
    }
    @Override public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
        realCallableStatement.setTime(parameterName, x, cal);
    }
    @Override public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
        realCallableStatement.setTimestamp(parameterName, x, cal);
    }
    @Override public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        realCallableStatement.setNull(parameterName, sqlType, typeName);
    }
    @Override public String getString(String parameterName) throws SQLException {
        return realCallableStatement.getString(parameterName);
    }
    @Override public boolean getBoolean(String parameterName) throws SQLException {
        return realCallableStatement.getBoolean(parameterName);
    }
    @Override public byte getByte(String parameterName) throws SQLException {
        return realCallableStatement.getByte(parameterName);
    }
    @Override public short getShort(String parameterName) throws SQLException {
        return realCallableStatement.getShort(parameterName);
    }
    @Override public int getInt(String parameterName) throws SQLException {
        return realCallableStatement.getInt(parameterName);
    }
    @Override public long getLong(String parameterName) throws SQLException {
        return realCallableStatement.getLong(parameterName);
    }
    @Override public float getFloat(String parameterName) throws SQLException {
        return realCallableStatement.getFloat(parameterName);
    }
    @Override public double getDouble(String parameterName) throws SQLException {
        return realCallableStatement.getDouble(parameterName);
    }
    @Override public byte[] getBytes(String parameterName) throws SQLException {
        return realCallableStatement.getBytes(parameterName);
    }
    @Override public Date getDate(String parameterName) throws SQLException {
        return realCallableStatement.getDate(parameterName);
    }
    @Override public Time getTime(String parameterName) throws SQLException {
        return realCallableStatement.getTime(parameterName);
    }
    @Override public Timestamp getTimestamp(String parameterName) throws SQLException {
        return realCallableStatement.getTimestamp(parameterName);
    }
    @Override public Object getObject(String parameterName) throws SQLException {
        return realCallableStatement.getObject(parameterName);
    }
    @Override public BigDecimal getBigDecimal(String parameterName) throws SQLException {
        return realCallableStatement.getBigDecimal(parameterName);
    }
    @Override public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
        return realCallableStatement.getObject(parameterName, map);
    }
    @Override public Ref getRef(String parameterName) throws SQLException {
        return realCallableStatement.getRef(parameterName);
    }
    @Override public Blob getBlob(String parameterName) throws SQLException {
        return realCallableStatement.getBlob(parameterName);
    }
    @Override public Clob getClob(String parameterName) throws SQLException {
        return realCallableStatement.getClob(parameterName);
    }
    @Override public Array getArray(String parameterName) throws SQLException {
        return realCallableStatement.getArray(parameterName);
    }
    @Override public Date getDate(String parameterName, Calendar cal) throws SQLException {
        return realCallableStatement.getDate(parameterName, cal);
    }
    @Override public Time getTime(String parameterName, Calendar cal) throws SQLException {
        return realCallableStatement.getTime(parameterName, cal);
    }
    @Override public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
        return realCallableStatement.getTimestamp(parameterName, cal);
    }
    @Override public URL getURL(String parameterName) throws SQLException {
        return realCallableStatement.getURL(parameterName);
    }
    @Override public RowId getRowId(int parameterIndex) throws SQLException {
        return realCallableStatement.getRowId(parameterIndex);
    }
    @Override public RowId getRowId(String parameterName) throws SQLException {
        return realCallableStatement.getRowId(parameterName);
    }
    @Override public void setRowId(String parameterName, RowId x) throws SQLException {
        realCallableStatement.setRowId(parameterName, x);
    }
    @Override public void setNString(String parameterName, String value) throws SQLException {
        realCallableStatement.setNString(parameterName, value);
    }
    @Override public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
        realCallableStatement.setNCharacterStream(parameterName, value, length);
    }
    @Override public void setNClob(String parameterName, NClob value) throws SQLException {
        realCallableStatement.setNClob(parameterName, value);
    }
    @Override public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        realCallableStatement.setNClob(parameterName, reader, length);
    }
    @Override public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        realCallableStatement.setClob(parameterName, reader, length);
    }
    @Override public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        realCallableStatement.setBlob(parameterName, inputStream, length);
    }
    @Override public void setNClob(String parameterName, Reader reader) throws SQLException {
        realCallableStatement.setNClob(parameterName, reader);
    }
    @Override public NClob getNClob(int parameterIndex) throws SQLException {
        return realCallableStatement.getNClob(parameterIndex);
    }
    @Override public NClob getNClob(String parameterName) throws SQLException {
        return realCallableStatement.getNClob(parameterName);
    }
    @Override public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        realCallableStatement.setSQLXML(parameterName, xmlObject);
    }
    @Override public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        return realCallableStatement.getSQLXML(parameterIndex);
    }
    @Override public SQLXML getSQLXML(String parameterName) throws SQLException {
        return realCallableStatement.getSQLXML(parameterName);
    }
    @Override public String getNString(int parameterIndex) throws SQLException {
        return realCallableStatement.getNString(parameterIndex);
    }
    @Override public String getNString(String parameterName) throws SQLException {
        return realCallableStatement.getNString(parameterName);
    }
    @Override public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        return realCallableStatement.getNCharacterStream(parameterIndex);
    }
    @Override public Reader getNCharacterStream(String parameterName) throws SQLException {
        return realCallableStatement.getNCharacterStream(parameterName);
    }
    @Override public Reader getCharacterStream(int parameterIndex) throws SQLException {
        return realCallableStatement.getCharacterStream(parameterIndex);
    }
    @Override public Reader getCharacterStream(String parameterName) throws SQLException {
        return realCallableStatement.getCharacterStream(parameterName);
    }
    @Override public void setBlob(String parameterName, Blob x) throws SQLException {
        realCallableStatement.setBlob(parameterName, x);
    }
    @Override public void setClob(String parameterName, Clob x) throws SQLException {
        realCallableStatement.setClob(parameterName, x);
    }
    @Override public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
        realCallableStatement.setAsciiStream(parameterName, x, length);
    }
    @Override public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
        realCallableStatement.setBinaryStream(parameterName, x, length);
    }
    @Override public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        realCallableStatement.setCharacterStream(parameterName, reader, length);
    }
    @Override public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        realCallableStatement.setAsciiStream(parameterName, x);
    }
    @Override public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        realCallableStatement.setBinaryStream(parameterName, x);
    }
    @Override public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        realCallableStatement.setCharacterStream(parameterName, reader);
    }
    @Override public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        realCallableStatement.setNCharacterStream(parameterName, value);
    }
    @Override public void setClob(String parameterName, Reader reader) throws SQLException {
        realCallableStatement.setClob(parameterName, reader);
    }
    @Override public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        realCallableStatement.setBlob(parameterName, inputStream);
    }
    @Override public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        return realCallableStatement.getObject(parameterIndex, type);
    }
    @Override public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        return realCallableStatement.getObject(parameterName, type);
    }
}
