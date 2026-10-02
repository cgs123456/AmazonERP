# 2026-10-02 P2-2 HTTP 层前后对比：report 端测到了，顺带炸出两个"master 起不来"

范围：接 `2026-10-02-p2-2-baseline-recheck.md`。那份文档 §5 承认"没量 JVM 与 HTTP 全链路"，
本轮把这块补上——**report 端点拿到了配对的 HTTP 前后数据**，另外两个端点在量的过程中
撞出了 master 自身的启动缺陷。

## 0. 结论先行

| 项 | 结果 |
|---|---|
| 利润汇总端点 HTTP 前后对比 | **完成**。同形状下 master 比下沉前 p50 快 1.66–1.76×；而**全年 20 万行 + 并发 10 这一档，下沉前直接 OOM（100 个请求只活 19 个）**，master 100/100 全过 |
| 回款概览端点（finance） | **未量到**：`amz-service-finance` 在 master 上**根本起不来**（§3 D1） |
| 健康度汇总端点（product） | **未量到**：`amz-service-product` 在 `MONGO_PASSWORD` 未设时**起不来**（§3 D2） |
| 是否停过在跑的演示栈 | **没有**。全程未 `compose down`，那 11 个 `amz-*` 容器至今 Up 44 小时未被打断 |

## 1. 为什么改成影子环境（以及为什么没停那套栈）

原计划是停掉 `amz-erp-p2-perf` 后接管重测。停之前查了两件事，两件都推翻了这个做法：

1. **那套栈跑的是 `SPRING_PROFILES_ACTIVE=mock`**，基线里的 `/report/dashboard/{shop}`
   在 mock 下由 `MockReportServiceImpl`（`@Profile("mock")`）应答，**不碰数据库**；
   `/order/list`、`/finance/profit/sku` 也不经过我这轮改的 mapper。停它重测等于量不到目标。
2. **演示库的数据规模不支持**：`amz_profit_detail` 每店最多 **556 行**且
   `COUNT(DISTINCT amazon_order_id) == COUNT(*)`（一单都不重复，去重成本为 0）；
   `amz_payment_collection` 每店 1,112 行同理；`amz_listing_health` **0 行**。
   效应存在于 10^5 量级（与 §2 of 上一份文档一致），556 行下前后只差几毫秒。

因此改成：**我自己的影子 MySQL + 两个 jar 直接对打**，不碰任何人在跑的东西。

| 环境项 | 值 |
|---|---|
| 数据库 | `docker run --rm` 的 `mysql:8.0`（**8.0.46**），`127.0.0.1:3408`，独立网络，测完即删 |
| 建表 | 由服务自己跑 Flyway（`spring.flyway` 在 boot 时执行）——不是手写简化表 |
| 数据 | `amz_profit_detail` **200,000** 行 / 44,445 个去重订单 / 5,000 个 ASIN；窄窗口取 7 天 = **3,835** 行 |
| 被测代码 | A 臂 = 当前 master（jar `902c8a7e…`）；B 臂 = `098271c~1`（下沉前，jar `6ef30f5b…`，`sumByAsin` 不存在已核对） |
| JVM | 两臂同参数 `-Xms512m -Xmx1024m -XX:+UseG1GC`（照抄那套栈 override 里的 JAVA_OPTS） |
| 客户端 | 自写 dependency-free runner，warmup 3 + c10×r{3,50,100}，**延迟统计含失败请求**（与参考基线同口径） |
| 鉴权 | 我自己 HS256 签的 JWT（`JwtUtil` 同 claim 形状），未取任何在跑栈的凭据 |

## 2. HTTP 前后对比（report `/report/v2/profit/summary/{shop}`）

| 形状 | 下沉前（B 臂） | master（A 臂） | 差异 |
|---|---|---|---|
| c1 × 3，全年 20 万行 | p50 **1,588 ms** / p95 1,743 / rps 0.62，3-3 成功 | p50 **904 ms** / p95 1,006 / rps 1.10，3-3 成功 | p50 **1.76×** |
| c10 × 50，7 天 3,835 行 | p50 **198 ms** / p95 292 / p99 317 / rps 47.1，50-50 成功 | p50 **119 ms** / p95 160 / p99 166 / rps 76.2，50-50 成功 | p50 1.66×、**吞吐 1.62×** |
| c10 × 100，全年 20 万行 | **19/100 成功**，81 个请求打到我的 30 s 超时；p50 显示 30,009 ms 是超时值不是延迟值 | **100/100 成功**，p50 2,345 / p95 3,473 / p99 3,887 ms，响应 879,510 B | **定性差别，不是倍数** |

第三行不是"慢"，是**崩**：B 臂日志里反复出现

```
java.sql.SQLException: Java heap space
Caused by: java.lang.OutOfMemoryError: Java heap space
    at ...GlobalExceptionHandler - 运行时异常
```

下沉前的实现把整店 20 万行明细读成实体再在 Java 里分组，**单请求堆占用与行数成正比**：
c1 时还能过（1,588 ms），c10 十份同时进堆就在 1 GiB 上限下 OOM。
这正好补上了上一份文档 §5 说"没量到 JVM 物化成本"的那块——它不是线性的百分比，
而是**在并发下会先于延迟变成可用性问题**。

口径正确性也顺带在 HTTP 响应里核到：master 返回 `totalOrders = 44,445`，
与库里 `COUNT(DISTINCT amazon_order_id)` 逐值相等（旧的按 ASIN 相加会是 5 位数偏大的错值），
说明 `countDistinctOrders` 这条改动真的走到了端到端，不只是单测里绿。

**这组数的限制（不要外推）**：直连服务端口，没有 gateway/Feign/JSON 网关层；
宿主 JDK 21 而镜像是 17-JRE；`spring.cloud.nacos.discovery.fail-fast=false` 是我加的
（注册中心被我故意指向死地址）；两臂各自独占 CPU 跑（测一臂时另一臂的 JVM 已停）。
所以 p50/p95 的**绝对值**不代表生产容器里的数，**前后比值与"并发下 OOM"这个定性结论**才是这份数据的用途。

## 3. 量的过程中炸出来的两个 master 启动缺陷（都不是我改出来的）

> **处置去向**：这两条已在 `2026-10-02-feign-and-mongo-startup-fixes.md` 修复并验证
> （含把两个服务真启动起来的 A/B、新静态契约闸与三次变异），以及"为什么不能把它们加进
> runtime-smoke 的腿"的实测理由。本节保留的是**发现时的原始现场**。

### D1 `amz-service-finance` 起不来

```
Caused by: java.lang.IllegalArgumentException:
    @RequestMapping annotation not allowed on @FeignClient interfaces
  → Error creating bean 'spApiFinanceClient' → feeDiscrepancyServiceImpl → feeDiscrepancyController
```

全仓枚举（22 个带 `@FeignClient` 的接口文件，逐文件核类级注解，不是 grep 一把抓）：

| 文件 | 类级注解 |
|---|---|
| `amz-service-finance/.../client/SpApiFinanceClient.java:29` | `@RequestMapping("/spapi/finance")` |
| `amz-service-finance/.../client/ProcurementCostClient.java:22` | `@RequestMapping("/procurement")` |
| `amz-service-product/.../client/SpapiFeedsClient.java:23` | `@RequestMapping("/spapi/feeds")` |

这三处来自 `b583994`（财务域闭环那次），过去能跑是因为当时的 Spring Cloud 容忍这种写法；
现在的版本把它变成启动期硬失败。**另注意**：`CryptoKeyProvisioningContractTest` 早已钉过
"异常文案让人设 `AMZ_CRYPTO_KEY`，但只有 spapi 的 yml 映射了它"这一族"文案与真实生效路径不一致"的坑——
D1/D2 属于同一大类（配置/注解层面的错，只有真启动才暴露）。

### D2 `amz-service-product` 在 `MONGO_PASSWORD` 未设时起不来

```
Failed to bind properties under 'spring.data.mongodb.password' to char[]:
    Property: spring.data.mongodb.password   Value: "${MONGO_PASSWORD:}"
    Origin: application.yml:48
    Reason: NullPointerException: Cannot invoke "java.util.Collection.toArray()" because "c" is null
```

空默认值绑到 `char[]` 直接把上下文炸掉。设了值就绕过（我验证时设的是无意义占位值）。
这决定了 D2 是**确定性缺陷**而非环境抖动：演示栈里它没暴露，是因为那套栈的环境文件给了这个变量。

### 两条都不是 CI 能拦住的

`mvn clean verify` 全绿（1704 例），因为**没有任何测试真的把 finance / product 的 Spring 上下文启起来**；
CI 的 `runtime-smoke` 那道闸按设计只起 **message + gateway 两条腿**（`CiWorkflowContractTest` 里
我自己钉的就是"至少两条腿"）。腿不够，这类"注解/配置级启动崩"就从闸底下滑过去了。

## 4. 我自己造成的一次环境渗漏（记下来避免再犯）

product 那次尝试因为没设 `REDIS_HOST`，JVM 去连了 `127.0.0.1:6379`——那是**在跑的那套栈的 Redis**。
它被 `WRONGPASS` 拒绝，没有写入，但这说明"我只用自己的容器"这句话在漏项上并不成立：
host-run 的服务会拿 yml 里的 `localhost` 默认值去找邻居。
（同时核对过：report 两臂的日志里 redis 只出现在 Spring Data 仓库扫描的 INFO 行
"Found 0 Redis repositories"，未发起连接，所以 §2 的数字没有被那套栈污染。）

另记一个样本：product 第一次启动失败的原因是
`Communications link failure` / `SocketException: Connection reset`（宿主↔容器 MySQL 的传输抖动），
与 `2026-10-01` 那份文档里 Flyway IT 的红是同一族现象，这是第 2 个样本。

## 5. 收尾

- 影子容器 `erp-rm-mysql`、网络 `erp-rm-net`、临时口令文件、临时 worktree `AmazonERP-rm-before`
  全部删除；`git worktree list` 回到原有 5 个；`tasklist` 里已无 java 进程；端口 18xxx 全部释放。
- 在跑的演示栈**从头到尾没被停过**，收尾复查 11 个 `amz-*` 容器仍 Up 44 小时、`amz-mysql`/`amz-redis` healthy。
- 复现件留在 `.qoder-cn/tmp/rm-shadow/`（`bench_rm.py`、`seed-*.sql`、`run-arm.sh`、6 份 bench JSON），
  不含任何口令。
