package com.translator.proxy.server;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.core.config.TranslationConfig;
import com.translator.metrics.MetricsConfig;
import com.translator.proxy.backend.BackendEntry;
import com.translator.proxy.backend.BackendPoolManager;
import com.translator.proxy.protocol.pg.PgResponseWriter;
import com.translator.proxy.core.frontend.FrontendProtocol;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.handler.HandshakeHandler;
import com.translator.proxy.core.handler.NettyMetricsHandler;
import com.translator.proxy.metrics.MetricsModule;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.protocol.pg.PgProtocol;
import com.translator.proxy.protocol.pg.PostgreSqlFrontendProtocol;
import com.translator.proxy.server.config.ConfigLoader;
import com.translator.proxy.server.config.ConfigWatcher;
import com.translator.proxy.server.config.ProxyConfig;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

/**
 * MySQL Proxy 启动引导类（多后端版）。
 *
 * <p>启动时初始化 {@link BackendPoolManager} 管理多个后端连接池，
 * 将 {@link BackendRouter} 注入 {@link CommandHandler} 实现按数据库名路由。
 *
 * <p>启动后通过 {@link ConfigWatcher} 监听配置文件变更，
 * 实现 backends 列表的热更新。
 */
public class ProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProxyBootstrap.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup bizExecutorGroup;
    /**
     * 多后端管理器
     */
    private BackendPoolManager backendPoolManager;

    /**
     * 指标模块
     * 用于收集和暴露 Proxy 运行时指标，如连接数、查询量等。
     */
    private MetricsModule metricsModule;
    /**
     * 配置文件监听器
     */
    private ConfigWatcher configWatcher;

    public ProxyBootstrap(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        // 同步系统变量中的 max_allowed_packet，使客户端驱动在连接建立时能获取到正确的限制
        com.translator.proxy.core.intercept.SystemVariableInterceptor.setSystemVariable(
                "max_allowed_packet", String.valueOf(config.getMaxAllowedPacket()));

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        int bizThreads = 4;
        for (ProxyConfig.TargetConfig tcBackend : config.getBackends()) {
            bizThreads = Math.max(bizThreads, tcBackend.getMaxPoolSize());
        }
        bizExecutorGroup = new DefaultEventExecutorGroup(bizThreads);

        String cachedAuthUser = config.getAuth().getUser();
        String cachedAuthPassword = config.getAuth().getPassword();

        // 构建翻译配置（全局默认）
        ProxyConfig.TranslationConf trc = config.getTranslation();
        TranslationConfig defaultTranslationConfig = new TranslationConfig()
                .withKeywordCase(TranslationConfig.KeywordCase.valueOf(trc.getKeywordCase()))
                .withIdentifierCase(TranslationConfig.IdentifierCase.valueOf(trc.getIdentifierCase()));

        // 将 ProxyConfig.TargetConfig 转换为 BackendEntry 列表
        List<BackendEntry> backends = new ArrayList<>();
        for (ProxyConfig.TargetConfig tc : config.getBackends()) {
            backends.add(ConfigWatcher.toBackendEntry(tc));
        }

        // 解析前端协议（MYSQL 默认；POSTGRESQL 启用 PG 前端管线）
        boolean pgFrontend = "POSTGRESQL".equalsIgnoreCase(config.getFrontendProtocol());
        FrontendProtocol frontendProtocol = pgFrontend ? new PostgreSqlFrontendProtocol() : null;

        // 初始化多后端连接池管理器（传入 reload 参数）
        backendPoolManager = pgFrontend
                ? new BackendPoolManager(
                        backends,
                        defaultTranslationConfig,
                        config.getReloadQueueCapacity(),
                        config.getReloadDrainTimeoutMs(),
                        new PgResponseWriter(),
                        "POSTGRESQL")
                : new BackendPoolManager(
                        backends, defaultTranslationConfig, config.getReloadQueueCapacity(), config.getReloadDrainTimeoutMs());

        // 将路由器注入 CommandHandler
        CommandHandler.setBackendRouter(backendPoolManager);
        // 初始化指标模块
        ProxyConfig.MetricsConf mc = config.getMetrics();
        if (mc.isEnabled()) {
            int metricsPort = mc.getPort();
            if (metricsPort <= 0) {
                metricsPort = config.getPort() + 10000;
            }
            MetricsConfig metricsConfig = new MetricsConfig(mc.isEnabled(), metricsPort);
            metricsModule = new MetricsModule(metricsConfig);
            metricsModule.start();
        }

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("nettyMetrics", new NettyMetricsHandler());
                            pipeline.addLast("idleHandler", new IdleStateHandler(28800, 28800, 0));

                            if (pgFrontend && frontendProtocol != null) {
                                // PostgreSQL 前端管线
                                pipeline.addLast("decoder", frontendProtocol.newDecoder(config.getMaxAllowedPacket()));
                                pipeline.addLast("encoder", frontendProtocol.newEncoder());
                                pipeline.addLast(
                                        "handshakeHandler",
                                        frontendProtocol.newHandshakeHandler(
                                                cachedAuthUser, cachedAuthPassword, bizExecutorGroup, backendPoolManager));
                            } else {
                                // MySQL 前端管线（默认，保持原行为零回归）
                                pipeline.addLast("decoder", new MySQLPacketDecoder(config.getMaxAllowedPacket()));
                                pipeline.addLast("encoder", new MySQLPacketEncoder());
                                pipeline.addLast(
                                        "handshakeHandler",
                                        new HandshakeHandler(cachedAuthUser, cachedAuthPassword, bizExecutorGroup));
                            }
                        }
                    });

            // 端口自适应：PG 前端且未显式设置端口时默认 5432（MySQL 默认 3306）
            int bindPort = config.getPort();
            if (pgFrontend && bindPort == 3306) {
                bindPort = PgProtocol.DEFAULT_PORT;
            }

            ChannelFuture future = bootstrap.bind(bindPort).sync();
            log.info("========================================");
            log.info(
                    "  SDT Proxy ({}) started on port {}",
                    pgFrontend ? "POSTGRESQL frontend" : "MySQL frontend", bindPort);
            log.info(
                    "  Backends: {} configured",
                    backendPoolManager.getBackendNames().size());
            for (String name : backendPoolManager.getBackendNames()) {
                log.info("    - {}", name);
            }
            log.info("  Auth: user={}", config.getAuth().getUser());
            log.info("  Biz threads: {}", bizThreads);
            log.info(
                    "  Reload: queue={}, drainTimeout={}ms, debounce={}ms",
                    config.getReloadQueueCapacity(),
                    config.getReloadDrainTimeoutMs(),
                    config.getReloadDebounceMs());
            log.info("========================================");

            // 启动配置文件监听器
            startConfigWatcher();

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 启动配置文件监听器。
     */
    private void startConfigWatcher() {
        String configPath = ConfigLoader.resolveConfigPath();
        if (configPath == null) {
            log.info("No file-based config to watch (using classpath or defaults), config watcher disabled");
            return;
        }

        configWatcher = new ConfigWatcher(configPath, config.getReloadDebounceMs(), backendPoolManager, config);
        configWatcher.start();
    }

    public void shutdown() {
        // 先停止 watcher，避免 reload 干扰 shutdown
        if (metricsModule != null) {
            metricsModule.stop();
        }
        if (configWatcher != null) {
            configWatcher.stop();
        }
        if (backendPoolManager != null) {
            backendPoolManager.close();
        }
        if (bizExecutorGroup != null) {
            bizExecutorGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("SDT Proxy stopped.");
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ConfigLoader.load();
        ProxyBootstrap bootstrap = new ProxyBootstrap(config);
        bootstrap.start();
    }
}
