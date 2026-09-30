# P1 后续项 4+5 — 降级可归因 + 订单数口径修正

日期：2026-09-30
分支：master
基线：8d63246（CI runtime-smoke 启动门禁）

## 1. 这一轮解决什么

后续计划里我列的第 4、5 项：

- **项 4**：20 个 `*FallbackFactory` 的降级日志只有 `cause.getMessage()`，异常类型与栈全部丢失，
  调用点拿到的是空兜底值——降级与"对端确实没数据"在响应里不可区分，在日志里也不可归因。
- **项 5**：补一条真实的跨服务编解码往返测试，让
  `2026-09-30-p1-feign-result-decode-fix.md` §4 记录的"没有一条测试覆盖真实往返"这个盲区不再复现；
  顺带处理 review 里点出的 report 侧裸 `Map` 契约问题。

## 2. 先更正上一轮 review 的一处判断

上一轮我写的是"report 的 6 个裸 `Map` 方法把信封当 payload"。**实测不成立**：
`RealReportServiceImpl.extractData()`（第 334 行起）每次都先取 `resp.get("data")` 再读业务字段，
`extractOrders` 还兼容 `data` 是 `List` 与 `data.orders` 两种形状。信封没被当 payload。

真正的问题在 `fetchTotalOrders`，一条口径错 + 一条窗口错：

| 位置 | 当时的实现 | 实测事实 |
|---|---|---|
| 取值端点 | `GET /order/profit/summary/{shopId}` 的**返回行数** | `ProfitReportMapper.selectMonthlySummary` 是 `GROUP BY shop_id, sku, DATE_FORMAT(stat_date,'%Y-%m')`，**没有任何订单数量列**；行数 = (SKU × 月份) 分组数 |
| 时间窗 | 入参里没有 `days`，而 `totalSales` 是按 `days` 取的 | 分子（销售额）随 dateRange 变，分母（行数）不变 |
| 下游 | `report.setTotalOrders(...)`、`avgOrderValue = totalSales / totalOrders` | 仪表盘 KPI"订单数"与"客单价"同时失真 |
| 兜底 | `extractInt` 最后 `Integer.parseInt(data.toString())` | 对端返回字符串也会"算出一个数"，把契约漂移伪装成正常值 |

`RealReportServiceImpl` 第 376 行原本写着"以 SKU 数量近似订单数"——注释是诚实的，
但它近似的那个字段本来就不是订单数，而且没有任何测试守这个口径。

## 3. 改动

### 3.1 项 4：把异常本体交给 logger（20 个工厂，每个 1 行）

```java
- log.warn("Feign call to amz-service-order (report) degraded: cause={}", cause.getMessage());
+ log.warn("Feign call to amz-service-order (report) degraded", cause);
```

SLF4J 末位 Throwable 参数会打印类型 + 全栈。只改日志，**不改返回值**：
降级取值仍是各工厂自己的兜底，行为面零风险。

为什么不给兜底返回值加 `code=503` 之类的"可判别降级标记"（实测后放弃）：
`getDashboard` 的日志行是 `adSummary == null ? 0 : adSummary.size()`，
把 `Collections.emptyMap()` 换成 `{code:503, message:...}` 会让 `adSummaryKeys=2`，
"降级"反而看起来更像有数据——这正是"改动让红灯变绿"的反面，收益不成立。

### 3.2 项 5：订单数改取同窗口订单总数

`amz-service/amz-service-report/src/main/java/com/amz/service/impl/RealReportServiceImpl.java`

```java
private Integer fetchTotalOrders(Long shopId, int days) {
    Map<String, Object> resp = orderFeignClient.listOrders(shopId, days);
    Object data = extractData(resp);
    if (data instanceof Map) {
        Object total = ((Map<?, ?>) data).get("total");
        if (total instanceof Number) { return ((Number) total).intValue(); }
        log.warn("order 服务返回的 data 里没有可用的 total 字段，订单数按 0 处理：...");
        return 0;
    }
    log.warn("order 服务返回的 data 不是对象，订单数按 0 处理：...");
    ...
}
```

`/order/list` 的 `data.total` 来自 `pageResult.getTotal()`，即同窗口订单表分页总数，
与 `fetchTotalSales` 的 `days` 窗口一致；`days` 现在由 `getDashboard` 传入。

同一个方法里把"拿不到数"的三种形状分开记账，让降级在消费端也是可读的三条不同 WARN：

| 形状 | 含义 | 处理 |
|---|---|---|
| `resp` 为空 Map | fallback 工厂的 `Collections.emptyMap()`，根本没拿到响应 | WARN 点名"无响应体（fallback 兜底）" |
| `code != 200` | 对端业务失败（`Result.failure`） | WARN 带 code 与 message |
| `data` 里 `total` 缺失或非数字 | 契约漂移 | WARN 带 `data.keySet()` |

三者都返回 0，但日志不再同形——旧 `extractInt` 的 `Integer.parseInt(data.toString())`
会把第三种伪装成一个正常订单数。

连带删除：
- `extractInt(...)`：唯一的调用方就是 `fetchTotalOrders`，且它的 `parseInt(toString())` 兜底正是
  "把契约漂移伪装成数值"的那段逻辑，留着会被下一个 KPI 复用。
- `OrderServiceFeignClient.getProfitSummary(...)` 及其 fallback 覆写：该声明的语义（利润汇总行数）
  被当作订单数用过，留着就是下一个坑。端点本身（`/order/profit/summary/{shopId}`）没动，
  前端与网关路由不受影响。

### 3.3 项 5：真实跨服务编解码往返测试

新增 `amz-service/amz-service-product/src/test/java/com/amz/client/OrderServiceFeignDecodeIT.java`：
JDK 自带 `HttpServer` 起桩（**不引 WireMock**，离线构建可用、不加依赖），
用生产同款 `SpringDecoder` + `SpringMvcContract` 构造真实的 `OrderServiceFeignClient`，
桩侧按 order 服务的真实规则返回（`sizeTier` 分组 + `weight_g >= 入参` 取最小档）。

三个断言分别是：
1. 有费率时 `Result` 信封与 `data` 都要读得回来（`code`/`message`/`fulfillmentFee`/`weightG`）；
2. `data:null` 也必须解码成功——业务空值与调用失败不能靠异常区分；
3. 请求按 `@GetMapping` 路径与 `@RequestParam` 参数发出（桩里记录真实收到的 URI）。

`pom.xml` 的 surefire `<includes>` 显式含 `**/*IT.java`，所以它进 `mvn verify`，不是死文件。

## 4. 测试与变异验证

| 测试 | 正常态 | 注入历史故障 | 还原 |
|---|---|---|---|
| `OrderServiceFeignDecodeIT` | 3/3 绿 | 删 `@NoArgsConstructor` → **3 红**，报的就是当时那条 `feign.codec.DecodeException: Type definition error: [simple type, class com.amz.result.Result]` | 3/3 绿 |
| 同上 | — | 客户端 `@GetMapping` 改成 `/order/fees/lookup-mutated` → **恰 1 红**（`sendsContractedRequest`，消息里带出被改的路径） | 3/3 绿 |
| `DashboardOrderCountMetricContractTest`（新，2 个用例） | 2/2 绿 | 取 `data.get("count")` 而非 `total` → **1 红**；`days` 硬编码成 7 → **1 红**（`expected: <42> but was: <0>`，窗口漂移直接可见） | 2/2 绿 |
| `FeignDegradationLoggingContractTest`（新，仓库级） | 3/3 绿 | 一个工厂回退成 `cause.getMessage()`、另一个删掉日志 → **3 红**，报错分别点名 `...report...FallbackFactory.java:17`（只留 message）与 `...ai...AdServiceClientFallbackFactory.java`（没有异常本体） | 3/3 绿 |

契约测试的三条断言：工厂总数必须仍是 20（新增要纳入、删除要改断言）；每个文件都要有
`log.x("...", cause)`；不允许出现"只带 `cause.getMessage()` 的 log 语句"（按行判定，
`Result.failure("... " + cause.getMessage())` 这类**返回值**文案不受限）。

## 4.1 全仓终态（改动定稿后重跑）

| 验证 | 命令 | 结果 |
|---|---|---|
| 全仓 | `mvn -o -B clean verify -fae` | **BUILD SUCCESS，VERIFY_RC=0，19/19 模块 SUCCESS** |
| 测试量 | 同上（16 个有测试的模块汇总） | **1573 tests，0 failures，0 errors，5 skipped**（MySQL 依赖 IT 缺环境变量自动跳过） |
| 门禁 | `python tools/release/repository_hygiene.py --root .` | rc=0，findings=0 |
| 换行符 | 26 个改动/新增文件逐字节统计 | CRLF 计数全为 0（`.gitattributes` 要求 `*.java text eol=lf`） |

## 5. 已证 / 未证

已证：
- 20/20 工厂改造后全仓编译通过（各模块 test-compile + 单测在 verify 里）。
- 订单数新口径的取值链在单测里跑通（Mockito 桩给出与真实 `Result` 同形的信封）。
- 编解码往返在真实 Feign 栈（`SpringDecoder`）上跑通，且能抓住当年的故障形状。

未证（诚实边界）：
- **仪表盘 KPI 的真实数值没在运行环境复核**。演示库的 `amz_profit_report` / `amz_order`
  没被读取比对（这轮只做静态与单测级证明），所以"修好后订单数显示多少"没有实测数字，
  只有"取哪个字段"的契约证明。
- **响应体本身仍不可判别**"降级"与"业务空值"：两者都表现为 `data` 缺失。
  可判别性现在落在两处——fallback 工厂的 WARN（异常类型 + 全栈）与
  `fetchTotalOrders` 的三条不同 WARN。要在响应 JSON 里可判别需要改返回类型或加显式标记，
  属于接口层改造，本轮没做。
- 其余三个消费端聚合方法（`fetchTotalSales` / `fetchAdSummary` /
  `fetchDailyRevenueFrom*`）没接入这套分形状记账，只享受了 §3.1 的工厂级改进。
- `listOrders` 依赖 `@ShopScoped` + `FeignAuthRelayConfig` 透传用户 JWT；
  这条链路本轮没有新证明，但它与 `fetchShopSales`/`fetchDailyRevenueFromOrders`
  走的是同一个方法、同一个拦截器，不是新增风险面。

## 6. 环境陷阱（会误导下一个接手者）

`~/.m2/repository/com/amz/amz-common/1.0-SNAPSHOT/amz-common-1.0-SNAPSHOT.jar` 是 **9 月 28 日 19:08**
装的旧包，里面 `Result.class` 只有两个带参构造器、**没有默认构造器**（`javap` 实测）。
`mvn verify` 不 install，所以它一直是旧的。

后果：`mvn -pl amz-service/amz-service-product test`（**不带 `-am`**）会拿这个旧 jar 编译与运行，
`OrderServiceFeignDecodeIT` 全红，报的正是"没修之前的故障"——看起来像修复失效，实际是在测旧字节码。
带上 `-am` 后 3/3 绿。**接手者请一律加 `-am`**，或先 `mvn -pl amz-common install`。

## 7. 复跑命令

```bash
# 跨服务编解码往返（必须 -am，见 §6）
mvn -o -B -pl amz-service/amz-service-product -am test -Dtest='OrderServiceFeignDecodeIT'

# 订单数口径
mvn -o -B -pl amz-service/amz-service-report -am test -Dtest='DashboardOrderCountMetricContractTest'

# 降级日志契约（扫全仓 20 个工厂）
mvn -o -B -pl amz-common test -Dtest='FeignDegradationLoggingContractTest'

# 门禁
python tools/release/repository_hygiene.py --root .
```
