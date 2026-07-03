package com.translator.proxy.server;

import com.translator.proxy.backend.JdbcBackendQueryProcessor;
import com.translator.proxy.backend.TranslationQueryProcessor;
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

/**
 * MySQL Proxy 启动引导类。
 *
 * <p>Netty Pipeline 架构：
 * <pre>
 *   IO Thread (EventLoop):
 *     [MySQLPacketDecoder → MySQLPacketEncoder]
 *
 *   IO Thread (EventLoop) — 握手阶段:
 *     [HandshakeHandler → AuthHandler]
 *
 *   Biz Thread (DefaultEventExecutorGroup) — 命令阶段:
 *     [CommandHandler]
 *     (JDBC 阻塞调用在 CommandHandler 内部进一步委托到独立线程池)
 * </pre>
 */
public class ProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ProxyBootstrap.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private DefaultEventExecutorGroup bizExecutorGroup;
    /** 后端查询处理器 */
    private JdbcBackendQueryProcessor backendProcessor;
    /** 内嵌账密 snapshots（在 start() 时设置，供 ChannelInitializer 闭包使用） */
    private String cachedAuthUser;
    private String cachedAuthPassword;

    public ProxyBootstrap(ProxyConfig config) {
        this.config = config;
    }

    /**
     * 启动 Proxy 服务。
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        // 业务线程池：用于运行 CommandHandler，避免阻塞 IO 线程
        int bizThreads = Math.max(config.getTarget().getMaxPoolSize(), 4);
        bizExecutorGroup = new DefaultEventExecutorGroup(bizThreads);

        // 快照配置
        cachedAuthUser = config.getAuth().getUser();
        cachedAuthPassword = config.getAuth().getPassword();

        // 初始化后端查询处理器（HikariCP 连接池）
        ProxyConfig.TargetConfig tc = config.getTarget();
        JdbcBackendQueryProcessor jdbcProcessor = JdbcBackendQueryProcessor.create(
                tc.getJdbcUrl(), tc.getUsername(), tc.getPassword(),
                tc.getMaxPoolSize(), tc.getMinIdle());
        backendProcessor = jdbcProcessor;

        // 包装翻译装饰器（当源 MySQL ≠ 目标方言时生效）
        CommandHandler.QueryProcessor processor = new TranslationQueryProcessor(
                jdbcProcessor, tc.getDialect());
        CommandHandler.setQueryProcessor(processor);

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

                            // IO 层：协议编解码器
                            pipeline.addLast("decoder", new MySQLPacketDecoder());
                            pipeline.addLast("encoder", new MySQLPacketEncoder());

                            // 连接空闲超时（8 小时无读写则断开，与 MySQL 默认 wait_timeout 一致）
                            pipeline.addLast("idleHandler",
                                    new IdleStateHandler(28800, 28800, 0));

                            // IO 层：握手处理器（使用配置的账密）
                            pipeline.addLast("handshakeHandler",
                                    new HandshakeHandler(cachedAuthUser, cachedAuthPassword));
                        }
                    });

            ChannelFuture future = bootstrap.bind(config.getPort()).sync();
            log.info("========================================");
            log.info("  MySQL Proxy started on port {}", config.getPort());
            log.info("  Target: {} ({})", config.getTarget().getJdbcUrl(), config.getTarget().getDialect());
            log.info("  Auth: user={}", config.getAuth().getUser());
            log.info("  Biz threads: {}", bizThreads);
            log.info("========================================");

            future.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 关闭 Proxy 服务。
     */
    public void shutdown() {
        if (backendProcessor != null) {
            backendProcessor.close();
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

    // ==================== main 入口 ====================

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ConfigLoader.load();
        ProxyBootstrap bootstrap = new ProxyBootstrap(config);
        bootstrap.start();
    }
}
