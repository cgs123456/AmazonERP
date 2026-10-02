# 项 7g：运营预警台（差评 / 跟卖 / 关键词排名）+ 处理端归属修复

日期：2026-10-03。切片：7g。前置：7f（多平台运营台）、审计快照 v6。

## 接了什么

新页 `/ops-alerts`（`OpsAlerts.vue`，3 个 Tab），把 `OpsController` 7 条端点里的
**4 条读/写路径**接上：

| Tab | 端点 |
| --- | --- |
| 差评告警 | `GET /ops/review/list/{shopId}`（status + keyset 分页）、`POST /ops/review/{alertId}/handle` |
| 跟卖告警 | `GET /ops/hijack/list/{shopId}`（status + keyset 分页，只读） |
| 关键词排名 | `GET /ops/rank/trend?shopId=&keyword=&asin=` |

**不接的 3 条**：`review/scan`、`hijack/scan`、`rank/capture`。读实现就知道它们不是采集器：
`OpsServiceImpl` 用 `ThreadLocalRandom` 现编 ASIN/评分/排名往告警表里插，且
`mockGeneratorsAllowed()` 只在 mock profile 放行（生产直接返回 0）。
所以页面不放「扫描」按钮，并在说明区写明「这里的行都来自曾经写入，不能声称是刚采到的」；
`OpsAlerts.test.ts` 里有一条用例把这六个标签（扫描差评/扫描跟卖/抓取排名/触发扫描/忽略告警/标记已忽略）
钉成「页面上必须不存在」。

跟卖那一栏连「已处理」按钮都没有：后端只有 `review/{id}/handle`，没有跟卖告警的写入口，
DDL 里的 `IGNORED` 状态同样没有对应端点——所以页面也不做「忽略」。

## 后端修的三个缺陷

1. **`handleNegativeReviewAlert` 的归属判定形同虚设**。原写法：

   ```java
   if (alert.getShopId() != null && !UserContext.isShopAllowed(alert.getShopId())) { return false; }
   ```

   一行里两个洞：`shop_id` 在 V1 DDL 是 `BIGINT NOT NULL`，出现 null 是脏数据，
   却被当成「谁的都不是、谁都能改」的免检通道；`isShopAllowed` 是宽松档，
   没有用户上下文时放行。而这条端点上没有 `@ShopScoped`（切面只认名为 shopId 的
   path/query 参数，`alertId` 解析不到），所以服务内的逐行判定是唯一一道防线。
   现在换成 `isShopAllowedStrict`，不存在 / 越权 / null 归属 / 无上下文四种情况
   一律 `CodeErrorException`，且前两种同一句文案（否则这条端点就是告警 ID 的存在性探针）。
   重复处理（已经是 HANDLED）也报错，而不是静默再写一遍。
2. **两个告警列表不带 LIMIT**：`selectList` 整表读进内存，HTTP 200 加数组和「确实只有这些」
   在调用方看来毫无区别。现在按 id 倒序 keyset 分页（`Result.paged`，探测 size+1）。
3. **排名趋势没有上限**：折线要连续序列所以不翻页，但改成「倒序取最近
   `MAX_RANK_TREND_POINTS = 200` 个点，再反转成时间升序返回」，
   避免跑得越久查得越贵，同时调用方不用猜方向。

顺带把两个实体漏掉的 `createTime` 补上（DDL 有 `create_time DEFAULT CURRENT_TIMESTAMP`
但实体没映射，接口读不到时间）。插入语句不受影响：MyBatis-Plus 默认跳过 null 列，
默认值照常生效；页面对缺时间的行显示「未记录」而不是补一个。

`OpsServiceImplTest` 里两条固化旧行为的用例是**改写不是删除**：
`testHandleNegativeReviewAlertExists` 的 fixture 补成 DDL 形状（shopId=1）并放进 OPERATOR 上下文；
`testHandleNegativeReviewAlertNotFound` 从「断言返回 false」改成「断言业务错误 + 不写库」。

## 反证：15 个变异全部变红

后端（`OpsServiceImpl`，7 个）：

| 变异 | 变红的用例 |
| --- | --- |
| MG1 换回宽松档 + `!= null` 免检 | `nullShopOwnerIsNotACheckBypass`、`withoutContextFailsClosed` |
| MG2 去掉重复处理拦截 | `alreadyHandledAlertIsRejected` |
| MG3 去掉列表 LIMIT | `reviewAlertsPagedById` |
| MG4 忽略游标 | `reviewAlertsUseKeysetCursor` |
| MG5 趋势上限形同虚设 | `rankTrendIsCappedButStillAscending` |
| MG6 趋势不做反转 | 同上（顺序断言） |
| MG7 「不存在」换成另一句文案 | `missingAlertUsesTheSameMessageAsForeignOne` + `testHandleNegativeReviewAlertNotFound` |

前端（`OpsAlerts.vue`，8 个）：MA1 已处理行也给按钮 / MA2 写失败仍刷新 /
MA3 趋势表单忽略 ASIN / MA4 ASIN 不转大写 / MA5 首屏把两个列表都拉 /
MA6 Buy Box 的未知态被写成肯定答案 / MA7 忽略 truncated / MA8 趋势报错伪装成「查了没数据」
——逐个重放后都至少打红一条用例，每次跑完按 sha256 校验还原。

## 顺手抓到的两个「测量本身在说谎」

1. **hygiene 门禁跑在 `git add` 之前 = 假绿。** 7f 报告 findings=0 时新增文件还没被跟踪，
   而 `repository_hygiene.py` 默认只扫已跟踪文件（`--include-untracked` 才带上别的）。
   提交后 CI 的 hygiene 直接红：4 处 `secret-like-assignment`，全是我自己写的假值
   （测试 fixture 里的 `apiKey: 'sk-…'`、一处 `body.apiKey = <长串>`、文档里引用的一行读 token 的守卫代码）。
   处理方式不是加白名单，而是把它们改成不会长得像凭证的写法：fixture 用
   `sk-example-*`（工具的 `PLACEHOLDER_MARKERS` 认得 example/test），
   视图里先 `const key = …` 再 `if (key) body.apiKey = key`。
   现在同一棵树在默认与 `--include-untracked` 两种模式下分别是 0 与 48 条，
   那 48 条是仓库根目录本来就散着的 `round33-*.log`、`mvn-*.log` 等历史产物（不是我这一片产生的，
   CI 的跑法也不会遇到），没有替你删。
2. **端点覆盖审计把注释里的路径当成了「前端调用过」。** 同一棵树：带注释匹配报
   71 条无前端命中 / 51 条用户可见候选，剥掉注释后是 76 / 55。原因就是
   `opsAlerts.ts` 的头注里列了三个「刻意不接」的扫描端点，于是 `OpsController`
   显示 0 缺口——而页面上根本没有这三个按钮。已经把 `strip_comments()` 落进工具
   （docstring 也写明「注释里的路径不算调用」），现在 `OpsController` 老实报出 3 条。

## 闸口

- `mvn -pl amz-service/amz-service-ops test`：**Tests run: 24, Failures: 0**（原 11 条 + 13 条新用例）
- `vue-tsc --noEmit` 0 错误；`vitest run` **36 文件 / 390 例全绿**（新增 `OpsAlerts.test.ts` 15 例）；`vite build` 成功
- `repository_hygiene.py --root .` findings=0（router 因新增路由再钉一次：`a68a86c3… → a69b4f8d…`）
- `endpoint_coverage_audit.py`（快照 v7，剥注释）：359 条方法注解、60 个 controller、
  无前端命中 76 条、用户可见候选 **55**（v6 是 73；7f −14、7g −4，工具修正是 +5 里的一条净额变化）
- Playwright：定向 4 例（侧边栏跳转 + 预警台 3 例）全过。全量 98 例：**97 通过 / 1 失败**，
  失败的是 OrderAudit 的「单订单审单先挡住空订单号」，单独跑 776ms 就过（`pw_rc=0`）。
  这与 7f 记录的冷编译抖动是同一类（每次失败的用例都不同），没有为了变绿去改断言。
  注意别把这条读成「CI 会红」：CI 的 e2e 跑法带 `retries=1`，而本地 `retries=0`。

## 遗留

- 三个扫描端点要变成真的：需要 SP-API 评论/跟卖/排名采集的实现与落库来源列，
  否则页面上的告警永远只能显示「曾经写入」。
- `amz-service-ops` 里没有「列出被追踪关键词」的端点，所以排名 Tab 只能手填 keyword+asin；
  要做成列表得先加端点。
- 告警表的 `IGNORED` 状态没有写入端点；要支持忽略需要在后端补状态迁移与归属判定。
