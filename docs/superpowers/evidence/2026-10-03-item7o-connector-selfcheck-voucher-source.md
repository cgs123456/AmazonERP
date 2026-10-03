# 7o — 连接器中心接进程自描述与操作目录，采购页接凭证来源

日期：2026-10-03。候选数 42 → **39**（v13）。

## 1. 接的三条（都不需要外部凭据）

| 端点 | 后端事实 | 前端位置 |
| --- | --- | --- |
| `GET /spapi/status` | `ConnectorSelfDescription.of`：固定键 service/connector/profile/mockClientsActive/startupCheckRan/startupRequireCredentials/loadedCredentialCount，**不含任何密文** | 连接器中心顶部「SP-API 进程自检」卡 |
| `GET /spapi/operations` | `SpApiOperationCatalog` 静态目录（operationId/family/method/path/requiredPathParameters/bodyRequired/…），`@RequireRole({VIEWER,OPERATOR,ADMIN})` | 同页「可调用操作目录」表，>20 条时折叠 |
| `GET /procurement/order/voucher-source/{shopId}` | 真表 `amz_purchase_order`，只出 QC_PASSED/RECEIVED/COMPLETED，keyset 游标分页 | 采购页新 Tab「凭证来源（可入账）」 |

`/spapi/status` 的存在理由值得写在页面上而不是只写在代码里：验收 runbook 的硬约束 C1
要求「被测服务必须以 `SPRING_PROFILES_ACTIVE=prod` 启动，违反则整份验收记录作废」，
而在这个端点出现之前，这条约束在进程外**没有任何核验渠道**——mock 档下
`ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 返回离线样例，据此产出的"成功样例"是假证据。
卡片上那句 `C1` 说明就是为了让下一个想"顺手做个健康检查"的人知道它不是健康检查。

## 2. 三条刻意不接的口径（都写在界面上）

- `POST /spapi/operations/{operationId}`：目录只列不用。它需要已存店铺凭证，
  而且它的 `@ShopScoped` **实测不生效**（路径里没有 `Long shopId` 参数，`ShopIdGuardAspect:63-67` 直接放行），
  真正的守卫是服务内显式的 `isShopAllowedStrict(request.getShopId())`。
  把一个能写远端的通用执行器摊成页面上的按钮，等于给每个登录用户一个任意 API 调用面板。
- `POST /spapi/credential`：`@Deprecated` 的旧入口（只允许 ADMIN，新写走 `PUT /spapi/credentials/shop/{shopId}`，
  连接器页已经在用后者）。
- `POST /spapi/sync/orders`：需要真实 SP-API 凭据；库存/订单侧已有各自的重算与回补入口，不再开一个会静默失败的按钮。

## 3. 一个"看起来对、其实测不到"的断言被推翻重写

第一版给「读失败要清空旧值」写的断言是 `.kv-grid` 消失。变异（不再生成清空那行）跑出来 **exit=0，全绿**：
因为模板是 `v-if="statusError"` / `v-else-if="selfDescription"` 的互斥链，
错误分支本来就把 kv-grid 藏了，清空与否根本不可观察——那条断言守的是一个它看不见的东西。

改成盯真正会残留的地方：`.mock-warning` 那段警告用的是独立 `v-if`，不在互斥链里，
旧值不清空它就会继续挂在错误条旁边。改完再跑同一个变异：1 红 / 13 绿，断言这才算有牙。

顺带留一条通用教训：**断言要钉在"两种实现会不一样"的地方**；如果观察不到差异，
诚实的写法是"这是防御性代码"，而不是留着一条永远不会红的守卫。

## 4. 闸口

| 闸口 | 结果 |
| --- | --- |
| `vue-tsc` | 0 |
| `vitest run` | 453 tests / 39 files 全绿（ConnectorCenter 14、Procurement 20） |
| `vite build` | 通过 |
| Playwright | 新增 2 条（自检卡+目录无执行入口；凭证来源 Tab 与全量列表不同源），连同采购/连接器原有相关用例共 7 passed |
| 清点工具 | `--self-test` 5/5；候选 42 → **39**（`ProcurementController` 38 条里只剩这一条缺口已清，`SpApiOperationController` 只剩那个刻意不接的执行端点） |
| 变异检查 | **4/4 咬住**：M22 不清空旧值 → 1 红；M23 目录不显示 body 必填 → 1 红；M24 -1 当成真实条数 → 1 红；M25 凭证 Tab 改打全量列表 → 3 红。两文件还原后字节一致（`ConnectorCenter.vue 421d97409c1ff8ea`、`Procurement.vue f27c8bfc6e9ee283`） |

e2e 桩的一处刻意的不一致：`/procurement/order/list/` 返回空、`voucher-source` 返回一条 `PO-VOUCHER-77`，
这样"两个端点弄混"或"拿全量列表自己筛状态"都会在断言上立刻塌掉；
同时把 `GET /connectors` 能力清单从兜底改成显式登记——自检卡的断言不该建立在
"未登记的路径落到对象兜底、页面恰好不炸"这种运气上。

## 5. 剩余 39 条的构成（截至 v13）

- 需外部凭据/付费上游：Keepa 3、SP-API 消息 3、财务报表/费用 2、Feeds 提交、Inventory 同步、
  Sellers 参与关系、`/spapi/operations/{id}` 执行、`/spapi/sync/orders`、AdWords 关键词优化 = 14
- 造数或零鉴权且烧 token：AI 5（chat / agent chat / eval run / review analyze / selection analyze）、
  ops 扫描 3、多平台 6 = 14
- 阻塞在迁移：商品主数据 3（实体映射的列在 DDL 里不存在）+ `POST /order/saveOrder`（依赖同一实体）= 4
- 其它：`POST /user/updateImage`（OSS 占位凭据）、`GET /spapi/replenish/urgent/{shopId}`（/list 真子集）、
  `GET /ad/report/{shopId}`（已接服务的第二个入口）= 3

这 39 条到这里已经全部有明确判级与理由，判级证据分散在
`2026-10-03-item7f…7o` 各篇与 v13 快照里；剩下能动的是「凭据到位后再接」和
「商品域实体-表收敛迁移」两类工程决策，不是清点缺口。
