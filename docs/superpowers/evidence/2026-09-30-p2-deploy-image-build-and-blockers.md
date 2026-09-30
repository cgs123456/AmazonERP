# 后续项 2/6/7/8/9 — 部署面实测与阻塞点定位

日期：2026-09-30（接 `2026-09-30-p1-degradation-visibility-and-order-count.md`）
基线：ca561a4

## 1. 结论先说

| 项 | 结论 |
|---|---|
| 2 重建并滚动镜像 | **镜像可以在本机离线造出来并已验到镜像层**（agent 挂载、非 root、日志目录、HEAD 的 ENV/ENTRYPOINT）；**滚动不能做**：在跑的这套栈属于另一个 worktree，且 HEAD 的 gateway 在该环境下会拒绝启动（缺 `crypto.key`），A/B 已证 |
| 6 代理依赖的重建 + k8s 验证 | 干净重建确实做不了，但原因和我上一轮说的不同（见 §2）；k8s 侧本机**没有集群也没有 context**，`kubectl apply --dry-run=client` 仍要连服务器取 API group，无法离线验 |
| 7 P1-3 生产前演练 | **另一 agent 正在做**（worktree `codex/p1-db-migration-audit` + `amz-p13-*` 四个容器已跑 12–14 小时 + risk1–4 文档今天 04:31/05:31 已提交）。我不重复占同一批演练资源 |
| 8 分支保护 / 第二人复核 | `gh auth status` = 未登录，服务端规则下发不了；且开启 master 保护会直接挡住另外 4 个 worktree 的直推，属需要你先定的策略问题 |
| 9 P2-2 性能基线 | **另一 agent 正在做**（worktree `codex/p2-performance-baseline`，跑着的就是它那套栈）。但它测的是**不含今天修复的代码**（§4），数值合完 master 后要重测 |

## 2. 更正我上一轮的代理判断（两次都错了，按实测收敛）

- 上一轮我说"阻塞是 Docker Desktop 代理配置，要改配置或重启 Docker"。**不准确**：
  `docker info` 显示的是 `http.docker.internal:3128`（Docker 自己的 hub 代理，正常工作，
  `docker pull`/`docker images` 都通）。
- 然后我说"`--build-arg http_proxy=http://host.docker.internal:7900` 可以绕开"。**这条也错了**，
  实测 `docker build` 失败在 `[skywalking-downloader] ADD https://archive.apache.org/...`：

  ```
  connecting to archive.apache.org:443 container connecting via static system HTTPS proxy
  http://127.0.0.1:7897: dial tcp 127.0.0.1:7897: connectex: ... actively refused it
  ```

  BuildKit 抓 `ADD` 的 URL 用的是 **Windows 系统代理**（注册表里指向 7897，而 Clash 现在听 7900），
  `--build-arg http_proxy` 只影响 `RUN` 里的进程，管不到 BuildKit 自己的取源。
- 真正的最小修复是**主机侧**的一件事，不用碰 Docker：把系统代理端口从 7897 改到 7900
  （或让 7897 重新监听）。这属你的机器设置，我没动。
- 顺带一条自己踩的坑：那次后台 `docker build ... > file; echo BUILD_RC=$?` 的包装脚本
  退出码取的是收尾的 `tail`，不是 `docker build`，所以我一度以为构建成功了（`docker images` 里没有）。
  判据要看日志里的 `ERROR: failed to build`，不能只信外层 rc。

## 3. 在跑的部署面比仓库旧得多（实测）

`docker inspect` 六件套（gateway + order/user/finance/spapi/report）：

| 检查项 | 在跑的 `amazon-erp/<svc>:latest` | 仓库 HEAD Dockerfile 要求 |
|---|---|---|
| `appuser` | **不存在**（进程 uid=0 以 root 跑） | `groupadd/useradd appuser` + `USER appuser` |
| `/skywalking-agent` | **整个不存在** | COPY agent 9.7.0 并去掉 `optional-reporter-plugins` |
| `curl`（HEALTHCHECK 用） | 有 | apt 安装 |

也就是说：**当前部署面从来没有挂过 agent，也不是非 root 运行**——traceId 相关的一切修复
在现网都只是"仓库里成立"。同一条 gateway 日志把这件事写得很直白：

```
旧镜像（现网）： 2026-09-30 17:28:49.553 [main] [] INFO com.amz.AmzGatewayApplication - Started ... in 12.219s
新镜像（本轮）： 2026-09-30 17:27:18.474 [main] [TID:N/A] ...（且 SkyWalking agent 已加载）
```

那个 `[]` 就是当初死掉的 `%X{traceId}` 占位符；新镜像里同一位置是活的 `%traceId`。

## 4. 离线造镜像（已做成的部分）

脚本：`~/img-probe/derive_images.py`（一次性工具，未入库，理由见 §6）。
做法：以在跑的镜像为 base（它的 apt 层已有 curl）→ 补 `appuser` → 从主机已校验的官方
`skywalking-agent`（9.7.0，51MB，`~/skywalking-probe/agent-official`）COPY 进 `/skywalking-agent`
并按 HEAD 删掉 `optional-reporter-plugins` → 换上今天的 fat jar（`clean verify` 产物，17:02–17:05）
→ **ENV / EXPOSE / USER / HEALTHCHECK / ENTRYPOINT 逐字取自仓库 Dockerfile 的 final stage**，
不手抄（手抄必然漂移）。

已构建并验到镜像层：`amazon-erp/amz-gateway:p2fix-ca561a4`（981MB）、
`amazon-erp/amz-service-order:p2fix-ca561a4`。gateway 镜像内实测：

```
User=appuser   uid=999(appuser)   agent-present   opt-plugins-removed   logs-writable
```

启动探针（挂到该栈网络、关掉 nacos 注册、不发布端口）故意复现历史故障条件 `JAVA_OPTS=`（空）：
agent 仍然挂上（`/skywalking-agent/skywalking-agent.jar` + 插件加载日志），
证明 Dockerfile 那层"agent 挂载免疫于 JAVA_OPTS 整体覆盖"的改动在镜像里生效。

## 5. 为什么还不能滚动（两个硬阻塞）

1. **HEAD 的 gateway 在这个环境起不来**（与我的改动无关，但会挡住上线）：

   ```
   Caused by: java.lang.IllegalStateException: crypto.key 未配置，拒绝启动。
            请通过环境变量 AMZ_CRYPTO_KEY 提供 32 字节 base64 编码的密钥。  (CryptoUtil.init:53)
   ```

   A/B 对照（同一 env-file、同一网络、只差镜像）：旧镜像 `running exit=0`（同一处 Seata
   `service.vgroupMapping.default_tx_group` 报错但容忍），新镜像 `exited exit=1`。
   现网 gateway/order/user/report 四个容器的 env 里 **`CRYPTO_KEY` 出现次数都是 0**——
   也就是说这套环境的 gateway 从没配过 crypto key，而 master 现在把它当成启动前置条件。
   上线前必须先补配置（compose/k8s/或 Nacos 共享配置），否则滚动 = gateway 下线。
2. **这套栈不是本 worktree 的**：容器标签指向
   `C:\Users\Administrator\Desktop\AmazonERP-p2-performance\docker-compose.yml`
   （网络名 `amz-erp-p2-perf_default`），那是另一 agent 的 P2-2 基线环境，且有 17 个未提交文件。
   我换它的容器 = 踩它的在跑测量。

另外说明：DB 类服务（order 等）我**故意没有起探针**。它们的启动会带 Flyway 迁移，
等于往别人在用的演示库写 schema；本轮约束是不向该库写数据，所以停在镜像层验证。

## 6. k8s / 集群侧（项 6）能做到的边界

`kubectl` 客户端 v1.36.1 在，但 `kubectl config get-contexts` 为空，也没有 kind/k3d/minikube/helm。
`kubectl apply --dry-run=client --validate=false` 依然要连服务器解析 API group：

```
unable to recognize "k8s/infra/skywalking.yaml": the server has asked for the client to provide credentials
```

17 份清单（16 服务 + skywalking）在离线的 kubectl 路径下 **0 份可通过**。
清单本身的结构性约束由测试守着（`SkyWalkingIdentityContractTest`、
`PlaceholderCoverageContractTest` 等），但"apply 得下去、Pod 起得来"这一层本机无法证明。
启用 Docker Desktop 的 Kubernetes 会重启 VM，连带杀掉另一 agent 的整套容器——没你同意我不会做。

## 7. 交付与后续（按依赖顺序）

1. 你决定：把 Windows 系统代理从 `127.0.0.1:7897` 改到 `7900`（或恢复 7897 监听）→
   之后 `docker build`（干净重建，含 OS 层刷新）才可用，`amazon-erp/<svc>:latest` 才能正式重打。
2. 补环境前置：给 gateway 配 `CRYPTO_KEY`（compose + k8s 两条路都要），并决定 Seata 的
   `service.vgroupMapping.default_tx_group` 是补齐配置还是关掉 seata。
3. 与另一 agent 协调：它那套栈要么先完成 P2-2 基线，要么由它确认可以让本 worktree 的镜像接管。
4. 项 8：你 `gh auth login` 之后我才能下发保护规则；同时要先决定"是否允许 5 个 worktree 直推 master"。
5. 项 9（要转达给做 P2-2 的 agent）：它的分支不含 `9fc2fb3`（Result 解码）/`060497c`（降级日志+订单数）/
   `ca561a4`，也就是它现在测的 38 个跨服务方法**全部在解码失败后走降级路径**，
   且降级日志改动会让"每次降级多打一条全栈"进入热路径。合并 master 之后必须重测，
   否则基线数字解释的是另一套代码。

## 8. 阻塞① 已解（更正：不是"环境缺配"，是 master 少供给 14 个服务）

我上一版把它写成"这套环境没给 gateway 配 crypto.key"。**方向错了**：真实情况是
master 自己少配了 14 个服务，换任何一个环境都一样起不来。

实测链（同机同网络，只差环境变量）：

| 镜像 | 结果 |
|---|---|
| `amazon-erp/amz-gateway:latest`（旧码） | `running exit=0`，同一处 Seata `vgroupMapping` 报错但被容忍 |
| `amazon-erp/amz-gateway:p2fix-ca561a4`（HEAD 码） | `exited exit=1`：`IllegalStateException: crypto.key 未配置，拒绝启动`（`CryptoUtil.init:53`） |
| 同上 + 只加一个 `CRYPTO_KEY` 环境变量 | `running exit=0`，`Started AmzGatewayApplication in 16.294 seconds`，日志 trace 槽 `[TID:N/A]` 是活的，`JAVA_OPTS=`（空）下 agent 仍挂载 |

成因：`CryptoUtil` 是 amz-common 的 `@Component`，`@Value("${crypto.key:}")` 空值即拒；
而仓库只在 **spapi 与 user** 两处给了供给路径（`application.yml` 里
`crypto.key: ${AMZ_CRYPTO_KEY:}` + compose 传 `AMZ_CRYPTO_KEY`）。
其余 14 个可部署服务两个都没有。另外注意环境变量名必须是 `CRYPTO_KEY`
（Spring 宽松绑定落到 `crypto.key`），而异常文案却叫运维去设 `AMZ_CRYPTO_KEY`——
照文案设值照样起不来。

修法（只动部署面，不动代码）：

- `docker-compose.yml`：16 个可部署服务段各加一行
  `CRYPTO_KEY=${AMZ_CRYPTO_KEY:?AMZ_CRYPTO_KEY_required}`（值仍来自部署方已有的那一个密钥，不落两处）；
- `k8s/services/*.yaml`：缺的 14 份各加
  `CRYPTO_KEY` ← `secretKeyRef{amz-erp-secret, AMZ_CRYPTO_KEY}`；spapi/user 原本就走 `AMZ_CRYPTO_KEY` + yml 映射，保持不动；
- 新增 `CryptoKeyProvisioningContractTest`（4 个用例）守住这条：compose 覆盖、k8s 覆盖（且禁止明文字面量）、
  代码侧属性名仍是 `crypto.key`、gateway 单点必须用 `CRYPTO_KEY`；
- `PlaceholderCoverageContractTest` 的"注入集必须等于占位符集"允许集加上 `CRYPTO_KEY` 并写明别名理由。

变异验证：只摘 gateway 的 compose 注入行 → **恰 2 红**，报错直接点名
`这些服务会带着空 crypto.key 启动并当场拒绝 ==> expected: <[]> but was: <[amz-gateway]>`；
同时摘掉 order 的 k8s 注入行 → **3 红**；还原 → 4/4 绿（compose/k8s diff 回到纯插入 48/2 行）。

## 9. 交给做 CryptoUtil 的那位 agent 的一条反证

`AmazonERP-p2-performance` 工作树里有未提交改动：`@ConditionalOnProperty(name = "crypto.key")` 加在 `CryptoUtil` 上。
方向能理解（别让没用加密的服务因为缺密钥而起不来），但它会把**启动期故障挪成运行期故障**：
`amz-common/config/CryptoTypeHandler` 是**静态**取 `CryptoUtil.getInstance()` 的
（`CryptoUtil.java:73`，`INSTANCE == null` 时抛），属性没供给的服务容器能起来、
一到加解密列才炸；`amz-service-multiplatform` 正是这种情形——它的主代码用 `CryptoUtil`，
但 `application.yml` 里没有 `crypto:` 块。

本次的部署侧供给与该改动不冲突：属性供给齐之后，`@ConditionalOnProperty` 处处成立，
行为回到今天之前；而 §8 的契约测试会一直要求"供给齐"，所以两条路都指向同一个前提。
建议它落地时一并断言 multiplatform 有供给路径（否则条件化会把这条静默掉）。

## 10. CI 冒烟矩阵补上 gateway 这条腿（项 3 的加固）

今天的阻塞恰好是 CI 现有冒烟的盲区：`runtime-smoke` 只起 `amz-service-message` 一个模块，
而实际炸的是 gateway——故障形状是"amz-common 里的 `@Component` + 部署面少配一个变量"，
跟被起的那个模块是不是受害者无关。

改动：`.github/workflows/ci.yml` 的冒烟 job 现在起**两个**模块（都与中间件无关、无 datasource/flyway）：

```
mvn -B -pl amz-service/amz-service-message,amz-gateway -am package -DskipTests
runtime_smoke.py --jar .../amz-service-message-1.0-SNAPSHOT.jar   → $RUNNER_TEMP/runtime-smoke-message.log
runtime_smoke.py --jar amz-gateway/target/amz-gateway-1.0-SNAPSHOT.jar → $RUNNER_TEMP/runtime-smoke-gateway.log
```

本地实测 gateway 这条腿可过（无 nacos、无库、隔离环境变量）：

```
RUNTIME_SMOKE PASS jar=amz-gateway-1.0-SNAPSHOT.jar
日志 100 行，Started AmzGatewayApplication，其中 49 行带 [TID: N/A]，致命签名 0 次
```

元契约同步加固：`CiWorkflowContractTest` 现在要求 `runtime_smoke.py` 调用 ≥2 次且两条腿的 jar 都在，
否则红灯。变异验证：**删掉 gateway 的 `--jar` 行 → 3 用例中恰 1 红**（还原前那次"绿"是假的——
我的 python 断言先抛异常、文件根本没被改，mvn 测的还是原文件；这条也记在这里当反面教材）。

## 11. 复跑命令

```bash
# 离线派生镜像（脚本在主机上，不入库）
python ~/img-probe/derive_images.py p2fix-ca561a4 amz-gateway amz-service-order

# 镜像层验证
docker run --rm --user appuser --entrypoint sh amazon-erp/amz-gateway:p2fix-ca561a4 \
  -c 'id; ls /skywalking-agent/skywalking-agent.jar; ls -d /skywalking-agent/optional-reporter-plugins; test -w /app/logs && echo writable'

# 为什么干净重建失败（看这一行就够）
grep -A3 "skywalking-downloader" /tmp/build_order.txt   # 若日志已清，重跑一次 docker build 即可复现

# crypto.key 供给契约（compose 16 段 + k8s 16 份 + 代码侧属性名 + gateway 单点）
mvn -o -B -pl amz-service/amz-service-spapi -am test \
  -Dtest='CryptoKeyProvisioningContractTest,PlaceholderCoverageContractTest'

# gateway 这条冒烟腿（本机无需 nacos/库）
mvn -o -B -pl amz-gateway -am package -DskipTests
python tools/ci/runtime_smoke.py --jar amz-gateway/target/amz-gateway-1.0-SNAPSHOT.jar \
  --timeout 150 --dump-log /tmp/ci-smoke/gateway.log

# compose 渲染检查（用一次性假值，证明 16 段都拿到了变量）
python - <<'PY'
import re, subprocess, os
from pathlib import Path
txt = Path('docker-compose.yml').read_text(encoding='utf-8')
env = dict(os.environ)
for v in sorted(set(re.findall(r'\$\{([A-Z0-9_]+):[?]', txt))):
    env[v] = 'probe-dummy'
r = subprocess.run(['docker', 'compose', 'config'], capture_output=True, text=True, env=env)
print('rc=', r.returncode, '| CRYPTO_KEY 出现次数:', r.stdout.count('CRYPTO_KEY'))
PY
```
