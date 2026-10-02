# 7m — 个人资料页、editInfo 取值校验、全站第一个角色来源

日期：2026-10-03。同轮前置件（单独 commit `32fab3f`）：删掉两个从未被调用且类型写错的 Keepa 导出，
清点工具不再把它们算成「已接入」——候选数从 41 回到真实的 43，本轮做完是 42。

## 1. 接了什么

| 端点 | 之前 | 现在 |
| --- | --- | --- |
| `PUT /user/editInfo` | 全仓零调用方（只有 3 个 Java 文件提到过） | 新页 `/profile`「个人资料」，只提交改过的字段 |
| `GET /user/getInfo` 的 `user.role` | 后端一直在返回，前端 `UserVo` 没声明，因此全站没有角色来源 | `utils/identity.ts` 存进 `localStorage.user_role`，海外仓按它决定给不给「改库位」入口 |

归属本来就是安全的：`UserServiceImpl#editInfo` 只用 `UserContext.getUserId()`，DTO 里没有 id，
改不到别人头上（新增的 `rejectsForeignShopRow` 一类用例在 7l，这里是 `hasUpdate` 组用例）。

## 2. 后端补的校验（全部是「现在只会变成 500」的输入）

MySQL 跑默认 `STRICT_TRANS_TABLES`（`docker-compose.yml:32` 未改 sql-mode），而 `amz_user` 的列宽是硬约束
（V1__init.sql:12-18：nickname 50 / image 500 / address 200 / birthday 20，sex 是 TINYINT）。
原实现只判 `!= null`，所以：

- `nickname`/`address`/`image` 超长 → 1406 → `DataAccessException` → 全局兜底成一句 `服务器内部错误`；
- `sex: "男"` 或 `sex: ""`（DTO 是 String，库里是 TINYINT）→ 1366 → 同样只剩一句兜底；
- `birthday: "2026/01/02"` → 存进去，读的时候 `getInfo` 解析失败 → 只记一条日志、`age=0`（用户看到 0 岁）；
- **只带 `signature`/`school`/`identity`**（DTO 里有、表里已删列）→ 一个 `set` 都没有 →
  生成 `UPDATE amz_user` 直接报错，而返回码还是「服务器内部错误」。

现在：长度按 DDL 常量拒绝并回报实际长度；生日要求 `yyyy-MM-dd`（`LocalDate.parse`）；
`sex` 只接受能落进 TINYINT 的整数；没有任何可更新字段时明确失败并说明那三个字段已不可存储。
`null` 仍表示「不改」，空串仍表示「显式清空」（昵称/生日/地址可以清空，性别不行——库里没有空的 TINYINT 语义）。

## 3. 页面上刻意不做的东西

- **不提供** 个性签名 / 学校 / 证件号 输入框：填了不会保存，与其给三个假输入框不如说明。
- **不提供** 性别输入：String→TINYINT 且全项目没有任何地方解释这个编号的含义，
  只在读区原样展示当前值。
- **不提供** 头像上传：`POST /user/updateImage` → `OssUtil.uploadImg` 直接 `putObject`，
  没有凭据检查也没有本地兜底，而 `oss.accessKeyId` / `oss.bucketName` 的默认值就是
  `your-access-key-id` / `your-bucket-name`（application.yml:51-55，`application-local.yml` 里也没配）。
  接进来只会稳定报错，判为不接。
- **手机号只读且打码**（`138****1234`）：改绑需要短信验证流程，后端没有端点；
  页面也不需要完整号码，所以不给「显示」按钮。
- **头像地址按文本显示**，不放 `<img>`：那是第三方域名，加载它等于把访问者泄露出去。

## 4. 闸口

| 闸口 | 结果 |
| --- | --- |
| `mvn -pl amz-service/amz-service-user -am test` | 15 tests，0 failures / 0 errors（新增 `UserEditInfoValidationTest` 6 条） |
| RED | 6 条用例先红，全是断言：`expected: <400> but was: <200>`、以及 `Wanted 1 time ... Actually 2 times`（测试自身的记账错，已改成 `times(2)`） |
| 中途踩到的假 RED | `can not find lambda cache for this entity`：纯 Mockito 下 `LambdaUpdateWrapper` 需要 `TableInfoHelper.initTableInfo(...)`；补 `@BeforeAll` 后才是真正的行为红 |
| `vue-tsc` / `vitest run` / `vite build` | 0 / 441 tests（39 files）全绿 / build 通过 |
| Playwright | 新增 3 条（保存只带一个字段、无改动时禁用、VIEWER 不给入口）连同 7j-7l 相关共 9 条全绿 |
| 端点清点 | 43 → **42**；`UserController` 4 条里只剩 `POST /user/updateImage`（不接）与内部端点 |
| 清点工具自检 | `--self-test` 仍 5/5 |

## 5. 变异检查（6/6 咬住，四个文件字节级还原）

| 变异 | 重新注入的东西 | 应变红 | 实测 |
| --- | --- | --- | --- |
| M15 | 生日不解析（守卫短路） | `rejectsUnparseableBirthday` | 1/1 红 |
| M16 | 去掉「没有可更新字段」守卫 | `rejectsNothingToUpdate` | 1/1 红 |
| M17 | `NICKNAME_MAX_LENGTH` 50 → 500 | `rejectsOverlongNickname` | 1/1 红 |
| M18 | 保存时提交全部字段而非改过的 | 「只提交改过的字段」+「清空昵称提交空串」 | 2 failed / 8 passed ✓ |
| M19 | 角色门禁恒为允许 | VIEWER 隐藏入口那条 | 1 failed / 13 passed ✓ |
| M20 | 去掉 AppHeader 里的 `setUserRole(...)` | VIEWER 那条 e2e（`.loc-locked` 不再出现） | e2e 红，还原后绿 |

还原哈希：`UserServiceImpl 5358467c89b2e39e`、`Profile.vue 49810a843963123e`、
`Warehouse.vue c8ba37f25d7a6df1`、`AppHeader.vue 5bbbba3ae64a9c30`。

M20 的过程本身记一条教训：先用一段没有 `try/finally` 的内联脚本跑变异，脚本因
`FileNotFoundError`（subprocess 不认 `node`，必须给 `node.exe` 绝对路径）中途崩掉，
把 `AppHeader.vue` 留在了变异态；靠脚本里先前捕获的原文与规范化 sha256 当场核对还原。
变异脚本必须把还原写在 `finally` 里，并且还原后要验哈希，不能只看 `exit`。

## 6. 广告域本轮判为不接（同轮实测结论）

- `GET /ad/report/{shopId}`：读的是真表（`amz_ad_daily_report`，有 `LIMIT probeSize` 的 keyset 分页、
  无数据时返回空列表而不是模拟活动），但 `AdManager.vue:50` 与 `:152` 已经在调
  同一个服务方法的 `/ad/reports` 变体——再接就是第二条入口，7b/7c 已两次判过，维持不接。
- `GET /ad/keyword/optimize`：需要真实 Advertising 凭据（`@Profile("!mock")` 客户端缺凭据时 fail-closed，
  mock 档返回一条写死的 "wireless earbuds"）；即使有凭据，`AdServiceImpl:176-178` 把报表当
  `Collections.emptyList()` 传进去，所有建议恒为 `OBSERVE` 且 `suggestedBid` 为 null——不可执行的结果不接。
- **顺带量到的一个真问题（未在本轮修）**：`AdManager` 的 SP 表格与 DSP 汇总读
  `c.spend / c.sales / c.acos`，这三列来自 `amz_ad_campaign_ext`，而全仓没有任何写入路径
  （`setSpend(` 零命中，`AdCampaignExtMapper.java:66` 的注释也说不碰它们）→ 真实模式下这几列恒为 `$0`。
  这不是「没数据」而是「把没有数据显示成 0」，与 7h 修掉的「0 会被读成测出来是零」同一类。
  修法是把 SP 表格的这三列改从已获取的广告报表行里取（真数据），取不到的显示 `—`；
  留下一轮，因为要同时核对 `getAdReports` 返回的行能否按 campaignId 对齐。

## 7. 遗留

- `locationRoleAllowed` 只在 setup 读一次会稳定读到「角色未知」（AppHeader 的 getInfo 与页面并发），
  所以 `setUserRole` 现在广播 `amz:role-changed`，Warehouse 订阅后刷新。其它需要角色的入口应复用这条广播。
- `PUT /user/editInfo` 仍没有 `@Valid`（仓库里没有引 `spring-boot-starter-validation`），
  校验是手写 if；字段再多就该上 Bean Validation。
- `getInfo` 会把 `phone` 与 `role` 返给本人，这没问题；但它同时是 AppHeader 全站唯一的身份来源，
  任何页面硬刷都会打一次——后续若加缓存要连同 `user_role` 一起失效，否则角色会残留。
