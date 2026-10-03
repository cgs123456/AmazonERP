# 7y：三条运营扫描从「静默 0」改成显式拒绝，定时任务整轮跳过

日期：2026-10-03 · 分支：master · 属于「非凭据缺口逐项处理」第 2 批

## 1. 这一条为什么算缺陷而不是「已门禁」

7g/7k 那几处 mock 门禁里，只有 ops 三条是 `return 0`：控制器写成 `Result.success(0)`，
调用方看到的是**「扫描成功，新增 0 条告警」**，与「真的扫过、一家店铺都没命中」在协议上完全同形。
AI 的 `reminder/scan` 与选品两条都是 `Result.failure("…仅在 mock 档可用…")`，
所以这一处是漏网的，不是设计选择。

## 2. 改动要同时管两头，否则会按下葫芦浮起瓜

- `OpsServiceImpl`：三处 `return 0;` → `throw new CodeErrorException("…未接入真实数据源：仅 mock 档…")`。
- `OpsMonitorScheduler`：`dailyScan` / `rankCapture` **不能**沿用「逐店 catch 记 ERROR」的老路——
  三条 scan 都被同一个定时任务按店铺循环调用，改抛异常后会每轮为每家店铺刷一条 ERROR，
  把一个静默假成功换成一个噪音故障。所以在任务入口判档：非 mock 直接 `log.warn` 一轮一条并返回。

调度器由此新增 `Environment`（`required = false`，与 service 同一口径：没注入按可用处理）。

## 3. 假绿的额外风险，用正向对照堵住

`dailyScan()` 的函数体是包在 `distributedJobLock.runWithLock(key, ttl, runnable)` 里的。
如果测试把锁当成普通 mock（不打桩），回调**根本不会执行**，
那么「跳过了」和「压根没跑到」两种情况的断言结果一模一样——`verifyNoInteractions` 白过。
因此新增 `positiveControlLockActuallyRunsTheBody`：mock 档下打桩让锁真的执行回调，
断言 `shopMapper` 确实被调用；这条绿了，才说明上一条「非 mock 不查店铺」是真结论。

## 4. 验收

| 检查 | 结果 |
| --- | --- |
| `mvn -q -pl amz-service/amz-service-ops -am test` | rc=0（模块全绿） |
| 新增 `OpsScanNonMockRefusalTest` | 3 passed |
| 变异 M1：三条扫描退回 `return 0` | 3 条里 **1 红**：`allThreeScansRefuseOutsideMock`（调度器守卫仍在，所以跳过用例正确地保持绿） |
| 变异 M2：摘掉调度器跳过守卫 | 3 条里 **1 红**：`schedulerSkipsOutsideMock` |
| 两次恢复 | service 与 scheduler 文件均按 sha256 校验字节一致 |

端点计数不变（仍是 35 条候选）：这三条本来就在 A 桶，改的是**返回的可信度**，不是接线与否。
