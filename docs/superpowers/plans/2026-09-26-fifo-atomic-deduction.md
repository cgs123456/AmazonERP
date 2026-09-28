# FIFO 原子扣减实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 FBA FIFO 出库在并发场景下的丢失更新和超卖，任一批次扣减失败时整体事务失败。

**Architecture:** `InventoryBatchMapper` 提供带 `available_quantity >= qty` 条件的原子扣减 SQL；`FbaShipmentServiceImpl#fifoOutbound` 按 FIFO 顺序逐批调用并检查受影响行数，返回 0 时抛出业务异常，由现有 `@Transactional(rollbackFor = Exception.class)` 回滚此前扣减。单元测试使用线程安全的内存扣减模拟器验证并发算法，并通过 SQL 契约测试防止条件被移除；真实 MySQL 并发集成测试仍需在具备测试库的环境执行。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、MySQL InnoDB

**Spec:** `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`

**Status:** Completed (code-level; real MySQL integration pending)

**Evidence:** procurement 与 amz-common 新鲜回归合计 **178 / 0F / 0E / 0S**；真实 MySQL 多连接并发、锁等待、死锁/重试和事务回滚守恒仍未验证。

## Global Constraints

- 不提交、不推送；当前工作区存在用户既有改动和 6 个暂存删除文件，禁止 `git add .`、`git commit`、`git reset`、`git clean`。
- 无真实 API 请求 ID、响应和权限验证时，不得把 API 联调或生产就绪状态写成已验证。
- 数据库为 MySQL InnoDB；扣减必须是相对更新，不能使用读后覆盖写。
- SQL 的 `status` 赋值必须位于 `available_quantity` 赋值之前，避免 MySQL 单表 UPDATE 从左到右求值导致重复扣减。
- 失败请求不得返回部分成功；受影响行数为 0 时必须抛异常并触发事务回滚。

---

### Task 1: 用失败测试锁定原子扣减契约

**Files:**
- Modify: `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/FbaShipmentServiceImplTest.java`
- Create: `amz-service/amz-service-procurement/src/test/java/com/amz/mapper/InventoryBatchMapperContractTest.java`
- Create: `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/FbaShipmentFifoAtomicDeductionTest.java`

**Interfaces:**
- Consumes: `InventoryBatchMapper.decreaseAvailableQuantityAtomic(Long id, Long shopId, String sku, Integer qty)`
- Produces: 对 SQL 守卫、服务调用方式和并发失败行为的回归断言

- [x] **Step 1: 写并发算法失败测试**

新增 100 轮双线程测试：初始可用量 5，两次请求各出库 3；两个线程都必须先读到旧库存，再竞争原子扣减。断言恰好 1 次成功、1 次库存不足、最终可用量为 2。

- [x] **Step 2: 写 Mapper SQL 契约失败测试**

通过反射读取 `@Update` SQL，断言包含：
- `available_quantity = available_quantity - #{qty}`
- `available_quantity >= #{qty}`
- `shop_id`、`sku`、`status = 'ACTIVE'`
- `status` 的 CASE 表达式位于相对扣减赋值之前

- [x] **Step 3: 运行测试确认失败**

Run: `mvn -pl amz-service/amz-service-procurement -am -Dtest=FbaShipmentServiceImplTest,InventoryBatchMapperContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL，原因是 Mapper 尚无原子扣减方法，服务仍走 `updateById`。
Observed: 临时还原 `updateById` 后，并发测试出现 2/2 成功，失败信息为 `expected: <1> but was: <2>`；恢复原子实现后通过。

### Task 2: 实现数据库原子扣减

**Files:**
- Modify: `amz-service/amz-service-procurement/src/main/java/com/amz/mapper/InventoryBatchMapper.java`
- Modify: `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/FbaShipmentServiceImpl.java`

**Interfaces:**
- Produces: `int decreaseAvailableQuantityAtomic(Long id, Long shopId, String sku, Integer qty)`；返回 1 表示成功，0 表示库存不足或批次状态/租户不匹配。

- [x] **Step 1: 增加 Mapper 条件更新**

使用 `UPDATE amz_inventory_batch`，条件包含 `id`、`shop_id`、`sku`、`status = 'ACTIVE'`、`available_quantity >= qty`；将可用量相对减少，并在同一 SQL 中将扣至 0 的批次置为 `DEPLETED`。

- [x] **Step 2: 服务改为检查受影响行数**

删除 `updateById(batch)` 路径；每次调用原子扣减，返回 0 时抛出 `CodeErrorException`。异常会使整个事务回滚，禁止继续下一批或返回部分结果。

- [x] **Step 3: 运行定向测试确认通过**

Run: `mvn -pl amz-service/amz-service-procurement -am -Dtest=FbaShipmentServiceImplTest,InventoryBatchMapperContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS，0 failures，0 errors。
Observed: 定向测试 **14 / 0F / 0E / 0S**。

### Task 3: 模块回归与证据边界

**Files:**
- Modify: `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`
- Modify: `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`
- Modify: `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`
- Modify: `README.md`

- [x] **Step 1: 运行 procurement 全模块测试**

Run: `mvn -pl amz-service/amz-service-procurement -am test`
Expected: 全部通过；记录 tests/failures/errors/skipped 原始数字。
Observed: `amz-common` **110 / 0F / 0E / 0S** + procurement **68 / 0F / 0E / 0S**，合计 **178 / 0F / 0E / 0S**；`BUILD SUCCESS`。

- [x] **Step 2: 更新 P0-01 状态与证据边界**

明确区分“SQL 原子性与服务并发算法已通过代码级测试”和“真实 MySQL 并发集成回放未执行”。不得把后者标记为已验证。

- [x] **Step 3: 检查工作区隔离**

Run: `git diff --check -- <相关文件>` 和 `git status --short`
Expected: 无空白错误；6 个既有暂存删除文件保持原状；不产生提交。
Observed: `git diff --check` 退出码 0；仅有 CRLF 提示，不是空白错误。