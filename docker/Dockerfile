# SDT Proxy Docker Image — 多阶段构建
# Stage 1: 编译阶段（使用 Maven + JDK 8）
FROM eclipse-temurin:8-jdk AS builder

WORKDIR /workspace

# 先复制 Maven Wrapper 及 pom 文件，利用 Docker 层缓存加速依赖下载
COPY .mvn/ .mvn/
COPY mvnw mvnw
RUN chmod +x mvnw

COPY pom.xml pom.xml
COPY sdt-core/pom.xml       sdt-core/pom.xml
COPY sdt-jdbc/pom.xml       sdt-jdbc/pom.xml
COPY sdt-metrics/pom.xml    sdt-metrics/pom.xml
COPY sdtp-protocol/pom.xml  sdtp-protocol/pom.xml
COPY sdtp-protocol-mysql/pom.xml sdtp-protocol-mysql/pom.xml
COPY sdtp-protocol-pg/pom.xml   sdtp-protocol-pg/pom.xml
COPY sdtp-core/pom.xml      sdtp-core/pom.xml
COPY sdtp-backend/pom.xml   sdtp-backend/pom.xml
COPY sdtp-metrics/pom.xml   sdtp-metrics/pom.xml
COPY sdtp-server/pom.xml    sdtp-server/pom.xml

# 预下载依赖（go-offline），只有 pom 变化时才重新触发
RUN ./mvnw dependency:go-offline -B -q

# 复制全部源码并执行打包
COPY . .
RUN ./mvnw clean package -DskipTests -B -q

# -------------------------------------------------------------------
# Stage 2: 运行阶段（仅 JRE 8，镜像更小）
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

EXPOSE 3306

# JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["./bin/start.sh", "start-fg"]
