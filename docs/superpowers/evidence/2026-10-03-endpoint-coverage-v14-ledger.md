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
