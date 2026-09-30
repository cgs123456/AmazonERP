# P2-1 可观测性 — Actuator / Prometheus 暴露面契约修复

日期：2026-09-30
分支：master
基线：46eade5

## 结论

Prometheus 抓取配置（prometheus.yml）声明了 16 个抓取目标（gateway + 15 业务服务），
但实际只有 3 个模块显式暴露了 /actuator/prometheus，其余 13 个在 Boot 3.5.16 默认
配置下返回 404。此外有 2 个抓取端口写错（user 8086 vs 实际 8080、product 8087 vs
实际 8095），连不上而非 404。另有 3 个模块用 Boot 2 的废弃 key
management.metrics.export.prometheus.enabled（Boot 3.5.16 无绑定类，配置为死代码）。

## 修复清单

### 1. prometheus.yml 端口修正
- amz-service-user:8086 → amz-service-user:8080
- amz-service-product:8087 → amz-service-product:8095
- 依据：docker-compose.yml、k8s/services/*.yaml、application.yml 三层一致，
  prometheus.yml 是唯一不一致的层级。

### 2. 12 个无 management 块的模块：新增规范块
ad, ai, customer, finance, logistics, message, multiplatform, ops,
procurement, report, search, user

每个模块追加：
\\yaml
management:
  prometheus:
    metrics:
      export:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
\
### 3. order / product：Boot 2 → Boot 3 key 迁移
- 旧：management.metrics.export.prometheus.enabled
- 新：management.prometheus.metrics.export.enabled
- 依据：spring-boot-actuator-autoconfigure 3.5.16.jar 的
  PrometheusProperties / PrometheusPropertiesConfigAdapter 只绑定新前缀。

### 4. gateway：补 prometheus 暴露 + Boot 3 key
- include 从 health,info 扩展为 health,info,prometheus（prometheus.yml 在抓取 gateway）
- 新增 management.prometheus.metrics.export.enabled=true

### 5. k8s/ingress.yaml：移除 /actuator 公网路由
- 原因：k8s 探针走 kubelet 直连 pod（已验证 deployment yaml probe path），
  Prometheus 走内网 service DNS；公网 /actuator 只是暴露面。
- 现在路径只有 /api 和 /。

## 契约测试

\ObservabilityExposureContractTest\（amz-service-spapi test）：7 个断言全绿。

| 断言 | 验证内容 |
|------|----------|
| everyScrapeTargetResolvesToItsOwnServerPort | 16 个抓取目标与 16 个模块一一对应且端口一致 |
| everyScrapedModuleExposesPrometheusEndpoint | 每个被抓取模块的 include 包含 prometheus |
| everyModuleEnablesLivenessAndReadinessProbes | probes.enabled=true 显式设置 |
| everyModuleKeepsHealthDetailsPrivate | show-details=never |
| everyModuleUsesTheBootThreePrometheusRegistryKey | Boot 3 key 存在 + Boot 2 key 不存在 |
| yamlExtractionIsTrustworthy | YAML 解析器能区分 pass/fail（防空绿） |
| ingressMustNotExposeActuator | k8s/ingress.yaml 不包含 /actuator |

## 验证门

- mvn clean verify（19 模块）：BUILD SUCCESS
  - 2 个环境级失败（Windows JDK UnixDomainSockets loopback 连接），
    在 stash 后 clean tree 上复现，确认是 pre-existing 而非本次改动引入。
- Checkstyle critical：0 violations
- 契约测试：7/7 PASS

## 遗留 / 后续

1. OpenTelemetry tracing 与结构化日志仍在 P2-1 范围但未做（本文只覆盖
   Micrometer + Prometheus 指标暴露面契约）。
2. 性能压测基线（P2-2）与 CI/CD 深化（P2-3）未动。
