package com.translator.core.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PARROT 官方数据集原始行结构。
 *
 * <p>官方 PARROT 数据集采用"多方言列"格式：每行代表同一 SQL 查询，
 * 各字段存放该 SQL 在不同数据库方言下的版本。当前仅映射本项目 DialectType
 * 支持的四种方言（mysql / postgres / oracle / tsql），其余列通过
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParrotNativeRow {

    /** 题目唯一标识符 */
    @JsonProperty("id")
    private String id;

    /** 标准化/规范化 SQL（方言无关） */
    @JsonProperty("norm")
    private String norm;

    /** MySQL 方言 SQL */
    @JsonProperty("mysql")
    private String mysql;

    /** PostgreSQL 方言 SQL */
    @JsonProperty("postgres")
    private String postgres;

    /** Oracle 方言 SQL */
    @JsonProperty("oracle")
    private String oracle;

    /** SQL Server / T-SQL 方言 SQL */
    @JsonProperty("tsql")
    private String tsql;

    public ParrotNativeRow() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNorm() {
        return norm;
    }

    public void setNorm(String norm) {
        this.norm = norm;
    }

    public String getMysql() {
        return mysql;
    }

    public void setMysql(String mysql) {
        this.mysql = mysql;
    }

    public String getPostgres() {
        return postgres;
    }

    public void setPostgres(String postgres) {
        this.postgres = postgres;
    }

    public String getOracle() {
        return oracle;
    }

    public void setOracle(String oracle) {
        this.oracle = oracle;
    }

    public String getTsql() {
        return tsql;
    }

    public void setTsql(String tsql) {
        this.tsql = tsql;
    }

    @Override
    public String toString() {
        return "ParrotNativeRow{id='" + id + "'}";
    }
}
