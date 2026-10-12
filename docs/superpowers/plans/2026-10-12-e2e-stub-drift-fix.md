# E2E 桩漂移修复方案（2026-10-12）

> **状态（同日收口）**：A/B/C 三项修复 + 防回潮契约**已全部落地并实测**。
> E2E 全量 **126 例：123 passed / 3 failed（3.6 分钟）**；剩余 3 例经单跑验证为
> **冷启动抖动**（单跑 24 例全过，含这 3 例）——稳定失败为 0。
> 基线是 52 passed / 72 failed、27.9 分钟。以下正文保留修复前的分析口径，便于复核。

> **目标**：让 CI 的 frontend job 转绿。曾因 master 最近 4 次 push 中 3 次 failure、1 次 cancelled，
> 唯一红的 job 是 `frontend`（`timeout-minutes: 45` 被打断），根因是 E2E 套件与产品代码漂移。
>
> **一句话结论**：漂移主要是**一处**——E2E spec 用 `addInitScript` 播种的店铺名
> `Shop A (US)` 精确命中生产代码 `purgeLegacyPlaceholderShops()` 的清理模式，播种即被清空，
> 页面停在「请先选择店铺」，所有依赖店铺的断言因此超时。仅改这一处，实测
> **52 passed / 72 failed → 119 passed / 7 failed，耗时 27.9 分钟 → 4.7 分钟**；
> 连同 B/C 修完后 **123 passed / 3 failed，3.6 分钟**（剩者为抖动）。

# 一、根因与证据

## 1.1 漂移链（逐环实测）

```
spec 播种 { id:'1', name:'Shop A (US)' }      ← 写死在 4 个 spec 的 addInitScript/SEED
   ↓ 命中 /^Shop [ABC] \((US|UK|DE)\)$/        ← src/utils/shop.ts:73 LEGACY_PLACEHOLDER_PATTERN
purgeLegacyPlaceholderShops() 清空 shops + current_shop_id
   ↓ 而 e2e 桩的 /user/getInfo 不返回顶层 shops（后端 UserVo.shops 已加，桩未同步）
AppHeader.syncShopsFromServer 拿不到店铺 → 下拉框停在「请选择店铺」
   ↓ 业务页命中 useShopGuard 守卫（!currentShopId 直接 return，不发请求）
页面停在 shop-tip，表格不渲染 → 25 例 click 超时 + 大量 toHaveCount(0)
```

关键判据（本机实测，非推断）：

| 实验 | 结果 |
|---|---|
| 基线全量（126 例） | 52 passed / 72 failed，27.9 分钟 |
| **只改店铺名**（不动桩） | 3 个代表性用例由红转绿，耗时 60s+ → 16.8s |
| 只改店铺名 + 主 spec 全量 | 99 passed / 3 failed，4.3 分钟 |
| 4 个 spec 全改 | **119 passed / 7 failed，4.7 分钟** |

「只改店铺名就转绿」这个结果同时排除了「桩缺登记」这一猜测：诊断脚本（把落到
`EMPTY_PAGE` 兜底的未匹配端点全部打印）跑完全量 126 例，**只发现 1 个未登记端点**
（`GET /user/notification/list`）。桩的覆盖是够的，问题出在播种数据被生产代码判为脏数据清掉。

## 1.2 为什么本机一直绿的门禁没抓到

- `src/__tests__/shopSource.test.ts:47` 断言「源码里不再存在写死的店铺种子」，
  但它**只读 `src/main.ts`**，不读 `e2e/` —— E2E 目录不在任何契约的扫描范围内。
- `tools/schema/stub_shape_audit.py`（形状闸门）实测 **0 findings（144 注册 / 107 可比 / 96 过）**，
  它只验「已登记桩的字段形状」，管不到「压根没登记的端点」，也管不到 spec 播种值。
- E2E 是 P3/P4 那个 commit（`6abcc1e`）**首次接进 ci.yml** 的，此前 `test:e2e` 从未在远端跑过，
  套件腐烂无人发现——ci.yml 注释自己记着基线「23 passed / 16 failed」。

所以这不是「五项推进把 CI 弄红了」，而是**新开的门禁第一次上远端就撞上长期无人维护的套件**。

# 二、修复项

## A. 播种店铺名（主干，已完成并实测）

4 个 spec 的占位店铺名改为不命中清理模式的名称（`E2E Alpha / Beta / Gamma`）：

- `e2e/full-interaction.spec.ts:69`（`addInitScript` 里的数组）
- `e2e/core-pages.spec.ts:15`、`e2e/all-pages.spec.ts:24`（`SEED.shops` 字符串）
- `e2e/agent-eval.spec.ts:14`

**为什么改 spec 而不是改清理规则**：清理规则是生产代码的正确行为（S7.2 定案：店铺只能来自后端），
E2E 应当模拟真实店铺，而不是绕过守卫。改规则等于让测试去迁就假数据。

**判据**：改完 126 例 119 passed / 7 failed（4.7 分钟）。无 spec 断言店铺名文本（只断言 id），改动安全。

## B. 订单页 `.filter-btn` 定位歧义（新功能改 DOM，旧 spec 未同步）— **已修**

`e2e/core-pages.spec.ts:48` 的 `page.locator('.filter-btn').click()` 现在解析到 2 个元素
→ strict mode violation。原因是 **P1 的 CSV 导出**给订单页加了「导出」按钮并复用了 `.filter-btn`
类名（`src/views/OrderList.vue:25-26`，导出按钮带 `.export` 修饰类）。

**已改为** `.filter-btn:not(.export)`。
**不要用 `.first()`** 掩盖歧义，那会让按钮顺序变化时静默点错。

## C. 消息中心文案断言过时（新功能改行为，旧 spec 未同步）— **已修**

`e2e/all-pages.spec.ts:218` 断言 `.notice-zone` 含「服务端不落库」，
而 **P1.6 消息落库**做完后页面文案已改成「通知已服务端落库（P1.6）：进入页面即加载最近 100 条历史…」。

**已改为**断言「服务端落库」（去掉「不」字），并同步用例标题。
这不是放松标准——新功能是真做了落库，页面文案准确，spec 断言的是**已不存在的旧行为**；
同步事实与「把断言改松让它过」是两回事，区别在于新文案必须有产品代码支撑
（此处 `src/views/NotificationPage.vue:26-29` 的 `.notice-zone` 就是）。

**顺带已补桩**：`GET /user/notification/list` 是唯一未登记端点（诊断脚本实测结论）。
后端 `NotificationController#list` 返回 `Result<List<Notification>>`，前端按数组消费，
所以登记成 `[]` + `FULL_PAGE(0)`，而不是让它落到 `EMPTY_PAGE` 对象兜底——
否则「空态」断言测的是「前端把对象当空」，不是「真的没有通知」。
补后形状闸门 `registrations 144→145 / passed 96→97 / RED 0`。

# 三、开放项：冷启动抖动（未修，已定性）

修完 A/B/C 后剩余 3 例，经单跑验证为 dev server 按需编译路由 chunk 造成的抖动
（单跑 24 例全过，含这 3 例；**稳定失败为 0**）。症状与判据：

- 症状：`page.goto` 超时、`Failed to fetch dynamically imported module`、`.toHaveURL` 失败。
- 判据：**失败集合每次都不同，单跑即过**。
  修前全量剩 7 例、单跑后 4 例红但「物流看板」是新面孔、原 7 例中 3 例转绿；
  修完全量剩 3 例（自建下单/选品分析/未登录重定向），又是一批新面孔。
- `playwright.config.ts` 注释已记录这是已知开放项，并记过一次失败的尝试：
  换 `vite preview` 会让 4 个运营台用例全红——`src/api/auth.ts` 的
  `baseURL = import.meta.env.DEV ? '/api' : VITE_API_BASE_URL`，生产构建的包不打同源 `/api`，
  hermetic 桩一条都匹配不上。要换 preview 得连带做「测试模式构建 + 把 `VITE_API_BASE_URL` 指回 `/api`」，
  那是独立改动。

处置建议（本次不做）：先按修完 A/B/C 后的实际失败率决定。若抖动仍会偶发打断 CI，
优先做的是**给 E2E 单独放宽 job 超时**（45 分钟现在绰绰有余：修完后全量只要 3.6 分钟），
而不是改测试去迁就抖动。

# 四、防回潮：把 e2e 目录纳入店铺契约

改 `src/__tests__/shopSource.test.ts` 的「仓库源码里不再存在写死的店铺种子」一条：
把扫描范围从 `src/main.ts` 单文件扩到 **`src/**` + `e2e/**`**（排除 `e2e/support/api-stub.ts` 里
`DASHBOARD_SHOP_DIST` 那三处展示用店铺名——它们不进 localStorage、不经过清理函数，
且有页面渲染断言依赖其文本）。

**已落地**：`src/__tests__/shopSource.test.ts` 新增一条「E2E 播种的店铺名不会被启动清理判为占位值」，
扫描范围从原来的 `src/main.ts` 单文件扩到 **`e2e/**`（排除 `e2e/support/api-stub.ts`）**。
排除理由：那三处是 `DASHBOARD_SHOP_DIST` 展示用店铺名，不进 localStorage、不经过清理函数，
且有页面渲染断言依赖其文本。

**变异验证已做**：把 `e2e/agent-eval.spec.ts` 的播种名改回 `Shop A (US)`，
该契约**恰好 1 红**并点名漂移文件；改回后恢复绿（8 项全过）。

这样漂移 A 这类问题会在 vitest 阶段（前端 job 里 E2E 之前的那一步）就红，
不必等 20 多分钟的 E2E 跑完。B/C 两类（新功能改 DOM/文案）没有通用契约能拦，
靠的是「新功能改了 UI 就跑一次 E2E」这条纪律。

# 五、门禁同步注意事项（沿既有坑）

- **坑 11**：改被 `hygiene-allowlist.json` 按文件哈希钉住的文件必须重新 attest。
  本次改的 4 个 spec 不在 allowlist 内，但要复核。
- **坑 12/19**：契约测试与 hygiene 都是**裸串扫描**，注释/文档里的字面量同样会被拦。
  本方案文档与新增注释避免出现 `token = xxx`、`password: xxx` 形式的赋值串。
- 新增/修改 E2E 断言后：`npm run test:run`（vitest）与 `npx vue-tsc --noEmit` 仍须 0 错。
- 收口前按交班纪律刷新 HANDOFF，并把 §S10 优先序里已过时的
  「run 38133981053 待确认全绿」改为实测结论（那个 run 实际是 failure）。

# 六、执行顺序与验收

1. ✅ 改 4 个 spec 的店铺名 —— 实测 119/126。
2. ✅ 修 B（`.filter-btn` 收窄定位）、修 C（通知文案 + 补 `/user/notification/list` 桩）。
3. ✅ 加防回潮契约（§四），并做变异验证（改回 `Shop A (US)` → 恰好 1 红，已还原）。
4. ✅ 本机全量 `npx playwright test`：**123 passed / 3 failed（3.6 分钟）**，
   剩余 3 例单跑全过 → 稳定失败 0，剩者为已知抖动。

   本机门禁复核：vue-tsc **0 错**、vitest **506/506**、hygiene **0 findings**、
   release tools **91/91**、stub-shape **RED 0**（145 注册 / 97 过）。
5. ⬜ 推送后用 `gh run list --branch master --limit 1` 确认远端全绿再收口
   （本机环境会掩盖三类问题，别只靠本地绿收工——见 HANDOFF 2026-10-11 教训段）。

**诚实边界**：这套 E2E 是 hermetic 打桩（见 `e2e/support/api-stub.ts` 顶部注释），
只证明「前端拿到这样的响应时会这样渲染」，不证明后端真返回这个结构。
修完 A/B/C 让它全绿，含义是**前端渲染契约自洽**，不等于真实联调通过——真实凭据到位后的
runbook §3 端到端验收（A5/E4–E5）仍是主线，不受本次修复影响。
