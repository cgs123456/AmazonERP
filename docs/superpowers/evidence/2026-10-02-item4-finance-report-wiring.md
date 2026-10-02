# 功能覆盖第 4 项：财务凭证补数 + 财务/报表接线（2026-10-02）

## 结论

三个 commit：

1. `a73473f` PLATFORM_FEE / REFUND 凭证 producer（finance 内自足）
2. `2fa5224` PROCUREMENT 凭证 producer（跨采购域读取）
3. `2f62fe1` + 本次：财务 21 个零入口端点接进 `财务管理`，报表 20 个零入口端点新建 `经营报表` 页

前端 `vue-tsc` 干净、**26 文件 / 227 用例**绿、build 出包；后端 finance 189 / procurement 97 / common 177 全绿。
变异检查：finance 侧 4/4、前端侧 4/4 被抓（详见验证节）。**没有起真服务**。

## 为什么先做凭证，再做界面

`calculateProfit` 的注释和分支早就在扣 PROCUREMENT / PLATFORM_FEE / REFUND 三类成本，
但仓库里没有任何生产者写入它们：收入侧有 MQ 消费者出 ORDER 凭证，成本侧一片空白。
结果是利润系统性偏高，而且高得很安静——界面显示的是一个正常的数字。
补 producer 比接线更值钱，所以先做它。

## 凭证 producer（3 个新类型的来源与不来源）

| 类型 | 数据来源 | 幂等键 | 方向 |
| --- | --- | --- | --- |
| PLATFORM_FEE | `amz_settlement_detail` 里 Order 非 Principal 分项 + ServiceFee/未知类型 | `(shopId, PLATFORM_FEE, row_key)`，缺指纹退 `SD-<行PK>` | 借 6601 销售费用 / 贷 1002 银行存款，被利润减 |
| REFUND | 结算行 `transactionType=Refund` | 同上 | 借 6001 主营业务收入（红冲）/ 贷 1122 应收账款，被利润减 |
| PROCUREMENT | `/procurement/order/voucher-source`，只出 QC_PASSED/RECEIVED/COMPLETED | `(shopId, PROCUREMENT, orderNo)` | 借 1405 库存商品 / 贷 2202 应付账款，被利润减 |

三条口径上的坚持：

- **归类只有一个入口。** `SettlementClassifier` 是唯一把结算行分成 PRINCIPAL/FEE/REFUND/ADJUSTMENT 的地方，
  `PaymentCollectionServiceImpl.aggregate` 原来的私有 if 链改走它。否则回款表和凭证簿各算一遍，
  两处金额迟早互相打脸，而且没人报错。
- **不出凭证的类型显式排除。** PRINCIPAL 归订单凭证（MQ 链路），ADJUSTMENT 归索赔链路；
  `voucherizable()` 把这条规则写成代码而不是文档。
- **不猜。** 无币种的结算行跳过并计数（按 1:1 折算会把美元扣费当人民币扣费）；
  采购域读不到时 `remoteDegraded=true` 且一张凭证都不出，控制器把这种情况翻成 code 400
  但 data 仍带完整报告（沿用 `syncToKingdee` 的先例）。「没读到」和「没有采购成本」是两回事。
- 采购单没有币种列，1688 报价与支付都是人民币 → 凭证按 CNY 记，并在 DTO 注释里写明这是
  与数据源同假设，将来接外币采购必须先给采购单加币种字段。
- 不加 `@Transactional`：按行幂等，中途失败时已写凭证是有效结果，重跑只补差额；
  把 5000 行包进一个长事务反而会在失败时把有效凭证一起回滚。

## 界面接线

`财务管理` 从 2 个分区扩到 8 个：凭证 / 利润 / 回款对账 / 结算原表 / 费用差异 / 亚马逊索赔 / 单品利润 /
VAT 与凭证补数。新增 `经营报表` 页（`/reports`）5 个分区：经营概览 / 利润明细 / 库存周转与滞销 /
日销与同期对比 / 实时快照与成本分摊。

界面上的反粉饰做法（都有用例钉住）：

- 分区**首次进入才请求**，不在首屏打满 8 个重端点。
- 会动数据的操作一律二次确认：重算回款、SP-API 结算同步、忽略差异、生成索赔单、驳回索赔、
  VAT 月结、按 SKU 重算快照、成本分摊。
- 结算同步报告把「读到的行数 / 新入库 / 重复跳过 / 失败行」四个数分开显示；负数扣费按负数显示。
- 凭证补数报告把 `capped` / `remoteDegraded` 显示成结论（「是，需再跑一次」「降级：…别当成没有成本」）。
- 快照里 `vatCost/refundCost/otherCost` 三个后端常量字段用占位样式（斜体灰）渲染，
  页面顶部常驻说明它们不是算出来的、快照净利偏高、不能当结算依据。
- 成本缺失的 SKU 显示「缺 / 不完整」而不是 0 成本。
- VAT 三个查询端点后端返回给人看的字符串，原样透出，不在前端正则解析成数字。
- 列表一律读服务端 `_page.truncated`；缺 `_page` 且有数据时额外提示「本页可能不完整」。
- `allocDetails` 后端存的是 JSON 文本：能 parse 就展开成 `sku:金额`，parse 不了原样显示，不假装是空。

顺手修掉两个真问题：

1. `api/finance-ext.ts` 的 `syncSettlement` 收了 `shopId` 却没放进 query（后端是 `?shopId=`，不是路径参数）。
   是 `vue-tsc` 的 unused-param 报出来的——接线时才发现的哑端点。
2. `api/dashboard.ts` 把 `conversionRate ?? 0` 显示成「0%」。真实报表实现
   （`RealReportServiceImpl`）**从不给 conversionRate 赋值**，它恒为 null，
   所以界面一直在把一个没统计的指标显示成「测出来是 0」。现在缺值显示「未统计」，
   真有值才显示百分比，`0` 仍然显示 `0%`（测出的 0 与没测不同）。抽出 `mapKpiItems` 便于单测。

## 故意没接的端点（以及为什么）

`POST /report/v2/profit`、`/report/v2/inventory-turnover`、`/report/v2/sales-daily`、
`/report/v2/business-overview`、`/report/profit/allocation` 都不进 UI：它们接收完整实体，
是给调度器和其他服务写数用的；放进界面等于让人手工录入会计数据。

更硬的理由：这 5 个端点的 `@ShopScoped` **是空转的**。`ShopIdGuardAspect` 只按参数名找
`shopId` 的 `@PathVariable`/`@RequestParam`，这 5 个的 shopId 只在 `@RequestBody` 里 → 找不到就放行。
也就是说它们目前既没有角色要求，也没有有效的店铺归属校验。有用例断言 UI 不会碰到它们
（`5 个实体入库 POST 不接入 UI`），但**后端缺陷本身没修**，见下面待办。

## 本轮新发现、尚未处理的后端缺口

按严重度排，都写进第 5/6 项的待办：

1. （High）上述 5 个 report POST 端点越权可达：任意登录用户可向别家 shopId 写利润/周转/日销/概览/分摊明细。
   报告服务全部 23 个端点也都没有 `@RequireRole`。
2. （Medium）`ProfitSnapshot` 的 `vatCost`/`otherCost` 写死 0、`refundCost` 从不赋值
   （`RealtimeProfitServiceImpl.java:106,115,125,128`），所以实时利润与毛利率整体偏高。
   界面已如实标注，但值本身仍是占位。
3. （Medium）`RealReportServiceImpl.java:99-103` 把 returnRateTrend/conversionTrend/trafficSource/
   categorySales/topProducts 写成常量空值，`fetchAdSummary` 的返回值没有任何字段消费它（只进日志）。
4. （Low）`Finance.vue` 里 `truncated` 的注释与代码相反（注释说「宁可让用户再点一次」，
   代码是 `_page` 缺失就置 false）。行为与既有用例一致，暂未动。

## 验证

- 后端：`mvn -o -pl amz-service/amz-service-finance,amz-service/amz-service-procurement -am test`
  → BUILD SUCCESS；finance 189（含新增 producer 11 + 归类口径 5 + 读取口径 3 + 控制器 2），procurement 97，common 177。
  凭证写接口计数守卫 `FinanceControllerGuardTest` 由 13 → 14 → 15。
- 变异检查（finance 侧）：去掉金额取反 / PRINCIPAL 也出凭证 / capped 造假 / 幂等查询失效 → 4/4 红。
  跨服务侧：降级被当空页继续 / 借贷写反 / 去掉状态过滤 → 3/3 红。均按 sha256 还原。
- 前端：`vue-tsc` exit 0；`npm run test:run` 26 文件 / 227 用例；`npm run build` 出
  `ReportCenter-*.js 28.45 kB`、`Procurement-*.js 47.71 kB`。
- 前端变异检查：确认弹窗改为直接执行 / 常量字段不标占位 / 进快照分区不加载分摊 /
  转化率缺值又显示 0% → 4/4 红（第一条起初写成模板内 `box.run()` 导致编译失败而非断言失败，
  已换成编译合法的「confirm 立即执行」重跑，抓到 2 个用例）。
- `python tools/release/repository_hygiene.py` → 0 finding（路由新增 `/reports` 一行，
  把既有 `token` 误报指纹重挂）。

## 没验证到什么程度

- 三个 producer 与 21+20 个端点都只有单元/组件级验证，**没有起 finance/report 真服务打 HTTP**。
  字段名与状态枚举来自 Java 实体、DTO 与控制器签名（列表端点直接返回实体，无 DTO 转映射层）。
- `Result._page` 信封沿用第 2 项在 product 上实测过的同一 `com.amz.result.Result`。
- 像素级 UI 未看（dev 登录走 peer 的 :8080 用户服务，本地铸的 token 会被拒）。
- 生产库上跑 producer 的实际行数影响未测：`generateSettlementVouchers` 一次最多 5000 行、
  `generateProcurementVouchers` 一次最多 50 页×200，首轮会一次性补出历史累计扣费与采购凭证，
  利润会明显下降——这是修正，不是回归，但上线前要先在影子库看总量。
