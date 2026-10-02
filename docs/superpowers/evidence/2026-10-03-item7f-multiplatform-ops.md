# 项 7f：多平台运营台接入（账号 / 商品映射 / 消息 / 库存 / Webhook / ISV 应用）

日期：2026-10-03。切片：7f。前置：7d（订单）、7e（租户判定）、830007d（6 个列表补 keyset 分页）。

## 这一片接了什么

`MultiplatformController` 原有 26 个端点，7d 接了 5 个（订单链路）。本片再接 **14 个**，
新页 `/multiplatform-ops`（`MultiplatformOps.vue`，6 个 Tab 各自懒加载）：

| Tab | 端点 |
| --- | --- |
| 平台账号 | `GET account/list/{shopId}`、`POST account`、`PUT account/{id}`、`DELETE account/{id}` |
| 商品映射 | `GET product/list/{shopId}`、`POST product/{productId}/map` |
| 消息 | `GET message/list/{shopId}`、`POST message/{messageId}/assign` |
| 库存 | `GET inventory/list/{shopId}`、`GET inventory/aggregated/{shopId}` |
| Webhook | `GET webhook/list/{shopId}` |
| ISV 应用 | `POST oauth/app`、`POST oauth/app/{id}/rotate`、`GET oauth/app/list/{shopId}` |

**刻意不接的 6 条**（页面上也不出现按钮，有测试把它们钉住）：

- `POST account/{id}/test`——只校验端点字符串格式，却会把 `status` 改写成 ACTIVE/ERROR
  并刷新 `lastSyncTime`。一个没发过包的检查不该改「账号是否活跃」，先定口径。
- `POST message/{id}/reply`——只往本地库写一条回复，不会发到平台或买家；摆个「回复」按钮
  就是让人以为买家收到了。
- `POST product|message|inventory/sync/**` 三条——三家的真实客户端对这些动作一律抛
  「未接入」，只有 mock Profile 有样例数据。
- `POST oauth/token`——机器对机器的换 token 端点，不是运营动作，放页面上只会诱导人把
  appSecret 粘进浏览器。

## 后端改动：PROCESSED 之前永远不会出现

`receiveWebhook` 的成功分支原先把 `RECEIVED` 又原样写回一遍，只有异常分支才改状态，
于是 V1 DDL 里的 `PROCESSED` 在整个系统里不可能出现——按状态筛选/统计都会以为事件
还堵在队列里。现在成功分支写 `PROCESSED` 并留一句人话结果：

```java
handleWebhookEvent(event);
event.setStatus("PROCESSED");
event.setProcessResult("已记录；当前事件分发只写日志，不触发业务动作");
```

新增 `MultiplatformWebhookProcessingTest`（2 例）。反证（重放历史缺陷，看测试是否变红）：

| 变异 | 结果 |
| --- | --- |
| MW1 成功分支改回 `setStatus("RECEIVED")` | rc=1，`Tests run: 2, Failures: 1`，`MultiplatformWebhookProcessingTest.70 expected: <PROCESSED> but was: <RECEIVED>` |
| MW2 去掉 `setProcessResult(...)` | rc=1，`71 expected: not <null>` |

两次变异后都按 sha256 校验还原（`restored_sha_ok=True`）。

## 前端改动里修掉的一个会说谎的实现

`deleteAccount` 返回 `boolean`，`false` 表示「一行都没删掉」（账号已不存在）。原写法
`if (ok === null) return` 只把「业务失败」当失败，`false` 被当成成功继续刷新列表——
用户看到的是「点了删除，那行还在，什么也没说」。现在 `false` 会明确报错并说明本地状态未变：

```ts
if (ok === false) pushError(`删除账号：后端没有删掉任何行（账号 #${a.id} 可能已不存在），本地状态未变`)
```

凭证相关的两条硬约束（都有测试钉住）：

1. `apiKey` 留空 = 不改。**只有填了才把该键放进请求体**，否则编辑一次就把线上凭证清空了；
   后端读取接口已经抹掉 `apiKey` 与三个 `*Encrypted` 列，所以页面没有「查看密钥」，
   编辑框也不回填。
2. 注册/轮换返回的明文 `appSecret` 只在那一次响应里出现（后端只存 SHA-256），因此
   页面只在一次性卡片里显示，「我已保存」后置空，不写进列表、不缓存。

## 变异反证（前端 8 个变异全部变红）

`src/__tests__/MultiplatformOps.test.ts`（21 例）逐条重放缺陷：

| 变异 | 变红的用例 |
| --- | --- |
| MU1 apiKey 无条件提交 | 新增账号（请求体不该有这个键）＋ 编辑账号留空不能清空凭证 |
| MU2 `false` 当成功 | 删除账号返回 false 时不能装作成功 |
| MU3 空处理人不校验 | 消息分配：处理人为空不发请求 |
| MU4 删除后的刷新改为清空错误 | 删除账号……返回 false 时不能装作成功（消息会被刷新抹掉） |
| MU5 onMounted 六个列表一起拉 | 首屏只加载账号列表 ＋ 另外 7 例（懒加载契约被破坏的连带） |
| MU6 确认文案不做 ASIN 大写归一 | 商品映射：确认文案要给出后端会做的归一 |
| MU7 切 Tab 每次都重拉 | 切回来不重复请求 |
| MU8 忽略 `_page.truncated` | 截断时给出下一页并带上游标 |

MU4 的第一版写法没有变红：我把「刷新保留错误」这件事写在了消息分配路径上，但那条路径
`run` 失败会提前 return，根本不刷新，参数是死的。因此把 `clearErrors=false` 收缩到唯一
可观察的删除路径，其余四处（新增/编辑账号、映射、分配、注册/轮换）改回默认。
**教训**：一个「看起来更安全」的参数，如果没有任何用例能因它变红，就是死代码。

## 闸口

- `mvn -pl amz-service/amz-service-multiplatform test`：**Tests run: 84, Failures: 0**，BUILD SUCCESS
- `vue-tsc --noEmit`：0 错误；`vitest run`：**35 文件 / 375 例全绿**；`vite build`：成功
- `repository_hygiene.py`：findings=0（router 改动后重新钉 sha：`b0fca381… → a68a86c3…`，
  命中项仍是既有的 `const token = localStorage.getItem('token')` 那一行）
- `endpoint_coverage_audit.py`（快照 v6）：`parsed_method_annotations=359 unparsed=1`，
  无前端命中的端点 78 条，其中用户可见候选 **58**（v5 是 73，本片 −14）；
  `MultiplatformController` 26 条 → **剩 6 条**，就是上面点名不接的那 6 条。

## E2E

新增 5 例（侧边栏跳转 + 运营台 4 例）：懒加载只打一条列表请求、账号新增的确认与请求体形状、
删除/分配的空值不发请求、轮换密钥的一次性显示与关闭后不可再取。定向跑 5/5 通过。

全量跑（94 例）本片期间两次都没全绿：第一次失败 1 个（NAV 运营台跳转），第二次失败 5 个
（OrderAudit / Procurement / WarehouseAlerts / AdBidSchedule / MultiplatformOrders）——
**失败集合每次不同，且每个单独跑都绿**。机制是 dev server 按需编译路由 chunk：跑到后半程时
第一次访问某路由要现场编译，实测冷启动 4.4s，而 `expect` 默认超时 10s。已把 NAV 那条的
URL 断言放宽到 20s 并写明原因；根修需要换成预构建服务，见下。

### 顺手量到的一个真问题：生产构建的 API 基址是占位域名

试过把 `playwright.config.ts` 的 webServer 换成 `npm run build && vite preview`（消掉上面的
冷编译抖动）。换过去之后 4 个运营台用例全红，列表一条都拿不到。根因不在页面：

```ts
// src/api/auth.ts:58
baseURL: import.meta.env.DEV ? '/api' : import.meta.env.VITE_API_BASE_URL
```

而 `.env.production` 里是 `VITE_API_BASE_URL=https://api.yourdomain.com`——占位值。
也就是说 **今天 `npm run build` 出来的包，所有请求都发往一个不存在的域名**，
hermetic 桩只拦 `localhost:5173`，于是全部 `abort` → 页面空列表。这条对用户的含义是
「部署前必须填这个 env，否则前端起来了但一寸也动不了」，比 E2E 抖动严重，先记账不擅改：
把 preview 方案落进测试环境要连带定「测试模式构建 + 把 `VITE_API_BASE_URL` 指回 `/api`」，
而 `import.meta.env.DEV` 被别的页面用来分流（如 `VITE_AD_DEMO_MODE`、AgentChat 的 WS 基址），
一次改动的爆炸半径不止 E2E。因此本片把 config 还原成 `npm run dev`，把结论和这段踩坑
留在注释与本文里。


## 遗留

- `.env.production` 的 `VITE_API_BASE_URL` 仍是占位域名 `https://api.yourdomain.com`：
  照现在的配置发布，前端能打开但所有接口都打到不存在的域。要用户定实际网关地址，
  不属于「顺手改一下」的范围（改法是部署配置，不是代码）。
- E2E 冷编译抖动（每次全量随机红 1~5 个）仍未根修。
- `apiKey` 仍是明文入库（后端没有加密步骤）。页面已经写明「不要贴生产密钥」，
  但这需要后端补一次加密迁移才算解决。
- Webhook 的 `handleWebhookEvent` 只写日志：`PROCESSED` 现在诚实了，但「事件驱动业务」
  这件事仍未实现，页面文案同步说明。
- 商品/库存/消息列表只读本地表。列表为空 = 从没同步过，不等于平台没有数据（页面空态已写明）。
- `POST /rule/execute/{shopId}` 批量执行、SP-API 搜索词报表入库、`Warehouse.vue` 吞掉加载失败
  等条目仍在本片的计划清单里，不属于本片。
