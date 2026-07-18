package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.Test;

/**
 * PgMessageDecoder 单元测试：启动消息、简单查询、扩展查询、口令、终止等消息的帧解析。
 */
public class PgPacketCodecTest {

    private static ByteBuf startupMessage(String... kv) {
        // kv: key1,val1,key2,val2,...
        ByteBuf body = Unpooled.buffer();
        for (int i = 0; i < kv.length; i += 2) {
            body.writeBytes(kv[i].getBytes(PgMessageDecoder.ASCII));
            body.writeByte(0);
            body.writeBytes(kv[i + 1].getBytes(PgMessageDecoder.ASCII));
            body.writeByte(0);
        }
        body.writeByte(0); // terminating null
        int len = 4 + 4 + body.readableBytes();
        ByteBuf out = Unpooled.buffer();
        out.writeInt(len);
        out.writeInt(PgProtocol.PROTOCOL_VERSION_3);
        out.writeBytes(body);
        return out;
    }

    private static ByteBuf regularMessage(byte type, ByteBuf body) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(type);
        out.writeInt(4 + body.readableBytes());
        out.writeBytes(body);
        return out;
    }

    private static ByteBuf cstring(String s) {
        ByteBuf b = Unpooled.buffer();
        b.writeBytes(s.getBytes(PgMessageDecoder.ASCII));
        b.writeByte(0);
        return b;
    }

    @Test
    public void decodesStartupMessageWithUserAndDatabase() {
        ByteBuf buf = startupMessage("user", "postgres", "database", "testdb", "application_name", "psql");
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        assertTrue(ch.writeInbound(buf));
        Object msg = ch.readInbound();
        assertNotNull(msg);
        assertTrue(msg instanceof PgStartupMessage);
        PgStartupMessage sm = (PgStartupMessage) msg;
        assertEquals("postgres", sm.getUser());
        assertEquals("testdb", sm.getDatabase());
        assertEquals("psql", sm.getApplicationName());
    }

    @Test
    public void decodesSimpleQuery() {
        ByteBuf body = cstring("SELECT 1");
        ByteBuf buf = regularMessage(PgProtocol.MSG_QUERY, body);
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        assertTrue(ch.writeInbound(buf));
        Object msg = ch.readInbound();
        assertTrue(msg instanceof PgQueryMessage);
        assertEquals("SELECT 1", ((PgQueryMessage) msg).getSql());
    }

    @Test
    public void decodesTwoFramedQueriesSeparately() {
        ByteBuf b1 = regularMessage(PgProtocol.MSG_QUERY, cstring("SELECT 1"));
        ByteBuf b2 = regularMessage(PgProtocol.MSG_QUERY, cstring("SELECT 2"));
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        assertTrue(ch.writeInbound(b1));
        assertTrue(ch.writeInbound(b2));
        PgQueryMessage q1 = (PgQueryMessage) ch.readInbound();
        PgQueryMessage q2 = (PgQueryMessage) ch.readInbound();
        assertEquals("SELECT 1", q1.getSql());
        assertEquals("SELECT 2", q2.getSql());
    }

    @Test
    public void decodesPasswordMessage() {
        ByteBuf body = cstring("md5abcdef1234567890");
        ByteBuf buf = regularMessage(PgProtocol.MSG_PASSWORD_MESSAGE, body);
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        assertTrue(ch.writeInbound(buf));
        Object msg = ch.readInbound();
        assertTrue(msg instanceof PgPasswordMessage);
        assertEquals("md5abcdef1234567890", ((PgPasswordMessage) msg).getToken());
    }

    @Test
    public void decodesTerminate() {
        ByteBuf buf = regularMessage(PgProtocol.MSG_TERMINATE, Unpooled.EMPTY_BUFFER);
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        assertTrue(ch.writeInbound(buf));
        assertTrue(ch.readInbound() instanceof PgTerminateMessage);
    }

    @Test
    public void decodesExtendedQueryParseBindExecuteSync() {
        // Parse: name, query, paramCount=0
        ByteBuf parseBody = Unpooled.buffer();
        parseBody.writeBytes(cstring("stmt1"));
        parseBody.writeBytes(cstring("SELECT $1"));
        parseBody.writeShort(0); // param type count
        EmbeddedChannel ch = new EmbeddedChannel(new PgMessageDecoder());
        ch.writeInbound(regularMessage(PgProtocol.MSG_PARSE, parseBody));
        PgParseMessage parse = (PgParseMessage) ch.readInbound();
        assertEquals("stmt1", parse.getName());
        assertEquals("SELECT $1", parse.getQuery());

        // Bind: portal, statement, paramFmtCount=0, paramCount=1, "42", resultFmtCount=0
        ByteBuf bindBody = Unpooled.buffer();
        bindBody.writeBytes(cstring("")); // portal
        bindBody.writeBytes(cstring("stmt1")); // statement
        bindBody.writeShort(0); // param format codes
        bindBody.writeShort(1); // param count
        bindBody.writeInt(2); // param len
        bindBody.writeBytes("42".getBytes(PgMessageDecoder.ASCII));
        bindBody.writeShort(0); // result format codes
        ch.writeInbound(regularMessage(PgProtocol.MSG_BIND, bindBody));
        PgBindMessage bind = (PgBindMessage) ch.readInbound();
        assertEquals("stmt1", bind.getStatement());
        List<String> params = bind.getParamValues();
        assertEquals(1, params.size());
        assertEquals("42", params.get(0));

        // Execute: portal, maxRows=0
        ByteBuf execBody = Unpooled.buffer();
        execBody.writeBytes(cstring(""));
        execBody.writeInt(0);
        ch.writeInbound(regularMessage(PgProtocol.MSG_EXECUTE, execBody));
        PgExecuteMessage exec = (PgExecuteMessage) ch.readInbound();
        assertEquals(0, exec.getMaxRows());

        // Sync
        ch.writeInbound(regularMessage(PgProtocol.MSG_SYNC, Unpooled.EMPTY_BUFFER));
        assertTrue(ch.readInbound() instanceof PgSyncMessage);
    }
}
