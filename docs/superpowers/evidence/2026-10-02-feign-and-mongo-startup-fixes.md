# 2026-10-02 修复：finance / product 在 master 上起不来（两个启动期硬失败）

起因：做 P2-2 HTTP 重测时要启这两个服务，结果它们**在 master 上根本起不来**，
而 `mvn clean verify` 与 CI 全绿。现象与动因见
`2026-10-02-p2-2-http-before-after.md` §3；本文记录修法与验证。

## 1. D1：`@FeignClient` 接口带类级 `@RequestMapping` → 上下文刷新直接失败

启动期硬失败（不是运行期）：

```
Caused by: java.lang.IllegalArgumentException:
    @RequestMapping annotation not allowed on @FeignClient interfaces
  -> bean 'spApiFinanceClient' -> feeDiscrepancyServiceImpl -> feeDiscrepancyController
```

修法是把前缀从类级注解搬到 `@FeignClient(path = ...)`——这是同一语义的受支持写法，
**不是把校验关掉**。全仓 22 个带 `@FeignClient` 的接口文件逐个核类级注解（不是 grep 一把抓），
命中 3 处，全部改掉：

| 文件 | 改动 |
|---|---|
| `amz-service-finance/.../client/SpApiFinanceClient.java` | `@RequestMapping("/spapi/finance")` → `path = "/spapi/finance"` |
| `amz-service-finance/.../client/ProcurementCostClient.java` | `@RequestMapping("/procurement")` → `path = "/procurement"` |
| `amz-service-product/.../client/SpapiFeedsClient.java` | `@RequestMapping("/spapi/feeds")` → `path = "/spapi/feeds"` |

各自的 `import ...RequestMapping` 随之删除（三个文件里该类注解都只出现 1 次，核过）。

**这一步最容易出的次生事故是"前缀丢了"**：服务能起、每次跨服务调用 404，再被 fallback 兜成空数据——
比原来的启动失败难发现得多。所以路径语义必须被单独钉住（见 §3、§4）。

## 2. D2：`spring.data.mongodb.password` 的空默认值把 product 炸在绑定阶段

```
Failed to bind properties under 'spring.data.mongodb.password' to char[]:
    Value: "${MONGO_PASSWORD:}"      Origin: application.yml:48
    Reason: NullPointerException: Cannot invoke "java.util.Collection.toArray()" because "c" is null
```

Boot 里这个属性是 **`char[]`**；`spring.data.redis.password` / `DB_PASSWORD` 那些同样是
`${X:}` 空默认值，但它们是 `String`，所以无害——**只有 mongo 这一族会炸**，
规则也因此只钉这一族，不泛化。

修法（`amz-service-product/src/main/resources/application.yml:48`）：去掉空默认值，改成必需的
`${MONGO_PASSWORD}`。依据是"供给方本来就一定有它"，不是"让开发环境炸"：

- `docker-compose.yml:434` 用 `${MONGO_PASSWORD:?MONGO_PASSWORD_required}`（compose 层就 fail-fast）；
- `k8s/secret.yaml` + `k8s/infra/mongodb-statefulset.yaml` 从 Secret 供；
- 离线开发走 `application-local.yml:27`，那里保留 `${MONGO_PASSWORD:123456}` 的非空默认值。

## 3. 修改不是靠推理验收的，是把它真启起来

| 检查 | 结果 |
|---|---|
| finance（修后，隔离中间件） | **BOOT OK**：`Started AmzServiceFinanceApplication in 23.011 seconds` |
| product（修后，`MONGO_PASSWORD` 有值） | **BOOT OK**：`Started AmzServiceProductApplication in 25.801 seconds` |
| product（修后，`MONGO_PASSWORD` 确实未设） | 报 **`Could not resolve placeholder 'MONGO_PASSWORD'`**（点名变量），不再是 NPE |
| D1 的因果 | 修前日志的致命项就是 `not allowed on @FeignClient`；修后同一环境里该错误不再出现且启动成功。未再重构建旧 jar 做严格 A/B（旧 jar 被覆盖），但那条异常文案与注解一一对应，不是"换个中间件就过"的那种模糊状态 |

第一次 `sed` 造"未设变量"场景时**改错了行**（`MONGO_HOST` 与 `MONGO_PASSWORD` 在同一行 export 上），
那一跑其实带着密码，属于无效验证；发现后重做才得到上面第三行的结果。

## 4. 新闸：`FeignAndConfigStartupContractTest`（amz-service-spapi/src/test/.../deploy）

三条规则，都是"编译与单测看不见、只有真启动才炸"这一族的静态替身：

1. 任何 `@FeignClient` 接口不得带类级 `@RequestMapping`；
2. 三个客户端必须仍通过 `path=` 声明各自前缀（钉住 §1 的次生事故）；
3. `mongodb:` 块内的 `password:` 不得用空默认值。

规则 1 与 3 都带**防空跑**约束：扫到的 Feign 文件数 `< 20` 或一个 `mongodb:` 块都找不到时直接失败，
否则扫描逻辑一旦失配，"零违规"就变成假的绿。

**变异验证**（注入→变红→还原→变绿，逐条归属）：

| 注入 | 期望 | 实测 |
|---|---|---|
| 删掉 `ProcurementCostClient` 的 `path =` | 规则 2 红 | `Tests run: 3, Failures: 1`，红在 `eachPrefixedClientDeclaresItsPathOnTheFeignClient` |
| 给 `SpApiFinanceClient` 塞回类级 `@RequestMapping` | 规则 1 红 | `Tests run: 3, Failures: 3`（同时触发规则 2，因为改动把 `path` 也换掉了，符合预期） |
| 把 yml 改回 `${MONGO_PASSWORD:}` | 规则 3 红 | 同上一次跑里 `mongoPasswordIsNeverBoundFromAnEmptyDefault` 红 |
| 全部还原 | 全绿 | `Tests run: 3, Failures: 0` ✅ |

## 5. 被我的改动"抓到"的既有测试（改的是写法，不是断言强度）

`ProcurementCostClientContractTest.costSummaryPathMatchesProcurementController` 原本用
`getAnnotation(RequestMapping.class)` 取前缀，改掉类级注解后它必然红。这个测试本来就是
"客户端路径要和采购服务控制器一致"的语义守卫——**留着它的意图，换它的取值来源**：
前缀改从 `@FeignClient.path()` 读，另外加一条 `assertNull(类级 @RequestMapping)`，
于是它同时也是一个缺陷守卫。等式 `path + method = "/procurement/batch/cost-summary/{shopId}"` 一字未松。

## 6. 为什么没把 finance / product 加进 runtime-smoke 的腿

我原本提议加腿，实测后**这个做法不成立**：finance 在没有 RabbitMQ 时启动即失败——

```
ApplicationContextException: Failed to start bean 'internalRabbitListenerEndpointRegistry'
```

而 `runtime-smoke` 那个 job 的设计恰恰是"**中间件全不可达也要干净启动**"（`--network none`）。
把 finance/product 塞进去会因为缺中间件而红，红的原因和要防的缺陷无关，等于制造常红的闸。
真要覆盖它们，需要的是一个带 mysql/redis/rabbitmq/nacos 的启动 job（另一件事、另一个量级的改动），
本轮做的是把这两族缺陷变成静态可检（§4）。

## 7. 我在验证过程中造成的两次环境渗漏（都无写入，但必须记）

影子 JVM 没设 `REDIS_HOST` / `RABBITMQ_HOST` 时，会按 yml 默认去连 `localhost:6379` /
`localhost:5672`——那两台是**在跑的那套演示栈的** Redis 与 RabbitMQ。两次都被认证拒绝
（Redis `WRONGPASS`、Rabbit `ACCESS_REFUSED`），没有任何状态改变；随后我把隔离 Redis（带密码）
与隔离 RabbitMQ 起起来并显式注入地址/凭据，`run-boot.sh` 里逐条写死中间件地址，
连 mongo 端口都用 `--spring.data.mongodb.port=27019` 指到无人监听的端口，避免撞那套栈的 27017。

教训一句话：**host-run 的服务会拿 yml 的 localhost 默认值去找邻居**，"我只用自己的容器"
这句话在每个没显式覆盖的连接项上都不成立。

## 8. 与在途工作的冲突（需要人看的）

`AmazonERP-p2-performance` 那个 worktree 里，`SpApiFinanceClient.java`、
`ProcurementCostClient.java`、`SpapiFeedsClient.java` **都有未提交改动**（即另一个会话也在修同一处）。
我这边没有读也没有复制他们的实现，本文的改法独立来自启动日志。
**合并时这三个文件必然冲突**，比较点就两条：前缀是否仍在（`path=`）、类级 `@RequestMapping` 是否已去掉。
他们那侧 `product/application.yml` 的 `${MONGO_PASSWORD:}` 当时仍是旧写法（我没碰他们的文件）。
