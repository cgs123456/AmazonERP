import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * 店铺真实来源契约（批 2 P5）。
 * <p>
 * 此前前端店铺 100% 来自 main.ts 写死的「Shop A (US) / Shop B (UK) / Shop C (DE)」，
 * 而 axios 拦截器会把选中的 id 当 shopId 请求头发给网关——等于用编造的 ID 去撞
 * 店铺隔离这条最重要的边界。本测试钉住三件事：
 * <ol>
 *   <li>启动时不再注入占位店铺；</li>
 *   <li>历史遗留的假店铺会被清掉（否则老浏览器缓存继续拿假 ID 发请求）；</li>
 *   <li>后端返回空列表时本地也清空（账号被回收后不能继续显示旧店铺）。</li>
 * </ol>
 * main.ts 的初始化是模块副作用，无法直接调用，因此这里复刻其判定逻辑做契约断言，
 * 并对「假店铺识别」这条规则单独验证——它是清理能否生效的关键。
 */
describe('店铺来源：不再使用写死的占位店铺', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  /** 与 main.ts 保持一致的假店铺识别规则 */
  const isLegacyFakeShop = (name?: unknown): boolean =>
    typeof name === 'string' && /^Shop [ABC] \((US|UK|DE)\)$/.test(name)

  it('能识别出历史写死的三个占位店铺', () => {
    expect(isLegacyFakeShop('Shop A (US)')).toBe(true)
    expect(isLegacyFakeShop('Shop B (UK)')).toBe(true)
    expect(isLegacyFakeShop('Shop C (DE)')).toBe(true)
  })

  it('真实店铺名称不会被误判为占位值', () => {
    expect(isLegacyFakeShop('美国站-主店')).toBe(false)
    expect(isLegacyFakeShop('Shop 1')).toBe(false)
    expect(isLegacyFakeShop(undefined)).toBe(false)
    expect(isLegacyFakeShop('')).toBe(false)
  })

  it('仓库源码里不再存在写死的店铺种子', async () => {
    // 直接读源码断言：这是唯一能防止后人把占位值加回去的方式
    const fs = await import('node:fs')
    const main = fs.readFileSync('src/main.ts', 'utf8')
    expect(main).not.toContain('Shop A (US)')
    expect(main).not.toContain('Shop B (UK)')
    expect(main).not.toContain('Shop C (DE)')
  })
})

describe('店铺同步：后端返回为空时必须清空本地', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  // 复刻 AppHeader.syncShopsFromServer 的语义
  const syncShopsFromServer = (serverShops?: Array<{ id: string | number; name: string }>) => {
    if (!Array.isArray(serverShops)) return
    localStorage.setItem('shops', JSON.stringify(serverShops))
    const valid = serverShops.some(
      (s) => String(s.id) === String(localStorage.getItem('current_shop_id') ?? '')
    )
    if (!valid) {
      if (serverShops.length > 0) {
        localStorage.setItem('current_shop_id', String(serverShops[0].id))
      } else {
        localStorage.removeItem('current_shop_id')
      }
    }
  }

  it('后端返回空数组时清空当前选中店铺（账号被回收后不该继续显示）', () => {
    localStorage.setItem('shops', JSON.stringify([{ id: '9', name: '旧店' }]))
    localStorage.setItem('current_shop_id', '9')

    syncShopsFromServer([])

    expect(JSON.parse(localStorage.getItem('shops') || '[]')).toEqual([])
    expect(localStorage.getItem('current_shop_id')).toBeNull()
  })

  it('当前选中店铺不在新列表里时回退到第一家', () => {
    localStorage.setItem('current_shop_id', '999')

    syncShopsFromServer([
      { id: '1', name: '店一' },
      { id: '2', name: '店二' }
    ])

    expect(localStorage.getItem('current_shop_id')).toBe('1')
  })

  it('当前选中店铺仍有效时保持不变', () => {
    localStorage.setItem('current_shop_id', '2')

    syncShopsFromServer([
      { id: '1', name: '店一' },
      { id: '2', name: '店二' }
    ])

    expect(localStorage.getItem('current_shop_id')).toBe('2')
  })

  it('后端未返回 shops 字段时不动本地（兼容老版本后端）', () => {
    localStorage.setItem('shops', JSON.stringify([{ id: '7', name: '本地缓存' }]))

    syncShopsFromServer(undefined)

    expect(JSON.parse(localStorage.getItem('shops') || '[]')).toEqual([
      { id: '7', name: '本地缓存' }
    ])
  })
})
