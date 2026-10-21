import { ref } from 'vue'
import {
  getShops,
  getCurrentShopId,
  setCurrentShopId,
  setShops,
  isShopsLoadTrusted,
  type ShopOption,
} from '../utils/shop'

/**
 * 顶栏店铺选择器的状态与同步逻辑。
 *
 * <b>为什么从 AppHeader 拆出来</b>：AppHeader 反复触到文件行数上限（500 行棘轮），
 * 根因是它同时管着用户信息、未读数、登录态、店铺选择四件事。
 * 店铺这一组（响应式状态 + 与后端的同步 + 切换动作）本身是内聚的完整关注点，
 * 约 60 行，拆走后 AppHeader 只剩「组装」职责。
 *
 * <b>为什么同步逻辑值得单独放</b>：`shopsLoaded` 的三种语义（查到 / 查到但没店 /
 * 查失败）在这里分流，而分错的代价是整站越权报错——这段逻辑需要被单独测试覆盖，
 * 埋在组件里测不到。
 */
export function useShopSelection() {
  /** 当前选中店铺 id（字符串，与 localStorage 'current_shop_id' 一致） */
  const currentShopId = ref('')
  /** 授权店铺列表，来源 localStorage 'shops' */
  const shops = ref<ShopOption[]>([])

  // 创建时先从 localStorage 读一次。
  // 不读的后果：调用方在第一次 getInfo 之前拿到的 currentShopId 恒为 ''，
  // 于是「本次请求有没有带店铺」的判定失真——原实现每次调 getCurrentShopId()
  // 都直读 localStorage，改成响应式值后这个语义必须在入口处补回。
  currentShopId.value = getCurrentShopId()
  shops.value = getShops()

  /** 从 localStorage 读回响应式状态（不动缓存本身）。 */
  const reload = () => {
    shops.value = getShops()
    currentShopId.value = getCurrentShopId()
  }

  /**
   * 用后端返回的店铺覆盖本地缓存（全站唯一写入口）。
   * <p>
   * 空数组也要写入：后端明确「该账号没有店铺」时，继续留着上一次的旧列表
   * 会让用户以为还能访问已被回收的店（而请求会被网关按 shopId 越权拒绝，
   * 表现为大片「不在授权范围」报错，比直接显示「无可用店铺」更难排查）。
   * 当前选中店铺若已不在新列表里，一并清掉并回退到第一家。
   */
  const syncFromServer = (serverShops?: ShopOption[] | null) => {
    if (!Array.isArray(serverShops)) {
      // 字段缺失或为 null：不动本地，只把缓存读回响应式状态
      reload()
      return
    }
    setShops(serverShops)
    shops.value = getShops()
    const stillValid = shops.value.some((s) => String(s.id) === String(getCurrentShopId()))
    if (!stillValid) {
      if (shops.value.length > 0) {
        setCurrentShopId(String(shops.value[0].id))
      } else {
        localStorage.removeItem('current_shop_id')
      }
    }
    currentShopId.value = getCurrentShopId()
  }

  /**
   * 把 `/user/getInfo` 的店铺字段落到本地。
   * <p>
   * `shopsLoaded=false` 表示后端这次**没查到**（DB 抖动、凭证列解密异常等），
   * 与「查到但该账号没有店铺」是两件事：前者必须保留本地列表——
   * 清空后所有带 shopId 的请求都会带着后端已不认可的 id 发出去，
   * 整站变成大片越权报错，比显示一份可能过期的列表难查得多。
   *
   * `shops` 允许 null：后端查失败时显式返回 null（不用空数组冒充「没店」），
   * 那条路径上类型必须收得下，否则调用方要写 `as any` 才能表达真实响应。
   *
   * @returns 本次是否为可信同步。false 时调用方应提示用户（列表可能是旧的）。
   */
  const applyFromUserInfo = (data: {
    shops?: ShopOption[] | null
    shopsLoaded?: unknown
  }): boolean => {
    if (!isShopsLoadTrusted(data.shopsLoaded)) {
      reload()
      return false
    }
    syncFromServer(data.shops ?? undefined)
    return true
  }

  /**
   * 切换店铺：更新 localStorage 并刷新页面。
   * <p>
   * 整页刷新而不是局部重载：店铺 id 会影响几乎所有页面的数据视图，
   * 逐个页面通知刷新的成本比一次 reload 高，且容易漏。
   */
  const change = (value: string) => {
    setCurrentShopId(value)
    currentShopId.value = value
    window.location.reload()
  }

  return { shops, currentShopId, reload, syncFromServer, applyFromUserInfo, change }
}
