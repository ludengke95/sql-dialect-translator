package com.translator.metrics;

/**
 * Prometheus 指标暴露配置。
 */
public class MetricsConfig {

    /** 是否启用指标暴露 */
    private boolean enabled = true;

    /** HTTP 指标暴露端口，默认 9090 */
    private int port = 9090;

    public MetricsConfig() {}

    public MetricsConfig(boolean enabled, int port) {
        this.enabled = enabled;
        this.port = port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String toString() {
        return "MetricsConfig{enabled=" + enabled + ", port=" + port + "}";
    }
}
