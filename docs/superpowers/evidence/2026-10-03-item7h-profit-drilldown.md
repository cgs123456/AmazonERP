# 项 7h：利润下钻（订单 / SKU / 月度聚合）+ 换掉写死的时间窗

日期：2026-10-03。切片：7h。前置：7f/7g、审计快照 v7（含工具修正）。

## 接了什么

`ProfitController`（amz-service-order）的 3 条端点原本零前端命中，现在都进了 `/profit` 页：

| 面板 | 端点 |
| --- | --- |
| 下钻·按订单号 | `GET /order/profit/order/{shopId}/{amazonOrderId}`（keyset 分页） |
| 下钻·按 SKU | `GET /order/profit/sku/{shopId}/{sku}`（keyset 分页） |
| 维度·按 SKU×月 | `GET /order/profit/summary/{shopId}`（后端 SQL 聚合，行数有上限） |

## 顺手修掉的四个「会说谎」的实现

1. **统计区间写死在 `onMounted` 里。** 页面调的是
   `getProfitReport(shopId, '2026-06-01', '2026-06-30')`，而汇总卡片四个数标着
   「总销售额 / 总成本 / 毛利润 / 毛利率」，没有任何地方说明这是一整个六月的窗口。
   现在区间是可见可改的两个日期输入，卡片标签里带上区间本身，`当前区间 …` 也写在旁边。
2. **「按店铺」维度永远填不上。** 三个维度里 `shop` 的数据只来自 mock，
   后端没有按店铺聚合利润的端点；真实数据到手后它被清空，但 Tab 还在——一个点进去永远是空的开关。
   现在这个 Tab 删了，并在说明区写明「后端没有这个聚合端点」，而不是留一个人能点开的空壳。
3. **读失败只进 console。** `catch (e) { console.warn(...) }` 之后页面继续显示示例数据，
   「示例」徽标说明这是假的，但「为什么是假的」看不见。现在 `.error-zone` 会写
   「利润报表：network error，下面显示的是示例数据」——降级仍然保留（既有测试钉着），
   只是不再静默。
4. **缺失的成本列被画成 `$0.00`。** 下钻行里 `productCost/adCost/vat/FBA` 为 null 时，
   原来的格式化函数会输出 `$0.00`，读起来像「这一项确实花了 0」。现在缺失显示 `—`；
   FBA 两项合并也只在至少有一项取到数字时才相加（两边都没有就是没有，不是 0）；
   负数改成 `-$3.00` 而不是 `$-3.00`。月度聚合里缺的费用分项同样留白。

## 后端改动

- `getByOrder` / `getBySku` 原来是裸 `selectList`（按 SKU 查会把该 SKU 从有数据以来所有行吐出来）。
  现在按记录 id 倒序做 keyset 分页，探测 `size+1`，走 `Result.paged`；非法游标按参数错误处理，
  不会退化成「当作没有游标、从头再读一遍」。
- 月度汇总把行数上限做成**绑定参数**（`LIMIT #{maxRows}`）而不是拼进 Java 字符串，
  并按月份倒序，所以被截掉的是最老的月份；控制器里传常量 `MAX_SUMMARY_ROWS = 500`。
- 这个模块的控制器直连 mapper、没有 service 层，所以分页形状在控制器层测（见下）。

## 反证：14 个变异全部变红

后端 `ProfitDrilldownQueryTest`（9 例，模块 72 例全绿）：

| 变异 | 变红的用例 |
| --- | --- |
| MB1 去掉 LIMIT | `orderByOrderIsPaged`、`skuDrilldownIsPaged`、`nullPageFallsBackToDefaultSize` |
| MB2 探测行数改成页长（永远不会截断） | 同上三条 |
| MB3 忽略游标 | `orderDrilldownUsesKeysetCursor` |
| MB4 去掉 shop_id 条件 | `orderByOrderIsPaged` |
| MB5 汇总上限形同虚设（传 MAX_VALUE） | `monthlySummaryIsBoundedByParameter` |
| MB6 聚合 SQL 丢掉 bound 与倒序 | `summarySqlKeepsBoundAndOrdering` |

前端 `ProfitReport.test.ts`（15 例）：MF1 失败不落到页面 / MF2 不完整行当完整行 /
MF3 请求回到写死的日期 / MF4 忽略下钻截断 / MF5 缺失费用补 0 / MF6 空值仍发请求 /
MF7 缺失金额打成 $0.00 / MF8 FBA 两边都缺时相加为 0 —— 逐个重放各打红一条用例。

两处「测试自己有问题」也被这轮反证逼出来，都是先改测试而不是改代码：

- **MF6 一开始是绿的。** 「空处理人」那类守卫在查询按钮上已经被 `disabled` 挡住了，
  测试点不到它。真正的入口是「下一页」——它只受 `busy` 控制，把输入框清空再点就走到守卫里。
  补了这条路径后变异才变红，说明守卫不是死代码。
- **MF2 一次红了两条。** 追下去发现是分页用例自己的顺序错：
  第二页响应 `_page=null` 之后「下一页」按钮就消失了，我却在那之后再去点它。
  顺序改成「先点空值（按钮还在），再点正常下一页」。

## 闸口

- `mvn -pl amz-service/amz-service-order -am test`：**Tests run: 72, Failures: 0**
  （amz-common 177 例同时在跑，0 失败）。注意 `-pl` 不带 `-am` 会拿本地仓库里旧的
  amz-common jar 编译，报 `package com.amz.redis does not exist`——这不是代码问题，是取到旧字节。
- `vue-tsc --noEmit` 0 错误（中途 `sum` 的 reduce 推断成 unknown 报过 TS2322/TS18046，已修）；
  `vitest run` **36 文件 / 395 例全绿**；`vite build` 成功。
- Playwright：`/profit` 相关 4 例全过（NAV 1 + 新增 3）。
  第一次跑时「统计区间」那条报 element not found，单独跑和整组重跑都过——
  是改完 .vue 后 dev server 的模块重载竞态，不是页面缺陷；记在这里以免下次当成误判。
- hygiene 在 `git add` 之后跑（7g 的教训），端点覆盖审计快照 v8 见同目录 txt。

## 遗留

- `getProfitReport` 的日期区间默认值仍然是六月的常量（现在看得见也改得动），
  要不要默认成「最近 30 天」是个产品口径，等用户定。
- 按 SKU 下钻需要精确 SKU 字符串，页面上没有「列出本店有利润记录的 SKU」的端点；
  要做成可点的清单得先加一个 distinct 查询端点。
- `amz_profit_report` 由 ProfitMQConsumer 逐单写入；`dataComplete=false` 的行只是被标出来，
  补齐缺的成本项需要上游（费用/广告/FBA 报表）真的有数据，不是页面能修的。
