import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { useShopGuard } from '@/composables/useShopGuard'
import { useShopSelection } from '@/composables/useShopSelection'
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

describe('useShopSelection（店铺选择器状态与同步）', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('创建时即从 localStorage 读回状态（否则首次 getInfo 前 currentShopId 恒为空）', () => {
    // 这条是回归守卫：曾把 getCurrentShopId() 直读改成响应式值后忘了初始化，
    // 导致「本次请求是否带了店铺」的判定失真，getInfo 被多打一次。
    localStorage.setItem('current_shop_id', '9001')
    localStorage.setItem('shops', JSON.stringify([{ id: 9001, name: '美国站' }]))
    const { currentShopId, shops } = useShopSelection()
    expect(currentShopId.value).toBe('9001')
    expect(shops.value).toHaveLength(1)
  })

  it('applyFromUserInfo：可信时同步，且返回 true', () => {
    const { applyFromUserInfo, shops, currentShopId } = useShopSelection()
    const ok = applyFromUserInfo({
      shops: [{ id: 42, name: '新店' }],
      shopsLoaded: true,
    })
    expect(ok).toBe(true)
    expect(shops.value).toEqual([{ id: 42, name: '新店' }])
    expect(currentShopId.value).toBe('42')
    expect(localStorage.getItem('current_shop_id')).toBe('42')
  })

  it('applyFromUserInfo：shopsLoaded=false 时不动本地缓存并返回 false', () => {
    localStorage.setItem('current_shop_id', '7')
    localStorage.setItem('shops', JSON.stringify([{ id: 7, name: '旧店' }]))
    const { applyFromUserInfo, shops, currentShopId } = useShopSelection()
    const ok = applyFromUserInfo({ shops: null, shopsLoaded: false })
    expect(ok).toBe(false)
    expect(shops.value).toEqual([{ id: 7, name: '旧店' }])
    expect(currentShopId.value).toBe('7')
  })

  it('applyFromUserInfo：老后端无 shopsLoaded 字段时按可信处理', () => {
    const { applyFromUserInfo } = useShopSelection()
    expect(applyFromUserInfo({ shops: [] })).toBe(true)
  })

  it('同步后当前选中店铺不在新列表里 → 回退到第一家', () => {
    localStorage.setItem('current_shop_id', '999')
    const { applyFromUserInfo, currentShopId } = useShopSelection()
    applyFromUserInfo({ shops: [{ id: 11, name: 'A' }, { id: 12, name: 'B' }], shopsLoaded: true })
    expect(currentShopId.value).toBe('11')
  })

  it('同步到空数组 → 清掉 current_shop_id（账号店铺被回收后不能留旧值）', () => {
    localStorage.setItem('current_shop_id', '11')
    localStorage.setItem('shops', JSON.stringify([{ id: 11, name: 'A' }]))
    const { applyFromUserInfo, currentShopId } = useShopSelection()
    applyFromUserInfo({ shops: [], shopsLoaded: true })
    expect(localStorage.getItem('current_shop_id')).toBeNull()
    expect(currentShopId.value).toBe('')
  })
})
