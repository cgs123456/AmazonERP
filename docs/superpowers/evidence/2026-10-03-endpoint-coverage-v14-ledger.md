# 端点覆盖清点 v14：剩余 39 条逐条判类（收口台账）

生成：`python tools/schema/endpoint_coverage_audit.py .` → `controllers=60 endpoints_without_a_frontend_name=60 user_facing_candidates=39`
（`--self-test` 5 例通过后才引用这个数字；闸口自检不通过时这份表没有意义）

口径：闸口数仍是 **39**，因为 24–26 三条只是后端改为拒绝、前端本来就没接。
「已收口」是判定而不是接线，闸口看不出区别——这是闸口的边界，不是它的缺陷。
分桶合计 14+19+3+2+1=39。本轮相对 v13 的变化：24–26 从「阻塞在迁移」变为「已 fail-fast 收口」（见 7p），
新增一条同类硬缺陷 `amz_replenishment_suggestion`（见 7q 第 5 节，待用户定夺）。

## A. 后端会必然失败或必然造数，故意不给按钮（14 条，含文末更正移入的 9/12/14）

| # | 端点 | 判定依据 |
| --- | --- | --- |
| 4 | `POST /ai/agent/memory/reminder/scan` | 7j 加了 mock 门禁：非 mock Profile 直接拒绝并 WARN 指名当前 profile |
| 10 | `POST /multiplatform/product/sync/{shopId}/{platform}` | RealClient 侧 `AbstractPlatformClient.unimplemented()` 抛 `UnsupportedOperationException`，只有 mock Profile 有样例数据 |
| 11 | `POST /multiplatform/message/sync/{shopId}/{platform}` | 同上 |
| 13 | `POST /multiplatform/inventory/sync/{shopId}/{platform}` | 同上 |
| 15 | `POST /ops/review/scan/{shopId}` | `OpsServiceImpl:72-75` `mockGeneratorsAllowed()` 为假时返回 0；开启时插入的是 `ThreadLocalRandom` 造的假 ASIN/评论 |
| 16 | `POST /ops/hijack/scan/{shopId}` | 同 15 |
| 17 | `POST /ops/rank/capture/{shopId}` | 同 15 |
| 18 | `GET /ops/selection/competitors/{asin}` | 7k 加了 mock 门禁 + 前端「模拟数据」标；生产 Profile 拒绝 |
| 19 | `POST /ops/selection/keyword` | 同 18 |
| 2 | `GET /ad/keyword/optimize` | 7n 实测：报表行恒为 `Collections.emptyList()`，优化建议恒为 `OBSERVE`——按钮点下去只会永远一个答案 |
| 9 | `POST /multiplatform/account/{id}/test` | 7f 已判定：它只校验端点字符串格式，却会把账号 `status` 改写成 ACTIVE/ERROR——一个没发过包的检查不该改「账号是否活跃」。接按钮前要先定后端口径（api/multiplatform.ts:95-97 同条） |
| 12 | `POST /multiplatform/message/{messageId}/reply` | 7f 已判定：只往本地库写一条回复，不会发到平台或买家；「回复」按钮等于对客服说已回复而买家什么都没收到 |
| 14 | `POST /multiplatform/oauth/token` | 7c/7d/7f 三处已判定：机机换发接口，不给浏览器。附带一条独立的安全观察（不属于覆盖率口径）：`appSecret` 目前是 `@RequestParam`，即便调用方是服务器，密钥也会进网关访问日志 |
| 20 | `POST /order/saveOrder` | 经 `ProductClient.getProductById` 打进 7p 已证死的 `amz_product` 旧列通路；且「允许无商品的裸订单吗」是产品口径，不是覆盖率 |

A 类的共同点：**接上去就是把必然失败或假数据摆到页面上**。前 9 条在页面上的正确形态是
「mock 环境才有的模拟数据标」（已做），不是生产可用按钮。

## B. 缺外部凭证/付费上游，代码路径本身是真的（19 条）

| # | 端点 | 缺什么 |
| --- | --- | --- |
| 3 | `POST /ai/agent/memory/chat` | `deepseek.api-key`；记忆表由这条写入，页面已说明「表为空≠没聊过」 |
| 5 | `POST /ai/chat` | 同 3，且与已接的 `GET /ai/chat-stream`（SSE，已接）是同一能力的同步孪生 |
| 6 | `POST /ai/agent/chat` | 同 5；业务工具链已接 `/ai/erp/agent`，不重复铺 |
| 8 | `POST /ai/review/analyze` | 模型 key |
| 21-23 | `GET /product/keepa/{price\|rank\|competitor}/{asin}` | Keepa 付费 key；返回体是上游 JSON 透传，`@ShopScoped` 只是装饰（7n 已记） |
| 27 | `POST /spapi/feeds/submit` | 店铺 LWA 凭证 + 真实 Feed 副作用（写亚马逊），属不可逆外部动作 |
| 28-29 | `POST /spapi/finance/{report/request\|fees/estimate}` | 同上 |
| 30 | `POST /spapi/inventory/sync/{shopId}` | 同上 |
| 31-33 | `GET /spapi/messaging/{actions\|attributes}`、`POST /spapi/messaging/send` | 同上；`send` 会真的发消息给买家 |
| 34 | `GET /spapi/replenish/urgent/{shopId}` | 凭证 + 是 7l 已接补货列表的 urgent 子集，重复口径 |
| 35 | `GET /spapi/sellers/marketplace-participations` | 凭证 |
| 36 | `POST /spapi/credential` | **它本身就是录凭证的入口**，当前由配置文件/运维流程提供；做成 UI 需要单独的密钥存储设计（见 ConnectorCenter 的 selfcheck 面板已把「有没有凭证」显示出来） |
| 37 | `POST /spapi/sync/orders` | 凭证；7o 的连接器自检面板已给出该店凭证状态 |
| 38 | `POST /spapi/operations/{operationId}` | 凭证 + 任意操作号的通用调用入口，暴露到页面等于把 SP-API 当 curl 用 |

B 类的共同点：**能接通的前提是外部密钥**，而这台机器/这个仓库里没有真密钥（7o 已经把
「哪家店有可用凭证」做成面板，所以缺凭证这件事在界面上现在是可见的，不是靠按钮去试）。
`/spapi/operations/{operationId}` 与 `/spapi/credential` 还额外有「不该给业务用户裸接口」的判断。

## C. 已处置：后端 fail-fast 收口（3 条，本轮）

| # | 端点 | 处置 |
| --- | --- | --- |
| 24 | `GET /product/getProductList` | 7p：入口拒绝 + 指名 `/product/master/list/{shopId}` |
| 25 | `GET /product/getProductsByShop/{productId}` | 同上 |
| 26 | `POST /product/postProduct` | 同上（另有 `getProduct`、`updateProduct` 两个 Feign 入口也一并收口） |

## D. 口径重复，不另开入口（2 条）

| # | 端点 | 说明 |
| --- | --- | --- |
| 1 | `GET /ad/report/{shopId}` | 与已接的广告报表同口径，7n 已把 spend/sales/acos 改为真实关联；再开一个只多一份数字来源 |
| 39 | `POST /user/updateImage` | 头像上传指向阿里云 OSS，配置项是 `your-access-key-id`/`your-bucket-name` 占位；7m 的个人资料页因此只接文本三字段，图片以文本框呈现 |

## E. 值得下一轮做的真缺口（1 条：#7；原列的 9/12/14 已下移到 A，见文末更正）

| # | 端点 | 为什么算真缺口 |
| --- | --- | --- |
| 7 | `POST /ai/eval/run` | 12 条评测用例是**已存在且真实执行**的后端能力。已读到实现：`AiController:113-124`，缺省 `mode=keyword` 走 `agentEvalRunner.runAll()`，**不需要模型 key**，并把每次运行落库 `amz_agent_eval_log`（best-effort）；`mode=both` 才要求 `AGENT_LLM_EVAL_ENABLED=true` + `deepseek.api-key`，否则返回明确的失败文案。也就是说它缺的不是凭证而是入口：没有任何界面能跑一次 prompt 回归。做成面板时要把「both 未配 key ⇒ 直接失败」如实显示 |

B 类里唯一需要补一句的是 `POST /ai/review/analyze`：已读到 `ReviewAnalysisServiceImpl:49-62`
在空列表时才走「无评论数据可分析」，非空即 `buildPrompt → callDeepSeek`，确实需要模型 key，
不是规则兜底——所以它留在 B 而不是 E。


## 判定方法（避免下次重复审）

- 每条都先看**写/读的真实来源**：mapper 是否真查表、客户端是否真发请求、
  `mockGeneratorsAllowed()`/`isMockProfile()` 门禁是否存在（7k/7j 加的）。
- 再看**接线后用户能否拿到真答案**。拿不到就不接，把原因写进 `src/api/*.ts` 的头注释
  （多平台那份是范本），这样闸口的「未命中」是已知选择而非未知缺口。
- 数字要能自证：v13=39、v14=39，两条都是 `--self-test` 通过后的运行；
  本轮把 3 条从「待迁移」挪到「已收口」，闸口数不变（闸口只看前端名字）。
- 剩余漂移由 `tools/schema/entity_column_drift.py`（含 `--self-test`）给出 5 条，
  其中 `amz_replenishment_suggestion` 是唯一落在已接线路径上的，见 7q 第 5 节。

## 更正（2026-10-03 同日，本台账发布后）

E 桶原本写「9/12/14 是真实回传/真实签发，值得接线」——**错了**，而且错的证据就在我自己
两天内写下的三份文档里：`item7f-multiplatform-ops.md:21-23` 明确判定 `account/{id}/test`
只做端点格式校验却改写账号 status、`message/{id}/reply` 只写本地库不会发到平台、
`item7c/7d` 两处写明 `oauth/token` 是机机接口不给浏览器；`src/api/multiplatform.ts:95-97`
也把同样的理由写在了代码注释里。判定时我只读了 Controller 与 Service 的实现形状，
没有回读这三处已有结论，于是把「代码不抛 UnsupportedOperation」误当成「接上就有真答案」。
已把这三行移入 A 桶并注明出处；E 桶只剩 `POST /ai/eval/run`（本轮 7s 已接线）。

教训写在这里而不是删掉原文：**判类要按端点逐条回读既有结论**，尤其是那种
「实现看着像真的、但语义会让页面说谎」的情况。appSecret 进查询参数这条保留，
但它是安全项（#54 已改写），不属于覆盖率口径。

**2026-10-03 后续（7u）**：核对器 5 → 3 条。`amz_history.history→keyword` 与 `amz_order_attribute.label→name` 两处**已接线路径上**的错映射已修并各自加了列名契约测试；
剩下 3 条里 `amz_replenishment_suggestion` 仍是唯一未决的已接线漂移（#52），另两条是已收口的 `amz_product` 与孤儿实体 `product/pojo/Shop`（删除需先证明不可达，单独处理）。
## 分母订正（同日第三版）：闸门的解析盲点让计数少算了一条

完成度自查时发现 `parsed_method_annotations=359 unparsed=1`：那条是
`UploadsController` 的 `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`——
属性型注解没有路径字面量，解析器判成「解析不出来」，于是这条端点**从来没进过分母**。
Spring 的语义是路径取类前缀，所以它等价于裸 `@PostMapping`，是可以确定的，不该丢。

订正后实测：`parsed_method_annotations=359 unparsed=0`，
`endpoints_without_a_frontend_name 59 → 60`，`user_facing_candidates **38 → 39**`。
也就是说我之前几轮引用的 39/38 一直被自己的工具少算 1 条。

新进入视野的 `POST /spapi/uploads` 判入 **B 桶（缺外部凭证）**，依据写在类注释里：
它是 Amazon Uploads 的服务端闭环（服务端算 MD5 → createUploadDestinationForResource → PUT 预签名 URL，
只回 uploadDestinationId，预签名 URL 不进响应以免被网关日志/浏览器拿到写权限），
且整类标了 `@Profile("!mock")`——mock 环境下这个 bean 根本不注册，浏览器接上去是 404，
生产环境没有真凭据也只能失败。所以不接按钮是对的，但**这条曾经不在清单上**，
差别就在于我有没有把「解析不出」当成「不存在」。

闸门自检从 5 项扩到 7 项：属性型 mapping 必须解析成类前缀；`@PostMapping(UPLOAD_PATH)`
这种常量路径必须继续被披露、不许猜。

两个失误留在记录里：(1) 第一次把新用例插到了 `failed` 统计之后，输出照报 `SELFTEST 5/5`——**计数没动就是没跑**，这条判据救了我一次；(2) 移动代码块时把两行 `if` 插进了跨行 `print` 的续行之间，脚本直接 SyntaxError。
## 收线补记（同日第四版）：旧商品死面整体删除，候选 41 → 36，漂移豁免清零

`59b03d7` 把订单金额的来源改回消息本身之后，`/product/getProduct/{id}` 与 `/product/updateProduct`
失去了唯一的 Feign 调用方，被闸口重新计成 user-facing 候选（39 → 41）。这说明 7p 的「入口拒绝」
只是止血：拒绝体仍然占着端点位置，而且它把「两条端点本来没人用」这件事盖住了。

于是按「确实无用就清理」把它们撤干净：`ProductController` 的 5 个旧映射、`ProductService`、
`ProductServiceImpl`、`ProductMapper`、`pojo/Product`、`ProductDto`、`ProductVo`、
`pojo/ProductAttribute`（全仓零引用）一并删除。删前逐类证明隔离：除这块死面自身与两个专属测试外，
没有任何引用（search 模块的 `ProductVo` 是另一个模块里的同名类，不是同一份代码）。

| 指标 | 删前 | 删后 |
| --- | --- | --- |
| user-facing 候选 | 41 | **36** |
| 无前端名的端点数 | 60 | 55 |
| 列漂移 hard-mismatch | 1（amz_product） | **0** |
| 闸门豁免清单 PENDING | 1 条 | **0 条**（此后任何漂移都直接红） |

分桶随之改写：A 14 + B 20 + D 2 = 36，**C 桶归零**（那 5 条不是「收口」，是不存在了）。
随死面一起删掉的两个测试要记明白，别当成「为了变绿删断言」：
`ProductLegacyDriftFenceTest`（守卫 5 个入口的拒绝行为）——被守卫的端点已经不存在；
`ProductServiceImplSearchPagingTest`（P1-01 硬编码 LIMIT 20 的回归）——`searchProducts` 从未挂在
任何 HTTP 映射上，随 `ProductService` 一起删除。防同类回归的职责已由「实体↔建表零豁免闸门」承担，
它在 CI 的 hygiene 作业里先跑 `--self-test`（12 项）再跑 `--gate`。

## 收线补记（第五版）：D 桶清空，候选 36 → 35

- `GET /ad/report/{shopId}`：证明零调用方（前端只用 `/ad/reports`，无 Feign 引用）后删除，D 桶 -1；
- `POST /user/updateImage`：改为「OSS 未配置即显式拒绝」，从「口径重复」移出，归入已收口一类。

现在 35 条 = A 类 15（必然失败/造数/未配置，含 updateImage）+ B 类 20（缺外部凭据）。
D 桶清空；C 桶已随死面删除归零（见上一版）。明细见 `2026-10-03-item7x-oss-guard-and-dup-endpoint.md`。

## 收线补记（第六版）：ops 三条扫描的「静默 0」改成显式拒绝

A 桶里 `/ops/review/scan`、`/ops/hijack/scan`、`/ops/rank/capture` 原来在非 mock 档 `return 0`，
控制器包成 `Result.success(0)`——「扫过没有」与「没实现」在协议上同形。现改为抛业务拒绝，
并给定时任务加同档跳过（否则每轮为每家店铺刷 ERROR）。条数不变（仍 35），但 A 桶的语义从
「静默假成功」变成「显式不可用」。明细见 `2026-10-03-item7y-ops-scan-silent-zero.md`。

## 收线补记（第七版）：A 桶的「测试连接」改真探测并接进页面，候选 35 → 34

A 桶第 9 条 `POST /multiplatform/account/{id}/test` 的判定被推翻了一次：原先理由是「它只校验
端点字符串格式，却会改写 status/lastSyncTime，接按钮前要先定后端口径」。口径定完（自检版：只回结果
不改状态）之后发现更根本的问题是**这条端点本身没在探测**。现在它复用三家真实客户端都已实现的
`fetchRecentOrders` 发一次已鉴权读，状态由平台是否回话决定，`lastSyncTime` 一个字节都不写；
亚马逊由控制器 `guarded` 转成点名的业务失败，不再被全局兜底成「服务器内部错误」。
前端运营台账号行加了「测试连接」，探测后重拉列表（结论只在状态列上）。

分桶随之改写：A 14 + B 20 = **34**。A 桶里剩下的多平台两条仍是「接上去就是假动作」：
`message/{messageId}/reply`（只写本地备注，买家收不到）与 `oauth/token`（机机接口）。
明细见 `2026-10-03-item7z-platform-connection-probe.md`。

## 收线补记（第八版）：A 桶的 `/order/saveOrder` 接成「自建下单」页，候选 34 → 33

用户点名的第 1 项。接之前先量了三件事：这条链路**不写 shop_id**（所以它永远不会出现在按店铺读的
`/order/list`，只能由 `/order/getOrderList` 按登录用户读回）、`amz_order.product_id` 是 `INT`
而 `amz_product.id` 是 `BIGINT`（今天装得下，2^31 会溢出，另立一片）、以及
`saveOrder` 成功只代表「消息进队列」。

后端同时修掉三条会让页面说谎的口径：`userId` 与 `messageId` 不再认请求体
（原先任何登录者可替他人造订单，而读的是登录用户；塞已占用的幂等键可让订单被静默丢弃），
金额非法在 HTTP 入口当场拒且与 MQ 入口共用同一条判定，投递失败带上异常类型。

分桶：A 13 + B 20 = **33**。明细见 `2026-10-03-item7aa-b2c-order-page.md`。

## 收线补记（第九版）：A 桶的 `message/{id}/reply` 从「假动作」变「先真发、拒就点名」

条数不变（仍 33），但这条端点的性质换了：`replyMessage` 过去只往本地写一条 OUT 备注就把原消息
标成 REPLIED——客服页面显示「已回复」而买家什么都没收到。现在它先向平台真发，拿到平台消息 ID
才写本地两行；三家真实客户端都没有站内信发送的 method 依据，因此生产档一律
`UnsupportedOperationException`（经控制器转成 code 400 的点名拒绝），一行都不写。
`LOCAL-REPLY-` 这个"只有内部记过"的前缀随之从代码里消失。

前端仍然不给「回复」按钮，理由从"语义未定"更新为"给了只会稳定报错，等真有 method 依据再接"。
明细见 `2026-10-03-item7ab-real-send-refusal.md`。

## 收线补记（第十版）：第四轮全量清点，集合停在 33，另加一把反向尺

明细见 `2026-10-03-coverage-inventory-v20.md`。要点三条：

1. **v19→v20 集合级 diff 为空**（33 条 = A 13 + B 20），A 桶每条的机制都在当前代码里重读过，
   不是照抄旧判定；`message/{id}/reply` 的 A 桶理由已从「只写本地备注」换成「先真发、无依据即拒」。
2. **新尺度量出 0 缺口**：反向核对「前端调用 (方法, 路径) → 后端映射」，264 字面量 / 273 个带方法
   调用点，0 孤儿、0 方法不一致。第一版没剥注释时报了 19 条孤儿，其中 12 条是 doc 注释里的通配串。
3. **发现一个结构性风险**：`/connectors` `/preflight` `/credentials` 是网关别名，别名表在
   `viteProxy.ts` 与 gateway `RewritePath` 两处各自维护，没有任何一致性测试——
   一处漂移会让前端 404 而 hermetic e2e 全绿。**本轮清点后即补上**：
   `GatewayAliasContract.test.ts` 4 项断言 + 两条变异验证（改网关目标 / 加一条没人用的别名）都会红。

台账缺口一并记下：第六版记录的「36→35」那一步没有留下快照文件，35 这个中间态无法复核。

## 收线补记（第十一版）：反向接线闸门进 CI，候选 33 → 34（挤掉一条假豁免）

`endpoint_coverage_audit.py --reverse` 把四件事变成阻断检查：前端调用的路径与方法必须在后端存在且一致、
e2e 桩不能拦后端没有的形状、静态路由必须有侧边栏入口、侧边栏不能指向不存在的路由。
基线 276 调用点 / 140 桩 / 29 静态路由，0 findings；四条变异注入各由对应检查报出（详见
`2026-10-04-unmeasured-closure-reverse-gate.md`）。

候选 +1 的来源必须说清楚：`/logistics/warehouse/stock/aging/{shopId}` 原先被 `has-feign-caller`
豁免，但 ai 模块只是**声明**了这个 Feign 方法，全仓没有任何调用点（接口 + 降级实现之外再无引用，
已 grep 复核）。豁免前提「有服务间调用方」是假的，所以删掉这条死声明，端点如实浮成浏览器候选。
同类披露：`/spapi/finance/events`（`listEvents`）也声明无调用，但带 `@InternalServiceAccess`
所以不进候选，改由 `--reverse` 的 `feign-declared-uncalled` 行报出（当前 =1，不阻断）。

工具侧顺带清掉三处自身缺陷：别名表从三份手抄改成只认网关 `RewritePath`；`@RequestMapping`
类前缀只认行首（javadoc 里的例子不再被当声明）；`shape()` 剥查询串。另有两处本轮自暴：
强制泛型会让不带泛型的调用静默不进分母、Feign 方法名取到注解名导致「无调用点」虚报成 8 条。

## 收线补记（第十二版）：#56 结案 —— CI 的红是 IT 自己的字典序重放，不是 V10

一次性 MySQL 8.0.46 实测：走真 Flyway 的 `AllModulesFlywayMySqlIT` 一直全绿（14 库 × 50 迁移），
红的是 `BareSqlBuiltSchemaFlywayStartIT` —— 它用 `Files.list(...).sorted()` 按**字典序**重放迁移，
`V10__` 排在 `V1__`/`V2__` 之前（`_`=0x5F > `0`=0x30），于是 V10 的 ALTER 早于 V1 建表。
`apply_migrations.py` 的真实路径本来就有数值排序键，所以 synthetic-data 作业一直是绿的；
错的只有那个「复刻版」。V10 只是第一个把缺陷暴露出来的输入，不是缺陷本身。

修：IT 抽出 `sortedByMigrationVersion()`；`apply_migrations.py` 的 dry-run 与真实执行共用同一把钥匙；
新增不需要 MySQL 的 `MigrationOrderingContractTest`（数值序 vs 字典序、ALTER 不得早于 CREATE、
版本号唯一且首文件是 V1）。修前 IT_RC=1，修后 =0（两个 IT + 3 项顺序测试全绿）。
细节与两处自暴（第一版顺序测试把同文件内「先 CREATE 后 ALTER」的 V2 误判为违规；
参数名探针报的 8 条全是解析伪影）见 `2026-10-04-migration-order-ci-red-attribution.md`。

覆盖率计数不变（34 条候选）。边界说清楚：CI 是否转绿要等下一次 run 的日志，匿名拉不到，
这里只能说「同一输入下的精确复现已消除」。
