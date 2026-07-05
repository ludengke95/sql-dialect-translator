package com.translator.proxy.server.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Proxy 配置模型 —— 支持多后端数据库实例。
 *
 * <p>客户端通过 JDBC URL 中的 database 名称或 &#64;64;USE db_name 选择后端。
 * 启动时加载全部后端连接池，按 {@link TargetConfig#getName()} 索引。
 */
public class ProxyConfig {

    private int port = 3306;

    private AuthConfig auth = new AuthConfig();
    private List<TargetConfig> backends = new ArrayList<>();
    private TranslationConf translation = new TranslationConf();

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    public List<TargetConfig> getBackends() { return backends; }
    public void setBackends(List<TargetConfig> backends) { this.backends = backends; }

    public TranslationConf getTranslation() { return translation; }
    public void setTranslation(TranslationConf translation) { this.translation = translation; }

    // ==================== 内嵌配置类 ====================

    public static class AuthConfig {
        private String user = "root";
        private String password = "proxy_password";

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * 单个后端数据库实例配置。
     * name 字段对应客户端连接时指定的数据库名称。
     */
    public static class TargetConfig {
        /** 后端名称，客户端通过 USE {@literal <name>} 或 JDBC URL database 连接 */
        private String name;

        /** 目标库方言类型，如 MYSQL, POSTGRESQL, ORACLE 等 */
        private String dialect = "POSTGRESQL";

        /** 目标库 JDBC URL */
        private String jdbcUrl = "jdbc:postgresql://localhost:5432/mydb";

        /** 目标库用户名 */
        private String username = "sdtpu";

        /** 目标库密码 */
        private String password = "pg_password";

        /** 连接池最大连接数 */
        private int maxPoolSize = 20;

        /** 连接池最小空闲连接数 */
        private int minIdle = 2;

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

    /**
     * SQL 翻译配置。
     */
    public static class TranslationConf {
        private String keywordCase = "UPPER";
        private String identifierCase = "LOWER";

        public String getKeywordCase() { return keywordCase; }
        public void setKeywordCase(String keywordCase) { this.keywordCase = keywordCase; }
        public String getIdentifierCase() { return identifierCase; }
        public void setIdentifierCase(String identifierCase) { this.identifierCase = identifierCase; }
    }
}
