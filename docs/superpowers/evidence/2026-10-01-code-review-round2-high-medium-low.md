# 2026-10-01 审查轮 2：High / Medium / Low 逐项落实记录

范围：本轮目标为「检查错误、重复代码和明显性能问题 → 按严重程度排序 → 逐项修复 → 跑相关测试
→ 失败则定位修复 → 最后自审」。所有结论以下列命令的实际输出为准，不采信推断。

闸口：`mvn -o -B clean verify -fae` → BUILD SUCCESS，19/19 模块，1646 tests / 0 failures / 0 errors；
`python tools/release/repository_hygiene.py --root .`（CI 用的 tracked 口径）→ findings=0。

---

## 1. High（5 项，全部落地）

| 编号 | 问题 | 修复 | 验证 |
|---|---|---|---|
| A1 | 报表降级被伪装成「0 销售额」 | `degradedSources` 显式随响应返回，降级来源可归因 | `DashboardDegradationVisibilityTest`（上一轮） |
| B3 | SP-API 退避重试 4 份复制，只有 `OrdersClient` 处理 5xx | `FbaInventoryClient` / `FeedsClient` / `SpApiGateway` 补齐同一分支 | `SpApiRetryParityContractTest`，模式要求 `1L << attempt` 同处分支内，避免匹配邻居 sleep 造成假绿 |
| C1 | 补货计算逐 SKU 重查配置表 | 6 参重载保持原语义，新增 8 参批量入口由调度器每轮取一次；混合引擎抽出 `buildSuggestion(histories)` 复用同一份 30 天历史 | `ReplenishmentConfigQueryHoistTest` 断言的是 **SQL 次数**（真实引擎 + mock mapper）。变异：把取值挪回 SKU 循环 → 3 项里恰该红的红了；把混合路径改回 `super.generateSuggestion` → 父类 mapper `never()` 断言变红 |
| C2 | 回款概览整表读入 Java 求和 | `PaymentCollectionMapper#aggregateSummaryByCurrency` 按币种 GROUP BY | 见 §3 的 MySQL 实跑与静态契约 |
| C4 | 报表利润 ASIN 汇总同样全量读入 | `ProfitDetailMapper#sumByAsin`（日期区间可选，下沉为 `<if>`） | 同上；该方法此前**零测试覆盖** |

C1 附带修掉一个我自己改动暴露的隐患：`HybridReplenishmentEngine` 覆写点必须挂在 8 参入口，
否则批量路径会退回纯规则（这是改到一半时编译期发现的，不是事后补的）。

## 2. Medium（7 项：6 落地，1 部分落地）

| 编号 | 问题 | 修复 | 验证 |
|---|---|---|---|
| M1 | `GlobalExchangeRateService` 未知币种 1:1 兜底：`toCny` 认 `strict-unknown` 开关，`getRate` 两者都不认且不留日志 | 三条路口（toCny / getRate / 交叉汇率）统一经 `rateOrPolicy`；按币种告警一次 + 命中计数可查 | `strict-unknown` 此前**零覆盖**。变异：把 `getRate` 还原成 `getOrDefault(...,ONE)` → 3 个用例变红；去掉「首次才告警」→ 收敛用例变红 |
| M2 | 结算报表轮询只有次数上限 | 加挂钟上限 `poll-deadline-ms`（默认 90s），超时信息带上实际耗时 | 新增 `protected nanoTime()` 取样缝，假时钟证明「单次查询即超期」时只查 1 次（次数上限还剩 9 次）。变异：删截断分支 → 该用例变红 |
| M3 | 物流 6 份私有 `requireShopAllowed`，5 份静默不记日志 | 收敛为 `LogisticsShopGuard`，拒绝一律 warn（userId/role/授权店铺/目标店铺，与 ad 模块 `AdTenantGuard` 同口径），22 处调用点不变 | 防再复制门禁 + 行为用例。变异：删 warn → 「拒绝必须可见」变红；重新塞回一份私有方法 → 门禁点名该文件变红 |
| M5 | `UserContext.isShopAllowed`「无店铺列表即放行」完全无声 | 行为不动（定时任务依赖），改为按来源告警一次 + `noListGrantHits` 计数 | 变异：摘掉 `recordNoListGrant()` → 2 个用例同时变红 |
| M4 | `platform.exchange-rates` 死配置段（JPY 0.045 与在用 0.046 分叉） | 删除；跨模块 `amz.exchange-rates` 逐值一致性 + 死段回归 → 构建期门禁 | 两处注入差异均实测变红；扫描按多文档 yml 处理并要求 ≥2 处声明，避免零命中空跑 |
| M7 | HTTP 侧 `?limit=` 无上限 | `AgentMemoryController → MemoryServiceImpl` 加 1..`PageRequest.MAX_SIZE` 校验（越界报错，不静默收敛）；顺带把原地 `Collections.reverse(mapper 返回值)` 改成倒序复制 | 变异：只留下限判断 → `limit=501` 用例变红。**清单原述被纠正**，见 §4 |
| M8 | 整店列表无上限读取 | `ListingMonitorServiceImpl` 5 个 `list*` 统一探边界多读一行、超限截断 + warn；`healthSummary` 刻意保持全量（否则整店口径数字变成抽样） | 断言含 wrapper 实际渲染出 `LIMIT 501`。纯单测需 `TableInfoHelper.initTableInfo` 才有 lambda 缓存，否则 MP 抛「找不到 lambda 缓存」 |

M6（6 份私有 `toBigDecimal`）按「错误」而非「性能」处理：逻辑统一进 `MapArgUtils`，
调用点保留一行委托且**不改语义** —— null→null 与 null→ZERO 两档各钉一条断言
（`MapArgUtilsBigDecimalTest`），因为把「未知」当「0」正是本轮 inventory 那条 High 的同类病。

## 3. 「等价」不是推断出来的

C2 / C4 的聚合语义下沉到 SQL 后，编译期与单测都覆盖不到 SQL 本身。做法：
在**隔离的** MySQL 8.4 容器（`--rm`、独立端口、不挂卷、跑完即删）里，用项目自己的建表 DDL
构造 fixture，实跑聚合再与逐行 Java 语义比对。

C2 fixture 覆盖：NULL 金额、NULL 币种、`SHORTFALL` 状态但 `shortfall` 为 NULL、跨店铺行、
无边界查询。上卷结果 9 / 156.96 / 102.70 / 已结 4 笔 39.46 / 短款 2 笔 1.10 / 已知短款行 2，
与手算逐行值逐项相等，跨店行未参与。
C4 fixture 另含同订单同 ASIN 重复行与跨年份行，分组值与日期区间过滤均逐值相等。

**因此被锁住的是"这一份数据上等价"，不是一般性证明**：真实数据里若出现 DDL 未约束的
组合（例如 `status` 大小写混杂、`report_date` 为 NULL），仍需 SQL 侧继续处理。
这也是本轮坚持"不静默收敛"的原因。

## 4. 被推翻或降级的清单条目（带证据）

- 「9 个服务缺 MyBatis-Plus 分页硬上限」—— 不成立。全仓只有 `order` 用 `selectPage`，
  且 `MybatisPlusConfig` 已设 `maxLimit(500)`；其余走 keyset 游标 + `PageRequest`（MAX_SIZE=500，
  越界报错）。真正无上限的是 `ListingMonitorServiceImpl` 的 5 个 `list*` 与 ai 的 `?limit=`，都已修。
- 「6 处前置通配 LIKE 需要改成前缀匹配」—— 多数不成立。6 处全部先 `eq(shop_id)` 收敛再 LIKE，
  且 5 处分页；改前缀会砍掉「按订单号尾号搜索」这类真实用法，收益接近 0。
- 「5 处 `new ObjectMapper()` 是每次调用重建」—— 不成立。6 处都是实例/静态字段（单例 Bean 一份），
  不是热点。剩下的只是「各自配置不一致」的弱一致性问题，注入 Spring 的 ObjectMapper 反而会
  连带改掉 `FAIL_ON_UNKNOWN_PROPERTIES` 默认值，属于用未验证的行为换整洁，本轮不做。
- 「`double` 金额渗进展示文本」—— 在 `ErpToolExecutor` 未成立：面向用户/LLM 的字符串已经是
  `%.2f` / `%.1f%%` 格式化。机器可读 payload 里保留 double 是 AI 建议值的表示选择，
  改成 BigDecimal 会改变 JSON 数字标度、影响下游解析，不是 Low 该换的。

推翻的条目保留在本文件里而不是删掉，是为了下一轮不必再花同样的力气去复核。

## 5. 刻意未做（含理由）

| 项 | 为什么不做 | 恢复条件 |
|---|---|---|
| L2 循环单条 insert | 语义已按用户选定实现（分块批量 + 失败块退回逐条），并先落到最大的一条路径：结算明细落库 `SettlementServiceImpl.ingestParsedRows`。逐点核查其余 14 处的量级后判定不动：物流轨迹是"每货件几十条事件"且已按指纹去重，批量省的是十几次往返；订单/回款/平台商品三类要么是 upsert（要的是 `ON DUPLICATE KEY UPDATE` 而不是多值 insert），要么已经有自己的幂等层。`PaymentCollectionServiceImpl.rebuild` 同轮一并做完：整店台账预读 + 逐单 select/insert/update 换成一条 ODKU 批量幂等写（短款不进更新列表、状态用旧行短款判定），见 §3 末。 | 见 `BatchInsertsTest` + `SettlementBatchIngestTest`；rebuild 的批量 upsert 待做 |
| L5 审单正则重复编译 | 已落地：`compiledPattern` 走访问序 LRU（上限 64）。规则正则由**店铺管理员输入**，无界缓存等于给一条内存放大通道，所以同时钉住"有界""失败不进缓存"。 | 见 `AuditPatternCacheTest`；把上限改回无界后对应用例变红（实测 200 vs 64） |
| L3 4 份 `RedisConfig` 合并 | 合并 = 改变 16 个服务的 Bean 装配顺序，本机无法逐个启动验证（Nacos/MySQL 属另一套在跑的栈，不应写入）。用未验证的装配变更换一个 Low 的整洁不值 | 能跑通 4 个服务的启动冒烟后上收为 amz-common 自动配置，并删除 `RedisConfigDuplicationGateTest`；过渡期该门禁保证不会出现"改漏一份"的静默分叉 |
| `healthSummary` / `PaymentCollectionServiceImpl.rebuild` 下沉 SQL | 前者是整店口径聚合（同 C2 的同类问题，但改法要新增 mapper 方法与契约测试），后者要按交易类型在 SQL 里做 CASE 归类，而 MySQL 字符串比较的大小写敏感性依赖列 collation —— 换引擎/换排序规则就可能悄悄改变归类结果 | 需要一次带真实 collation 前提的等价性验证，单独排期 |
| 报表 `orderCount` / `totalOrders` 其实是明细行数 | `amz_profit_detail` 没有 (shop_id, amazon_order_id, asin) 唯一键，`COUNT(1)` ≠ 订单数（fixture 实测 3 行 / 2 个订单）。本轮 C4 只搬位置不改口径，因为改这个数会同时改掉报表数字 | 需要产品侧确认字段含义后，连同前端文案一起改 |

## 6. 自审（双轴）

标准轴：逐提交回看 diff。**回看抓到一个我本轮引入的真缺陷**：
M8 里 `healthSummary` 为了"保持全量口径"改成调用私有 `loadHealth(shopId, null)`，
但那个方法当时已经无条件带上 `LIMIT 501` —— 于是整店汇总其实被截断了，
注释却说它没被截断。对应的用例之所以是绿的，是因为 mock 无论 wrapper 怎么写都返回同样的行数，
属于典型的假绿。修法：
1. `loadHealth(..., boolean cap)` 把两条路口分开，汇总传 `false`；
2. 用例改为**按 wrapper 是否含 LIMIT 分别应答**，断言汇总 total=600 而列表读仍为 500；
3. 把修复前的缺陷重新注入一次，确认新用例这次会变红（此前它不会）。

另外补了一条调用方契约注释（`ReplenishmentEngine` 8 参入口不再自查，
传错 category 只会静默算错补货量），并把证据文档放回项目实际使用的
`docs/superpowers/evidence/`（第一次写到了 `docs/evidence/`）。

规格轴：本轮 spec 即 §1/§2 清单 —— High 5/5、Medium 7/7（M6 按"错误/重复"处理，
M7 与 M8 修正了清单原述），Low 5 项处理（L3 门禁 + L5 有界缓存 + L2 按选定语义落地到结算落库，L1/L4 经核查不成立）。
无遗漏项，无越界新增功能。

## 7. 提交清单

`c66f115` C1 补货 · `a07ea08` C2 回款 · `098271c` C4 利润 · `142c599` M1 汇率 ·
`a051cab` M2 轮询 · `8a1a1f2` M3 守卫 · `bc86569` M5 宽松放行 · `0797e8e` M4 死配置 ·
`88bcc59` M7 limit · `c161ed3` M8 读上限 · `42107a3` M6 toBigDecimal · `ee6d21d` L3 门禁

### rebuild 的批量幂等写（同轮补做）
`upsertBatch` 的语句不是手写副本，而是**从 mapper 源码里把模板逐列代入**渲染出来，
再丢进隔离 MySQL 8.0.46 实跑（部署用的就是这个 major）；同一脚本在 8.4 上跑过一遍，
两边逐字段一致。三种情形：
`ORD-4` 有正短款 → 金额更新、`shortfall=1.10` 保留、状态仍 `SHORTFALL`；
`ORD-5` 短款为 0 → 保留 0、状态跟随新基础值变 `IN_TRANSIT`；
`ORD-8` 库里没有 → 插入且 `shortfall` 为 NULL。
两条不可退让的规则另有静态契约守着（更新列表里不得出现 `shortfall = new.shortfall`、
必须保留 `IF(shortfall IS NOT NULL AND shortfall > 0, 'SHORTFALL', new.status)`）。

踩到的两个坑一并记下：① 分页夹具第二页没把"探边界多读的那一行"带回来，
测出来像生产丢数据，实际是夹具替 DB 少给了一行；② 抽取 SQL 的脚本 assert 失败后
没写文件，而 `mysql < 旧文件` 照样执行了上一版内容并报语法错 ——
凡是"写文件再执行"的验证，先删目标文件或校验字节数，否则验的是旧版本。
