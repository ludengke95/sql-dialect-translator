package com.translator.proxy.core.handler;

import com.translator.proxy.core.session.FrontendSession;
import io.netty.util.AttributeKey;

/**
 * Netty Channel 属性键常量。
 */
public final class SessionAttribute {

    private SessionAttribute() {}

    /** FrontendSession 在 Channel 上的 AttributeKey */
    public static final AttributeKey<FrontendSession> SESSION_KEY =
            AttributeKey.valueOf("frontendSession");
}
