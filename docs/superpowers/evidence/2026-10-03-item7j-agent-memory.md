# 7j — AI/Agent 域：身份修复 + 助手记忆页接通

日期：2026-10-03。切片目标：把 `amz-service-ai` 里「有真实表、有鉴权、但前端没有入口」的
Agent 记忆端点接进 UI，同时修掉两处会让调用方越权或拿到假数据的缺陷。

## 1. 修的两个后端缺陷

### A. `POST /ai/erp/agent` 的 userId 是客户端说了算（IDOR）

```java
// 修前（AiController.java:77-79）
public Result<String> erpAgent(@RequestParam(value = "userId", defaultValue = "1") Long userId,
                               @RequestBody ErpAgentRequest request)
// LangChain4jAgentService.java:42
String sessionId = "sess-" + userId;
```

`userId` 不是普通字段，它被直接拼进 ChatMemory 的会话键，也就是**对话记忆的归属**。
任何登录用户在 query 里写 `?userId=9` 就能读写 9 号的上下文；不登录时统一落到 `1`。
同仓库的 `AgentSseController#requireAuthenticatedUser()` 与 `AgentMemoryController#currentUserId()`
都已经是「只认认证上下文」，这是那两处规则漏进来的第三个入口。

修法：删掉参数，改取 `UserContext.getUserId()`，未登录抛 `ResponseStatusException(401)`
（与 SSE 侧同口径，而不是给一个默认用户）。

### B. `POST /ai/agent/memory/reminder/scan` 绕过了 mock 档门禁（造数直发 IM）

`ProactiveReminderService#remindForUser` 的四条提醒全是写死示例
（SKU `B08X4-001` 可售 4 天、昨日 18 单环比降 28%、1 条 2 星差评、1 个跟卖者），
与 shopId 无关，而 `scanAndRemind()` 会把生成的条目 `imPushService.pushAll(...)` 推出去。
定时任务侧早就有门禁（`DailyReportScheduler#isMockProfile`，:64-71 / :155-158），
HTTP 入口却没有：ADMIN 一次 curl 就能让假告警进真实通知渠道。

修法：控制器同规则拒绝——非 mock 档返回 `Result.failure`，并在拒绝时打一条带当前
profile 的 WARN，说明「恢复该能力需改为真实数据查询」。缺省（没有 active profile）按非 mock 处理。

### C. `POST /ai/agent/memory/language` 把非法语言静默写成 ZH

`LanguageEnum.fromCode` 的语义是「未识别即 ZH」——那是提示词渲染侧的兜底，
但 `switchLanguage` 拿它去写**持久化偏好**，注释还写着「校验语言代码合法」。
用户请求 `language=fr` 得到 200，读回来却是 ZH，且再也看不出区别。

修法：控制器改用严格解析（大小写不敏感，与既有行为一致），未识别代码返回
`language 仅支持 ZH/EN/JA/DE，实际收到「fr」`，且**不触碰 memoryService**。
`fromCode` 保留原语义（`MemoryAwareAgentService:65`、`MultiLangPromptBuilder:38` 仍依赖它）。

## 2. 接通的前端

新增 `src/api/agentMemory.ts`、`src/api/ai.ts`、`src/views/AgentMemory.vue`（路由 `/agent-memory`，
侧边栏「助手记忆」）。

| 端点 | 接入方式 |
| --- | --- |
| `GET /ai/agent/memory/preference/{userId}` | 表单回填 |
| `POST /ai/agent/memory/preference` | 「保存偏好」，只提交非空字段 |
| `POST /ai/agent/memory/language` | 「仅切换语言」 |
| `GET /ai/agent/memory/history/{userId}` | 对话记忆表（条数 20/50/100/200） |
| `POST /ai/erp/agent`、`GET /ai/chat-stream` | 从 `AgentChat.vue` 里收回 `api/ai.ts` 统一持有 |

`AgentChat.vue` 不再上报 `userId`（POST 兜底链路的 `params: { userId: localStorage.user_id }` 删掉）。

页面刻意做的事：

- **身份只来自 `GET /user/getInfo` 的 `user.id`**。不用 `localStorage.user_id`——
  `main.ts:19-21` 在缺失时会把它兜底写成 `'1'`，拿它当路径参数只会稳定撞上后端的 403；
  身份拿不到就不发任何请求，并说明原因。
- **没有假按钮**：`POST /ai/agent/memory/chat`（需 `deepseek.api-key`）、
  `POST /ai/agent/memory/reminder/scan`（造数，见 1.B）、`POST /ai/eval/run`（不鉴权且烧 token）
  都不给入口；e2e 断言「发起对话 / 扫描提醒 / 运行评测」三个按钮数量为 0。
- **不承诺清空**：后端是 MyBatis-Plus `updateById`（非空字段才更新），所以留空的字段原样保留，
  页面上写明这一点而不是做一個「清除」按钮。
- **不用全站那个店铺下拉**：它的数据源是 `main.ts` 里写死的 Shop A/B/C（后端没有
  「我的店铺列表」端点，`setShops()` 在整个前端里从未被调用），本页改成数字输入店铺 ID。
- **空态说明原因**：`conversation_memory` 只由 `MemoryAwareAgentService.chat` 写入，
  SSE 与 `/ai/erp/agent` 走 LangChain4j 内存会话不落库，所以「暂无对话记忆」是正常状态，
  页面直接把这句话放进空态，避免被读成「数据丢了」。

## 3. 闸口与证据

| 闸口 | 结果 |
| --- | --- |
| `mvn -pl amz-service/amz-service-ai -am test` | 124 tests，0 failures/errors |
| 新增测试 | `AiAgentIdentityTest` 4、`AgentReminderScanGateTest` 3、`AgentMemoryControllerTest` +3（9）、`AgentMemory.test.ts` 12 |
| RED | 语言两条：`Cannot invoke UserPreference.setLanguage(...) because "pref" is null`（说明当时真的会去写）；`erpAgent` 三条：签名不兼容 |
| `vue-tsc --noEmit` / `vitest run` / `vite build` | 全绿，412 tests / 37 files |
| Playwright（`-g "Agent\|助手记忆"`） | 5 passed（含改过契约后的 Dashboard SSE 两条） |
| 端点清点 | v9 49 → v10 43 条用户可见候选；`AgentSseController` 1→0，`AiController` 4→2，`AgentMemoryController` 6→2 |

### 变异检查（每个新守卫都被重新注入过一次原缺陷）

| 变异 | 重新注入的东西 | 应变红的测试 | 实测 |
| --- | --- | --- | --- |
| M1 | `chat(1L, ...)`（等于旧的 `defaultValue=1`） | `erpAgentUsesAuthenticatedUser`、`erpAgentRequiresIdentity` | 2/2 红 |
| M2 | `isMockProfile()` 恒真 | `nonMockProfileRefuses`（`expected 400 but was 200`） | 1/1 红 |
| M2b | 「profile 未声明就当 mock」 | `unsetProfileRefuses` | 1/1 红 |
| M3 | `switchLanguage` 改回 `fromCode` | `testIllegalLanguageRejected`、`testBlankLanguageRejected` | 2/2 红 |
| M4 | AgentChat 重新带 `userId` | `后端返回 Result JSON…` | 1 红 / 9 绿 |
| M5 | 提交被清空与非法的字段 | 两条保存用例 | 2 红 / 10 绿 |
| M6 | 身份改读 `localStorage.user_id` | 含「身份缺失」「身份取自 /user/getInfo」 | 3 红 / 9 绿 |

M2 只打中一条是**有意义的**：`getActiveProfiles()` 返回空数组时根本进不了循环体，
所以「缺省即拒绝」这条属性只能由 M2b 单独证明——两个变异、两条用例，各自钉住自己的性质。
四个 Java 变异 + 三个前端变异跑完后源文件字节级还原（sha256 在 `finally` 里核对：
`AiController 451aa7540d8985c2`、`AgentMemoryController 66226170075384e7`、
`AgentChat.vue 3aff13c71aa8095a`、`AgentMemory.vue 5747f65da1f2b883`）。

## 4. 顺手修掉的清点工具缺陷（两处，方向相反）

1. **假红**：`strip_comments` 先剥块注释，于是 `api/*.ts` 这种写在**行注释**里的文本被当成块注释起点，
   一路吞到下一个 `*/`，把 `agentMemory.ts` 真正的调用行删没，`GET /preference/{userId}` 被报成缺口。
   改成一次性交替匹配 `(?<!:)//[^\n]*|/\*.*?\*/`（从左到右消费，行注释先没了，块注释内的 `//` 也不会提前收尾）。
2. **假绿**：`build_matcher` 没有右边界，`/ai/chat` 被 `/ai/chat-stream` 命中，
   于是前端根本没调的 `POST /ai/chat` 被算成「已接入」。补 `(?![\w-])`（斜杠仍允许，子路径不受影响）。
   修完 `POST /ai/chat` 回到候选清单——这是**多报一条**，不是少报。

两条都写成了 `--self-test`（5 条断言，含 v7 那次「注释里的路径不算调用」的回归），
`python tools/schema/endpoint_coverage_audit.py --self-test` → `SELFTEST 5/5 passed`。

## 5. 遗留（本轮不做，理由写清楚）

- `MemoryServiceImpl#getOrCreatePreference` 建默认行时把 `preferredShopId` 写死成 `1L`，
  于是「读一次偏好」就有写副作用且带一个编出来的店铺。要改得先定「新用户到底该没有偏好、
  还是必须有真实店铺」，属于产品口径决策，不是本轮的接入口题。
- 全站店铺下拉的数据源仍是 `main.ts` 的三个示例店铺，且 `setShops()` 无人调用；
  真实授权店铺列表在 JWT 的 shops 声明里，后端没有对应端点。要接真数据得先补一个「我的店铺」读接口。
- `POST /ai/chat`、`POST /ai/agent/chat`、`POST /ai/eval/run`：判为不接。前两条要真实 LLM key，
  `/eval/run` 不鉴权且每次烧 token——它更该先加鉴权而不是接进 UI（已作为加固建议上报）。
- 语言代码严格化只落在写偏好这一侧；`LanguageEnum.fromCode` 的「未识别即 ZH」语义保持不变，
  两处不同规则是有意的，测试已分别钉住。
