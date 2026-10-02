# 功能覆盖第 5、6 项：闭环与「不再撒谎」的现状（2026-10-02）

## 第 5 项：三条跨模块闭环 —— 已全部落地

| 闭环 | 之前断在哪 | 现在的走法 | 落点 |
| --- | --- | --- | --- |
| 到货差异 → 费用差异 | `FbaShipmentServiceImpl.processReceipt` 检测到短收只 `log.warn`，亚马逊该赔的钱停在日志里 | 采购域新增 `GET /procurement/fba/shipment/receipt-shortages/{shopId}` 交事实；财务「费用差异」页列待登记清单，「按此行登记」把 SKU/货件号/短收件数带入表单，金额与币种由人确认后走 `POST /finance/discrepancy/inbound-shortage` | `4873e43` |
| 补货建议 → 采购计划 | `PurchasePlan.source` 有 AUTO、`replenishmentData` 字段专门留档，却没有任何生产者 | 库存页每行加「生成采购计划」，落草稿并把当时的库存/日均/可售天数/建议量/统计日期写进 `replenishmentData`；采购页「补货依据」把这段快照读回来 | `e797e8f` |
| 头程分摊 → 实时利润 | 实时快照的 `headhaulCost` 只读 `amz_cost_allocation`，而这张表只能人手打一个总额 + 均摊、没有来源标识 | `allocateCost` 支持 `SKU:金额`（按采购域已摊好的数入账、合计不符直接 400）、`sourceRef` 幂等、币种必须显式；报表页加「按货件运费入账 HEADHAUL」 | `6f36fc0` |

两处刻意留在人工确认：短收索赔金额与货件成本币种。理由不是偷懒——
`amz_fba_shipment` / `amz_inventory_batch` 都没有币种列，而亚马逊赔付按站点币种结算，
系统里那个 CNY 口径的分摊数一旦被自动当成 USD 索赔额，错的是钱。
所以闭环做到「把事实和缺口一起摆到人面前」，不做到「自动替人填数」。

## 第 6 项：本轮做完的部分

### 1. 消息中心不再摆示例数据（commit `ad6d93b`）

`NotificationPage.vue` 进页面就渲染 5 条硬编码通知（`iPhone15-Black-128G` 库存预警、
订单 `114-1234567-8901234` 退货、B0XXXXXXXX 差评…），跟登录店铺无关，却排在最前面像真待办。
`amz-service-message` 没有消息表、没有 REST 读取接口，只有 Netty 内存会话。
现在：列表只装本次会话的 WebSocket 推送 + 空态说明「历史消息不持久化、无法回看」。

两条 Playwright 用例原先断言的就是这 5 条伪造通知（`all-pages.spec.ts:203`、
`full-interaction.spec.ts:284`），等于把假数据固化成契约——已改成断言空态、Tab 切换与连接状态；
「忽略/查看」交互需要真推送才有对象，桩环境不驱 WS，注释里写明不再断言。

### 2. AI 日报与主动提醒不再外发伪造经营数据（commit `ad6d93b`）

`DailyReportScheduler.buildReport()`（:148-190）除 shopId/date 外全是写死数字
（订单 23 单、销售额 $1,234.56、ACoS 24.95%、B08X4-001 补 200 件、Temu 3 单 / $89.94…），
过去每天 8 点经 Feign 推给活跃用户；`ProactiveReminderService` 四类提醒同样写死。
现在沿用仓库既有规则：模拟内容只允许 mock 档产出，非 mock 显式跳过并把原因写进 warn 日志；
`DailyReportFabricationGateTest` 4 例把两侧行为钉住（非 mock 连用户表都不查）。

### 3. 审单不再把建议说成已执行（本轮）

`auditOrder` 的 `actions` 里 `MERGE`/`SPLIT` 与 `BLOCK`/`FLAG` 混在一起返回，
但系统没有合并/拆单实现，`amz_order_split_log` 全仓零插入点（只有 :46 字段与 :421 查询）。
现在响应多出 `advisoryActions` + `advisoryNote`，并 warn 一条；
用例断言命中 SPLIT 时 `orderSplitLogMapper` 无任何交互。

### 4. 库存页假数据兜底已撤（commit `e797e8f`）

接口失败时原先继续显示页面写死的 7 个 SKU 与 4 个健康度计数，「没数据」和「数据长这样」无法区分。
现在失败=错误条+空列表+「—」，且只有真实列表数据到位才允许点「生成采购计划」。
对应的两条单测（断言降级到 mock）已按新行为重写，不是删断言。

## 第 6 项：还没做，按价值排好的下一步

### N1（High，安全）report 的 5 个入库 POST 越权可达

`POST /report/v2/profit | /inventory-turnover | /sales-daily | /business-overview` 与
`POST /report/profit/allocation` 的 `@ShopScoped` 是空转的：
`ShopIdGuardAspect.resolveShopId` 只按参数名找 `@PathVariable`/`@RequestParam` 里名为 shopId 的 Long，
这 5 个的 shopId 只在 `@RequestBody` 里 → 找不到就放行。report 全部 23 个端点也都没有 `@RequireRole`。
任何登录用户可往别家 shopId 写利润/周转/日销/概览/分摊。

UI 侧已经隔离（`ReportCenter.test.ts` 有一条用例断言前端绝不请求这 5 个端点），
但**后端缺陷本身没修**：改法是让切面识别 body 里的 shopId，或在控制器加显式校验——
前者会影响所有服务与调度器的内部调用（之前能过的调用可能开始被拒），属于需要单独评估的扩大化修改。

### N2（High，业务）审单页与拆合单

`/order/audit` 9 个端点零前端调用（规则 CRUD/toggle/删除、单笔/批量审单、路由、拆分日志查询）。
`routeOrder` 还有个真问题：它把 `"US-FBA-Warehouse"` 这种字符串拼出来的仓名 `insert` 进
`amz_shipment_routing`（`OrderAuditServiceImpl:389-407`），订单模块没有仓库数据可查，
这条路由记录里的仓库是虚构的。要接 UI 就得同时决定：路由解析接哪个数据源。

### N3（Medium，业务）客服 21 个端点零入口

`/customer/**`（工单 5 + 邮件模板/触发/任务/差评/RMA 16）。真实落库的部分是好的；
两处不真实：`processPendingEmails` 根本没有发信通道（模块内无 JavaMailSender/SMTP/客户端），
非 mock 只是把任务标 FAILED；`matchNegativeReviewToOrder` 与索评在 mock 档造 `SIMULATED-*` 行。
另外 RMA/工单的 `amazonOrderId` 是自由文本，`createRma` 从不校验订单是否存在或属于该店。
接 UI 前要先定：邮件通道做不做、RMA 与订单的关联校验放哪层。

### N4（Low，业务）搜索模块整体前端不接

`/search/search/{key}`、`/search/getHotList`、`/search/getHistoryList`、`/search/deleteHistory`
四个端点全部真实（ES BM25+kNN+RRF、Redis ZSET 热词、`amz_history`），零伪造，零前端调用。
纯接线，风险最低，适合放在 N2/N3 之后。

### N5（Low，清理）12 张零引用表的处置

113 张 DDL 表比对 `@TableName` + XML mapper + 原生 SQL 后的结论：

| 表 | 出处 | 结论 |
| --- | --- | --- |
| amz_ad_placement_report | ad `V1__init.sql` / `24-init-tables-ad-upgrade.sql` | 无引用 |
| amz_attention | user `V1__init.sql` / `02-init-tables-user.sql` | 只在 `FlywayBaselineContractTest:164` 出现 |
| amz_cart / amz_user_coupon / amz_product_browse | `04-init-tables-product.sql` | 无引用（C 端功能未做） |
| amz_coupon | `04...` + order `V3__order_coupon_key_type.sql` | 无引用，V3 只改 DDL |
| amz_customer_service_kpi | `25-init-tables-customer-upgrade.sql` | 无引用（客服 KPI 未实现） |
| amz_oper_log | `18-init-tables-oper-log.sql` | 无引用（操作日志未接线） |
| amz_purchase_approval | `23-init-tables-procurement-upgrade.sql` | 无引用；审批已改用 `amz_purchase_plan.status` |
| amz_listing_seo / amz_report_template / amz_logistics_quote | `33-init-tables-p2-ai-tools.sql` | 无引用；`/logistics/dashboard/quotes` 实际读 `amz_carrier_quote` |

`amz_field_permission` 曾经被误列为零引用：它由
`FieldPermissionServiceImpl:37` 用原生 SQL 读（只读不写），因此不在此列——这条修正同时说明
「grep `@TableName` 判定表是否被用」会漏掉原生 SQL，清点时必须三路一起看。

处置建议：`amz_coupon`/`amz_user_coupon`/`amz_cart`/`amz_product_browse` 属未做的 C 端功能，
个人项目里建议保留 DDL 不动；`amz_purchase_approval`（被 plan 状态机取代）与
`amz_logistics_quote`（被 `amz_carrier_quote` 取代）是真正的重复建模，可在下次清库时删；
其余 6 张等功能再做。删表要单独立项（V 序号 + 备份恢复演练），本轮未动任何表。

## 验证与未验证

- 后端：`amz-service-order`（FailClosed 9）、`amz-service-ai`（门禁 4）、`amz-service-report`（49）、
  `amz-service-finance`（189）、`amz-service-procurement`（103）本地全绿。
- 前端：`vue-tsc` 干净，26 文件 / 234 用例绿，`npm run build` 出包。
- E2E：本地补装匹配版本的 chromium 后，`all-pages` + `full-interaction` 共 39 例全绿。
  在此之前 CI 的 frontend(E2E) 作业从 #112 起红：我新加的分区用 `v-show` 常挂 DOM，
  而用例用全局 `.data-table tbody tr` / `.page-info` 计数 → 隐藏面板的空态行被算进去；
  `b97b451` 改 `v-if` 并把错误条移到页面级后本地复绿（#116 是在这之前推的，仍会红，#117 起应恢复）。
- 仍然没做的验证：没起任何真服务打 HTTP；所有字段/状态名来自 Java 实体、DTO、控制器签名与 DDL；
  `#117` 之后的 CI 结论未回收（写入本文时仍在跑）。
