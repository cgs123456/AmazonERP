# 广告业务唯一键迁移：预检、执行与回滚 Runbook

## 1. 适用范围

本 Runbook 对应 `amz-service-ad` 的
`V7__ad_business_uniqueness.sql`，目标是在 MySQL 8 上把广告搜索词、关键词和
ASIN 反查从“应用层假设唯一”收敛为“数据库约束保证唯一”。

V7 建立三组业务唯一键：

| 表 | 唯一键 | 业务含义 |
|---|---|---|
| `amz_ad_keyword` | `uk_ad_keyword_shop_campaign_keyword_match (shop_id, campaign_id, keyword, match_type)` | 同一店铺、同一广告活动、同一关键词和匹配类型只有一条记录 |
| `amz_ad_converting_terms` | `uk_ad_converting_shop_campaign_term (shop_id, campaign_id, search_term)` | 同一店铺、活动、搜索词只有一条出单聚合记录 |
| `amz_ad_asin_keyword` | `uk_ad_asin_shop_asin_keyword (shop_id, asin, keyword)` | 同一店铺、ASIN、关键词只有一条反查快照 |

V7 还会执行以下规范化：

- `keyword`、`search_term` 转为小写并去除首尾空白。
- `asin` 转为大写并去除首尾空白；空字符串转为 `NULL`。
- `match_type` 转为大写并去除首尾空白；`NULL` 或空值默认为 `EXACT`。
- `amz_ad_converting_terms.campaign_id` 的 `NULL` 转为 `''`，最终改为
  `NOT NULL DEFAULT ''`。
- 重复行保留最小 `id` 作为 canonical row；统计快照从最大 `id` 合并到该行；
  随后删除较旧的重复行。

> 关键边界：这里是**破坏性数据归并**，不是单纯加索引。重复行的旧值不一定能恢复，
> 有真实数据后只能通过迁移前备份恢复，不能依赖反向 SQL 重建已删除记录。

## 2. 不可跳过的前提

1. 目标库必须是 MySQL 8.0，且已成功执行 V1-V6。
2. 必须在维护窗口或停止广告同步、搜索词聚合、ASIN 反查写入后执行。
3. 必须先完成第 3 节预检。任一 STOP 条件命中时，不得直接执行 V7。
4. 必须完成第 4 节逻辑备份，并在独立临时库验证备份可恢复。
5. 必须先记录迁移前行数、唯一键分组数和表 checksum。
6. 生产变更需有回滚批准人和业务负责人；确认可以接受“重复行合并后旧记录消失”。
7. 首次上线当前仍无真实广告数据时，也应保留本记录，用合成数据完成同流程演练。

## 3. 迁移前预检

> **这一节已经有可执行版本**：`tools/db-migration/ad_v7_preflight.py`。
> 它把下面的 SQL 全部跑一遍并**自己给出退出码**：`0`=未命中 STOP（可进入备份与执行）、
> `2`=命中 STOP（禁止执行 V7）、`1`=脚本自身出错（同样禁止）。只读，不执行 V7、不改数据。
> 用法与证据见 `docs/superpowers/evidence/2026-09-30-p1-3-risk1-ad-v7-preflight.md`。
> 下面的 SQL 保留为**人工复核与理解语义**用；发布时以脚本的退出码为准。

### 3.1 重复业务键数量

```sql
SELECT 'keyword' AS dataset, COUNT(*) AS duplicate_rows_removed
  FROM (
    SELECT shop_id, campaign_id, LOWER(TRIM(keyword)) AS keyword,
           UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT'))) AS match_type
      FROM amz_ad_keyword
     GROUP BY shop_id, campaign_id, LOWER(TRIM(keyword)),
              UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT')))
    HAVING COUNT(*) > 1
  ) d
UNION ALL
SELECT 'converting_term', COUNT(*) FROM (
    SELECT shop_id, TRIM(COALESCE(campaign_id, '')) AS campaign_id,
           LOWER(TRIM(search_term)) AS search_term
      FROM amz_ad_converting_terms
     GROUP BY shop_id, TRIM(COALESCE(campaign_id, '')), LOWER(TRIM(search_term))
    HAVING COUNT(*) > 1
) d
UNION ALL
SELECT 'asin_keyword', COUNT(*) FROM (
    SELECT shop_id, UPPER(TRIM(asin)) AS asin, LOWER(TRIM(keyword)) AS keyword
      FROM amz_ad_asin_keyword
     GROUP BY shop_id, UPPER(TRIM(asin)), LOWER(TRIM(keyword))
    HAVING COUNT(*) > 1
) d;
```

`duplicate_rows_removed` 表示将有二义性地归并的重复组数，不等于删除行数。
需要精确评估删除量时，对每组使用 `COUNT(*) - 1` 求和：

```sql
SELECT 'keyword' AS dataset, COALESCE(SUM(cnt - 1), 0) AS rows_physically_deleted
  FROM (
    SELECT COUNT(*) AS cnt
      FROM amz_ad_keyword
     GROUP BY shop_id, campaign_id, LOWER(TRIM(keyword)),
              UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT')))
    HAVING COUNT(*) > 1
  ) d
UNION ALL
SELECT 'converting_term', COALESCE(SUM(cnt - 1), 0) FROM (
    SELECT COUNT(*) AS cnt
      FROM amz_ad_converting_terms
     GROUP BY shop_id, TRIM(COALESCE(campaign_id, '')), LOWER(TRIM(search_term))
    HAVING COUNT(*) > 1
) d
UNION ALL
SELECT 'asin_keyword', COALESCE(SUM(cnt - 1), 0) FROM (
    SELECT COUNT(*) AS cnt
      FROM amz_ad_asin_keyword
     GROUP BY shop_id, UPPER(TRIM(asin)), LOWER(TRIM(keyword))
    HAVING COUNT(*) > 1
) d;
```

### 3.2 立即停止迁移的 STOP 条件

- `amz_ad_keyword.keyword` 或 `amz_ad_converting_terms.search_term` 规范化后为空。
- `amz_ad_asin_keyword.asin` 或 `keyword` 规范化后为空。
- `amz_ad_converting_terms.campaign_id` 同时存在 `NULL`、空白和非空白值的同键重复行，
  使“空活动”与真实活动语义不清。
- 同一 converting-term 重复组内 `status` 或 `is_added_to_keyword` 冲突。
  V7 保留最小 `id` 的人工维护值，不会自动判断哪个值正确。
- 同一 keyword 重复组内 `bid`、`base_bid`、`state` 不存在明确“最新责任行”。
- 同一 ASIN-keyword 重复组内 `last_checked` 为空或时间不唯一，无法判断最新快照。
- 业务方无法确认最大 `id` 一定晚于最小 `id`；若数据通过批量导入生成，`id` 顺序可能不代表业务时间。

两条来自演练（2026-10-02，见 `evidence/2026-10-02-p1-3-risk1-v7-backup-restore-drill.md`）的读法约束：

- **`将物理删除约 N 行` 的口径已与 V7 对齐。** 预检原先漏了 V7 第一步对
  `amz_ad_keyword.campaign_id` 的 `TRIM`，带首尾空格的 campaign 会被拆开、删除量被低估
  （同一份夹具实测：预估 1 行，V7 实删 2 行）。2026-10-02 已修 `KW_KEY`，并由
  `test_group_keys_match_v7_step1_normalizations` 从迁移 SQL 现读第一步的规范化赋值、
  逐列钉住"预检键必须与 V7 一致"；修后同一夹具预估 2 行 = 实删 2 行。
- **删多少与"谁是最新"无关，但留下什么有关。** 组内删 `COUNT(*)-1` 行只取决于分组，
  而 `MAX(id)` 选出的那一行是否业务上的最新快照，只影响保留下来的字段值。
  批量导入的数据里 `id` 顺序未必代表业务时间（见上面 STOP 清单最后一条），这只能由业务方确认。
- **规范化只折首尾空白，不折内部空白。** `'yoga  mat'`（双空格）与 `'yoga mat'` 在 V7 之后
  仍是两条不同记录，唯一键不会合并它们。若运营预期“多余空格会被自动合并”，需先清理数据再迁移。

人工状态冲突查询：

```sql
SELECT shop_id, TRIM(COALESCE(campaign_id, '')) AS campaign_id,
       LOWER(TRIM(search_term)) AS search_term,
       COUNT(*) AS row_count,
       COUNT(DISTINCT status) AS status_count,
       COUNT(DISTINCT is_added_to_keyword) AS added_flag_count,
       GROUP_CONCAT(CONCAT(id, ':', status, ':', is_added_to_keyword)
                    ORDER BY id SEPARATOR ',') AS rows_detail
  FROM amz_ad_converting_terms
 GROUP BY shop_id, TRIM(COALESCE(campaign_id, '')), LOWER(TRIM(search_term))
HAVING COUNT(DISTINCT status) > 1
    OR COUNT(DISTINCT is_added_to_keyword) > 1;
```

若该查询返回任何行，先由运营确认保留值，修正 canonical row（当前定义为最小 `id`），
再重新预检。不得仅凭 V7 的“保留 keeper”规则处理冲突状态。

### 3.3 迁移前基线

```sql
SELECT 'amz_ad_keyword' AS table_name, COUNT(*) AS row_count FROM amz_ad_keyword
UNION ALL
SELECT 'amz_ad_converting_terms', COUNT(*) FROM amz_ad_converting_terms
UNION ALL
SELECT 'amz_ad_asin_keyword', COUNT(*) FROM amz_ad_asin_keyword;

CHECKSUM TABLE amz_ad_keyword, amz_ad_converting_terms, amz_ad_asin_keyword;
```

将预检时间、库名、实例地址、Flyway 版本、执行人、审批人和结果保存到变更单。
不要保存数据库密码或 PII。

## 4. 备份与恢复演练

1. 停止所有广告域写入，或把服务切到只读；确认没有在途批量任务。
2. 记录最大 `id`、`update_time`，用于确认冻结点。
3. 获取表结构快照：

```sql
SHOW CREATE TABLE amz_ad_keyword;
SHOW CREATE TABLE amz_ad_converting_terms;
SHOW CREATE TABLE amz_ad_asin_keyword;
```

4. 至少执行逻辑备份。示例：

```bash
mysqldump --single-transaction --routines --triggers \
  --set-gtid-purged=OFF \
  --databases amz_ad > amz_ad_before_v7.sql
```

5. 在独立临时库恢复该备份，运行 `CHECKSUM TABLE` 并与源库冻结点对比。
6. 只有恢复演练通过后，才允许执行 Flyway。
7. 备份文件应加密保存，并按公司数据保留策略限制访问；广告表可能包含搜索词等经营数据。

## 5. 执行 V7

由 flyway 按版本执行，禁止手工拆分 SQL 后“选择性执行”。推荐方式：

```bash
flyway -url="$AMZ_AD_DB_URL" -user="$AMZ_AD_DB_USER" \
  -locations=filesystem:amz-service/amz-service-ad/src/main/resources/db/migration \
  -target=7 migrate
```

若由 Spring Boot 启动时自动迁移，必须先确认应用版本固定为包含 V7 的构建，
并防止多个实例并发启动迁移。迁移期间监控：

- MySQL `metadata_lock`、`data_lock` 等待和活跃会话。
- 三张表的行数与 delete 速率。
- InnoDB undo/log 增长、复制延迟。
- 应用错误率；迁移窗口内应保持写入关闭。

执行后立即记录：

```sql
SELECT installed_rank, version, description, success, execution_time
  FROM flyway_schema_history
 WHERE version IN ('6', '7')
 ORDER BY installed_rank;
```

`success = 0` 或 V7 未出现在历史表中，均视为迁移失败。

## 6. 迁移后验证

### 6.1 唯一索引存在且列数正确

```sql
SELECT table_name, index_name, non_unique, seq_in_index, column_name
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN (
     'uk_ad_keyword_shop_campaign_keyword_match',
     'uk_ad_converting_shop_campaign_term',
     'uk_ad_asin_shop_asin_keyword'
   )
 ORDER BY table_name, index_name, seq_in_index;
```

预期：

- `amz_ad_keyword`：4 列，`non_unique = 0`。
- `amz_ad_converting_terms`：3 列，`non_unique = 0`。
- `amz_ad_asin_keyword`：3 列，`non_unique = 0`。

### 6.2 规范化与 NOT NULL

```sql
SELECT COUNT(*) AS bad_keyword
  FROM amz_ad_keyword
 WHERE keyword <> LOWER(TRIM(keyword))
    OR match_type <> UPPER(TRIM(match_type))
    OR match_type IS NULL
    OR TRIM(keyword) = '';

SELECT COUNT(*) AS bad_converting_term
  FROM amz_ad_converting_terms
 WHERE search_term <> LOWER(TRIM(search_term))
    OR campaign_id IS NULL
    OR campaign_id <> TRIM(campaign_id)
    OR TRIM(search_term) = '';

SELECT COUNT(*) AS bad_asin_keyword
  FROM amz_ad_asin_keyword
 WHERE asin <> UPPER(TRIM(asin))
    OR keyword <> LOWER(TRIM(keyword))
    OR TRIM(asin) = ''
    OR TRIM(keyword) = '';
```

三个结果必须均为 `0`。

### 6.3 重复键残留

重跑第 3.1 节查询；所有业务键分组不得再有 `HAVING COUNT(*) > 1` 结果。

### 6.4 行数与 checksum 比较

```sql
SELECT 'amz_ad_keyword' AS table_name, COUNT(*) AS row_count FROM amz_ad_keyword
UNION ALL
SELECT 'amz_ad_converting_terms', COUNT(*) FROM amz_ad_converting_terms
UNION ALL
SELECT 'amz_ad_asin_keyword', COUNT(*) FROM amz_ad_asin_keyword;

CHECKSUM TABLE amz_ad_keyword, amz_ad_converting_terms, amz_ad_asin_keyword;
```

预期行数变化：

- keyword 行数 = 迁移前行数 - 该表删除的重复行数。
- converting-term 行数 = 迁移前行数 - 该表删除的重复行数。
- ASIN-keyword 行数 = 迁移前行数 - 该表删除的重复行数。

checksum 必然因规范化、合并和删除发生变化；不能把“checksum 不同”当作失败，
需要与预检得到的预期删除量核对。若实际减少量大于预检值，立即停止应用上线并进入恢复评估。

### 6.5 抽样保留语义

对每组历史重复键，抽查 canonical row 是否满足：

- keyword：`bid/base_bid/state` 来自最大 `id` 的新快照。
- converting-term：`asin/统计字段/first_seen/last_seen` 来自最大 `id`，而
  `status/is_added_to_keyword` 保留 canonical row 的人工值。
- ASIN-keyword：排名、搜索量、索引状态、检查日期来自最大 `id`。

抽样数量和样本 ID 由数据量决定；零数据窗口可记录为“无真实重复组”。

### 6.6 应用级验收

1. 启动 `amz-service-ad`，确认 Flyway 不再尝试执行 V7。
2. 调用搜索词同步两次，确认 converting-term 不产生重复，并保留人工状态。
3. 并发调用关键词基准价认领，确认只出现一行且不会覆盖已认领 `base_bid`。
4. 调用 ASIN 关键词 upsert，确认同店同键更新、跨店不串数据。
5. 执行广告模块回归测试：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
mvn -o -pl amz-service/amz-service-ad test
```

6. 如提供真实 MySQL 8 测试库，显式执行集成测试：

```powershell
$env:AD_MYSQL_IT_URL='jdbc:mysql://127.0.0.1:3306/amz_ad_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:AD_MYSQL_IT_USER='root'
$env:AD_MYSQL_IT_PASSWORD='<secret>'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
mvn -o -pl amz-service/amz-service-ad '-Dtest=AdMigrationMySqlIT' test
```

集成测试应使用专用空数据库；脚本假设从 V1 开始迁移，不能对生产库执行。

## 7. 回滚与失败处理

### 7.1 没有通用反向 SQL

V7 删除重复行并把多行合并为一行。删除后 V7 不再保留被删行的完整副本，
因此不能通过“DROP INDEX”恢复数据。以下操作都不是数据回滚：

- 删除新唯一索引。
- 把 `campaign_id` 改回 nullable。
- 把 keyword/search term 改回原大小写或空格。

失败恢复必须使用迁移前备份，或使用经过业务批准的补偿数据重建。若生产已恢复写入，
不得直接整库恢复覆盖新数据；应把备份恢复到临时库，按业务键做差异修复，并由业务确认冲突。

### 7.2 部分执行失败

MySQL 8 的单条 DDL 具有原子性，但 V7 文件包含多条 DML 和 DDL，Flyway 会按语句依次执行。
某条语句失败时，之前的规范化、合并和删除可能已经提交。处理步骤：

1. 立即停止应用写入，保留现场，不要重复手工执行整个 V7。
2. 查询 `flyway_schema_history` 和实际索引、行数，确定失败位置。
3. 从迁移前备份恢复到隔离库。
4. 按 Flyway 校验和修复已有历史，或使用经过评审的前向修复迁移。
5. 只有在数据核对完成后才恢复写入。

### 7.3 索引已建立但应用回退

如果 V7 成功、数据已归并，而应用需回退版本：

- 不要删除唯一索引；旧应用通常仍可读写，唯一索引会阻止旧竞态再次制造重复。
- 如果旧应用对该表写入格式与规范化约束冲突，必须修复旧应用或继续前滚。
- 保留 V7 数据与索引，避免出现“代码回退成功、数据继续分裂”的双状态。

## 8. 变更记录模板

| 项目 | 内容 |
|---|---|
| 环境 / 实例 | |
| 数据库 | `amz_ad` |
| 冻结时间 | |
| 备份文件与恢复验证 | |
| 预检重复组数 | keyword: ___ / converting: ___ / asin: ___ |
| 冲突人工值 | 无 / 已修复，证据：___ |
| Flyway 执行时间 | |
| 迁移后行数变化 | keyword: ___ / converting: ___ / asin: ___ |
| 唯一索引核验 | pass / fail |
| 应用验收 | pass / fail |
| 审批人 / 执行人 | |
| 最终结论 | success / rolled back / forward fix |

## 9. 相关事实源

- 迁移 SQL：`amz-service/amz-service-ad/src/main/resources/db/migration/V7__ad_business_uniqueness.sql`
- MySQL 集成测试：`amz-service/amz-service-ad/src/test/java/com/amz/migration/AdMigrationMySqlIT.java`
- 引用键迁移：`reference-key-convergence-rollback.md`
- 租户回填处置：`ad-tenant-backfill-remediation.md`


## 10. 真机取证与 CI 接线（2026-09-28）

### 10.1 为什么需要这一节

在此之前，V7 的结论只有两层证据：(a) 静态契约测试（`AdUniquenessMigrationContractTest` 等，不连数据库）；
(b) 合成数据工具链在真 MySQL 8 上的导入（`apply_migrations.py` 以**裸 SQL** 执行 `db/migration/*.sql`，**绕过 Flyway**）。
两者都不能证明「V1–V7 在真实 MySQL 8 上被 Flyway 依次执行过」。
`AdMigrationMySqlIT` 是全仓唯一补上这一层的测试，但在 2026-09-28 之前它**从未被执行过**：
surefire 默认不收 `**/*IT.java`，且 CI 里没有任何一步会触发它（因此它里面的缺陷也一直没人发现）。

### 10.2 怎么跑

本地（换成你自己的一次性 MySQL 8 端口与账号）：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
$env:AD_MYSQL_IT_URL='jdbc:mysql://127.0.0.1:3399/amz_ad_it?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
$env:AD_MYSQL_IT_USER='amz'
$env:AD_MYSQL_IT_PASSWORD='<你的密码>'
$env:AD_MYSQL_IT_RESET='true'   # 自动 DROP+CREATE 保证空库；不设则库非空时快速失败并给出提示
mvn -B test -pl amz-service/amz-service-ad -am
```

三个变量不设 → 整类被 `@EnabledIfEnvironmentVariable` 跳过（实测：无变量时 ad 模块 117 例中 `Skipped: 1`、BUILD SUCCESS），
所以没有 MySQL 的开发机不受影响。

CI：`.github/workflows/ci.yml` 的 `test` job 自带 `mysql:8.0` service，已在该步骤注入
`AD_MYSQL_IT_URL` / `AD_MYSQL_IT_USER` / `AD_MYSQL_IT_PASSWORD`；
数据库由 IT 自己 `CREATE DATABASE IF NOT EXISTS` 建立（service 只给空实例，不预建库），因此不需要额外装 mysql-client。

### 10.3 为什么不能用 `-Dtest=AdMigrationMySqlIT`

`CiWorkflowContractTest` 把 `-Dtest=` / `-Dgroups=` / `-DexcludedGroups=` 定义为「收窄范围」，
断言 `invocations.narrowing.isEmpty()`，且没有任何补偿机制。
所以触发方式只能是**整仓 `mvn test` + 根 pom 的 surefire `<includes>` 额外收 `**/*IT.java`**。
代价与前提：本仓不引入 failsafe，因此**任何新增 `*IT.java` 必须自带环境守卫**（`@EnabledIfEnvironmentVariable` 等），
否则在没有 MySQL/Redis 的开发机与 CI 上会直接红。

### 10.4 真机暴露的两个真实缺陷（2026-09-28 已修）

两者在静态契约测试下都不会暴露，只有 MySQL 8 严格模式会：

- **A. `insertLegacyBusinessDuplicateRows()` 的列清单与值错位**：原写
  `(campaign_id, shop_id, keyword, match_type, bid, state) VALUES (101, 'c1', ...)`，
  而 `V1__init.sql` 中 `shop_id` 是 BIGINT、`campaign_id` 是 VARCHAR(64) —— 值与列正好对调。
  MySQL 8 严格模式直接报 `Incorrect integer value: 'c1' for column 'shop_id'`（宽松类型引擎不会炸）。
  已改为 `(shop_id, campaign_id, ...)`；同文件其它 INSERT 本就以 `shop_id` 打头，属抄写笔误。
- **B. 三处 `uniqueIndexColumns(...)` 期望值写成 1**：该函数数的是
  `information_schema.statistics` 里 `non_unique=0` 的**行数**，即索引**包含的列数**，不是索引个数。
  已按 V7 实际建出的键改为 **4 / 3 / 3**（`uk_ad_keyword_shop_campaign_keyword_match` 4 列、
  `uk_ad_converting_shop_campaign_term` 3 列、`uk_ad_asin_shop_asin_keyword` 3 列），并加注释防止被改回。

### 10.5 实测记录（MySQL 8.0.39，端口 3399）

| 项 | 结果 |
|---|---|
| `mvn -B test -fae`（整仓，带 IT 环境变量） | **BUILD SUCCESS**；ad 模块 **117** 例（116 + IT）、0F / 0E |
| `AdMigrationMySqlIT` | Tests run: 1, Failures: 0, Errors: 0 |
| `flyway_schema_history`（库 `amz_ad_it`） | V1–V7 **7/7 success=1**，13 张表建成 |
| 不设 IT 环境变量（ad 模块） | 117 例中 `Skipped: 1`，BUILD SUCCESS |
| `CiWorkflowContractTest`（ci.yml 改动后） | 2/2 PASS |

本机 3399 实例是一次性实例（见 `mock-data-seed-and-cleanup-runbook.md` §8），`amz_ad_it` 为 IT 专用库，
14 个业务库里的合成数据集未被污染。
