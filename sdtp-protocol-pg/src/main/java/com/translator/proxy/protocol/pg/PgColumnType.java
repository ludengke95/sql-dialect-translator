package com.translator.proxy.protocol.pg;

/**
 * PostgreSQL 列类型元数据（OID + 名称 + 长度 + 修饰符）。
 *
 * <p>用于把 JDBC {@code ResultSetMetaData} 的列类型映射为 PG 线协议所需的
 * RowDescription 字段。
 */
public class PgColumnType {

    /** 类型 OID（如 int4=23） */
    public final int oid;
    /** 类型名称（如 int4） */
    public final String name;
    /** 类型长度（-1 表示变长，如 text/varchar） */
    public final int typeLen;
    /** 类型修饰符（-1 表示无） */
    public final int typeMod;

    public PgColumnType(int oid, String name, int typeLen, int typeMod) {
        this.oid = oid;
        this.name = name;
        this.typeLen = typeLen;
        this.typeMod = typeMod;
    }

    @Override
    public String toString() {
        return "PgColumnType{oid=" + oid + ", name='" + name + "', len=" + typeLen + ", mod=" + typeMod + '}';
    }
}
