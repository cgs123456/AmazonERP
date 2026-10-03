# 7z 平台「测试连接」改真探测并接进运营台（2026-10-03）

## 结论

`POST /multiplatform/account/{id}/test` 从「只校验端点字符串格式」改成**真探测**，并接进
`MultiplatformOps` 账号行；用户可见缺口 **35 → 34**。探测的产物只有账号状态列
（回话=ACTIVE / 没回话=ERROR），**不写 `lastSyncTime`**；亚马逊这类本模块不探测的平台
是**点名拒绝**（`Result.failure` + 原因），不是被标成 ERROR。

## 为什么这样接（不是先想到的做法）

第一版我在 `PlatformDataClient` 上加了 `String getPlatformName()` 给探测日志标平台，
结果 6 个实现全都要新增一个公开访问器——而 `AbstractPlatformClient.getPlatform()` 本来就有
这个值，只是 `protected`，接口方法要求 `public`，等于凭空多出一处能被改写的平台口径。
删掉它，日志改用实现类简单名（`TemuRealClient` / `TemuMockClient`），信息量相同、零新增契约。

探测刻意复用各家**已实现的订单读**（`fetchRecentOrders`，三家真实客户端都带自己的签名），
而不是新造一个 `ping/health` 路径：平台没有那个端点时，探测要么永远失败，要么被写成永远成功，
两种都比「不探测」更坏。凭证缺失 / 签名被拒 / 网络不通一律 `false` + WARN——
探测的职责是给结论，不是把异常抛给调用方去猜。

日志用 SLF4J 而不是 `java.util.logging`：接口拿不到子类的 `@Slf4j` 字段，而 JUL 记录
不进本项目的 logback 管线，失败原因会掉出应用日志（全仓 `System.getLogger` 零先例，实测确认）。

## 改了哪五处

| 位置 | 改动 |
| --- | --- |
| `client/PlatformDataClient.java` | `fetchRecentOrders` 提到公共契约；新增 `default probeConnection`（复用订单读，RuntimeException→false+WARN） |
| `service/impl/MultiplatformServiceImpl.java` | `testConnection` 走真探测；客户端分派放在探测**之外**（不支持的平台先拒绝）；删除已死的 `isEndpointWellFormed` |
| `controller/MultiplatformController.java` | `guarded` 泛型化为 `<T>`，`testConnection` 也走它——否则 `AttrIsNullException` 会被全局兜底成「服务器内部错误」 |
| `api/multiplatform.ts` + `MultiplatformOps.vue` | 新增 `testAccountConnection`、行内「测试连接」按钮；说明区第②条从「按钮没有做」改成真探测三条口径 |
| `e2e/support/api-stub.ts` | 补 `/account/\d+/test → true`；不加就会落到 JSON 兜底拿到分页对象，被前端当成「平台回话了」 |

前端点完探测必须重拉账号列表：结论只写在状态列上，只发 POST 就等于点了没反应。
拒绝路径**不**重拉——一行都没写，刷新会把「拒绝」洗成一次正常加载。

## 测试

`MultiplatformInternalSemanticsTest` 7 项（原 4 项的「自检不改状态」断言已随口径作废，
改成钉住新契约的三条）：

- `probeReusesTheAuthenticatedOrderRead`：手搓 `PlatformDataClient`，订单读成功→true
  （**空列表也算 true**，因为鉴权过了就是连通），抛异常→false；
- `testConnectionWritesStatusFromRealProbe` / `...MarksErrorWhenProbeFails`：状态由探测结果决定，
  两条都断言 `lastSyncTime` 仍为 null；
- `testConnectionRefusesUnsupportedPlatformInsteadOfMarkingItBroken`：AMAZON 抛
  `AttrIsNullException` 且 `updateById` 一次都没调；
- 他店账号仍被 `CodeErrorException` 拒绝。

`MultiplatformOps.test.ts` 22 项通过（新增 1 项 + 改写 2 项：说明区文案、「语义未定的入口必须不存在」
的黑名单里摘掉「测试连接」）；`vue-tsc --noEmit` 干净；Playwright `MultiplatformOps` 5 项通过
（新增 1 项断言 POST 一次 + GET 两次 + 该行「最后同步」仍是「从未」）。

后端定向跑：`amz-service-multiplatform` 全模块 **91 tests, 0 failures**。

## 变异检查（三条各由一条独立断言抓住）

| 注入的历史 bug | 结果 |
| --- | --- |
| `probeConnection` 的 `return false` → `true`（探测永远成功） | `probeReusesTheAuthenticatedOrderRead` 红：`凭证缺失应判为没连通 expected <false> but was <true>` |
| 探测顺手 `setLastSyncTime(now)` | 两条状态测试同时红（`lastSyncTime ... expected <null> but was <2026-10-03T15:28:50>`） |
| 不支持平台被 catch 成 `false` + 写 ERROR | AMAZON 拒绝测试红：`expected AttrIsNullException but nothing was thrown` |
| 前端探测后不重拉列表 | vitest 红（`listAccounts` 调用数 1≠2） |
| 前端拒绝路径也重拉列表 | vitest 红（刷新次数与预期不符） |

第一轮注 M1 时锚点写的是已被替换掉的 JUL 版本字符串，脚本 `assert count==1` 直接拦住——
那一次「7 tests 全绿」是**没变异**的绿，不作证据；换锚点重跑才拿到上面第一行。

## 闸口

`endpoint_coverage_audit --self-test 7/7`，候选 35 → **34**（A 14 + B 20，C/D 空）；
`entity_column_drift --gate` 不变（本轮无 DDL）；`repository_hygiene --root .` **findings 0，RC=0**。

## 仍然不做的

`POST /multiplatform/message/{id}/reply` 仍不接：它只写本地一条 OUT 备注，平台与买家都收不到。
用户点名的「真实发送」要在同一个签名+HTTP 缝上做，但**没有平台端点依据时不编造路径**——
这条留在下一片。
