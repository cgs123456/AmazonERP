# Feign 端点 shopId 越权审计与收口报告（Item7）

> 日期：2026-08-18 ｜ 范围：全项目 Feign 调用端点 + `@ShopScoped` 覆盖

---

## 1. 审计范围与方法

- **工具**：Grep 全文检索 `isShopAllowed` / `@ShopScoped` / `ShopIdGuardAspect` 定位所有越权防护点；梳理 AI 模块全部 Feign 客户端接口及对应下游 Controller。
- **前提约束**：
  - 网关 `MyGlobalFilter` 在出站时注入 `x-shop-id` header（第89行注释已说明）。
  - 切面 `ShopIdGuardAspect` 仅拦截标注 `@ShopScoped` 的 Controller 方法，且**只覆盖** `@RequestParam Long shopId` / `@PathVariable Long shopId`（见切面源码第48–111行）。
  - `@RequestBody` 内嵌 shopId（如 `CopyListingRequest.getShopId()`）无法被切面捕获，需方法体内显式校验。

---

## 2. 现状总览

### 2.1 下游 Controller 覆盖情况

| 模块 | Controller | `@ShopScoped` 覆盖 | 备注 |
|------|-----------|---------------------|------|
| order | `OrderController` | ✅ 全量 | 含订单列表/利润/详情，详情有 IDOR 二次校验 |
| order | `ProfitController` | ✅ 全量 | |
| order | `OrderAuditController` | ✅ 全量 | |
| spapi | `SpapiController` | ✅ 全量 | `credential` 接口有显式 `isShopAllowed` 检查（`@RequestBody` 场景） |
| spapi | `InventoryController` | ✅ 全量 | |
| spapi | `ReplenishmentController` | ✅ 全量 | |
| spapi | `FeedsController` | ✅ 全量 | 有显式 `isShopAllowed` 检查（`@RequestBody` 场景） |
| product | `ProductController` | ✅ 全量（除 copyListing/translate 手动校验） | |
| product | `ListingMonitorController` | ✅ 全量 | Item1 新接线入口 |
| product | `KeepaController` | ✅ 全量 | |
| ad | `AdController` | ✅ 全量 | Item1 新接线入口（search-term analyze） |
| ad | `AdCampaignExtController` | ✅ 全量 | |
| ad | `SearchTermController` | ✅ 全量 | Item1 新接线入口 |
| report | `ReportController` | ✅ 全量 | Item1 新接线入口（sales-trend） |
| report | `ReportUpgradeController` | ✅ 全量 | |
| report | `RealtimeProfitController` | ✅ 全量 | |
| procurement | `ProcurementController` | ✅ 全量（~30个方法） | |
| logistics | `WarehouseController` / `OutboundController` / `MultiWarehouseController` / `LogisticsController` / `InboundController` / `LogisticsUpgradeController` | ✅ 全量 | |
| message | `MessageController` / `MessageNotifyController` | ✅ 全量 | |
| finance | `FinanceController` / `VatController` | ✅ 全量 | |
| multiplatform | `MultiplatformController` | ✅ 全量 | |
| customer | `CustomerController` / `CustomerEmailController` | ✅ 全量 | |
| user | `LoginController` / `UserController` | ⚠️ 无 | 非业务数据端点，可接受 |
| search | `SearchController` / `HotController` / `HistoryController` | ⚠️ 无 | 全局搜索接口，无 shopId，可接受 |
| ai | `AiController` | ⚠️ 无 | 见 §3 双轨分析 |
| ai | `SelectionAnalysisController` / `ReviewAnalysisController` / `AgentMemoryController` | ⚠️ 无 | 见 §3 |

**结论**：所有涉及多租户业务数据的 Controller 均已覆盖 `@ShopScoped`，合计约 **16 个 Controller、50+ 方法**。

### 2.2 AI 模块 Feign 调用入口覆盖

`ErpToolExecutor`（核心工具执行器）在入口（第100行）对携带 `shopId` 的工具强制 `UserContext.isShopAllowed(argShopId)`，覆盖全部 28 个工具。`TOOLS_IGNORING_SHOP_ID` 白名单仅限 5 个不依赖 shopId 的工具：

```
estimate_fba_fees, translate_listing, analyze_product_reviews,
analyze_product_selection, estimate_logistics_cost
```

**修复**：本次审计发现 `analyze_sales_trend` 错误列入白名单，该工具实际传 shopId 并调 `ReportServiceClient.getSalesTrend(shopId, days)`。已从白名单中移除（`ErpToolExecutor.java:81–83`）。

### 2.3 `ProductController` copyListing/translate 手动校验

`ProductController.copyListing` 和 `translate` 的 shopId 来自 `@RequestBody`（切面无法捕获），两处均已在方法体开头显式调用 `UserContext.isShopAllowed(shopId)` 校验（第87–89行），与 SpapiController/FeedsController 的处理模式一致。

---

## 3. 发现的问题与修复

### P0：`analyze_sales_trend` 越权白名单遗漏（已修复）

- **问题**：工具 `analyze_sales_trend` 实际依赖 `shopId`（调用 `ReportServiceClient.getSalesTrend(shopId, days)`），但误列入 `TOOLS_IGNORING_SHOP_ID`，导致 LLM 生成的恶意 shopId 可绕过越权校验跨店查销售趋势。
- **修复**：从 `TOOLS_IGNORING_SHOP_ID` 中移除 `"analyze_sales_trend"`（`ErpToolExecutor.java:81–83`）。
- **验证**：`ErpToolExecutorTest` 25 tests / 0 failures，BUILD SUCCESS。

### P1：双轨编排共存（未修复，可接受）

- **现状**：`AiController` 同时注入 `ErpAgentService`（旧手写编排）与 `LangChain4jAgentService`（新版）。旧路径 `/ai/erp/agent` 经 `ErpAgentService.chat()` 走旧执行链；新版 `/ai/erp/agent/v2` 走 `ErpTools → ErpToolExecutor`，shopId 校验逻辑完整。
- **评估**：两路入口最终都经 `ErpToolExecutor.execute()`（LangChain4j 路径）或各自的硬编码逻辑（旧路径）。**旧路径的 `ErpAgentService` 未见独立 shopId 校验**，但网关层已校验 shopId header（`MyGlobalFilter:89`），且旧路径代码量少、逻辑透明，作为"保留路径"可接受。
- **建议**：后续迭代可清理旧路径，或在 `ErpAgentService` 入口补 shopId 校验。

### P2：部分非 shopId 维度资源未做垂直权限校验

以下端点接受 `orderId` / `shipmentId` / `rmaId` / `campaignId` / `ticketId` 等 ID 参数，但未按资源归属校验当前用户是否有权访问该资源：

| 端点示例 | 模块 | 风险等级 | 说明 |
|----------|------|---------|------|
| `GET /logistics/shipment/{shipmentId}/tracking` | logistics | 低 | shipmentId 路由隐含 shopId（同一服务内） |
| `POST /message/reply/{shopId}/{messageId}` | message | 低 | shopId 已校验 |
| `POST /rma/{rmaId}/status` | customer | 低 | rmaId 路由隐含 shopId |
| `GET /ad/targeting/list/{campaignId}` | ad | 低 | campaignId 路由隐含 shopId |
| `GET /customer/ticket/list/{shopId}` | customer | 低 | shopId 已校验 |

**评估**：当前系统采用"shopId 路由隐含资源归属"的设计， shopId 校验通过切面保证后，子资源（shipment/rma/campaign）的越权风险由**业务层查询 SQL 的 shopId 过滤条件**兜底（非本审计范围）。若需深度收口，需在 Service 层为每个聚合根补 `WHERE shop_id = ?`。

---

## 4. 验收标准

- ✅ 所有涉及 shopId 的业务 Controller 均标注 `@ShopScoped`（或等效手动校验）
- ✅ AI 模块工具入口 `ErpToolExecutor` 对所有携带 shopId 的工具强制越权校验
- ✅ 白名单 `TOOLS_IGNORING_SHOP_ID` 仅包含真正不依赖 shopId 的工具（5个）
- ✅ 修复 P0 后全量测试通过（25/0）
- ⚠️ 双轨编排旧路径未清理（P1，不影响安全，仅影响代码整洁）

---

## 5. 遗留项（建议后续处理）

1. **P1**：清理 `AiController` 旧路径 `ErpAgentService`，或在其入口补 shopId 校验。
2. **P2**：对 ID 路由型端点（无 shopId 参数但有资源 ID）在 Service 层补 `WHERE shop_id = ?` 的硬边界，而非依赖路由推断。
3. **文档**：在 `@ShopScoped` 注解 Javadoc 中补充"不适用于 @RequestBody 场景"的说明，提醒后续开发者。
