# 2026-10-03 · 项 7d：多平台订单接上界面，并把三个「会说谎的实现」改掉

`MultiplatformController` 26 个端点在前端零命中（23 个视图里没有一个是多平台），
网关 `Path=/multiplatform/**` 早就配好了 —— 也就是说这个模块整体是「后端建好、没人能进」。
本轮先做订单链路，因为在动手前查出三个实现层面的问题，直接接 UI 会把它们固化成产品事实。

## 一、动手前查出的三件事

1. **列表没有 LIMIT**。`listOrders` / `listByPlatform` 直接 `selectList`，
   一张被同步持续写入的 `amz_unified_order` 会被整表读进内存；
   而返回 `List` 时「确实没有更多」与「被返回量截断」在 HTTP 层完全同形。
   这正是本仓 P1-01 已经处理过的那一类，只是这个模块当时不在范围内。
2. **发货的越权拒绝长成了 500**。`markShipped` 抛 `IllegalStateException`
   （全局兜底成「服务器内部错误」），运单号不校验，`platform` 为 null 会 NPE。
   归属判定用的是 `UserContext.isShopAllowed`（lenient）：读完实现后确认
   它在 `userId != null` 时直接委托给 `isShopAllowedStrict`，而 HTTP 路径一定会写 userId，
   **所以「空 shops 列表的浏览器 token 能发货」这个担心是错的**；
   lenient 的放行分支只在「没有 userId」时生效（定时任务与内部调用要走它）。
   仍然改成严格版，理由是纵深防御：一个上下文没建起来的调用不该有跨店发货的通行证。
3. **全平台同步把失败吞成 0**。`syncAllPlatforms` 的 `syncPlatformSafely` 对每个平台
   try/catch 后 `return 0`，端点又只回一个 `int`：
   「Temu 超时 + TikTok 成功」与「三个平台都没新单」在页面上是同一个数字。

三条都改在代码里，不是只在页面上加一句提醒。

## 二、后端改动

- `listOrders(shopId, page)` / `listByPlatform(shopId, platform, page)`
  改走 `PageResult` + keyset（排序键 id，`LIMIT size+1` 探测），控制器改用 `Result.paged`；
  游标必须在查询**之前**解析，坏游标不该先扫一遍库。
- `markShipped`：`isShopAllowedStrict` + `CodeErrorException`；运单号必填并 `trim`；
  `platform` 为 null 时走「不支持的平台」而不是 NPE。
- `syncAllPlatforms` 返回 `record OrderSyncSummary(attempted, succeeded, failed, inserted, failedPlatforms)`。
  单平台异常仍然只记日志不外抛（一家挂不该让另外两家的同步作废），但**哪个挂了要点得出名字**。

`MultiplatformServiceImplTest` 里那条「全平台同步 - 单平台异常不影响其他平台（降级返回 0）」
按新返回类型改写，并把原来的 `assertTrue(result >= 1)` 加强成
同时断言 `failed=1` 与 `failedPlatforms=[TEMU]`；该类补了 OPERATOR + 店铺 1 的上下文，
因为发货现在是严格判定——**没有放宽校验，也没有删断言**。

新增 `MultiplatformOrderControlTest` 15 例：分页 SQL 与探测行、坏游标、
按平台过滤双条件、空 shops 列表被拒、**整个用户上下文缺失时被拒**（就是这条让 X1 变异真的红）、
他店被拒、ADMIN 无列表可放行、平台返回 false 时本地不写、按订单自己的平台回传、
订单不存在、运单号为空、以及同步汇总的两条区分（有失败 / 全成功但没新单）。

模块 `mvn test`：55 例全绿（15 新增 + 40 既有，含被改写的 1 例）。

## 三、前端 /multiplatform「多平台订单」

`src/views/MultiplatformOrders.vue` + `src/api/multiplatform.ts`，5 个端点：
`GET order/list/{shopId}`、`GET order/list/{shopId}/{platform}`、
`POST sync/all/{shopId}`、`POST sync/{shopId}/{platform}`、`POST order/{orderId}/ship`。

页面上明写的边界：
- 列表读本地表，「为空」不等于平台没订单，可能只是从没同步过；
- 同步结果按后端计数原样列出，并点名失败平台；「新增 0 且失败 0」才等于没有新单；
- 单平台同步给的是**本次新增**，不是平台总单量；若该平台真实客户端未接入，后端会返回业务失败而不是空列表；
- 发货是真实回传：平台接受才变 SHIPPED，拒绝时本地不动并把话讲明（附上订单当前状态）；
- 已是终态的订单，确认框额外提示重复回传大概率被拒；运单号预填订单上已有的值；
- **不提供**商品/消息/库存的同步按钮：那三个端点在三家 RealClient 里一律抛
  `UnsupportedOperationException`（只有 mock Profile 有样例数据），做一个必然失败的入口不是覆盖率。

枚举取自 V1 迁移列注释：platform `TEMU/TIKTOK/SHEIN`，
status `UNPAID/PAID/SHIPPED/DELIVERED/COMPLETED/CANCELED/REFUNDED`。
后端没有暴露「上次同步时间」这类字段，页面也就没有摆一个看起来像的数。

## 四、闸口与变异检查

后端 4 条变异（文件按 sha256 复原并复跑 15 例绿）：

| 变异 | 结果 |
| --- | --- |
| X1 发货归属退回 `isShopAllowed` | rc=1，`shipRejectsContextWithoutUserId` 红 |
| X2 越权又抛 `IllegalStateException` | rc=1，3 条红 |
| X4 坏游标不解析（静默忽略分页） | rc=1，`listOrdersRejectsBadCursor` 红 |
| X5 按平台查询丢掉平台条件 | **SKIP**：锚点在文件里命中 2 次，脚本没有替换（平台条件本身仍被 `listByPlatformFiltersBothShopAndPlatform` 断言） |

前端 3 条变异（`MultiplatformOrders.vue` 复原后 12 例绿）：

| 变异 | 结果 |
| --- | --- |
| Y1 发货后刷新清掉「平台未接受」的错误条 | rc=1，对应用例红 |
| Y2 空运单号也照样发请求 | rc=1，对应用例红 |
| Y3 同步不再二次确认 | rc=1，对应用例红 |

**这里有一条必须记下来的过程失败**：第一轮跑 X1 时 `rc=0`，14 例全绿 ——
也就是我写的「空 shops 列表也能发货」那条守卫测试**根本抓不到 lenient/strict 的差别**。
原因是 `isShopAllowed` 在 `userId != null` 时直接委托给 strict，而用例的上下文一直带着 userId。
补了 `shipRejectsContextWithoutUserId`（清掉整个上下文）之后 X1 才真的红。
如果没有跑这条变异，我会在文档里写下一个不存在的漏洞、并让下一个人按假前提行事。

- 后端：`MultiplatformOrderControlTest` **15 例**；模块 `mvn test` **55 例全绿**（含被改写的那条既有用例）；
- 前端单测 **34 文件 / 354 例全绿**（本轮 +1 文件 +12 例，router +1）；
- `vue-tsc --noEmit` rc=0；`vite build` rc=0（`MultiplatformOrders-*.js` 9.90 kB）；
  `repository_hygiene.py` 0 findings（router 又变了，pin `ae8fd5ea…` → `b0fca381…`，只改这一行）；
- E2E 新增 3 例（列表与边界文案 / 全平台同步点名失败平台 / 发货回传要运单号且真发 POST），
  `/multiplatform` 五条路由登记进 hermetic 桩（按平台的列表要排在通用 `list/` 之前），
  `NAV` 清单第 18 项；`--grep MultiplatformOrders` 3 passed，本轮未重复全量本地跑（理由见 7c 文档）；
- 清点快照 v5：未点名端点 98 → 93，用户可见候选 78 → **73**，
  `MultiplatformController` 26 → 21 条待接；分母 359 不变（本轮只改实现与签名，没有新增注解）。

本轮还抓到一个**重复出现三次的同类缺陷**：动作完成后刷新列表时把
「刚才那个动作没成功」的错误条一起清空（连接器重放、广告规则出建议、这里是发货回传）。
前两处是写的时候避开的，这一处是写完被自己的用例抓出来的。
现在这一族的写法固定为：`load(append, clearErrors)` + 动作后 `load(false, false)`，
并且每条都配一条「消息必须活过刷新」的用例。


## 五、剩下的 21 条多平台端点（已逐条读过实现，下一轮的地图）

| 组 | 端点 | 判定 |
| --- | --- | --- |
| 平台账号 | account POST/PUT/DELETE、account/list、`POST account/{id}/test` | 该接 UI；但 `test` 只做格式校验不发网络请求，接之前要么改名为「校验配置格式」要么补真实 ping |
| 商品映射 | product/list、product/{id}/map | 该接 UI（真实 DB）；product/sync 三家未实现 |
| 消息 | message/list、message/{id}/assign | 该接 UI；`reply` 只写本地库、没有平台回传契约 → 需要产品确认「仅内部备注」是否成立 |
| 库存 | inventory/list、inventory/aggregated | 该接 UI（真实查询）；inventory/sync 未实现 |
| Webhook | webhook/list | 该接 UI；`POST webhook/{platform}/{eventType}` 是平台回调入口，不给浏览器 |
| ISV 应用 | oauth/app、app/{id}/rotate、app/list | 该接 UI（管理员操作）；`oauth/token` 是 appKey/appSecret 换发的机机接口，不给浏览器 |
| 同步 | product/message/inventory 的 sync/** | 后端本身缺实现（真实客户端抛不支持），不做入口 |
