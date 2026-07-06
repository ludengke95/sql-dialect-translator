package com.translator.metrics;

import io.prometheus.client.exporter.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Prometheus HTTP 指标端点服务器封装。
 *
 * <p>在独立端口启动嵌入式 HTTP Server，暴露 /metrics 供 Prometheus scrape。
 */
public class MetricsHttpServer {

    private static final Logger log = LoggerFactory.getLogger(MetricsHttpServer.class);

    private final int port;
    private HTTPServer server;

    public MetricsHttpServer(int port) {
        this.port = port;
    }

    /**
     * 启动 HTTP 指标端点。
     */
    public synchronized void start() {
        if (server != null) {
            log.warn("Metrics HTTP server already running on port {}", port);
            return;
        }
        try {
            server = new HTTPServer(port);
            log.info("Metrics HTTP server started on port {}", port);
        } catch (IOException e) {
            log.error("Failed to start metrics HTTP server on port {}: {}", port, e.getMessage());
        }
    }

    /**
     * 停止 HTTP 指标端点。
     */
    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            log.info("Metrics HTTP server stopped");
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return server != null;
    }
}
