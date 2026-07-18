package com.translator.proxy.protocol.pg;

import io.netty.util.AttributeKey;

/**
 * PostgreSQL 前端协议常量。
 *
 * <p>参照 PostgreSQL v3 前端/后端协议消息类型与关键值。
 */
public final class PgProtocol {

    private PgProtocol() {}

    /** 协议版本号 3.0（0x00030000），StartupMessage 中使用 */
    public static final int PROTOCOL_VERSION_3 = 0x00030000;

    /** SSL 协商请求 magic（0x04D2162F），出现在 StartupMessage 之前的预连接探测 */
    public static final int SSL_REQUEST_CODE = 80877103;

    // ==================== 前端 → 后端 消息类型 ====================
    public static final byte MSG_QUERY = 'Q'; // 简单查询
    public static final byte MSG_PARSE = 'P'; // 解析（扩展查询）
    public static final byte MSG_BIND = 'B'; // 绑定（扩展查询）
    public static final byte MSG_EXECUTE = 'E'; // 执行（扩展查询）
    public static final byte MSG_DESCRIBE = 'D'; // 描述
    public static final byte MSG_SYNC = 'S'; // 同步
    public static final byte MSG_TERMINATE = 'X'; // 终止连接
    public static final byte MSG_PASSWORD_MESSAGE = 'p'; // 口令（明文或 md5）
    public static final byte MSG_CLOSE = 'C'; // 关闭语句/门户
    public static final byte MSG_FLUSH = 'H'; // 刷新
    public static final byte MSG_FUNCTION_CALL = 'F'; // 函数调用（少用）
    public static final byte MSG_COPY_DATA = 'd';
    public static final byte MSG_COPY_DONE = 'c';
    public static final byte MSG_COPY_FAIL = 'f';

    // ==================== 后端 → 前端 消息类型 ====================
    public static final byte MSG_AUTH_REQUEST = 'R'; // 认证请求
    public static final byte MSG_PARAMETER_STATUS = 'S'; // 参数状态
    public static final byte MSG_BACKEND_KEY_DATA = 'K'; // 后端密钥数据
    public static final byte MSG_READY_FOR_QUERY = 'Z'; // 就绪
    public static final byte MSG_ROW_DESCRIPTION = 'T'; // 行描述
    public static final byte MSG_DATA_ROW = 'D'; // 数据行
    public static final byte MSG_COMMAND_COMPLETE = 'C'; // 命令完成
    public static final byte MSG_ERROR_RESPONSE = 'E'; // 错误
    public static final byte MSG_NOTICE_RESPONSE = 'N'; // 通知
    public static final byte MSG_EMPTY_QUERY_RESPONSE = 'I'; // 空查询
    public static final byte MSG_PARSE_COMPLETE = '1';
    public static final byte MSG_BIND_COMPLETE = '2';
    public static final byte MSG_CLOSE_COMPLETE = '3';
    public static final byte MSG_NO_DATA = 'n';
    public static final byte MSG_PARAMETER_DESCRIPTION = 't';
    public static final byte MSG_PORTAL_SUSPENDED = 's';

    // ==================== 认证请求子类型 ====================
    public static final int AUTH_OK = 0;
    public static final int AUTH_CLEAR_TEXT = 3;
    public static final int AUTH_MD5 = 5;

    // ==================== ReadyForQuery 的事务状态 ====================
    public static final byte TX_IDLE = 'I'; // 空闲（不在事务中）
    public static final byte TX_IN_TRANSACTION = 'T'; // 在事务中
    public static final byte TX_FAILED = 'E'; // 事务失败

    /** 默认监听端口 */
    public static final int DEFAULT_PORT = 5432;

    /** 当前 SQL 命令类型（INSERT/UPDATE/DELETE/SELECT），用于构造 CommandComplete 标签 */
    public static final AttributeKey<String> PG_COMMAND_TAG = AttributeKey.valueOf("pgCommandTag");
}
