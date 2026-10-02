# 项 7i：Listing 监控台接上自查登记 / 排名趋势 / 竞品对比

日期：2026-10-03。切片：7i。前置：7h、审计快照 v8。

## 接了什么

`ListingMonitorController`（amz-service-product）里 3 条零前端命中的端点进了 `/listings` 页
（在既有 6 个 Tab 内加面板，没有新增 Tab，位置敏感的旧用例不受影响）：

| 面板 | 端点 |
| --- | --- |
| 健康度 → 人工登记一次自查 | `POST /product/listing-monitor/health/check` |
| 关键词排名 → 排名趋势 | `GET /product/listing-monitor/ranking/trend/{shopId}` |
| 竞品监控 → 竞品历史对比 | `GET /product/listing-monitor/competitor/compare/{shopId}` |

顺手把 `/listings` 整套读端点的 E2E 桩补齐（概览、健康度列表、排名、竞品、Buybox、
变更记录、主数据），这个页面此前在 E2E 里一次都没跑过。

## 三个页面不能照原样接的原因

1. **`checkListing` 把 A+ 内容写死成「正常」**：`boolean aplusOk = true; // A+ 需要 SP-API 检查`。
   这一句会永久写进 `amz_listing_health`，而健康度汇总读的就是这张表——每条被登记过的
   Listing 都白拿一个 A+ 合格。现在 A+ 只在调用方明确给出时才写值，没给就是 null（未知），
   并把「A+内容未检查」写进问题清单。
   实体上还要 `@TableField(insertStrategy/updateStrategy = ALWAYS)`：DDL 是
   `aplus_ok TINYINT(1) DEFAULT 1`，MyBatis-Plus 默认跳过 null 字段，
   「不写」会被数据库默认值变成「已确认正常」。这条由 `aplusColumnWritesNull` 钉住。
2. **这条端点不读 Listing 本体**，判定项全部来自请求参数。所以它在 UI 上是
   「人工登记一次自查」而不是「系统检查」，面板标题直接写明会写库。
   两点后果记在这里：判定口径（标题 80–200、五点 ≥5、描述 ≥300、图片 ≥7、搜索词非空、
   状态非 SUPPRESSED/INACTIVE）留空就按不合格扣分；`checkListing` 的 selectOne+insert
   没有事务/锁，同一 ASIN 并发首次登记会撞唯一键 `uk_shop_asin` 报 500——这属于后端既有
   缺口，本片没动。
3. **趋势/对比会把「没有记录」画成有记录**：排名点用 `Map.of` 构造，null 被替换成 0
   （排名语义里 0 比第一名还好）。现在用 `LinkedHashMap` 保留 null，页面显示 `—`。

## 后端另外两处修复

- `competitorComparison` 读竞品历史完全不带 LIMIT，而且 `ORDER BY snapshot_date ASC`：
  一旦命中上限，被丢掉的是**最新**快照。改成倒序取 `LIMIT 501` + `capRead` + 反转成升序，
  并在返回里给出 `truncated`。
- 排名趋势同样加上限。**这里差点留了个新坑**：我第一版在原有 `orderByAsc` 之后又追加了
  `orderByDesc`，MySQL 以先出现的排序为准，于是截断丢的还是最新快照。SQL 形状断言
  （`ORDER BY rank_date DESC` 且整段不含 `ASC`）把它逼了出来——mock 掉的 mapper 不执行 SQL，
  只断言行数的话这种错误是看不见的。
- `ownAsinCompared=false` 加进对比结果：这个接口只查竞品一侧，`myAsin` 只是回显。
  页面上明写「不是并排对比」，免得被读成两栏数据。

## 前端两个实现细节问题（测试先绿不了才发现的）

- 五点描述用的是单行 `<input>`：浏览器会**静默吃掉换行**，五点变成一条 `abcde`，
  然后被后端判成「不足 5 条」。改成 `<textarea>`，并在 E2E 里断言查询串里确实带 4 个换行。
- 「趋势缺失排名显示 `—`」这条用例第一版只断言了整段文本包含 `—`：广告列也有 `—`，
  所以把自然排名补成 0 的变异是绿的。改成逐格断言（第一行自然排名 `7`、广告 `—`；
  第二行相反）之后，`p.organicRank ?? 0` 立刻变红。

## 反证：16 个变异全部变红

后端 9 个（`MI1` A+ 回到写死 TRUE、`MI2` 去掉「未检查」提示、`MI3` 缺失排名补 0、
`MI4` 趋势不反转、`MI5b` 对比去掉 LIMIT、`MI6` 隐藏 truncated、`MI7` 实体改回 NOT_NULL 策略、
`MI8` 重新加回矛盾的 ASC、`MI9` 趋势去掉 LIMIT），前端 7 个（`MF1/MF1b` 排名补 0、
`MF2` 三态未知画成「否」、`MF3` A+ 空值当肯定答案、`MF4` 写库后不重算、
`MF5` 登记失败静默、`MF6` 去掉单侧说明）。每个都点名了对应用例，跑完按 sha256 校验还原。

第一轮有两个变异是绿的（`MF1` 与 `MI5`），都不是实现的问题而是断言的问题：
整段文本断言 + mock 不执行 SQL。补了逐格断言与 SQL 形状断言之后才变红。

## 闸口

- `mvn -pl amz-service/amz-service-product -am test`：**Tests run: 72, Failures: 0, Errors: 0, Skipped: 3**
  （skipped 是需要真实依赖的 IT；amz-common 177 例同跑 0 失败）
- `vue-tsc` 0 错误；`vitest run` **36 文件 / 400 例全绿**；`vite build` 成功
- Playwright：`/listings` 新增 3 例全过（含「整页无接口失败」这条，靠的是补齐的读端点桩）
- `repository_hygiene.py`：在 `git add` **之后**跑，findings=0；router 未改，无需重钉
- `endpoint_coverage_audit.py` 快照 v9：用户可见候选 52 → 49（本片的 3 条），
  `ListingMonitorController` 缺口 3 → 0

## 遗留

- `checkListing` 的并发唯一键竞态（无事务/无 upsert 语句）。
- 趋势与对比都没有真实采集器：`KeepaCompetitorScheduler` 只对表里**已有**的 ASIN 再采样，
  且 `KEEPA_API_KEY` 未配就直接返回，所以本地跑起来这两块基本是空的；页面上空态都写明了
  「空不等于没有」。
- 竞品对比没有我方一侧的数据源，真正的并排对比要先有 self-ASIN 快照采集。
