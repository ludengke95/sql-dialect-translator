# SDT Proxy Docker Image — 多阶段构建
# Stage 1: 编译阶段（使用 Maven 3.9 + JDK 8 官方镜像）
FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /workspace

# 复制项目所有文件
COPY . .

# 编译并打包 sdtp-server 模块及其依赖 (-pl sdtp-server -am)
RUN mvn clean package -pl sdtp-server -am -DskipTests -B -q

# -------------------------------------------------------------------
# Stage 2: 运行阶段（仅 JRE 8 镜像）
FROM eclipse-temurin:8-jre

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

# 从编译阶段复制分发包并解压
COPY --from=builder /workspace/sdtp-server/target/sdtp-server-*.tar.gz /tmp/
RUN tar xzf /tmp/sdtp-server-*.tar.gz -C /opt/ \
    && rm /tmp/sdtp-server-*.tar.gz \
    && mv /opt/sdtp-server-* /opt/sdtp

WORKDIR /opt/sdtp

# 日志目录
RUN mkdir -p logs

EXPOSE 7788

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["./bin/start.sh", "start-fg"]
