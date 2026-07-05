package com.translator.proxy.server;

import com.translator.core.config.TranslationConfig;
import com.translator.proxy.backend.BackendEntry;
import com.translator.proxy.backend.BackendPoolManager;
import com.translator.proxy.core.handler.BackendRouter;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.handler.HandshakeHandler;
import com.translator.proxy.protocol.codec.MySQLPacketDecoder;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.server.config.ConfigLoader;
import com.translator.proxy.server.config.ProxyConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL Proxy 启动引导类（多后端版）。
 *
 * <p>启动时初始化 {@link BackendPoolManager} 管理多个后端连接池，
 * 将 {@link BackendRouter} 注入 {@link CommandHandler} 实现按数据库名路由。
 */
public class ProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProxyBootstrap.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup bizExecutorGroup;
    /** 多后端管理器 */
    private BackendPoolManager backendPoolManager;

    public ProxyBootstrap(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
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
            BackendEntry be = new BackendEntry(
                    tc.getName(), tc.getDialect(), tc.getJdbcUrl(),
                    tc.getUsername(), tc.getPassword(),
                    tc.getMaxPoolSize(), tc.getMinIdle());
            // 如果后端自带翻译配置，覆盖全局默认
            if (tc.getTranslation() != null) {
                be.setKeywordCase(tc.getTranslation().getKeywordCase());
                be.setIdentifierCase(tc.getTranslation().getIdentifierCase());
            }
            backends.add(be);
        }

        // 初始化多后端连接池管理器
        BackendPoolManager bpm = new BackendPoolManager(backends, defaultTranslationConfig);
        backendPoolManager = bpm;

        // 将路由器注入 CommandHandler
        CommandHandler.setBackendRouter(bpm);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();

                            pipeline.addLast("decoder", new MySQLPacketDecoder());
                            pipeline.addLast("encoder", new MySQLPacketEncoder());

                            pipeline.addLast("idleHandler",
                                    new IdleStateHandler(28800, 28800, 0));

                            pipeline.addLast("handshakeHandler",
                                    new HandshakeHandler(cachedAuthUser, cachedAuthPassword));
                        }
                    });

            ChannelFuture future = bootstrap.bind(config.getPort()).sync();
            log.info("========================================");
            log.info("  MySQL Proxy started on port {}", config.getPort());
            log.info("  Backends: {} configured", backendPoolManager.getBackendNames().size());
            for (String name : backendPoolManager.getBackendNames()) {
                log.info("    - {}", name);
            }
            log.info("  Auth: user={}", config.getAuth().getUser());
            log.info("  Biz threads: {}", bizThreads);
            log.info("========================================");

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
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
        log.info("MySQL Proxy stopped.");
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ConfigLoader.load();
        ProxyBootstrap bootstrap = new ProxyBootstrap(config);
        bootstrap.start();
    }
}
