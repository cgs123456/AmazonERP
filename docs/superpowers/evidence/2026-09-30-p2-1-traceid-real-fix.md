# 2026-09-30 P2-1 traceId 真修复：从"假绿"到可用（含 P0 定级更正）

> 结论先行：**`f1dfd1d` 不是"traceId 显示不全"，而是 16 个服务启动即失败（P0）。**
> 本文推翻两份既有结论：`2026-09-30-p2-1-tracing-traceid-fix.md` 的"traceId 修复可用"，以及
> `2026-09-30-p2-1-json-log-handoff.md` §4.2 的"JSON 运行时冒烟可输出、服务在跑"。
> 本轮把 traceId 修到真实可用（含部署层 agent 挂载），并补上能抓住这类故障的 hermetic 契约测试与反向验证。
> **P2-1 仍不能标完成**，但未证项已收敛为两条：① 跨服务**关联链复核需要 OAP**（传播本身已在 §10.3 实测成立）；
> ② 实测发现两侧 `%tid` 不逐字相同，交接文档"两服务取到相同 traceId"这条验收写法在 agent 9.7.0 下不成立，需修正。
> 另外 k8s 侧仍无采集端；仓库 Dockerfile 的完整重建受容器侧死代理阻塞（§10.1），ENTRYPOINT 语义已用派生镜像验证（§10.2）。

## 1. P0 定级证据（RED 基线）

不改任何文件，用工作区配置打出的 fat jar 直接启动（端口与中间件都绕开在跑的 mock 栈）：

```powershell
mvn -o -B -pl amz-service/amz-service-message -am clean package -DskipTests
java -jar amz-service/amz-service-message/target/amz-service-message-1.0-SNAPSHOT.jar `
  --spring.profiles.active=mock --server.port=18889 `
  --spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false
```

| 观测 | 值 |
|---|---|
| 进程退出码 | 1 |
| `Started .*Application` | 0 次（从未启动成功） |
| 异常链 | `java.lang.IllegalStateException: Logback configuration error detected` → `ClassNotFoundException: org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdConverter` |
| 触发点 | `PatternLayoutEncoder.start()` → `ConverterSupplierByClassName.get()` → `addError(...)`（`javap` 实查该方法 catch Exception 后 `addError` 并返回 null） |
| 放大机制 | Boot 3.5.16 `LogbackLoggingSystem#reportConfigurationErrorsIfNecessary` 对任意 ERROR status 抛 `IllegalStateException`（`javap` 实查该私有方法构造 `Logback configuration error detected: %n%s` 并 throw） |

因此 `f1dfd1d`（已提交、未推送）把 **16 个可部署模块全部变成启动即崩**，与是否启用 `log-json` 无关：文本分支的 `%traceId` 也会被同一个坏类编译。

### 1.1 为什么上一任的冒烟给出了相反结论

两条独立原因，都属于取证方法问题：

1. **测的是过期产物。** 本机现存 jar 里，`amz-service-user`/`amz-service-message` 的 fat jar 曾停留在 10:01 构建，其内嵌 `amz-common` 用的是更早的 `net.logstash.logback.provider.PatternJsonProvider`（该 FQCN 在 logstash-encoder 8.0 里也不存在）。`mvn package` 会报 `BUILD SUCCESS` 并打印 `Replacing main artifact`，但增量构建复用了旧 jar，产物mtime 与内容都没变。加 `clean` 后内嵌配置才与工作区一致。**任何"打包即绿"的运行时结论都必须先证明被测产物携带当前配置。**
2. **在用容器取证取错了对象。** 当前在跑的 6 个 app 镜像早于 Dockerfile 的 agent stage，`docker exec <svc> ls /skywalking-agent` → No such file，其 stdout 的空 `[]` 槽位来自 `f1dfd1d` 之前的 `%X{traceId}`，不能用来推断新配置的行为。

## 2. 根因是两层，不是一层

| 层 | 缺陷 | 证据 |
|---|---|---|
| 日志配置 | `conversionRule` 指向 toolkit 里不存在的 `TraceIdConverter` | `unzip -l apm-toolkit-logback-1.x-9.7.0.jar`：可用类只有 `LogbackPatternConverter`、`TraceIdPatternLogbackLayout`、`mdc.LogbackMDCPatternConverter`、`logstash.TraceIdJsonProvider`、`logstash.SkyWalkingContextPatternConverter`/`SkyWalkingContextJsonProvider`、`log.GRPCLogClientAppender` |
| 部署装配 | **agent 从未挂载** | `Dockerfile` 里 `-javaagent` 只存在于 `ENV JAVA_OPTS` 默认值，ENTRYPOINT 是 `java $JAVA_OPTS -jar`；而 compose 对每个 app 服务传 `JAVA_OPTS=${JAVA_OPTS:-}`、`k8s/configmap.yaml` 自带不含 agent 的 `JAVA_OPTS` → 两条部署路径整体覆盖，agent 被摘掉 |
| 服务身份 | 全仓无 `SW_AGENT_NAME` | grep compose/k8s/`.env*` 零命中；镜像默认 `amz-service` 会让 16 个服务在 SkyWalking 里塌缩成同一个名字 |
| 测试机制 | 契约测试只断言 XML/yml 文本 | `ObservabilityExposureContractTest.logbackSpringRegistersTraceIdConversionRule` 断言的是"字符串里有没有 `TraceIdConverter`"——它恰好把坏类名冻结成了绿 |

只修第一层（换类名）会让 traceId 永久停在降级值，这也是交接文档 §9.1 的方案走到头仍然拿不到真 id 的原因。

## 3. 三种 traceId 机制实测（决定 JSON 字段契约）

一次运行并列装配四种输出，在**已挂 agent 的 fat jar**（Spring Boot 嵌套 `LaunchedClassLoader`）里发起真实 HTTP 请求：

| 机制 | 无 agent（含 out-of-span） | 有 agent · in-span | JSON key | 结论 |
|---|---|---|---|---|
| M1 `logstash.TraceIdJsonProvider` | 字段整体省略（`getTracingId` 读 `propertyMap["TID"]` 得 null，`JsonWritingUtils.writeStringField` 对 null 跳过写入） | `"TID":"47ef4ce5...0001"` | `TID` | **采用**：值不带前缀，可直接检索 |
| M2 `LoggingEventPatternJsonProvider` + `{"traceId":"%traceId"}` | `"traceId":"TID: N/A"` | `"patternTid":"TID:47ef4ce5...0001"` | 自定义 | 弃用：agent 侧 `PrintTraceIdInterceptor` 返回 `"TID:" + getGlobalTraceId()`，值被前缀污染 |
| M3 `mdc.LogbackMDCPatternConverter`（裸 conversionRule） | 空 | — | — | 判死：需要 `TraceIdMDCPatternLogbackLayout` 注册 `%X{tid}`，直接 `%X{...}` 读的是空 MDC |
| 文本 `[%traceId]` | `[TID: N/A]` | `[TID:47ef4ce5...0001]` | — | 保留（16 个模块 pattern 不动） |

字节码依据（均本地 `javap`/`unzip` 实查，非文档推断）：

- `LogbackPatternConverter.convert()` 编译体是 `ldc "TID: N/A"; areturn` —— 无 agent 桩，真值由 agent 增强注入。
- `activations/apm-toolkit-logback-1.x-activation-9.7.0.jar` 内 `skywalking-plugin.def` 列出被增强项：`LogbackPatternConverterActivation`、`logstash.TraceIdJsonProviderActivation`、`mdc.MDCConverterActivation`、`async.*`、`log.GRPCLogAppenderActivation`。toolkit 的插桩定义在 activation jar 内，`plugins/` 下没有单独的 logback toolkit plugin。
- `TraceIdJsonProvider` 构造体不调 `setFieldName`，`setFieldNames(LogstashFieldNames)` 里 `ldc "TID"` 硬编码；logstash-encoder 8.0 的 `LogstashFieldNames` 根本没有 tracing 字段。⇒ **字段名只能是 `TID`**；同时 `<fieldName>` 显式写 `TID` 是必要的保险：encoder 若不回写，裸 provider 会因 `fieldName==null` 静默丢字段。

选型判据在跑之前就已写死：① 有/无 agent 值必须字面不同 ② in-span 值是非 `N/A` 的长 id ③ 无 agent 时是稳定常量；多个入选时优先"key 与消费端一致 **且** 值不带前缀"，只能二选一则取值干净者。实测只有 M1 满足②③且值干净，故选 M1，代价是 key 为 `TID`（下游需把 `TID` 映射为 `traceId`，属采集端一行改动，本轮不动 `logstash/logstash.conf`）。

## 4. 两个部署侧硬约束（必须写进运维认知）

1. **OAP 不可达时 agent 不建 trace context。** `ContextManagerExtendService.createTraceContext` 首条分支即 `if (!Config.Agent.KEEP_TRACING && GRPCChannelStatus.DISCONNECT == status) return IGNORED_CONTEXT`，日志因此变成 `TID:Ignored_Trace`。所以"起了 agent"不等于"有 traceId"，还需 OAP 可达，或显式 `SW_AGENT_KEEP_TRACING=true`（本轮不改默认值，保持 SkyWalking 语义）。
2. **agent 日志目录必须可写。** 默认 `/skywalking-agent/logs` 属主是 root，镜像以 `appuser` 运行，故新增 `SW_LOGGING_DIR=/app/logs/sw-agent` 并建目录赋权。

顺带修正：`Dockerfile` 原 `ENV SW_COLLECTOR` 是死变量（镜像内 `agent.config` 读的是 `SW_AGENT_COLLECTOR_BACKEND_SERVICES`）；`agent.service_name=${SW_AGENT_NAME:Your_ApplicationName}`，故镜像不再提供 `SW_AGENT_NAME` 默认值——漏配时暴露成明显错误的名字，比 16 个服务共用一个假身份更好。

> `skywalking/agent/config/agent.config` 仍在仓库里，但 `Dockerfile` 从未 `COPY` 它，属惰性误导文件。**本轮只记录、未删除**（避免与并行修改该树的 agent 冲突），建议后续显式清理或改为真正进镜像的 vendored config。

## 5. 本轮改动

| 文件 | 改动 |
|---|---|
| `amz-common/src/main/resources/logback-spring.xml` | `converterClass=...TraceIdConverter` → `class=...LogbackPatternConverter`（`class` 是新属性名，`converterClass` 已被 logback 1.5 标 deprecated 并产生 WARN）；JSON 分支 provider 换成 `TraceIdJsonProvider` 并显式 `<fieldName>TID</fieldName>` |
| `Dockerfile` | 拆出 `ENV SW_AGENT_OPTS`（不再依赖 `JAVA_OPTS`）；`ENTRYPOINT` 改为 `exec java ${SW_AGENT_OPTS} ${JAVA_OPTS}` 并先对 `JAVA_OPTS` 去重 `-javaagent`；`SW_COLLECTOR` → `SW_AGENT_COLLECTOR_BACKEND_SERVICES`；新增 `SW_AGENT_NAMESPACE`、`SW_LOGGING_DIR`；移除 `ENV SW_AGENT_NAME` 默认值 |
| `docker-compose.yml` | 16 个 app 服务各加字面 `SW_AGENT_NAME=<service key>`；默认 profile 由 `prod,log-json` 回退为 `prod` |
| `k8s/services/*.yaml`（16 份） | Deployment 主容器加字面 `SW_AGENT_NAME=<metadata.name>`（不进 ConfigMap：`kubernetesReferencesResolveExactlyToDeclaredConfigAndSecretKeys` 要求 configData/configRefs 双向相等，且共享 ConfigMap 无法表达逐服务身份） |
| `k8s/configmap.yaml`、`.env.example` | 默认 profile 回退为 `prod`；`.env.example` 保留一句 `log-json` 是显式 opt-in 的说明 |
| `amz-common/.../AmzLogbackConfigContractTest`（新） | 4 条：XML 引用的每个 FQCN 必须可加载且角色正确；两个 profile 分支真实装配零 ERROR/可疑 status；降级值常量断言；JSON 输出可解析且 `service` 来自 `spring.application.name` |
| `amz-service-spapi/.../SkyWalkingIdentityContractTest`（新） | 5 条：agent 挂载独立于 `JAVA_OPTS`；ENTRYPOINT 用 `exec`；使用真实变量名且保留 CVE 面裁剪；compose/k8s 各 16 个唯一服务名；不得残留共享默认名 |
| 三个既有契约测试 | 见 §6 |
| `tools/release/hygiene-allowlist.json` | 删除 `DeploymentManifestContractTest` 的 content-pin 条目（触发点已消除） |

## 6. 契约测试改写（改断言，不是删）

| 测试 | 旧断言 | 新断言与理由 |
|---|---|---|
| `ObservabilityExposureContractTest.logbackSpringRegistersTraceIdConversionRule` | 必须包含 `TraceIdConverter` | 必须包含 `LogbackPatternConverter` 且**禁止**出现 `TraceIdConverter`；注释指向新 hermetic 测试：文本断言只证明接线，类可加载与零 ERROR 装配由它证明 |
| `ObservabilityExposureContractTest.structuredJsonLoggingProfileIsComplete` | 必须用 `LoggingEventPatternJsonProvider` 且含 `"traceId":"%traceId"` | 必须用 `TraceIdJsonProvider` + `<fieldName>TID</fieldName>`，并**禁止** `"traceId":"%traceId"`（防回退到前缀污染）；结构断言（两个 `springProfile`、`JSON_CONSOLE`、`LogstashEncoder`、`springProperty`、appender-ref 顺序）全部保留 |
| `ObservabilityExposureContractTest.everyModuleUsesSkyWalkingTraceIdConverter` | 已有 `%X{traceId}` 禁令 | 追加 `%X{tid}` 禁令（M3 实测恒空） |
| `composeDefaultsEnableStructuredJsonLogging` → `composeDefaultsKeepTextLoggingWithJsonAsOptIn` | 16 个服务必须 `:-prod,log-json` | 16 个服务必须 `:-prod`，且 compose 不得出现 `:-prod,log-json}`；16 计数保留 |
| `kubernetesDefaultProfileEnablesStructuredJsonLogging` → `...KeepsTextLogging` | ConfigMap 必须含 `log-json` | ConfigMap 必须为 `prod` 且不含 `prod,log-json` |
| `ProfileActivationContractTest` | `DEFAULT_ACTIVE=prod,log-json` | 回退为 `prod`；compose/k8s 条目禁止出现 `log-json`；`.env.example` 必须精确 `prod` 且**必须文档化** `log-json` 的存在（否则后来者会以为分支不存在而删掉它） |
| `PlaceholderCoverageContractTest` | compose/k8s 注入集必须等于"代码占位符 ∪ {SPRING_PROFILES_ACTIVE, TZ, JAVA_OPTS}"，**多余变量一律判失败** | 把 `SW_AGENT_NAME` 加入两侧允许集并注明理由：它只被 java agent 读取，代码里没有 `${SW_AGENT_NAME}` 占位符，所以进不了推导集；其取值正确性由 `SkyWalkingIdentityContractTest` 逐服务约束。这条门禁全仓 `verify` 里最先抓到我的新增变量，属有效拦截而非噪声 |
| `DeploymentManifestContractTest` | `assertEquals("prod,log-json", ...)` ×2、compose `contains("prod,log-json")` | 改为精确 `prod`（用 `assertEquals` 防 `prod,log-json` 蒙混）+ 反向 `assertFalse(contains log-json)`；`deploymentSecretsAreStructurallyValidPlaceholders` 的局部变量 `secret` 改名 `secretDocument`，消除 hygiene `secret-like-assignment` 触发点 |

## 7. 已证 / 未证

| 项 | 状态 | 依据 |
|---|---|---|
| `f1dfd1d` 导致 16 服务启动即崩 | **已证** | §1 原始栈 + rc=1 |
| 文本分支修复后正常启动并渲染降级常量 | **已证** | 无 logback ERROR，39 行 `[TID: N/A]` |
| `log-json` 分支输出可解析 JSON 且带 `service` | **已证** | 39/39 行解析成功，`service` = 应用名 |
| fat jar 嵌套类加载器下 agent 增强生效 | **已证** | §3 的 in-span 真实 id（`amazonerp-user:verify` 镜像内 agent + 新 jar，`SW_AGENT_KEEP_TRACING=true`） |
| M1/M2/M3 的降级值与 key 形状 | **已证** | §3 表 + 字节码 |
| 跨服务上下文传播 | **已证成立**，但见下行 | §10.3：callee 侧 id 携带 caller 的 agent uuid，而它自己直连 trace 前缀不同 |
| 两侧 `%tid` 可精确字符串 join | **不成立** | §10.3 判定 2：下游渲染的段号/时间是本地分量。交接的验收写法需在 OAP 侧关联或改用本段 id，另立决策 |
| 生产 compose 栈真带 agent 起服务 | **已用派生镜像验证 ENTRYPOINT** | §10.2；仓库 Dockerfile 的完整重建仍被容器侧死代理阻塞（§10.1），未覆盖 |
| k8s 侧 traceId 可用 | **清单与地址已接线，运行时未证** | §10.5：新增 OAP/UI 清单 + 16 个 collector 回填 + 跨文件契约；未在集群 apply，镜像也未拉取过 |
| hygiene 门禁 | 已回到 rc=0（改动前为 1，触发点 `DeploymentManifestContractTest.java:228`） | `python tools/release/repository_hygiene.py --root .` |

## 8. 遗留与后续建议

1. 跨服务串联验证：起 `skywalking-oap` + `elasticsearch`，用同一 `SW_AGENT_NAMESPACE` 打通 gateway→一个业务服务，比对两侧日志的 `TID` 值一致；同时验证 `log-json` 分支在同一请求里取到同值。
2. k8s 部署补齐 OAP（或明确该环境不接 trace），并给 `SW_AGENT_COLLECTOR_BACKEND_SERVICES` 指到集群内地址。
3. `logstash/logstash.conf` 的 grok 期望 `TIMESTAMP_ISO8601` 而 pattern 是空格分隔日期，文本模式下本就应持续 `_grokparsefailure`；若要真正落 ES，需要一并处理"字段名 `TID` → `traceId`"与前缀剥离。本轮不动该文件。
4. 清理惰性文件 `skywalking/agent/config/agent.config`（见 §4 说明）。
5. 构建流程改进：运行时冒烟前必须校验产物内容（例如比对 jar 内 `logback-spring.xml` 与工作区一致），避免 §1.1 的过期产物误判重演。
6. 历史结论修正：`2026-09-30-p2-1-tracing-traceid-fix.md` 的"修复完成"与 `f1dfd1d` 的 commit message 均不成立。按 fix-forward 处理，不重写历史（`f1dfd1d` 尚未推送，但保留错误记录 + 本文更正比抹掉证据更有价值）。

## 9. 验证终态（本机离线，全部读命令自身退出码）

| 验证 | 命令 | 结果 |
|---|---|---|
| 全仓终态 | `mvn -o -B clean verify -fae` | **BUILD SUCCESS，VERIFY_RC=0，19/19 模块 SUCCESS，0 FAILURE，0 SKIPPED** |
| spapi 套件 | 同上（模块内） | 647 tests，0 失败，4 skipped（MySQL 依赖 IT 缺环境变量自动跳过） |
| 已知环境性红灯 | `DeepSeekAgentConfigurationContractTest` | **3/3 通过 —— 交接 §4.3 记录的 Windows 红灯在本次未复现**，所以本终态无需豁免任何 pre-existing 失败 |
| 门禁 | `python tools/release/repository_hygiene.py --root .` | rc=0（本轮改动前为 rc=1） |
| 发布清单 | `release_manifest.py build` + `verify`（对齐 ci.yml 的占位 digest 形式） | 均 rc=0，输出写在仓库外，工作树保持干净 |
| 门禁工具自测 | `python -m unittest tools.release.test_*`（7 个模块） | Ran 88 tests，rc=0 |
| Checkstyle | `checkstyle-critical.xml` / `google_checks.xml` | 均 rc=0 |
| 文本分支运行时 | mock profile 起 fat jar | 39 行 `[TID: N/A]`，`Failed to instantiate`/`Logback configuration error` 计数 0 |
| JSON 分支运行时 | `mock,log-json` 起 fat jar | 39/39 行 JSON 可解析，`service` 正确；无 agent 时 `TID` 字段缺省（实测） |
| 有 agent 运行时 | 现有镜像内 agent + 新 jar + 真实请求 | 文本与 JSON 取到同一 id `47ef4ce5...0001` |
| ENTRYPOINT 逻辑 | 真实镜像 dash 内用函数替换 `java` 回放同一段脚本 | 三种情形均正确：旧 `.env` 残留 `-javaagent` 被去重（agent 只出现一次）；compose 传空 `JAVA_OPTS` 仍挂 agent；`SW_AGENT_OPTS=` 可干净关闭 |
| 反向验证（新闸） | 注回历史故障 / provider 类名打错 | `AmzLogbackConfigContractTest`：4/4 绿 → 注回 `TraceIdConverter` **3 红** → provider 打错 **2 红** → 还原 4/4 绿 |
| 反向验证（身份闸） | 把 `-javaagent` 塞回 `JAVA_OPTS` / 删 gateway 的 `SW_AGENT_NAME` | `SkyWalkingIdentityContractTest`：5/5 绿 → **各 1 红** → 还原 5/5 绿 |

提交后自审修正两处（不影响结论，属质量项）：`NO_AGENT_TRACE_TEXT` 声明后未被引用，已改为在断言中实际使用；测试类刻意位于 `org.springframework.boot.logging.logback` 包（Boot 的 `SpringBootJoranConfigurator` 是 package-private），该理由已补进类 javadoc，防止后来者"顺手搬回 com.amz 包"而退化成重置全局 LoggerContext。

## 10. 后续项实测（同日续）

### 10.1 环境约束：Docker 侧网络被死代理挡住

Docker Desktop 被配置为走系统 HTTPS 代理 `127.0.0.1:7897`，而该代理当前未运行：
`docker pull` 与构建期 `ADD https://archive.apache.org/...` 均报
`connectex: No connection could be made because the target machine actively refused it`。
宿主机自身直连正常（`curl` 取 archive.apache.org 返回 200）。后果与处置：

- 仓库 `Dockerfile` 的**完整重建本轮不可验证**（`apt-get`、基础镜像 pull、agent `ADD` 都要网络）。
- 权威 agent 9.7.0 改由宿主机下载：45,958,841 字节，sha256 前缀 `b5de2f7e`，解出 202 个 jar。
  顺带证实官方 tgz **自带 macOS AppleDouble `._*` 条目**，agent 会对每个 `._*.jar` 报
  `jar file can't be resolved`——属上游分发包内容，不是本机提取失误。
  （若要消掉这列噪声，可在 `Dockerfile` 解包后 `find /skywalking-agent -name '._*' -delete`。）

### 10.2 后续项2：派生镜像验证新 ENTRYPOINT

离线做法：以本地含 agent 的镜像为基座，只复刻本轮改动的 `ENV`/`ENTRYPOINT` 语义，
挂当前修复后的 fat jar 运行。

| 检查 | 结果 |
|---|---|
| 传一个**残留 `-javaagent` 的旧式 `JAVA_OPTS`** | PID 1 命令行里 `javaagent` 计数 = **1**（去重生效） |
| `exec` 语义 | `PID 1 = java`（SIGTERM 可直达 JVM，不再等 grace period 被 SIGKILL） |
| agent 挂载来源 | 由 `SW_AGENT_OPTS` 提供，与被覆盖的 `JAVA_OPTS` 无关 |
| 服务启动 | `Started AmzServiceUserApplication in 50.219 seconds`，logback ERROR 计数 0 |
| traceId 活跃 | 请求行 `[TID:1f16f52d….70.…]`，无 span 行 `[TID:N/A]`（增强后的无空格形态） |
| `SW_LOGGING_DIR` | `/app/logs/sw-agent` 属主 `appuser:appuser`，运行用户可写 |

探针容器与派生镜像已全部删除，在跑的 mock 栈（19 个容器）未被改动。

### 10.3 后续项1：跨服务传播实测（并修正一条验收标准）

拓扑：`product`（文本日志）→ Feign → `order`（`log-json`），两侧各挂 9.7.0 权威 agent，
**均关闭 nacos 服务注册**以免抢占在跑栈的流量；collector 不可达，靠 `SW_AGENT_KEEP_TRACING=true`
让 agent 在断连时仍建 trace（否则整条链路退化为 `Ignored_Trace`，见 §4）。

| 观测 | 值 |
|---|---|
| product 侧（caller，root） | `[TID:68ba3402….128.17907473346960001]` |
| order 侧（callee，同请求入站） | `"TID":"68ba3402….144.17907473347050001"` |
| order 侧**直连**请求（无上游） | `"TID":"a2eed589….128…"` ← uuid 段不同 |

判定：

1. **跨进程上下文传播成立。** order 自己发起的 trace 前缀恒为 `a2eed589…`；而经 product 转发的请求，
   order 打出的 id 前缀变成 product 的 `68ba3402…`，尾段与 product 时序相邻。
   本地新生成的 id 不可能携带上游 uuid，故 `sw8` 注入/解析链路有效。
2. **但两侧打印串不逐字相同。** `DistributedTraceId.toString()` 原样返回其 `id`，而下游渲染的
   中间段与尾段是本地分量（`.128` vs `.144`）。⇒ **不能用 `%tid`/`TID` 做跨服务精确字符串 join**。
   交接文档 §10.4 第 3 条"两条服务日志都能取到相同非空 traceId"这一写法在 agent 9.7.0 上不成立，
   应改为：跨服务关联以 OAP 侧 segment refs 为准，日志侧 `TID` 作为**本段可检索 id** 使用。
   本轮未起 OAP，UI 侧关联链尚未复核（属下一步，不是已完成）。
3. 顺带暴露一个**与日志无关的应用侧缺陷**：`product → order` 的 Feign 响应反序列化失败
   `Type definition error: [simple type, class com.amz.result.Result]`，被
   `OrderServiceFeignClientFallbackFactory` 静默降级为 `source=estimated`——该链路每次都在走降级路径，
   值得单独立项。

### 10.4 复跑要点

```bash
# 权威 agent（宿主机可直连，容器内不行）
curl -o agent-9.7.0.tgz https://archive.apache.org/dist/skywalking/java-agent/9.7.0/apache-skywalking-java-agent-9.7.0.tgz
tar xzf agent-9.7.0.tgz && find skywalking-agent -name '._*' -delete
# 探针必须：SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false（不抢流量）
#          SW_AGENT_KEEP_TRACING=true（collector 不在时才有真 id）
# caller 侧指定下游：-Dspring.cloud.discovery.client.simple.instances.amz-service-order[0].uri=http://<callee>:8105
# 容器内绝对路径命令需 MSYS_NO_PATHCONV=1，否则 /app/... 会被重写成 C:/Program Files/Git/app/...
```

### 10.5 后续项4：k8s 采集端清单与 collector 回填

| 内容 | 说明 |
|---|---|
| 新增 `k8s/infra/skywalking.yaml` | `skywalking-oap`（Service + Deployment，11800 gRPC / 12800 REST，`SW_STORAGE=elasticsearch` 指向既有 `elasticsearch` 服务，TTL 收窄为 7/14/7 天防单节点 ES 无界增长）＋ `skywalking-ui`（`SW_OAP_ADDRESS` 指向 OAP REST） |
| 16 个应用 Deployment | 回填字面 `SW_AGENT_COLLECTOR_BACKEND_SERVICES=skywalking-oap.amz-erp.svc.cluster.local:11800`。不放 ConfigMap：`kubernetesReferencesResolveExactlyToDeclaredConfigAndSecretKeys` 要求 configData 与引用双向相等，且逐环境/逐服务的寻址值不该塞进共享配置。compose 侧无需该变量：镜像默认 `skywalking-oap:11800` 已与 compose 服务名一致 |
| 新契约 `k8sApplicationsPointAtTheDeclaredOapService` | 跨文件核对"应用写死的地址 == OAP Service 声明的名字与 grpc 端口"。这正是本轮反复遇到的失效类型：地址写错时 agent 连不上，日志静默变 `Ignored_Trace`，服务本身毫无报错 |
| 反向验证 | grpc 端口改 11999 → 1 红；某服务地址改成短名 `skywalking-oap:11800` → 1 红；还原 → 32/32 绿（identity 6 + placeholder 2 + deployment 10 + observability 14） |
| **证明级别** | 仅静态 YAML 解析 + 契约测试。**未在真实集群 apply 过**；且容器侧代理失效导致 `apache/skywalking-oap-server:10.1.0` / `skywalking-ui` / ES 镜像能否拉取、OAP 能否启动、ES 存储是否被接受均**未证**。操作前置：ES StatefulSet 必须先于 OAP 就绪 |

## 11. 验证命令（接手者可直接复跑）

```powershell
# 门禁（改动前 rc=1）
python tools/release/repository_hygiene.py --root .; $LASTEXITCODE

# 契约套件
mvn -o -B -pl amz-common test "-Dtest=AmzLogbackConfigContractTest,ProfileActivationContractTest"
mvn -o -B -pl amz-service/amz-service-spapi -am test `
  "-Dtest=ObservabilityExposureContractTest,DeploymentManifestContractTest,SkyWalkingIdentityContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"

# P0 复现（把工作区 logback 换回 TraceIdConverter 后即可看到启动崩）
mvn -o -B -pl amz-service/amz-service-message -am clean package -DskipTests
java -jar amz-service/amz-service-message/target/amz-service-message-1.0-SNAPSHOT.jar `
  --spring.profiles.active=mock --server.port=18889 `
  --spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false

# 有 agent 运行时证明（agent 直接取自现有镜像，不下载）
docker create --name swx amazonerp-user:verify; docker cp swx:/skywalking-agent ./skywalking-agent; docker rm swx
```
