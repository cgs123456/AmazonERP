# 7aa 自建下单（B2C）页面接入，并修掉「身份与幂等键由客户端说了算」

## 结论

`POST /order/saveOrder` 与 `GET /order/getOrderList` 接进新页面 `/b2c-order`「自建下单」；
候选 **34 → 33**。顺带修掉三条会让页面说谎的后端口径：

1. `userId` 原先取自请求体 —— 任何登录者都能给任意用户造订单，而 `GET /order/getOrderList`
   读的是认证上下文里的 userId：**别人能替你下单，你自己下完查不到**。现在服务端强制改写为登录用户。
2. `messageId` 原先也取自请求体，而消费端拿它做幂等占位 —— 塞一个已占用的 id
   就能让一条订单被**静默丢弃**。现在服务端每次新生成。
3. 金额非法原先照投不误：页面回「已提交」，消息只会进死信。现在 HTTP 入口当场拒，
   并且**与 MQ 入口共用同一条判定**（`priceRejectReason`），措辞一字不差。
   投递失败也不再被洗成一句「保存订单失败」，而是带上异常类型与原因。

## 先量了再动手（三个决定成败的前提）

| 待定的事 | 实测 |
| --- | --- |
| 下的单能不能查回来 | `saveOrderInternal` **不写 shop_id**（`Order` 有该列，V1 里是 `shop_id BIGINT`）。所以这张单**不会**出现在按店铺读的 `/order/list`，只能由 `/order/getOrderList`（按登录用户）读回。页面把这条写进说明区，不放一个「跳到订单列表」的假链接 |
| 商品 ID 塞不塞得下 | `amz_order.product_id` 是 `INT`、`Order.productId`/`OrderDto.productId` 是 `Integer`，而 `amz_product.id` 是 `BIGINT AUTO_INCREMENT`。当前 ID 从 1 递增，**今天能装下**；到 2^31 会溢出。这是遗留的窄化风险，修它要动 DDL + 实体，另立一片，本轮只在类型注释里说明，不假装它不存在 |
| 提交成功等于成单吗 | 不等于。`saveOrder` 只做 `rabbitTemplate.send(...)`，成功仅代表消息进队列。页面因此用**绿色 status 横幅**说「已投递下单消息…下面这张表出现新行才算成单」，与红色 error 横幅分开 |

## 改了哪几处

后端 `OrderServiceImpl`：新增 `priceRejectReason` 单一口径（MQ 与 HTTP 共用）、
`saveOrder` 入口改写身份/幂等键、投递失败带原因。
`amz-frontend`：`api/order.ts` 增 `saveB2cOrder`/`getMyB2cOrders`/`B2C_STATUS_LABELS`，
新页面 `views/B2cOrder.vue`（商品下拉取 `/product/master/list/{shopId}`，属性行按
`{label, value:[v]}` 提交），路由与侧边栏加入「自建下单」，e2e 桩补
`/order/saveOrder → null`、`/order/getOrderList`、`/product/master/list/`。

桩里 `saveOrder` 的 data 必须是 `null`：后端返回的是 `Result<Void>`，
给个对象页面就会把「还没落库」显示成有一行订单数据。

## 测试

- 后端 `SaveOrderIdentityTest` 5 项（新）+ `SaveOrderPriceSourceTest` 4 项不改动全绿；
  **order 模块全量 83 tests, 0 failures**。
  断言看的是**真正交给 RabbitTemplate 的消息体 JSON**，不是方法调用参数——
  只有这样才能测出「userId 被改写成 42、messageId 换掉了」。
- 前端 `B2cOrder.test.ts` 8 项、全量 vitest **467 tests / 41 files 全绿**、`vue-tsc --noEmit` 干净；
  Playwright `自建下单` 3 项通过（含侧边栏跳转那项）。
  e2e 里断言了请求体 `toEqual({productId, price})` 且 `'userId' in body === false`。

## 变异检查（五条各由一条独立断言抓住）

| 注入 | 红的那条 |
| --- | --- |
| 去掉 `setUserId(currentUserId)` | `身份…由服务端定`：`expected <42> but was <99>` |
| 去掉 HTTP 金额预检 | 同步拒绝项 + **共用口径项**（HTTP 回 200、MQ 抛异常）同时红 |
| 投递失败回到「保存订单失败」 | `transportFailureReasonIsVisible` 红 |
| 去掉匿名守卫 | `anonymousIsRefusedBeforeAnythingIsSent` 红 |
| 前端 `loadMine` 清横幅（原有写法） | 主数据报错被擦掉 → vitest 红（这条是**先写测试才暴露出来的真 bug**：一个 loader 清 `errors` 会把另一个 loader 刚报的原因抹掉，与 7f 那三次踩的是同一个坑） |

## 闸口

`endpoint_coverage_audit --self-test 7/7`，候选 **33**；
`entity_column_drift --self-test` + `--gate`：**101 entities / 0 drift / 0 豁免**（本轮无 DDL）；
`repository_hygiene --root .`：**findings 0，RC=0**。

其中 hygiene 中途红过一次，值得记下来：`hygiene-allowlist.json` 的豁免是按**整份文件内容哈希**
钉的，我在 `router/index.ts` 加了一条路由 → 文件哈希变了 → 原先已复核的那行
`const token = localStorage.getItem('token')` 重新变成 finding。这不是误报复活，而是机制按设计
要求「碰过的文件要重新看一眼」。已确认那一行是运行时读取而非硬编码密钥，重新 attest 新哈希。
**没有放宽规则，也没有把该行加进跳过名单。**

## 仍然留下的

- 双击仍会各下一单（幂等键服务端每次新生成）。要做真正的提交幂等，需要前端带一次性提交令牌
  + 后端校验，那是独立一片；本轮先用提交期间禁用按钮兜住最常见的手滑。
- `product_id` 的 INT/BIGINT 窄化风险（见上表）。
- `/order/saveOrder` 无 `@ShopScoped`：它本就不带店铺，归属由 userId 定；等 B2C 域真正
  要多店隔离时再定口径。
