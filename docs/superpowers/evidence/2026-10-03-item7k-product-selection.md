# 7k — 选品域：停止把模拟结论写到默认店铺 1，两条造数端点加 mock 门禁

日期：2026-10-03。切片背景：清点剩 43 条用户可见候选（见 v10 快照），本轮处理 `ProductSelectionController`
剩下的两条，并处理清点过程中发现的一个更严重的问题——**已经接进 UI 的那条也在造数，且 UI 没说**。

## 1. 事实（先确定数据到底从哪来）

三条链路同一个引擎：`ProductSelectionServiceImpl` 用 `new Random(keyword/asin 哈希)` + 品类基准值生成指标，
类注释自己写着「由于无真实数据源…使用基于品类基准的合理模拟数据」（:38-39）。

| 端点 | 数据来源 | 是否落库 | 前端 |
| --- | --- | --- | --- |
| `POST /ops/selection/market` | 播种 Random | 写 `amz_selection_opportunity` 5 行 + `amz_keyword_research` 1 行 | 已接（选品分析页主功能） |
| `GET /ops/selection/competitors/{asin}` | 播种 Random（价格/评论/BSR/跟卖数）+ 5 条写死的中文建议 | 否 | 无入口 |
| `POST /ops/selection/keyword` | 播种 Random | 写 `amz_keyword_research` 1 行 | 无入口 |
| `GET /ops/selection/opportunities` | 真读表（表里的行就是上面造出来的） | 否 | 已接 |

## 2. 修的两件事

### A. 静默把写入归属降级成 1 号店

```java
// 修前，两处（analyzeMarket:74、researchKeyword:204）
Long shopId = UserContext.getShopId() != null ? UserContext.getShopId() : 1L;
```

没有店铺头的调用方（脚本、内部服务、token 不带 shopId）会拿到 200，并把 5 条假机会 + 1 条假调研
挂到 **1 号店**名下。后果不是「多几行脏数据」这么简单：`GET /ops/selection/opportunities` 是**真读表**的，
所以这些假结论会出现在别人（或本人）的选品列表里，看起来像分析出来的历史结果。
仓库范围实测：`grep ': 1L;'` 全仓只有这 2 处，都在本文件内，改动不外溢。

修法：`requireShopId()` 只在 `> 0` 时返回，否则返回 null，调用点直接
`Result.failure("缺少店铺上下文：请先选择店铺后再分析，机会与调研结果不再写入默认店铺 1")`，
**一条都不写**。

### B. 两条造数端点按仓库既有规则只在 mock 档产出

`isMockProfile()`（与 `DailyReportScheduler`、`CustomerServiceImpl`、7j 的提醒扫描同一条规则），
非 mock 时拒绝并打 WARN 说明「没有一行来自真实查询，恢复需接 SP-API Brand Analytics」。
竞品那条在 marketplace 缺省归一之后判断，关键词那条先判档位再判店铺上下文（顺序有测试钉住）。

这两条端点本来就没有 UI 入口，所以门禁不削减任何现有功能；被关的是「一次 curl 就能拿假数据当市场分析」。

### C. 前端补齐数据来源披露（本轮最值钱的一条）

`views/ProductSelection.vue` 原本没有任何说明，把播种值直接渲染成「市场分析摘要 / 机会评分 / 8 维雷达」，
点一次还往两张表写 6 行。新增 `.notice-zone` 说明：指标怎么来的、会落哪两张表、不再默认写 1 号店、
两条同类端点已拒绝所以没入口、以及「可用于演示联调，不能当决策依据」；
成功摘要标题旁挂静态 `模拟数据` 徽标（后端返回 200 也要挂——这里的 200 本身就是模拟值，
和 `useMockFlag` 那种「降级才挂」的语义不是一回事）。

## 3. 闸口

| 闸口 | 结果 |
| --- | --- |
| `mvn -pl amz-service/amz-service-ops -am test` | 29 tests，0 failures / 0 errors；`ProductSelectionServiceImplTest` 10 条 |
| RED | 4 条新用例先红，报错是断言不是编译：`expected: <400> but was: <200>` ×4 |
| `vue-tsc` / `vitest run` / `vite build` | 全绿，416 tests / 38 files |
| Playwright | `侧边栏点击「选品分析」应跳转 /selection` 通过（页面加了披露块后仍正常渲染） |
| 端点清点 | 本轮不新增前端调用，候选数不变（43）；两条端点从「未接」变成「未接且已拒绝造数」 |

## 4. 变异检查（3/3 咬住，源文件字节级还原）

| 变异 | 重新注入的东西 | 应变红的测试 | 实测 |
| --- | --- | --- | --- |
| M7 | `requireShopId()` 取不到就返回 `1L` | `analyzeMarketRequiresShopContext`、`researchKeywordRequiresShopContext` | 2/2 红（Tests run 10, Failures 2） |
| M8 | `isMockProfile()` 恒真 | `analyzeCompetitorsRefusedOutsideMock`、`researchKeywordRefusedOutsideMock` | 2/2 红 |
| M9 | 删掉 `.notice-zone` 块（改类名） | 披露用例 + 「未选店铺时披露仍在」 | 2 failed / 2 passed / 4，与预期一致 |

还原后 sha256：`ProductSelectionServiceImpl.java e4ac01b533df84e0`、`ProductSelection.vue eb43e0fa5a593174`
（每次变异都在 `finally` 里写回原始字节并核对）。

`analyzeMarketWritesContextShop`（断言 5 条机会行 + 1 条调研行的 shopId 都等于上下文店铺 7）
在 M7 下仍绿——这是有意的：M7 只影响「无上下文」分支，它由另外两条用例钉住；
这条用例钉的是「有上下文时不许改成别的店」。

## 5. 判为不接（连同理由，供后续加固排序）

- `GET /product/keepa/competitor/{asin}`：`!mock` 档走真 Keepa（缺 key 时 fail-closed），`mock` 档造
  `competitorCount/buyBoxWinner`。它的 `@ShopScoped` 是**装饰性的**——路径里没有 `Long shopId` 参数，
  `ShopIdGuardAspect:63-67` 直接放行，实际只校验「你至少有一个店」。付费上游 + 无效守卫，不接。
- `POST /ai/review/analyze`、`POST /ai/selection/analyze`：
  正文是真 LLM 调用，但零守卫（无 `@RequireRole`/无归属校验）且按次烧 DeepSeek token；
  后者的输入还是上面这些播种行——垃圾进、垃圾出。建议先加鉴权与配额，再谈接 UI。
- `POST /ai/eval/run`、`POST /ai/chat`、`POST /ai/agent/chat`：同 7j 的判定。

## 6. 遗留

- `analyzeMarket` 本身仍在造数并落库，因为选品页的主功能就是它。本轮把它**说清楚**并止住跨店写入；
  要真正修好需要接 SP-API Brand Analytics / Helium 10，属于独立工程项。
- `POST /ops/selection/keyword` 每次调用无条件 insert，无去重、也无读回，表会只增不减。
  本轮之后只有 mock 档能触发，暂不处理膨胀。
- `ProductSelectionServiceImpl` 的 `DEFAULT_CATEGORY="ELECTRONICS"`（选品侧同类问题，见 7l 的补货引擎）
  等硬编码常量不在本轮范围。
