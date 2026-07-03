package com.translator.proxy.server.config;

/**
 * Proxy 配置模型 —— 单实例单目标库架构，启动时一次性加载。
 */
public class ProxyConfig {

    private int port = 3306;

    private AuthConfig auth = new AuthConfig();
    private TargetConfig target = new TargetConfig();
    private TranslationConf translation = new TranslationConf();

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public TargetConfig getTarget() {
        return target;
    }

    public void setTarget(TargetConfig target) {
        this.target = target;
    }

    public TranslationConf getTranslation() {
        return translation;
    }

    public void setTranslation(TranslationConf translation) {
        this.translation = translation;
    }

    // ==================== 内嵌配置类 ====================

    public static class AuthConfig {
        private String user = "root";
        private String password = "proxy_password";

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class TargetConfig {
        /**
         * 目标库方言类型，如 MYSQL, POSTGRESQL, ORACLE 等。
         * 对应 translator-core 中的 DialectType。
         */
        private String dialect = "POSTGRESQL";

        /** 目标库 JDBC URL */
        private String jdbcUrl = "jdbc:postgresql://localhost:5432/mydb";

        /** 目标库用户名 */
        private String username = "pg_user";

        /** 目标库密码 */
        private String password = "pg_password";

        /** 连接池最大连接数 */
        private int maxPoolSize = 20;

        /** 连接池最小空闲连接数 */
        private int minIdle = 2;

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
     * SQL 翻译配置 —— 控制翻译后 SQL 中关键词和标识符的大小写。
     *
     * <p>不同目标数据库推荐：
     * <ul>
     *   <li>PostgreSQL / HighGo / KingbaseES：keywordCase=UPPER, identifierCase=LOWER ✅ 默认</li>
     *   <li>Oracle / DM（达梦）：keywordCase=UPPER, identifierCase=UPPER</li>
     *   <li>MySQL：keywordCase=UPPER, identifierCase=UNCHANGED</li>
     *   <li>SQL Server：keywordCase=UPPER, identifierCase=UNCHANGED</li>
     * </ul>
     */
    public static class TranslationConf {
        /** 关键词大小写：UPPER（默认）或 LOWER */
        private String keywordCase = "UPPER";

        /** 标识符大小写：LOWER（默认）、UPPER 或 UNCHANGED */
        private String identifierCase = "LOWER";

        public String getKeywordCase() { return keywordCase; }
        public void setKeywordCase(String keywordCase) { this.keywordCase = keywordCase; }
        public String getIdentifierCase() { return identifierCase; }
        public void setIdentifierCase(String identifierCase) { this.identifierCase = identifierCase; }
    }
}
