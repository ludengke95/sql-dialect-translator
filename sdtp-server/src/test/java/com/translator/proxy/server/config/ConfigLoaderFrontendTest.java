package com.translator.proxy.server.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * TDD 回归测试：验证 {@link ConfigLoader} 能正确读取前端协议配置键
 * （{@code frontend} 或别名 {@code frontend-protocol}），映射到
 * {@link ProxyConfig#getFrontendProtocol()}。
 *
 * <p>该映射此前缺失：{@code ProxyConfig.frontendProtocol} 字段存在、
 * {@code ProxyBootstrap} 也读取它，但 {@code ConfigLoader} 从未从 YAML 载入，
 * 导致配置 {@code frontend: POSTGRESQL} 静默失效、代理始终以默认 MYSQL 前端启动。
 */
public class ConfigLoaderFrontendTest {

    private ProxyConfig loadWith(String proxyBody) throws Exception {
        File f = File.createTempFile("sdtp-cfg", ".yml");
        f.deleteOnExit();
        String yaml = "proxy:\n"
                + proxyBody
                + "\nbackends:\n"
                + "  - name: tpch\n"
                + "    dialect: MYSQL\n"
                + "    jdbc-url: jdbc:mysql://localhost:3306/tpch\n";
        Files.write(f.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return ConfigLoader.loadFromFileOrNull(f.getAbsolutePath());
    }

    @Test
    public void frontendKeyEnablesPostgreSql() throws Exception {
        ProxyConfig c = loadWith("  port: 5432\n  frontend: POSTGRESQL\n");
        assertEquals("POSTGRESQL", c.getFrontendProtocol());
    }

    @Test
    public void frontendProtocolAliasAlsoWorks() throws Exception {
        ProxyConfig c = loadWith("  port: 5432\n  frontend-protocol: POSTGRESQL\n");
        assertEquals("POSTGRESQL", c.getFrontendProtocol());
    }

    @Test
    public void defaultFrontendIsMysql() throws Exception {
        ProxyConfig c = loadWith("  port: 3306\n");
        assertEquals("MYSQL", c.getFrontendProtocol());
    }

    @Test
    public void mysqlFrontendStaysMysql() throws Exception {
        ProxyConfig c = loadWith("  port: 3306\n  frontend: MYSQL\n");
        assertEquals("MYSQL", c.getFrontendProtocol());
    }
}
