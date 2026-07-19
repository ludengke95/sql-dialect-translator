package com.translator.proxy.protocol.pg.command;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.proxy.core.handler.SessionAttribute;
import com.translator.proxy.core.handler.QueryProcessor;
import com.translator.proxy.core.session.FrontendSession;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import com.translator.proxy.protocol.pg.catalog.PgSystemCatalogProvider;
import com.translator.proxy.protocol.pg.codec.PgMessage;
import com.translator.proxy.protocol.pg.codec.PgRawMessage;
import com.translator.proxy.protocol.pg.codec.PgWire;
import com.translator.proxy.protocol.pg.result.PgResponseWriter;

/**
 * PostgreSQL 命令分发器 —— 处理 Simple Query 和 Extended Query。
 *
 * <p>支持的消息类型：
 * <ul>
 *   <li>Simple Query ('Q')</li>
 *   <li>Parse ('P') + Bind ('B') + Describe ('D') + Execute ('E') + Sync ('S')</li>
 *   <li>Close ('C')</li>
 *   <li>Flush ('H')</li>
 *   <li>Terminate ('X')</li>
 * </ul>
 */
public class PgCommandHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PgCommandHandler.class);

    private static volatile QueryProcessor queryProcessor = QueryProcessor.NOOP;

    private final PgResponseWriter responseWriter = new PgResponseWriter();
    private final PgSystemCatalogProvider systemCatalog = new PgSystemCatalogProvider();

    /** 已准备的语句缓存（statement name → sql） */
    private final Map<String, String> preparedStatements = new ConcurrentHashMap<String, String>();

    /** 已绑定的门户缓存（portal name → sql） */
    private final Map<String, String> portals = new ConcurrentHashMap<String, String>();

    /**
     * 设置全局查询处理器。
     */
    public static void setQueryProcessor(QueryProcessor processor) {
        queryProcessor = processor != null ? processor : QueryProcessor.NOOP;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof PgRawMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }

        PgRawMessage raw = (PgRawMessage) msg;
        try {
            byte type = raw.getType();
            switch (type) {
                case 'Q':
                    handleSimpleQuery(ctx, raw);
                    break;
                case 'P':
                    handleParse(ctx, raw);
                    break;
                case 'B':
                    handleBind(ctx, raw);
                    break;
                case 'D':
                    handleDescribe(ctx, raw);
                    break;
                case 'E':
                    handleExecute(ctx, raw);
                    break;
                case 'S':
                    handleSync(ctx, raw);
                    break;
                case 'C':
                    handleClose(ctx, raw);
                    break;
                case 'H':
                    handleFlush(ctx, raw);
                    break;
                case 'X':
                    handleTerminate(ctx, raw);
                    break;
                default:
                    log.debug("Unknown PG message type: {}", (char) type);
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling PG command", e);
            responseWriter.sendError(ctx, "ERROR", "58000", e.getMessage());
            responseWriter.sendReadyForQuery(ctx, PgWire.TXN_IDLE);
        } finally {
            raw.release();
        }
    }

    // ==================== Simple Query ====================

    private void handleSimpleQuery(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        String sql = payload.toString(StandardCharsets.UTF_8);
        log.debug("PG Query: {}", sql);

        FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();

        if (sql == null || sql.trim().isEmpty()) {
            responseWriter.sendEmptyQuery(ctx);
            responseWriter.sendReadyForQuery(ctx, PgWire.TXN_IDLE);
            return;
        }

        // 系统目录查询
        if (systemCatalog.canHandle(sql)) {
            systemCatalog.handleQuery(ctx, sql, session);
            return;
        }

        // 转发到后端
        queryProcessor.process(ctx, sql, session);
    }

    // ==================== Extended Query ====================

    private void handleParse(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        String stmtName = readCstr(payload);
        String query = readCstr(payload);

        // 读取参数类型 OID 列表
        if (payload.readableBytes() >= 2) {
            int paramCount = payload.readShort();
            for (int i = 0; i < paramCount; i++) {
                payload.readInt(); // 参数 OID
            }
        }

        log.debug("Parse: {} → '{}'", stmtName, query);
        if (!stmtName.isEmpty()) {
            preparedStatements.put(stmtName, query);
        }

        // ParseComplete
        ByteBuf response = ctx.alloc().buffer(1);
        response.writeByte('1');
        ctx.write(new PgMessage((byte) '1', response));
    }

    private void handleBind(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        String portalName = readCstr(payload);
        String stmtName = readCstr(payload);

        log.debug("Bind: portal={}, stmt={}", portalName, stmtName);

        String sql = preparedStatements.get(stmtName);
        if (sql == null) {
            sql = stmtName;
        }

        if (!portalName.isEmpty()) {
            portals.put(portalName, sql);
        }

        // BindComplete
        ByteBuf response = ctx.alloc().buffer(1);
        response.writeByte('2');
        ctx.write(new PgMessage((byte) '2', response));
    }

    private void handleDescribe(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        byte describeType = payload.readByte();
        String name = readCstr(payload);

        log.debug("Describe: type={}, name={}", (char) describeType, name);

        // NoData（简化处理）
        ByteBuf response = ctx.alloc().buffer(1);
        response.writeByte('n');
        ctx.write(new PgMessage((byte) 'n', response));
    }

    private void handleExecute(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        String portalName = readCstr(payload);
        int maxRows = payload.readInt();

        log.debug("Execute: portal={}, maxRows={}", portalName, maxRows);

        String sql = portals.get(portalName);
        if (sql == null && !portalName.isEmpty()) {
            sql = portalName;
        }

        if (sql != null) {
            FrontendSession session = ctx.channel().attr(SessionAttribute.SESSION_KEY).get();

            if (systemCatalog.canHandle(sql)) {
                systemCatalog.handleQuery(ctx, sql, session);
            } else {
                queryProcessor.process(ctx, sql, session);
            }
        } else {
            // EmptyQuery
            ByteBuf response = ctx.alloc().buffer(1);
            response.writeByte('I');
            ctx.write(new PgMessage(PgWire.MSG_EMPTY_QUERY_RESPONSE, response));
        }
    }

    private void handleSync(ChannelHandlerContext ctx, PgRawMessage raw) {
        log.debug("Sync");
        responseWriter.sendReadyForQuery(ctx, PgWire.TXN_IDLE);
    }

    private void handleClose(ChannelHandlerContext ctx, PgRawMessage raw) {
        ByteBuf payload = raw.getPayload();
        byte closeType = payload.readByte();
        String name = readCstr(payload);

        log.debug("Close: type={}, name={}", (char) closeType, name);

        if (closeType == 'S') {
            preparedStatements.remove(name);
        } else if (closeType == 'P') {
            portals.remove(name);
        }

        // CloseComplete
        ByteBuf response = ctx.alloc().buffer(1);
        response.writeByte('3');
        ctx.write(new PgMessage((byte) '3', response));
    }

    private void handleFlush(ChannelHandlerContext ctx, PgRawMessage raw) {
        log.debug("Flush");
        ctx.flush();
    }

    private void handleTerminate(ChannelHandlerContext ctx, PgRawMessage raw) {
        log.debug("Terminate");
        ctx.close();
    }

    // ==================== 辅助方法 ====================

    private static String readCstr(ByteBuf buf) {
        int len = buf.bytesBefore((byte) 0x00);
        if (len < 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        buf.skipBytes(1);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 内部绑定的查询对。
     */
    static class BoundQuery {
        final String sql;
        final String portalName;

        BoundQuery(String sql, String portalName) {
            this.sql = sql;
            this.portalName = portalName;
        }
    }
}
