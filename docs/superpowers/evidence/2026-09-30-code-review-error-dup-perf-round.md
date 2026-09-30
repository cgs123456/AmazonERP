# 代码审查轮：错误 / 重复 / 性能三通道排查与逐项修复

日期：2026-09-30
分支：master
基线：ca561a4 → 本轮 7 个修复提交（末态 0c5bc5d）+ 自审批次

## 1. 做法

1. 清点结构：17 个 Java 模块、818 个主源文件、245 个测试文件；spapi 一家 21k 行占最大头。
2. 三条独立通道并行排查（错误处理与并发资源 / 重复代码与配置漂移 / 明显性能问题），
   **每条结论自己打开文件复核**，不采信只靠 grep 命中的判断。
3. 按 High→Medium→Low 排序逐项修；每修一处跑该模块测试；每个新守卫做变异验证。
4. 最后对自己的改动做双轴自审（见 §5）。

## 2. 已修 High（8 项，逐项带证据与变异验证）

| # | 缺陷（复核后的事实） | 影响 | 提交 |
|---|---|---|---|
| H1 | `amz-service-finance` 声明 fallbackFactory 但没有熔断装配开关也没有熔断依赖；`spring-cloud-starter-openfeign` 在无装配器时<b>静默忽略</b> fallback | 两个兜底工厂从未执行过，下游故障直接抛给调用方；上一轮刚加的"降级带异常本体"日志对 finance 永远打不出来 | `273d7c3` |
| H2 | finance 无 `feign.client.config` | 走缺省 connect 10s / read 60s，而其余 6 个有客户端的模块统一 3s/10s，60s 又超网关 30s | 同上 |
| H3 | 网关放行 `/user/refresh`，服务层 `BaseAuthInterceptor.WHITE_LIST` 没有（注释自称"与网关保持一致"） | **实测 401**（直连 user 服务）：access token 过期后无法续期，只能重新短信登录 | `0a1f372` |
| H4 | 审单规则所有评估故障被折成 `false`（正则超时/语法错/超长/未知操作符/数值解析失败/字段取不到值/执行异常） | fail-open：正则线程池只有 2 worker，批量审单时排队任务未开跑即撞 500ms → BLOCK 类规则整批静默绕过；`shipping_address` 返回空串使地址类规则<b>从未生效</b> | `f959495` |
| H5 | 消费端 `catch (IllegalStateException) → ack`，而该类型同时表达可重试故障 | **订单被永久丢弃**：`saveOrderInternal` 的"商品不存在或商品服务不可用"正是 product Feign 降级/超时的快速失败，同函数注释写着"由 MQ 稍后重试" —— 行为与意图相反 | `6c9b6ec` |
| H6 | report 三个 Feign 客户端的 fallback 返回 `emptyMap()` 且熔断开着 → 不抛异常 | 看板把下游不可用渲染成 `0 销售额 / 0 订单`，与"真的没卖"在 JSON 里同形，喂给经营决策 | `a92c8b9` |
| H7 | SP-API 退避重试在 4 个类各抄一份，只有 `OrdersClient.java:327` 有 5xx 分支（全仓 grep 实测唯一） | 503 抖动时订单侧自愈、库存/Feeds/网关路径把 5xx 当终态 → "订单好了但库存空了"这种半成功最难归因 | `24ccfe1` |
| H8 | `getInt(details, "fulfillable", 0)`：字段缺失/结构变化/解析失败都变 0 并 upsert 进 `available_quantity` | 补货引擎把"未知"当"卖光"，凭空生成补货建议；落到采购是真金白银的多余备货 | `0c5bc5d` |

守卫与变异：`FeignFallbackWiringContractTest`(4)、`AuthWhitelistParityContractTest`(2)、
`OrderAuditFailClosedTest`(8)、`OrderConsumerRoutingTest`(6)、`DashboardDegradationVisibilityTest`(4)、
`SpApiRetryParityContractTest`(3)、`InventoryQuantityUnknownTest`(4)。
逐个注入对应历史故障后红条数分别为 1 / 1 / 5 / 1(2 处分支各 1) / 2 / 1 / 1(两处独立分支各 1)，还原后全绿。

## 3. 已修 Medium（本轮已落地的两项）

| # | 缺陷 | 影响 | 提交 |
|---|---|---|---|
| M1 | `FbaShipmentServiceImpl.allocateCosts` 用 `mapToInt(FbaShipmentItem::getQuantity)`，且 `totalQty>0` 只校验总量 | 任一行数量为 null → 拆箱 NPE；单行为 0 → 末尾除零 `ArithmeticException`；两者让整个 `@Transactional` 分摊回滚，一条脏明细挡住整单成本入账（同文件 :115/:335 早就按 null 处理，唯独这里漏） | `f5e86ef` |
| M2 | `RealtimeProfitServiceImpl` 分摊 JSON 解析失败只 `log.debug` | 生产级别下等于瞒报：该条头程成本被丢掉，SKU 利润系统性高估；同方法上方对"明细截断"已是 WARN，级别不一致 | `f5e86ef` |

守卫：`FbaShipmentCostAllocationTest`（1 用例）。变异验证：拿掉单行数量守卫 → 用例直接复现当年那条
`NullPointerException: Cannot invoke "Integer.intValue()"`；还原 → 绿。

## 4. 复核后降级或推翻的判断

- 调查通道报"MQ 里 ISE 都是基础设施异常，属静默丢单"，我逐点核对后细化：
  6 个 ISE 抛出点里**只有 2 个**是真正的幂等/参数跳过，其余 3 个（商品服务不可用、凭证发送失败、
  订单 ID 未回填）确属可重试，1 个（用户 ID 为空）属永久不合法 —— 因此不是一刀切改成重投，
  而是三分路由（ack / DLQ / requeue）。
- 调查通道建议把 `CryptoUtil` 条件化即可解决启动阻塞：`CryptoTypeHandler` 是**静态**取
  `CryptoUtil.getInstance()`，条件化会把启动期故障挪到运行期（multiplatform 用得上却没 `crypto:` 块）。
  已把这条写进部署证据文档 §9 交给正在改该文件的工作树。
- "report 裸 Map 把信封当 payload"这条我上一轮的 review 判断，实测不成立（`extractData` 一直正确拆封），
  已在上一份文档更正。

## 5. 未修清单（仍然按严重度排序，保持原范围）

High（性能，需要各自的度量与影子库，不与本轮小修混做）：
- **P1** `ReplenishmentScheduler`：店铺 × SKU × ~6 次查询；`seasonal_index` / `promotion_calendar`
  是无条件的小配置表却被逐 SKU 重查（应整轮加载一次）。
- **P2** `PaymentCollectionServiceImpl.summary/rebuild`：`while(true)` 游标全表读入内存后在 Java 侧
  求和计数，且 `summary` 挂在 GET 请求路径上（一句 SQL `SUM/CASE ... GROUP BY status` 即可）。
- **P3** `ReportUpgradeServiceImpl`：profit_detail 全量读入后 `groupingBy + 8 路 reduce + sort`，
  日期条件可选 ⇒ 不传日期就是全店历史无上限。

Medium：`RealtimeProfitServiceImpl` 分摊解析失败仅 `log.debug` 跳过（利润系统性高估且不可见）；
`FbaShipmentServiceImpl` `mapToInt(getQuantity).sum()` 未防 null 与除 0（同类 458 行有校验，此处漏）；
`SettlementServiceImpl.awaitReportDone` 在请求线程 `Thread.sleep` 轮询 30s；
`GlobalExchangeRateService` 未知币种默认按 1:1；`logistics` 6 份逐字复制的 `requireShopAllowed`
（只有 1 份打越权日志）；汇率表 4 份 + `multiplatform` 一个无绑定的死配置块；
`UserContext.isShopAllowed` 宽松/严格按模块分裂（只有 ad 用 Strict）；
`toBigDecimal` 在不同文件里返回 null / ZERO 两种语义；9 个服务无 MyBatis-Plus 分页硬上限；
6 处前置通配 `LIKE '%x%'`。

Low：AI 侧金额用 `double` 累加后展示（同文件另一处又用 BigDecimal，口径不一）；
`NumberUtil` 注释承诺"并发不重复"但同毫秒 1/9000 碰撞；请求路径 `Pattern.compile` / `new ObjectMapper`；
~12 处循环单行 insert 应批量；4 份字节相同的 `RedisConfig`。

## 6. 自审（双轴）

**标准轴**：新增代码遵循仓库既有风格（契约测试为源码级断言、失败消息写"为什么"而不是复述"什么"）；
未引入新抽象层；删掉了因此变成死代码的 `extractBigDecimal`；
自审中改掉两处自己的问题：① `valuePresent(rule, expected)` 带一个不用的参数（无意义间接）已删；
② 移除 ISE-ack 兜底后，**畸形 JSON 会走通用 requeue 而无限重投**（畸形消息永不变好）——
已补 `JsonProcessingException → DLQ` 路由与用例。
遗留：`unevaluatedEntry` 与 alert 在"命中但动作名写错"时同时出现，属有意（两个字段语义不同），已在代码注释写明。

**规格轴**：目标要求的六步（理解结构 / 查三类问题 / 排序 / 逐项修 High-Medium-Low / 改后跑测试 / 自审）
中，"逐项修"已完成 High 全量与小部分 Medium，**Medium/Low 与三项性能 High 尚未完成**，
故本轮不声明达成目标，清单保留在 §5 继续推进。

## 7. 一个非本轮引入的环境性红灯

`AdvertisingApiRealClientContractTest.listKeywordsUsesV3ListEnvelope` 在一次全仓 `clean verify` 中报
`ConnectException`（打到本机 `HttpServer` 桩却 2.4s 连接失败）；单独连跑 2 次均 7/7 绿，
重跑全仓亦通过。判为负载相关间歇失败，疑点：每个用例新建 `HttpClient`，而 JDK 17 的 HttpClient
不可关闭（selector/executor 线程留存）。已记录，不当作已修，也不通过改断言让它变绿。

## 8. 复跑

```bash
mvn -o -B -pl amz-service/amz-service-finance -am test           # 熔断装配后的模块自证
mvn -o -B -pl amz-common test -Dtest='FeignFallbackWiringContractTest,AuthWhitelistParityContractTest'
mvn -o -B -pl amz-service/amz-service-order -am test             # 审单 fail-closed + MQ 路由
mvn -o -B -pl amz-service/amz-service-report -am test            # 降级可见性
mvn -o -B -pl amz-service/amz-service-spapi test                 # SP-API 重试与库存语义
mvn -o -B clean verify -fae                                      # 全仓闸口
```
