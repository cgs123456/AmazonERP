# #56 结案：CI `test` 作业的红来自 IT 自己的字典序重放，不是 V10（2026-10-04）

## 结论

**归因确定，本地已修复并复跑证明。**

红的是 `BareSqlBuiltSchemaFlywayStartIT`：它**自己按文件名字典序**重放迁移
（`Files.list(...).sorted()`），而 `V10__…` 排在 `V1__…`/`V2__…` **之前**
（比较到 `V1` 之后一位：`_` = 0x5F > `0` = 0x30）。于是 `V10__replenishment_ml_columns.sql`
里的 `ALTER TABLE amz_replenishment_suggestion` 在 V1 建表之前执行：

```
BareSqlBuiltSchemaFlywayStartIT.flywayStartsCleanlyOnSchemasBuiltWithRawSql:85
  SQLSyntaxError Table 'amz_spapi_bsit.amz_replenishment_suggestion' doesn't exist
```

- **V10 的 SQL 没有错**：同一次运行里走真正 Flyway 的 `AllModulesFlywayMySqlIT`
  全绿（14 库 × 50 迁移，MySQL 8.0.46）。
- **`apply_migrations.py` 的真实执行路径也没有错**：它一直按版本号数值排序
  （`files.append((int(m.group(1)), f)); files.sort()`），所以 synthetic-data 作业是绿的。
  错的只有 IT 那个「复刻版」，外加 dry-run 的打印顺序用了字典序（只误导人，不影响执行）。

也就是说：把 CI 红归到「我加了 V10」是错的因果 —— V10 只是**第一个把这个排序缺陷暴露出来**的输入。

## 复现与验证（用户授权的一次性 MySQL）

第一次尝试（上一轮）失败在我的容器只给了 `root@localhost`，从宿主机连进去被
`Access denied for user 'root'@'172.17.0.1'` 拒了 —— 那是探针自身的前置条件没成立，
不是关于迁移的证据。这次改用显式授权的应用账号：

```
docker run -d --name amz-it-mysql-56 -p 127.0.0.1:3399:3306 mysql:8.0 ...
GRANT ALL PRIVILEGES ON *.* TO 'amzit'@'%'   -- IT 需要 DROP/CREATE 自己的 <db>_fwit/_bsit 库
auth-ok user=amzit@%  ver=8.0.46  ddl-ok
```

| 阶段 | IT_RC | 结果 |
| --- | --- | --- |
| 修复前 | 1 | `AllModulesFlywayMySqlIT` 1/1 绿；`BareSqlBuiltSchemaFlywayStartIT` 报上面那条错 |
| 修复后 | **0** | 两个 IT 各 1/1 绿 + 新增 `MigrationOrderingContractTest` 3/3 绿，BUILD SUCCESS |

跑完即拆：容器已 `docker rm -f`，两个临时口令文件已删除。
未设 `FLYWAY_ALL_IT_URL` 时 `amz-service-spapi -am test` 仍 **MVN_RC=0**（IT 按 env 跳过，
新的顺序测试照常运行）—— 没把 CI 之外的机器弄成需要数据库才能跑测试。

## 修了什么

1. `BareSqlBuiltSchemaFlywayStartIT`：新增 `migrationVersion()` / `sortedByMigrationVersion()`，
   按版本号数值升序、同版本按文件名兜底；`discoverModules()` 不再用 `list.sorted()`。
2. `tools/synthetic-data/apply_migrations.py`：抽出 `migration_files()` 作为**唯一**排序钥匙，
   dry-run 与真实执行共用（此前 dry-run 的字典序打印会让人以为建表顺序是错的）。
3. 新增 `MigrationOrderingContractTest`（**不需要 MySQL**，3 项）：
   - 数值序必须把 V10 排在 V2 之后，同时断言「字典序给出的第一位就是 V10」——
     两种排法必须不同，否则这条断言是空转；
   - 按执行顺序走完每个模块的迁移，任何 `ALTER TABLE x` 引用的表都必须已被前面的语句 CREATE；
   - 每个模块版本号唯一、严格递增、首个必须是 V1（baseline 取最后一个文件，依赖这个约定）。

## 我这次自己踩到的两个坑（都记下来，别当成一次通过）

- **第一版顺序测试把 V2 判成违规**：它先用一个正则扫完整文件的 ALTER、再扫 CREATE，
  而 `V2__spapi_call_outbox.sql` 正是「同文件里先建 `amz_shop_credential` 再 ALTER 它」。
  改成**按出现位置**单趟扫描（CREATE/ALTER 一把抓）后才是真的依赖检查。
  第一版报的那条 violations 是工具的错，不是 DDL 的错。
- **参数名维度量到的是噪声**：顺手写的探针报 8 条「前端传了后端不接收的键」，逐条看全是解析伪影
  （`@RequestParam(defaultValue = "14")` 被当成参数名、`{ ...q, sku }` 把变量名 `q` 当键、
  POST body 的键拿去比 query 参数名）。可靠结论只有一条：**42 个带 params 的调用点，
  42 个都能定位到后端同形状同方法的映射**。参数名/DTO 字段位仍属未量到，需要真正的签名解析才能做。

## 变异验证（三条断言都能红）

| 注入 | 结果 |
| --- | --- |
| `sortedByMigrationVersion` 退回字典序（M-A） | `versionOrderIsNumericNotLexicographic` 红：`expected <[V1, V2, V9, V10]> but was <[V10, V1, V2, V9]>`；`versionsAreUniqueAndStrictlyIncreasing` 红：真实 spapi 目录给出 `[10, 1, 2, …, 99, 9]` |
| 临时放一个 `V99__probe_orphan_alter.sql`（ALTER 一张不存在的表，M-B） | `everyAlterTargetIsCreatedEarlierInExecutionOrder` 红，点名模块与文件 |

两次注入同时跑 → 3 条测试全红；还原后迁移目录回到 10 个文件、`git status` 干净。

## 还不能说的话

CI 是否变绿要等下一次 run 的日志；匿名拉日志是 403，我**没有**读到。
所以这里的结论边界是：**同一输入下的精确复现已被消除**（修前 1 → 修后 0，只差这一处代码），
而不是「CI 已绿」。

## 【2026-10-06 结案升级：run 元数据佐证】

上述边界已可解除：run **#197**（HEAD `89c10f3`）11 个 job **全部 success**——含 `test`
（`BareSqlBuiltSchemaFlywayStartIT` 的常驻执行位）与 `mysql-import`，#56 对应的 CI 红在
远端确认消失。

取证方式更新（本段实测）：job **日志 zip** 仍匿名 403，但 run/job **元数据 API** 匿名可读
（`GET /repos/.../actions/runs`、`/runs/<id>/jobs`），conclusion、失败 job 名、失败 step 名
都拿得到——「是否绿、红在哪个 job/step」已不需要 gh 认证，剩下的细节才靠本地逐字复现。

同期 CI 历史（均与迁移顺序无关，#56 的因果结论不受影响）：#194（`c2993ee`）与 #195
（`3fd1b12`）红在 hygiene `Release tool tests`——Flyway 迁移清点钉数 50 未随 58 个迁移
文件更新（`ce8a360` 修复）；#196（`74e1842`）hygiene 转绿、docker `Dry-run bake` 红——
`buildx bake` 没有 `--dry-run` 标志，正确干跑是 `--print`（`89c10f3` 修复；该步骤此前因
hygiene 恒红、docker 被连带 skip，从未执行过，故一直「假绿」）。
