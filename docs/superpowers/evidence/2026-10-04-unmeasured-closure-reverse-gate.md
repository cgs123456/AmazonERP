# 未量到项的处置：反向接线闸门进 CI（2026-10-04）

## 结论

v20 清点报告里列的 6 条「未量到」，这一轮处理掉 3 条（变成 CI 门禁）、澄清 1 条、
仍有 2 条**未解决**（如实保留）。用户可见候选 **33 → 34**，且这 +1 是**挤掉假豁免**的结果，
不是新发现的缺口类型：

- `/logistics/warehouse/stock/aging/{shopId}` 原先被 `has-feign-caller` 豁免 ——
  ai 模块确实声明了它，但**全仓没有任何调用点**（只有接口声明 + 降级实现）。
  豁免的前提「有服务间调用方，所以不该由浏览器负责」因此是假的。删掉这条死声明后，
  它如实浮成浏览器候选（现在 34 条里多它一条）。
- `POST /spapi/finance/events`（`listEvents`）同样声明而无人调用，但它带
  `@InternalServiceAccess("amz-service-finance")`，被 internal 标签豁免，不进候选。
  它留在**披露项**里：`feign-declared-uncalled=1`，不阻断，但每次清点都看得见。

## 固化下来的反向尺（`endpoint_coverage_audit.py --reverse`）

四件事一次量，任一不成立就 exit 1：

| 检查 | 基线 |
| --- | --- |
| 前端调用 (方法, 路径) → 后端映射存在 | 276 个调用点，0 孤儿 |
| 方法一致（GET/POST/PUT/DELETE/PATCH） | 0 不一致 |
| e2e 桩拦的形状 → 后端真的有 | 140 条桩，0 虚构 |
| 静态路由 ⇔ 侧边栏入口 | 29 条静态路由全部可达；侧边栏无死链 |

CI 位置：`hygiene` 作业，紧跟在 `entity_column_drift` 之后
（`--self-test .` 19 项 → `--reverse .`）。正向清点仍是人读的台账，不进 CI。

## 顺带清掉的工具自身问题（都是这轮量出来的）

1. **别名表原来手抄了第三份**（`viteProxy.ts`、网关 yml、审计工具各一份）。
   改成从网关 `RewritePath` 现场解析：网关没有别名时工具退化成「前端必须写后端全路径」，
   宁可多报缺口也不自己造绿灯。自测里钉了来回换算复原 + 不吃 `/ws` 那条。
2. **`CLASS_MAP` 不锚行首**：javadoc 里举例写的 `@RequestMapping("/fake")` 可能被当成类前缀。
   现在只认行首注解，并加了一条对应自测。
3. `shape()` 不剥查询串 → `POST /ai/eval/run?mode=${mode}` 被误报成后端没有。已修。

## 三个自暴的时刻（不写成「一次通过」）

- 提取调用点时强制要求泛型（`request.get<...>`），于是不带泛型的调用会**静默不进分母**；
  自测样本 `request.get('/a/plain')` 拿不到才暴露。改成泛型可选，并保留该自测。
- Feign 方法名从注解起点往后找第一个 `word(`，结果全部取到 `PostMapping`，
  「无调用点」从 2 条虚报成 8 条。新增 `method_name_after()` 跳过注解串，自测直接写死这个形状。
- `root = Path(sys.argv[1])` 把 `--reverse` 当成仓库根，正向/自测模式恰好不读文件所以没炸，
  `--reverse` 一读就 `FileNotFoundError`。改成忽略以 `-` 开头的参数取位置参数。

已知粗糙处：桩正则里的 `(list|sync)` 会被归一成 `/list/sync` 这种合并段，比对不上就报缺口
（方向是 fail-closed，不会漏报），当前基线里没有这类桩，所以是 0 findings。

## 变异验证（四条 GATE RED 各由一条独立检查报出）

| 注入 | 结果 |
| --- | --- |
| `api/order.ts` 加一条 `/order/definitely-not-an-endpoint` | `GATE RED frontend-call-without-backend-mapping: 1` |
| 把 `getMyB2cOrders` 的 `request.get` 改成 `request.put` | `GATE RED http-verb-mismatch: 1`（后端只有 GET） |
| 桩里加 `/^\/order\/ghost\/(list\|sync)$/` | `GATE RED e2e-stub-without-backend-mapping: 1` |
| 路由里加一条没有侧边栏入口的 `/orphan-page` | `GATE RED static-route-without-sidebar-entry: 1` |

四次注入同时跑，`--reverse` 退出码 1 且四类各报一处；还原后 `0 findings`、退出码 0，
`git status` 确认没有残留。

## 验证

`--self-test` **19/19**；`--reverse` **0 findings**；正向 `controllers=60 / candidates=34 /
unparsed=0`；`amz-service-ai` **124 tests BUILD SUCCESS**（删声明后测试仍能编译）；
`repository_hygiene` findings 0。

## 仍未量到（保留，不当成已解决）

1. **#56**：CI `test` 作业自 V10 起红，与 V10 的因果**仍未判定**（上次一次性 MySQL 是我自己的
   容器 root 账号没建好，不是产品结论）。这条排在本文件后面单独处理。
2. **参数名 / DTO 字段位**：`@RequestParam("x")` 与前端 `params` 键、body 字段与 DTO 属性名，
   这把尺只看路径与方法，不看键名。要做需要先决定「以谁为准」，属于新增一把尺，不是本轮补漏。
3. 桩数据**形状**（字段级）与后端真实响应的结构一致性，仍靠人工核对。
4. 动态路由（`/orders/:id` 这类）不参与侧边栏可达性判定，只统计不比对。
