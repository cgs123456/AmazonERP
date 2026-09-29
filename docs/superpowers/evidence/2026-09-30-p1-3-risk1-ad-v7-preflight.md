# P1-3 随访：风险 #1 —— ad/V7 业务唯一键迁移的可执行预检（2026-09-30）

> 结论先行：Runbook 第 3 节的预检 SQL 已经从「文档里的一段 SQL」变成**一条会自己给出退出码的命令**
> `tools/db-migration/ad_v7_preflight.py`（只读，不执行 V7、不改数据）。
> 单测 **15/15 绿**（不连库）；在真实 **MySQL 8.0.46** 上跑了 5 个用例，得到
> rc=0（可放行）/ rc=2（命中 STOP）/ rc=1（脚本错误，同样不得执行），退出码契约全部兑现。
> **V7 本身仍未执行，风险 #1 仍未闭环**：预检只挡 STOP，不替代备份、维护窗、审批和恢复演练（Runbook §4/§5）。

## 1. 为什么要有这个脚本

`V7__ad_business_uniqueness.sql` 是**破坏性归并**：先 `UPDATE` 规范化，再把最大 `id` 的快照合并到
最小 `id` 的行，然后 `DELETE` 较旧的重复行，最后加唯一键。被删掉的旧行**不可通过反向 SQL 重建**。

Runbook §3 已经写清了预检 SQL 和 STOP 条件，但它们是**文档里的 SQL**：要靠人肉复制粘贴执行、靠人肉判断
「有没有命中 STOP」。人肉判断在发布夜是最容易跳过的环节，所以预检必须是一条能直接串进发布流程、
命中 STOP 就退出非 0 的命令。

## 2. 交付物

| 路径 | 说明 |
|---|---|
| `tools/db-migration/ad_v7_preflight.py` | 预检本体。只读：`SELECT` / `information_schema` / `CHECKSUM TABLE`。密码走 `--password` 或 `MYSQL_PWD`（不进进程列表），与 `tools/synthetic-data/apply_migrations.py` 同一套做法 |
| `tools/db-migration/test_ad_v7_preflight.py` | 15 个回归单测，用 stub client 驱动 `run_preflight`，**不连数据库**，可在任何 CI 上跑 |

退出码契约：

| 退出码 | 含义 |
|---|---|
| `0` | 未命中任何 STOP，可以进入备份与执行阶段（Runbook §4/§5） |
| `2` | 命中至少一个 STOP，**不得执行 V7** |
| `1` | 脚本自身出错（连不上库、SQL 执行失败、找不到 mysql 客户端）；**同样不得执行 V7**（fail-closed） |

STOP 码与 Runbook 的对应关系（脚本 docstring 里有同一张表）：

| STOP 码 | 来源 |
|---|---|
| `server-version` | §2.1 目标必须是 MySQL 8.0 |
| `missing-tables` | §2.1 三张表必须存在 |
| `no-flyway-history` / `flyway-failed-history` | §2.1 / §5 必须有 history，且不能有 `success=0` |
| `v7-already-applied` | §2.1 重复执行无意义，且说明状态与变更单不符 |
| `v1-v6-not-applied` | §2.1 V7 依赖 V1-V6 |
| `keyword-empty` / `search-term-empty` / `asin-empty` / `asin-keyword-empty` | §3.2 规范化后为空（归并后会丢身份） |
| `campaign-id-mixed` | §3.2 同键组内 `campaign_id` 的 NULL / 空白 / 非空白混杂 |
| `converting-conflict` | §3.2 同键组内 `status` / `is_added_to_keyword` 冲突 |
| `keyword-value-conflict` | §3.2 同键组内 `bid` / `base_bid` / `state` 没有明确责任行 |
| `asin-last-checked-ambiguous` | §3.2 同键组内 `last_checked` 为空或不唯一 |

## 3. 规范化键与 V7 的一致性（已逐条对原文核对）

预检判定的「重复组」必须和 V7 真正会归并的组是同一批，否则预检就是自欺。三个键对照
`V7__ad_business_uniqueness.sql` 原文：

| 表 | V7 的规范化 + 分组 | 预检的键表达式 |
|---|---|---|
| `amz_ad_keyword` | `campaign_id=TRIM(...)`、`keyword=LOWER(TRIM(...))`、`match_type=UPPER(TRIM(COALESCE(NULLIF(match_type,''),'EXACT')))`，`GROUP BY shop_id, campaign_id, keyword, match_type` | `shop_id, campaign_id, LOWER(TRIM(keyword)), UPPER(TRIM(COALESCE(NULLIF(match_type,''),'EXACT')))` |
| `amz_ad_converting_terms` | `campaign_id` NULL→`''` 再 `TRIM`、`search_term=LOWER(TRIM(...))`，`GROUP BY shop_id, campaign_id, search_term` | `shop_id, TRIM(COALESCE(campaign_id,'')), LOWER(TRIM(search_term))` |
| `amz_ad_asin_keyword` | `asin=UPPER(TRIM(asin))`、`keyword=LOWER(TRIM(keyword))`，`GROUP BY shop_id, asin, keyword` | `shop_id, UPPER(TRIM(asin)), LOWER(TRIM(keyword))` |

一致的证据（实测，见 §4 case2）：`'Widget'/'exact'/bid=1.00` 与 `' widget '/'EXACT'/bid=2.00`
两行被判定为**同一组**（大小写、首尾空白、`match_type` 大小写都按 V7 的方式归一）。

## 4. 实跑证据（真实 MySQL 8.0.46）

### 4.1 环境

| 项 | 值 |
|---|---|
| 目标库 | `amz-p13-drill2`（`mysql:8.0` → **MySQL 8.0.46**），docker 内网 `p13net`，`172.20.0.3:3306`，`root` |
| 预检执行容器 | `amz-p13-py`：`mysql:8.0` 镜像（自带 `mysql  Ver 8.0.46` 客户端）+ `python-build-standalone` CPython **3.11.10** 解压到 `/opt/py/python` |
| 演练库 | `amz_ad_pf`：用 `amz-service-ad` 的 **V1–V6 原始 SQL** 逐条 `source` 建表（**未执行 V7**），再插入 6 行 `flyway_schema_history`（version 1–6，success=1） |
| 对照库 | `amz_ad_it`：`AdMigrationMySqlIT` 已真实跑过 **V1–V7 7/7 success=1** 的库 |

> 环境说明：本机 Docker 守护进程配置的代理 `http://127.0.0.1:7897` 已失效（pull 报
> `dial tcp 127.0.0.1:7897: connectex ... refused`），拉不到 `python:*` 镜像，容器内 `apk/apt` 也出不去。
> 因此改用**宿主下载 CPython 独立发行版、`docker cp` 进已有 `mysql:8.0` 容器**的方式拼出
> 「python + mysql 客户端」运行环境。这只影响取证手段，**不影响脚本本身**：生产上用系统 python + mysql 客户端即可。

### 4.2 用例与结果

| # | 场景 | 期望 | 实测 | 关键输出 |
|---|---|---|---|---|
| 0 | 空库 `amz_ad_pf`（V1–V6 已应用） | rc=0 | **rc=0** | 全部 `STOP ... = 0`，末行「未命中 STOP：可进入备份与执行阶段」 |
| 1 | 插入 `keyword='   '`（规范化后为空） | rc=2 | **rc=2** | `[keyword-empty] ... 命中 1 处` |
| 2 | 插入同键组重复且 `bid` 冲突的两行 | rc=2 | **rc=2** | `重复组数 keyword: 1（将物理删除约 1 行）` + `[keyword-value-conflict] 命中 1 处` |
| 3 | 插入 `asin='  '` | rc=2 | **rc=2** | `[asin-empty] ... NULL 0 行 / 空白 1 行` |
| 4 | 清理后重跑 | rc=0 | **rc=0** | 回到「未命中 STOP」 |
| 5 | 对已执行过 V7 的 `amz_ad_it` 跑 | rc=2 | **rc=2** | `[v7-already-applied] V7 已执行过（success=1）` |
| 6 | 库不存在 / 账号错误 | rc=1 | **rc=1** | `预检无法完成：SQL 执行失败（退出码 1）：ERROR 1049 Unknown database ...` / `ERROR 1045 Access denied` |

case5 的原始报告节选（真实输出，未加工）：

```
=== V7 预检报告（库 amz_ad_it@172.20.0.3:3306）===
MySQL 版本: 8.0.46
目标表: amz_ad_keyword, amz_ad_converting_terms, amz_ad_asin_keyword
Flyway 已成功版本: 1, 2, 3, 4, 5, 6, 7
基线行数 amz_ad_keyword: 2
基线行数 amz_ad_converting_terms: 3
基线行数 amz_ad_asin_keyword: 3
基线 checksum: amz_ad_it.amz_ad_keyword = 1678144882
...
=== STOP：命中 1 项，禁止执行 V7 ===
  [v7-already-applied] V7 已执行过（success=1）。重复执行无意义，请确认变更单与实际状态一致
```

case2 的关键两行：

```
重复组数 keyword: 1（将物理删除约 1 行）
  [keyword-value-conflict] 同键组内 bid / base_bid / state 无明确责任行：命中 1 处
```

### 4.3 只读性验证

对 `amz_ad_it` 连续跑两次：两次输出**逐字节相同**（`IDENTICAL`），且 `SELECT COUNT(*)` 仍是
`amz_ad_keyword=2`、`amz_ad_asin_keyword=3`。脚本只发 `SELECT` / `information_schema` / `CHECKSUM TABLE`，
不改数据——这点有实测支撑，不是声明。

## 5. 边界：这些证据**不**说明什么

1. **空库 rc=0 ≠ 生产可以无脑执行**。case0 只是「这个库的当前状态没有触发任何 STOP」；
   生产是否有重复、是否接受「旧行消失」仍要人来拍板。
2. **预检不替代备份**：rc=0 且 `重复组数 > 0` 时，V7 依然会物理删行（脚本会打印「将物理删除约 N 行」）。
   这类情况**不是 STOP**，但仍是破坏性操作 —— Runbook §4 的备份与恢复验证不可省。
3. **预检不替代审批与维护窗**（Runbook §2.2/§2.6、§5）。
4. **未接入 CI**：脚本目前是手工命令，没有挂到 GitHub Actions。单测 15/15 是本地 `python -m unittest`
   的结果，CI 上还没有跑过 —— 若要当门禁，需要先加一个 workflow 步骤。
5. **演练库的 history 是 INSERT 出来的 fixture**：`amz_ad_pf` 的 6 行 `flyway_schema_history` 是手工插入的，
   `checksum` 为 NULL，只用来证明脚本读 history 的逻辑，**不能当作 Flyway 真实执行记录**。
6. **一个实测发现的语义细节**：`amz_ad_asin_keyword.asin` 在建表时是 `NOT NULL`，所以预检里
   `asin IS NULL` 那一支在 V7 之前恒为 0；而 V7 第 3 节只做 `UPPER(TRIM(asin))`、
   **不像 converting_terms 那样把空白转成 NULL**，因此空白 ASIN 会变成 `''` 并被唯一键归并。
   这正是 `asin-empty` 要报的东西，执行人需要明确接受「多个空白 ASIN 行会被合成一行」。

## 6. 复现命令

```bash
# 1) 单测（不连库，宿主 python 3.11 即可）
cd tools/db-migration
python -m unittest -v test_ad_v7_preflight      # 期望 Ran 15 ... OK

# 2) 真实库预检（有 python + mysql 客户端的机器上直接跑）
MYSQL_PWD='<pw>' python ad_v7_preflight.py \
    --host <host> --port 3306 --user root --database amz_ad
# rc: 0=可进入备份/执行；2=命中 STOP，禁止执行 V7；1=脚本自身出错，同样禁止
```

## 7. 风险 #1 剩余待办（仍 OPEN）

- [ ] 生产/预发库的真实预检执行（本轮证据来自演练库 + 合成数据）
- [ ] Runbook §4 备份 + 独立临时库恢复验证
- [ ] 维护窗 + 停止广告同步/搜索词聚合/ASIN 反查写入
- [ ] 回滚批准人与业务负责人签字
- [ ] 把预检接进 CI / 发布流程（否则它仍然是一条「想起来才跑」的命令）