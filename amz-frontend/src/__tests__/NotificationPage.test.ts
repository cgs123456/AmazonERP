import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 消息中心「全部已读」：接上孤儿 api markAllNotificationsRead
 * （后端 POST /user/notification/read-all）。
 * <p>
 * 后端端点与单测早已就绪，前端此前零调用。本测试钉住：有未读行时才出现按钮、
 * 点击先二次确认（说明不可撤销）、成功后重拉列表并把本地未读清掉、
 * 失败时点名原因且不装作成功。操作后派发 `amz:notifications-changed` 让顶栏红点同步。
 */
const { listNotificationsMock, markAllNotificationsReadMock } = vi.hoisted(() => ({
  listNotificationsMock: vi.fn(),
  markAllNotificationsReadMock: vi.fn()
}))

vi.mock('@/api/notification', () => ({
  listNotifications: listNotificationsMock,
  markAllNotificationsRead: markAllNotificationsReadMock,
  markNotificationRead: vi.fn(),
  default: { get: vi.fn(), post: vi.fn() }
}))

vi.mock('@/composables/useWebSocket', () => ({
  useWebSocket: () => ({ isConnected: { value: true }, send: vi.fn(), onMessage: vi.fn() })
}))

import NotificationPage from '../views/NotificationPage.vue'

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const ok = (data: unknown) => ({ code: 200, data, message: 'ok' })
const fail = (message: string) => ({ code: 400, data: null, message })

const ROWS = [
  { id: 11, type: 0, title: '库存预警', content: 'SKU-A 低于安全库存', isRead: 0, createTime: '2026-10-01T09:00:00' },
  { id: 10, type: 1, title: '订单异常', content: '订单 113-0000000-0000001 延迟', isRead: 0, createTime: '2026-10-01T08:00:00' }
]

const mountPage = async () => {
  const wrapper = mount(NotificationPage, { global: stubs })
  await flushPromises()
  return wrapper
}

const btnByText = (wrapper: any, text: string) =>
  wrapper.findAll('button').filter((b: any) => b.text() === text)

describe('消息中心 · 全部已读', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    listNotificationsMock.mockResolvedValue(ok(ROWS))
    markAllNotificationsReadMock.mockResolvedValue(ok(true))
  })

  it('列表为空时不显示「全部已读」按钮（没有未读可清）', async () => {
    listNotificationsMock.mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    expect(btnByText(wrapper, '全部已读').length).toBe(0)
  })

  it('有未读行时显示「全部已读」按钮，且只出现一次', async () => {
    const wrapper = await mountPage()
    expect(btnByText(wrapper, '全部已读').length).toBe(1)
  })

  it('点击先二次确认，确认文案说明不可撤销并点明影响范围', async () => {
    const wrapper = await mountPage()
    await btnByText(wrapper, '全部已读')[0].trigger('click')
    await flushPromises()

    const modal = wrapper.find('.modal')
    expect(modal.exists(), '没有出现二次确认框').toBe(true)
    expect(modal.text()).toContain('全部标记为已读')
    expect(modal.text()).toContain('不可撤销')
    // 未确认前不能真调后端
    expect(markAllNotificationsReadMock).not.toHaveBeenCalled()
  })

  it('确认后调用后端、重拉列表，并派发通知已变化事件（顶栏红点同步）', async () => {
    const wrapper = await mountPage()
    const changed = vi.fn()
    window.addEventListener('amz:notifications-changed', changed)

    await btnByText(wrapper, '全部已读')[0].trigger('click')
    await flushPromises()
    await btnByText(wrapper, '确认执行')[0].trigger('click')
    await flushPromises()

    expect(markAllNotificationsReadMock).toHaveBeenCalledTimes(1)
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect(changed).toHaveBeenCalledTimes(1)
    window.removeEventListener('amz:notifications-changed', changed)
  })

  it('后端拒绝时点名原因，不刷新列表、不装作成功', async () => {
    markAllNotificationsReadMock.mockResolvedValue(fail('未获取到登录用户'))
    const wrapper = await mountPage()
    await btnByText(wrapper, '全部已读')[0].trigger('click')
    await flushPromises()
    await btnByText(wrapper, '确认执行')[0].trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('未获取到登录用户')
    // 失败就不要重拉列表（否则看起来像「刷新一下就好了」）
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
  })
})
