# 7ab 消息回复改「先真发、后记账」，未接入即显式拒绝

用户点名的第 3 项（真实发送）。

## 结论

`replyMessage` 不再是「写一条本地 OUT 备注 + 把原消息标成 REPLIED」。现在顺序是：
**向平台真发 → 拿到平台侧消息 ID → 才写本地两行**。三家真实客户端都没有买家站内信发送的
Open API method 依据，所以生产档一律 `UnsupportedOperationException`（点名「TEMU 平台的
买家站内信发送接口尚未接入」），**一行都不写**；控制器经 `guarded` 转成 code 400 的业务失败。
mock 档返回带 `MOCK-OUT-` 前缀的假 ID，页面/日志能一眼看出这是模拟档给的成功。

覆盖候选条数不变（仍 33 条，reply 依旧没有前端调用方），变的是这条端点的语义：
从「静默假装已回复」变成「要么平台真收，要么点名拒绝」。

## 为什么不干脆实现一个发送路径

`markShipped` 已经是现成的写操作缝（签名 + POST + 幂等提示），照抄一个
`/router?method=seller.im.reply` 之类的字符串就能让代码"跑通"——但没有任何一处依据说明
三家里哪家有这个 method、参数叫什么。凭空编造的后果是确定的：真实环境永远 404/签名失败，
于是有人会把探测/发送改成「返回成功」来消掉报错。**不编造端点**是本片唯一的产品级取舍。

## 改了哪几处

| 位置 | 改动 |
| --- | --- |
| `client/PlatformDataClient.java` | 契约新增 `String sendMessage(PlatformMessage outbound)`：返回平台消息 ID；未接入必须抛，禁止 null/假 ID/false 冒充成功 |
| 三个 Real 客户端 | `sendMessage` → `throw unimplemented("买家站内信发送（需对应 Open API method 名与请求字段映射）")` |
| 三个 Mock 客户端 | 返回 `MOCK-OUT-<ts>` 并 WARN 说明买家并没有真的收到 |
| `MultiplatformServiceImpl.replyMessage` | 先 `dataClient(...).sendMessage(...)`，成功后才 `updateById(原消息)` + `insert(OUT 行)`，OUT 行的 `platformMessageId` 就是平台给的 ID |
| `MultiplatformController.replyMessage` | 走 `guarded(...)`（上一片刚泛型化成 `<T>`，这里就用上了），拒绝理由直达前端 |
| 前端 `api/multiplatform.ts` + `MultiplatformOps.vue` 说明区 | 把「后端只写本地一条回复」的旧口径改成新口径；按钮仍不放（放了也只会稳定报错） |

`LOCAL-REPLY-` 前缀随本片消失：那是"只有内部记过"时代的标记，现在 OUT 行的 ID 必须是平台给的。
库里若已有历史 LOCAL-REPLY- 行，它们是那段行为的证据，不做数据改写。

## 测试

- 新增 `PlatformSendRefusalTest`（2 项）：三家 Real 客户端点名拒绝且理由含平台名 + 「站内信发送」；
  三家 Mock 客户端的 ID 必须 `MOCK-OUT-` 前缀。直接 new 客户端，不需要 Spring 上下文。
- `MultiplatformInternalSemanticsTest` 8 项：其中回复两条改写为新契约——
  `replySendsFirstAndRecordsOnlyWhatThePlatformAccepted` 用 `InOrder` 钉住「先发后写」的顺序，
  并断言 OUT 行 ID == 平台返回的 `TM-OUT-777`；
  `replyRefusesWithoutWritingWhenPlatformSendIsMissing` 断言拒绝时 `insert`/`updateById` 都没发生。
- 契约改动本身被编译器盯住：`PlatformDataClient` 加方法后，测试里手搓的 `StubClient`
  立刻编译失败（"does not override abstract method sendMessage"）——这正是把能力放进公共契约的收益。
- `amz-service-multiplatform` 全模块 **94 tests, 0 failures**；前端 `MultiplatformOps.test.ts` 22 项、
  `vue-tsc --noEmit` 干净。

## 变异检查

| 注入 | 结果 |
| --- | --- |
| 把 `sendMessage` 挪到 `updateById` 之后（先标 REPLIED 再发） | 回复两条同时红（InOrder 与拒绝路径） |
| `TemuRealClient.sendMessage` 改成 `return "TM-FAKE-1"` | `PlatformSendRefusalTest` 红：`Expected UnsupportedOperationException to be thrown, but nothing was thrown` |

第一次注「先写后发」时把整行发送语句移到了 `insert` 之后，直接编译不过（`platformMessageId` 未定义），
那一轮不算证据；换成移到 `updateById` 之后才拿到上面的红。

## 闸口

`endpoint_coverage_audit`：候选 **33**（reply 仍是 A 桶，但性质从"假动作"变"显式拒绝"）；
`entity_column_drift --gate`：101/0/0（无 DDL）；`repository_hygiene`：**findings 0，RC=0**。

## 还剩什么

- 真正的发送实现：等拿到三家任一家的站内信 method 与字段映射，替换那三处 `unimplemented`，
  并把前端「回复」按钮接上（`guarded` 已经能给出可读失败）。
- 平台已收但本地写入回滚 → 重试会重复发送。与 `markShipped` 同一类残余风险，
  需要平台侧幂等键才能收敛，代码注释里已点名，不假装解决。
- `oauth/token` 的 `appSecret` 进访问日志（安全项，独立于覆盖率）。
