# 7s：Agent 评测面板接线（`POST /ai/eval/run`）

日期：2026-10-03 · 分支：master · 关联：#53、`2026-10-03-endpoint-coverage-v14-ledger.md` E 节

## 1. 为什么这条值得接，而 A/B 桶那些不值得

`AiController:102-125` 读到的事实：
- 缺省 `mode=keyword` 走 `agentEvalRunner.runAll()`，**不依赖模型密钥**，所以它在任何环境都能给出真结果；
- `mode=both` 需 `AGENT_LLM_EVAL_ENABLED=true` + `deepseek.api-key`，不满足时**返回业务失败**而不是退化；
- 每次运行 best-effort 落库 `amz_agent_eval_log`。

这是 38 条候选里唯一一条「后端已经真实可用、只缺入口」的，所以接线不产生假按钮。

## 2. 改动

新增 `src/api/agentEval.ts`（路径字面量内联，理由与 `agentMemory.ts` 同：抽 BASE 会让清点工具在源码里找不到完整路径）、
`src/views/AgentEval.vue`、路由 `/agent-eval`、侧边栏「Agent 评测」；
e2e 打桩加 `/ai/eval/run`（fixture 自洽：4 条用例、3 通过、1 失败、passRate=0.75）。

三条展示口径是硬的：
- `code!==200` 只显示后端文案，**不渲染汇总/条目**——把拒绝显示成「0 条通过」是在制造第二个事实；
- 通过率是 `passRate×100`（后端 `AgentEvalRunner:100` 是 `passed/size` 的 0~1 比值，不是百分数），
  且 `totalCases<=0` 时显示 `—`；
- 缺失关键词/错误为空的单元格显示 `—`，不留空。

## 3. 验收

| 闸口 | 结果 |
| --- | --- |
| `vitest run src/__tests__/AgentEval.test.ts` | 5 passed（新增文件，收集数从 0 → 5） |
| `vitest run`（全量） | **40 files / 458 passed**（此前 453，恰好 +5） |
| `vue-tsc --noEmit` | rc=0 |
| `vite build` | ✓ built in 4.61s |
| `playwright test e2e/agent-eval.spec.ts` | 3 passed（首轮 2 failed：`locator('select')` 撞上页头的店铺下拉、`hasText:'通过'` 同时命中「通过率」——都是我的选择器不严，改定位方式后过，没动断言） |

变异检查（每次只改一处、`finally` 里按字节恢复并校验 sha256）：

| 变异 | 结果 | 红的用例 |
| --- | --- | --- |
| M1 拒绝分支永不进入（`if (!res && Math.random()<0)`） | 1 failed / 4 passed | both 模式被后端拒绝时… |
| M2 通过率不按比值换算（`r.toFixed(1)`） | 1 failed / 4 passed | 缺省以 keyword 模式调用后端… |
| M3 空结果「不知道」守卫去掉 | 1 failed / 4 passed | 后端返回空结果集时… |
| 恢复 | sha256 一致，5 passed | — |

三个变异各打红**不同**的用例，说明这三条口径是分别被看着的，不是一条宽断言在兜。

清点闸口：`endpoint_coverage_audit.py`（先 `--self-test` 通过）
`endpoints_without_a_frontend_name 60 → 59`、`user_facing_candidates 39 → 38`，
候选列表里 `/ai/eval/run` 消失——这是「接线」唯一可信的外部证明。

## 4. 剩余台账

38 条里：A 桶（必然失败/造数、已门禁、或语义会让页面说谎）14、B 桶（缺外部凭证）19、
C 桶（本轮已收口的旧商品接口）3、D 桶（口径重复）2、E 桶只剩本条 #7（已接线）。
`account/{id}/test` 与 `message/{id}/reply` 不是「等接线」，是等后端口径
（7f:21-23 已判定：前者只校验格式却改写账号状态，后者只写本地库不会发到平台）；
`oauth/token` 是机机接口，本来就不给浏览器（7c/7d/7f）。这三条已从 v14 台账的 E 桶更正到 A 桶。
另有一条**已接线路径上的硬缺陷** #52（`amz_replenishment_suggestion` 缺三列）等用户定夺：
补 `V10` 迁移会 ALTER 共享演示库，改 `exist=false` 会丢 ML 可追溯性，两条都不该我替你选。
