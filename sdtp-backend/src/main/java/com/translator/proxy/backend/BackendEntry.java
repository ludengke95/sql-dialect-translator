package com.translator.proxy.backend;

/**
 * 后端数据库实例配置条目。
 *
 * <p>由 {@code ProxyBootstrap} 从 {@code ProxyConfig.TargetConfig} 转换而来，
 * 传入 {@link BackendPoolManager} 初始化连接池。
 */
public class BackendEntry {

    private String name;
    private String dialect;
    private String jdbcUrl;
    private String username;
    private String password;
    private int maxPoolSize = 20;
    private int minIdle = 2;

    public BackendEntry() {}

    public BackendEntry(String name, String dialect, String jdbcUrl,
                         String username, String password,
                         int maxPoolSize, int minIdle) {
        this.name = name;
        this.dialect = dialect;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maxPoolSize = maxPoolSize;
        this.minIdle = minIdle;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }

    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
}
