# 2026-10-03 · 项 7e：多平台侧的跨店铺写守卫（含一个没有任何判定的入口）

7d 读 `MultiplatformServiceImpl` 时顺带发现的：这个模块的 id 定位写操作，守卫写得比广告侧松，
其中 **`mapProduct` 根本没有判定**。本轮只做后端守卫，不动 UI（UI 接入排在 7f）。

## 一、查出来的四类问题

| # | 位置（改前） | 事实 |
| --- | --- | --- |
| 1 | `mapProduct(platformProductId, asin, sku)` | 取完行直接 `updateById`，**没有任何店铺归属判定**：任何登录用户都能把别人店铺的平台商品改映射到自己的 ASIN/SKU |
| 2 | `createAccount` / `updateAccount` / `deleteAccount` / `testConnection` / `replyMessage` / `assignMessage` | 判定用 `UserContext.isShopAllowed`（lenient），且越权抛 `IllegalStateException` |
| 3 | `registerApp` / `rotateAppSecret` | 条件是 `ownerShopId != null && !allowed` —— **没有归属就等于不校验** |
| 4 | 上述所有列表方法 | 见 7d 的分页讨论；本轮没动，留给接 UI 时逐条加 |

关于第 2 条要说清一件容易夸大的事：`isShopAllowed` 在 `userId != null` 时直接委托给 `isShopAllowedStrict`，
而经过网关与 `BaseAuthInterceptor` 的浏览器请求一定带 userId。
所以 lenient 的放行分支只对「没有 userId 的上下文」生效（定时任务、白名单内部调用、以及上下文没建立起来的异常路径）。
**这不是一个「浏览器 token 能跨店改别人数据」的活漏洞**，改成严格版是纵深防御；
真正立刻可复现的行为差异是第 2 条的后半段：越权被全局兜底成 500「服务器内部错误」，
被拒的一方拿不到结论，日志里也混在系统故障里。

第 3 条则相反：`amz_oauth_app.owner_shop_id` 在 V1 DDL 里是 `NOT NULL`，
所以那个 `!= null` 分支不可能被真实数据命中，它唯一的作用是让**缺归属的注册请求绕过校验**、
然后在 INSERT 上撞数据库错误。轮换密钥同理——凭证面不能靠「大概不会有 null」兜着。

## 二、改法：判定收敛到一个 helper

```java
private void requireShopOnRow(Long shopId, String what) {
    if (!UserContext.isShopAllowedStrict(shopId)) {
        rejectForeign(shopId, what, null);   // CodeErrorException + 一条越权 WARN
    }
}
```

8 个入口全部改为先按 id 取行、再 `requireShopOnRow(行.shopId, 类型)`；
文件里剩下的 `UserContext.isShopAllowed*(` 调用**只有 helper 里那 1 处**（脚本核对过），
`IllegalStateException("无权…")` 残留 0 处。

另外两件顺手的事：`mapProduct` 现在要求 ASIN 非空、按广告域同一规则大写归一，SKU 去空白可空；
`markShipped` 也并入同一个 helper（7d 刚改的那条单独判定不再各写各的）。

## 三、测试

新增 `MultiplatformTenantGuardTest` 17 例（模块 `mvn test` **72 例全绿**）：
每个入口一条「他店被拒且不写库」，一条「ADMIN 无列表可放行」（严格版的短路边），
`mapProduct` 另有三条（无上下文、缺 ASIN、本店成功落库并归一），
`registerApp` 两条（缺归属 → `AttrIsNullException`；他店 → `CodeErrorException`），
`rotateAppSecret` 一条 null 归属按 fail-closed 拒绝。

关键是一条 `everyWriteEntryRejectsMissingContext`：它把 8 个入口在「上下文没建立」时
的拒绝放在同一个用例里断言。归属判定收敛到一个 helper 之后，
这条用例就是「谁偷偷换回 lenient」的总闸。

## 四、变异检查（3 条，文件按 sha256 复原后复跑绿）

| 变异 | 结果 |
| --- | --- |
| Z1 helper 退回 lenient | rc=1，32 例里 3 红：`shipRejectsContextWithoutUserId`、`mapProductRejectsContextWithoutUserId`、`everyWriteEntryRejectsMissingContext` |
| Z2 `mapProduct` 去掉归属判定 | rc=1，2 红：`mapProductRejectsForeignShop`、`mapProductRejectsContextWithoutUserId` |
| Z3 越权又抛 `IllegalStateException` | rc=1，16 红（覆盖每个入口各一条断言） |

Z1 只红了 3 条而不是 8 条，这点要看清：它证明的是「**helper 这一处**被换掉会被抓到」，
不是「8 个入口各自都被独立盯住」。8 个入口现在共用同一个判定，
所以真正兜底的是 `everyWriteEntryRejectsMissingContext` 这一条把 8 个入口都在同一个用例里断言了一遍——
任何入口想绕开 helper 自己写判定，那条用例就会红。
（对照 7d：那轮 X1 第一次跑出 rc=0，是这条链路上的测试根本区分不了两个 helper，补了才抓到。）

## 五、没做与遗留

1. 这些列表方法（`listAccounts`/`listProducts`/`listMessages`/`listPlatformInventory`/
   `listWebhookEvents`/`listApps`）还是没有 LIMIT 的整表读，与 7d 的订单列表同属一类；
   本轮没有顺手改，因为分页签名要跟着 UI 的翻页交互一起定，空改会让下一次接入时口径又变。
2. `POST /multiplatform/account/{id}/test` 的名字仍然骗人：它只做端点格式校验，
   但会把账号 `status` 写成 ACTIVE/ERROR 并刷新 `lastSyncTime`——
   一个没发过包的检查在改「账号是否活跃」。这个要先定产品口径（改名 / 补真实 ping / 不写状态），
   不是前端能掩盖的，所以本轮也没有把它接进 UI。
3. `replyMessage` 仍然只写本地库、没有平台回传契约，接 UI 前需要产品确认「仅内部备注」是否成立（7f）。
4. 没有连真实 MySQL：所有断言止于 wrapper 条件、绑定参数与 mapper 调用次数。

