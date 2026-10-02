# 2026-10-03 · 项 7b：分时调价补上控制面 + `/ad/bidSchedule` 接入

承接 `2026-10-03-item7a-ad-search-term-ui.md`。7a 里我写下「全系统唯一的真实改价通道是分时调价」，
这一轮就是去核这句话，结果发现这条通道**只能创建、只能查**：

```
$ grep -n "BidSchedule" amz-service/amz-service-ad/src/main/java/com/amz/service/AdService.java
50:    BidSchedule createBidSchedule(BidSchedule schedule);
55:    PageResult<BidSchedule> listBidSchedules(Long shopId, PageRequest page);
```

也就是说，一条写错的调价规则会每小时改一次广告账号竞价，**没有任何接口能停它**，
只能登数据库删行。这比 7a 那条「规则只出建议」严重一个量级，所以本轮先补控制面再接页面。

## 一、后端补的三个端点 + 两处写入把关

| 端点 | 作用 |
| --- | --- |
| `PUT /ad/bidSchedule/{id}` | 改时段/倍率/活动范围 |
| `POST /ad/bidSchedule/{id}/toggle?enabled=` | 启用/停用（停用后下一个整点起不再改价） |
| `DELETE /ad/bidSchedule/{id}` | 删除 |

三个都按 **id 反查归属**（`requireScheduleAccess`：`selectById` 后用
`UserContext.isShopAllowedStrict(row.shopId)`），请求体里的 shopId 不作为放行依据，
也不允许把规则挪店。路径里没有 shopId，所以 `@ShopScoped` 切面按它自己的约定是「解析不到参数就放行」，
真正的门在 Service 层——这一点写在控制器 javadoc 里，免得后人以为注解就是防线。

写入校验（`requireValidWindow`，create 与 update 共用）：

- `startHour`/`endHour` 必填且都在 0-23：调度查询是
  `enabled = 1 AND start_hour <= #{hour} AND end_hour >= #{hour}`，越界值永远不会命中；
- **拒绝跨零点**（`start > end`）：上面那条 SQL 不支持回绕，22→2 是一条永久的死规则。
  晚间要覆盖到凌晨得拆两条，这条边界也写进了页面说明；
- 倍率限定 0.10~5.00：执行器只兜**绝对价**（0.02~1000）且禁止连乘，认不出 50x 是策略还是手滑。
  这个区间是业务判断，取值参照 V1 预置的 0.7/1.2/1.5 留了余量，改它只需要动一个常量；
- `updateRule` 校验的是「库里现值 + 本次非空覆盖」的合并形态（PUT 允许只带被改字段），
  并把 `shopId` 回填成库里的值——两个方向都有测试盯着。

新增 `BidScheduleControlTest` 14 例；ad 模块 `mvn test` **149 例 0 失败 1 skip**（需真实 MySQL 的那条）。
`AdServiceImplTest.createBidScheduleAllowsAuthorizedShop` 原本用一个只有 shopId 的空对象创建规则，
新校验下会被拒——它的意图是「授权店铺能创建」，所以把夹具补成 V1 种子的形状
（20-23 × 1.5）并额外断言 `enabled` 默认值，**没有删断言、也没有放宽校验**。

变异检查（restore 一律放 `finally`，输出 ASCII 转义——第一版脚本因为控制台 GBK 在打印时崩了，
把一处未复原的变异留在了文件里，本轮重跑时已确认 sha 复原）：

| 变异 | 抓到它的用例 |
| --- | --- |
| B1 create 不校验窗口与倍率 | `createRejectsHourOutOfRange` / `createRejectsWindowCrossingMidnight` / `createRejectsOutOfRangeMultiplier` / `createRejectsMissingWindowOrMultiplier` |
| B2 update 只看请求体不做合并 | `updateAcceptsLegalPatch`（缺小时 → 被误判非法） |
| B3 按 id 反查时不校验归属 | `toggleRejectsForeignSchedule` + `deleteRejectsForeignSchedule` |
| B4 允许跨零点 | `createRejectsWindowCrossingMidnight` + `updateValidatesMergedWindow` |
| B5 倍率不设界 | `createRejectsOutOfRangeMultiplier` |
| B6 update 忘记回填 shopId | `updateAcceptsLegalPatch`（`expected <1> but was <null>`） |

顺带记一次工具教训：B1/B6 的第一版锚点里有 `\n`，而这个仓库的 Java 文件是 **CRLF**，
`text.count(anchor)` 返回 0 被脚本判成 SKIP ——「没跑」和「跑了没抓到」不是一回事，
所以脚本现在会先打印每条锚点的命中数，并且跳过时显式说 SKIP。

## 二、前端 `/ad-bid-schedule`

`amz-frontend/src/views/AdBidSchedule.vue` + `src/api/adBidSchedule.ts`，接 5 个端点
（list / create / update / toggle / delete）。要点：

- 说明区第一句就是**这里保存的启用规则会真实修改广告账号竞价**，并解释
  基准价语义（不会逐小时连乘）、不支持跨零点、同时段多规则按顺序后者覆盖、
  停用/删除都不回滚已改出去的价。
- 创建/修改/启停/删除全部二次确认，确认文案带上真实影响：
  `20:00-24:00 内该店铺全部活动，每个整点会把关键词竞价改成 基准价 × 1.2`。
  「× 1.2」用的是用户刚填的值，不是模板里的占位。
- 表单按后端同一套规则前置把关（跨零点、倍率越界时按钮禁用并把拒因显示出来），
  但权威仍在后端：写失败时表单不关、错误条保留、列表不刷新。
- 编辑只提交时段/倍率/范围/启用四个字段，**不带 shopId**——归属由后端按 id 反查。
- 活动留空提交 `null`（全店语义）而不是空串。
- 列表截断时显式给「下一页」并说明「本页不是全量」。

## 三、闸口数字

- `vue-tsc --noEmit` rc=0（0 行输出）；
- 前端单测 **33 文件 / 334 例全绿**（本轮 +1 文件 +13 例，router +1；上一轮末 320）；
- `vite build` rc=0，产出 `AdBidSchedule-*.js` 10.59 kB / css 3.85 kB；
- `repository_hygiene.py` 0 findings（路由文件又变了，pin `1ce4dae5…` → `ae8fd5ea…`，只改这一行）；
- Java：ad 模块全量 149 例绿；
- E2E 新增 3 例（列表解释 / 跨零点前端拦住 / 停用要确认）+ `NAV` 清单第 17 项。
  全量：**84 例 = 81 passed / 3 failed**（3.0 分钟）。三条失败是
  `侧边栏点击「库存监控」`、`AdSearchTerms 出建议二次确认`、`AdBidSchedule 跨零点被拦`
  （最后这条是本轮新写的，单独跑也过），报错签名分别是
  `locator.click: Test timeout 30000ms exceeded` ×2 与
  `TypeError: Failed to fetch dynamically imported module: .../src/views/AdSearchTerms.vue` ×1。
  **把这三条单独重跑：3 passed（4.6 秒）**。上一轮全量失败的是另外两条（Notifications、Warehouse 弹窗），
  失败集合每次都在动 → 已知的 dev-server 冷启动类，不是逻辑回归；
  本机也没有别的服务占 5173（跑完只剩 TIME_WAIT，`reuseExistingServer` 复用陈旧 dev server 的猜测已排除）。
  诚实记录趋势：套件从 74 涨到 84，这类失败从 2 条变 3 条，正在变多。
  下一轮如果还要动 E2E，建议的根因修法是把 `webServer.command` 从 `npm run dev`
  换成对已构建 `dist/` 的 `vite preview`（打桩层在浏览器里拦 `/api/**`，不依赖 dev 代理），
  彻底去掉按需转译；本轮没有做，也没有用调大 retries 把它盖掉。
- 清点快照 v3：解析出的方法注解 356 → **359**（正是本轮新增的三个端点），
  未点名端点 102 → **100**，用户可见候选 82 → **80**（`GET /ad/bidSchedule/{shopId}` 与
  `POST /ad/bidSchedule` 被接上；新增的三个端点同时被 `src/api` 点名，所以候选没有反弹）。

## 四、`AdController` 剩下 4 条候选的判类（不是都该接）

| 端点 | 判定 | 依据 |
| --- | --- | --- |
| `GET /ad/report/{shopId}` | **不接**：同一实现的第二入口 | 与已接的 `GET /ad/reports?shopId=` 都调 `adService.getShopReports`，接两条只会给同一份数据造两个口径 |
| `GET /ad/keyword/optimize` | 该接 UI，下一轮 | `KeywordOptimizer` 出建议，属于 AdManager 的活动列表现场 |
| `POST /ad/reports/sync`（两个重载：单店 OPERATOR / 全店 ADMIN） | 该接 UI，下一轮 | 目前只有 cron 会写 `amz_ad_daily_report`，运营想回补日期只能等第二天；这是真实缺口 |
| `GET /ad/competitor` | **不接**：后端本身是占位 | 控制器注释与返回体都是 `myPrice/avgCompetitorPrice/... = null` + `note:"需接入 SP-API Pricing"`，接上去就是一张全空表，等于用 UI 给占位实现盖章 |

## 五、本轮没做的

1. **没做「一次改价的执行历史」**：`amz_ad_keyword.base_bid` 只存基准价，
   哪个整点把哪个关键词从多少改到多少，只有 `BidScheduleExecutor` 的 log，没有落库表。
   要能核对就得新增一张执行流水表（DDL + 迁移），不在本轮范围。
2. 没有给 `POST /ad/bidSchedule` 加「同店铺同时段重叠」提示——重叠是允许的（不同活动），
   只有同活动同小时重叠才有歧义，页面用了文字说明而不是替用户判断。
3. 分时调价的真实下发只做过单元测试级验证（mock 客户端），**没有真实广告账号凭证**，
   所以「按小时改到 Amazon 上的价」这件事本轮依旧只有代码证据，没有现场证据。
4. 共享演示栈、真实 MySQL、Flyway 迁移本轮都没碰；也没有删任何表。
