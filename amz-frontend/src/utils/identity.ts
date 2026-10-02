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
