# SDT Prometheus 监控指标 (JDBC Driver & SDK)

> SDT JDBC Driver 内置了 Prometheus 指标采集，覆盖 SQL 翻译、JDBC 操作及底侧性能指标。指标统一注册在 JVM 全局的 `io.prometheus.client.CollectorRegistry.defaultRegistry` 中。

---

## 目录

1. [集成方式](#1-集成方式)
   - [1.1 Spring Boot + Actuator（零配置）](#11-spring-boot--actuator零配置)
   - [1.2 非 Spring Boot 应用](#12-非-spring-boot-应用)
2. [常用指标清单](#2-常用指标清单)
3. [Prometheus 刮取配置](#3-prometheus-刮取配置)

---

## 1. 集成方式

SDT JDBC 驱动**不启动独立的 HTTP 服务**。所有指标已注册在 JVM 全局的 `CollectorRegistry.defaultRegistry`，你的应用程序只需暴露这个 registry 即可。

### 1.1 Spring Boot + Actuator（零配置）

如果你的应用是 Spring Boot，无需任何额外代码。

**Maven 依赖**：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**application.yml**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
```

访问 `http://localhost:8080/actuator/prometheus`，SDT 指标将自动出现。

### 1.2 非 Spring Boot 应用

在 `main()` 或初始化代码中添加：

```java
import io.prometheus.client.exporter.HTTPServer;

public class MyApp {
    public static void main(String[] args) throws Exception {
        // 启动 Prometheus HTTP 端点，SDT 指标自动出现在 /metrics
        new HTTPServer(9090);

        // ... 你的业务代码
    }
}
```

---

## 2. 常用指标清单

| 指标名称 | 类型 | 标签 | 含义 |
|---------|------|------|------|
| `sdt_translation_duration_seconds` | Summary / Counter | `source_dialect`, `target_dialect` | SQL 翻译耗时与成功计数 |
| `sdt_translation_errors_total` | Counter | `source_dialect`, `target_dialect` | SQL 翻译失败总数 |
| `sdt_jdbc_executions_total` | Counter | `method` | JDBC 执行拦截计数 |

---

## 3. Prometheus 刮取配置

在 Prometheus 配置 `prometheus.yml` 中添加 target 节点：

```yaml
scrape_configs:
  - job_name: 'sdt-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```
