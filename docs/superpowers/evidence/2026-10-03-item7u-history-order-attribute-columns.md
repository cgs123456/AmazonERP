# 7u：两处「实体字段名 ≠ 列名」的已接线漂移（搜索历史 / 订单属性）

日期：2026-10-03 · 分支：master · 依赖：7p 的核对器、7q 的契约测试形状

## 1. 从剩余 5 条漂移里挑出来的两条

7p 的核对器给出 5 条真实漂移。7q 修掉 `amz_selection_opportunity`，本轮把剩下 5 条里
**落在已接线路径上**的两条查清并修掉；另外 3 条的处置见第 5 节。

两条都不是缺列，而是「字段名换了、列名没换」，和 7q 同一族：

| 表 | DDL 列（Flyway） | 实体映射 | 真库后果 |
| --- | --- | --- | --- |
| `amz_history` | `id, user_id, **keyword**, create_time` | `@TableField("history")` | `HistoryServiceImpl.getHistoryList` 的 selectList → 1054 |
| `amz_order_attribute` | `id, order_id, **name**, value, create_time` | `label`（无注解，推断成 `label`） | `OrderServiceImpl:423-427` 每条属性 insert → 1054 |

## 2. 为什么这两条比「页面打不开」更坏

- 搜索历史：`ProductSearch.vue:180` 真的在调 `GET /search/getHistoryList`，页面直接 500；
  而写入侧 `SearchServiceImpl:102-118` 把 insert 包在 try/catch 里只打 WARN
  （「搜索记录为辅助功能，失败不应阻断主流程」）——**搜索看着成功，历史从此不再落库**。
  一个静默降级把另一个可见故障盖住了。
- 订单属性：只有 insert 侧碰这两列，读侧不报错，所以「订单详情里没有属性」被当成数据少，
  而不是写失败。

## 3. 修法

只动实体，不动表、不动 JSON 契约（前端 `SearchHistory.history`、订单属性的 `label` 字段名不变）：

```java
@TableField("keyword")   // 原 "history"
private String history;

/** 列名是 name：字段沿用 label 是为了不改 JSON 契约，映射必须显式对齐。 */
@TableField("name")      // 原：无注解 → 推断成 label
private String label;
```

同时确认没有手写 SQL 需要同步改：两个 mapper 都没有 `@Select/@Update/@Insert`，
`src/main` 下也没有引用旧列名的 XML。

## 4. 验收

闸口命令（各模块）：`mvn -q -pl <module> -am test -Dtest=<ContractTest>`

| 阶段 | 搜索 | 订单 |
| --- | --- | --- |
| 加注解前（RED） | Tests run: 2, **Failures: 2**（报错直印 `映射了 DDL 里不存在的列：[history]` 与 `expected: <keyword> but was: <history>`） | Tests run: 2, **Failures: 2**（`[label]` / `expected: <name> but was: <label>`） |
| 加注解后 | 2/0，整模块 `mvn -q … test` rc=0 | 2/0，整模块 rc=0 |
| 变异 H1/O1：把注解改回原值 | Tests run: 2, **Failures: 2**，红的就是 `HistoryColumnContractTest` | Tests run: 2, **Failures: 2**，红的就是 `OrderAttributeColumnContractTest` |
| 恢复 | 源文件 sha256 一致，2/0 | 源文件 sha256 一致，2/0 |

两支测试都不写死列名清单：从 classpath 上的 `db/migration/V1__init.sql` 解析建表块，
再用 MyBatis-Plus 自己的 `TableInfoHelper` 取实体真实映射列（7q 的同一形状），
所以「DDL 改了」和「实体改了」两个方向都能红；解析为空时直接失败，不会当成通过。

surefire 报告目录求和（含上一轮全模块跑留下的行，仅作参考）：search 5、order 74，0 失败 0 错误。
真正的绿信号是 `mvn -q` 的 rc=0 与两支新类各自的 `Tests run: 2, Failures: 0`。

核对器复核：`python tools/schema/entity_column_drift.py --self-test .` 6 项 PASS，
全仓 `hard-mismatch` 5 → **3**。

## 5. 剩下 3 条漂移的处置

| 表 | 状态 |
| --- | --- |
| `amz_product`（`pojo/Product` 旧列 7 个） | 7p 已 fail-fast 收口，实体保留但 HTTP 入口一律拒绝；不补列是等迁移决策 |
| `amz_shop`（product 模块 `pojo/Shop`） | 7p 之后 `ShopMapper` 在该模块已无引用，是孤儿实体。**没删**：删除要先证明不可达（`ProductVo.shop` 仍引用该类型），且它是另一件事 |
| `amz_replenishment_suggestion`（ML 三列） | #52，等用户定夺（补 `V10` 会 ALTER 共享演示库；改 `exist=false` 丢可追溯性） |

这 3 条都不是「再改一次注解」能收的，所以本轮不硬凑。核对器仍未进 CI：
它现在还会红 3 条，其中 2 条是待决策项——先把闸做成永远红、再被人绕过，比没有闸更糟。
## 6. 追加：孤儿旧实体删除 + 漂移闸门上线（同一轮）

证明不可达后才动：`pojo/Shop` 全仓只被 `ProductVo.shop` 引用，`ShopMapper`（product 模块）
在 7p 之后只剩自身声明 + 一个测试里的空 mock + 一句注释；前端没有任何镜像类型。
于是删掉 `amz-service-product/.../mapper/ShopMapper.java` 与 `.../pojo/Shop.java`、
`ProductVo.shop` 字段、测试里的空 mock，并把提到已删除类的注释改写为不点名。
商品模块 `mvn -pl amz-service-product -am test` BUILD SUCCESS，
`ProductServiceImplSearchPagingTest` 仍 4/4（那 4 条断言的是分页契约，与 mock 字段无关）。
核对器：`entities checked 103 → 102`、`hard-mismatch 3 → 2`。

闸门 `--gate` 模式：`PENDING` 里登记两条刻意保留的漂移（`amz_product` 已收口等迁移、
`amz_replenishment_suggestion` 等 #52 决策），只在两种情况开口——出现没登记的新漂移，
或某条豁免与现状不再匹配（列集变了或已修好），后者防止豁免变成永久绿灯。
`--self-test` 从 6 项扩到 10 项，新增 4 项全打在闸门自身：新表要红、登记的表要放行、
豁免失效要红、登记表多出新缺口也要红。

**闸门差点是假的。** 第一次真树变异（把 `@TableField("trend_30d")` 注释掉）跑出来
仍 rc=0、`drifted_tables=2`——因为实体解析器会把**注释行**里的 `@TableField` 当成有效注解，
于是「删掉守卫」这个动作对扫描器完全隐形。修成跳过 `//`、`*`、`/*` 开头的行之后：

| 阶段 | 结果 |
| --- | --- |
| 干净树 `--gate` | rc=0，scanned=102 drifted=2 pending=2 |
| 变异：注释掉 `@TableField("trend_30d")` | rc=**1**，hard-mismatch 3，`GATE RED new-drift: amz_selection_opportunity` |
| 恢复 | rc=0，与变异前逐项一致（源文件按字节回写并比对相等） |

CI 侧新增 `Entity/column drift self-test + gate` 步骤（hygiene 作业内，python 3.11 已有），
自检不过就不必谈闸门。
