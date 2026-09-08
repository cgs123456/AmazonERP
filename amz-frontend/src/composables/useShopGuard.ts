import { ref } from 'vue'
import { getCurrentShopId } from '../utils/shop'

/**
 * 店铺上下文守卫（B4：收敛六页复制的守卫逻辑）。
 * <p>
 * - currentShopId：当前选中店铺（挂载时快照，用于模板 shop-tip 展示）；
 * - refreshShop：每次发请求前同步一次 localStorage（切店铺不重挂载时快照会过期，
 *   直接用快照会查错店；返回最新值，调用方按空阻断查询）。
 */
export function useShopGuard() {
  const currentShopId = ref(getCurrentShopId())

  const refreshShop = (): string => {
    const id = getCurrentShopId()
    currentShopId.value = id
    return id
  }

  return { currentShopId, refreshShop }
}
