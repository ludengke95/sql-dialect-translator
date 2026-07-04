# SDT Proxy Docker Image
# 基于 Eclipse Temurin JRE 8 运行，暴露 3306 端口
FROM eclipse-temurin:8-jre

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

WORKDIR /app

# 复制构建产物（所有 jar 都在 lib/ 下，JDBC 驱动在 jdbc/ 下）
COPY sdtp-server/target/lib/ lib/
COPY sdtp-server/target/jdbc/ jdbc/

# 启动脚本
COPY sdtp-server/src/main/assembly/bin/start.sh /app/bin/start.sh
RUN chmod +x /app/bin/start.sh

# 默认配置文件（可通过 volume 挂载覆盖）
COPY sdtp-server/src/main/assembly/config/proxy-config.yml /app/config/proxy-config.yml

# 日志目录
RUN mkdir -p /app/logs

# 环境变量
ENV PROXY_CONFIG=/app/config/proxy-config.yml
ENV LOG_DIR=/app/logs

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["/app/bin/start.sh", "start-fg"]
