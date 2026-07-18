package com.translator.proxy.protocol.pg;

import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 前端消息的 POJO 集合（同包可见，供解码器与命令分发器使用）。
 */
final class PgMessages {
    private PgMessages() {}
}

/** SSL 协商请求（在 StartupMessage 之前，proto=80877103 的预连接探测） */
class PgSslRequest {}

/** 启动消息（连接建立后客户端首条消息，无类型字节） */
class PgStartupMessage {
    private final Map<String, String> params;

    PgStartupMessage(Map<String, String> params) {
        this.params = params;
    }

    public String getParam(String key) {
        return params.get(key);
    }

    public String getUser() {
        return params.get("user");
    }

    public String getDatabase() {
        return params.get("database");
    }

    public String getApplicationName() {
        return params.get("application_name");
    }

    public Map<String, String> getParams() {
        return params;
    }
}

/** 简单查询（Q） */
class PgQueryMessage {
    private final String sql;

    PgQueryMessage(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }
}

/** 口令消息（p）：明文或 md5 token */
class PgPasswordMessage {
    private final String token;

    PgPasswordMessage(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}

/** 终止连接（X） */
class PgTerminateMessage {}

/** 解析（P）：命名语句 + 查询 + 参数类型 */
class PgParseMessage {
    private final String name;
    private final String query;
    private final List<Integer> paramTypes;

    PgParseMessage(String name, String query, List<Integer> paramTypes) {
        this.name = name;
        this.query = query;
        this.paramTypes = paramTypes;
    }

    public String getName() {
        return name;
    }

    public String getQuery() {
        return query;
    }

    public List<Integer> getParamTypes() {
        return paramTypes;
    }
}

/** 绑定（B）：门户 + 语句 + 参数值 */
class PgBindMessage {
    private final String portal;
    private final String statement;
    private final List<String> paramValues;
    private final List<Integer> resultFormatCodes;

    PgBindMessage(String portal, String statement, List<String> paramValues, List<Integer> resultFormatCodes) {
        this.portal = portal;
        this.statement = statement;
        this.paramValues = paramValues;
        this.resultFormatCodes = resultFormatCodes;
    }

    public String getPortal() {
        return portal;
    }

    public String getStatement() {
        return statement;
    }

    public List<String> getParamValues() {
        return paramValues;
    }

    public List<Integer> getResultFormatCodes() {
        return resultFormatCodes;
    }
}

/** 执行（E） */
class PgExecuteMessage {
    private final String portal;
    private final int maxRows;

    PgExecuteMessage(String portal, int maxRows) {
        this.portal = portal;
        this.maxRows = maxRows;
    }

    public String getPortal() {
        return portal;
    }

    public int getMaxRows() {
        return maxRows;
    }
}

/** 描述（D） */
class PgDescribeMessage {
    private final byte target;
    private final String name;

    PgDescribeMessage(byte target, String name) {
        this.target = target;
        this.name = name;
    }

    /** 'S' = 语句，'P' = 门户 */
    public byte getTarget() {
        return target;
    }

    public String getName() {
        return name;
    }
}

/** 同步（S） */
class PgSyncMessage {}

/** 刷新（H） */
class PgFlushMessage {}

/** 关闭（C） */
class PgCloseMessage {
    private final byte target;
    private final String name;

    PgCloseMessage(byte target, String name) {
        this.target = target;
        this.name = name;
    }

    public byte getTarget() {
        return target;
    }

    public String getName() {
        return name;
    }
}
