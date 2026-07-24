# SDT Proxy Docker Image — 多阶段构建
# Stage 1: 编译阶段（使用 Maven 3.9 + JDK 8 官方镜像）
FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /workspace

# 复制项目所有文件
COPY . .

# 全量编译打包（maven-assembly-plugin 已配置 dir 格式，直接在 target 目录生成完整应用文件夹）
RUN mvn clean package -DskipTests -B -q

# -------------------------------------------------------------------
# Stage 2: 运行阶段（仅 JRE 8 镜像）
FROM eclipse-temurin:8-jre

LABEL maintainer="sql-dialect-translator"
LABEL description="SDT Proxy — MySQL protocol proxy, single target DB per instance"

# 从编译阶段直接复制 Maven 生成的应用目录（完全无需运行 tar/unzip 命令解压）
COPY --from=builder /workspace/sdtp-server/target/sdtp-server-1.0.0-SNAPSHOT /opt/sdtp

WORKDIR /opt/sdtp

# 日志目录
RUN mkdir -p logs

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xms512m -XX:+UseG1GC"

ENTRYPOINT ["./bin/start.sh", "start-fg"]
