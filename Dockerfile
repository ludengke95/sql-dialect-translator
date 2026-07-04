# SDT Proxy Docker Image
# 使用 Maven 分发包构建（与发布版本一致的结构）
FROM eclipse-temurin:8-jre

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

# 复制并解压分发包（assembly appendAssemblyId=false，无 -dist 后缀）
COPY sdtp-server/target/sdtp-server-*.tar.gz /tmp/
RUN tar xzf /tmp/sdtp-server-*.tar.gz -C /opt/ \
    && rm /tmp/sdtp-server-*.tar.gz \
    && mv /opt/sdtp-server-* /opt/sdtp

WORKDIR /opt/sdtp

# 日志目录
RUN mkdir -p logs

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["./bin/start.sh", "start-fg"]
