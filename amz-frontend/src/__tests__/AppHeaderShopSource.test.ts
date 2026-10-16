import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * AppHeader 店铺选择器：真实来源链路（批 2 P5）。
 * <p>
 * 此前店铺 100% 来自 main.ts 写死的「Shop A/B/C」，`setShops` 在生产代码里
 * 零调用——也就是说后端就算返回了店铺，前端也没人用。本测试钉住新链路：
 * 登录态下 GET /user/getInfo 返回的 shops → 写入 localStorage → 下拉真的渲染出这些店。
 */
const { getUserInfoMock, pushMock } = vi.hoisted(() => ({
  getUserInfoMock: vi.fn(),
  pushMock: vi.fn()
}))

vi.mock('../api/auth', () => ({
  getUserInfo: getUserInfoMock,
  default: { get: vi.fn(), post: vi.fn() }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => ({ path: '/' }),
  RouterLink: { template: '<a><slot /></a>' }
}))

import AppHeader from '../components/AppHeader.vue'

describe('AppHeader 店铺来源', () => {
  beforeEach(() => {
    localStorage.clear()
    getUserInfoMock.mockReset()
    pushMock.mockClear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('getInfo 返回的店铺被写入 localStorage 并渲染到下拉', async () => {
    localStorage.setItem('token', 'fake-token')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'ADMIN' },
        shops: [
          { id: 9001, name: '美国站主店' },
          { id: 9002, name: '德国站' }
        ]
      }
    })

    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    const stored = JSON.parse(localStorage.getItem('shops') || '[]')
    expect(stored).toHaveLength(2)
    expect(stored.map((s: { name: string }) => s.name)).toEqual(['美国站主店', '德国站'])

    const options = wrapper.findAll('option').map((o) => o.text())
    expect(options).toContain('美国站主店')
    expect(options).toContain('德国站')
    // 不得再出现任何写死的占位店铺
    expect(options.join('|')).not.toContain('Shop A')
  })

  it('后端返回空店铺列表时清空本地（账号被回收后不残留旧店）', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('shops', JSON.stringify([{ id: '9', name: '已被回收的店' }]))
    localStorage.setItem('current_shop_id', '9')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: { user: { id: 1, nickname: '运营', role: 'VIEWER' }, shops: [] }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(JSON.parse(localStorage.getItem('shops') || '[]')).toEqual([])
    expect(localStorage.getItem('current_shop_id')).toBeNull()
  })

  it('当前选中店铺不在新列表时回退到第一家', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('current_shop_id', '9999')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'OPERATOR' },
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(localStorage.getItem('current_shop_id')).toBe('9001')
  })

  it('未登录时不请求 getInfo，店铺保持为空', async () => {
    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(getUserInfoMock).not.toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem('shops') || '[]')).toEqual([])
    expect(wrapper.findAll('option')).toHaveLength(0)
  })

  // 店铺级角色接通（HANDOFF §S22）：前端显隐必须按**当前店铺上的有效角色**，
  // 而不是全局角色。读 user.role 会让「店 A 是 OPERATOR、店 B 是 VIEWER」的用户
  // 在店 B 看到 OPERATOR 的界面——按钮亮着，点下去报无权限。
  it('角色存的是 effectiveRole（当前店上的有效角色）而不是全局 role', async () => {
    localStorage.setItem('token', 'fake-token')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'ADMIN' },   // 全局 ADMIN
        effectiveRole: 'VIEWER',                            // 但这家店只给了 VIEWER
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(localStorage.getItem('user_role')).toBe('VIEWER')
  })

  it('老后端没有 effectiveRole 字段时回落 user.role，行为不变', async () => {
    localStorage.setItem('token', 'fake-token')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'OPERATOR' },
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(localStorage.getItem('user_role')).toBe('OPERATOR')
  })

  // 首次登录时 localStorage 里还没有 current_shop_id，第一次 getInfo 不带 shopId 头，
  // 后端只能按「无店铺上下文」算 effectiveRole（= 全局角色）。若不重取，
  // 「店 A 是 OPERATOR、店 B 是 VIEWER」的用户在默认登录店会拿到错的角色。
  it('首次登录定下默认店铺后必须重取一次，拿到该店的有效角色', async () => {
    localStorage.setItem('token', 'fake-token')
    // 第一次调用：无店铺上下文 → effectiveRole 回落全局 ADMIN
    getUserInfoMock.mockResolvedValueOnce({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'ADMIN' },
        effectiveRole: 'ADMIN',
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })
    // 第二次调用：已选中 9001 → effectiveRole 是该店的真实角色 VIEWER
    getUserInfoMock.mockResolvedValueOnce({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'ADMIN' },
        effectiveRole: 'VIEWER',
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(getUserInfoMock).toHaveBeenCalledTimes(2)
    expect(localStorage.getItem('current_shop_id')).toBe('9001')
    expect(localStorage.getItem('user_role')).toBe('VIEWER')
  })

  it('店铺选择未变时不重取（避免每次挂载都多打一次 getInfo）', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('current_shop_id', '9001')
    getUserInfoMock.mockResolvedValue({
      code: 200,
      data: {
        user: { id: 1, nickname: '运营', role: 'OPERATOR' },
        effectiveRole: 'OPERATOR',
        shops: [{ id: 9001, name: '美国站主店' }]
      }
    })

    mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(getUserInfoMock).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('user_role')).toBe('OPERATOR')
  })
})
