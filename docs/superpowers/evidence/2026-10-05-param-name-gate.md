# 参数名闸门进 CI：前端 query 键 vs 后端 @RequestParam（2026-10-05）

> **最终态（同日 b750db6 后）**：可比 163/163 全过，六桶全零（type-extra/not-comparable/
> missing-required/ambiguous/unverifiable/RED）。本文中段的 106→155、57 条、8 条等
> 数字是收口过程的历史快照；23 条 type-extra 已按「共享类型按端点拆窄」终解。

## 同日增量：披露桶收口 + 分发变体消歧（任务 2/3）

初版基线（106 可比 / 57 不可比 / 1 歧义 / 1 缺必填）按 HANDOFF 计划 2/3 收口：

**函数作用域归属 + 类型解析**：文件按顶层 const 箭头函数声明切成顺序区间，
每个调用点只归属包含它的那一个函数体——helper（`params(q)` 的 null 过滤器）
自己的 `q: Record<...>` 不会再串到 api 函数的 `q: ReportQuery` 上，这就是
「同名参数命中多个签名」歧义的解法。归属唯一后，`params: q` / `{ params }` /
`params: params(q)` 三类变量透传都能经函数签名解析键：命名类型（`type X = {...}`）、
交叉类型（`A & {...}` 合并）、内联字面量、跨文件 `import type`（一跳）都支持；
`Record`/联合类型/索引签名/未解析展开如实披露。

**键溯源分桶（本轮最重要的口径决策）**：类型注解是键的**上界**而非实际发送集——
`ListQuery` 是 customer 四个列表函数共享的超类型，`minRating`/`templateType`
只在部分端点有意义。因此：
- 后端缺 **literal** 键（调用点内联字面量、路径内联 `?key=`）= 确定发送、
  真静默失效 → **GATE RED**；
- 后端缺 **type** 键 = 契约层虚报、运行时未必发送 → 单独披露 type-extra
  （19 条，如 `ListQuery` 系列与 `/logistics/warehouse/stock/list` 的 `enabled`），
  不当红。为收分母把上界当实发集就是制造伪影——上一班探针的教训反过来用。

**params= 分发变体消歧**：`@PostMapping(value="/reports/sync", params="shopId")`
与 `params="!shopId"` 是同路径同方法的两条 Spring 分发变体。解析限定符后按
「present ⊆ 调用键 且 forbid 与调用键不相交」过滤，只剩实际命中的那条——
歧义披露清零；限定符要求的键计入必填。若所有变体都不满足则披露
「该请求在后端会 404」。

**基线更新（同日）**：

```
backend-shapes=352 call-sites=278 with-keys=155 compared=155
passed=155 RED=0 type-extra=19 missing-required=0 ambiguous=0 unverifiable=0 not-comparable=8
```

可比分母 106 → 155（+46%），不可比 57 → 8，歧义与缺必填双清零。
自测 **25/25**（新增：类型字面量/交叉/联合/Record、归属唯一、限定符解析、
literal 溯源）。双变异验证：字面量注入 `bogus` 键精确 1 红（exit 1）；
类型注解注入 `bogusFilter` 只进 type-extra 披露（exit 0）。

## 实现里值得记的坑（第三次踩「切片后区间失效」）

- 外层补丁脚本的字符串转义会把 `\n`/`\b` 变成真实控制字符写进源文件
  （heredoc → Python 三引号 → 字面量三层转义）。写含转义序列的补丁一律走
  文件拼接或 Edit 工具，别用嵌套 heredoc。

上一班键名探针的遗留问题（「8 条伪影、方法不可靠」）本轮按形状尺的方法论重做，
固化为 `tools/schema/param_name_audit.py`（`--self-test` + 闸门模式），接进 CI
`hygiene` 作业。回答的问题：**前端 query 串里的键，后端真会读吗**——
Spring 对未知 query 参数是静默忽略的，页面带着失效筛选键照样显示「已筛选」。

首轮 20 红经候选匹配修正（见下）收敛到 0 红；变异验证（给
`GET /ai/knowledge/search` 的 params 注入 `bogus` 键）精确产生 1 红、指向漂移点。

## 口径

- **后端参数名按 Spring 契约取**：`value="x"` 显式名优先，其次 `@RequestParam("x")`，
  裸注解/`required=false`/`defaultValue` 取 Java 参数名。`defaultValue = "14"` 是
  默认值不是名字——上一班 8 条伪影之一，自测单钉一条。
- **带 `defaultValue` 或 `required=false` 即可选**（Spring 语义）。后端必填而前端
  键缺失是潜在 400，本轮**披露**（1 条）不阻断，量清楚后再决定是否收紧。
- **无注解形参走 ModelAttribute 语义**（`verify(LoginDto)` 这类）：标量取参数名，
  POJO 按 DTO 字段绑定（全可选）；MultipartFile 是 multipart body part 不是 query。
- **前端只比 `params:` 键与路径内联 `?key=`**。POST body 的键不比（上一班伪影之一
  就是拿 body 键比 query 参数名）。
- `params: params({...})` 这类 null 过滤 helper 不改键名只筛值，可比；
  变量实参、`{ params }` 透传、对象展开解析不了的**披露**不猜。
- **候选端点用形状全等匹配**（前端 api 路径是完整字面量，`${}` 占位归一成 `{}`）。
  首轮用 build_matcher 前缀匹配 + 通配打分，`/ad/bidSchedule/{id}` 会吃掉
  `/ad/bidSchedule/{id}/toggle` 且打分并列，产出 18 条伪红——改成全等后归零。
  前缀匹配只适用于桩正则那种**本来就开放结尾**的形状，这里不适用。
- 多候选裁决聚合：全过→过，全红→红，混合→披露 ambiguous。
- 分母必须打印。

## 基线（2026-10-05，HEAD = 本次提交）

```
backend-shapes=352 call-sites=278 with-keys=106 compared=106
passed=105 RED=0 missing-required-disclosed=1 ambiguous=1 unverifiable=0 not-comparable=57
```

- missing-required 1 条：`POST /ad/reports/sync` —— 后端其实是
  `@PostMapping(value=..., params="shopId")` 与 `params="!shopId"` 两条**同路径同方法
  分发变体**，Spring 按参数存在性路由，工具无法归因到具体变体，按歧义+缺键双披露。
- not-comparable 57 条：`params: q` 变量透传（键在函数签名的类型注解里）、helper
  转发变量、对象展开。下一步可解析「最近函数签名的类型字面量」把这部分收进来，
  但要先解决「同名参数多个签名」的归属歧义，别为了收分母引入伪影。

## 实现里踩掉的两个坑（都进了 --self-test）

1. **切片后必须重算字符串区间**：签名括号内层是在 `rest` 上切片得到的，区间若在
   切片前的文本上算，下标错位——注解属性里的真逗号被当成「串内」跳过，
   `@RequestParam(value="topN", defaultValue="5") int topN` 整个参数段丢进前一段，
   该端点少一个参数、误报红。这是本班第二次踩「切片后区间失效」（形状尺踩过一次）。
2. **`params:` 值的终止符要带深度**：`{ params: params({ asin }) }` 的值扫描若不
   跟踪相对深度，内层 `}` 会被当成值终止符，整个 helper 调用被截断解析失败。

## 验证

- `--self-test` **17/17**；变异注入 `bogus` 键**恰好 1 红**、exit 1，恢复后回绿。
- 全量：`passed=105 RED=0`；`endpoint_coverage --reverse` 0 findings；
  `stub_shape_audit` 0 findings；`entity_column_drift` gate 101/0/0；
  `repository_hygiene` 0 findings。
- 整仓 `mvn test`（新 HEAD）：**1986 tests / 0 failures / 0 errors / 17 skipped**。
- CI 状态仍外部不可验证（gh 未认证，匿名拉日志 403）；本班推送的 run 是一次自然验证。
