package com.translator.proxy.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * 连接层指标：活跃连接、认证成功/失败、连接存活时长。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code auth_plugin} — 认证插件（native / sha256）</li>
 *   <li>{@code reason} — 认证失败原因（wrong_user / wrong_password / unsupported_plugin）</li>
 * </ul>
 */
public final class ConnectionMetrics {

    private ConnectionMetrics() {}

    /** 当前活跃连接数 */
    public static final Gauge ACTIVE = Gauge.build()
            .name("sdt_connections_active")
            .help("Current number of active client connections")
            .register();

    /** 启动以来连接总数 */
    public static final Counter TOTAL = Counter.build()
            .name("sdt_connections_total")
            .help("Total number of connections since startup")
            .register();

    /** 认证成功次数（按 auth_plugin 分 label） */
    public static final Counter AUTH_SUCCESS = Counter.build()
            .name("sdt_auth_success_total")
            .help("Total successful authentications")
            .labelNames("auth_plugin")
            .register();

    /** 认证失败次数（按 reason 分 label） */
    public static final Counter AUTH_FAILURE = Counter.build()
            .name("sdt_auth_failure_total")
            .help("Total failed authentications")
            .labelNames("reason")
            .register();

    // ==================== 便捷方法 ====================

    /** 连接建立时调用 */
    public static void onConnect() {
        ACTIVE.inc();
        TOTAL.inc();
    }

    /** 连接关闭时调用 */
    public static void onDisconnect() {
        ACTIVE.dec();
    }

    /** 认证成功时调用 */
    public static void onAuthSuccess(String plugin) {
        AUTH_SUCCESS.labels(plugin).inc();
    }

    /** 认证失败时调用 */
    public static void onAuthFailure(String reason) {
        AUTH_FAILURE.labels(reason).inc();
    }
}
