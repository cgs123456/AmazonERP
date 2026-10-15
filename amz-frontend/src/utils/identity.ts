/**
 * 当前登录用户的角色（后端 JWT 里的 role）。
 *
 * 以前前端没有任何角色来源：LoginModal 只写 token/refreshToken/token_expiry，
 * 而 GET /user/getInfo 的响应里其实一直带着 `user.role`，只是前端 UserVo 类型没声明它。
 * 这里把它读出来存住，用于「点了必然 403」的入口提前给出理由；
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

/** 门控隐藏时给用户的说明文案：不解释「为什么没有按钮」用户只会以为功能没了。 */
export const WRITE_LOCKED_HINT = '需 OPERATOR/ADMIN'
