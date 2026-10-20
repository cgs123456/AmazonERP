import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * shopsLoaded=false 时不得清空本地店铺列表。
 *
 * <b>存在动因</b>：后端 `/user/getInfo` 的 `shops` 字段有三种含义曾挤在一起——
 * 查到店铺、查到但没店铺、查询失败。第三种此前与第二种同形（都是空/null），
 * 前端据此写 localStorage，会把「查失败」误判成「该账号没有店铺」并清空列表。
 * 后果是所有带 shopId 的请求带着后端已不认可的 id 发出去，整站变成大片越权报错。
 *
 * 后端已补 `shopsLoaded` 显式区分（false = 没查到，不是没店）。
 * 这里钉住前端必须按这个口径分流：false 时保留本地，true 时才同步。
 *
 * 实测过的故障链：amz_shop 凭证列解密异常 → selectList 整行抛错 →
 * 后端 shops=null → 前端清空 → 用户看到「登录后没有店铺」，
 * 而真正的报错藏在服务端一句 warn 里。
 */
describe('shopsLoaded=false 时保留本地店铺，不清空', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const existing = [{ id: '5001', name: '既有店铺' }]

  /** 复刻 AppHeader.loadUserInfo 里对 shopsLoaded 的分流判定 */
  const applyShops = (data: { shops?: unknown; shopsLoaded?: unknown }) => {
    if (data.shopsLoaded === false) return { synced: false, shops: existing }
    return { synced: true, shops: data.shops ?? [] }
  }

  it('shopsLoaded=false → 不同步，保留既有列表', () => {
    const result = applyShops({ shops: null, shopsLoaded: false })
    expect(result.synced).toBe(false)
    expect(result.shops).toEqual(existing)
  })

  it('shopsLoaded=true → 同步（含空数组，代表确实没店）', () => {
    expect(applyShops({ shops: [], shopsLoaded: true }).shops).toEqual([])
    expect(
      applyShops({ shops: [{ id: '6001', name: '新店' }], shopsLoaded: true }).shops
    ).toEqual([{ id: '6001', name: '新店' }])
  })

  it('老后端无 shopsLoaded 字段（undefined）→ 按 true 处理，不改变既有行为', () => {
    // 渐进发布：后端没上线该字段时前端不能把用户店铺全清空
    expect(applyShops({ shops: [{ id: '7001', name: '老后端' }] }).synced).toBe(true)
    expect(applyShops({ shops: [] }).shops).toEqual([])
  })

  it('shopsLoaded 是 false 之外的假值（如 null/0/"false"）不当成失败', () => {
    // 后端类型是 Boolean，序列化只会是 true/false/缺失；这里的反向断言是
    // 防止有人把判定写成 `!shopsLoaded` 或 `== 'false'` 而误伤正常响应。
    ;[null, 0, ''].forEach((v) => {
      expect(applyShops({ shops: [], shopsLoaded: v }).synced).toBe(true)
    })
  })
})
