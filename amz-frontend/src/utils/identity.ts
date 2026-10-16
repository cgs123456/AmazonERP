/**
 * 当前登录用户**在当前店铺上**的角色。
 *
 * 唯一来源是 GET /user/getInfo 的 `effectiveRole`（店铺级角色优先、全局角色兜底，
 * 与后端鉴权同一口径）。**不要改成读 `user.role`**：那是全局角色，用它做显隐会让
 * 「店 A 是 OPERATOR、店 B 是 VIEWER」的用户在店 B 看到 OPERATOR 的界面——
 * 按钮亮着、点下去报无权限。切店会整页刷新（AppHeader.handleShopChange），
 * getInfo 带新 shopId 头重跑，所以本值在切店后自动是新的。
 *
 * 它只是显示优化，权限的权威判定始终在后端。
 */
const ROLE_KEY = 'user_role'

export const ROLE_CHANGED_EVENT = 'amz:role-changed'

/**
 * 写入角色并广播变化：AppHeader 的 getInfo 与各页面的挂载是并发的，
 * 只在 setup 里读一次会稳定读到「角色未知」，入口该隐藏时也隐藏不了。
 */
export const setUserRole = (role?: string | null): void => {
  if (role) {
    localStorage.setItem(ROLE_KEY, role)
  } else {
    localStorage.removeItem(ROLE_KEY)
  }
  window.dispatchEvent(new Event(ROLE_CHANGED_EVENT))
}

export const getUserRole = (): string => {
  try {
    return localStorage.getItem(ROLE_KEY) || ''
  } catch {
    return ''
  }
}

export const clearUserRole = (): void => {
  try {
    localStorage.removeItem(ROLE_KEY)
  } catch {
    // localStorage 在隐私模式下会抛，这里无需处理
  }
}

/**
 * 未知角色不隐藏按钮：硬刷新某个页面时 AppHeader 的 getInfo 可能还没回来，
 * 因为「还没取到角色」就把功能藏掉，等于让前端状态去否决后端能力。
 *
 * 注意「未知」的意思：`effectiveRole` 取不到值（老后端没这个字段、或请求还没回来）。
 * 后端既然能返回 `user.role`，`effectiveRole` 缺席只可能是版本落后，
 * 此时放行按钮、由后端按真实角色判定，比前端猜一个更保守的值更安全。
 */
export const canUseRole = (allowed: string[]): boolean => {
  const role = getUserRole()
  return role === '' || allowed.includes(role)
}

export const LOCATION_EDIT_ROLES = ['OPERATOR', 'ADMIN']

/**
 * 仓库新建/编辑（POST/PUT /logistics/warehouse）的守卫角色。
 * 与 LOCATION_EDIT_ROLES 目前取值相同，但**不是同一个语义**：库位码改的是单条库存记录，
 * 仓库新建/编辑改的是主数据。两边后端各自声明 @RequireRole，将来只要有一边放开
 * （比如仓库编码允许 OPERATOR 建、只有 ADMIN 能改），这两个常量就该分开——
 * 现在合成一个会让「改一处以为两处都改了」。
 */
export const WAREHOUSE_WRITE_ROLES = ['OPERATOR', 'ADMIN']

/**
 * WMS 单据写入（入库单 / 出库单 / 盘点单）的守卫角色。
 *
 * 合并成一个常量的理由，以及**什么时候必须拆开**：
 * <p>
 * 后端 InboundController、OutboundController、StocktakeController 目前**逐条声明**同一个
 * {@code @RequireRole({"OPERATOR","ADMIN"})}（共 15 个端点），取值完全相同。这三类的
 * 用户可见语义也确实是同一件事——「仓里发生了什么」的记账动作，所以这里合并。
 * <p>
 * 但合并是有条件的，不是「因为长得像」：一旦后端对某一族放开（例如入库单允许 VIEWER
 * 建、盘点落账仍然只给 ADMIN），就必须拆成 INBOUND_WRITE_ROLES /
 * OUTBOUND_WRITE_ROLES / STOCKTAKE_WRITE_ROLES 三个常量。继续共用一个常量的后果是
 * 前端会对已经放开的那一族仍然隐藏入口——**前端用旧判断否决了后端的新能力**，
 * 而这类不一致没有任何测试会发现（后端放开了，前端却藏着）。
 * <p>
 * 与 WAREHOUSE_WRITE_ROLES 分开是因为仓库主数据不是单据流：改仓库名与「这一单收了货」
 * 不是同一个授权决策，合并会让「改一处以为两处都改了」。
 */
export const WMS_WRITE_ROLES = ['OPERATOR', 'ADMIN']

/**
 * 订单履约写入（配货出库 / 向 Amazon 确认发货）的守卫角色。
 * <p>
 * 后端 OrderFulfillmentController 的两个端点**逐条声明**同一个
 * {@code @RequireRole({"OPERATOR","ADMIN"})}，用户可见语义也是同一件事——「货出去了」，
 * 所以这里合并。
 * <p>
 * 合并的条件与 WMS_WRITE_ROLES 相同：一旦后端对其中一个放开，就必须拆成
 * ALLOCATE_WRITE_ROLES / CONFIRM_SHIP_WRITE_ROLES。继续共用一个常量的后果是前端
 * 对已放开的那一侧仍然隐藏入口——前端用旧判断否决后端的新能力，而这类不一致
 * 没有任何测试会发现。
 * <p>
 * 与 WMS_WRITE_ROLES 分开是因为授权决策不同：仓内记账是「这一单收了货」，
 * 订单履约是「这一单货出去了并通知了 Amazon」。合并会让「改一处以为两处都改了」。
 */
export const ORDER_FULFILLMENT_WRITE_ROLES = ['OPERATOR', 'ADMIN']

/**
 * 财务写入（凭证 / 结算同步 / 差异扫描 / 索赔 / 回款重算 / VAT 月结）的守卫角色。
 * <p>
 * 后端 FinanceController、SettlementController、FeeDiscrepancyController、
 * ReimbursementClaimController、PaymentCollectionController、VatController 共 15 个
 * 写端点**逐条声明**同一个 {@code @RequireRole({"OPERATOR","ADMIN"})}。
 * <p>
 * 单独一个常量而不是复用 WMS_WRITE_ROLES：财务写的是账，「记账」与「仓里发生了什么」
 * 不是同一个授权决策。FinanceControllerGuardTest 已有断言钉住这 15 个端点的数量，
 * 新增财务写端点必须同步更新该测试——那正是这个常量该跟着改的信号。
 * <p>
 * 计数口径：`@Post/Put/Delete/PatchMapping` 方法数（`amz-service-finance/src/main`
 * 下六个 Controller 合计 15，与 `tools/schema/endpoint_role_audit.py` 和
 * FinanceControllerGuardTest 的断言三方一致）。此前这里写 14，是漏数了
 * `/voucher/from-settlement` 与 `/voucher/procurement` 之一。
 */
export const FINANCE_WRITE_ROLES = ['OPERATOR', 'ADMIN']

/**
 * 商品主数据 CSV 导入（POST /product/master/import/csv）的守卫角色。
 * <p>
 * **只覆盖这一个端点**，不要因为「都是 ListingMonitor 页的写操作」就把它扩成
 * 「ListingMonitor 页写角色」：同一个页面上的 POST /product/master（建/改）与
 * /product/listing-monitor 的 health/check、ranking、competitor、buybox 在后端
 * <b>都没有 @RequireRole</b>，VIEWER 与 OPERATOR 权限相同。用这个常量去门控那些入口，
 * 就是前端否决后端已有的能力，而且没有任何测试会发现这种不一致。
 * <p>
 * 后端哪天给那些端点补上 @RequireRole，这里要按新角色集逐个补门控——那是改这个常量
 * 的语义（从「一个端点」变成「一族端点」）的信号，届时应重命名而不是就地扩用途。
 */
export const PRODUCT_MASTER_WRITE_ROLES = ['OPERATOR', 'ADMIN']

/** 门控隐藏时给用户的说明文案：不解释「为什么没有按钮」用户只会以为功能没了。 */
export const WRITE_LOCKED_HINT = '需 OPERATOR/ADMIN'
