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
  // localStorage 在隐私模式/禁用存储下抛错：此处是全站守卫入口，必须兜底空串防白屏
  try {
    let id = localStorage.getItem(CURRENT_SHOP_KEY) || ''
    if (!id) {
      const shops = getShops()
      if (shops.length > 0) {
        id = String(shops[0].id)
        localStorage.setItem(CURRENT_SHOP_KEY, id)
      }
    }
    return id
  } catch {
    return ''
  }
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

/**
 * 历史占位店铺的识别模式。
 * <p>
 * 在本仓库的某个阶段，{@code main.ts} 会把「Shop A (US) / Shop B (UK) / Shop C (DE)」
 * 三个写死的示例店铺塞进 localStorage，而 axios 拦截器会把选中的 id 当 shopId 请求头
 * 发给网关——等于用编造的店铺 ID 去撞店铺隔离这条边界。后端补上
 * {@code GET /user/shop/list} 与 {@code UserVo.shops} 之后，这三个值必须被清掉。
 */
const LEGACY_PLACEHOLDER_PATTERN = /^Shop [ABC] \((US|UK|DE)\)$/

/**
 * 清理历史遗留的占位店铺缓存（启动时调用一次）。
 * <p>
 * 只清「能确认是占位值」的那一份：真实店铺名不会命中该模式，
 * 因此老用户的既有数据不会被误删。命中时连当前选中项一起清，
 * 否则会残留一个指向不存在店铺的 current_shop_id。
 */
export const purgeLegacyPlaceholderShops = (): void => {
  const raw = localStorage.getItem(SHOPS_KEY)
  if (!raw) return
  try {
    const parsed = JSON.parse(raw) as Array<{ id?: unknown; name?: unknown }>
    const hasPlaceholder = parsed.some(
      (s) => typeof s?.name === 'string' && LEGACY_PLACEHOLDER_PATTERN.test(s.name)
    )
    if (hasPlaceholder) {
      localStorage.removeItem(SHOPS_KEY)
      localStorage.removeItem(CURRENT_SHOP_KEY)
    }
  } catch {
    // 解析不了就当作没有店铺数据，交由登录流程重新写入
    localStorage.removeItem(SHOPS_KEY)
  }
}
