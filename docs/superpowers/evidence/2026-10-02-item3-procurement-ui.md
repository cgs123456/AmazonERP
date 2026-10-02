# 功能覆盖第 3 项：采购域前端接入（2026-10-02）

## 结论

采购域 33 个端点里 **32 个已接进 UI**（1 个是造数端点，故意不接），
新增 `api/procurement.ts` + `views/Procurement.vue` + `/procurement` 路由 + 侧边栏入口 + 13 个用例。
`vue-tsc` 干净、前端全量 198 用例绿、`npm run build` 出 47.71 kB 分包、hygiene 门禁 0 finding。
**没有跑真服务**，字段名的来源与未验证项见最后一节。

## 为什么这一项排第三

覆盖清点里 procurement 是唯一「后端整条链都实现、前端零入口」的域：
供应商比价、计划审批流、1688 下单、质检判定、FBA 头程分摊、FIFO 批次成本，
后端都在跑，但 UI 一个按钮都没有——审批只能在状态机外面干等，闭环断在界面上。
纯增量，不改后端一行代码。

## 接了什么

| 分区 | 端点 | 关键行为 |
| --- | --- | --- |
| 供应商与比价 | `/supplier` 增改查、`/{id}/status`、`/supplier/product`、`/by-sku`、`/compare`、`/{id}/kpi` | 状态三值按 DDL 限死（ACTIVE/DISABLED/BLACKLISTED，后端不校验枚举）；比价表直接用后端 `overallScore`，不在前端重算 |
| 采购计划 | `/plan` 增查、`submit`/`approve`/`convert`/`cancel` | 按钮按状态机出现：DRAFT→提交审批，PENDING_APPROVAL→通过/驳回（审批人没填则不可点），APPROVED→转采购单 |
| 采购单 | `/order` 增查、`submit`/`sync`/`cancel`、`/qc/{id}` | 提交 1688 与取消都要过确认弹窗；`cancel` 返回 `false`（远程关单失败）时页面直说「本地状态保持不变」 |
| FBA 货件 | `/fba/shipment` 增改查、`item`/`items`、`ship`/`status`/`allocate`/`receive` | 签收默认补满未收行、已收行不提交；差异面板显示 `SHORT_RECEIVING`/`OVER_RECEIVING` |
| 库存批次 | `/batch/list`、`/batch/cost-summary`、`/batch/fifo-outbound` | FIFO 扣减走确认弹窗；无批次时说明「成本退化为采购单价」而不是显示 0 |

## 两处刻意的设计

**1. 分页不兜底。** 后端 `Result.paged` 带 `_page.truncated`，页面把它显示成
「已加载 N 条（后端未给全量数）· 后端标记仍有下一页」并给「加载下一页」按钮；
顶部四张统计卡的标签统一写「本页」，注释里写明合计不是全量。
采购审批把首页当全量会漏单，这是本域最贵的错误，所以宁可啰嗦。

**2. `/promotion/plan` 不接。** Controller 里它返回写死的
`Lightning Deal / 20% / "$500 广告预算" / "150% 提升"`，与 `shopId`、`asin` 无关，
接进 UI 等于把模板文案显示成经营建议。用例 `造数端点 promotion/plan 留在注释里说明`
锁住这条：文件里可以出现该词（注释），但任何 `request.get/post` 都不许带上它。

## 验证

- `npx vue-tsc --noEmit` → 无输出，exit 0。
- `npm run test:run` → 24 文件 / **198 通过**（新增 13）。
- `npm run build` → `Procurement-D9tSo64W.js 47.71 kB (gzip 13.34)`。
- `python tools/release/repository_hygiene.py` → findings 0；
  路由文件加了 `/procurement` 一行，把 `token` 那处既有误报的允许列表指纹从
  `8dde9181…` 重挂为 `76eb4719…`。
- **变异检查 4/4 被抓**（baseline 13 绿，每次改完按 sha256 `b6d3569e…` 校验还原）：
  A 签收后重走 `selectShipment`（会冲掉刚拿到的差异面板）→ 差异用例红；
  B 提交 1688 跳过确认弹窗 → 确认门用例红；
  C 截断时不给「下一页」入口 → 分页用例红；
  D 审批按钮不再要求填审批人 → 状态机用例红。
  其中 A 是写第一版时真实存在的缺陷：`receive()` 设完 `receipt` 又调 `selectShipment`，
  后者清空结果面板，签收差异永远看不见；拆成 `loadItems()`/`selectShipment()` 后修好。

## 没验证到什么程度（重要）

- **没起真服务**。字段名来自 `amz-service-procurement` 的实体类与 `V1__init.sql` 的状态注释
  （列表端点直接返回实体、无 DTO 转映射层），信封 `_page` 沿用第 2 项在 product 上实测过的同一
  `com.amz.result.Result`。仍不等于抓包：上线前应在影子环境点一遍四个列表 + 一次建计划。
- **`@RequireRole({"OPERATOR","ADMIN"})` 没有前端门控**（仓库现状：没有任何角色工具函数）。
  若登录用户是低权角色，写操作会拿到后端 403 并显示在错误条里，而不是按钮提前隐藏。
- **像素级 UI 未看**：dev 登录走 peer 的 :8080 user 服务，本地铸的 token 会被拒。
- **1688 真实下单**只在 `!mock` 档成立；确认文案按最坏情况写（含「响应丢失可能重复单，需与 1688 后台对账」），
  没有把它当成安全操作。
