# 7p：旧商品接口按实体-表漂移收口（fail-fast 拒绝，不再 500）

日期：2026-10-03 · 分支：master · 关联：#39（剩余 39 条待判类）、上一轮清点里「阻塞在迁移：商品主数据 3 + `/order/saveOrder`」

## 1. 先量机制，再动代码

上一轮为了不再靠猜，写了静态核对器 `tools/schema/entity_column_drift.py`（实体映射列 vs
Flyway/legacy 建表脚本真实列）。首版有**两个假阳**，都在自我复核时被抓出来并修掉：

| 缺陷 | 现象 | 后果 |
| --- | --- | --- |
| `ALTER_RE` 用非贪婪 `(.*?);` 收尾 | V5 的 `COMMENT 'LWA or RDT; existing rows are LWA'` 里那个分号把 ALTER body 截断 | 一条四子句 ALTER 只解析出 1 列 → 误报 `amz_spapi_call_outbox` 硬缺 3 列 |
| 实体只打印文件名 | 三个模块各有一个 `Shop.java` | `amz_shop` 的告警无法归因 |
| `ROOT = sys.argv[1]` | `--self-test .` 时把选项当根目录 | 自检出「真文件不存在」FAIL，看着像检出缺陷、其实是被检物没进嘴 |

修完后 `--self-test` 6 项全 PASS（含一条**负向对照**：同一条 fixture 用旧正则确实丢列，
证明 fixture 咬到了 bug），全仓扫描从 7 条降为 **6 条真实漂移**，`amz_spapi_call_outbox` 消失，
`amz_shop` 归因到 `amz-service-product/.../pojo/Shop.java`。

## 2. 本轮处置的对象：ProductMapper 通路的 5 个接口

`Product`（`@Deprecated`）映射 `name/type/image/sales/stock/user_id`。核对器 + 手工三处确认：

- Flyway `V1__init.sql:43-59` 建的是新 schema（`shop_id/sku/asin/marketplace_id/title/…`）；
- 该表唯一的后续迁移 `V2__listing_product_type.sql` 只 `ADD COLUMN product_type`；
- `docker/init-sql-legacy/04-init-tables-product.sql:4` 明写「主表已迁移至 09…（新 schema）」，
  即 legacy 建表脚本里这张表也已是新 schema。

所以 7 个旧列在两条建表路径里都不存在，`ProductController` 的 5 个入口今天一调就是
MySQL 1054 → HTTP 500。`grep` 确认前端零调用；`getProduct`/`updateProduct` 有 Feign 调用方
（`amz-service-order/ProductClient`），其中 `OrderServiceImpl:385` 在 `saveOrder` 链路上
`productClient.getProductById(...)` —— 这正是上一轮把 `/order/saveOrder` 归入「阻塞在迁移」的同一根因。

## 3. 改动

`ProductServiceImpl`：5 个方法体收敛为入口拒绝，理由常量 `LEGACY_DRIFT_REFUSAL` 里点名
表、缺失列、替代接口（`/product/master/*`）和出路（补齐列的迁移 SQL）。
随迁移逻辑一起删掉的是 `ShopMapper`/`MongoTemplate` 两个字段（本轮改动后再无引用）。

`searchProducts`/`selectById`/`updateById` **不加守卫**：它们不在任何 HTTP 映射上，
而 `ProductServiceImplSearchPagingTest`（P1-01 硬编码 `LIMIT 20` 的回归）依赖其分页语义。
一旦被暴露，需同步加同样的守卫——已写在类注释里。

## 4. 验收（三个数一起报）

闸口命令：`mvn -q -pl amz-service/amz-service-product -am test -Dtest=ProductLegacyDriftFenceTest`

| 阶段 | 结果 |
| --- | --- |
| 加守卫前（RED） | Tests run: 3, **Failures: 3**（失败理由就是 `请先选择店铺` / 落到 mock，证明断言咬的是现网行为） |
| 加守卫后 | Tests run: 3, Failures: 0 |
| 变异 M1：去掉 `getProductList` 守卫，回落到 `productMapper.selectList` | Tests run: 3, **Failures: 3**（三个方法全红，含 `verifyNoInteractions(productMapper)`） |
| 变异 M2：去掉 `postProduct` 守卫，直接 `Result.success(null)` | Tests run: 3, **Failures: 1**（只有走查五个入口的那条红） |
| 恢复原文件 | sha256 一致，Tests run: 3, Failures: 0 |

M1 与 M2 红数不同（3 vs 1）正是**逐入口归因**的证据：读路径漏守卫会连带两条专属断言，
写路径漏守卫只有走查断言能抓到。走查断言改成「逐个判定再汇总」，否则第一个入口失败就中断，
看不出还有哪几个入口漏了守卫。

同模块相邻测试保持绿：`ProductServiceImplSearchPagingTest` 4/4、`ProductMasterServiceImplTest` 9/9，
三个类同跑合计 `Tests run: 16, Failures: 0`。

> 过程注记：中途一次用 `-Dtest=A+B+C` 跑「成功」其实是**零测试的假绿**（surefire 分隔符是逗号，
> 加上 `-Dsurefire.failIfNoSpecifiedTests=false` 后静默通过）。改回逗号后才拿到 16 这个数。

## 5. 没做什么，为什么

- **不写补齐列的迁移**：给新 schema 加回 `name/image/sales/stock/user_id` 是给一张没有数据的表
  凭空补列，且会改到共享演示库的活表；那是需要单独批准的数据决策。
- **不接前端**：接口今天不能工作，加按钮就是把 500 摆到页面上。
- `saveOrder` 只记录不改动：它的失败根因在这里，但是否允许「无商品的裸订单」是产品口径。
- 核对器**没进 CI**：现存 6 条真实漂移里有 4 条（`amz_history.history`、`amz_order_attribute.label`、
  `amz_shop` 旧列、以及已处置的 `amz_product`）今天就会让它红，先作为排查工具提交，
  等逐条判类完再决定当闸。

## 6. 剩余 6 条漂移判类（本轮更新）

| 表 | 实体 | 硬缺列 | 处置 |
| --- | --- | --- | --- |
| `amz_product` | product/pojo/Product | name,type,image,sales,stock,time,user_id | 本轮收口 |
| `amz_shop` | product/pojo/Shop | fans,image,name,sales,time,user_id | 待判：`ShopMapper`（product 模块）现已无引用，随下一轮决定是否连同 `ProductAttribute` 一起清 |
| `amz_history` | search/pojo/History | history | 待判 |
| `amz_order_attribute` | order/pojo/OrderAttribute | label | 待判 |
| `amz_replenishment_suggestion` | spapi/ReplenishmentSuggestion | blend_strategy,ml_confidence,ml_predicted_demand | 待判：**已接线读路径**，真库上会 1054 |
| `amz_selection_opportunity` | ops/SelectionOpportunity | trend30d,trend90d | 待判：**已接线读路径**，真库上会 1054 |

清点口径变化：39 条待接里，商品旧接口 3 条转为「已收口：漂移未迁移」，剩 **36** 条；
其中后两行是**新的红灯候选**（前端已接线 + Flyway 建库 → 调用即 500），下一轮优先。
