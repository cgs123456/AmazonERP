# HANDOFF — AmazonERP 全面审查与修复战役（2026-10-06 交班）

工作树以本文件随交班 commit 推送为准，HEAD 以 `git log` 为准；全部已推送 `origin/master`。

## 一句话现状

上一班交班后，本班完成了**三轮全面 review + 修复战役**：修掉约 25 项发现
（3 处租户守卫 IDOR、PII 裸奔、webhook 伪造、前端假成功全链、可观测性断链、
CI 门禁缺口、11 张死表、3 个 VARCHAR 日期列、query helper×10 收敛、参数名尺
六桶归零、孤儿代码清扫）。基线：整仓 `mvn test` **2002 / 0F / 0E / 17S**，
四把尺 + 漂移 + hygiene 全零，部署链（compose + k8s + 双 env 模板）闭环。
覆盖率候选稳定 **33**（B 桶 20 缺凭据 / A 桶 13 刻意拒绝）。**无已知死代码。**

## 用户点名的任务与落地状态（全部完成）

| 任务 | 状态 |
| --- | --- |
| 第一轮 review 四步（整仓回归 / #57 / 形状尺 / 参数名尺） | ✅ 全部落地 |
| review 发现清单「逐项修复直至全部完善」 | ✅ 步 1-4 全完成（commit 分组见下） |
| 死表处置 | ✅ 拍板「直接清理」→ 5 服务 V-next DROP（`832a4e5`），zero-ref 现 102/0 |
| VARCHAR 日期列迁移 | ✅ 拍板「完成」→ procurement V6 / logistics V6（`6590ca4`），实体同步 LocalDate |
| 第二、三轮 review（现状盘点 + 死代码清理 + 文档更新） | ✅ 完成；交班自查又抓到 1 个「改一半」并契约化（见坑 1） |

## 本班 commit 分组（按主题；完整序列以 git log 为准）

| 主题 | commits | 要点 |
| --- | --- | --- |
| 安全 | `665a2fc` `8c8165d` `5ef5ed0` | 工单读/写与选品建议按行归属守卫（`requireShopOnRow`，CodeErrorException）；`getUserById` 非本人/非 ADMIN 掩码 phone/address（**掩码非拦截**——search 卖家卡片只需昵称，属合法调用方）；webhook HMAC-SHA256 验签 fail-closed、先于幂等落库；`IllegalArgumentException` 登进业务异常组；aiSuggestion 话术统一防 ID 存在性探针 |
| 前端诚实性 | `9a4165b` `061d320` `74573bf` | Dashboard/Warehouse/OrderList/LogisticsDashboard/Finance/ProductSelection 假成功全清（失败进错误横幅；示意数据必须带标识；利润 null 显示 `—` 而非 0.00）；多清空者统一「整页入口清一次、loader 按前缀追加」 |
| 三把尺进 CI | `57e79d3` `a9a4aa2` `a03b3f0` `cc67e69` `b750db6` | 形状尺（首轮抓到 `field` vs `fieldName` 真漂移）；参数名尺六桶归零（278 调用点，163 带键全核验）；type-extra 23 条按端点拆窄共享类型终解；query helper 收敛 `utils/query.ts` |
| CI/发布 | `8c8165d` `b956439` `f081bb4` | runtime-smoke 进 docker needs；全作业 timeout-minutes；release quality-gate 测试面与 ci 逐字对齐（services+24 env）；回滚演练 plan 干跑；bake 干跑；node 统一 22 |
| 可观测性 | `c86dfb3` `b2af827` | rabbitmq/node-exporter 抓取（积压/磁盘告警从永不触发变可用）；filebeat 采集链路（修 `include_lines` 全丢行的形态错）；16 服务 healthcheck；基础设施容器名 amz- 前缀 |
| 数据 | `6a7473c` `832a4e5` `30a5a18` `6590ca4` | ad V8 campaign_id 宽度收敛 + 冗余索引 DROP；11 死表 DROP 全链同步（synthetic-data/快照/zero-ref 工具补 DROP 重放）；3 日期列 → DATE/DATETIME（`STR_TO_DATE` 先清洗；**`TrackingEvent.eventTime` 刻意保持 String**——游标协议依赖字符串形态） |
| 部署开箱即用 | `9a4165b` `b5d71c9` `fdff0da` `cb53234` | compose 前端改带代理镜像（原路径把占位域名烤进产物）；webhook 密钥五处链闭环（双 env/compose/secret.yaml/k8s service）+ 两部署契约白名单；sonar/spotbugs 孤儿配置删除 |
| 清理与交班 | `8f7011c` `c13c9bb` `c2993ee` + 本次 | 孤儿 `parseDate`×2 + 测试 `DATE_FMT` 删除；README/HANDOFF 数字清账（113→102、1134 历史脚注、24/24）；node 统一收尾（见坑 1） |

## 门禁基线与复跑命令

```bash
export PATH="/c/tools/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
# 清点（正向=人读台账，不阻断）
python tools/schema/endpoint_coverage_audit.py                    # 候选 33
# 四把尺（CI 门禁：self-test + 闸门；任何「0 findings」先核对分母非空）
python tools/schema/endpoint_coverage_audit.py --self-test .      # 19/19；--reverse 0 findings
python tools/schema/stub_shape_audit.py --self-test .             # 20/20；闸门 0（140 注册 / 106 可比）
python tools/schema/param_name_audit.py --self-test .             # 24/24；闸门 0（163 调用点全核验）
python tools/schema/entity_column_drift.py --gate .               # 101 实体 / 0 漂移
python tools/release/repository_hygiene.py --root .               # 以退出码为准，别 grep 文本
python tools/schema/zero_reference_tables.py                      # 102 表 / 0 零引用
# 部署链契约（改 workflow/compose/k8s/env 模板前后必跑，含 node 版本三方一致）
mvn -pl amz-service/amz-service-spapi -o test -Dtest='CiWorkflowContractTest,PlaceholderCoverageContractTest,DeploymentManifestContractTest'
# synthetic-data（改 DDL/迁移后必跑；工作目录 tools/synthetic-data）
python tools/synthetic-data/snapshot_schema.py --check            # 102 表基线
cd tools/synthetic-data && python generate.py --tier ci --reset --out out/ci && python verify.py --tier ci --dataset out/ci
# 前端：node 在 /c/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-3；
# vue-tsc --noEmit、vitest run（必须在 amz-frontend/ 里跑）、playwright --workers=1。
```

- 基线：整仓 **2002/0/0/17**（16 模块，覆盖到 `c2993ee`+交班 commit）；
  spapi 675/0F、multiplatform 101、logistics 153、customer 33、ops 36、user 19；
  vitest **479/479**、e2e 串行 40-44 全过；vue-tsc 0 错（tsconfig 开
  `noUnusedLocals`——前端孤儿 import 编译期即红，历轮 0 错即无孤儿之证）。
- 后端定向跑：**逗号**分隔 `-Dtest='A,B'`，从不 `+`；`-q` 会伪装零测试绿。

## 卡住 / 未解决（按能不能自己推进分类）

**需要外部条件（做不了，别在原地重试）**
- B 桶 20 条：`DEEPSEEK_API_KEY`（4）、Keepa token（3）、店铺 SP-API 凭证（13）。
  代码路径是真代码、未配置即点名失败，没有凭据只能保持未接。
- CI run 是否真绿：gh 未认证、匿名日志 403。#56 本地精确复现已消除；本班推了
  20+ 个 commit，下次 run 日志是一次自然验证（含全部新门禁）。
- deploy-it 脚本在仓库外：迁移数值序修复只能仓外核实（仓内证据见
  `2026-10-04-migration-order-ci-red-attribution.md`）。

**需要产品/用户决策**
- webhook 真实回调启用时机：密钥部署链已闭环（`.env` 填 `MULTIPLATFORM_WEBHOOK_SECRET_*`
  即生效，空=回调拒绝），只差平台侧配置与验收。
- `amz_order.coupon_id` 语义悬空：`amz_coupon` 表已删（死表），该列仍留在订单表。
  留着无害（跨库无 FK、无代码读写）；彻底清理属订单域决策，本班未动。

**遗留改进（可做可不做，非阻塞）**
- 多清空者两模式并存：9 视图已用「前缀过滤」（新模式，见 `CustomerService.vue`），
  4 视图（AdBidSchedule/AdSearchTerms/ConnectorQueue/Multiplatform*）仍是
  「`clearErrors` 参数」旧模式——旧模式默认 true，并发 loader 仍可能互擦。
  统一迁到前缀模式即可（一次 commit 的事，回归只跑对应 e2e 套件）。
- 反向尺动态路由（`/orders/:id`）侧边栏可达性只统计不比对（2026-10-04 文档第 4 条）。
- 参数名尺 not-comparable 4 条（函数体内条件拼装）：收它需真数据流分析，
  性价比低，建议接受为永久披露。

## 下一步计划（建议顺序）

1. **例行止损**：整仓 `mvn test`（确认交班 commit 无回归，基线 2002）+ 看一次
   新 CI run 日志（验证本班全部新门禁真实转绿）。
2. 若做多清空者统一：4 视图迁「前缀过滤」，抄 `CustomerService.vue` 的模式，
   **过滤前缀必须与 pushError 的 tag 逐字一致**。
3. 若继续压 A 桶：显式拒绝优于假成功，**不要把任何拒绝改回沉默**（前端假成功
   本班已清零，后端同理）。
4. CI 绿后把 #56 结案记录从「本地复现」升级为「run 日志佐证」。
5. webhook 启用时：先用 demo 档验签回环（签名构造抄
   `MultiplatformWebhookProcessingTest.sign()`），再配真实密钥。
6. report.ts 利润明细现为全量读（后端无分页参数）；若表变大需要分页，先给后端
   加 size/cursor 再恢复 cursor 续读——两处契约要同步改。

## 踩过的坑（勿重演；含本班新增）

**交班自查抓到的三条新坑（最值钱）：**
1. **commit message 声称 ≠ 真改完**：`f081bb4` 写「node 统一升到 22」实际只改了
   release.yml，ci.yml 停在 20，一路无门禁发现——交班前 grep 实测才抓住。已补
   `CiWorkflowContractTest.frontendNodeVersionConsistentAcrossWorkflowsAndDockerfile`
   三方一致锁死（含本坑自证）。教训：**凡 commit 声称「统一/全部/收敛」，
   交班前逐处实测，别信 message。**
2. **契约测试是裸串扫描，禁词写进注释也红**：`|| true` 出现在 release.yml 注释里
   被反掩蔽契约拦下（与 hygiene 拦 md 同款）。给 workflow 加步骤前先看
   `test_release_workflow.py` / `CiWorkflowContractTest` 的禁词清单。
3. **含 `${}` 模板的 Vue 文件禁止自动括号配平批量注入**：给 LogisticsDashboard
   批量插 else 分支时，配平器被 `${action}` 骗过、吞掉了操作处理器的成功分支
   （症状：一处双闭合 `}` + 一个未用函数报 TS6133）。已 `git checkout` 恢复该文件
   （恢复的是本会话自己写坏的，合法例外）再用 Edit 逐处小步重做。
   **改这类文件只用 Edit 工具。**

**历史坑（仍有效）：**
4. 字典序 ≠ 版本号序：复刻别的工具的**机制**（Flyway 按数值序），别照抄 `sorted()`（#56 根因）。
5. 豁免/密钥扫描按文件哈希钉住：改 hygiene-allowlist 覆盖过的文件要重 attest；
   文档里别抄「名字像密钥的赋值」。
6. 注释不是调用；裸 `//` 不是正则字面量；正则字面量 body 用 `+`；注释剥离走两遍法
   （先字符串区间保护、再删区间外注释）。
7. **切片后字符串区间必须重算**（本班踩第三次）：在任何切片上复用母文本的 spans
   都错——参数段静默丢失、门禁假绿。
8. `rg -r` 是**替换标志**：`rg -rn "pattern"` 把输出的命中文本替换成 `n`（本班两次
   被骗：`/n/list`、`request.ln.request`）。要递归+行号用 `rg -n`。
9. 类型注解是键的**上界**不是实发集：共享超类型直接当实发比 = 伪红；按 literal/type
   溯源分桶才诚实；终解是按端点拆窄类型（`b750db6`）。
10. 变异测试必须能红**且先确认基线里目标在可比分母内**：形状尺首轮「0 红 + 变异
    仍绿」= 解析 bug 让目标没进分母的假绿。
11. record 的组件在头部括号里；嵌套类型 声明+类体 整段挖掉再提外层字段；
    DTO 字段索引按类型名不按文件名（`Capability`/`OutboxView` 都是嵌套 record）。
12. vitest 必须在 `amz-frontend/` 里跑：仓库根会捡进 e2e Playwright spec（47 文件
    21 假失败）。修 mvn 后 shell CWD 常被 cd 回根，注意。
13. e2e 并行批偶发单例失败、每批不同、单独跑即过 = 共享 dev server/HMR 抖动；
    复核用 `--workers=1` 串行整批，别归因代码。
14. ad Real 契约测试连自己 127.0.0.1 桩偶发 ConnectException = 本机代理
    （127.0.0.1:7897）瞬时干扰；**先重跑再归因**。
15. mvn 日志是 ISO-8859/CRLF：GNU grep 当二进制静默无输出；汇总用 `rg --text` +
    `^\[INFO\] Tests run:` 行尾锚定（不带 `-- in` 的才是模块行）。
16. **后台 mvn 的完成通知与 /tmp 日志可能截断**：本班一次 /tmp/mvn-cleanup.log 只有
    6752 行、无 BUILD 行，但 stdout 文件里真实结果是 16 模块 2002/0/0——
    **以 exec stdout 文件的 echo 汇总为准，别拿截断日志的 partial 数当基线**。
17. 嵌套 heredoc 补丁是转义雷区：反斜杠转义序列会被外层吃成真实控制字符写进源
    文件（本班写了多处坏文件，含 HANDOFF 一条坑记录自己）。含转义序列的补丁一律
    走 Write/Edit 工具或文件拼接。
18. 一次性 MySQL 前置：官方镜像只给 root@localhost；宿主机连接要显式建应用账号
    + GRANT（IT 自管 `<db>_fwit`/`_bsit` 库）。
19. `<script setup>` 模板里不能写命名空间导入常量（必须具名导入）；同一张横幅不能
    有两个清空者；v-show 面板加新表格要把行定位收窄到具体卡片。
20. 后端定向跑用**逗号**分隔 `-Dtest='A,B'`，从不 `+`；`-q` 会伪装零测试绿。
21. filebeat 的 `include_lines` 过滤的是日志 message 而非容器元数据（元数据是
    `add_docker_metadata` 之后才附加的）——想按容器名过滤要用 autodiscover matcher +
    `drop_event`，用 include_lines 会把所有行丢光。
22. 部署链三张契约（Placeholder/DeploymentManifest/CiWorkflow）是裸 grep + YAML 解析
    风格：**新增任何 env/服务/workflow 步骤必须三处同步**（compose、k8s、双 env 模板、
    契约白名单），漏一处交班 mvn 必红——本班 webhook 密钥就是先只接了 compose。
23. relaxed-binding 属性（`crypto.key`、`multiplatform.webhook.secret.*` 等小写点分名）
    不以 `${大写}` 占位符出现在 yml 里，部署契约按「代码扫描出的大写占位符」推期望
    集合会误判多余——要按模块条件加白名单（`CRYPTO_KEY`/webhook 是仅有的两例）。

## 环境与边界（务必遵守）

- `zc-live-*`（mysql/redis/rabbit）与 `amz-p13-*` 是**别人在跑的栈**：不重启、不改
  Docker 配置/代理、不往演示库写数据。一次性容器用完即 `docker rm -f`，临时口令
  文件随手删。本仓 compose 容器名已全部 `amz-` 前缀（含 filebeat/node-exporter），
  同主机共存不再冲突。
- 不 DROP 业务表——**自 `832a4e5` 起该边界对「逐表核实零引用 + 过索引冻结集契约」
  的死表解除**；未核实的表仍适用原边界。新增 DROP INDEX/DROP TABLE 迁移前必读
  `OrderV4IndexMigrationContractTest` 的冻结集流程。
- 不 `git checkout --` 覆盖未提交内容（一律从 `cp` 备份或内存字节恢复）。
  例外：恢复**本会话自己写坏**的文件（见坑 3）。
- 推送 `master` 属已授权范围。GitHub 直推偶发 `getaddrinfo()`/连接重置，
  等待 ~20s 重试即过，别连珠炮重试。
- 写文档（含本文件）也要过 `repository_hygiene.py --root .` 再提交。
