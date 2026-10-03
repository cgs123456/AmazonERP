# 7q：选品趋势两列的列名漂移（驼峰推断在数字前不加下划线）

日期：2026-10-03 · 分支：master · 依赖工具：`tools/schema/entity_column_drift.py`（7p 引入并修好）

## 1. 发现

7p 修完核对器的两个假阳后，全仓剩 6 条真实漂移，其中两条落在**已经接线的前端路径**上，
比旧商品接口更严重：

```
amz_selection_opportunity  ops/SelectionOpportunity  HARD=[trend30d, trend90d]
amz_replenishment_suggestion  spapi/ReplenishmentSuggestion  HARD=[blend_strategy, ml_confidence, ml_predicted_demand]
```

选品这条的根因不是缺迁移，而是**命名推断的边界**：MyBatis-Plus 的驼峰转下划线只在
大写字母前插 `_`，数字不是大写字母，所以

| Java 字段 | MP 推出的列 | DDL 实际列 | 结果 |
| --- | --- | --- | --- |
| `avgRating` | `avg_rating` | `avg_rating` | 正常 |
| `trend30d` | `trend30d` | **`trend_30d`** | 1054 Unknown column |
| `trend90d` | `trend90d` | **`trend_90d`** | 1054 Unknown column |

`amz-service-ops/.../V1__init.sql:71-72` 建的是 `trend_30d/trend_90d`，
实体 `SelectionOpportunity.java:64,67` 没写 `@TableField`。
选品页每次 `selectList`（字段表是拼出来的全列）都会 1054；
`ProductSelectionServiceImpl` 落库与 `:294` 回读 AI 请求都碰这两列。
mock 测试全绿，因为 wrapper 从没真正生成过 SQL。

## 2. 修法

实体上显式声明列名（对齐 DDL，不动表结构、不改 JSON 字段名，前端零改动）：

```java
@TableField("trend_30d")
private String trend30d;

@TableField("trend_90d")
private String trend90d;
```

并加闸：`SelectionOpportunityColumnContractTest` 用 MP 自己的 `TableInfoHelper` 解析实体，
再把 `V1__init.sql`（随 jar 打包，测试 classpath 上就有）的建表块解析成列集合，
断言「实体映射出的每一列都在 DDL 里」。这是把 7p 那个离线工具的同一条规则，
收敛到**这一张已接线的表**上做回归，而不是把全仓 5 条已知漂移一起塞进 CI。

## 3. 验收（四个数）

闸口命令：`mvn -q -pl amz-service/amz-service-ops -am test -Dtest=SelectionOpportunityColumnContractTest`

| 阶段 | 结果 |
| --- | --- |
| 加注解前（RED） | Tests run: 2, **Failures: 2**，报错原文 `映射了 DDL 里不存在的列：[trend30d, trend90d]`、`expected: <trend_30d> but was: <trend30d>` |
| 加注解后 | Tests run: 2, Failures: 0；ops 模块整体 `Tests run: 31, Failures: 0`（原 29 + 新 2） |
| 变异 M1：两条注解全部注释掉 | Tests run: 2, **Failures: 2**，drift 列表 `[trend30d, trend90d]` |
| 变异 M2：只注释 `trend_90d` | Tests run: 2, **Failures: 2**，drift 列表 `[trend90d]` ← 逐列可归因 |
| 恢复 | 源文件 sha256 一致，Tests run: 2, Failures: 0 |

M1/M2 红数相同（两条断言看的都是这两列），**区别在报错点名的列**：
M2 只列出 `trend90d`，说明闸口报的是具体漂移列而不是笼统失败。

离线核对器复核：全仓 `hard-mismatch` 6 → **5**，`amz_selection_opportunity` 消失。

## 4. 同类陷阱排查

写了个针对「字段名里带数字且没有 `@TableField`」的探针，扫全部 103 个实体：
只有 `ad/AdAutoRule.java:32 conditionValue2` 一条命中，而它是**数字结尾**
（`conditionValue2` → `condition_value2`，与 DDL 一致），且核对器里 AdAutoRule 无漂移 →
判定为同类形状、非同类缺陷。真正的坑形是「数字在中间且 DDL 用下划线分隔」。
探针是临时脚本，未提交。

## 5. 剩下来的一条硬骨头：`amz_replenishment_suggestion`

`ReplenishmentSuggestion` 的 `ml_predicted_demand/ml_confidence/blend_strategy`
在三张 Flyway 脚本和 legacy 建表里都不存在（V1~V9 全无），但**有真实写入方**：
`HybridReplenishmentEngine:149-151` 会回填这三个值，`ReplenishmentController`、
`SpapiController`、`ReplenishmentScheduler` 都经 `ReplenishmentSuggestionMapper` 读写这张表。
也就是说：

- 任何 `selectList/selectById` 一定 1054（SELECT 字段表含这三列）；
- 只有走纯规则分支（三列为 null、MP 默认策略不写入 null 字段）的 insert 侥幸能过。

这条与选品不同，**不是注解能解决的**：数据是引擎真产出的，缺的是列。出路只有两种，
且都需要用户点头，因此本轮**只做记录不做改动**：

1. 补一条 `V10__replenishment_ml_columns.sql`（三列全 NULL 可空）。它是自动应用的：
   下一次 spapi 服务启动就会对共享演示库执行 `ALTER TABLE ... ADD COLUMN`，
   属于影响共享环境的动作，不能替用户决定；
2. 或者把这三列从持久化里摘出去（`@TableField(exist = false)` + 引擎只回传不落库），
   代价是丢掉 ML 预测的可追溯性。

本轮没有触碰任何数据库，也没有启动任何服务。
