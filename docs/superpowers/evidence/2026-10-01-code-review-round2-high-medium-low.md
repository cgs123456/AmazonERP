# 2026-10-01 审查轮 2：High / Medium / Low 逐项落实记录

范围：本轮目标为「检查错误、重复代码和明显性能问题 → 按严重程度排序 → 逐项修复 → 跑相关测试
→ 失败则定位修复 → 最后自审」。所有结论以下列命令的实际输出为准，不采信推断。

闸口（第二轮自审后重跑，见 §8）：`mvn -o -B clean verify -fae` → BUILD SUCCESS，18 个模块全过，
**1682 tests / 0 failures / 0 errors / 11 skipped**（模块汇总行与 268 条分类行各自累加得同一组数字，
两种解析一致才敢引这个数）；`python tools/release/repository_hygiene.py --root .` → findings=0；
`python -m unittest discover -s tools/release -t .` → OK。
两个 MySQL IT 另在隔离容器（MySQL 8.4.11，`--rm`、不挂卷、独立端口 3399）实跑 6 例全绿。
（本文件顶栏此前一度是假的：文档自己触发了扫描规则，findings 从 0 变 1 —— 见 §8 ①。）

---

## 1. High（5 项，全部落地）

| 编号 | 问题 | 修复 | 验证 |
|---|---|---|---|
| A1 | 报表降级被伪装成「0 销售额」 | `degradedSources` 显式随响应返回，降级来源可归因 | `DashboardDegradationVisibilityTest`（**上一轮提交**，本轮只复核） |
| B3 | SP-API 退避重试 4 份复制，只有 `OrdersClient` 处理 5xx | `FbaInventoryClient` / `FeedsClient` / `SpApiGateway` 补齐同一分支 | `SpApiRetryParityContractTest`（**上一轮提交** `24ccfe1`，本轮只复核），模式要求 `1L << attempt` 同处分支内，避免匹配邻居 sleep 造成假绿 |
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
- 「6 处前置通配 LIKE 需要改成前缀匹配」—— 多数不成立。**复测口径**：全仓 `.like(` 共 9 个谓词、
  分布在 5 个调用点（`SearchTermServiceImpl:106`、`OrderController:105`、`SupplierServiceImpl:108-110`、
  `ListingMonitorServiceImpl:256`、`ProductServiceImpl:167-169`），清单原写的"6 处"是数错了。
  这 9 个谓词全部先 `eq(shop_id)` 收敛再 LIKE，且 5 处里有 4 处带分页；改前缀会砍掉
  「按订单号尾号搜索」这类真实用法，收益接近 0。（`ListingMonitorServiceImpl:256` 那处现在还在
  单读上限之内，M8 已经把它一起管住了。）
- 「5 处 `new ObjectMapper()` 是每次调用重建」—— 不成立。6 处都是实例/静态字段（单例 Bean 一份），
  不是热点。剩下的只是「各自配置不一致」的弱一致性问题，注入 Spring 的 ObjectMapper 反而会
  连带改掉 `FAIL_ON_UNKNOWN_PROPERTIES` 默认值，属于用未验证的行为换整洁，本轮不做。
- 「`double` 金额渗进展示文本」—— 在 `ErpToolExecutor` 未成立：面向用户/LLM 的字符串已经是
  `%.2f` / `%.1f%%` 格式化。机器可读 payload 里保留 double 是 AI 建议值的表示选择，
  改成 BigDecimal 会改变 JSON 数字标度、影响下游解析，不是 Low 该换的。

推翻的条目保留在本文件里而不是删掉，是为了下一轮不必再花同样的力气去复核。

## 5. Low 项处置（已落地 / 刻意未做，含理由）

| 项 | 状态与说明 | 恢复条件 / 验证 |
|---|---|---|
| L2 循环单条 insert | **已落地两处热路径**：通用流程 `BatchInserts`（分块批量 + 失败块退回逐条）落到结算明细落库 `SettlementServiceImpl.ingestParsedRows`，以及 `PaymentCollectionServiceImpl.rebuild`（整店台账预读 + 逐单 select/insert/update 换成一条 ODKU 批量幂等写；短款不进更新列表、状态用旧行短款判定）。其余 13 处逐点核过量级后判定不动：物流轨迹是"每货件几十条事件"且已按指纹去重，批量省的是十几次往返；订单/回款/平台商品要么是 upsert 语义、要么已有自己的幂等层。 | `BatchInsertsTest` + `SettlementBatchIngestTest` + `PaymentCollectionServiceImplTest`；MySQL 侧由两个 IT 常驻 CI（见 §7 末） |
| L5 审单正则重复编译 | **已落地**：`compiledPattern` 走访问序 LRU（上限 64）。规则正则由**店铺管理员输入**，无界缓存等于给一条内存放大通道，所以同时钉住"有界""失败不进缓存""淘汰按访问序"。 | `AuditPatternCacheTest`：上限改回无界、或把淘汰改成插入序（FIFO），对应用例分别变红 |
| L3 4 份 `RedisConfig` 合并 | **刻意未做**（配方已收成一份，见文末追加）：合并 = 改变 16 个服务的 Bean 装配顺序，本机无法逐个启动验证（Nacos/MySQL 属另一套在跑的栈，不应写入）。用未验证的装配变更换一个 Low 的整洁不值 | 能跑通 4 个服务的启动冒烟后上收为 amz-common 自动配置，并删除 `RedisConfigDuplicationGateTest`；过渡期该门禁保证不会出现"改漏一份"的静默分叉 |
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

本轮 = `origin/master..HEAD` 共 19 个提交（截至第一次自审时 12 个，文末 §8 又补了 7 个）：

`c66f115` C1 补货 · `a07ea08` C2 回款 · `098271c` C4 利润 · `142c599` M1 汇率 ·
`a051cab` M2 轮询 · `8a1a1f2` M3 守卫 · `bc86569` M5 宽松放行 · `0797e8e` M4 死配置 ·
`88bcc59` M7 limit · `c161ed3` M8 读上限 · `42107a3` M6 toBigDecimal · `ee6d21d` L3 门禁 ·
`d5f0df3` 自审抓回 M8 引入的截断缺陷 · `9c0c224` L5 有界 LRU · `b9a0e23` L2 通用分块流程 ·
`e59ea38` L2 结算落库接入 · `1a1d84d` L2 回款 rebuild ODKU · `8c6841c` 两个 IT 接进 CI ·
`48c6ac9` Redis 配方收成一份

A1 / B3 两条 High 属上一轮提交（`a92c8b9` / `24ccfe1`，已在 origin/master 上），本轮只复核不改。
按用户决定，这 19 个提交暂不推送。

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

### 批量写不只在 MySQL 里跑过 SQL 文本，也真的经过 MyBatis 执行
补了两个 env 门控 IT（不设变量整类跳过，CI 不受影响），在 MySQL 8.0.46 上真跑：
- `PaymentCollectionUpsertMySqlIT`（3 例）：用 standalone MyBatis 配置调用 `upsertBatch` 本身，
  断言库里最终状态 —— 这一步抓的是"模板列名/属性名对不上"这类手工代入 SQL 发现不了的问题。
- `SettlementBatchIngestMySqlIT`（3 例）：`Db.saveBatch(500 行)` 返回成功且库里 `COUNT(1)=500`
  —— 注意这是**结果断言**，不是"确实只发了一条语句"的证明（要证那条得数语句，本 IT 没数）；
  重复 `row_key` 会让整批抛出（→ "退回逐条"这条路会被走到，不是设计想象）；
  抛出形态交给**生产用的那一份** `BatchInserts.isDuplicateKey` 判定，能认出
  `SQLIntegrityConstraintViolationException`，所以"冲突算跳过"在真实驱动下成立，
  不会把已在库里的行谎报成失败。（自审时这一条原来是 IT 里复制的一份判定 —— 复制品测不出
  生产那份被改坏，已改成直接调用生产实现。）

两个 IT 已接进 CI 常驻执行（`.github/workflows/ci.yml` 的 mysql service job 里加了
`COLLECTION_UPSERT_IT_*` / `SETTLEMENT_BATCH_IT_*`），不是"写完就永久跳过"的装饰性测试：
本地就用 CI 那个形态的 URL 跑过（指向一个**不存在**的 `amz_ad_it` 库 + 完整驱动参数），
6 例全绿 —— 建库连接走 server-only URL，业务连接才带库名，这一条也是那次跑出来的。

过程中被编译器抓到两处：Java 字符串里写正则 `\?` 被 python 生成成了 `\?`→`\?`（非法转义），
以及消息串里嵌 ASCII 引号（本轮第三次踩）。最后改成按 `?` 直接切串，不用正则。

## 8. 第二轮整体自审（对全部 19 个提交重跑双轴）

前一次的自审是**逐改动**做的；这一轮改成把 `origin/master..HEAD` 整体当输入，标准轴与规格轴各查一遍。
抓到的东西分四类，全部已修：

**① 闸口自己变红了（最严重）。** 本文件末段在描述 `secret-like-assignment` 豁免时，
把那个字段的赋值形态原样写进了文档，而扫描器同样扫文档 —— `--root .` 从 findings=0 变成 1，
CI 的 hygiene job 会直接红。这条恰好是本仓反复踩的形状：**证据文档里"引用被测对象的字面形态"
会让文档自己成为被拦下的输入**。改法是描述而不引用（见文末）。改完实测 findings=0。

**② 兜底路径把原因扔了。** `BatchInserts` 在 `catch (Exception batchError)` 里只做"退回逐条"，
异常既不入日志也不进结果：逐条重试会让每一行都写成功，于是"批量这条路在当前 schema 上永远走不通"
（列名改了、`<foreach>` 写错）恰好被它的兜底掩盖成一次全绿。修法：`Result` 增加 `getBatchErrors()`
（每块一条，带最里层原因与块行数），两个调用方把它并进 warn。
新用例断言的正是"结果数字全对，但原因仍在"：`batchErrors` 里有 `Unknown column 'row_ky'`。

**③ 测试测的不是生产。** `SettlementBatchIngestMySqlIT` 复制了一份 `isDuplicateKey` 去打真实异常 ——
生产那份被改坏或删掉，IT 照样绿（它测的是自己的副本）。判定规则收进 `BatchInserts.isDuplicateKey`
（唯一实现），生产与 IT 同时调用它，另加 `BatchInsertsTest` 覆盖三种形状 + **一个负例**
（语法错误不许被认成"库里已有这行"，否则真实失败会被静默记成跳过）。
同类问题另外查出三处并逐个补强：
- `LogisticsShopGuardTest` 断言 `message.contains("2")`，而 `userId=42` 里就带 2 ——
  把「目标店铺=2」整段删掉照样绿。改成逐字段 `键=值` 断言。
- `AuditPatternCacheTest` 用 200 条不同正则 + `size()==64` 只钉住了"有界"；纯插入序（FIFO）
  同样满足这两个数字，所以"访问序 LRU"这个说法当时没有被任何用例保护。
  先实测确认 `computeIfAbsent` 命中会刷新访问序（JDK 21：`[f1,f2,f3,hot]` → 塞一条新的后 `hot` 仍在），
  再加"热用 64 轮不被挤掉"的用例 —— 这条正是 FIFO 会红的判别用例。
- `ListingMonitorReadCapTest` 声称"5 个入口都收口"，实际只测了 `listHealth`；
  其余 4 个入口的 `.last(limitClause())` 若被漏掉，内存里的 `capRead` 依然把返回截到 500 行，
  调用方看到的数字完全正常，原始缺陷（读几年快照只为每 ASIN 取最新一条）却回来了。
  现在 4 个入口逐个断言"SQL 里真的带 LIMIT"，且 mock 按 wrapper 有无 LIMIT 分别应答。
  另把"未达上限"那条用例补成真的断言"不告警 + 不复制"，而不是只看 size。

**④ 文档/口径与代码不一致。** `unknownCurrencyHits` 的字段说明写"被 1:1 兜底命中过"，
但计数发生在严格模式抛错之前 —— 被拒绝的币种根本没有失真金额，混进来会让这个指标失去
"有没有钱算错了"的含义。改为只在真正兜底时计数，并补断言（严格模式三次拒绝后仍应为 0）。
其余是文档侧：§1 把 A1/B3 标回上一轮（它们已在 origin/master 上）、§4 的"6 处 LIKE"复测实为
9 个谓词 / 5 个调用点、§5 里 L2/L5 两行原本挂在"刻意未做"下却已落地、§7 少列了 7 个提交、
`Db.saveBatch` 那条 IT 断言的是行数而非语句数（原文写成"确实一条批落库"说过头了）。

**⑤ 两处行为变更当时没记进清单（范围外改动，如实登记）**：
`rebuild` 的失败语义从"首个失败即中断"改为"全部试完后汇总抛出"（配合分块批量，否则一块失败就丢掉整批归属）；
`SettlementServiceImpl.batchWriter` 是留给单测的包级替换缝（纯单测没有 Spring 上下文，
不给替换点就只能测到兜底那条路）。两者都不是"性能修复"的必需项，但都在改动路径上。

**⑥ IT 库名撞车。** 两个新 IT 沿用了 `*_fwit` 命名，而 `AllModulesFlywayMySqlIT` 正是用
`<业务库>_fwit` 对每个模块 DROP + CREATE —— 同一个 CI step 里两边会互相删表，且
`assertTrue(SCHEMA.endsWith("_fwit"))` 是对本地字面量自证，永远不会红。
改成各自独立库（`amz_finance_collection_it` / `amz_finance_batch_it`），护栏换成
`ItSchemaGuard`：既要求 `*_it`、又明确禁止 `*_fwit`，还拿运行期 URL 里的库名比对，
"IT 直接打在业务库上"这条路也被它挡着。

（另：`hygiene-allowlist.json` 里那两个 IT 的内容锁随本次修改失效，扫描立刻点名 ——
锁是有牙的，按流程刷新哈希而不是放宽规则。）

### 8.1 这七条新断言逐条做过"重新注入缺陷 → 变红 → 还原"

| 注入的缺陷 | 变红的用例 | 实际报错 |
|---|---|---|
| `BatchInserts` 丢掉批量异常原因 | `batchFailureCauseSurvivesTheFallback` | expected `<2>` but was `<0>` |
| `isDuplicateKey` 一律返回 true | `duplicateKeyPredicateRecognisesWrappedAndMessageOnlyShapes` | expected `<false>` but was `<true>` |
| 兜底计数挪回严格模式抛错之前 | `strictModeRejectsUnknownOnAllPaths` | expected `<0>` but was `<3>` |
| `LinkedHashMap` 访问序改插入序（FIFO） | `hotPatternSurvivesStreamOfNewPatterns` | 第 0 轮 `hot-(\d+)` 拿到新实例 |
| BuyBox 列表漏掉 `.last(limitClause())` | `everyListEntrySendsTheLimitToTheDatabase` | 点名 "BuyBox 快照 没把上限交给数据库" |
| 越权 warn 去掉 `目标店铺={}` | `crossShopAccessIsDeniedAndLogged` | expected `<true>` but was `<false>` |
| IT 库名改回 `amz_finance_fwit` | `SettlementBatchIngestMySqlIT.bootstrap` | 护栏在 `ItSchemaGuard:23` 拒绝 |

顺带说一句诚实的：`amz-service-logistics` 那轮 BUILD FAILURE 就是我自己漏还原一处注入造成的，
被上一行的用例抓住 —— 这条记录里的"还原"不是凭记忆说的，是靠重跑闸口证明的。

## 追加（同一轮）：RedisConfig 那 4 份副本
"合并成一份 amz-common 自动配置"这条路量过之后**主动放弃**：amz-common 就在扫描根包
`com.amz` 下，公共 `@Configuration` 会被 16 个服务全部扫到 —— 网关（WebFlux）与
procurement / spapi（现在用 Boot 默认模板）会被凭空加上或改掉 `redisTemplate` bean，
换掉 value 序列化器还会让既有缓存读不出来。那不是去重，是跨服务改 bean 拓扑，
而本环境无法逐个启动验证。

改做行为等价的另一半：**把重复的配方收进 `com.amz.redis.RedisTemplates`（唯一实现）**，
`@Bean redisTemplate` 仍留在 4 个服务里，bean 名/类型/生效范围逐字节不变。
门禁随之升级：副本里不得重新实现序列化配方（改成 JdkSerialization 后实测变红）、
副本之间不得分叉；`RedisTemplatesTest` 4 例钉住 key=String、value=JSON 且带类型往返。

两个新 IT 各有一个从环境变量取密码的 `PASSWORD` 字段（默认值为空），触发了
`secret-like-assignment`；与既有 2 个 MySQL IT 同形，走仓库既定的内容固定 allowlist 入口，
而不是为了绕开扫描器改字段名：少一个豁免项不是安全改进，扫描器读不懂的变量名才是债。
（注意：本段刻意不写出 `字段 = System.getenv(...)` 的字面形态——文档自己也会被扫描，
第一版就是这么把绿闸口改红的。）豁免是否仍然有效已实测：
给被固定内容的文件加一个换行 → findings=1 → 还原 → 0。
