# traceId 可用性的运维前置条件（SkyWalking）

> 结论先行：**服务起来了不等于有 traceId。** 本项目日志里的 `%traceId` / JSON 字段 `TID`
> 由 SkyWalking java agent 在运行时注入，缺任一前置条件都会静默退化成占位值，
> 且**不会有任何启动报错**——这正是 P2-1 期间最难发现的一类问题。

## 1. 三态判读表

先看日志里的实际形状，再决定查哪里：

| 日志形状 | 含义 | 处置 |
|---|---|---|
| `[TID: N/A]`（带空格） | **agent 没挂上**。这是 `LogbackPatternConverter` 未增强时的编译期常量 | 见 §2 检查 agent 挂载 |
| `[TID:Ignored_Trace]` | agent 挂上了，但**到 OAP 的 gRPC 通道是断的**：agent 在 `!keep_tracing && DISCONNECT` 时直接不建 trace context | 见 §3 起采集端，或按 §4 权衡开关 |
| `[TID:N/A]`（无空格）且请求行仍是 `N/A` | agent 已增强、通道正常，但该日志行**不在 span 内**（启动日志、定时任务、线程池后台线程） | 正常现象，不是故障 |
| `[TID:<32位hex>.<段号>.<时间>]` | 健康 | — |

无空格与否本身就是判别依据：`TID: N/A`（带空格）＝未增强；`TID:N/A`＝已增强但无上下文。

## 2. 检查 agent 是否真的挂上

agent 挂载走独立变量 `SW_AGENT_OPTS`，**不再依赖 `JAVA_OPTS`**（历史缺陷：`-javaagent` 写在
`JAVA_OPTS` 默认值里，而 compose 传 `JAVA_OPTS=${JAVA_OPTS:-}`、k8s ConfigMap 也自带 `JAVA_OPTS`，
两处整体覆盖都会把 agent 摘掉）。

```bash
# 容器内 PID 1 的命令行必须含 -javaagent，且只出现一次
docker exec <容器> sh -c 'tr "\0" " " < /proc/1/cmdline'
# 期望：java -javaagent:/skywalking-agent/skywalking-agent.jar <其余 JVM 参数> -jar /app/app.jar
```

k8s 侧同理用 `kubectl exec <pod> -- cat /proc/1/cmdline`。要临时关闭 agent：把
`SW_AGENT_OPTS` 显式设为空，不要改 `JAVA_OPTS`。

## 3. 采集端必须在业务服务之前起来

### docker-compose（本机/演示）

```bash
docker compose up -d elasticsearch
docker compose up -d skywalking-oap      # SW_STORAGE=elasticsearch，必须等 ES 就绪
docker compose up -d skywalking-ui       # 默认宿主端口 8081
docker compose ps skywalking-oap          # OAP 首个 gRPC 端口 11800
```

应用服务侧的 collector 地址来自镜像默认 `SW_AGENT_COLLECTOR_BACKEND_SERVICES=skywalking-oap:11800`
（与 compose 服务名一致），无需逐个服务声明。

### k8s

```bash
kubectl apply -f k8s/infra/elasticsearch-statefulset.yaml
kubectl -n amz-erp wait --for=condition=Ready pod -l app=elasticsearch --timeout=300s
kubectl apply -f k8s/infra/skywalking.yaml      # OAP(Service+Deployment) + UI
kubectl -n amz-erp rollout status deployment/skywalking-oap
```

应用 Deployment 里逐服务写死的
`SW_AGENT_COLLECTOR_BACKEND_SERVICES=skywalking-oap.amz-erp.svc.cluster.local:11800`
与 `k8s/infra/skywalking.yaml` 的 Service 名/gRPC 端口由 `SkyWalkingIdentityContractTest` 双向核对，
改一边就会红。

## 4. OAP 故障期要不要仍生成 trace id

`SW_AGENT_KEEP_TRACING=true` 可让 agent 在通道断开时继续创建 trace context，日志因此保留可关联 id；
代价是仍在生成 segment 并写入内存缓冲（上报失败即丢弃），CPU/内存有额外开销，且 OAP 里查不到数据。

当前**默认保持 SkyWalking 语义（false）**：通道断开时不建 trace，日志显示 `TID:Ignored_Trace`。
若某环境需要"OAP 挂了也要能按 id 串日志"，在该环境的部署清单里显式加这个变量，不要全局改。

## 5. 已知边界：跨服务精确 join

实测（agent 9.7.0，`product` → Feign → `order`）：下游日志里的 id 会携带**上游 agent 的 uuid 段**，
但段号与时间是本地渲染，两侧字符串**不逐字相同**。因此：

- 不能把 `TID` 当成跨服务精确 join 的键；跨服务关联以 OAP 侧 segment refs 为准（UI 里按 trace 展开）。
- 日志侧 `TID` 的可靠用法是"定位本服务本次请求的所有行"，以及把其中的 uuid 段贴进 UI 检索起点。
- 若确实需要两侧完全一致的字符串，需要额外引入 MDC 注入层（属新功能，待 OAP 跑通后评估）。

`logstash/logstash.conf` 已把文本槽位（剥掉 `TID:` 前缀）与 JSON 的 `TID` 字段统一到 `traceId`。

## 6. 相关契约测试（改配置前先跑）

```bash
mvn -o -B -pl amz-common test -Dtest='AmzLogbackConfigContractTest'
mvn -o -B -pl amz-service/amz-service-spapi -am test \
  -Dtest='SkyWalkingIdentityContractTest,ObservabilityExposureContractTest,PlaceholderCoverageContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

前两条覆盖：logback 真实装配零 ERROR、降级常量、agent 挂载免疫于 `JAVA_OPTS`、
16 个服务身份唯一、collector 地址与 OAP 清单一致、logstash 消费端与生产端 pattern 对齐。
