# P2-1 可观测性 — SkyWalking traceId 注入修复 + 异常输出修复

日期：2026-09-30
分支：master
基线：0e8cfff（P2-1 指标暴露面契约修复）

## 问题

1. **死 traceId 占位符**：所有 16 个模块的 `logging.pattern.console` 包含 `%X{traceId}`（注释声称"与 Skywalking 链路追踪关联"），
   但全仓没有任何代码调用 `MDC.put("traceId",...)`，也没有引入
   SkyWalking `apm-toolkit-logback-1.x` 的 `TraceIdConverter`。
   `%X{traceId}` 直接读 MDC，无任何代码写入就永远输出空字符串。
2. **异常堆栈不会打印**：自定义 pattern 覆盖 Spring Boot 默认后缺少 `%wEx`
   （`LOG_EXCEPTION_CONVERSION_WORD` 的默认值），导致 `logger.error(msg, throwable)`
   的异常堆栈不会输出。

## 修复

| 变更 | 文件 |
|------|------|
| 新增 `skywalking.version=9.7.0` 属性（与 Dockerfile agent 9.7.0 对齐） + dependencyManagement 条目 | pom.xml |
| 新增 `apm-toolkit-logback-1.x` 依赖（所有模块经 amz-common 继承） | amz-common/pom.xml |
| 注册 `conversionRule conversionWord="traceId" converterClass="...TraceIdConverter"` | amz-common/src/main/resources/logback-spring.xml |
| 16 个 application.yml：`[%X{traceId}]` → `[%traceId]` ＋ 末尾追加 `%wEx` | amz-gateway + 15 个服务 |

## 工作原理

`%traceId` 使用 SkyWalking 的 `TraceIdConverter` 转换器而非直接读 MDC：
- 有 SkyWalking agent 时：转换器调用 `TraceContext.traceId()` 获取当前 span 的 trace ID
- 无 agent 时（本地开发）：转换器返回 `IGNORED`，不会抛异常
- `%wEx` 是 Spring Boot 的 `LOG_EXCEPTION_CONVERSION_WORD` 默认值，
  注册在 `defaults.xml` 中的 `ExtendedWhitespaceThrowableProxyConverter`，
  用于在消息后输出异常堆栈。

## 契约测试新增断言

在已有 `ObservabilityExposureContractTest` 中新增 3 个测试（总计 10 个）：

| 断言 | 验证内容 |
|------|----------|
| everyModuleUsesSkyWalkingTraceIdConverter | 每个模块 pattern 含 `[%traceId]`，不含 `%X{traceId}`，且含 `%wEx` |
| logbackSpringRegistersTraceIdConversionRule | logback-spring.xml 注册了 conversionRule + TraceIdConverter |
| rootPomManagesSkyWalkingToolkitVersion | 根 pom 管理 apm-toolkit-logback-1.x + skywalking.version=9.7.0 |

## 验证门

- mvn clean verify（19 模块）：BUILD SUCCESS
- Checkstyle critical：0 violations
- 契约测试：10/10 PASS

## 遗留

1. 本地开发无 agent 时 traceId 输出 `IGNORED`（预期行为，非缺陷）
2. OpenTelemetry 与 SkyWalking 是两个独立系统；仓库已有 SkyWalking 基础设施，
   引入 OpenTelemetry 会产生双 agent 冲突，需架构级决策。本文档只做了
   traceId 日志关联修复（让 SkyWalking 链路真正可用），不做 OpenTelemetry 迁移。
3. 结构化日志（JSON 输出）仍未做；当前为纯文本格式。
