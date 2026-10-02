# 7l — 海外仓库位就地编辑 + 库存监控补货重算

日期：2026-10-03。切片选择依据：v11 之前剩 43 条用户可见候选，本轮挑的是**读写真表、不需要外部凭据、
页面缺的就是那一个动作**的两条；同类里被判「不接」的也一并记下来。

## 1. 接的两条端点

| 端点 | 数据来源 | 页面 |
| --- | --- | --- |
| `PUT /logistics/warehouse/inventory/{inventoryId}/location` | `amz_warehouse_inventory`，`selectById` → 归属判定 → `updateById` | 海外仓 → 库存查询 Tab，库位码列就地编辑 |
| `POST /spapi/replenish/calc/{shopId}` | `ReplenishmentScheduler.calcShopReplenishment`：读 `amz_fba_inventory` + `amz_sales_history` + 季节/促销配置，逐条 upsert `amz_replenishment_suggestion` | 库存监控页顶部「重算补货建议」 |

`calc` 不是 SP-API 调用，不需要店铺凭据，返回的是**本次生成的建议条数**（不是布尔），
所以成功后必须重新拉一次列表才能看到新的建议量——页面上这句写在 hint 里。
为了让「重算后重拉」和首屏共用同一条路径，把 `onMounted` 里的加载体抽成 `reloadData()`（行为不变，
错误条目与 `dataLive`/`healthKnown` 的置位逻辑原样搬过去）。

## 2. 顺手修的一个静默破坏

`WarehouseServiceImpl.updateLocationCode` 原来对 `locationCode` 一个字节都不校验：

```java
inv.setLocationCode(locationCode);   // 修前
```

而参数是 `@RequestParam String locationCode`——`?locationCode=` 这种「参数存在但为空」能通过必填检查，
于是**一次空提交就把库位清空**；长度超过 DDL 的 `VARCHAR(50)`（V1__init.sql:76）则在 MySQL 严格模式下
变成 500。现在：

- `null`/空白 → `CodeErrorException("库位码不能为空（要清空请走专门的解绑操作，本接口不做静默清除）")`；
- 超过 `LOCATION_CODE_MAX_LENGTH = 50`（常量与 DDL 对齐，注释写明来源）→ 报出实际长度；
- 合法值先 `trim()` 再落库，返回值也是 trim 后的。

前端同规则先挡一层（`api/warehouse.ts` 导出 `LOCATION_CODE_MAX_LENGTH`，页面输入框带 `maxlength`，
保存前再判一次，粘贴与程序化赋值也挡得住），失败信息留在**那一格**上而不是全局 toast。

一个 Vue 侧的坑记下来：模块命名空间对象（`import * as WH`）不能直接写进模板绑定——
Vue 会对 setup 绑定做 ref 解包，测试里 mock 的命名空间被读 `__v_isRef` 直接抛
`No "__v_isRef" export is defined on the "@/api/warehouse" mock`。改成脚本里取常量 `LOCATION_MAX` 再进模板。

## 3. 闸口

| 闸口 | 结果 |
| --- | --- |
| `mvn -pl amz-service/amz-service-logistics -am test` | 151 tests，0 failures / 0 errors（新增 `WarehouseLocationUpdateTest` 4 条） |
| RED | 3 条新用例先红，都是断言而非编译：`expected: <B-12-04> but was: <  B-12-04  >`、两条 `Expected CodeErrorException to be thrown, but nothing was thrown` |
| 前端 | `vue-tsc` 退出 0；`vitest run` 428 tests / 38 files 全绿（Warehouse 11、InventoryMonitor 15）；`vite build` 通过 |
| Playwright | 新增 3 条（PUT 回显、空白不发请求、POST calc 后展示条数）全通过 |
| 端点清点 | 43 → **41**；`WarehouseController` 与 `ReplenishmentController` 的本轮候选清零 |
| 清点工具自检 | `python tools/schema/endpoint_coverage_audit.py --self-test` → 5/5（7j 里补的两条回归仍成立） |

`GET /spapi/replenish/urgent/{shopId}` 判为不接：它是 `/replenish/list/{shopId}` 的真子集
（同表同店，只多一个 `urgency_level = 'URGENT'` 条件），而列表接口已按紧急度倒序返回并在页面上分档展示；
再接一条只会多一次无谓往返。

## 4. 变异检查（5/5 咬住，三个文件字节级还原）

| 变异 | 重新注入的东西 | 应变红 | 实测 |
| --- | --- | --- | --- |
| M10 | 去掉空值判断（`if (false && trimmed.isEmpty())`） | `rejectsBlankLocation` | 1/1 红（Tests run 4, Failures 1） |
| M11 | `LOCATION_CODE_MAX_LENGTH` 改成 500 | `rejectsOverlongLocation` | 1/1 红 |
| M12 | 不做 `trim()` | `acceptsAndTrims` | 红，且顺带打断 `rejectsBlankLocation`（"   " 不再等价于空）→ Failures 2 |
| M13 | 前端取输入不去 `trim` | 「空白不发请求」+「PUT 带的是去空格后的值」 | 2 failed / 9 passed / 11，与预期一致 |
| M14 | 重算成功后不再 `reloadData()` | 「展示条数并重新拉列表」 | 1 failed / 14 passed / 15 |

还原后 sha256：`WarehouseServiceImpl.java f2ff33c9818a3859`、`Warehouse.vue 6b9e9dfc1e9f6954`、
`InventoryMonitor.vue 2097c8fc2de035f0`（每次都在 `finally` 里写回原字节并核对）。

## 5. 遗留（本轮不做，理由写清楚）

- `GET /spapi/replenish/list/{shopId}` 与 `/urgent/{shopId}` 是**无上限 selectList**（没有 LIMIT、没有游标）。
  本轮没动它：返回体是裸 `List`，加 `truncated` 就得改契约，而页面已经在 join 这条列表。
  补法要么给它上 keyset 游标（与 `/warehouse/inventory` 同形制），要么返回 `Result.paged`。
- 角色门禁 `@RequireRole({"OPERATOR","ADMIN"})` 只在后端；前端没有可读的角色来源
  （`localStorage` 里没有 role，`/user/getInfo` 的 `UserVo` 也没有角色字段），
  所以 VIEWER 点「保存」会吃到 403 并把错误显示在格子上。要做到「看不到按钮」需要先有身份元数据来源。
- 库位「解绑」（清空）没有端点，本轮明确不提供假按钮；真要做得加一个显式动作。
- `ProductController` 的 `GET /product/getProductList`、`getProductsByShop/{productId}`、`postProduct`
  是三条无上限/无注解端点（写路径只用 lenient `isShopAllowed`），与 `api/listing.ts` 里
  两个从未被引用的 `getKeepaPrice/getKeepaRank` 一起留给下一轮处置——那两个死导出会让清点工具
  把没接的 Keepa 端点误报成已覆盖（与 7j 的 `/ai/chat` 同一类假绿）。
