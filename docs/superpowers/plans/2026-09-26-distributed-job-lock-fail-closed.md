# DistributedJobLock fail-closed 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and superpowers:executing-plans. Checkbox syntax tracks implementation.

**Goal:** 消除 `DistributedJobLock` 在 Redis 缺失、Redis 异常或抢锁结果不确定时默认 fail-open 导致的多实例重复执行，同时保留仅供已证明幂等/只读任务使用的显式降级入口。

**Architecture:** `DistributedJobLock` 默认对不可确认的锁状态 fail-closed；`runIdempotentWithLock(...)` 作为显式、按任务审计的降级入口。业务调用点不批量迁移，锁语义由独立单测验证，真实 Redis 双实例回放作为生产验收门槛。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Data Redis、Micrometer、JUnit 5、Mockito、Redis。

**Status:** Completed (code-level; real Redis and two-instance integration pending)

**Evidence:** `DistributedJobLockTest` **9 / 0F / 0E / 0S**；全仓 **19/19 模块 BUILD SUCCESS**，Surefire **164 份 / 1068 / 0F / 0E / 2S**。

## Global Constraints

- 不执行 `git add .`、`git commit`、`git reset`、`git clean`。
- 不修改 6 个既有 staged 删除文件。
- 不提供全局 fail-open 开关；非幂等/未知幂等性任务必须 fail-closed。
- 无真实 Redis 和双实例证据时，不得声明生产互斥已验证。

---

### Task 1: 锁定 fail-closed 语义

**Files:**
- Modify: `amz-common/src/main/java/com/amz/lock/DistributedJobLock.java`
- Create: `amz-common/src/test/java/com/amz/lock/DistributedJobLockTest.java`

- [x] 写 Redis 异常、RedisTemplate 缺失、抢锁失败、显式幂等降级和非法租期测试。
- [x] 先确认旧实现会在 fail-closed 断言处失败。
- [x] 将默认路径改为不可确认即跳过，并增加显式幂等降级入口。
- [x] 运行 `DistributedJobLockTest`，结果为 **9 / 0F / 0E / 0S**。

### Task 2: 增加锁指标与释放边界

**Files:**
- Modify: `amz-common/src/main/java/com/amz/lock/DistributedJobLock.java`
- Modify: `amz-common/src/test/java/com/amz/lock/DistributedJobLockTest.java`

- [x] 增加 `acquire.failed`、`degraded`、`skipped` 指标及 reason tag。
- [x] 先写 `release.failed` 断言并确认红灯：`expected: <1.0> but was: <0.0>`。
- [x] 加入最小实现，记录释放锁失败且不覆盖业务结果。
- [x] 重跑定向测试，结果为 **9 / 0F / 0E / 0S**。

### Task 3: 回归与文档收敛

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`
- Modify: `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`
- Modify: `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`

- [x] 运行全仓 `mvn -B -ntp test` 并记录原始模块和 Surefire 汇总。
- [x] 将 P0-02 标记为“代码级已修复，待真实 Redis 双实例回放”。
- [x] 补充 REL-13 验收矩阵与 G.19 轮次记录。
- [ ] 真实 Redis + 双实例验收：断连不执行写任务、同窗仅一个副作用、崩溃后租期到期可接管、释放失败/降级可告警。
- [ ] 评估锁续租/watchdog 或任务超时保护，并单独测试长任务跨租期行为。

## 验收标准

- 代码级：Redis 不可用或抢锁不确定时，默认路径不执行业务 action。
- 代码级：只有显式幂等入口可降级，且降级有指标。
- 代码级：释放锁失败不覆盖业务结果，并有独立指标。
- 生产级：真实 Redis 双实例、断连、租约过期、崩溃接管和长任务跨租期回放全部有原始证据后，才能提升证据等级。