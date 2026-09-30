# 2026-09-30 P2-1 结构化日志收尾交接

> 结论先行：**P2-1 不能标记完成。** Actuator / Prometheus 暴露契约已经修复并提交；JSON 控制台日志的配置、依赖、部署默认值和契约测试已在本地工作区完成并通过定向测试；但 **SkyWalking `traceId` 当前仍不可用**。`f1dfd1d` 提交声明 `TraceIdConverter` 可用是错误结论：该类不在 `apm-toolkit-logback-1.x:9.7.0` 中。运行时冒烟也显示 JSON 可输出、`service` 字段存在，但 `traceId` 为空，并且 stderr 报 `TraceIdConverter` 无法实例化。接手者应先修 traceId，再做全仓验证和提交。

## 1. 当前仓库快照

本快照在创建本交接文档前实测：

| 项 | 状态 |
|---|---|
| 仓库 | `C:\Users\Administrator\Desktop\AmazonERP` |
| 分支 | `master` |
| 本地 HEAD | `f1dfd1d03c7ced63952f5e8450312e81e34009f3` |
| 远端基线 | `origin/master` = `46eade553687b048c3ec7b51a93ddebb34d9812a` |
| 领先远端 | 2 个本地提交：`0e8cfff`、`f1dfd1d` |
| 工作区 | 创建本文件前有 9 个已跟踪修改文件；本文件会新增 1 个未跟踪交接文档 |
| 核心脏改 | P2-1 JSON 结构化日志相关依赖、配置、部署默认 profile 与契约测试 |
| 进程残留 | 无已知 Java 服务进程残留 |
| 未完成 | **traceId 注入仍失败；最后一次脏改状态下未做全仓 `mvn clean verify`** |

### 1.1 创建本文件前的 9 个脏文件

```text
.env.example
amz-common/pom.xml
amz-common/src/main/resources/logback-spring.xml
amz-common/src/test/java/com/amz/config/ProfileActivationContractTest.java
amz-service/amz-service-spapi/src/test/java/com/amz/deploy/DeploymentManifestContractTest.java
amz-service/amz-service-spapi/src/test/java/com/amz/deploy/ObservabilityExposureContractTest.java
docker-compose.yml
k8s/configmap.yaml
pom.xml
```

`git diff --stat` 显示 9 files changed, 126 insertions(+), 36 deletions(-)。这个数字不含本交接文档。

## 2. 已提交工作

### 2.1 Commit `0e8cfff` — Actuator / Prometheus 暴露契约

该提交已经完成：

1. 修正 Prometheus 抓取端口：
   - user：`8086 -> 8080`
   - product：`8087 -> 8095`
2. 12 个模块补齐 `management` 配置。
3. order / product 从 Spring Boot 2 的 `management.metrics.export.prometheus.enabled` 迁移到 Boot 3 的 `management.prometheus.metrics.export.enabled`。
4. 移除 `k8s/ingress.yaml` 中公网 `/actuator` 暴露。
5. `ObservabilityExposureContractTest` 建立并修复到绿色。

### 2.2 Commit `f1dfd1d` — traceId 修复声明有误

该提交做了这些表面修复：

1. 新增 `skywalking.version=9.7.0`。
2. 引入 `apm-toolkit-logback-1.x`。
3. 16 个 `application.yml` 把 `%X{traceId}` 改成 `%traceId`，并补 `%wEx`。
4. `logback-spring.xml` 注册：

```xml
<conversionRule conversionWord="traceId"
                converterClass="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdConverter"/>
```

但这个类不在依赖里。对本地 Maven 仓库 `apm-toolkit-logback-1.x-9.7.0.jar` 的 `jar tf` 实查只看到：

```text
org/apache/skywalking/apm/toolkit/log/logback/v1/x/logstash/TraceIdJsonProvider.class
org/apache/skywalking/apm/toolkit/log/logback/v1/x/TraceIdPatternLogbackLayout.class
org/apache/skywalking/apm/toolkit/log/logback/v1/x/LogbackPatternConverter.class
```

没有 `TraceIdConverter.class`。因此该 commit message 中 “working `%traceId` converter” 的说法不能作为事实采信。当前 16 个模块的文本 console pattern 仍然引用 `%traceId`，在 JSON 分支里也通过 pattern provider 引用 `%traceId`，运行时会受同一个错误类影响。

## 3. 本轮未提交 P2-1 JSON 工作

### 3.1 目标

把服务控制台日志从文本切到可选 JSON，并给聚合系统提供稳定的服务名和 traceId。当前完成的是前半部分：JSON 可选分支可用；后半部分 traceId 仍不可用。

### 3.2 已实现内容

| 文件 / 范围 | 变更 |
|---|---|
| 根 `pom.xml` | 新增 `logstash-logback-encoder.version=8.0`，并在 `dependencyManagement` 钉住版本 |
| `amz-common/pom.xml` | 引入 `net.logstash.logback:logstash-logback-encoder` |
| `amz-common/src/main/resources/logback-spring.xml` | 增加 `!log-json` 文本分支和 `log-json` JSON 分支；JSON 分支使用 `LogstashEncoder`、`customFields` 写入 `service`，并通过 `LoggingEventPatternJsonProvider` 输出 `traceId` 字段 |
| `docker-compose.yml` | 16 个 Spring 应用服务默认 profile 从 `prod` 改成 `prod,log-json` |
| `k8s/configmap.yaml` | `SPRING_PROFILES_ACTIVE` 改成 `prod,log-json` |
| `.env.example` | 同步为 `prod,log-json`，并说明 `log-json` 用途 |
| `ProfileActivationContractTest` | 契约更新为 16 个 compose 应用服务必须默认 `prod,log-json`，且禁止 `mock` 作为默认 |
| `DeploymentManifestContractTest` | 契约更新为 K8s ConfigMap 默认 profile 包含 `log-json` |
| `ObservabilityExposureContractTest` | 增加 JSON / logstash encoder / service / traceId / compose 数量等契约断言 |

当前 JSON appender 的关键片段是：

```xml
<appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <customFields>{"service":"${serviceName}"}</customFields>
        <provider class="net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider">
            <pattern>{"traceId":"%traceId"}</pattern>
        </provider>
    </encoder>
</appender>
```

这里 `"traceId":"%traceId"` 是当前失败点，因为 `%traceId` 被注册到不存在的 `TraceIdConverter`。

## 4. 验证状态

### 4.1 本文件创建前已复跑的定向测试

命令：

```powershell
mvn -pl amz-service/amz-service-spapi -am `
  "-Dtest=ObservabilityExposureContractTest,DeploymentManifestContractTest,ProfileActivationContractTest" `
  "-DfailIfNoTests=false" test
```

结果：

| 模块 / 测试 | 结果 |
|---|---|
| `amz-common` / `ProfileActivationContractTest` | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| `amz-service-spapi` / `DeploymentManifestContractTest` | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` |
| `amz-service-spapi` / `ObservabilityExposureContractTest` | `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` |
| Maven result | `BUILD SUCCESS`，总耗时 9.906 s |

### 4.2 其他历史验证

| 验证 | 状态 | 边界 |
|---|---|---|
| 早期全仓 `mvn clean verify -Dmaven.test.failure.ignore=true` | BUILD SUCCESS | 是在 JSON 脏改推进过程中做的，不是最后一次脏改终态 |
| 最近一次打包 `mvn -pl amz-service/amz-service-spapi -am package -DskipTests` | PASS | 只验证打包，不替代测试 |
| JSON 运行时冒烟 | JSON 可输出、可解析；`service` 字段存在 | `traceId` 为空 |
| JSON 运行时 stderr | 出现 `TraceIdConverter` 无法实例化错误 | 这是真实失败，不是噪声 |

### 4.3 已知环境级失败

`DeepSeekAgentConfigurationContractTest.createsAgentBeansWhenApiKeyIsConfigured` 在 Windows JDK 环境下因 `UnixDomainSockets` / loopback 连接失败。该失败与本次 JSON 日志改动无关，属于环境性问题，不能用来掩盖 traceId 的真实缺陷。

## 5. 工作过程记录

1. 从 `2026-09-28-phase0-handoff.md` 进入，先核对 Phase 0 未完成项。
2. P0 按 `2026-09-30-phase0-p0-closure.md` 闭环：checksum 修复由 `v0.1.3` 远端 run 验证，Cosign 独立验签 17/17 通过，release immutability 已对未来发布启用。
3. P1-2 前端 E2E 已闭环：旧套件真实红灯被修复为 hermetic stubs；远端 run `36646986161` 为 45 passed / 10 jobs success。仍要记住：**前端打桩不等于后端契约验证**。
4. P1-3 Flyway 迁移审计和四项风险随访完成到可决策状态。其中 legacy init SQL 建议保留 + 冻结；ad/V7 仍需生产/预发预检、备份恢复演练、维护窗和审批；order/V4 已有 runbook，但生产行数、复制延迟和云盘 IO 未测。
5. P2-1 第一步先修 Micrometer / Prometheus 暴露契约，提交为 `0e8cfff`。
6. P2-1 第二步尝试修 SkyWalking traceId，提交为 `f1dfd1d`，但该步的类存在性判断错误。
7. P2-1 第三步推进 JSON 结构化日志：引入 logstash encoder、增加 profile 分支、统一部署默认值、补契约测试。
8. 运行时冒烟揭穿契约测试的盲区：测试只检查配置文本，不能发现 `conversionRule` 引用的类不存在。
9. 本轮按用户要求停止继续实现，转为交接。

## 6. 当前项目整体状态

| 阶段 | 当前状态 | 诚实边界 |
|---|---|---|
| P0 / Phase 0 | 已闭环 | 治理仍是单用户自审；`master` 分支保护仍未启用 |
| P1-2 前端 E2E | 已闭环 | stub 只保证前端不因 mock 数据形状崩溃，不验证真实后端契约 |
| P1-3 数据库迁移 | 审计与演练完成，风险已量化 | ad/V7 仍缺生产预检、备份恢复、审批；order/V4 生产规模未测 |
| P2-1 可观测性 | 指标暴露契约已提交；JSON 日志本地可用；traceId 未解决 | **不能宣称 P2-1 完成** |
| P2-2 性能压测 | 未开始 | 无生产规模基线 |
| P2-3 CI/CD 深化 | 未开始 | 当前治理和分支保护缺口仍待决策 |

## 7. 已解决问题

1. **Prometheus 抓取端口错配**：user / product 抓取端口已与实际 server.port 对齐。
2. **Boot 2 / Boot 3 Prometheus 配置键混用**：order / product 已迁到 Boot 3 key。
3. **公网 Actuator 暴露面**：`k8s/ingress.yaml` 不再暴露 `/actuator`。
4. **文本日志异常堆栈缺失**：16 个模块 console pattern 已补 `%wEx`。
5. **JSON 输出能力**：`log-json` profile 可输出可解析 JSON，且带 `service` 顶层字段。
6. **部署默认一致性**：compose、K8s ConfigMap、`.env.example` 默认 profile 统一为 `prod,log-json`。
7. **契约防回退**：16 个 compose 应用服务数量、K8s profile、依赖版本、JSON appender 结构已有测试冻结。

## 8. 未解决问题与风险

### 8.1 P2-1 traceId 仍然失败

这是最高优先级。

- JSON 日志里的 `traceId` 当前为空。
- stderr 有 `TraceIdConverter` 实例化失败。
- 文本分支的 `%traceId` 也不是可靠能力，因为同一个不存在的类被注册为 converter。
- 契约测试当前只检查 XML 文本，不能证明类可加载和运行时可注入。

### 8.2 全仓终态验证未做

最后一次脏改状态下只复跑了三个契约测试套件。还没有在当前终态执行：

```powershell
mvn clean verify
```

因此不能宣称脏改对 19 个模块无副作用。

### 8.3 日志消费端兼容风险

默认 profile 从 `prod` 变成 `prod,log-json` 后，所有 16 个应用的 stdout 会从文本变成 JSON。如果现有 Docker / K8s 日志采集器、告警规则或人工排障脚本按文本 pattern 解析，会立即断。

这个变更方向合理，但它不是纯 additive，需要在交接后确认采集端已支持 JSON。

### 8.4 契约测试假绿风险

`ObservabilityExposureContractTest` 能防止 XML 结构被误删，但它当前验证不了：

1. `conversionRule` 指向的类是否真的存在。
2. SkyWalking agent 是否真的注入 traceId。
3. 无 agent 时的降级输出是否符合预期。

### 8.5 历史提交与证据不一致

`f1dfd1d` 的 commit message 和 `2026-09-30-p2-1-tracing-traceid-fix.md` 都宣称 traceId 修复可用。这个结论已被运行时证据推翻。不要删改历史文件来掩盖；后续修复文档必须显式更正。

### 8.6 P1-3 与治理遗留

- ad/V7 的生产/预发预检、备份恢复验证、审批签字未做。
- order/V4 的生产行数、复制延迟、云盘 IO 未测。
- `master` 分支保护仍 404。
- `production` environment 仍是单用户自审，不构成独立四眼复核。

## 9. 后续计划

### 9.1 第一优先级：修复 traceId，不要继续堆新功能

建议按下面的顺序做：

1. **JSON 分支改用 SkyWalking 官方 provider**
   - 删除当前 `LoggingEventPatternJsonProvider` 里的 `%traceId` 方案，或至少不再依赖不存在的 converter。
   - 评估并改用：

     ```text
     org.apache.skywalking.apm.toolkit.log.logback.v1.x.logstash.TraceIdJsonProvider
     ```

   - 该类已通过 `jar tf` 确认存在于 `apm-toolkit-logback-1.x:9.7.0`。

2. **文本分支重新设计**
   - 当前 `TraceIdConverter` 不存在，不能继续作为事实源。
   - 候选方案是 `TraceIdPatternLogbackLayout` 或 `LogbackPatternConverter`，但必须先做最小运行时验证，不要只看类名和文档。
   - 文本 pattern 与 JSON 分支的 traceId 语义要一致。

3. **补强契约测试**
   - 不只断言 XML 包含某个字符串。
   - 至少应通过 `Class.forName` 或等价机制验证 XML 中引用的 SkyWalking layout/provider 类在运行时 classpath 可加载。
   - 保留 16 个服务数量和 profile 默认值断言。

4. **运行时双向冒烟**
   - 无 SkyWalking agent：服务必须正常启动，JSON 可解析，traceId 的降级值必须明确定义。
   - 有 SkyWalking agent：同一请求跨服务日志应能取到相同非空 traceId。
   - stderr 不允许再出现 converter instantiation 错误。

5. **更新证据文档**
   - 修正 `2026-09-30-p2-1-tracing-traceid-fix.md` 的错误结论，或在后续文档中明确撤销其 traceId 成功声明。
   - 记录修复前后 JSON 样例、stderr、agent / no-agent 差异和测试输出。

6. **终态全仓验证**
   - traceId 修完后执行 `mvn clean verify`。
   - 若有环境级失败，必须先在干净基线复现，证明是 pre-existing，不能直接忽略。

### 9.2 第二优先级：提交和推送策略

当前 `master` ahead 2，且工作区脏。建议：

1. 不要把当前 JSON 工作和 traceId 修复混成一个大 commit。
2. 先修 traceId，补运行时证据，再单独提交。
3. JSON 日志与 profile 默认值变更可以作为另一个 commit，或与 traceId 修复合并成一个完整 P2-1 commit，但 commit message 必须写清楚 JSON 可用、traceId 已修复。
4. 推送前重新跑定向契约测试和全仓 verify。
5. `f1dfd1d` 尚未推送；如果团队决定在本地修正历史，可以在明确批准后 rebase / amend。若担心证据链一致性，推荐 fix-forward，不重写。

### 9.3 第三优先级：P1-3 和治理决策

1. 对 ad/V7 安排生产/预发真实预检、备份恢复演练、维护窗和审批。
2. 对 order/V4 用生产或接近生产的数据规模验证耗时、复制延迟和存储 IO。
3. 决策 `master` 分支保护策略。
4. 为 `production` environment 增加第二审批人 / 团队，并启用 `prevent_self_review=true`。

### 9.4 暂缓项

在 P2-1 traceId 未闭环前，不建议开始：

1. P2-2 性能压测基线。
2. P2-3 CI/CD 深化。
3. OpenTelemetry 迁移。仓库已有 SkyWalking 设施，双 tracing 系统会引入 agent 冲突风险，需要架构决策。

## 10. 接手者快速检查命令

### 10.1 确认仓库状态

```powershell
git status -sb
git log -5 --oneline --decorate
git diff --stat
```

预期：接手时至少能看到本交接文档和 9 个 P2-1 脏文件；`master` 相对 `origin/master` ahead 2。

### 10.2 确认 SkyWalking 9.7.0 jar 中没有错误类

```powershell
$jar = "$env:USERPROFILE\.m2\repository\org\apache\skywalking\apm-toolkit-logback-1.x\9.7.0\apm-toolkit-logback-1.x-9.7.0.jar"
jar tf $jar | Select-String -Pattern 'TraceIdConverter|TraceIdPatternLogbackLayout|LogbackPatternConverter|TraceIdJsonProvider'
```

预期：没有 `TraceIdConverter.class`；其余三个候选类存在。

### 10.3 复跑当前契约测试

```powershell
mvn -pl amz-service/amz-service-spapi -am `
  "-Dtest=ObservabilityExposureContractTest,DeploymentManifestContractTest,ProfileActivationContractTest" `
  "-DfailIfNoTests=false" test
```

预期：6 + 10 + 14 = 30 tests PASS。注意，这只说明配置契约未回退，不代表 traceId 运行时可用。

### 10.4 traceId 修复后的最低验收

1. 无 agent 启动一个服务，确认 JSON 每行可解析。
2. 有 agent 启动至少两个有调用关系的服务，发一次跨服务请求。
3. 两条服务日志都能取到相同非空 traceId。
4. stderr 无 converter 初始化错误。
5. `mvn clean verify` 通过。
6. 更新 P2-1 证据文档后，再提交。

## 11. 关键文件索引

| 文件 | 作用 |
|---|---|
| `docs/superpowers/evidence/2026-09-30-phase0-p0-closure.md` | P0 闭环证据 |
| `docs/superpowers/evidence/2026-09-30-p1-2-frontend-e2e.md` | 前端 E2E 闭环证据 |
| `docs/superpowers/evidence/2026-09-30-p1-3-migration-audit.md` | Flyway 审计总入口 |
| `docs/superpowers/evidence/2026-09-30-p2-1-observability.md` | Prometheus / Actuator 契约证据 |
| `docs/superpowers/evidence/2026-09-30-p2-1-tracing-traceid-fix.md` | 旧 traceId 证据；其中“已修复可用”结论已被推翻 |
| `amz-common/src/main/resources/logback-spring.xml` | 文本 / JSON 日志分支和当前 traceId 缺陷所在 |
| `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/ObservabilityExposureContractTest.java` | 可观测性契约测试 |
| `docker-compose.yml` | 16 个应用服务默认 profile |
| `k8s/configmap.yaml` | K8s 默认 profile |