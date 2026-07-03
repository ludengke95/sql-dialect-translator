package com.translator.proxy.backend;

import com.translator.proxy.backend.mapper.ResultSetEncoder;
import com.translator.proxy.core.handler.CommandHandler;
import com.translator.proxy.core.session.FrontendSession;
import com.translator.proxy.protocol.codec.MySQLPacketEncoder;
import com.translator.proxy.core.handler.AuthHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * 基于 JDBC 的后端查询处理器。
 *
 * <p>实现 CommandHandler.QueryProcessor 接口，管理 HikariCP 连接池，
 * 在独立线程中执行 SQL 并通过 ResultSetEncoder 将结果流式回传。
 *
 * <p>线程安全：连接池本身是线程安全的，每个查询从池中获取连接后独立执行。
 */
public class JdbcBackendQueryProcessor implements CommandHandler.QueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(JdbcBackendQueryProcessor.class);

    private final HikariDataSource dataSource;

    private JdbcBackendQueryProcessor(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 工厂方法：根据配置创建处理器。
     */
    public static JdbcBackendQueryProcessor create(String jdbcUrl, String username,
                                                    String password, int maxPoolSize,
                                                    int minIdle) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(10000);    // 10s
        hikariConfig.setIdleTimeout(600000);          // 10min
        hikariConfig.setMaxLifetime(1800000);         // 30min

        // 关键：设置事务为只读 + 自动提交（配合流式读取）
        hikariConfig.setReadOnly(true);
        hikariConfig.setAutoCommit(true);

        HikariDataSource ds = new HikariDataSource(hikariConfig);
        log.info("HikariCP pool created: jdbcUrl={}, maxPoolSize={}", jdbcUrl, maxPoolSize);

        return new JdbcBackendQueryProcessor(ds);
    }

    @Override
    public void process(ChannelHandlerContext ctx, String sql, FrontendSession session) {
        // JDBC 操作在 CommandHandler 所在的业务线程执行
        // （CommandHandler 已通过 DefaultEventExecutorGroup 与 IO 线程解耦）
        try (Connection conn = dataSource.getConnection();
             Statement stmt = createStatement(conn)) {

            log.debug("Executing SQL: {}", sql);

            boolean isResultSet = stmt.execute(sql);

            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetEncoder.encodeAndWrite(ctx, rs, new ResultSetEncoder.SeqGenerator());
                }
            } else {
                // UPDATE/INSERT/DELETE 等
                int updateCount = stmt.getUpdateCount();
                ResultSetEncoder.encodeEmpty(ctx, Math.max(updateCount, 0), 0);
            }

        } catch (SQLException e) {
            log.error("SQL execution failed: {}", sql, e);
            writeError(ctx, e);
        } catch (Exception e) {
            log.error("Unexpected error executing SQL: {}", sql, e);
            writeError(ctx, 1105, "HY000", e.getMessage());
        }
    }

    /**
     * 创建 Statement 并配置流式读取。
     */
    private Statement createStatement(Connection conn) throws SQLException {
        Statement stmt = conn.createStatement();
        // 设置 fetchSize 实现流式读取（JDBC 标准方式）
        // PostgreSQL 需要 setAutoCommit(false) 才生效，但参数化查询不受影响。
        // 对于大结果集，驱动内部会用游标分批获取。
        stmt.setFetchSize(1000);
        return stmt;
    }

    // ==================== 错误处理 ====================

    private void writeError(ChannelHandlerContext ctx, SQLException e) {
        int errorCode = e.getErrorCode();
        String sqlState = e.getSQLState();
        String message = e.getMessage();
        writeError(ctx, errorCode != 0 ? errorCode : 1105,
                sqlState != null ? sqlState : "HY000",
                message != null ? message : "Unknown SQL error");
    }

    private void writeError(ChannelHandlerContext ctx, int errorCode,
                             String sqlState, String message) {
        ByteBuf err = AuthHandler.buildErrPacket(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(new MySQLPacketEncoder.OutgoingPacket(err, (byte) 1));
    }

    /**
     * 关闭连接池。
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("HikariCP pool closed.");
        }
    }
}
