# SDT Proxy Docker Image
# 基于 OpenJDK 8 运行，暴露 3306 端口
FROM openjdk:8-jre-slim

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

WORKDIR /app

# 复制构建产物（所有 jar 都在 lib/ 下）
COPY sdtp-server/target/lib/ lib/

# 默认配置文件（可通过 volume 挂载覆盖）
COPY sdtp-server/src/main/assembly/config/proxy-config.yml /app/config/proxy-config.yml

# 日志目录
RUN mkdir -p /app/logs

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Dproxy.config=/app/config/proxy-config.yml -Dlog.dir=/app/logs"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp 'lib/*' com.translator.proxy.server.ProxyBootstrap"]
