# HANDOFF — AmazonERP 功能覆盖修复（2026-10-04 交班）

工作树干净，`HEAD = db8346d`，已推送 `origin/master`。

## 一句话现状

覆盖率候选从 **103 → 33**；实体↔列漂移闸门 **101 实体 / 0 漂移 / 0 豁免**；反向接线闸门已进 CI；
悬了两个会话的 **#56（CI `test` 作业红）已结案** —— 根因是 IT 自己按字典序重放迁移，不是 V10。
剩下的 34 条里 **20 条只缺外部凭据**，**13 条是刻意显式拒绝**，库龄分析那条（删假豁免后浮出来的真缺口）已在本班接进海外仓页，所以 A 桶回到 13、B 桶 20。

## 当前任务

用户点名的四项决策已全部落地，任务列表只剩三条开放：

| 任务 | 状态 |
| --- | --- |
| #57 `oauth/token` 的 `appSecret` 走 query → 进访问日志 | **待决策**：需要先确认有无真实外部 ISV 调用方 |
| #60 库龄分析 | **已做完**：接进 `/warehouse` 的「库存查询」面板；顺带修掉后端三处可空列 NPE（见下） |
| 参数名 / DTO 字段位的一致性 | **未量到**：探针伪影占多数，需要真正的签名解析 |
| CI 是否真的转绿 | **未证实**：只能看下一次 run 的日志；本地精确复现已消除 |

## 本会话完成的 8 个 commit

| commit | 内容 | 对基数的影响 |
| --- | --- | --- |
| `fceb157` | ops 三条扫描非 mock 档不再 `return 0`，改显式拒绝 + 定时任务同档跳过 | 条数不变，A 桶语义变好 |
| `3e35304` | 「测试连接」改**真探测**：复用各家已实现的 `fetchRecentOrders`，写 `status` 不写 `lastSyncTime`，亚马逊点名拒绝，运营台接按钮 | 35→34 |
| `5bb4dbe` | **自建下单页** `/b2c-order`；`userId`/`messageId` 不再认请求体；金额与 MQ 入口共用一条判定 | 34→33 |
| `2865f4e` | 消息回复改「先真发、拿到平台 ID 才写本地两行」；三家 Real 无 method 依据一律 `unimplemented` | 条数不变，假成功消失 |
| `ecdbd0c` | 第四轮全量清点 v20 + 新增反向核对尺 | 台账 |
| `08b01a0` | SP-API 别名两处维护的一致性测试（4 项 + 2 条变异） | — |
| `fe617bb` | `--reverse` 反向接线闸门进 CI；删掉一条假 Feign 豁免 | **33→34**（如实上升） |
| `db8346d` | 迁移重放按版本号数值排序，#56 结案 | — |

## 门禁基线与复跑命令

```bash
export PATH="/c/tools/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
# 清点（正向=人读台账，反向=CI 门禁）
python tools/schema/endpoint_coverage_audit.py --self-test .    # 19/19
python tools/schema/endpoint_coverage_audit.py --reverse .      # 0 findings，非 0 即红
python tools/schema/endpoint_coverage_audit.py                  # 候选 34（不阻断）
python tools/schema/entity_column_drift.py --self-test . && python tools/schema/entity_column_drift.py --gate .
python tools/release/repository_hygiene.py --root .             # 以退出码为准，别 grep 文本
python tools/schema/zero_reference_tables.py                    # 113 表 / 11 零引用
```

- 后端定向跑：**逗号**分隔，从不使用 `+`（`-Dtest='A,B'` + `-q` 会伪装成零测试绿）。
- 前端：node 在 `/c/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-3`；
  `npx vue-tsc --noEmit`、`npx vitest run`、`npx playwright test -g "<套件>"`。
  e2e 跑在 5173 上（`webServer` 复用已有服务；跑前先确认该端口没被别人占）。
- 最后一次**整仓** `mvn test`：**1977 tests / 0 failures / 0 errors**，但那是在 `2865f4e` 之后、
  `08b01a0`/`fe617bb`/`db8346d` 之前 —— 下一班第一件事建议先跑一次整仓 `mvn test`。
- 单模块最近数字：multiplatform 94、order 83、ai 124、spapi 675。

## 卡住 / 未解决（按能不能自己推进分类）

**需要外部条件（做不了，别在原地重试）**

- B 桶 20 条：`DEEPSEEK_API_KEY`（4 条 AI）、Keepa token（3 条）、店铺 SP-API 凭证（13 条）。
  代码路径是真代码、未配置时会给点名失败，**没有凭据就只能保持未接**。
- CI `test` 作业是否真的转绿：匿名拉 run 日志是 403，我没有 token，也没有点开会话里明确授权的操作范围之外的登录动作。
  能说的是「同一输入下的精确复现已消除」（修前 `IT_RC=1`，修后 `IT_RC=0`）。

**需要决策（要用户点头）**

- **#57**：`oauth/token` 的 `appSecret` 目前是 `@RequestParam`，会进网关访问日志。
  改传输位需要知道有没有外部 ISV 在调；有 → 要兼容期；没有 → 直接收口。
- **#60**：库龄是接进 `/warehouse` 还是整条摘掉。我已量过它读真实库存表（不是播种数据），倾向接线。

**方法还不可靠**

- 参数名 / DTO 字段位。探针能对上的只有一句：**42/42 个带 `params` 的调用点都能定位到后端同形状同方法的映射**。
  键名比较报的 8 条逐条复查全是伪影（`defaultValue = "14"` 被当参数名、`{ ...q, sku }` 把变量名当键、
  POST body 的键去比 query 参数名）。要做对需要按 Spring 契约取参数名，或引真正的 Java 解析。

## 下一步计划（建议顺序）

1. **整仓 `mvn test`** 确认最新三个 commit 没引入回归（最便宜的止损）。
2. **#60 库龄接线**：`api/logistics.ts`（或 warehouse 侧对应 api 文件）加 `getWarehouseAging`，
   `/warehouse` 加分段面板（≤30/≤90/≤180/D90+ 的 SKU 数与金额），
   必须带上「`days_in_stock` 可能因快照滞后而偏高」的原文口径提示；配 vitest + e2e 桩。
   做完候选回到 33。
3. **#57**：先 grep 外部调用证据（网关访问日志、README、部署文档里的 ISV 说明），再决定改法。
4. 把反向尺的**形状级**核对（桩 data 字段 vs 后端 DTO 字段）做成一把能量化的尺，替掉现在的人工核对。
5. 若继续压 A 桶：先明确一条原则 —— 显式拒绝优于假成功，**不要把拒绝改回沉默**。

## 踩过的坑（本会话新增，勿在下一班重演）

1. **字典序 ≠ 版本号序**：`V10__` 排在 `V1__` 之前（`_`=0x5F > `0`=0x30）。凡是「复刻」别的工具的执行顺序，
   要复刻它的**机制**而不是照抄一个 `sorted()`。这就是 #56 红了两天的全部原因。
2. **豁免按整份文件内容哈希钉住**：改了 `hygiene-allowlist.json` 覆盖过的任何文件（哪怕加一条路由），
   原豁免就失效并重新报 finding。这是设计意图（碰过就要重看），不是误报。
   处理办法：确认那一行确无密钥 → 重新 attest 哈希；**不要放宽规则、不要加跳过名单**。
   副作用：**证据文档本身也在扫描范围内**，不要把那种「名字像密钥的赋值」原样抄进 md ——
   本会话踩了两次：第一次写在证据文档里，第二次写在本文档的警告句里（提交后才被 hygiene 拦下，
   已改写为不含字面量的描述）。
3. **注释不是调用**：反向尺第一版没剥注释，19 条孤儿里 12 条是 doc 注释里的 `**` 通配串；
   `@RequestMapping` 类前缀正则不锚行首时会被 javadoc 举例骗。
4. **提取器的隐性门槛**：强制要求泛型 `request.get<...>()` 会让不带泛型的调用静默不进分母；
   从注解起点找方法名会取到 `PostMapping`，把「零调用」从 2 虚报成 8。任何「0 findings」先问分母对不对。
5. **同一张横幅不能有两个清空者**：新页面的 mount 序列里两个 loader 各自 `errors = []`，
   后一个把前一个刚报的原因擦干净（表现为「点了没反应」）。这次是先写测试才暴露的。
6. **变异注入要能编译**：第一版 M1/M9 锚点写错或把变量移用到声明之前，得到的是「没变异的绿」或编译失败，
   都不算证据。重跑前先确认脚本 `assert count==1` 过了。
7. **一次性 MySQL 的前置条件**：官方镜像只给 `root@localhost`；从宿主机连会拿到
   `Access denied for 'root'@'172.17.0.1'`。要显式建应用账号并 `GRANT ALL ON *.*`（IT 会 DROP/CREATE 自己的 `<db>_fwit`/`_bsit` 库）。
   上一班把这个环境失败写成「V10 嫌疑未洗清」是不严谨的 —— 那是探针没跑起来，不是产品结论。

## 本班最后一轮（#60 库龄接线）新增的事实与坑

- 后端 `MultiWarehouseServiceImpl.agingAnalysis` 的 Top-10 段在**可空列**上会抛 NPE：
  `filter(s -> s.getAvailableQty() > 0)` 与 `comparingInt(getDaysInStock)` 自动拆箱，
  `Map.of("warehouse", name, ...)` 直接拒绝 null 值；而 DDL 里 `available_qty`、
  `days_in_stock`、`warehouse_name` 三列都可空。这条端点在接前端之前从没被浏览器调过，所以没人撞见。
  先写 `WarehouseAgingAnalysisTest` 跑出真实异常（两条红，异常信息由 JVM 指名），再改代码；
  分段与明细现在共用同一套「null 当 0 / 缺失仓库名当空串」口径。
- **`<script setup>` 的模板里不能写 `WH.AGG_BUCKETS`（命名空间导入）**：渲染时抛
  "Cannot read properties of undefined"，而 vue-tsc 完全不报 —— 只有跑起来才红。
  要在模板里用的常量必须具名导入。
- **清点工具现在会拒绝错误的 root**：在 `amz-frontend/` 里跑它会安静输出
  `controllers=0 / candidates=0`，看着像全绿。现在打印 `ROOT NOT A REPO` 并退出码 2。
  任何「0 findings」先确认分母非空。
- 面板是 `v-show` 同时挂着的：给面板加新表格时必须同时把行定位从
  `.panel:visible .data-table tbody tr` 收窄到具体卡片，否则会把别人在跑的断言一起改掉。
- 我在本文档里写「不要把那种赋值抄进 md」时，就把那条赋值原样写进去了，
  被自家 hygiene 拦下（`b965c34` 推上去时 hygiene 是红的，本次改写后才绿）。
  **教训：写文档也要过一遍 `repository_hygiene.py --root .` 再提交。**

## 环境与边界（务必遵守）

- `zc-live-*`（mysql/redis/rabbit）与 `amz-p13-*` 是**别人在跑的栈**：不重启、不改 Docker 配置/代理、
  不往演示库写数据。一次性容器用完即 `docker rm -f`，临时口令文件随手删。
- 不 DROP 业务表；不 `git checkout --` 覆盖未提交内容（一律从 `cp` 备份或内存字节恢复）。
- 推送 `master` 属已授权范围；本会话所有 commit 都已推送。GitHub 直推偶发
  `getaddrinfo() thread failed to start`，重试一次即过。
