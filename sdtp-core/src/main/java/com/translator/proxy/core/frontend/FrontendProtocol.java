package com.translator.proxy.core.frontend;

import com.translator.proxy.core.handler.BackendRouter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * 前端协议抽象（SPI）。
 *
 * <p>把「代理伪装成哪种服务端协议」从硬编码抽离为可插拔抽象。
 * 现有 MySQL 前端保持原样（{@code ProxyBootstrap} 在 {@code frontendProtocol=MYSQL} 时直接走旧 pipeline），
 * 新增的 PostgreSQL 前端通过实现本接口接入，零重复复用后端执行层与翻译引擎。
 *
 * <p>约定：编解码与响应构造不直接依赖 MySQL 的 {@code OutgoingPacket}，
 * 出站统一使用裸 {@link ByteBuf}，避免接口耦合 MySQL 类型。
 * 本接口只依赖 sdtp-core 自身与 netty（由 sdtp-protocol 传递引入），
 * 因此返回类型尽量用通用接口（{@link ChannelHandler}、{@link MessageToByteEncoder}），
 * 不引用 sdt-core 的 {@code DialectType}（源方言以字符串 id 表达，由 sdtp-backend 转换为枚举）。
 */
public interface FrontendProtocol {

    /** 协议标识，对应 {@code ProxyConfig.frontendProtocol}（MYSQL / POSTGRESQL） */
    String id();

    /**
     * 源方言 id——翻译层用它决定 SQL 解析口径。
     * 取值与 {@code DialectType} 的 name 对齐：PG 前端返回 {@code "POSTGRESQL"}，MySQL 前端返回 {@code "MYSQL"}。
     * 由 sdtp-backend 在构造 {@code TranslationQueryProcessor} 时转换为枚举。
     */
    String getSourceDialect();

    /** 构造入站解码器（包帧）。入参为该协议单消息字节上限。 */
    ChannelHandler newDecoder(long maxMessageSize);

    /** 构造出站编码器（包帧）。 */
    MessageToByteEncoder<?> newEncoder();

    /**
     * 握手/欢迎处理器（连接建立后的第一个 handler）。
     *
     * @param user     配置的认证用户名
     * @param password 配置的认证密码
     * @param biz      业务线程池（认证/后端调用应落入此线程组）
     * @param router   后端路由器（用于解析当前会话对应的查询处理器）
     */
    ChannelHandler newHandshakeHandler(String user, String password, EventExecutorGroup biz, BackendRouter router);

    /** 该协议默认的监听端口（PG=5432，MySQL=3306） */
    int defaultPort();
}
