# 第 6 项 N2/N3 落地：客服与审单接入 UI，路由不再拼假仓名（2026-10-02）

范围：功能覆盖修复方案第 6 项里 N3（客服）与 N2（审单）。证据都取自本机实测，命令写在每节末尾。

## 一、N3 客服中心（已完成）

21 个 `/customer/**` 端点此前零前端入口。新增 `src/api/customer.ts` + `src/views/CustomerService.vue`
（5 个 `v-if` 分区：工单 / 邮件模板 / 邮件任务 / 差评与索评 / RMA），路由 `/customer`，侧边栏「客服中心」。

界面上常驻说清三件「通道没接」的事实，避免 UI 把没发生的事显示成已办：

1. 邮件发送通道未接入（模块内没有 SMTP/Messaging 客户端）：非 mock 档 `process/{shopId}` 只会把
   PENDING 任务标成 FAILED 并写 failureReason，mock 档是「模拟发送」，两者都不是真发信；
2. 索评与「差评匹配订单」依赖 SP-API：非 mock 后端直接抛错，mock 档写 `SIMULATED-*` 行；
3. 工单分类/情感是关键词 contains（`TicketClassifier`），不是模型判定。

三个受限动作（处理待发队列 / 批量索评 / 匹配订单）一律先弹二次确认，确认里把上面三条原文贴出。

顺带修掉写这页时暴露的两个缺陷：

- `table-pager` 的 `</div>` 被误写成 `</table>`，Vue 解析器把「差评与索评」「RMA」两个分区嵌进了
  「邮件任务」的 `v-if` 里——那两个分区永远点不出来。唯一信号是 vue-tsc 的 TS2367（类型收窄），
  不是模板报错。现在单测与 E2E 都锁「一次只渲染一个 `.tab-panel`」，并对全部 23 个
  view/component 做了标签配对扫描（0 处不配对）。
- 弹窗编辑态的回填对象（`template`）会随 `form` 一起进请求体；现在 `delete body.id / body.template`。

## 二、N2 订单审单（已完成）

9 个 `/order/audit/**` 端点此前同样零入口。新增 `src/api/orderAudit.ts` 接口层 +
`src/views/OrderAudit.vue`（5 个分区：审单规则 / 单订单审单 / 批量审单 / 发货路由 / 拆分日志）。

候选值不前端编：动作 5 个取自 `auditOrder` 的 switch，条件字段 7 个取自 `extractFieldValue`，
操作符 8 个取自 `evaluateCondition`。页面常驻三条边界：

1. 审单只给判定与建议，不改订单数据；`MERGE`/`SPLIT` 是建议——系统没有合并/拆单实现，
   `amz_order_split_log` 全仓零插入点（拆分日志分区因此是只读，空态文案直接写明「没有写入方」）；
2. `shipping_address` 条件字段永远判不出来（Order 模型没有地址字段），DDL 预置的 PO Box / APO /
   同地址三条规则只会进 `unevaluatedRules`，把 verdict 抬到 REVIEW；界面对这类规则标「永远判不出来」；
3. 路由建议只给仓库**类型**。

后端改动（`OrderAuditServiceImpl#routeOrder`）：删掉 `warehouseName = country + "-FBA-Warehouse"`
这类拼接并入库。仓库主数据 `amz_warehouse` 属于物流模块，订单模块既无 mapper 也无 Feign 通道，
所以这条链路能真实判定的只有仓库类型；`warehouse_id`/`warehouse_name` 保持为空，`selected_reason`
写明「仅仓库类型建议；具体发货仓未解析，需物流模块确认」。`amz_shipment_routing.warehouse_name`
可空（V1 DDL），置空不会写失败。

## 三、验证数字

| 闸口 | 结果 |
| --- | --- |
| `npx vue-tsc --noEmit` | 0 错误（exit 0） |
| `npm run test:run` | 28 文件 / 264 测试全绿（新增 CustomerService 15 + OrderAudit 15） |
| `npm run build` | 成功，`OrderAudit-*.js` 23.84 kB、`CustomerService-*.js` 34.50 kB 独立分块 |
| `npx playwright test` | 58 全绿（新增客服 7 条、审单 7 条） |
| `python tools/release/repository_hygiene.py` | 0 finding；router 因内容变化重钉 sha256（`abda8cec…`，仍是同一条既有误报 `localStorage.getItem('token')`） |
| `mvn -pl :amz-service-order -am test -Dtest='OrderAudit*'` | 12 测试全绿（含新增 `OrderAuditRoutingTest` 3 条） |

变异检查（改回旧行为看是否被抓红，全部抓红后已复原并核对 sha）：

- 后端：重新拼 `US-FBA-Warehouse` → `OrderAuditRoutingTest` 断言红
  （`expected: <null> but was: <US-FBA-Warehouse>`，非编译错误）。
- 前端 8 项：错标签收尾、绕过删除确认、事件下拉不按启用过滤、回填键进请求体、去掉已加载判定、
  必填校验失效、判不出来/仅建议标注消失、空数组时补一行 → 全部红。
  其中「空数组补一行」第一轮是**静默绿**：用例只覆盖了 `Array.isArray` 为真的分支，
  补了「后端返回非数组也不补行」的用例后才抓红。`clickBtn` 辅助也改成要求标签唯一——
  它当场抓到「批量审单」的按钮与分区同名，之前点到的是 Tab。

## 四、E2E 冷启动抢跑（本次只做定量，未完全消解）

现象：整包冷启动跑 E2E 时随机红 1~3 个用例，且不限于新页面。两类：

1. `TypeError: Failed to fetch dynamically imported module: /src/views/Xxx.vue`
   —— 页面只剩空壳，用例等 30s 超时；
2. `page.goto(...)` 30s 不返回（waiting until "load"）。

机制（第 1 类）：路由组件全是动态 import，vite 的依赖预扫描从 `index.html` 出发爬不到它们，
第一次请求某个页面时才现场发现依赖并重启优化，打断在飞的模块请求。
修法：`vite.config.ts` 加 `optimizeDeps.entries`（`index.html` + `src/main.ts` + 全部 views/components）。

实测对比（本机，同一条 `npx playwright test`，workers=1）：

| 配置 | 轮次 | 结果 |
| --- | --- | --- |
| 改前（含一个中间方案：fixture 里预热模块清单） | 4 轮冷启动 | 每轮红 1~3；`Failed to fetch dynamically imported module` 出现在 2 轮 |
| 改后 `optimizeDeps.entries` | 2 轮冷启动 | 第 1 类 **0 次**；第 1 轮仍红 1 个（`page.goto('/inventory')` 挂住，即第 2 类），第 2 轮 58/58 |

结论：第 1 类（模块 fetch 被打断）由这个配置消解；第 2 类（goto 不收敛）**没有消解**，
是既有 `page.route('**/*')` 打桩层在冷机上的挂住问题，发生率低（本次 2 轮 1 次），
且不在我这批用例里（挂在 InventoryMonitor / all-pages 的旧用例上）。CI 配 `retries: 1`，
单个第 2 类失败会被重试吸收；若连续两轮都红就要按第 2 类继续查，不要归到本次改动。
中途试验过的 fixture 级预热（`e2e/support/warmup.ts`）实测无收益，已删除，不留半套机制。

## 五、第 6 项剩余

- **N1**：report 模块 5 个 POST 摄取端点 `@ShopScoped` 空转（shopId 在 `@RequestBody` 里，
  切面只认 `@PathVariable/@RequestParam` 的同名参数）且整个模块没有 `@RequireRole`。
  本次又看到同族一处：`/order/audit/rule/{id}` 的 toggle/delete/update 只按 id 操作，
  不校验规则属于哪家店（删除确认文案里已把这句写进界面提示，因为改切面会波及定时任务与内部调用）。
- **N4**：搜索 4 个端点（`/search/search/{key}`、`getHotList`、`getHistoryList`、`deleteHistory`）
  前端零调用。
- **N5**：12 张零引用表的删/留决策，其中 `amz_purchase_approval`、`amz_logistics_quote` 是真重复。
- **N3 残留**：客服邮件通道仍需真实 SMTP/Messaging 客户端；RMA 的订单号不与订单表校验。
