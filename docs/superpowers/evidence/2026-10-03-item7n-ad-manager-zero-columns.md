# 7n — 广告活动表把「没有数据」从 $0.00 改回 —，并更正一条误导后续开发的注释

日期：2026-10-03。起点是 7m 里量到的一条：`AdManager` 的 SP 表格读 `c.spend / c.sales / c.acos`。
本轮先做证伪，再改代码。

## 1. 这几列到底有没有人写（三条独立验证）

| 验证 | 结果 |
| --- | --- |
| `grep -rn "setSpend(" --include=*.java amz-service` | **0 命中**（`setSales/setAcos` 的命中全在 `AdReport`/mock 客户端/搜索词服务，不是 ext 实体） |
| `grep -rn "amz_ad_campaign_ext"` 找出所有写语句 | 只有两条：`AdCampaignExtMapper:54` 改 `status`、`:69` 元数据 upsert（列清单里没有 spend/sales/acos） |
| 日报同步实际写哪张表 | `AdReportSyncScheduler#upsertRows` 构造的是 `AdDailyReport` → `amz_ad_daily_report` |

DDL（V1__init.sql:73-76）里 `spend/sales DECIMAL(10,2) DEFAULT 0`、`acos DECIMAL(5,2) DEFAULT 0`。
结论：**这三列永远是 0**，`GET /ad/campaigns/list/{shopId}` 把它们原样发给前端，页面渲染成
`$0.00 / $0.00 / 0%`。同一行里的 `budget` 是真列（同步在 `AdReportSyncScheduler:235` 写）。

同一段注释（`AdCampaignExtMapper:64-66`）写着「不触碰**由日报同步维护**的
impressions/clicks/spend/sales/orders/acos/roas」——这正是让上一轮实现读错来源的原因：
注释指了一个不存在的维护者。已就地更正为事实（谁都不写这几列，只有 `amz_ad_daily_report` 有数），
并指向本页的正确读法。**注意 7m 的证据文档把这条写成「全仓无写入路径」，结论对，但当时没写
注释误导这一层**，此处补全。

## 2. 前端改法：关联已有的日报行，关联不到显示 —

`AdManager.vue` 的 `loadOverview()` 本来就拉了 `GET /ad/reports`（同一页总览用它），
所以不新增请求、不新增端点：

- 把 `reportRows` 提到两个 `try` 之外声明（放进第一个 `try` 会让第二个 `try` 抛
  `ReferenceError`，`loading` 永不复位，症状是「整块表格凭空消失」——这次踩到了，测试也确实红了）；
- 按 `campaignId` 汇总 `cost/sales`（同一活动可能有多天日报行）；
- `spend/sales` 改成 `number | null`：没有日报行 = `null`，渲染 `—`；
- ACoS 由 `cost/sales` 现算，**销售额为 0 时是 `null` 而不是 0%**（0% 会被读成免费流量），
  阈值配色只对有值的行生效；
- 表格下加 `data-note="sp-source"` 说明：花费/销售额/ACoS 来自日报聚合按 campaignId 关联，
  日预算仍是 ext 真列。

`AdCampaign` 类型随之改成可空，并在注释里写清为什么不能塌成 0。

## 3. 闸口

| 闸口 | 结果 |
| --- | --- |
| `vue-tsc` / `vitest run` / `vite build` | 0 / 445 tests（39 files）全绿 / build 通过 |
| AdManager 单测 | 17 条：4 条本轮新增（先红后绿），13 条原有仍绿。中途一次真红是**我改坏的**：`reportRows` 作用域错，导致原有用例 `活动列表应取自 /ad/campaigns` 一起红——按事实修好而不是放宽断言 |
| Playwright | 广告相关 9 条全绿；并把 `AD_CAMPAIGNS` 桩的 `spend/sales/acos` 从假值改成真实的 0，新加一条断言证明数值确实来自日报关联（`$320.00 / $1,600.00 / 20%`） |
| 端点清点 | 不新增前端调用，候选仍 42 条（v12 仍为准） |

打桩资产为什么必须改成 0：给 `spend: 32.5` 就等于允许「读 ext 表」这条错路径也能过测；
真实库里它只能是 0，桩跟着真实约束走，测的才是这次改动。

## 4. 变异检查（1/1 咬住，文件字节级还原）

| 变异 | 重新注入的东西 | 应变红 | 实测 |
| --- | --- | --- | --- |
| M21 | `spend/sales` 改回 `toNum(c.spend) / toNum(c.sales)`（即读 ext 零值列） | 关联用例 + 「没日报显示 —」+「销售额为 0 时 ACoS 是 —」 | 3 failed / 14 passed / 17，正是这三条 |

还原哈希：`AdManager.vue 7f9cac88afc9a6f1`（`finally` 里写回原字节并核对）。

## 5. 商品主数据三条端点：判为「架构漂移导致不可接」，不是忘了接

同轮实测（见 v12 之后的排查）：`ProductController` 的
`GET /product/getProductList`、`GET /product/getProductsByShop/{productId}`、`POST /product/postProduct`
走的是 `model/pojo/Product.java`——它自己已 `@Deprecated`，映射的列是
`name / type / image / time / sales / user_id / stock`；而 `amz_product` 的 DDL
（V1__init.sql:43-58，另有 docker/init-sql-legacy 的镜像）只有
`shop_id / sku / asin / marketplace_id / title / description / brand / price / currency / category /
size_tier / weight_g / status / create_time`。

- 两边交集之外没有别的解释：MyBatis-Plus 会按实体字段生成列清单，
  这三条端点一旦执行就是 `1054 Unknown column`，即 500，不是返回空列表；
- 该表的新数据由 `ProductMaster*`（`amz_product` 的另一套实体 `AmzProduct`）与
  `tools/synthetic-data/generate.py:120` 维护，`ListingMonitor.vue` 已经接的是这一套
  （`/product/master/list/{shopId}`），列也和新表一致；
- 因此这三条不列入「待接」，属于「要接必须先做一次实体/表收敛迁移」的项；
  `POST /order/saveOrder` 依赖 `productClient.getProductById`（同一套漂移实体），
  且 body 里带 `userId`、落库不设 `shopId` → 一并判为不接，属加固/迁移项。

清点里它们仍会显示为候选（前端不再命名它们），这是有意的：宁可清单上留着「不可用」，
也不要让源码里出现看起来能用、实际一调就 500 的契约。
