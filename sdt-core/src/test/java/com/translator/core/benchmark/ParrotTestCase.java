package com.translator.core.benchmark;

/**
 * PARROT Benchmark 测试用例实体类
 */
public class ParrotTestCase {
    private String id;
    private String sourceDialect;
    private String targetDialect;
    private String sourceSql;
    private String expectedSql;
    private String category;

    public ParrotTestCase() {
    }

    public ParrotTestCase(String id, String sourceDialect, String targetDialect, String sourceSql, String expectedSql, String category) {
        this.id = id;
        this.sourceDialect = sourceDialect;
        this.targetDialect = targetDialect;
        this.sourceSql = sourceSql;
        this.expectedSql = expectedSql;
        this.category = category;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceDialect() {
        return sourceDialect;
    }

    public void setSourceDialect(String sourceDialect) {
        this.sourceDialect = sourceDialect;
    }

    public String getTargetDialect() {
        return targetDialect;
    }

    public void setTargetDialect(String targetDialect) {
        this.targetDialect = targetDialect;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
    }

    public String getExpectedSql() {
        return expectedSql;
    }

    public void setExpectedSql(String expectedSql) {
        this.expectedSql = expectedSql;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Override
    public String toString() {
        return "ParrotTestCase{" +
                "id='" + id + '\'' +
                ", sourceDialect='" + sourceDialect + '\'' +
                ", targetDialect='" + targetDialect + '\'' +
                ", category='" + category + '\'' +
                '}';
    }
}
