# P1-3 数据库迁移审计（Flyway）— 证据文档

- 日期：2026-09-30
- 仓库：`C:\Users\Administrator\Desktop\AmazonERP`，分支 `master`，基线提交 `ee00bef`
- 范围：P1-3（数据库迁移审计）。本轮**未修改任何代码/迁移脚本**，只做只读审计 + 隔离演练 + 取证。
- 状态：**审计动作已全部执行完毕**；产出的 4 项风险需要用户决策（见第 9 节），不属于本轮自动修复范围。

---

## 1. 方法与演练环境

| 项 | 值 |
| --- | --- |
| 演练 MySQL | `mysql:8.0` 容器 `amz-p13-drill2`，`SELECT VERSION()` = **8.0.46**，参数 `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --skip-name-resolve --max-connections=500 --max-connect-errors=100000` |
| 演练库 | 由 `docker/init-sql/01-init-databases.sql` 建出 14 个空库（与生产初始化同源） |
| Flyway 版本 | **10.20.0**（与根 pom `flyway.version` 一致），通过仓库自带 IT 使用，未引入第三方 CLI |
| 执行位置 | 容器 `amz-p13-runner`（`maven:3.9-eclipse-temurin-17`）挂在 docker 网络 `p13net` 上，JDBC 直连容器 IP `172.20.0.3:3306` |
| 触发命令 | `docker exec -e FLYWAY_ALL_IT_URL='jdbc:mysql://172.20.0.3:3306/amz_fwit?...' ... mvn -o -B -ntp test -pl amz-service/amz-service-spapi "-Dtest=AllModulesFlywayMySqlIT,BareSqlBuiltSchemaFlywayStartIT" -DfailIfNoSpecifiedTests=false` |

**为什么从容器里跑（重要，避免误判）**：在 Windows 宿主上经 `127.0.0.1:<port>` 端口代理连 MySQL 时，连接**间歇性超时**。
实测探针（40 次顺序连接）：首次连接耗时 **15461 ms**，其后 39 次全部成功且极快（`fail=0`）。
即使把 JDBC URL 加上 `connectTimeout=120000&socketTimeout=600000` 仍会失败（`SQL State 08S01`），
说明这是 **Docker Desktop 宿主端口代理的环境问题，不是仓库缺陷**。绕开宿主端口代理（容器内网直连）后两个 IT 一次跑全绿。
→ 结论：先前 6 次红灯归因于环境，不应记到仓库账上。

---

## 2. 迁移清单（VERIFIED）

- `amz-service` 下 **15 个服务模块**，其中 **14 个** 带 `src/main/resources/db/migration`；
  `amz-service-message` 无迁移目录且 `application*.yml` 中**没有** `jdbc:mysql` 数据源（无库，符合预期）；
  根级 `amz-gateway` 同样无数据源、无迁移目录。**不存在“有库却没迁移”的服务。**
- 迁移文件总数 **49**，按模块：ad 7、ai 2、customer 1、finance 7、logistics 5、multiplatform 3、ops 1、order 5、procurement 2、product 4、report 1、search 1、spapi 9、user 1。
- 每个模块版本号 **从 1 连续到 N**，**无重复、无缺口**（脚本校验 `vs == range(1, n+1)`，全部通过）。
- `CREATE TABLE` 共 **113 处，全部带 `IF NOT EXISTS`**（不带的数量 = **0**）→ 建表语句本身幂等。

---

## 3. 静态审计：非幂等 / 破坏性语句（VERIFIED，脚本统计 + 人工读原文）

修正说明：早期一版扫描把 `ON DUPLICATE KEY UPDATE` 误计为 UPDATE DML（64 处），已排除。真值如下：

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| `ADD COLUMN` 不带 `IF NOT EXISTS` | 16 | MySQL 8.0 本身不支持该语法，重放会报 1060 |
| `ADD INDEX` | 7 | 重放会报 1061 Duplicate key name |
| `CREATE INDEX` | 9 | 不带 `IF NOT EXISTS`，重放即失败 |
| `DROP INDEX/COLUMN` 不带 `IF EXISTS` | 2 | 不可重入，见 order/V4 |
| `ON DUPLICATE KEY UPDATE` | 4 | 数据写入类 |
| `INSERT` | 16 | 多为 V1 种子数据 |
| `MODIFY COLUMN` | 12 | 改列类型 |
| 真 `UPDATE`（数据改写） | 6 | 见 ad/V7、product/V3、user/V1 |
| `CHANGE/RENAME COLUMN` | 4 | 见 order/V2、finance/V7、multiplatform/V3 |
| 多表 `DELETE ... FROM`（去重删数据） | 4 | **ad/V2 ×1、ad/V7 ×3** |

逐条高风险脚本（已读原文，非推测）：

1. **`amz-service-ad/.../V7__ad_business_uniqueness.sql`（全仓最危险）**
   先 `UPDATE amz_ad_keyword SET keyword = LOWER(TRIM(keyword)), match_type = UPPER(TRIM(COALESCE(NULLIF(match_type,''),'EXACT')))` **改写业务数据**（空 match_type 会被填成 `EXACT`），
   再用 3 处 `DELETE older FROM ...` **删除重复行**，最后 `ADD UNIQUE KEY`。
   迁移内删数据 → 上线前必须备份 + 按 `docs/.../ad-business-uniqueness-migration.md` runbook 做 preflight。
2. **`amz-service-ad/.../V2__campaign_metadata_unique.sql`**：1 处多表 `DELETE older FROM` + 加唯一键（第 11 行 `ADD UNIQUE KEY uk_shop_campaign`）。
3. **`amz-service-order/.../V4__order_shop_scoped_identity.sql`**（第 36-38 行）：
   `DROP INDEX uk_amazon_order, DROP INDEX idx_shop, ADD UNIQUE KEY uk_shop_market_order (...)`，`DROP INDEX` **不带 IF EXISTS**，是整个仓库仅有的 2 处不可重入 DROP。
4. **`amz-service-order/.../V2__order_reference_key_types.sql`**：`MODIFY COLUMN` 加宽 BIGINT + `CHANGE COLUMN order_id → amazon_order_id` + `RENAME INDEX`（MySQL 8.0 专有语法）。
5. **`amz-service-finance/.../V7__order_number_column_rename.sql`**：`CHANGE COLUMN` ×2 + `RENAME INDEX` ×2。
6. **`amz-service-multiplatform/.../V3__platform_message_order_no.sql`**：列重命名 ×1。
7. **`amz-service-user/.../V1__init.sql`**：`UPDATE` ×1 + `INSERT` ×2（种子数据）。
8. **`amz-service-product/.../V3__listing_copy_polling.sql`**：`UPDATE` ×1 + `CREATE INDEX` ×1。

---

## 4. CI 现状（VERIFIED，读 `.github/workflows/ci.yml` + 根 `pom.xml`）

- 根 pom surefire 显式 `<include>**/*IT.java</include>`（第 107 行，注释第 90-94 行写明“本仓不引入 failsafe”）→ `*IT.java` 在 `test` 阶段跑。
- `test` job 起 `mysql:8.0` service（3306），并设置 `AD_MYSQL_IT_*` 与 **`FLYWAY_ALL_IT_URL/USER/PASSWORD`（ci.yml 第 142-144 行）** → 两个 Flyway IT **在 CI 常驻执行**，不是只在本地能跑。
- `mysql-import` job（第 220 行）：起 `mysql:8.0`，执行 `python apply_migrations.py --host 127.0.0.1 --port 3306 --user root`（第 245 行）→ **用 mysql 客户端裸 SQL 依次执行 49 个迁移，完全不经过 Flyway**，因此建出的库没有 `flyway_schema_history`。
- `tools/synthetic-data/apply_migrations.py` 的 docstring 自己写明：14 个服务都配了 `baseline-on-migrate: true` + `baseline-version: 1`，裸 SQL 建库后服务启动会在 v1 打基线并重放 V2..Vn → 撞 Duplicate key name 启动失败；
  规避手段是 `ensure_flyway_baseline` 在**模块最高版本**插一条 baseline 行。
  → **现有 harness 从未演练过“采纳已有库 + 打 v1 基线”这条升级路径，它用 max-version baseline 绕过去了。**

两个 IT 的职责：
- `AllModulesFlywayMySqlIT`：空库路径守卫（Flyway 真跑 14 库 × 49 迁移；断言执行数==文件数、history 行数==文件数、0 失败行、`appliedTotal>=49`）。
- `BareSqlBuiltSchemaFlywayStartIT`：被采纳库路径守卫（裸 SQL 建库 + max-version baseline 后 Flyway 干净启动 `applied=0`），并做源码级断言（`apply_migrations.py` 必须仍含 `ensure_flyway_baseline` 与 `<< Flyway Baseline >>`）。

---

## 5. 活环境取证（`amz-mysql`，只读查询，2026-09-30）

| 库 | 表数 | `flyway_schema_history` 情况 |
| --- | --- | --- |
| amz_spapi | 14 | 9 行 v1..v9 全部 `SQL/success=1` |
| amz_order | 12 | 5 行 v1..v5 全部 SQL |
| amz_ops | 7 | **仅 1 行：`1:1:BASELINE:1`** |
| amz_report | 7 | 1 行 v1 SQL |
| amz_user | 7 | 1 行 v1 SQL |
| amz_finance | 6 | 7 行 v1..v7 全部 SQL |
| amz_ad / ai / customer / logistics / multiplatform / procurement / product / search | **0 表** | **无 history 表** |

关键结论（对先前交接文档的**修正**）：
- 那 8 个库不是“被裸 SQL 预建的有表库”，而是**完全空库**（0 表）。当前也没有这 8 个服务的容器在跑。
  它们一旦启动走的是**空库路径**（第 6 节已验证全绿），**不存在**“启动即撞 Duplicate key”的风险。先前交接里的这个推断不成立。
- 真正的活样本是 **`amz_ops`**：库里有 7 张表（其中 5 张与 `ops/V1__init.sql` 建表清单完全一致，另有 `amz_synthetic_dataset_registry`），
  但 history 里只有一条 `BASELINE` at v1 → **Flyway 在 v1 打基线，从未执行 `V1__init.sql`**，表是 `apply_migrations.py` 裸 SQL 建的。
  这是“`baseline-on-migrate` 会掩盖 V1 缺失”的活证据，也是第 6 节 Path B 在真实环境的对应物。
- 活的 `amz_ops.amz_keyword_rank` 列是 `rank_position`（与迁移一致），**不是** legacy 脚本里的 `rank` → 说明该库不是由 `init-sql-legacy` 建的。

---

## 6. 演练结果

### Path A：空库 + 真实 Flyway（VERIFIED 绿）
`AllModulesFlywayMySqlIT` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.97 s`，日志中 **14 条** `Successfully applied ... migration`。
14 个库 × 49 个迁移在 Flyway 10.20.0 + MySQL 8.0.46 下全部通过（仅有 MySQL 8 的 `Integer display width is deprecated` 1681 警告，无害）。

### Path B：裸 SQL 建库 + max-version baseline（VERIFIED 绿）
`BareSqlBuiltSchemaFlywayStartIT` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 19.90 s`；
日志逐库 `Schema ... is up to date. No migration necessary.`，总 `Tests run: 2, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

### 核心风险命题：naive baseline@1 会炸（VERIFIED 复现）
用服务**真实配置**（`baselineOnMigrate=true` + `baselineVersion=1` + `cleanDisabled=true`）对“裸 SQL 已建好的库”执行 `migrate()`：

| 模块 | 结果 | 报错原文 |
| --- | --- | --- |
| `amz-service-ad`（7 个迁移） | **启动失败** | `Script V2__campaign_metadata_unique.sql failed` / `SQL State 42000` / `Error Code 1061` / `Duplicate key name 'uk_shop_campaign'`（V2 第 11 行） |
| `amz-service-order`（5） | **启动失败** | `V2__order_reference_key_types.sql` / `42S22` / `1054` / `Unknown column 'order_id' in 'amz_shipment_routing'`（第 24 行） |
| `amz-service-spapi`（9） | **启动失败** | `V3__spapi_outbox_replay_metadata.sql` / `42S21` / `1060` / `Duplicate column name 'expected_statuses'`（第 3 行） |

对照（同一次运行、同一套脚本）：把 `baselineVersion` 换成**模块最大版本**（ad=7 / order=5 / spapi=9）→
`Successfully baselined schema with version: N` → `Schema ... is up to date. No migration necessary.` → `appliedMigrations=0, success=true` → **服务能起来**。

→ 这解释了为什么生产（空库起步）安全、为什么合成数据环境靠 `apply_migrations.py` 的 max-version baseline 兜住，
也说明 **“拿一个已有数据的库直接起服务”是一定会炸的**，炸点在各模块第一个非幂等迁移（ad=V2、order=V2、spapi=V3）。

### 跨版本漂移（VERIFIED 无）
`v0.1.0 / v0.1.1 / v0.1.2 / v0.1.3 / HEAD` 五个点，**每个点都是 49 个迁移文件**；
`git diff v0.1.0 HEAD -- '*/db/migration/*'` **无任何改动**；
逐文件 blob 哈希比对：**49 个 blob 在 v0.1.0 与 HEAD 完全相同**（`identical-set=True`）。
→ 发布史上**不存在增量升级场景，也不存在已发布迁移被事后修改的 checksum drift**。

---

## 7. `docker/init-sql-legacy` 与迁移的 diff（VERIFIED）

- 该目录 **31 个** `.sql`（先前交接写的 33 需更正），并且 **不在初始化路径**：
  `docker-compose.yml` 只挂载 `./docker/init-sql/01-init-databases.sql`（第 41 行）。
- 全量比对（105 张 legacy 表 vs 113 张迁移表）：
  - **legacy 独有表：0** → legacy 建的表，迁移里全都建了。
  - **迁移独有表：8**（`amz_agent_eval_log`、`amz_feed_result_error`、`amz_order_item`、`amz_shop_credential`、`amz_spapi_call_outbox`、`amz_spapi_notification_destination`、`amz_spapi_notification_inbox`、`amz_spapi_notification_subscription`）。
  - **105 张共有表中 11 张有列差异**，其中 4 张是 legacy 有、迁移没有的列：
    `amz_keyword_rank.rank`（迁移叫 `rank_position`，**列名不同，属真不兼容**）、`amz_shipment.data_source/last_track_time`、`amz_tracking_event.raw_status/source`、`amz_unified_order.items_json`。
  - 其余 7 张是迁移比 legacy 多列（`amz_user.role`、`amz_order.shop_id`、`amz_ad_campaign_ext.impressions` 等），属迁移演进，无害。
- **2 个 legacy 文件含 MariaDB 专有语法、在 MySQL 8.0 上必然报错**：
  `19-init-tables-field-permission.sql:44` 与 `23-init-tables-procurement-upgrade.sql:133-138` 使用 `ADD COLUMN IF NOT EXISTS`
  （这两个文件自己的注释就写明“MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，执行会直接报 ERROR 1064 并中止整个脚本”）。
  → 结论：legacy 目录**不能**在 MySQL 8.0 上重放，只能当归档参考；这进一步支持“不要用它建库”。

---

## 8. 本轮未完成 / 未做的事（诚实清单）

- 未修改任何迁移脚本、未给任何 IT 加重试逻辑（宿主端口代理的抖动是环境问题，不在仓库里打补丁）。
- 未做“升级路径”的真实数据演练（因为发布史上不存在跨版本迁移，见第 6 节；也拿不到历史生产库快照）。
- `FlywayBaselineContractTest.java` 未逐行审读（无环境依赖的契约测试，不影响本轮结论）。
- 未对 6 个在跑的业务容器做任何写操作；`amz-mysql` 只做了 `information_schema` / `flyway_schema_history` 只读查询。

## 9. 需要你决策的 4 项风险（新工作，不在 P1-3 自动修复范围）

1. **ad/V7 迁移内删数据 + 改写数据**（空 `match_type` 会被填成 `EXACT`）：上线前是否强制备份 + preflight？要不要我按 runbook 写一个可执行的上线检查脚本？
2. **order/V4 的 `DROP INDEX` 不带 `IF EXISTS`**：这是全仓唯一不可重入的 DROP。是否改成条件化（先查 `information_schema` 再 DROP）？
   ——注意：改已发布迁移会引入 checksum drift，而目前 49 个 blob 全历史一致，改动需连带处理 `baselineVersion` 或 `flyway repair`。
3. **“已有库直接起服务”必炸（第 6 节已复现）**：要不要在部署文档/启动检查里显式禁止，或让服务在检测到“非空库且无 history”时**直接 fail-fast 并给出明确提示**，而不是报一个含糊的 Duplicate key？
4. **`init-sql-legacy` 31 个脚本仍是 MySQL 8.0 不可执行的定时炸弹**（含 MariaDB 语法）：是否删除或移出仓库 / 加 CI 门禁禁止其被执行？

---

## 10. 复现命令（供复核）

```powershell
# 演练容器 + 网络
docker run -d --name amz-p13-drill2 -e MYSQL_ROOT_PASSWORD=P13drill_pw -p 3400:3306 mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --skip-name-resolve
docker network create p13net; docker network connect p13net amz-p13-drill2
Get-Content docker/init-sql/01-init-databases.sql -Raw | docker exec -i amz-p13-drill2 mysql -uroot -pP13drill_pw

# 从容器内网跑两个 IT（绕开宿主端口代理）
docker run -d --name amz-p13-runner --network p13net \
  -v "C:\Users\Administrator\Desktop\AmazonERP:/repo" -v "C:\Users\Administrator\.m2:/root/.m2" \
  maven:3.9-eclipse-temurin-17 sleep infinity
docker exec -e FLYWAY_ALL_IT_URL='jdbc:mysql://172.20.0.3:3306/amz_fwit?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -e FLYWAY_ALL_IT_USER=root -e FLYWAY_ALL_IT_PASSWORD=P13drill_pw amz-p13-runner \
  bash -lc 'cd /repo && mvn -o -B -ntp test -pl amz-service/amz-service-spapi "-Dtest=AllModulesFlywayMySqlIT,BareSqlBuiltSchemaFlywayStartIT" -DfailIfNoSpecifiedTests=false'
```

辅助脚本与原始日志（本机）：`%TEMP%\mig_summary.py`、`%TEMP%\mig_scan2.py`、`%TEMP%\mig_del.py`、`%TEMP%\legacy_diff2.py`（输出 `%TEMP%\legacy_diff2.txt`、`legacy_diff.json`）、`%TEMP%\J5.java`（连接探针）、`%TEMP%\NaiveBaseline.java`（baseline 实验）、日志 `%TEMP%\p13-ctr2.log`、`%TEMP%\p13-naive-ad.log`、`%TEMP%\p13-naive-order-spapi.log`。
