# HANDOFF — AmazonERP 止损清账 + 真实数据填充 + webhook 回环验收（2026-10-07 交班）

工作树以本文件随交班 commit 推送为准，HEAD 以 `git log` 为准；全部已推送 `origin/master`。

## 一句话现状

本班从**例行止损**开始（上一班交班后跑整仓回归 + 查真 CI run，抓到 `3fd1b12` 推上去的
**run #195 红**：hygiene job「Release tool tests」迁移清点钉数
50 被 `832a4e5`/`6590ca4`/`6a7473c` 的 +8 个迁移文件打破），修复并把同类「交班漏同步数字」
一次性清账：runbook/README/example manifest 的 113 表、90 行种子、225,734 行等死表清理前
口径全部刷新，真机导入链在一次性容器上重跑出实测数。**追红连环**：#196（含钉数修复）hygiene
转绿后 docker 首跑即红（`buildx bake` 不存在 `--dry-run` 标志，改 `--print`）→
**run #197 全绿（11/11 job success）——本仓有 CI 记录以来第一次远端全绿**，#56 结案升级为
run 元数据佐证。随后按用户指令继续三块：**完整性 review**（活数字全量复核，抓到 README
2002/「20/20 模块」两处残留 → `0e93f51`）、**真实数据模拟填充**（`2d8aeec`/`bfd03fb`，
调研 SP-API Orders 官方样例 + Keepa 文档后改造生成器与 mock 样例，run #199/#200/#201
三绿佐证）、**用户拍板落地**（webhook demo 档回环验收 `9427e1a`——抓到白名单缺项与
异常兜底两个部署形态缺陷；coupon_id 维持现状；GH_TOKEN 待用户本人配置）。续做清单：
多清空者统一（`e39dafd`）、动态路由可达性比对（`bdeb2af`）、利润明细分页（见 commit 表）。
基线：整仓
`mvn test` **2012 / 0F / 0E / 17S**
（2004 + 利润明细分页契约 5；班初 2003 = 上班 2002 + `3fd1b12` spapi node 契约 1）、
四把尺 + 漂移 + hygiene 全零、release-tools unittest **88/0F**、部署链三契约 17/17。
覆盖率候选稳定 **33**（B 桶 20 缺凭据 / A 桶 13 刻意拒绝）。**无已知死代码。**
本班另完成用户点名的**真实数据模拟填充**（`2d8aeec`）：演示库的商品标题/ASIN/订单号/
价格/成本/竞品数据全部改为真实亚马逊形态（调研自 SP-API Orders 官方样例与 Keepa 文档），
识别机制（SYN 标题前缀、ID 段、登记表）完整保留——**B 桶凭据仍是真代码+未接状态不变**，
但缺凭据时的演示数据已从「SYN 占位」升级为「真实形态+可识别」。

## 本班任务与落地状态

| 任务 | 状态 |
| --- | --- |
| 例行止损：整仓 mvn test 确认交班 commit 无回归 | ✅ 2003/0/0/17，BUILD SUCCESS（16 测试模块全绿） |
| 例行止损：看一次新 CI run 日志验证新门禁 | ✅ **匿名 API 现可读 run 状态**（gh 仍无认证、日志 zip 仍 403）；run #195 抓到 1 真红 → 已修（见下） |
| 用户点名：真实数据模拟填充（上网调研） | ✅ `2d8aeec`（见本节 commit 表）；商品/订单/竞品域按 SP-API Orders 官方样例 + Keepa 文档的真实形态生成，演示库数据不再是纯 SYN 占位 |
| run #195 红修复：迁移清点钉数 50→58 | ✅ `ce8a360`；钉数锁步范围写进注释；ci.yml 三处过时口径同步；**run #196 hygiene 转绿确认** |
| run #196 追红：docker `Dry-run bake` 用了不存在的 `--dry-run` 标志 | ✅ `89c10f3` 改 `--print`（本地实测 exit 0；该步骤被 hygiene 恒红连带 skip 从未执行过——坑 30） |
| 下一步计划 #1 后半段：#56 结案升级「run 日志佐证」 | ✅ **run #197 全绿（11/11）**，归因文档追加「结案升级」段；取证改用匿名 run/job 元数据 API（日志 zip 仍 403） |
| 合成数据口径随死表清理清账（runbook/README/example） | ✅ `1d91183`；113→102、90→87 种子（归因 `amz_report_template` 3 行随 V3 DROP）、225,734→224,993（demo）/25,484→25,188（ci）；真机链一次性容器重跑实测 ci 25,275/25,275、demo 225,080/225,080 |
| 完整性 review（用户指令）：活数字全量复核 | ✅ 本班实测全部吻合；抓到 README 2002→2003、「20/20 模块」→19/19 reactor 两处残留（`0e93f51`） |
| webhook demo 档回环验收（用户拍板 #4-b） | ✅ `9427e1a`；真 HTTP 五场景全过（正确签名→落库 PROCESSED、错签/未配平台/缺签名头→点名拒绝不落库、幂等重发→仅一条）；抓到并修复两个单测结构测不到的部署形态缺陷（坑 31） |
| 待用户操作的拍板项 | ✅ #5 coupon_id 维持现状（拍板记录进决策区）；⏳ #2 GH_TOKEN——用户拍板「可以做」，但 token 须用户本人生成并配置（本机实测尚未就位，操作指引已写入卡住区） |
| 逐项 1-9（用户指令「逐项完成」）：#7 利润明细分页 | ✅ `bbbbef8`（profit）+ `0eefe6f`（其余三个 /v2 列表收口）；无界全量读改 keyset 分页，前后端契约同步 + 后端 8 例分页契约（四端点方向分别钉死）+ 视图三表转 CursorList（整仓 2012、vitest 481）|
| 逐项 1-9：#8 动态路由可达性比对 | ✅ `bdeb2af`；反向尺从「只统计」升级为「比对」，变异验证能红不误报 |
| 逐项 1-9：#9 参数名尺 not-comparable | ✅ 实测已归零（`b750db6` 拆窄类型后六桶全零），HANDOFF「4 条永久披露」表述过时，已更正 |
| 逐项 1-9：#1/#2/#3 需外部条件/用户操作 | #1 B 桶凭据（用户拍板暂缓，技术侧无阻塞）；#2 GH_TOKEN（待用户本人生成配置，指引在卡住区）；#3 deploy-it 仓外（全盘搜索未找到脚本，仓内排序契约已锁死，维持被动）——三项均非代码可推进，如实留档 |
| 多清空者统一（遗留改进 #6，按推荐当班执行） | ✅ `e39dafd`；4 视图迁前缀过滤模式、clearErrors 全仓归零；vue-tsc/vitest/e2e 全链复核（3 超时失败隔离重跑全过=坑 13） |

## 本班 commit 分组

| 主题 | commits | 要点 |
| --- | --- | --- |
| CI 真红修复 | `ce8a360` | `test_current_flyway_inventory_contains_all_50_files` 钉数 50→58（死表 DROP +5、日期收敛 +2、宽度收敛 +1 打破了它）；ci.yml test 注释/mysql-import 注释/step 名三处 49/50→58；方法名+两处断言+锁步注释同改 |
| CI 真红修复（追红） | `89c10f3` | docker job `Dry-run bake` 的 `buildx bake --dry-run` → `--print`（bake 无 --dry-run 标志；needs hygiene 被连带 skip 所以从未执行，run #196 转绿后首跑即红）；`#56 结案升级`：run #197 全绿佐证写入归因文档 |
| 文档清账 | `1d91183` | mock-data runbook 全篇 113→102、§8 真机数字换成本班重跑实测（含种子 90→87 归因说明、历史轮次记录保留当时口径的注记）；README 工具链表 6 行数字清账；`docs/examples/release-manifest.example.json` 按 phase0 plan 命令重生成（49→58 迁移 / 75→132 前端文件，不被测试消费、纯示例，故无门禁拦它） |
| README 数字复核 | `0e93f51` | README 测试表 2002→2003（上班口径未折 spapi +1）、「20/20 模块」→19/19 reactor（与 Reactor Summary 实测一致） |
| 真实数据模拟填充（用户点名） | `2d8aeec` | `PRODUCT_CATALOG` 24 个真实亚马逊类目（Electronics/Home & Kitchen/…真实 Listing 风格标题+真实价格带 7.99-149.99，品牌虚构不冒充商标）；ASIN 真格式 `B0+8 位`（原 B0SYN00001）；订单号真形态 3-10-7（原 S001-…）；quantity/final_price/item_price/tax(7.25%)/促销与商品价自洽；采购成本=售价×28-44%；竞品/BuyBox 价格扰动+bs_rank/review 真实区间。**识别机制不变**（标题 'SYN ' 前缀过 markers、ID 段/登记表全保留）。真实形态来源：SP-API Orders 官方模型样例 + Keepa product-object 文档。行数不变（demo 224,993 / ci 25,188） |
| mock 财务样例真化 | `bfd03fb` | FinancesMockClient/ReportsMockClient 的 SKU-ALPHA 等占位 → 与生成器同风格（SYN-ELE-0101/合法 ASIN 字母表/3-10-7 订单号）；形状断言同步；mock profile fail-closed 边界不变。run #199（0e93f51）/#200（503de93，真机 MySQL 接受全部真实形态数据）/#201（bfd03fb）连续三绿 |
| webhook demo 档回环验收（用户拍板 #4-b） | `9427e1a` | 一次性容器起真服务跑五场景 HTTP 回环（签名构造同 `MultiplatformWebhookProcessingTest.sign()`），抓到并修复两个部署形态缺陷：① `/multiplatform/webhook` 无 JWT 被服务层 401（白名单两侧同步放行，过 parity 契约）；②「平台账号未录入」抛 IllegalStateException 兜底成 500（改 CodeErrorException 点名原因）。终态整仓 2004/0/0/17 |
| 多清空者统一（遗留改进 #6） | `e39dafd` | 4 视图（AdSearchTerms/ConnectorQueue/MultiplatformOrders/MultiplatformOps）迁前缀过滤模式，`clearErrors` 全仓归零；AdBidSchedule 核对早已是新（清单一处过时已更正）；回归 vue-tsc 0 / vitest 479 / e2e 101 串行（3 超时隔离重跑全过=坑 13） |
| 动态路由可达性比对（遗留改进 #8） | `bdeb2af` | `--reverse` 新增 `dynamic-route-unreachable` 闸门（详情页经列表页前缀判可达、catch-all 不参与、nav_dead 升级为可命中动态模式）；self-test 19→25；变异验证注入 `/ghost-page/:id` 点名红、`/orders/:id` 不误报 |
| 利润明细 keyset 分页（计划 #5） | `bbbbef8` | `/report/v2/profit/list` 无界全量读改 size/cursor，复刻快照列表 (report_date,id) 复合游标 + 探测行 + 非法游标 fail-closed；前后端三处契约同步（Controller Result.paged / api q 类型 / 视图 loadProfitDetails append + 下一页）；新增 ReportUpgradeProfitPagingTest 5 例、视图契约测试翻转（vitest 479→480） |
| /v2 四列表分页统一收口（#5 遗留观察项） | `0eefe6f` | inventory-turnover/sales-daily/business-overview 一并改 keyset 分页；游标辅助函数泛化（dateIdCursor/decodeDateIdCursor 四端点共用）；sales-daily 升序取 `report_date >` 下界（唯一方向差异，单列测试钉死）；契约测试更名 ReportUpgradeListPagingTest（5→8 例、report 71→74）；前端三表转 CursorList（裸 ref 数组→makeList+loadList 累积、truncated 出下一页）、周转 KPI「本页合计」→「已加载合计」；listTurnover 调用形状 (shopId,asin)→(shopId,q) 翻转旧断言同步（vitest 480→481、整仓 2009→2012） |

## 门禁基线与复跑命令

```bash
export PATH="/c/tools/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
# 清点（正向=人读台账，不阻断）
python tools/schema/endpoint_coverage_audit.py                    # 候选 33
# 四把尺（CI 门禁：self-test + 闸门；任何「0 findings」先核对分母非空）
python tools/schema/endpoint_coverage_audit.py --self-test .      # 25/25（含动态路由比对 6 例）；--reverse 0 findings
python tools/schema/stub_shape_audit.py --self-test .             # 20/20；闸门 0（140 注册 / 106 可比）
python tools/schema/param_name_audit.py --self-test .             # 24/24；闸门 0（163 调用点全核验）
python tools/schema/entity_column_drift.py --gate .               # 101 实体 / 0 漂移
python tools/release/repository_hygiene.py --root .               # 以退出码为准，别 grep 文本
python tools/schema/zero_reference_tables.py                      # 102 表 / 0 零引用
# 【本班新增·必跑】ci.yml hygiene job 的 Release tool tests 逐字复跑——
# 上一班清单漏了这串，run #195 因此红：
python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest tools.release.test_release_workflow tools.release.test_rollback_drill tools.release.test_cve_gate tools.release.test_verify_clean_clone   # 88/0F
# 部署链三契约（改 workflow/compose/k8s/env 模板前后必跑，含 node 版本三方一致）
mvn -pl amz-service/amz-service-spapi -o test -Dtest='CiWorkflowContractTest,PlaceholderCoverageContractTest,DeploymentManifestContractTest'
# synthetic-data（改 DDL/迁移后必跑；工作目录 tools/synthetic-data）
python tools/synthetic-data/snapshot_schema.py --check            # 102 表基线
cd tools/synthetic-data && python generate.py --tier ci --reset --out out/ci && python verify.py --tier ci --dataset out/ci
# 真机口径变了才需重跑：起一次性容器全链见 runbook §8（docker exec 包装法见本班坑 4）
# 前端：node 在 /c/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-3；
# vue-tsc --noEmit、vitest run（必须在 amz-frontend/ 里跑）、playwright --workers=1。
```

- 基线：整仓 **2012/0/0/17**（16 测试模块求和实测；班初 2003 含 `3fd1b12` 的 spapi
  node 契约 +1、webhook 归属拒绝 +1 = 2004，利润明细分页契约 +5 = 2009，四列表收口 +3 = 2012）；
  spapi 676/0F、multiplatform 101、logistics 153、customer 33、ops 36、user 19、report 74；
  vitest **481/481**（利润契约翻转 +1、周转下一页 +1）、e2e 串行 40-44 全过；vue-tsc 0 错（tsconfig 开
  `noUnusedLocals`——前端孤儿 import 编译期即红，历轮 0 错即无孤儿之证）。
- 合成数据口径（本班 2026-10-06 全链重跑实测）：快照 102 表 / 14 库；demo 224,993 行、
  ci 25,188 行；purge/verify_cleanup 102 DELETE；schema-load 灌入 0 错误、notes 386,231/209 列；
  真机一次性容器（mysql:8.0→8.0.46）：58 迁移全过、种子基线 **87 行/15 表**、ci 25,275/25,275、
  demo 225,080/225,080、cleanup 回基线。
- 后端定向跑：**逗号**分隔 `-Dtest='A,B'`，从不 `+`；`-q` 会伪装零测试绿。

## 卡住 / 未解决（按能不能自己推进分类）

**需要外部条件（做不了，别在原地重试）**
- B 桶 20 条：`DEEPSEEK_API_KEY`（4）、Keepa token（3）、店铺 SP-API 凭证（13）。
  代码路径是真代码、未配置即点名失败，没有凭据只能保持未接。
- CI run **结论可匿名读**：run/job 元数据 API 本班实测可用（`GET /repos/.../actions/runs`
  与 `/runs/<id>/jobs`，200，能拿 conclusion、失败 job 名、失败 step 名）；**但 job 日志
  zip 仍 403**、gh 仍未认证。定位流程 = run→失败 job/step→ci.yml 找 run 行→本地逐字复现，
  本班走通两次（#195 钉数、#196 bake 标志）。#197 已全绿，#56 结案升级完成。
  **2026-10-07 用户拍板「GH_TOKEN 可以做」，但 token 值只能用户本人生成**（本机实测
  GH_TOKEN 尚未配置）：用户操作 = GitHub → Settings → Developer settings → Personal
  access tokens（fine-grained，cgs123456/AmazonERP 只读）→ 生成后设为 Windows 用户级
  环境变量 `GH_TOKEN`（别贴进对话）。配好后下一班即可直接拉日志与 artifact，CI 定位
  从「元数据+复现」升级为「日志直读」；用户说一声「检查 token」即可验证。
- deploy-it 脚本在仓库外：迁移数值序修复只能仓外核实（仓内证据见
  `2026-10-04-migration-order-ci-red-attribution.md`）。**2026-10-07 全盘搜索**
  （Desktop/c/tools/d 盘 maxdepth 4，含 `*deploy*it*`/`*.sh` 通配）本机未找到该脚本——
  「被动处理」维持不变；核心排序逻辑已被仓内 `MigrationOrderingContractTest`（3 项，
  变异验证可红）锁死，脚本本身腐化无自动防线，但也没有自动修复的必要。

**需要产品/用户决策**
- ~~webhook 真实回调启用时机~~ **本班已拍板并完成 demo 档验收（`9427e1a`）**：一次性容器
  起真服务跑通五场景回环（正确签名→落库 PROCESSED / 错签 / 未配平台 / 缺签名头→点名拒绝
  不落库 / 幂等重发→仅一条）。抓到并修复两个部署形态缺陷：`/multiplatform/webhook`
  未入免鉴权白名单（平台回调无 JWT，被服务层 401 永远走不到验签层，两侧同步放行过 parity
  契约）、「平台账号未录入」抛 IllegalStateException 被兜底成「服务器内部错误」（改
  CodeErrorException 点名原因）。**真实平台侧配置**仍待业务：各平台后台配回调 URL +
  `.env` 填对应 `MULTIPLATFORM_WEBHOOK_SECRET_*` 即生效；启用前记得先录入
  `amz_platform_account`（否则回环验收同款「未录入店铺账号」点名拒绝，属预期）。
- `amz_order.coupon_id` 语义悬空：`amz_coupon` 表已删（死表），该列仍留在订单表。
  留着无害（跨库无 FK、无代码读写）；**2026-10-07 用户拍板维持现状**，彻底清理待优惠券
  功能立项时一并处置。

**遗留改进（可做可不做，非阻塞）**
- ~~多清空者两模式并存~~ **已完成（`e39dafd`，2026-10-07）**：AdSearchTerms /
  ConnectorQueue / MultiplatformOrders / MultiplatformOps 四视图迁到「前缀过滤」新模式
  （清单里原列的 AdBidSchedule 核对后发现早已是新模式——清单一处过时，一并更正）；
  `clearErrors` 全仓归零，「删除/探测后重拉列表保住紧随其后的警告」从手工传参变成
  前缀隔离结构性保证。回归：vue-tsc 0、vitest 479/479、e2e --workers=1 串行 101 用例
  （3 个超时失败逐一隔离重跑全过=坑 13 dev server 抖动）。
- ~~反向尺动态路由（`/orders/:id`）侧边栏可达性只统计不比对~~ **已完成（2026-10-07，见下）**：
  `--reverse` 新增 `dynamic-route-unreachable` 闸门——动态路由可达 = 某导航项能按段匹配
  （`:param` 吃一个非空段）或列表页前缀（首参段之前）在侧边栏；catch-all 不参与。
  变异验证：注入 `/ghost-page/:id` 点名红、`/orders/:id`（经 /orders）不误报；
  当前路由表无真动态路由（dynamic=0），闸门空转待命。self-test 19→25。
- ~~参数名尺 not-comparable 4 条~~ **已过时（2026-10-07 实测归零）**：`b750db6` 拆窄类型后
  六桶全零（实测 `not-comparable=0`），「建议接受为永久披露」随之作废——若未来重新出现
  （新调用点用条件拼装传参），按披露处理不阻断，但先核实现状再写文档。

## 下一步计划（建议顺序）

1. **例行止损闭环（已完成）**：整仓回归 2003/0/0/17、#195/#196 两个红修复、
   #197 全绿、#56 结案升级；webhook demo 档回环验收完成（`9427e1a`，终态 2004/0/0/17）。
   下一班接手时 **CI 基线是绿**，先确认后续 run 仍绿即可。
2. ~~若做多清空者统一~~ **已完成（`e39dafd`）**：4 视图迁前缀模式，`clearErrors` 归零。
   新模式要点（未来新列表照抄）：loader 非 append 时 `errors.value.filter(x =>
   !x.startsWith(\`label：\`))`，**过滤前缀必须与 pushError 的 tag 逐字一致**；
   整页入口（gotoTab/onMounted）`errors.value = []` 清一次。
3. 若继续压 A 桶：显式拒绝优于假成功，**不要把任何拒绝改回沉默**（前端假成功
   上班已清零，后端同理）。
4. ~~webhook 启用时：先用 demo 档验签回环~~ **已完成（`9427e1a`，见决策区）**；真实平台
   侧配置时先录入 `amz_platform_account`，再各平台后台配回调 URL + `.env` 填密钥即生效。
5. ~~report.ts 利润明细现为全量读（后端无分页参数）；若表变大需要分页，先给后端
   加 size/cursor 再恢复 cursor 续读——两处契约要同步改。~~ **已完成（`bbbbef8`）**：
   后端加 size/cursor 走 `Result.paged`（(report_date,id) 复合游标，复刻快照口径）、
   前端 `loadProfitDetails` 恢复 append 续读 + truncated 出下一页、契约测试同步。
   同域 `/report/v2` 其余三个列表端点（inventory-turnover/sales-daily/business-overview）
   **已一并收口（`0eefe6f`）**——四端点共用 (report_date,id) 载荷与 decode，
   sales-daily 是唯一升序列表（游标取 `report_date >` 下界，方向已分别钉死）；
   周转 KPI「本页合计」文案改「已加载合计」，防止分页后还拿已读页求全量。
   `/report/v2` 全部列表端点已无界读清零。

## 踩过的坑（勿重演；含本班新增）

**本班新坑：**
1. **「统一/清账」类 commit 改一半的第二个实锤（钉数型）**：`c2993ee` 声称
   「README/HANDOFF 数字清账（113→102）」，实际漏了 mock-data runbook 全篇、README 工具链表
   6 行、example manifest——而且**随死表清理变化的不止表数，还有合成行数（225,734→224,993）、
   种子基线（90→87）、schema-load notes**。数字清账必须「全仓 grep 旧数」收尾，
   且行数口径的联动项（行数/DELETE 条数/notes 计数）都在范围内。**凡「统一/全部/收敛」
   的 commit，交班前把该数字的全部派生口径逐类实测**（本文件坑 7「commit message
   声称 ≠ 真改完」的推广）。
2. **跑工具链前先确认读的是哪份产物**：purge/verify_cleanup/verify_schema_load 默认读
   `out/<tier>`，我 `generate --out out/demo-shift` 后忘传 `--dataset`，验证器读的是
   上一班陈旧的 `out/demo`（死表清理前的 113 表数据），冒出 25 行 `SYN-eta` 假违规——
   差点误判「日期收敛把生成器写坏了」。跑任何 verify 前核对数据集新鲜度与目录，
   或干脆先 `rm -rf out/<tier>` 再链式跑。
3. **匿名 GitHub Actions API 可读 run/job 元数据，但日志仍 403**：本班实测
   `GET /repos/.../actions/runs?per_page` 与 `/runs/<id>/jobs` 均 200（能拿 conclusion、
   失败 job 名、失败 step 名），`/jobs/<id>/logs` 403。所以「CI 是否绿、红在哪个 job/step」
   无需 gh 认证即可定位，剩下的红靠本地逐字复现那一步。上班「CI 外部不可验证」的口径
   收窄为「日志细节不可验证」。
4. **本机宿主机无 mysql.exe 也能跑真机导入链**：`load.sh` 自带 `--container` 走
   `docker exec`；`verify_import.py`/`apply_migrations.py` 的 `--mysql-path` 是
   `subprocess` 直调（Windows Git Bash 的 `/tmp` POSIX 脚本会 WinError 193），
   用 Write 工具写一个 `C:\...\Temp\mysqlw.cmd`（`docker exec -i -e MYSQL_PWD=... amz-mysqlshift mysql %*`，
   **口令硬编码进文件、跑完即删**），传 cygpath -w 的 Windows 路径即可。容器起法见
   坑 24（官方镜像 root 只认 localhost，`--mysql-path` 的 host/port 会打进容器内
   `mysql %*`，所以传 `-h 127.0.0.1 -P 3306` 是容器内回环，天然可用）；用完 `docker rm -f`。
5. **mvn 日志里 CRLF 与 `$` 锚定**：本文件坑 21（ISO-8859/CRLF）的变体——`^\[INFO\] Tests run:\d+...Skipped: \d+$`
   在 `rg --text` 下**匹配 0 行**（`\r` 挡住行尾锚），必须显式带 `\r$` 或 `tr -d '\r'`
   再求和。本班据此实测 2003/0/0/17（比上班记录多 1 = `3fd1b12` 的 spapi node 契约方法，
   上班 HANDOFF 的「2002」本身没把这次新增折进去）。
6. **git bash python 读不了 `/tmp`**：Git Bash 的 `/tmp` 对 Windows 原生 python 不存在
   （FileNotFoundError），`msys` 路径只在 bash 内有效。脚本要用 `/c/...` 或环境变量传路径。

**历史坑（仍有效；序号顺延，坑 7 起为上班新坑）：**
7. **commit message 声称 ≠ 真改完**：`f081bb4` 写「node 统一升到 22」实际只改了
   release.yml，ci.yml 停在 20，一路无门禁发现——交班前 grep 实测才抓住。已补
   `CiWorkflowContractTest.frontendNodeVersionConsistentAcrossWorkflowsAndDockerfile`
   三方一致锁死（含本坑自证）。教训：**凡 commit 声称「统一/全部/收敛」，
   交班前逐处实测，别信 message。**
8. **契约测试是裸串扫描，禁词写进注释也红**：`|| true` 出现在 release.yml 注释里
   被反掩蔽契约拦下（与 hygiene 拦 md 同款）。给 workflow 加步骤前先看
   `test_release_workflow.py` / `CiWorkflowContractTest` 的禁词清单。
9. **含 `${}` 模板的 Vue 文件禁止自动括号配平批量注入**：给 LogisticsDashboard
   批量插 else 分支时，配平器被 `${action}` 骗过、吞掉了操作处理器的成功分支
   （症状：一处双闭合 `}` + 一个未用函数报 TS6133）。已 `git checkout` 恢复该文件
   （恢复的是当事会话自己写坏的，合法例外）再用 Edit 逐处小步重做。
   **改这类文件只用 Edit 工具。**

**历史坑（仍有效；序号顺延，坑 10 起为既有累积坑）：**
10. 字典序 ≠ 版本号序：复刻别的工具的**机制**（Flyway 按数值序），别照抄 `sorted()`（#56 根因）。
11. 豁免/密钥扫描按文件哈希钉住：改 hygiene-allowlist 覆盖过的文件要重 attest；
    文档里别抄「名字像密钥的赋值」。
12. 注释不是调用；裸 `//` 不是正则字面量；正则字面量 body 用 `+`；注释剥离走两遍法
    （先字符串区间保护、再删区间外注释）。
13. **切片后字符串区间必须重算**（上班踩第三次）：在任何切片上复用母文本的 spans
    都错——参数段静默丢失、门禁假绿。
14. `rg -r` 是**替换标志**：`rg -rn "pattern"` 把输出的命中文本替换成 `n`（上班两次
    被骗：`/n/list`、`request.ln.request`）。要递归+行号用 `rg -n`。
15. 类型注解是键的**上界**不是实发集：共享超类型直接当实发比 = 伪红；按 literal/type
    溯源分桶才诚实；终解是按端点拆窄类型（`b750db6`）。
16. 变异测试必须能红**且先确认基线里目标在可比分母内**：形状尺首轮「0 红 + 变异
    仍绿」= 解析 bug 让目标没进分母的假绿。
17. record 的组件在头部括号里；嵌套类型 声明+类体 整段挖掉再提外层字段；
    DTO 字段索引按类型名不按文件名（`Capability`/`OutboxView` 都是嵌套 record）。
18. vitest 必须在 `amz-frontend/` 里跑：仓库根会捡进 e2e Playwright spec（47 文件
    21 假失败）。修 mvn 后 shell CWD 常被 cd 回根，注意。
19. e2e 并行批偶发单例失败、每批不同、单独跑即过 = 共享 dev server/HMR 抖动；
    复核用 `--workers=1` 串行整批，别归因代码。
20. ad Real 契约测试连自己 127.0.0.1 桩偶发 ConnectException = 本机代理
    （127.0.0.1:7897）瞬时干扰；**先重跑再归因**。
21. mvn 日志是 ISO-8859/CRLF：GNU grep 当二进制静默无输出；汇总用 `rg --text` +
    `^\[INFO\] Tests run:` 行尾锚定（不带 `-- in` 的才是模块行）。
22. **后台 mvn 的完成通知与 /tmp 日志可能截断**：上班一次 /tmp/mvn-cleanup.log 只有
    6752 行、无 BUILD 行，但 stdout 文件里真实结果是 16 模块 2002/0/0——
    **以 exec stdout 文件的 echo 汇总为准，别拿截断日志的 partial 数当基线**。
23. 嵌套 heredoc 补丁是转义雷区：反斜杠转义序列会被外层吃成真实控制字符写进源
    文件（上班写了多处坏文件，含 HANDOFF 一条坑记录自己）。含转义序列的补丁一律
    走 Write/Edit 工具或文件拼接。
24. 一次性 MySQL 前置：官方镜像只给 root@localhost；宿主机连接要显式建应用账号
    + GRANT（IT 自管 `<db>_fwit`/`_bsit` 库）。
25. `<script setup>` 模板里不能写命名空间导入常量（必须具名导入）；同一张横幅不能
    有两个清空者；v-show 面板加新表格要把行定位收窄到具体卡片。
26. 后端定向跑用**逗号**分隔 `-Dtest='A,B'`，从不 `+`；`-q` 会伪装零测试绿。
27. filebeat 的 `include_lines` 过滤的是日志 message 而非容器元数据（元数据是
    `add_docker_metadata` 之后才附加的）——想按容器名过滤要用 autodiscover matcher +
    `drop_event`，用 include_lines 会把所有行丢光。
28. 部署链三张契约（Placeholder/DeploymentManifest/CiWorkflow）是裸 grep + YAML 解析
    风格：**新增任何 env/服务/workflow 步骤必须三处同步**（compose、k8s、双 env 模板、
    契约白名单），漏一处交班 mvn 必红——上班 webhook 密钥就是先只接了 compose。
29. relaxed-binding 属性（`crypto.key`、`multiplatform.webhook.secret.*` 等小写点分名）
    不以 `${大写}` 占位符出现在 yml 里，部署契约按「代码扫描出的大写占位符」推期望
    集合会误判多余——要按模块条件加白名单（`CRYPTO_KEY`/webhook 是仅有的两例）。
30. **needs 被连带 skip 的 job 会藏红**（本班，run #194→#197 连环实测）：ci.yml docker job
    `needs` 含 hygiene，hygiene 自 #194 恒红后 docker 被整体 skip——`Dry-run bake` 步骤
    用的 `buildx bake --dry-run` 根本不存在（bake 无此标志，正确是 `--print`），一路「绿」了
    4 个 run 无人知晓，直到 #196 hygiene 修绿、docker 第一次真执行才现形。教训：
    **门禁链里「从未执行过」的步骤等于「没有验证」**——新增 workflow 步骤当班必须至少本地
    跑一次原命令（`docker buildx bake --help` 一行就能戳穿标志不存在）；排查 CI 红时也要
    把「被 skip 的下游 job」算进未验证面。
31. **直调 service 层的单测探不到鉴权与异常兜底层**（2026-10-07 webhook 回环实测）：
    `MultiplatformWebhookProcessingTest` 五场景全绿，但真 HTTP 回环抓到两个单测结构上
    测不到的缺陷——拦截器 401（`/multiplatform/webhook` 不在白名单，请求根本没到
    service）与 IllegalStateException 被全局处理器兜底成「服务器内部错误」（service
    内抛什么异常类型，单测只看抛没抛、不看 HTTP 响应呈现）。教训：**凡「外部系统会真打
    进来」的端点（webhook/回调/开放 API），验收必须走一次真 HTTP 链路**，白名单与异常
    分类只有那条路径能覆盖。
32. **relaxed-binding 密钥 env 名要按属性名推，别按注释里的别名**（坑 29 的实锤）：
    服务注释与文档多写 `AMZ_CRYPTO_KEY`，但 `crypto.key` 属性对应的 env 实为
    `CRYPTO_KEY`（我传 `AMZ_CRYPTO_KEY` 启动直接被 `IllegalStateException: crypto.key
    未配置` 拒启）。`multiplatform.webhook.secret.temu` → `MULTIPLATFORM_WEBHOOK_SECRET_TEMU`
    同理（点与下划线互换）。起服务前对着 yml 属性名推 env 名，别信记忆里的部署别名。
    另：无 Redis 时 `/actuator/health` 显 DOWN 但业务链路照常工作——回环验收按端点行为判定，
    别被探针误导。

## 环境与边界（务必遵守）

- `zc-live-*`（mysql/redis/rabbit）与 `amz-p13-*` 是**别人在跑的栈**：不重启、不改
  Docker 配置/代理、不往演示库写数据。一次性容器用完即 `docker rm -f`，临时口令
  文件随手删（本班 `amz-mysqlshift` 与 `mysqlw.cmd`/`mysqlw` 已删）。本仓 compose
  容器名已全部 `amz-` 前缀（含 filebeat/node-exporter），同主机共存不再冲突。
- 不 DROP 业务表——**自 `832a4e5` 起该边界对「逐表核实零引用 + 过索引冻结集契约」
  的死表解除**；未核实的表仍适用原边界。新增 DROP INDEX/DROP TABLE 迁移前必读
  `OrderV4IndexMigrationContractTest` 的冻结集流程。
- 不 `git checkout --` 覆盖未提交内容（一律从 `cp` 备份或内存字节恢复）。
  例外：恢复**本会话自己写坏**的文件（见坑 9）。
- 推送 `master` 属已授权范围。GitHub 直推偶发 `getaddrinfo()`/连接重置，
  等待 ~20s 重试即过，别连珠炮重试。
- 写文档（含本文件）也要过 `repository_hygiene.py --root .` 再提交。
- 交班必跑清单**新增一项**：ci.yml hygiene job 的 release-tools unittest 逐字串
  （本班 run #195 教训——上班清单缺它，四把尺+三契约全绿仍被真 CI 抓红）。
