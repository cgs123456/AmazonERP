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
→ tables_total=113 zero_reference=12    （接审批留痕之前）
→ tables_total=113 zero_reference=11    （接完留痕后重跑，见文末「本轮已消灭一张」）
```

结论与上一版一致：113 张 DDL 表里 **12 张零引用**（上一版表格里有 9 行，是因为有 3 行各并了 3 张与 2 张表）。
脚本自身按已知难例做过校验：`amz_field_permission` 判为「在用（native_sql）」、
`amz_attention` 判为「零引用，仅 `FlywayBaselineContractTest` 提到」、
`amz_order`/`amz_purchase_plan`/`amz_carrier_quote` 判为「在用（entity）」、
`amz_logistics_quote` 三路皆无命中。

## 本轮已消灭一张：amz_purchase_approval 接上审批留痕

不是删表，而是补上它本来该有的功能：`PurchasePlanServiceImpl#approve` 现在在同一事务里写一行留痕
（`ref_type=PLAN`、`ref_id=计划 id`、`shop_id` 取自计划本行而不是请求参数、`action=APPROVE/REJECT`、
`operator`、`comment`、`create_time`），并新增 `GET /procurement/plan/{planId}/approvals` 读取；
采购页每行多一个「审批留痕」展开块。要点：

- **写不上就整体失败**：`insert(...) != 1` 抛错，靠 `@Transactional` 回滚状态变更，
  不允许出现「状态已 APPROVED 但过程无痕」；
- `operator` 为空直接拒（该列 NOT NULL），不写一条没有主人的审批；
- 读取按 `plan.shopId` 走既有的 `getPlan` 归属校验，不在这张表上另起一套判断；
- 没有留痕的计划显示「留痕从 2026-10-03 才开始写入，之前审批过的计划不会补记录，也不会伪造」，
  且后端返回非数组时前端不补行；DRAFT 计划不给留痕入口；
- 已知残留：`operator` 仍是调用方自报的请求参数，JWT 里的可信用户 id 没有对应列——
  要记就是改表结构，另立项。

重跑清点：`zero_reference=11`。

浏览器侧：采购页此前在 E2E 里一次都没被访问过（连打桩都没有登记），这次一并补上——
`/procurement` 进侧边栏导航用例，另加 3 条：状态机决定行内可用操作（操作人未填时通过/驳回不可点）、
审批请求真的把 `operator`/`comment` 打到 `/procurement/plan/12/approve`（监听真实请求，不看打桩）、
留痕展开与「没有留痕就说不伪造」。采购页四个列表接口也登记了分页数组桩，
不再落到 `EMPTY_PAGE` 对象兜底（那样每个 loader 都会报「没有分页元数据」）。
整包 E2E 67 条全绿。

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
| amz_purchase_approval | procurement `V1__init.sql:220` | 原先审批只改 `amz_purchase_plan.status`，全仓无该表引用 | **已接上**（见上文）：status 只存当前状态，这张表存每次 APPROVE/REJECT 的操作人与意见；`approve()` 同事务写留痕，写不上整体回滚 |

## 为什么本轮一张表都没删

1. 删表在 MySQL 里不可逆，且这里跑着一套共享的演示栈与影子库演练（V7/ad 迁移都做过备份恢复演练），
   不能由「代码没引用」这一个理由推导出「数据可以丢」；
2. Flyway 已执行的迁移不能改写，删表只能是**新增一条正向 `DROP TABLE` 迁移**，那属于单独的立项
   （V 序号 + 备份恢复 + 回滚说明），和 `docs/superpowers/runbooks/reference-key-convergence-rollback.md`
   同级；
3. 上表里真正「可删」的只剩 `amz_logistics_quote` 一张；`amz_purchase_approval` 已按推荐解法
   **补功能消灭零引用**而不是删表，重跑清点为 11 张。

## 本轮未做的验证

- 没有连任何真实 MySQL 统计这些表的行数；「表里有数据但代码不读」这种情况脚本看不出来，
  真要删之前必须先数行（沿用 `tools/db-migration/ad_v7_preflight.py` 的做法）。
- 脚本按「三路引用」判定，不看反射拼表名的动态 SQL；已核对没有这种写法，但若将来出现需要加第四路。

## 追加：接上「海外仓库存与预警」6 个端点（同一轮清点里挑出的第一块待接候选）

清点里 110 条「该接候选」中，`/logistics/warehouse/stock/list`、`alert`(建/列/启停) 、
`alert/check` 这 6 条最干净：页面无需新数据源、闭环清楚（规则 → 立即检查 → 命中清单），
且已有一处会被误读：`alert_type` 只有 5 个取值真的参与判定，其它值在 `evaluateAlert` 落 default，
规则存得进去却永远不触发。新增 `src/api/warehouseAlerts.ts` + `src/views/WarehouseAlerts.vue`
（3 个分区：库存快照 / 预警规则 / 检查结果），路由 `/warehouse-alerts`。

四条写进界面的判读边界：不提供手工录入库存（后端有 `POST /stock`，数量该由海外仓回传，
手填等于造数，刻意不接）；只有 5 类会被判定，其它标「不会被判定」；阈值单位只有字面 `DAYS` 走天数分支，
且 LOW_STOCK/STOCKOUT 选 DAYS 时后端比的是**在库天数 ≤ 阈值**（源码注释自陈是简化反算），不是可售天数；
「立即检查」只读——不发通知（`notify_channels` 只存不读）、不写库，且扫描 500 行上限，
`stocksTruncated=true` 时必须显示结论不是全量。

闸口：新增单测 12 条 + E2E 3 条（整包 71 条，1 条既有导航用例冷启动偶发，单独重跑 13/13 绿）；
`vue-tsc` 0 错；前端 30 文件 / 291 测试；build 出 `WarehouseAlerts` 分块；hygiene 0 finding
（router 重钉 `43fc90cf…`）。变异 4 项：不可判定类型不标注、空 SKU 不再转 null、
立即检查绕过确认、截断不提示 → 全部抓红后复原核对 sha。
