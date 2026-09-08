import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { useShopGuard } from '@/composables/useShopGuard'
import { useMockFlag } from '@/composables/useMockFlag'

describe('useShopGuard（店铺上下文守卫）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('挂载时快照当前店铺', () => {
    localStorage.setItem('current_shop_id', '3')
    const { currentShopId } = useShopGuard()
    expect(currentShopId.value).toBe('3')
  })

  it('无店铺时快照为空字符串', () => {
    const { currentShopId } = useShopGuard()
    expect(currentShopId.value).toBe('')
  })

  it('refreshShop 同步最新值并回写快照（切店铺不重挂载场景）', () => {
    localStorage.setItem('current_shop_id', '1')
    const { currentShopId, refreshShop } = useShopGuard()
    localStorage.setItem('current_shop_id', '2')
    // 快照仍是旧值，直到刷新
    expect(currentShopId.value).toBe('1')
    expect(refreshShop()).toBe('2')
    expect(currentShopId.value).toBe('2')
  })
})

describe('useMockFlag（降级 mock 标识）', () => {
  it('初始为 mock 态', () => {
    expect(useMockFlag().isMock.value).toBe(true)
  })

  it('markLive 摘徽，markMock 重新挂徽', () => {
    const { isMock, markLive, markMock } = useMockFlag()
    markLive()
    expect(isMock.value).toBe(false)
    markMock()
    expect(isMock.value).toBe(true)
  })
})
