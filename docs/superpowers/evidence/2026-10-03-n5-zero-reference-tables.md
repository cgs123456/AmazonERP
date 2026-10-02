# N5 零引用表清点与处置建议（2026-10-03）

## 为什么重做一遍

上一版清单是手工三路 grep 得出的，而这个判定天生容易错：`amz_field_permission` 曾被列进零引用，
实际它由 `FieldPermissionServiceImpl` 用**原生 SQL** 读（`SELECT ... FROM amz_user.amz_field_permission`），
只 grep `@TableName` 看不见这种引用。所以把清点固化成脚本 `tools/schema/zero_reference_tables.py`，
输出带命中类型与出处，任何人可重跑复核。

三路引用（缺一即误判）：`@TableName` 实体绑定 / `*Mapper.xml` 表名 / main 代码里的原生 SQL 字面量。
**迁移脚本、测试、文档里的表名只算「被提到」不算「被使用」**——否则 `V3__order_coupon_key_type.sql`
改过 `amz_coupon` 的列，就会被读成「这张表在用」。

## 重跑命令与结果

```
python tools/schema/zero_reference_tables.py --json docs/superpowers/evidence/2026-10-03-zero-reference-tables.json
→ tables_total=113 zero_reference=12
```

结论与上一版一致：113 张 DDL 表里 **12 张零引用**（上一版表格里有 9 行，是因为有 3 行各并了 3 张与 2 张表）。
脚本自身按已知难例做过校验：`amz_field_permission` 判为「在用（native_sql）」、
`amz_attention` 判为「零引用，仅 `FlywayBaselineContractTest` 提到」、
`amz_order`/`amz_purchase_plan`/`amz_carrier_quote` 判为「在用（entity）」、
`amz_logistics_quote` 三路皆无命中。

## 逐张处置

| 表 | 建表出处 | 为什么零引用 | 建议 |
| --- | --- | --- | --- |
| amz_cart / amz_product_browse / amz_user_coupon / amz_coupon | product `04-init-tables-product.sql` | C 端下单/领券/浏览功能整体没做 | **保留 DDL 不动**，等功能真做时自然接上 |
| amz_attention | user `02-init-tables-user.sql` | 没有关注/粉丝类功能 | 保留 |
| amz_ad_placement_report | ad `24-init-tables-ad-upgrade.sql` | 广告报表实际读 `amz_ad_report` | 保留观察：与 amz_ad_report 是**不同粒度**（版位维度），不是重复建模 |
| amz_customer_service_kpi | customer `25-init-tables-customer-upgrade.sql` | 客服 KPI 没有任何计算与写入方 | 保留；本轮刚接好客服 UI，KPI 可以下一步从工单/邮件任务表聚合出来 |
| amz_oper_log | `18-init-tables-oper-log.sql` | 没有操作日志拦截器 | 保留；有明确落点（审单/规则/定价写操作都可挂），属于可补的功能 |
| amz_listing_seo / amz_report_template | `33-init-tables-p2-ai-tools.sql` | 功能未做 | 保留 |
| amz_logistics_quote | `33-init-tables-p2-ai-tools.sql` | `/logistics/dashboard/quotes` 读的是 `amz_carrier_quote`（实体绑定已核实） | **真重复**，可删 |
| amz_purchase_approval | procurement `V1__init.sql:220` | 审批走的是 `amz_purchase_plan.status`（DRAFT→PENDING_APPROVAL→APPROVED/REJECTED→CONVERTED），全仓无该表引用 | **不是纯重复**：status 只存「当前状态」，而这张表存的是**留痕**（operator / comment / ref_type+ref_id / 每次 APPROVE-REJECT）。删掉就等于承认审批永远不需要留痕。建议：要么把 `PurchasePlanServiceImpl` 的 approve/reject 路径接上写这张表（推荐，零引用变 0 张），要么明确「本项目不做留痕」再删 |

## 为什么本轮一张表都没删

1. 删表在 MySQL 里不可逆，且这里跑着一套共享的演示栈与影子库演练（V7/ad 迁移都做过备份恢复演练），
   不能由「代码没引用」这一个理由推导出「数据可以丢」；
2. Flyway 已执行的迁移不能改写，删表只能是**新增一条正向 `DROP TABLE` 迁移**，那属于单独的立项
   （V 序号 + 备份恢复 + 回滚说明），和 `docs/superpowers/runbooks/reference-key-convergence-rollback.md`
   同级；
3. 上表里真正「可删」的只有 `amz_logistics_quote` 一张，另一张 `amz_purchase_approval` 的正确解法是
   **补功能消灭零引用**而不是删表。

## 本轮未做的验证

- 没有连任何真实 MySQL 统计这 12 张表的行数；「表里有数据但代码不读」这种情况脚本看不出来，
  真要删之前必须先数行（沿用 `tools/db-migration/ad_v7_preflight.py` 的做法）。
- 脚本按「三路引用」判定，不看反射拼表名的动态 SQL；已核对没有这种写法，但若将来出现需要加第四路。
