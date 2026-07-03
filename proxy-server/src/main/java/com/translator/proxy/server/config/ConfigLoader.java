package com.translator.proxy.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.BeanAccess;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

/**
 * YAML 配置文件加载器。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>系统属性 -Dproxy.config=/path/to/config.yml</li>
 *   <li>classpath 下的 proxy-config.yml</li>
 *   <li>当前目录下的 proxy-config.yml</li>
 * </ol>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    /**
     * 加载配置。
     */
    public static ProxyConfig load() {
        String configPath = System.getProperty("proxy.config");

        if (configPath != null) {
            return loadFromFile(configPath);
        }

        // 尝试 classpath
        InputStream classpathStream = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("proxy-config.yml");
        if (classpathStream != null) {
            log.info("Loading config from classpath:proxy-config.yml");
            return loadFromStream(classpathStream);
        }

        // 尝试当前目录
        try {
            return loadFromFile("proxy-config.yml");
        } catch (Exception e) {
            log.info("No proxy-config.yml found, using defaults");
            return new ProxyConfig();
        }
    }

    private static ProxyConfig loadFromFile(String path) {
        log.info("Loading config from file: {}", path);
        try (FileInputStream fis = new FileInputStream(path)) {
            return loadFromStream(fis);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Config file not found: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ProxyConfig loadFromStream(InputStream stream) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(stream);

        ProxyConfig config = new ProxyConfig();

        // proxy 段
        Map<String, Object> proxy = (Map<String, Object>) root.get("proxy");
        if (proxy != null) {
            if (proxy.get("port") != null) {
                config.setPort(((Number) proxy.get("port")).intValue());
            }
            Map<String, Object> authMap = (Map<String, Object>) proxy.get("auth");
            if (authMap != null) {
                ProxyConfig.AuthConfig auth = config.getAuth();
                if (authMap.get("user") != null) {
                    auth.setUser((String) authMap.get("user"));
                }
                if (authMap.get("password") != null) {
                    auth.setPassword((String) authMap.get("password"));
                }
            }
        }

        // target 段
        Map<String, Object> target = (Map<String, Object>) root.get("target");
        if (target != null) {
            ProxyConfig.TargetConfig tc = config.getTarget();
            if (target.get("dialect") != null) {
                tc.setDialect((String) target.get("dialect"));
            }
            if (target.get("jdbc-url") != null) {
                tc.setJdbcUrl((String) target.get("jdbc-url"));
            }
            if (target.get("username") != null) {
                tc.setUsername((String) target.get("username"));
            }
            if (target.get("password") != null) {
                tc.setPassword((String) target.get("password"));
            }
            if (target.get("max-pool-size") != null) {
                tc.setMaxPoolSize(((Number) target.get("max-pool-size")).intValue());
            }
            if (target.get("min-idle") != null) {
                tc.setMinIdle(((Number) target.get("min-idle")).intValue());
            }
        }

        return config;
    }
}
