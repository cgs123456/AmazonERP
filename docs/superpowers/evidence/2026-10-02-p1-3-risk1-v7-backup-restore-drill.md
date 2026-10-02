# 2026-10-02 P1-3 风险 #1：ad/V7 备份恢复演练与迁移影响量化

范围：`docs/superpowers/evidence/2026-09-30-p1-3-migration-audit.md` §9 第 1 条（四个风险里当时唯一仍 OPEN 的）。
风险原文：V7 在迁移内**删数据 + 改写数据**（空 `match_type` 被填成 `EXACT`；重复业务键保留 min(id) 并把
最新快照并到 keeper 上，再删旧行），上线前要求备份 + 预检 + 恢复演练。
前一环：`2026-09-30-p1-3-risk1-ad-v7-preflight.md` 已交付只读预检 `tools/db-migration/ad_v7_preflight.py`
（单测 15/15，rc=0/1/2 三类退出码在真库兑现），但**它不替代备份、维护窗、审批与恢复演练** —— 本次就是补这一段。

流程按 `docs/superpowers/runbooks/ad-business-uniqueness-migration.md` §3.3 / §4 / §5 / §6 执行。

## 0. 结论先行

| 项 | 结果 |
|----|------|
| 备份/恢复演练 | **通过**。恢复到**另一个独立实例**后 `CHECKSUM TABLE` 三张表与源库逐项相同，行数相同 |
| 预检在真实形状数据上的行为 | **rc=2 拒绝执行**，命中 4 条 STOP（含 `campaign-id-mixed`、`keyword-value-conflict`、`asin-last-checked-ambiguous`、`no-flyway-history`） |
| V7 语义 | 与设计一致：规范化 → 最新快照并入 keeper → 删旧行 → 唯一键；迁移后重复残留 0 |
| 新增发现（决策相关） | 预检的删除量估算**曾会低估**，原因已定位并修复：`amz_ad_keyword` 的分组键漏了 V7 第一步的 `TRIM(campaign_id)`。修复后同一夹具预估 = 实删（2 = 2）|
| 量级耗时（合成 20 万行） | dump 1.10s / 22.9 MB；恢复 9.80s；V7 57.1s |
| 仍未闭环 | 审批、维护窗、备份文件的加密保存与保留策略、§6.6 应用级验收 —— 见 §5 |

## 1. 环境与做法

- 两个隔离 MySQL 8.0.46 容器（`--rm`、不挂卷、独立端口 3404 / 3405），
  与在跑的演示栈（`amz-mysql` 3307）完全无关，全程未向后者读写。
  删除量预估的修复实测（§3 发现 1）另起一个同规格容器（端口 3406）。三个都是 `--rm`，
  验证完即回收，本机无残留；预估=实删 这一条的可复现证据留在单测
  `test_deletion_estimate_uses_the_normalized_key` 里。
- 源库 `amz_ad` 按 `V1..V6` 建模式（逐文件用 mysql 客户端执行），三张目标表：
  `amz_ad_keyword`、`amz_ad_converting_terms`、`amz_ad_asin_keyword`。
- 夹具覆盖 V7 的每条分支：`campaign_id` 带首尾空格、`keyword` 大小写混排、
  `match_type` 为 `''` / `NULL` / `'exact'`、同业务键 3 行、`converting_terms.campaign_id IS NULL`、
  以及一行**内部双空格**的关键字（用来探"规范化到底折叠了什么"）。
- 本机没有 mysql 客户端，预检工具的 `--mysql-path` 指向一个转接脚本，把它的客户端调用转发进容器
  （端口/账号映射在转接里剥掉）。跑的是**真工具的 SQL 与退出码**，不是我对它逻辑的复述。

## 2. 演练记录（按 runbook 小节）

### §3.3 迁移前基线（11 行样本库）

```
amz_ad_keyword            6    checksum 2007690120
amz_ad_converting_terms   3    checksum 1002642057
amz_ad_asin_keyword       2    checksum 2118956172
冻结点：MAX(id)=6，MAX(update_time)=2026-09-09 10:00:00
结构快照：3 条 SHOW CREATE TABLE，3349 字节
```

### §4 备份与恢复

| 步骤 | 结果 |
|---|---|
| `mysqldump --single-transaction --routines --triggers --set-gtid-purged=OFF --databases amz_ad` | rc=0，22,582 字节（11 行样本，耗时低于计时精度，不外推） |
| 恢复到独立实例（8.0.46） | rc=0，约 2s |
| `CHECKSUM TABLE` 逐项比对 | **三张表全部一致**，行数 6 / 3 / 2 一致 |
| 合成量级下的同一对操作（200,011 行） | dump **1,102ms / 23,965,858 字节**；`DROP DATABASE` 后恢复 **9,797ms**；恢复后 checksum 与冻结点**逐字相同** |

顺带纠正我自己的两个操作失误：第一次取结构快照时把三条 `SHOW CREATE TABLE …\G` 塞进同一个 `-e`，
只落下来 51 字节的 `No database selected` 报错 —— 若不查字节数就会把坏产物当完成；
第二次是 `-N -e` 忘了带库名。都重做了。

### §5 执行 V7

整文件一次执行（不拆句、不选择性执行），源库 rc=0、**1,079ms**（小样本）。
量级库上同一条 V7：**57,064ms**（20 万行）。

⚠️ 差异登记：本次演练的 V7 是**由 mysql 客户端整文件执行**，没有经 Flyway，
所以 `flyway_schema_history` 不存在 —— 预检正是因此报出第 4 条 STOP
（`no-flyway-history`）。正式执行必须按 runbook §5 用 Flyway（`-target=7`），
本演练证明的是 SQL 本身的行为与代价，不是 Flyway 路径。

### §6 迁移后验证（全部通过）

| 检查 | 实测 |
|---|---|
| §6.1 唯一键存在且列序正确 | `uk_ad_keyword_shop_campaign_keyword_match` = (shop_id,campaign_id,keyword,match_type) 4 列；`uk_ad_converting_shop_campaign_term` 3 列；`uk_ad_asin_shop_asin_keyword` 3 列，`NON_UNIQUE=0` |
| §6.2 规范化与约束 | `' C1 '→'C1'`；`'Yoga Mat'→'yoga mat'`；`match_type ''`/`NULL` → `EXACT`；`keyword`/`match_type`/`campaign_id` 均 `IS_NULLABLE=NO`，`match_type` 默认 `'EXACT'` |
| §6.3 重复残留 | 量级库上 `GROUP BY … HAVING COUNT(*)>1` = **0** |
| §6.4 行数 | 小样本 6→4、3→2、2→1；量级库 100,006→**100,004**、100,003→**50,002**、2→1 |
| §6.5 抽样保留语义 | keyword keeper(id=1) 拿到组内 **id 最大**那行的 `bid=3.00` 与 `state`，而 `base_bid` 因最新行为 NULL 而保留 keeper 的 `1.10`（COALESCE 方向正确）；converting keeper 的 `asin` 同理保留 `B0ABC`；asin_keyword keeper 取到最新的 `organic_rank=7/ad_rank=1/last_checked=09-09` |

## 3. 决策相关的新发现

1. **预检的删除量估算确实低估过，但原因比我第一版的说法窄——而且已修。**
   第一版写成"预检按未规范化值分组、V7 按规范化值分组"，这话**过宽**：`keyword` / `match_type` /
   `search_term` / `asin` 的键本来就带了 `LOWER` / `TRIM` / `UPPER` / `COALESCE`；
   真正漏的只有一处 —— V7 第一步有 `SET campaign_id = TRIM(campaign_id)`，
   而预检的 `KW_KEY` 按原始 `campaign_id` 分组。所以只有"campaign 带首尾空格"这一类重复被漏算
   （同一夹具实测：预估 1 行、V7 实删 2 行）。
   另一处也一并更正：量级夹具里 `converting_terms` 因 `campaign_id IS NULL → ''` 折叠删掉的
   **50,001** 行**不是**低估案例 —— 那张表的键本来就规范化了 `campaign_id`，预检算得进去，
   而且这种情况会另外命中 `campaign-id-mixed` 这条 STOP。
   处置：`KW_KEY` 补 `TRIM(campaign_id)`，新增
   `test_group_keys_match_v7_step1_normalizations` 从**迁移 SQL 现读** V7 第一步的规范化赋值、
   逐列与预检键比对（期望值不手抄：手抄的那份改了 SQL 而预检没跟上时，测试会跟着一起绿）。
   这条测试自己做过变异验证，5 次注错全部翻红：预检少写 `TRIM(campaign_id)`、V7 改了表达式、
   V7 新增一个键外被规范化的列、解析器正则失配（防空跑）、等价表白名单留了用不上的条目。
   它由 `tools/db-migration/run_ad_v7_preflight_gate.sh` 第 3 步执行，而该脚本是 CI 的
   `ad-v7-preflight-gate` job（`.github/workflows/ci.yml:376`）——所以 V7 SQL 与预检键的
   对应关系每次推送都会被检查，不依赖有人手工去跑这个文件。
   修后同一活库实测**预估 2 行 = 实删 2 行**。
   真正还需要人确认的只剩一条：**批量导入的数据里 `MAX(id)` 未必是业务上的最新快照**。

2. **业务键是"规范化后的精确串"，内部空白不折叠。** `'yoga  mat'`（双空格）与 `'yoga mat'`
   在 V7 之后仍是两条不同记录。这不是缺陷（`TRIM` 本来只管首尾），但如果运营以为
   "多余空格会被自动合并"就会得到意外结果，值得在 runbook §6.5 的验收清单里点一句。
3. **`no-flyway-history` 这条 STOP 有牙齿。** 本次因为绕过 Flyway 而真实触发了一次，
   说明它不是纸面规则。

## 4. 变更单（runbook §8 模板，本次演练）

| 项目 | 内容 |
|---|---|
| 环境 / 实例 | 本机隔离 Docker 容器 `erp-v7drill` / `erp-v7restore`（MySQL 8.0.46），**非生产** |
| 数据库 | `amz_ad`（V1..V6 由 SQL 文件应用） |
| 冻结时间 | 样本库 MAX(id)=6 / MAX(update_time)=2026-09-09 10:00:00 |
| 备份文件与恢复验证 | 备份 22,582 字节（样本）/ 23,965,858 字节（20 万行）；恢复到独立实例后 `CHECKSUM TABLE` 三表逐项一致，行数一致 |
| 预检结果 | rc=**2**；4 条 STOP（见 §5） |
| 迁移执行 | V7 整文件 rc=0；样本 1,079ms、20 万行 57,064ms |
| 迁移后验证 | §6.1–§6.5 全通过 |
| 执行人 / 审批人 | 执行：本轮演练（Qoder agent）；**审批：待用户**；维护窗：**待定** |

## 5. 风险 #1 现在还差什么

演练侧已闭环；剩下四项都是**流程与授权**，不是技术缺口：

1. 在**生产形状**的数据上跑一次预检（本次是合成夹具），据此定维护窗时长——量级参考：20 万行 57s，
   但真实删除量取决于重复密度，不可由这个数字外推；
2. 备份文件的**加密存放与保留期限**（runbook §4.7；广告表含搜索词等经营数据）；
3. **审批人 + 冻结写入**的实际执行（本次没有并发写入场景，未测写锁行为；
   风险 #2 的随访已在 order/V4 上测过 `LOCK=NONE` 与并发写入）；
4. §6.6 应用级验收（keyword 写入走 `ON DUPLICATE KEY UPDATE`、converting terms 聚合、ASIN 反查），
   需要在跑着服务的实例上回归，而那套栈现在属于另一个 worktree，未被授权触碰。

生产执行路径本身仍须由 Flyway 走（`-target=7`），并防多实例并发迁移 —— 见 §5 的 ⚠️。
