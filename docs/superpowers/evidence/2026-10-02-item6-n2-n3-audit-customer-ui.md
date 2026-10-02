# 第 6 项 N2/N3/N4 落地：客服、审单、商品搜索接入 UI，路由不再拼假仓名（2026-10-02）

范围：功能覆盖修复方案第 6 项里 N3（客服）、N2（审单）与 N4（商品搜索）。证据都取自本机实测，命令写在每节末尾。

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

## 五、N4 商品搜索（已完成，追加于同日）

`/search/**` 4 个端点此前前端零调用。新增 `src/api/search.ts` + `src/views/ProductSearch.vue`
（关键词搜索 + 结果表 + 热搜 Top10 + 我的搜索历史），路由 `/search`，侧边栏「商品搜索」。

这一域和店铺无关：历史与热搜按**登录用户**归属（`UserContext`），检索也没有 shopId 维度，
所以页面不用 `useShopGuard`，不选店铺也能用（有一条单测专门锁「没有 current_shop_id 也照常请求」）。

界面上写清的事实：

1. 检索打在 ES 索引 `amz_product`（BM25，向量可用时 RRF 融合）。ES 不在线时
   `elasticsearchOperations.search` 抛出 → 接口报错，前端**不退回示例数据**，且把上一次的
   成功结果清掉（否则失败后旧结果看起来像「这次也查到了」）；只有查询向量化失败才降级纯 BM25。
2. 每搜一次会写一条 `amz_history`（同用户同关键词不重复插）并给热搜 ZSET +1，
   所以搜索成功后页面会重拉历史与热搜，而不是显示上一次的状态。
3. 热搜取 Redis ZSET 前 10：集合为空时后端返回的是 `data = null`（不是 `[]`），
   页面对此单独说明「没有计数」与「计数为 0」的差别；7 天无写入自动过期、超 1000 条裁剪。
4. `amz_history` 只有 keyword 与 userId 两列，没有 id → 只能整体清空，没有按条删除端点，
   所以「清空历史」是破坏性动作，带二次确认，且历史为空时按钮禁用。

验证：`vue-tsc` 0 错；单测 12 条（页面 29 文件 / 276 测试全绿）；Playwright 63 全绿；
`npm run build` 出独立分块 `ProductSearch-*.js` 7.85 kB；hygiene 默认档 0 finding（router 再次重钉
`de3fe20a…`）。变异检查 5 项：空关键词不拦截、搜完不刷新历史/热搜、失败后保留旧结果、
清空绕过确认、空历史时按钮可点——前 4 项一次抓红；第 3 项**第一轮静默绿**，
因为用例只测了「失败时页面本来就没有结果」，补成「先成功搜一次再失败一次」后才抓红。

### 冷启动抢跑的后续观察（补充第四节）

加上 `optimizeDeps.entries` 之后又跑了 4 轮冷启动整包：0 / 1 / 3 / 0 个失败。
其中 3 个的那一轮里，有 1 个是我自己用例的真缺陷（`.empty-block` 在同页有两处，
严格模式命中 2 个元素——已改成按文案定位），另外 2 个（AdManager 的 0 行、客服分区未渲染）
在下一轮同样的代码上不复现，属于会漂移的环境类失败。
`Failed to fetch dynamically imported module` 自加入该配置后未再出现。
残留这一类的发生率约 1~2/轮，`workers: 1` 下不与产品行为相关；继续查的方向是
`page.route('**/*')` 打桩层在页面加载期间的挂起，而不是给用例加重试。

## 六、N1 越权缺口（已完成）

`ShopIdGuardAspect` 只从 `@PathVariable` / `@RequestParam` 里取名为 `shopId` 的 Long 参数，
所以「shopId 只在 `@RequestBody` 实体里」的端点挂着 `@ShopScoped` 却完全空转。本次逐个补上服务层归属：

**report 模块（5 个摄取写入口）**

- 新增 `ReportTenantGuard`（照 `AdTenantGuard` 的形状），用严格档 `UserContext.isShopAllowedStrict`；
  接进 `saveProfitDetail` / `saveInventoryTurnover` / `saveSalesDaily` / `saveBusinessOverview`
  / `saveAllocation`。
- 选严格档的理由：这 5 个端点**今天没有任何合法内部调用方**（全仓无 Feign 客户端指向它们、
  无进程内调用、前端 `api/report.ts` 明确不接），所以没有需要放行的来源；
  宽松档 `isShopAllowed` 在「无店铺列表」时放行是为了兼容定时任务，用在这里等于不校验。
  将来真要让调度器或别的服务写，正确做法是把端点声明成双信任（`@InternalServiceAccess` +
  `isShopAllowedByUserOrTrustedService`），文档里写死了这条路径，避免有人回头放宽这里。
- `saveAllocation` 此前连 `shopId` 是否为空都不检查，会带着 null 直接 insert。

**order 模块（审单规则按 id 的写操作）**

- `createRule`：校验请求体实体的 shopId；
- `updateRule` / `toggleRule` / `deleteRule`：先 `selectById` 取出行，再判 `rule.shopId` 是否
  在调用方授权店铺内；「行不存在」与「不是你的店铺」返回同一句话，不把「这条 id 属于哪家店」泄露给探测者；
- 顺带修掉诚实性问题：旧 `toggleRule` 对不存在的行静默 no-op、Controller 仍返回 `true`，
  等于把「什么都没做」显示成「已启用/已停用」；现在会报错。前端 OrderAudit.vue 的删除确认文案与
  那条注释原样记录了旧行为，一并改到新语义（含单测与 E2E 的断言）。

**测试与变异**

- 新增 `ReportTenantGuardTest`（6 条：越权拒 / 缺 shops claim 不等于放行 / 无身份拒 / 命中放行 /
  ADMIN 短路 / null 报属性缺失）与 `ReportIngestTenantWiringTest`（6 条：5 个入口逐个点名越权被拒且
  `verifyNoInteractions(mapper)`，另加不带 shopId 的费用分摊）；
  `OrderAuditRuleOwnershipTest` 14 条覆盖 toggle/update/delete/create 的本店、他店、不存在、
  跨店移动、ADMIN 与统一错误信息。
- 模块全量：order 177 测试、report 63 测试全绿。
- 变异 6 项逐个拆线：4 个 report 守卫拆线各自让对应接线用例红、费用分摊拆线红 2 条
  （写入与不带 shopId）、把归属判定改成「只查存在」后恰好红掉 4 条跨店用例而不影响存在性用例。
  中途有一次变异把整条 `if` 语句替换掉导致编译错误——那不算被抓，已换成合法的等价改写重跑。
- 前端：`vue-tsc` 0 错，29 文件 / 276 测试绿，Playwright 63 全绿，hygiene 0 finding。

## 七、第 6 项剩余

- **N1 残留**：已收。report 的 7 个写端点（5 个摄取 + `POST /profit/snapshot` +
  `POST /profit/allocate/{shopId}`）现在都有 `@RequireRole({"OPERATOR","ADMIN"})`，
  并新增 `ReportWriteEndpointAuthorizationContractTest`：反射清点「写接口必须同时有
  @RequireRole 与 @ShopScoped」并钉住数量为 7（新增写接口时必须显式改数），
  再用 `AspectJProxyFactory` 把真切面套到真 controller 上验 VIEWER 被拒（code=400 且服务层
  一次都没被调用）、OPERATOR 放行、以及「受信服务身份先放行」这条豁免确实存在——
  内部调用不会因这次收紧被打断。摘掉注解、往角色表里塞 VIEWER 两种变异都被抓红。
- **N5**：清点已用脚本固化并可复核（`tools/schema/zero_reference_tables.py`），逐张处置见
  `2026-10-03-n5-zero-reference-tables.md`。`amz_purchase_approval` 已按「补功能而不是删表」的解法
  接上审批留痕，零引用从 12 张降到 **11 张**。剩下的只有 `amz_logistics_quote` 属真重复可删，
  删表需要单独立项（正向 DROP 迁移 + 备份恢复 + 先数行数），本轮仍未动任何表。
- **N3 残留**：客服邮件通道仍需真实 SMTP/Messaging 客户端；RMA 的订单号不与订单表校验。
