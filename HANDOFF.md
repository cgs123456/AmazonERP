# HANDOFF — AmazonERP 功能覆盖修复（2026-10-05 交班）

工作树干净，`HEAD = 57e79d3`，已推送 `origin/master`（本文件在其后单独提交）。

## 一句话现状

覆盖率候选稳定在 **33**（20 条只缺外部凭据、13 条刻意显式拒绝）；实体↔列漂移闸门
**101 实体 / 0 漂移**；反向接线闸门 + **新增形状级闸门**（e2e 桩字段 vs 后端 DTO 字段）
都已进 CI；**#57 已收口**（`oauth/token` 密钥挪进 JSON 请求体）；
形状尺首轮就抓到一条真漂移（桩写 `field`、后端 DTO 是 `fieldName`，页面靠双读兜底掩盖），
已修复。

## 当前任务

| 任务 | 状态 |
| --- | --- |
| #57 `oauth/token` 密钥走 query | **已收口**（2026-10-05）：挪进 JSON 请求体（`OauthTokenRequest`），query 传输位被契约测试钉死。依据是证据不是假设：端点在网关 JWT 白名单外（外部 ISV 无调用资格）、仓内零调用方、无 ISV 文档；泄漏通道在 ingress 层访问日志（nginx-ingress 默认记完整 request line），网关自身只记 path |
| #60 库龄分析 | **已做完**（上一班）：接进 `/warehouse`，候选回到 33 |
| 形状级反向核对尺 | **已做完**（本班）：`tools/schema/stub_shape_audit.py` 进 CI，见 `docs/superpowers/evidence/2026-10-05-stub-shape-gate.md` |
| 参数名一致性（`@RequestParam` 名 vs 前端 `params` 键） | **未量到**：探针伪影占多数，需要真正的 Spring 签名解析；形状尺只覆盖了「桩字段 vs DTO 字段」这一半 |
| CI 是否真的转绿 | **未证实**：只能看下一次 run 的日志；本地精确复现已消除（#56），本班又推了两个 commit |

## 本班完成的 2 个 commit

| commit | 内容 | 对基数的影响 |
| --- | --- | --- |
| `a2d35c0` | #57 收口：`oauth/token` 密钥挪进 JSON 请求体；服务层入口补显式空参校验；3 条契约测试钉住传输位；台账 14 行更新 | 不变（该端点本就不是候选） |
| `57e79d3` | 形状级闸门进 CI：新尺 self-test 20 项；首轮抓到真漂移（`LM_CHANGELOGS` 的 `field` vs DTO `fieldName`，页面双读掩盖）；变异验证精确 1 红 | 桩可比分母 140 条注册 / 106 可比 |

## 门禁基线与复跑命令

```bash
export PATH="/c/tools/apache-maven-3.9.16/bin:$PATH"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
# 清点（正向=人读台账，反向=CI 门禁）
python tools/schema/endpoint_coverage_audit.py --self-test .    # 19/19
python tools/schema/endpoint_coverage_audit.py --reverse .      # 0 findings，非 0 即红
python tools/schema/endpoint_coverage_audit.py                  # 候选 33（不阻断）
# 形状级（本班新增，进 CI）
python tools/schema/stub_shape_audit.py --self-test .           # 20/20
python tools/schema/stub_shape_audit.py .                       # 0 findings，非 0 即红
python tools/schema/entity_column_drift.py --self-test . && python tools/schema/entity_column_drift.py --gate .
python tools/release/repository_hygiene.py --root .             # 以退出码为准，别 grep 文本
python tools/schema/zero_reference_tables.py                    # 113 表 / 11 零引用
```

- 后端定向跑：**逗号**分隔，从不使用 `+`（`-Dtest='A,B'` + `-q` 会伪装成零测试绿）。
- 前端：node 在 `/c/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-3`；
  `npx vue-tsc --noEmit`、`npx vitest run`、`npx playwright test -g "<套件>"`。
  e2e 跑在 5173 上（`webServer` 复用已有服务；跑前先确认该端口没被别人占）。
- 本班开头跑过一次**整仓** `mvn test`：**1982 tests / 0 failures / 0 errors / 17 skipped**，
  但那是在 `a2d35c0`/`57e79d3` 之前 —— 下一班第一件事建议再跑一次整仓。
- 单模块最近数字：multiplatform **98**（+3 契约测试 +1 空参校验）、order 83、ai 124、spapi 675。

## 卡住 / 未解决（按能不能自己推进分类）

**需要外部条件（做不了，别在原地重试）**

- B 桶 20 条：`DEEPSEEK_API_KEY`（4 条 AI）、Keepa token（3 条）、店铺 SP-API 凭证（13 条）。
  代码路径是真代码、未配置时会给点名失败，**没有凭据就只能保持未接**。
- CI `test` 作业是否真的转绿：匿名拉 run 日志是 403，没有 token。
  能说的是「#56 同一输入下的精确复现已消除」（修前 `IT_RC=1`，修后 `IT_RC=0`）。

**方法还不可靠**

- 参数名 / DTO 字段位的另一半：`@RequestParam("x")` 与前端 `params` 键。
  探针能对上的只有一句：**42/42 个带 `params` 的调用点都能定位到后端同形状同方法的映射**。
  键名比较报的 8 条逐条复查全是伪影（`defaultValue = "14"` 被当参数名、`{ ...q, sku }` 把变量名
  当键、POST body 的键去比 query 参数名）。要做对需要按 Spring 契约取参数名，或引真正的 Java 解析。
  形状尺的经验可复用：**先打分消伪影，再比；分母打印；变异必须能红且红在正确的行**。

## 下一步计划（建议顺序）

1. **整仓 `mvn test`** 确认 `a2d35c0`/`57e79d3` 没引入回归（最便宜的止损）。
2. **参数名尺**：按形状尺同样的方法论做（先按 Spring 契约取参数名，再比键）。
   若做，注意 `@RequestParam` 的 `value=` 显式名、`defaultValue` 的存在不影响键名。
3. 若继续压 A 桶：先明确一条原则 —— 显式拒绝优于假成功，**不要把拒绝改回沉默**。
4. CI 绿了之后，把 #56 的结案记录从「本地复现」升级成「run 日志佐证」。

## 踩过的坑（勿重演；历史条目见 git 历史版本，以下含本班新增）

历史八条（字典序≠版本号序、豁免按哈希钉住、注释不是调用、提取器隐性门槛、
双清空横幅、变异要能编译、一次性 MySQL 前置、可空列 NPE / 命名空间导入 /
root 拒绝 / v-show 行定位）仍然有效，详见 `git show 471c9fe:HANDOFF.md`。
本班新增：

1. **裸 `//` 不是正则字面量**：给桩正则做「字符串/正则区间保护」时，正则字面量
   模式若允许空 body（`*`），每一处行注释 `//` 都会被当成空正则保护起来，
   注释剥离静默失效——桩常量里加一行带 `tools/schema/` 路径的注释，整个常量
   解析失败、门禁假绿。修法见形状尺 `strip_comments`（两遍法 + body 用 `+`）。
2. **record 的组件在头部括号里，不在类体里**：按类体提字段会漏光 record；
   按文件名建索引会漏嵌套 record（`Capability`/`OutboxView`/`ReplayResult` 都是
   嵌套类型）。外层类型提字段前必须把嵌套类型的 声明+类体 整段挖掉。
3. **变异测试抓住了假绿**：第一版基线 0 红，变异后门禁仍绿——因为上述解析 bug
   让目标桩根本没进可比分母。教训（重申 + 细化）：变异不仅要能红，
   还要**先确认基线里它在可比分母内**。
4. **`rg -r` 是替换标志**：`rg -rn "pattern"` 会把输出里的命中文本替换成 `n`，
   读证据时会被骗（本班把 `/change-log/list` 看成 `/n/list` 追查了一轮）。
   要递归 + 行号用 `rg -n`，别随手加 `-r`。
5. **mvn 日志是 ISO-8859/CRLF**：GNU grep 会把它当二进制（`grep -c` 无输出、
   退出码非 0 但静默），汇总测试数用 `rg --text` + `^\[INFO\] Tests run:` 行尾锚定
   （不带 `-- in` 的才是模块汇总行）。

## 本班新增的事实（形状尺量出来的）

- 后端控制器返回形态分布：`Result<Map<String,Object>>` 51 处、标量 53 处、
  具名 DTO 其余。形状尺能比的只是具名 DTO 部分，Map 形态 31 条在桩上**每次清点
  都以 unverifiable 披露**（不阻断、不沉默）。
- `/report/dashboard/kpi` 的后端是 `/{metric}` 动态路由——桩里的 `shopId`/`dateRange`
  后端从不返回（打分规则修掉伪影后，这类真问题才浮得出来）。
- 前端 `multiplatform.ts` 只接了 OAuth 的 app 注册/轮换/列表三个端点，
  `oauth/token` 无浏览器调用方（#57 收口依据之一）。

## 环境与边界（务必遵守）

- `zc-live-*`（mysql/redis/rabbit）与 `amz-p13-*` 是**别人在跑的栈**：不重启、不改 Docker 配置/代理、
  不往演示库写数据。一次性容器用完即 `docker rm -f`，临时口令文件随手删。
- 不 DROP 业务表；不 `git checkout --` 覆盖未提交内容（一律从 `cp` 备份或内存字节恢复）。
- 推送 `master` 属已授权范围；本会话所有 commit 都已推送。GitHub 直推偶发
  `getaddrinfo() thread failed to start`，重试一次即过。
- 写文档（含本文件）也要过一遍 `repository_hygiene.py --root .` 再提交。
