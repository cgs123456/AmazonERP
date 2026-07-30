// 店铺选择与存储工具
// localStorage 键约定（与后端 JWT 授权 shops 列表配合）：
//   'current_shop_id' — 当前选中店铺 ID（字符串）
//   'shops'           — 用户授权店铺数组 JSON：[{ id: string|number, name: string }]
// 网关 MyGlobalFilter 要求 /shop/ /product/ /order/ 路径请求头携带 shopId，
// 且 shopId 必须落在 JWT 授权 shops 列表内，故所有 api/*.ts 请求统一通过
// axios 拦截器注入 shopId header（见 src/api/auth.ts）。

export interface ShopOption {
  id: string | number
  name: string
}

const CURRENT_SHOP_KEY = 'current_shop_id'
const SHOPS_KEY = 'shops'

/**
 * 获取当前选中店铺 ID。
 * 优先返回 localStorage 的 'current_shop_id'；
 * 若无但 shops 列表非空，则回退到第一个并写入；
 * 否则返回空字符串（调用方应据此提示用户选择店铺）。
 */
export const getCurrentShopId = (): string => {
  let id = localStorage.getItem(CURRENT_SHOP_KEY) || ''
  if (!id) {
    const shops = getShops()
    if (shops.length > 0) {
      id = String(shops[0].id)
      localStorage.setItem(CURRENT_SHOP_KEY, id)
    }
  }
  return id
}

/** 设置当前选中店铺 ID */
export const setCurrentShopId = (id: string | number): void => {
  localStorage.setItem(CURRENT_SHOP_KEY, String(id))
}

/** 读取 localStorage 中的授权店铺列表 */
export const getShops = (): ShopOption[] => {
  try {
    const raw = localStorage.getItem(SHOPS_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((s: unknown): s is ShopOption => !!s && typeof s === 'object' && 'id' in s)
      .map((s: ShopOption) => ({ id: s.id, name: s.name ?? String(s.id) }))
  } catch {
    return []
  }
}

/** 写入授权店铺列表到 localStorage */
export const setShops = (shops: ShopOption[]): void => {
  localStorage.setItem(SHOPS_KEY, JSON.stringify(shops))
}
