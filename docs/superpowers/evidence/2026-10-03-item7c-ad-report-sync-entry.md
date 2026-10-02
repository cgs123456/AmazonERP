# 2026-10-03 · 项 7c：给「请先执行日报同步」这句提示补上它自己的按钮

`AdManager` 的空态一直写着这句话：

```
amz-frontend/src/views/AdManager.vue:48
  暂无已同步的广告报表，请先配置 API 并执行日报同步。
```

而 `POST /ad/reports/sync` 在此之前**全系统没有任何调用方**：既没有前端入口，也没有 Feign，
也没有脚本。也就是说页面上唯一的补救指引，指向一个只有 curl 能碰的端点。
本轮就是把这个按钮补回它自己的页面。

## 一、先核实这条同步链是真的（不是又一个占位）

清点工具把候选从 94 一路降到 78，靠猜是不行的。读代码确认：

- `amz_ad_daily_report` 的唯一写入方是 `AdReportSyncScheduler.upsertRows`
  （`AdReportSyncScheduler.java:317`），`AdServiceImpl` 只读它 —
  所以除了 cron，运营确实没有任何办法回补某天的数据；
- 手动端点走的是同一个 `syncShopReportsWithSummary` / `syncAllShopsWithSummary`，
  返回 `SyncSummary(attempted/succeeded/failed/skipped/upserted/metadataWarnings)`；
- `POST /ad/reports/sync` 是**同一个路径的两个重载**，靠有没有 `shopId` 查询参数区分：
  带 shopId 需 OPERATOR/ADMIN，且注释明写「缺少 shopId 的请求不会进入本方法，
  避免单店入口退化为全店铺同步」；不带 shopId 需 ADMIN。

## 二、前端做了什么

`src/api/ad.ts` 加 `syncAdReports(shopId, days)` / `syncAllAdReports(days)` + `AD_SYNC_MAX_DAYS = 30`
（常量对齐后端 `AdReportSyncScheduler.MAX_DAYS`，不自己发明上限）。

`AdManager` 底部新增「日报同步回补」卡片（默认收起，`v-if="!loading"`）：

- 两个按钮分别命中两个重载；**全店那个绝不带 shopId**——带了就静默变成单店同步，
  这正是后端注释要防的事，所以有一条用例专门盯它。
- 结果区把后端 6 个计数原样列出来，不折算成一个好看的数字：
  `尝试 1 · 成功 1 · 失败 0 · 跳过 0 · 落库 12 行`。
- 两条「别读错」的规则：
  - `failed > 0` → 红条写明「落库行数不代表窗口已补齐」；
  - `succeeded === 0` → 副行写明「跳过（skipped）不等于同步完成」，多为没有广告授权。
- 只有 `upserted > 0` 才重拉本页数据，否则空转一次没意义的请求。
- ADMIN 限定写在按钮旁边，而不是前端自己判断角色（全仓没有任何前端角色助手，
  前端扮授权只会和后端不一致）；非 ADMIN 点了会拿到后端真实的拒绝消息。

顺手做的一个机械重构：把 `onMounted(async () => {...})` 改成具名 `loadOverview()` 再
`onMounted(loadOverview)`，同步成功后才能复用同一段取数逻辑。函数体一行没改，
现有 6 条用例全绿即证明搬迁没改变行为。

一处刻意的取舍：卡片**没有**用 `ext-section` 类。这个页面的既有用例靠
`.ext-section:visible` 定位活动区（v-show 让多个面板常驻 DOM），
再加一个常驻可见的同类块会直接把那些定位器变成多义匹配。E2E 里留了一条
`not.toHaveClass(/ext-section/)` 把这件事钉住，而不是等下一个人踩。

## 三、闸口数字

本轮**只跑了 `--grep AdManager` 这一子集**（3 passed），没有重复全量 E2E：
全量本地跑的冷启动抖动（上一轮 84 例里 3 条，单独重跑全过）仍未修，
建议的根因修法仍写在 7b 文档第五节里（换 `vite preview` 打已构建产物）。
CI 那边每轮都会跑全量，是本仓全量 E2E 的实际执行者。


- `AdManager.test.ts` 6 → **13 例**（+7 覆盖两个重载、两条误读规则、拒绝分支、未选店铺）；
- 全仓前端单测：**33 文件 / 341 例全绿**（本轮 +7，上一轮末 334）；
- `vue-tsc --noEmit` rc=0；`vite build` rc=0；
- E2E：`AdManager 交互` 由 2 例增至 3 例，`--grep AdManager` **3 passed**（4.7 秒），
  既有的 SB tab / DSP tab 两条没有回归；
- 变异检查 5 条全部被抓（restore 在 `finally`，文件按 sha256 `eea7d09409f0` 复原并复跑 13 例绿）：

| 变异 | 结果 |
| --- | --- |
| W1 全店同步退化成带 shopId 的调用 | rc=1，2 条用例红 |
| W3 有店铺失败时不再报警 | rc=1，1 条红 |
| W4 未选店铺也能点同步 | rc=1，1 条红 |
| W5 后端拒绝时照样渲染结果 | rc=1，1 条红 |
| W6 落库 0 行也重拉本页 | rc=1，1 条红 |

- 清点快照 v4：用户可见候选 **80 → 78**（两个 `/ad/reports/sync` 重载都被点名），
  `AdController` 剩 4 条：`GET /ad/report/{shopId}`、`GET /ad/keyword/optimize`
  与两条 Feign 专用的（`summary`、`competitor`）。

## 四、顺带定性的两条（不接，理由写清楚）

- `GET /ad/report/{shopId}`：与已接的 `GET /ad/reports?shopId=` 都调
  `adService.getShopReports`，是同一份数据的第二个入口。接进 UI 只会多出一个可能口径不一致的读法。
- `GET /ad/keyword/optimize`：`AdServiceImpl.optimizeKeywords` 明写
  「日报表当前仅按 campaign/date 聚合，不能安全下推到单个关键词。在引入关键词级报表前
  只返回 OBSERVE，避免用活动级数据伪造关键词指标」——
  即它现在**只可能**输出 OBSERVE，而且每次要点一次真实广告 API 拉关键词列表。
  给它做一个建议表格，等于给一个已知降级实现盖章；正确顺序是先有
  关键词级报表（另一件事），再谈接 UI。

## 五、下一轮（已做完侦察，未动手）

`MultiplatformController` 26 条候选是整个清点的最大单块。子代理逐条读实现后的结论：

- 真实可用、该接 UI 的约 17 条：平台账号 CRUD、商品映射、消息列表与内部分配、
  库存列表/聚合、webhook 事件列表、ISV 应用注册与轮换、统一订单列表与发货回传；
- `POST /multiplatform/{product|message|inventory}/sync/**` 的 RealClient
  一律抛 `UnsupportedOperationException`（只有 `@Profile("mock")` 有样例数据）→ 后端本身缺实现；
- `webhook` 接收端与 `oauth/token` 换发不是给浏览器的；
- `testConnection` 只做格式校验不发网络请求，名字与行为不符；
- `replyMessage` 只写本地库、没有平台回传契约；
- 前端**完全没有**这个模块的页面（23 个视图里零命中）。

一处必须先解决的观察：`syncAllPlatforms` 用 `syncPlatformSafely` 把每个平台的异常
吞成返回值 0，端点又只回一个 int，所以「同步到 0 条」分不清是没新单还是三个平台全挂。
接 UI 之前应把它改成与广告侧同形的 per-platform 汇总，否则页面上只能显示一个会说谎的数字。
