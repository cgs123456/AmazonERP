# 功能覆盖清点 v20（2026-10-03，第四轮）

## 结论

用户可见缺口 **33 条**，与 v18/v19 相同（本轮两次接线已在前序切片里扣掉：34→33）。
分桶 **A 13 + B 20**，C/D 仍为空。四条闸全绿：audit self-test 7/7、drift gate 101/0/0（零豁免）、
hygiene findings 0、零引用表 11/113。

**本轮新增一把尺**：反向核对「前端调用 → 后端确有该映射且方法一致」，
264 条路径字面量 / 273 个带方法的调用点，**0 孤儿、0 方法不一致**。
顺带量到一个此前的盲区：三条路径是**网关别名**（`/connectors` `/preflight` `/credentials`
→ `/spapi/**`），别名表在两处各自维护（`amz-frontend/src/utils/viteProxy.ts` 与
`amz-gateway/src/main/resources/application.yml` 的 `RewritePath`），**没有任何一致性测试**。

##  movement（快照文件实测，不是复述台账）

| 快照 | 候选 | 差在哪 |
| --- | --- | --- |
| v13 / v15 | 39 / 39 | 7j/7k 前后 |
| v16 | 36 | 删 `/ad/report/{shopId}` 前 |
| — | **35** | 第六版记录的这一步**没有快照文件**，是当时的一次内联运行 → 记为台账缺口 |
| v17 | 34 | 接「测试连接」（真探测） |
| v18 | 33 | 接「自建下单」 |
| v19 / v20 | 33 / **33** | v19→v20 集合级 diff **完全相同**（含 Feign 侧 11 条） |

## A 桶 13 条：接上去就是把必然失败或假数据摆到页面上

每条都在当前代码里重读了一遍机制，不是照抄旧判定。

| # | 端点 | 当前机制（实测位置） |
| --- | --- | --- |
| 1 | `GET /ad/keyword/optimize` | `AdServiceImpl:174` 报表行恒 `Collections.emptyList()`，`:177` 只返回 OBSERVE |
| 2 | `POST /ai/agent/memory/reminder/scan` | `AgentMemoryController:154` `!isMockProfile()` → WARN + 拒绝（正文是写死示例） |
| 3 | `POST /multiplatform/product/sync/{shopId}/{platform}` | 三家 Real 客户端 `unimplemented()` |
| 4 | `POST /multiplatform/message/sync/{shopId}/{platform}` | 同上 |
| 5 | `POST /multiplatform/inventory/sync/{shopId}/{platform}` | 同上 |
| 6 | `POST /multiplatform/message/{messageId}/reply` | **本轮新语义**：先真发，三家无 method 依据 → 显式拒绝，一行不写（`PlatformSendRefusalTest` 钉住） |
| 7 | `POST /multiplatform/oauth/token` | 机机换发接口，不给浏览器；附 #57 安全项 |
| 8 | `POST /ops/review/scan/{shopId}` | `OpsServiceImpl:72` 非 mock 抛业务拒绝（不再 `return 0`） |
| 9 | `POST /ops/hijack/scan/{shopId}` | 同上 |
| 10 | `POST /ops/rank/capture/{shopId}` | 同上 |
| 11 | `GET /ops/selection/competitors/{asin}` | `ProductSelectionServiceImpl:191/196` 仅 mock 档可用，否则 `Result.failure` |
| 12 | `POST /ops/selection/keyword` | `:237/242` 同上（且会落库） |
| 13 | `POST /user/updateImage` | `OssUtil.requireConfigured()`：accessKeyId/bucket 仍是占位值即拒（`OssUtilConfigGuardTest`） |

## B 桶 20 条：代码是真的，缺外部凭据

| 组 | 条数 | 端点 | 缺的东西 |
| --- | --- | --- | --- |
| AI | 4 | `/ai/chat`、`/ai/agent/chat`、`/ai/agent/memory/chat`、`/ai/review/analyze` | `DEEPSEEK_API_KEY`；未配置时 `AiServiceImpl:46`、`LangChain4jAgentService:39` 直接 `Result.failure` 点名 |
| Keepa | 3 | `price/rank/competitor` | Keepa token |
| SP-API | 13 | `feeds/submit`、`finance/report/request`、`finance/fees/estimate`、`inventory/sync`、`messaging/actions`、`messaging/attributes`、`messaging/send`、`replenish/urgent`、`sellers/marketplace-participations`、`credential`、`sync/orders`、`operations/{operationId}`、`uploads` | 店铺 SP-API 凭证（`/spapi/credentials` 本身就是配凭证的入口，已在运营台接入） |

## 反向尺（本轮新量）

一次性脚本 `/tmp/rc4.py`（未入库，见「下一步」）：
`src/api/*.ts` 里每条**调用位**（排除注释与 doc 通配串）解析成 (方法, 归一路径)，
与 `amz-service/*/controller` 的 `@RequestMapping` 前缀 + 方法级映射集合比对。

```
带方法的调用点=273  不一致=0
前端字面量路径=264  后端映射=394  孤儿=0
SELFTEST 5/5 passed（方法集合、缺失路径、别名表都能被这套比对观察到）
```

自证不是形式：写第一版断言时我误把 `norm('/api/connectors/outbox')` 当成应走别名表，
自证那行**直接把脚本跑挂**（`/api` 前缀由 axios baseURL 与代理剥离，不在字面量里），
说明这套比对确实会因错误预期而红，不是恒真。

第一次跑（未剥注释）报了 19 条孤儿，其中 12 条是 doc 注释里的 `**` 通配串——
「注释不是调用」这条老规矩在这把新尺上重演了一次；剥掉注释后剩 7 条，全是别名路径。

## 零引用表（本轮复测）

`tables_total=113 zero_reference=11`：`amz_ad_placement_report`、`amz_attention`、`amz_cart`、
`amz_coupon`、`amz_customer_service_kpi`、`amz_listing_seo`、`amz_logistics_quote`、
`amz_oper_log`、`amz_product_browse`、`amz_report_template`、`amz_user_coupon`。
与 7n 的处置一致：`amz_order_attribute` 有插入点且本轮仍有回归测试钉住，不在这一列。

## 未量到（这轮尺子看不见的，不当成已解决）

1. **别名表两处一致性**：`viteProxy.ts` 与 gateway `RewritePath` 若漂移（改一处忘另一处），
   前端在联调档 404，而 hermetic e2e（自己拦 `/api/**`）全绿。本轮只是发现没有测试，没修。
2. Feign 声明的 11 条路径按规则免浏览器责任，但**没验证调用方是否真的调用**它们。
3. 视图→路由→侧边栏的可达性只验了本轮新增页那一条（NAV 用例）；没有全量「孤儿路由」尺。
4. e2e 桩的形状与后端真实响应形状的一致性仍是人工核对。
5. CI `test` 作业自 V10 起红（#56）：一次性 MySQL 因探针容器 root 账号没建好而**结论不确定**，
   所以「V10 导致 CI 红」既没证实也没洗清。
6. 参数名/必填位（`@RequestParam` 名称漂移、body 字段与 DTO 漂移）不在这把尺的口径内。

## 建议的下一步（按本轮新暴露排序）

1. 给别名表加一条跨语言一致性测试：解析 gateway yml 的 `RewritePath` 集合，断言与
   `SPAPI_PREFIX_REWRITES` 完全相等（单向漂移立刻红）。这是本轮唯一新发现的**结构性**风险。
2. 把反向尺固化进 `tools/schema/endpoint_coverage_audit.py`（同一份数据两个方向），
   否则「0 孤儿」只是一次性结论，防不住后续接线打错路径。
3. B 桶 20 条要有进展只能靠外部凭据；A 桶 13 条按当前口径已是终态（显式拒绝优于假成功）。
