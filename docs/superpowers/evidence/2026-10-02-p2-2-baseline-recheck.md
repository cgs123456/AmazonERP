# 2026-10-02 P2-2 基线重测：口径下沉到 SQL 层（用户决定），HTTP 层未重测的原因与代价都留档

范围：本轮"重测 P2-2 性能基线"这一项的实际执行结果。
**先说结论：基线口径按用户 2026-10-02 的决定下沉到 SQL 层——本轮交付的是 SQL 层重测（§2–§4），
它覆盖的正是本轮改动唯一真正触及的那一层；HTTP 层基线没有重测，§1 把它不能重测的实测依据、
以及日后仍要拿到 HTTP 绝对数所需的条件都留在档，不作为待办追着做。**

这个决定的**代价必须写在明面上**（详见 §5）：下沉后没有覆盖的是 JVM 侧实体物化、
HTTP/网关/Feign 路径、并发与连接池——§3 的比值因此是**下限**而不是端到端结论，
且换不了"上线后 p95/p99 是多少"这类问题（那仍只能等机器独占时按 §1 的命令跑一次）。

参考基线：`AmazonERP-p2-performance/docs/superpowers/evidence/2026-09-30-p2-perf-baseline/bench-read-c10-r100.json`
（**另一 agent 的未提交产物**，c10×r100 三场景，打的是它那套栈的 gateway `127.0.0.1:10010`）。

---

## 1. 为什么 HTTP 层基线在这台机器上不能重测（实测数字，不是推测）

| 项 | 实测值 | 取法 |
|---|---|---|
| 宿主内存 | 32 GiB（`TotalVisibleMemorySize=33444992` KB），空闲 **9.4 GiB** | `Win32_OperatingSystem` |
| 逻辑 CPU | **12** | `Win32_ComputerSystem` |
| Docker VM 内存上限 | **15.57 GiB** | `docker stats` 的 `MemUsage` 分母 |
| 在跑的 P2 栈占用 | **≈ 7.0 GiB / VM 的 45%**（gateway 739MiB、order 961MiB、report 826MiB、finance 760MiB、user 767MiB、spapi 1.13GiB、mysql 449MiB、mysql-slave 357MiB、nacos 764MiB、rabbitmq 164MiB、redis 10MiB） | `docker stats --no-stream` |

重跑那条 HTTP 基线需要再起一套 **gateway + order + report + finance + mysql + nacos + redis**
（≈ 4–5 GiB），把这 3×1000 次、并发 10 的请求压在**同一个 12 核 / 15.57 GiB 的 VM** 上，
而那份参考基线是在机器只有它一套栈时取的。两个后果都是致命的：

1. **数值不可比**：两套栈抢 12 核，p95/p99 一定被邻居污染。参考基线里已经是
   `order-list p99 = 21,065 ms`、`finance-profit p99 = 33,952 ms` 并带
   `WinError 10060/10054` 超时——在那种尾巴上再叠一层邻居负载，测出来的差异
   **无法归因**到底是代码还是抢 CPU（这正好违反"先证明因果再下结论"）。
2. **会危及别人的环境**：VM 剩余约 8.5 GiB，再加 4–5 GiB 后任何一次 JVM GC 峰值都可能
   让 Docker VM 触顶，先被 OOM 掉的可能就是**那套已经跑了 43 小时的演示栈**（含它的 MySQL）。
   这条栈不是我起的、不是我拥有的，用户明确要求不要动它。

**栈归属（实测，非记忆）**：`docker inspect amz-service-report` 的
`com.docker.compose.project = amz-erp-p2-perf`，
`project.working_dir = C:\Users\Administrator\Desktop\AmazonERP-p2-performance`，
`environment_file = AmazonERP-p2-performance\.env.demo.example`，
override 在 `%TEMP%\amz-erp-p2-compose.override.yml`，网络 `amz-erp-p2-perf_default`。
即：**Codex 会话 `codex/p2-performance-baseline`（worktree `AmazonERP-p2-performance`）拥有在跑的栈**，
它的工作区还有 13 个已改未提交文件 + 未跟踪的 `loadtest/scripts/bench.py`、
`loadtest/tests/`、基线 JSON —— 重测所需的**工具和基准数字都在那个未提交状态里**。

**若日后仍要 HTTP 绝对数，所需条件（按用户决定本轮不追，留作可执行的交接）**：
- 先决条件：那套栈收工并 `docker compose down`（或用户明确确认可以由我接管）——
  **这是停别人正在用的环境，我不会自己决定**；
- 且 `codex/p2-performance-baseline` 把 master 合进去、把 `bench.py` + 基线 JSON 提交下来
  （master 上现在只有 `quick-bench.sh` / JMeter / Gatling，**没有**那个 dependency-free 的 `bench.py`）；
- 届时命令：`python loadtest/scripts/bench.py --base-url http://127.0.0.1:<gateway 端口>
  --concurrency 10 --requests 100`（与参考基线同参数才可比）。

k8s 侧验证按用户指示**继续搁置**（本机无 cluster/context，本轮未做任何相关操作）。

---

## 2. SQL 层重测：环境（全部隔离，未触碰演示栈）

| 项 | 值 |
|---|---|
| 实例 | `docker run -d --rm` 的 `mysql:8.0`，实测 `SELECT VERSION()` = **8.0.46**，端口 `127.0.0.1:3407`，不挂卷 |
| 建库 | 用**仓库自己的** `tools/synthetic-data/apply_migrations.py` 经转接脚本执行：`TOTAL migrations=49 failed=0`（14 个库的真实 DDL 与真实索引，不是我手写的简化表） |
| 数据量（精确核对过） | `amz_profit_detail` **200,000** 行 / 44,445 个去重订单 / 5,000 个 ASIN；`amz_payment_collection` **100,000** 行 / 5 种币种；`amz_listing_health` **50,000** 行 |
| 夹具形状 | 一单跨 4.5 行（让 `COUNT(DISTINCT)` 的差距是真实的）；`shortfall` 70% 为 NULL（"费用比对未做"）；`health_score` 5% NULL；日期铺满 365 天 |
| 取数方式 | 每例 3 次取中位数，另测 `SELECT 1` 作为同轮基准并减掉 |
| 唯一键约束 | `uk(shop_id, amazon_order_id)`、`uk(shop_id, asin)` 逼着这两张表按行去重，只有 `amz_profit_detail` 允许一单多行 |

顺带用活库复核了一件与 §25 修复直接相关的事：在 `utf8mb4_unicode_ci` 下
`COUNT(DISTINCT severity)` 把 `'OK'` 与 `'ok'` 数成同一个值（夹具写了 4 种，实测只报 3）——
这正是 `aggregateHealthSummary` 必须写 `severity COLLATE utf8mb4_bin` 的原因，
不是理论洁癖。

---

## 3. SQL 层重测结果（net = 中位数 − 同轮 `SELECT 1` 基准）

| 场景 | 下沉前（整店取回 Java） | 下沉后（SQL 聚合） | 服务端耗时 | 传输量 |
|---|---|---|---|---|
| **C2 回款概览**（10 万行/店） | 0.505 s，15,361,183 B | **0.287 s，536 B** | 1.8× | **28,700×** |
| **C4 利润 ASIN 汇总**（全年 20 万行） | 1.494 s，36,364,114 B | **0.598 s，294,360 B** | 2.5× | 123× |
| C4 利润 ASIN 汇总（近 30 天） | 0.093 s，2,989,534 B | **0.025 s，52,857 B** | 3.7× | 57× |
| **§25 Listing 健康度汇总**（5 万行/店） | 0.221 s，6,864,903 B | **0.015 s，98 B** + Top5 **0.018 s，175 B** | 7.5× | **25,000×** |

同轮 `SELECT 1` 基准 = 0.264 s（三次 0.264/0.264/0.221）。

**订单数口径改动的代价**（#27，同库同量）：`COUNT(1)` 0.018 s → `COUNT(DISTINCT amazon_order_id)`
**0.335 s**，即这次改口在单店全年 20 万行上多花约 **0.32 s**。相对它换掉的 1.49 s 全行读取是
净赢，但它确实进了 dashboard 那条路径，值得记一笔。

---

## 4. 一条自我撤回：覆盖索引并没有我第一眼以为的那样有效

我给 `amz_profit_detail` 加了 `(shop_id, report_date, amazon_order_id)` 覆盖索引，
`EXPLAIN FORMAT=TREE` **确认优化器真的走了它**
（`Covering index range scan on ... using idx_shop_date_order`）——这一步必须做，
否则"加了索引没变化"可能只是索引根本没被用。

第一次对比（各自只有 3 次、且"加索引后"那一轮没测自己的基准）看起来像"去重订单数快了 23%"。
**那个结论是假的**：补做两轮各 5 次、每轮自带 `SELECT 1` 基准后，

| 查询 | 无覆盖索引 | 有覆盖索引 |
|---|---|---|
| `COUNT(DISTINCT amazon_order_id)` | 0.369 s | 0.395 s |
| ASIN 聚合（含每组去重） | 0.453 s | 0.429 s |
| `COUNT(1)` | −0.047 s（**不可能为负**） | 0.008 s |

两轮的空查询基准自己就从 0.262 s 漂到 0.339 s —— 差 77 ms，比这两项之间的差异还大，
`COUNT(1)` 甚至被减成负数。所以：

- **撤回**"覆盖索引让去重订单数快了 23%"；
- 正确的表述是：**这套"每次调用都新起一个客户端进程"的转接法，分辨率大约 0.1 s，
  0.1 s 以内的差异不能下任何方向的结论**；
- 因此"`(shop_id, report_date, amazon_order_id)` 覆盖索引能救回那 0.32 s"这一条
  **未被证实**，我没有据此改任何 DDL，也没有把它写进行动建议；
- §3 表里那几条 0.2–1.2 s 量级的 old/new 差异远大于噪声，且三次重复同向
  （如 C4 全年：1.758/1.723/1.835 vs 0.862/0.889/0.853），**方向与量级可以采信**。

下一次要测 0.1 s 以内的差，得换成**同一连接内**的计时（`SET @t0=NOW(6) … SET @t1=NOW(6)`
或打开 `performance_schema` 按 digest 取 `AVG_TIMER_WAIT`），而不是靠减进程启动成本。

---

## 5. 本轮**没有**量到的（不要把 §3 当成端到端结论）

> 2026-10-02 后续：**第 1、2 条已被补上**——`2026-10-02-p2-2-http-before-after.md` 用影子库
> 跑出了 report 端点的 HTTP 前后对比（含"下沉前在 c10 下直接 OOM"这个定性结果）。
> 该文档同时记下了量的过程中炸出的两个 master 启动缺陷（finance / product 起不来）。
> 本节的第 3、4 条（合成夹具、机器非独占）对那份 HTTP 数据同样成立，别外推成生产数。

按 §1 的决定，这一组不是"待办"，而是**下沉口径的固有限制**：以后引用 §3 的数字时，
这几条要一起带上。

1. **JVM 侧成本完全没进表**：下沉前 Java 还要把 10 万/20 万行映射成实体再遍历，
   这部分只被"减少行数"间接受益，没有实测——所以 §3 的耗时比是**下限**，
   真实端到端收益只会更大，不会更小。
2. 没有 HTTP/网关/Feign 路径，没有并发（单连接串行），没有冷缓存（只报中位数与重复值，
   未刻意做冷启动对比），没有 MyBatis 结果映射开销。
3. 数据是**合成夹具**，不是演示库的真实分布（真实库里一单跨多少 ASIN、币种占比都会变），
   所以这些数说明的是"形状的量级"，不是"上线后就是这个数"。
4. 机器不是独占的：那套 43 小时的栈全程在跑，虽然我只发单连接查询，
   §3 的绝对秒数仍带着这个背景噪声（这也是我把结论压在"比值和方向"上的原因）。

---

## 6. 收尾

- 测量容器 `erp-perf` 为 `--rm`，验证完已 `docker rm -f` 回收；**临时密码文件与转接脚本已删除**
  （密码全程未打印，只在仓库外 `.qoder-cn/tmp/shim3/dbenv.txt` 存在过，现已不存在）。
- 复现用的三样东西留在 `.qoder-cn/tmp/shim3/`（`seed.sql` 夹具、`spec.json` 查询对、
  `drv.py` 计时器），里面**不含任何口令**，只是重跑时要另起容器并自备一个 `mysql` 转接；
  它们不在仓库里，随时可能被清掉——真要长期保留就得按 §4 末的口径改成同连接计时再入库。
- 全程未连接、未读写演示栈的 `amz-mysql`（3307）；docker 侧只新增又删除了这一个一次性容器。
- **构建缓存按用户 2026-10-02 的指示执行了 `docker builder prune -f`：实测回收 21.4 GB**
  （Build Cache 274 项/28.69 GB → 114 项/7.284 GB）。只动了 build cache 这一类：
  `docker system df` 里 Images 43/16.14 GB、Containers 24/905.2 MB、Local Volumes 35/10.67 GB
  三项前后完全一致，`docker ps` 仍是 13 个在跑（含那套栈的 11 个 `amz-*`）。
  代价：Dockerfile 里我加的 `--mount=type=cache,target=/root/.m2` 属于 build cache，
  下一次干净构建会重新下载一次 Maven 依赖，之后缓存重新填上。
- 交接上不再有待办项：HTTP 层基线按上面的决定下沉，不再单独追。
