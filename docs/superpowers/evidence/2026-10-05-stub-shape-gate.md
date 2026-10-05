# 形状级闸门进 CI：e2e 桩字段 vs 后端 DTO 字段（2026-10-05）

## 结论

反向尺只看路径与方法；桩返回的 **JSON 字段名** 是否真实存在于后端返回的 DTO 里，
此前靠人工核对。本轮新增 `tools/schema/stub_shape_audit.py`（默认即闸门模式），
接进 CI `hygiene` 作业，紧跟 `endpoint_coverage_audit --reverse` 之后。

首轮就抓到一条**真漂移**：

- 桩 `LM_CHANGELOGS` 元素写的是 `field: 'price'`，后端
  `ListingChangeLog` DTO 的字段是 `fieldName`。页面接真实后端后「字段」列永远是
  undefined——此前页面用 `log.fieldName ?? log.field ?? '-'` 双读把漂移掩盖了。
  修复：桩与页面统一为 `fieldName`，删掉页面对错字段的兜底；
  e2e 的 ListingMonitor 套件 3/3 通过。

## 口径（刻意从窄）

- 只比顶层字段名：桩 data 的对象键 ⊆ 后端 data 类型字段。桩少给是正常的
  （页面未必渲染全部），桩多给才是危险方向。
- 只在两端都能静态解析时才比。后端 `Result<Map<String,Object>>` 等静态不可知
  形态**披露**为 unverifiable，绝不编造字段清单。
- 候选端点按「靠通配段才命中的位置数」打分，只比最佳组：桩字面段吃进后端 `{id}`
  的牵强前缀命中（如 `outbound/list` 撞上 `outbound/{id}/pick`）不再产生伪红。
  首轮没有打分时 59 条红里绝大多数是这类伪影，打分后剩 5 条、逐条甄别后 1 条真红。
- 形态错位（桩是对象、后端是数组，或反向）同样红。空数组桩对带字段 list 是
  诚实值，放行；标量/字符串桩对带字段后端是谎，红。
- 同一形状多个动词返回类型冲突（GET 返对象、PUT 返布尔）时无法归因到具体调用，
  披露为 ambiguous，不硬判。
- 分母必须打印。任何「0 findings」先看分母。

## 基线（2026-10-05，HEAD = 本次提交）

```
registrations=140 consts=95 backend-shapes=339
field-comparable=106 passed=92 RED=0 ambiguous=3 unverifiable=31 not-comparable=15
```

- unverifiable 31 条：绝大多数是 `Result<Map<String,Object>>`（51 处动态返回形态
  里被桩命中的部分），少数是 record/内部类解析不出的形态——每次清点都可见，不阻断。
- ambiguous 3 条：`/order/audit/rule/{}`、`/ad/bidSchedule/{}`、
  `/multiplatform/webhook/{}` 一类多动词同形状端点。
- not-comparable 15 条：函数载荷、`MP_ORDERS[0]` 这类下标引用、SSE 无 data 键等。

## 实现里值得记的三个坑（都进了 --self-test）

1. **裸 `//` 不是正则字面量**。桩正则以转义斜杠收尾（`/history\//`）文本上恰好
   产生相邻 `//`，而注释剥离若被「`//` 已在正则字面量区间内」骗过就会漏剥；
   反过来，正则字面量模式若允许空 body（`*`），裸行注释 `//` 又会被当成
   空正则字面量保护起来，**全文件的行注释都剥不掉**——桩常量里加一行带
   `tools/schema/` 路径的注释，整个常量解析失败、门禁静默假绿。
   修法：两遍法（先字符串区间保护、再剥区间外注释）+ 正则字面量 body 用 `+`。
2. **record 的组件在头部括号里**，不在类体里。按文件名建索引还漏掉嵌套
   record（`Capability`/`OutboxView`/`ReplayResult` 都是公开给前端的嵌套类型）；
   外层类型提字段前必须把嵌套类型的 声明+类体 整段挖掉，否则嵌套组件污染外层。
3. **变异测试抓住了假绿**：第一版基线 0 红，但变异（把 `fieldName` 改回 `field`）
   后门禁仍绿——因为注释解析 bug 让这个桩根本没进可比分母。修完解析后
   变异精确产生 1 红、指向漂移字段。教训重申：变异必须真的能红，而且要
   检查它红在「正确的行」上。

## 验证

- `--self-test` **20/20**；变异（`fieldName`→`field`）**恰好 1 红**、exit 1，恢复后回绿。
- 全量：`field-comparable=106 passed=92 RED=0`；`--reverse` 仍 0 findings；
  `entity_column_drift` gate 101/0/0；`repository_hygiene` 0 findings。
- 前端：`vue-tsc --noEmit` 0 错误；`vitest run` 478/478；
  `playwright test -g "ListingMonitor"` 3/3。
