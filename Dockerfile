# MySQL Dialect Proxy Docker Image
# 基于 OpenJDK 8 运行，暴露 3306 端口
FROM openjdk:8-jre-slim

LABEL maintainer="sql-dialect-translator"
LABEL description="MySQL Protocol Proxy — Single target DB per instance"

WORKDIR /app

# 复制构建产物
COPY proxy-server/target/proxy-server-*.jar proxy-server.jar
COPY proxy-server/target/lib/ lib/

# 默认配置文件（可通过 volume 挂载覆盖）
COPY proxy-server/src/main/resources/proxy-config.yml /app/config/proxy-config.yml

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -Dproxy.config=/app/config/proxy-config.yml"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp proxy-server.jar:lib/* com.translator.proxy.server.ProxyBootstrap"]
