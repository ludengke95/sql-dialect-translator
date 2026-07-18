package com.translator.proxy.protocol.pg;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * PostgreSQL 前端命令分发器（认证完成后处理客户端消息）。
 *
 * <p>支持：
 * <ul>
 *   <li>简单查询 {@code 'Q'}：系统目录探测由 {@link PgSystemCatalogProvider} 直接应答；
 *       事务控制（BEGIN/COMMIT/ROLLBACK/SET）本地处理；其余 SQL 经
 *       {@link BackendRouter} 转发到后端处理器（{@code process(ctx, sql, params, session)}）。</li>
 *   <li>扩展查询 {@code P/B/D/E/S/H/C}：Parse/Bind/Describe/Execute/Sync/Flush/Close。</li>
 *   <li>终止 {@code 'X'}：关闭连接。</li>
 * </ul>
 *
 * <p>每个简单查询结束、或扩展查询的 Sync 阶段，统一发送 ReadyForQuery('Z')。
 * 结果集/错误响应的编码由 {@link PgResponseWriter}（经后端处理器）完成。
 */
public class PgCommandDispatcher extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PgCommandDispatcher.class);

    private static final Pattern BEGIN_PATTERN =
            Pattern.compile("^\\s*(?:BEGIN|START\\s+TRANSACTION)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^\\s*COMMIT\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("^\\s*ROLLBACK\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_PATTERN = Pattern.compile("^\\s*SET\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_QUERY = Pattern.compile("^[\\s;]*$");

    private final BackendRouter router;

    /** 预备语句名 → 查询（扩展查询协议） */
    private final Map<String, String> preparedQueries = new HashMap<>();
    /** 门户名 → 绑定的语句名与参数值 */
    private final Map<String, BoundPortal> portals = new HashMap<>();

    public PgCommandDispatcher(BackendRouter router) {
        this.router = router;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof PgTerminateMessage) {
                ctx.close();
            } else if (msg instanceof PgQueryMessage) {
                handleSimpleQuery(ctx, ((PgQueryMessage) msg).getSql());
            } else if (msg instanceof PgParseMessage) {
                handleParse(ctx, (PgParseMessage) msg);
            } else if (msg instanceof PgBindMessage) {
                handleBind(ctx, (PgBindMessage) msg);
            } else if (msg instanceof PgDescribeMessage) {
                handleDescribe(ctx, (PgDescribeMessage) msg);
            } else if (msg instanceof PgExecuteMessage) {
                handleExecute(ctx, (PgExecuteMessage) msg);
            } else if (msg instanceof PgSyncMessage) {
                writeReadyForQuery(ctx);
            } else if (msg instanceof PgFlushMessage) {
                ctx.flush();
            } else if (msg instanceof PgCloseMessage) {
                ctx.write(PgOutbound.framed(buildCloseComplete(ctx.alloc())));
            } else {
                log.debug("PG dispatcher ignoring message: {}", msg.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Error in PG command dispatch", e);
            ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "HY000", "Internal error: " + e.getMessage())));
            writeReadyForQuery(ctx);
        }
    }

    // ==================== 简单查询 ====================

    private void handleSimpleQuery(ChannelHandlerContext ctx, String sql) {
        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        if (sql == null || EMPTY_QUERY.matcher(sql).matches()) {
            ctx.write(PgOutbound.framed(buildEmptyQueryResponse(ctx.alloc())));
            writeReadyForQuery(ctx);
            return;
        }

        // 1. 系统目录/内置函数 → 直接合成应答
        PgSyntheticResult catalog = PgSystemCatalogProvider.answer(sql);
        if (catalog != null) {
            writeSyntheticResult(ctx, catalog);
            writeReadyForQuery(ctx);
            return;
        }

        String trimmed = sql.trim();
        // 2. 事务控制（本地处理，不转发后端）
        if (BEGIN_PATTERN.matcher(trimmed).matches()) {
            session.setInTransaction(true);
            ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), "BEGIN")));
            writeReadyForQuery(ctx);
            return;
        }
        if (COMMIT_PATTERN.matcher(trimmed).matches()) {
            try {
                session.setInTransaction(false);
                router.resolve(session).commit(ctx, session);
            } catch (Exception e) {
                log.error("PG commit failed", e);
                ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "HY000", "Commit failed: " + e.getMessage())));
                writeReadyForQuery(ctx);
                return;
            }
            ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), "COMMIT")));
            writeReadyForQuery(ctx);
            return;
        }
        if (ROLLBACK_PATTERN.matcher(trimmed).matches()) {
            try {
                session.setInTransaction(false);
                router.resolve(session).rollback(ctx, session);
            } catch (Exception e) {
                log.error("PG rollback failed", e);
                ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "HY000", "Rollback failed: " + e.getMessage())));
                writeReadyForQuery(ctx);
                return;
            }
            ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), "ROLLBACK")));
            writeReadyForQuery(ctx);
            return;
        }
        // 3. SET 语句本地应答（避免透传到后端，如 search_path / timezone 等 PG 特有参数）
        //    注意：用 lookingAt 做前缀匹配（SET 后可跟任意参数），不能用 matches（要求整串匹配）
        if (SET_PATTERN.matcher(trimmed).lookingAt()) {
            ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), "SET")));
            writeReadyForQuery(ctx);
            return;
        }

        // 4. 普通 SQL → 经后端执行
        String verb = firstVerb(trimmed);
        ctx.channel().attr(PgProtocol.PG_COMMAND_TAG).set(tagForVerb(verb, trimmed));
        CommandHandler.QueryProcessor processor = router.resolve(session);
        try {
            processor.process(ctx, sql, Collections.<String>emptyList(), session);
        } catch (Exception e) {
            log.error("PG backend execution failed", e);
            ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "HY000", e.getMessage())));
        }
        writeReadyForQuery(ctx);
    }

    // ==================== 扩展查询 ====================

    private void handleParse(ChannelHandlerContext ctx, PgParseMessage parse) {
        preparedQueries.put(parse.getName() != null ? parse.getName() : "", parse.getQuery());
        ctx.write(PgOutbound.framed(buildParseComplete(ctx.alloc())));
    }

    private void handleBind(ChannelHandlerContext ctx, PgBindMessage bind) {
        portals.put(bind.getPortal() != null ? bind.getPortal() : "", new BoundPortal(bind.getStatement(), bind.getParamValues()));
        ctx.write(PgOutbound.framed(buildBindComplete(ctx.alloc())));
    }

    private void handleDescribe(ChannelHandlerContext ctx, PgDescribeMessage describe) {
        if (describe.getTarget() == 'S') {
            // 预备语句描述：参数描述 + NoData（结果列未知，发送 NoData）
            ctx.write(PgOutbound.framed(buildParameterDescription(ctx.alloc(), 0)));
            ctx.write(PgOutbound.framed(buildNoData(ctx.alloc())));
        } else {
            // 门户描述：NoData
            ctx.write(PgOutbound.framed(buildNoData(ctx.alloc())));
        }
    }

    private void handleExecute(ChannelHandlerContext ctx, PgExecuteMessage execute) {
        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        BoundPortal portal = portals.get(execute.getPortal() != null ? execute.getPortal() : "");
        String statementName = portal != null ? portal.statement : "";
        String query = preparedQueries.get(statementName != null ? statementName : "");

        if (query == null) {
            ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "26000", "Invalid statement name: " + statementName)));
            return;
        }

        PgSyntheticResult catalog = PgSystemCatalogProvider.answer(query);
        if (catalog != null) {
            writeSyntheticResult(ctx, catalog);
            return; // Sync 阶段发送 ReadyForQuery
        }

        String verb = firstVerb(query.trim());
        ctx.channel().attr(PgProtocol.PG_COMMAND_TAG).set(tagForVerb(verb, query.trim()));
        List<String> params = portal != null ? portal.params : Collections.<String>emptyList();
        try {
            router.resolve(session).process(ctx, query, params, session);
        } catch (Exception e) {
            log.error("PG extended execution failed", e);
            ctx.write(PgOutbound.framed(buildErrorResponse(ctx.alloc(), "HY000", e.getMessage())));
        }
        // 注意：扩展查询的 ReadyForQuery 由随后的 Sync 消息发送
    }

    // ==================== 消息构造 ====================

    private void writeSyntheticResult(ChannelHandlerContext ctx, PgSyntheticResult result) {
        ctx.write(PgOutbound.framed(buildRowDescription(ctx.alloc(), result.getColumns())));
        for (List<String> row : result.getRows()) {
            ctx.write(PgOutbound.framed(buildDataRow(ctx.alloc(), row)));
        }
        ctx.write(PgOutbound.framed(buildCommandComplete(ctx.alloc(), "SELECT")));
    }

    private void writeReadyForQuery(ChannelHandlerContext ctx) {
        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();
        byte tx = (session != null && (session.isInTransaction() || !session.isAutoCommit()))
                ? PgProtocol.TX_IN_TRANSACTION
                : PgProtocol.TX_IDLE;
        ctx.writeAndFlush(PgOutbound.framed(buildReadyForQuery(ctx.alloc(), tx)));
    }

    private static ByteBuf buildRowDescription(ByteBufAllocator alloc, List<String> columns) {
        ByteBuf b = alloc.buffer(64 + columns.size() * 32);
        b.writeByte(PgProtocol.MSG_ROW_DESCRIPTION);
        b.writeShort(columns.size());
        for (String col : columns) {
            writeCString(b, col);
            b.writeInt(0); // tableOID
            b.writeShort(0); // columnAttrNumber
            b.writeInt(PgTypeMapper.OID_TEXT); // typeOID
            b.writeShort((short) -1); // typeLen
            b.writeInt(-1); // typeMod
            b.writeShort(0); // formatCode = 0 (text)
        }
        return b;
    }

    private static ByteBuf buildDataRow(ByteBufAllocator alloc, List<String> row) {
        ByteBuf b = alloc.buffer(64 + row.size() * 32);
        b.writeByte(PgProtocol.MSG_DATA_ROW);
        b.writeShort(row.size());
        for (String value : row) {
            if (value == null) {
                b.writeInt(-1);
            } else {
                byte[] bytes = value.getBytes(UTF_8);
                b.writeInt(bytes.length);
                b.writeBytes(bytes);
            }
        }
        return b;
    }

    private static ByteBuf buildCommandComplete(ByteBufAllocator alloc, String tag) {
        ByteBuf b = alloc.buffer(32);
        b.writeByte(PgProtocol.MSG_COMMAND_COMPLETE);
        writeCString(b, tag);
        return b;
    }

    private static ByteBuf buildReadyForQuery(ByteBufAllocator alloc, byte txStatus) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_READY_FOR_QUERY);
        b.writeByte(txStatus);
        return b;
    }

    private static ByteBuf buildEmptyQueryResponse(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_EMPTY_QUERY_RESPONSE);
        return b;
    }

    private static ByteBuf buildParseComplete(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_PARSE_COMPLETE);
        return b;
    }

    private static ByteBuf buildBindComplete(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_BIND_COMPLETE);
        return b;
    }

    private static ByteBuf buildCloseComplete(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_CLOSE_COMPLETE);
        return b;
    }

    private static ByteBuf buildNoData(ByteBufAllocator alloc) {
        ByteBuf b = alloc.buffer(5);
        b.writeByte(PgProtocol.MSG_NO_DATA);
        return b;
    }

    private static ByteBuf buildParameterDescription(ByteBufAllocator alloc, int paramCount) {
        ByteBuf b = alloc.buffer(6 + paramCount * 4);
        b.writeByte(PgProtocol.MSG_PARAMETER_DESCRIPTION); // 't'
        b.writeShort(paramCount);
        for (int i = 0; i < paramCount; i++) {
            b.writeInt(0); // oid
        }
        return b;
    }

    private static ByteBuf buildErrorResponse(ByteBufAllocator alloc, String sqlState, String message) {
        ByteBuf b = alloc.buffer(64 + (message != null ? message.length() : 0));
        b.writeByte(PgProtocol.MSG_ERROR_RESPONSE);
        writeField(b, 'S', "ERROR");
        writeField(b, 'V', "ERROR");
        writeField(b, 'C', sqlState != null && sqlState.length() == 5 ? sqlState : "HY000");
        writeField(b, 'M', message != null ? message : "Unknown error");
        b.writeByte(0);
        return b;
    }

    // ==================== 辅助 ====================

    private static String firstVerb(String trimmed) {
        int i = 0;
        int n = trimmed.length();
        while (i < n && Character.isLetter(trimmed.charAt(i))) {
            i++;
        }
        return i == 0 ? "" : trimmed.substring(0, i).toUpperCase();
    }

    private static String tagForVerb(String verb, String trimmed) {
        if (verb.equals("SELECT") || verb.equals("SHOW") || verb.equals("WITH") || verb.equals("EXPLAIN")
                || verb.equals("VALUES") || verb.equals("TABLE")) {
            return "SELECT";
        }
        if (verb.equals("INSERT") || verb.equals("UPDATE") || verb.equals("DELETE")) {
            return verb;
        }
        // DDL：拼出 "VERB <OBJECT>" 风格标签（如 "CREATE TABLE"），客户端一般忽略
        if (verb.equals("CREATE") || verb.equals("ALTER") || verb.equals("DROP") || verb.equals("TRUNCATE")) {
            int space = trimmed.indexOf(' ', verb.length());
            if (space > 0) {
                int space2 = trimmed.indexOf(' ', space + 1);
                String obj = space2 > 0 ? trimmed.substring(verb.length(), space2) : trimmed.substring(verb.length());
                return (verb + obj).replaceAll("\\s+", " ").trim().toUpperCase();
            }
        }
        return verb;
    }

    private static final java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;

    private static void writeCString(ByteBuf b, String s) {
        if (s == null) {
            s = "";
        }
        byte[] bytes = s.getBytes(UTF_8);
        b.writeBytes(bytes);
        b.writeByte(0);
    }

    private static void writeField(ByteBuf b, char fieldType, String value) {
        b.writeByte((byte) fieldType);
        writeCString(b, value);
    }

    /** 扩展查询门户绑定 */
    private static final class BoundPortal {
        final String statement;
        final List<String> params;

        BoundPortal(String statement, List<String> params) {
            this.statement = statement;
            this.params = params;
        }
    }
}
