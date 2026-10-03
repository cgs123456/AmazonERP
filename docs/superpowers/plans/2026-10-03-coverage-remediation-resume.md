# 功能覆盖修复方案 · 交接与续做清单（2026-10-03 收线时点）

用途：目标被自动暂停后，下一轮（或换人）从这里接着做，不需要重读整段会话。
所有数字都是本节内实测过的，标注了取数命令。

## 1. 已落地（master；`0865772` 这一条的 CI 未取到结论）

> 收线时点复核：本机 `git status` 干净、master 与 origin 同步到 `0865772`；
> 前面 5 个含代码的提交逐个查过 CI conclusion=success，最后这条只改文档，
> 查它时匿名 API 触发 403 rate limit，**结论未知**。下一轮先看这个提交的红/绿再看别的。

（下表 commit 列即为已确认绿的范围）

| 切片 | commit | 内容 |
| --- | --- | --- |
| 7p | `a58a65e` | 旧商品接口（`ProductMapper` 通路 5 个入口）改为入口 fail-fast；实体-列漂移核对器上线 |
| 7q | `6397c22` | 选品 `trend30d/trend90d` → `trend_30d/trend_90d`，并加实体↔建表列名契约测试 |
| 台账 | `3fda5c7` `0d41f00` | v14 逐条判类 + 同日订正（E 桶误判移回 A 桶） |
| 7s | `b5fb9f1` | Agent 评测面板接线 `POST /ai/eval/run`（39 条里最后一条无外部依赖缺口） |
| 7u | `2187319` | `amz_history.history→keyword`、`amz_order_attribute.label→name` 两处已接线路径的错映射 |
| 7v | `bbb1ba1` `7f695ec` | 删除孤儿旧实体 `product/pojo/Shop` + `ShopMapper`；漂移闸门 `--gate` 接进 CI，并补上「整张表不在 Flyway」这一类 |
| 分母订正 | `04f74f9` | 清点工具不再把属性型 `@PostMapping` 判成解析不出（`unparsed` 1 → 0） |

取数命令（全部本机跑过）：

```
python tools/schema/endpoint_coverage_audit.py --self-test .   # 7/7 PASS
python tools/schema/endpoint_coverage_audit.py . | tail -1     # controllers=60 … user_facing_candidates=39
python tools/schema/entity_column_drift.py --self-test .       # 12/12 PASS
python tools/schema/entity_column_drift.py --gate .            # scanned=102 drifted=2 pending=2 rc=0
python tools/release/repository_hygiene.py --root .            # findings=0
```

CI：`test / hygiene / frontend / runtime-smoke / mysql-import / checkstyle*` 等 11 个作业，
`b5fb9f1`、`2187319`、`bbb1ba1`、`7f695ec`、`04f74f9` 全部 conclusion=success。

## 2. 覆盖计数现状

原始 103 条「后端有、前端无」→ 现在 **39 条**，逐条判定见
`docs/superpowers/evidence/2026-10-03-endpoint-coverage-v14-ledger.md`：

- A 桶 14：必然失败或必然造数（mock 门禁、`UnsupportedOperationException`、恒 `OBSERVE`、
  语义会让页面说谎的 `account/{id}/test` 与 `message/{id}/reply`）——**故意不给按钮**；
- B 桶 20：缺外部凭证（SP-API 12、Keepa 3、模型 key 4 = /ai/agent/memory/chat + /ai/chat + /ai/agent/chat + /ai/review/analyze，以及本轮新计入的 /spapi/uploads 1）；
- C 桶 3：旧商品接口，7p 已收口并指名替代；
- D 桶 2：口径重复；
- E 桶 0：`/ai/eval/run` 已于 7s 接线。

## 3. 未决事项（按「下一步动作」写，不写状态词）

### #52 `amz_replenishment_suggestion` 缺三列 —— 需要一次决策 + 一条迁移
事实：`ReplenishmentSuggestion` 用 `@TableField` 显式映射 `ml_predicted_demand`、
`ml_confidence`、`blend_strategy`；spapi 模块 V1~V9 与 legacy 建表都没有这三列；
`HybridReplenishmentEngine:149-151` 会真写这三列；`ReplenishmentController`、
`SpapiController`、`ReplenishmentScheduler` 都经 `ReplenishmentSuggestionMapper` 读写。
因此真库上任何 `selectList/selectById` 都是 1054（7l 接的补货列表就在这条路上）。

决策只有两个，都需要用户点头，因为它们要么动共享库要么删功能：
1. 新增 `V10__replenishment_ml_columns.sql`（三列 NULL）。**服务下次启动即对共享演示库执行
   `ALTER TABLE`**，所以我没有写也没有跑；批准后要做的是：先备份行数与建表快照，
   在影子库上 Flyway 跑一遍，再决定合并。
2. 或者把三列改成 `@TableField(exist = false)`：读路径立刻通，代价是 ML 预测不再可追溯。
   走这条要把引擎产物的落点改成日志/返回值，并同步改 `HybridReplenishmentEngineTest` 的 6 处断言。

### #54 多平台两个动作的口径 —— 需要产品判断
- `POST /multiplatform/account/{id}/test`：当前只做端点字符串格式校验却改写账号
  `status=ACTIVE/ERROR`。要么真发一次探测再据结果改状态，要么不改状态、只回显结果。
- `POST /multiplatform/message/{messageId}/reply`：只写本地库，平台和买家都收不到。
  要么接真实发送（并像发货那样读平台返回值、失败不写本地状态），要么改名成「内部备注」。
- 独立安全项（不属于覆盖率）：`POST /multiplatform/oauth/token` 的 `appSecret` 是
  `@RequestParam`，密钥会进网关访问日志。改法是挪进请求体，但这是机机接口的对外契约，
  仓库里看不到调用方，改之前得先确认谁在调。

### 20 条 B 桶 —— 需要真凭据才能推进
最小可推进的一步不是接 UI，而是让「缺哪份凭据」在界面上可归因：7o 的连接器自检面板已经
显示凭证状态，剩下的是补 Keepa / DeepSeek / OSS 三家的同类状态位（各有配置项名，不需要猜）。

## 4. 明确不要做的事（本轮踩过的线）

- 不要为了给某条端点凑「已接线」而加会必然失败的按钮：A 桶 14 条的理由都在代码注释里。
- 不要在没读 `PENDING` 语义的情况下放宽漂移闸门：闸门现在只放行两条已登记的漂移，
  登记项一旦与现状不匹配就红（这是防豁免烂掉的机制）。
- 不要相信任何没跑过「变异」的守卫：本轮 4 道新闸（漂移、列名契约、fail-fast、评测页）
  都是靠把缺陷重新注入才确认它真的会红；台账与 7p/7q/7s/7u 证据文档里逐个记了红数。
## 4. 收线时的 CI 事实（V10 落地后）

`4203f36`（V10）与 `a70558b`（内部口径 + 快照重生成）两次 CI 都是 `test` 作业红，其余作业绿；
其中 `synthetic-data` 的红已归因并修好（schema 快照漂移闸门，重新生成后 `--check` 通过）。
`test` 的红**未归因**，证据边界如下，不要当成已解决：

- `mysql-import` 在同一提交上绿：说明 V10 的 SQL 在真实 MySQL 8 上能被裸客户端执行；
- 本机 `mvn -B test -fae` 全绿：但两个 DB 门控 IT（`AllModulesFlywayMySqlIT`、
  `BareSqlBuiltSchemaFlywayStartIT`）在本机因无 MySQL 而跳过，所以本地绿不覆盖它们；
- 更早的 `a58a65e` 也只有 `test` 红、随后 5 个提交全绿：所以「DB 门控 IT 抖动」与
  「V10 让某个 IT 真红」两种解释目前无法区分；
- 作业日志匿名 API 403，annotations 只有「exit code 1」，取不到失败测试名；
- 我本机起临时 MySQL 复现失败：先是 mysql:8.4 不认 `default-authentication-plugin` 直接退出，
  换 8.0 后 root 口令没生效（Access denied）——那次「Communications link failure」
  是我的探针自己死了，不是被测物失败，不能记为 V10 的问题。

下一轮取结论只需一条命令（需带 token 的 gh）：
`gh run view --log-failed --job <test-job-id> <run-id for a70558b> | grep -E "Tests run|ERROR\]" | head`，
或在任意可达的 MySQL 8 上设 `FLYWAY_ALL_IT_*` 后跑那两个 IT。若归因为 V10，
需要同时把三列补进 `docker/init-sql-legacy/09-init-tables-p0-modules.sql`，
让裸 SQL 建库与 Flyway 建库两条部署路径一致（这条目前**没做**，是已知缺口）。
