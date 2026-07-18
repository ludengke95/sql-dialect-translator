package com.translator.proxy.protocol.pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.translator.proxy.core.frontend.FrontendProtocol;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;

import org.junit.Test;

/**
 * {@link PostgreSqlFrontendProtocol} SPI 契约测试。
 */
public class PostgreSqlFrontendProtocolTest {

    @Test
    public void spiContract() {
        PostgreSqlFrontendProtocol p = new PostgreSqlFrontendProtocol();

        assertEquals("POSTGRESQL", p.id());
        assertEquals("POSTGRESQL", p.getSourceDialect());
        assertEquals(5432, p.defaultPort());

        assertNotNull(p.newDecoder(1024));
        assertTrue(p.newDecoder(1024) instanceof PgMessageDecoder);

        assertNotNull(p.newEncoder());
        assertTrue(p.newEncoder() instanceof PgMessageEncoder);

        BackendRouter router = session -> CommandHandler.QueryProcessor.NOOP;
        assertNotNull(p.newHandshakeHandler("u", "p", null, router));
        assertTrue(p.newHandshakeHandler("u", "p", null, router) instanceof PgHandshaker);
    }
}
