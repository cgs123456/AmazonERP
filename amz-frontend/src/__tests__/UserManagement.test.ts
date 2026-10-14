import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 用户管理（P1.4，ADMIN）：账号列表 · 角色分配 · 启用禁用。
 * <p>
 * 该页此前**零测试覆盖**（无 vitest 文件、不在任何 E2E、端点未登记桩）。本测试钉住：
 * 列表渲染、角色下拉（不能改自己）、启用禁用、以及一处真实缺陷——
 * <b>角色/状态修改被后端拒绝时，失败原因必须留在页面上</b>。
 * <p>
 * 缺陷形态：失败分支先 pushError(原因) 再 await load()，而 load() 成功后执行
 * `errors.value = []`，把刚写进去的原因一起清掉——用户只看到下拉弹回旧值、没有任何说明。
 * 这正是仓库反复记录的「用同一个分支处理两类失败」家族（重拉成功把业务失败原因抹掉）。
 */
const { listUsersMock, updateUserRoleMock, updateUserStatusMock } = vi.hoisted(() => ({
  listUsersMock: vi.fn(),
  updateUserRoleMock: vi.fn(),
  updateUserStatusMock: vi.fn()
}))

vi.mock('@/api/userAdmin', () => ({
  listUsers: listUsersMock,
  updateUserRole: updateUserRoleMock,
  updateUserStatus: updateUserStatusMock
}))

import UserManagement from '../views/UserManagement.vue'

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const ok = (data: unknown) => ({ code: 200, data, message: 'ok' })
const fail = (message: string) => ({ code: 400, data: null, message })

const USERS = [
  { id: 1, phone: '13800000000', nickname: '管理员', role: 'ADMIN', status: 1, createTime: '2026-09-01T08:00:00' },
  { id: 2, phone: '13900000000', nickname: '运营甲', role: 'OPERATOR', status: 1, createTime: '2026-09-02T09:30:00' },
  { id: 3, phone: '13700000000', nickname: '访客乙', role: 'VIEWER', status: 0, createTime: '2026-09-03T10:00:00' }
]

const mountPage = async () => {
  const wrapper = mount(UserManagement, { global: stubs })
  await flushPromises()
  return wrapper
}

const rows = (wrapper: any) => wrapper.findAll('tbody tr')
const roleSelect = (wrapper: any, i: number) => rows(wrapper)[i].find('select.role-select')

const setRole = async (wrapper: any, i: number, role: string) => {
  const el = roleSelect(wrapper, i).element as HTMLSelectElement
  el.value = role
  el.dispatchEvent(new Event('change'))
  await flushPromises()
}

const rowBtn = async (wrapper: any, i: number) => {
  const btn = rows(wrapper)[i].find('button.row-btn')
  await btn.trigger('click')
  await flushPromises()
}

describe('用户管理 · 角色分配与启停', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    // 当前登录用户 id=1（页面用「不能操作自己」判断）
    localStorage.setItem('user_id', '1')
    listUsersMock.mockResolvedValue(ok(USERS))
    updateUserRoleMock.mockResolvedValue(ok(USERS[1]))
    updateUserStatusMock.mockResolvedValue(ok(USERS[2]))
  })

  it('渲染账号列表：手机号/昵称/角色/状态中文', async () => {
    const wrapper = await mountPage()
    expect(rows(wrapper)).toHaveLength(3)
    const text = wrapper.find('table').text()
    expect(text).toContain('13800000000')
    expect(text).toContain('运营甲')
    expect(text).toContain('已禁用') // status=0
    expect(text).toContain('正常')
  })

  it('不能修改自己的角色：本人那一行下拉禁用并给出理由', async () => {
    const wrapper = await mountPage()
    const self = roleSelect(wrapper, 0)
    expect(self.attributes('disabled')).toBeDefined()
    expect(self.attributes('title')).toContain('不能修改自己的角色')
    // 别人那一行可以改
    expect(roleSelect(wrapper, 1).attributes('disabled')).toBeUndefined()
  })

  it('改角色成功：下拉停在新值且无错误', async () => {
    const wrapper = await mountPage()
    await setRole(wrapper, 1, 'ADMIN')
    expect(updateUserRoleMock).toHaveBeenCalledWith(2, 'ADMIN')
    expect((roleSelect(wrapper, 1).element as HTMLSelectElement).value).toBe('ADMIN')
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('改角色被后端拒绝：失败原因必须留在页面上（重拉列表不能把它抹掉）', async () => {
    updateUserRoleMock.mockResolvedValue(fail('不能修改自己的角色（防止 ADMIN 全部流失）'))
    const wrapper = await mountPage()
    await setRole(wrapper, 1, 'ADMIN')

    // 关键断言：原因要可见——不能只看到下拉弹回旧值却没有任何说明
    const zone = wrapper.find('.error-zone')
    expect(zone.exists(), '失败原因被 load() 的重拉抹掉了').toBe(true)
    expect(zone.text()).toContain('不能修改自己的角色')
    // 失败要重拉（把下拉弹回服务端真实值），但不能因此丢掉原因
    expect(listUsersMock).toHaveBeenCalledTimes(2)
  })

  it('改角色抛异常（网络类）：同样要点名且保留原因', async () => {
    updateUserRoleMock.mockRejectedValue(new Error('Network Error'))
    const wrapper = await mountPage()
    await setRole(wrapper, 1, 'VIEWER')

    const zone = wrapper.find('.error-zone')
    expect(zone.exists()).toBe(true)
    expect(zone.text()).toContain('Network Error')
  })

  it('启用/禁用：成功时状态列翻转', async () => {
    const wrapper = await mountPage()
    // 第 3 行 status=0（已禁用）→ 点「启用」
    expect(rows(wrapper)[2].text()).toContain('已禁用')
    await rowBtn(wrapper, 2)
    expect(updateUserStatusMock).toHaveBeenCalledWith(3, 1)
    expect(rows(wrapper)[2].text()).toContain('正常')
  })

  it('禁用被拒绝：原因同样留在页面上', async () => {
    updateUserStatusMock.mockResolvedValue(fail('不能禁用自己的账号'))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 2)
    const zone = wrapper.find('.error-zone')
    expect(zone.exists()).toBe(true)
    expect(zone.text()).toContain('不能禁用自己的账号')
  })

  it('不能禁用自己的账号：本人那一行按钮禁用', async () => {
    const wrapper = await mountPage()
    expect(rows(wrapper)[0].find('button.row-btn').attributes('disabled')).toBeDefined()
  })

  it('列表加载失败时点名「用户列表」，并给空表提示', async () => {
    listUsersMock.mockResolvedValue(fail('无权限执行该操作（需要角色：ADMIN）'))
    const wrapper = await mountPage()
    const zone = wrapper.find('.error-zone')
    expect(zone.text()).toContain('用户列表')
    expect(zone.text()).toContain('ADMIN')
    expect(rows(wrapper)).toHaveLength(1) // 只剩空态行
    expect(wrapper.text()).toContain('无用户数据')
  })

  it('说明区三条边界常驻（含「不能改自己」与 token 24h 语义）', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('不能修改自己的角色')
    expect(note).toContain('禁用立即拦登录')
    expect(note).toContain('24 小时')
  })
})
