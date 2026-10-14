import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'

/**
 * 顶栏未读红点：接上孤儿 api getUnreadCount（后端 GET /user/notification/unread-count）。
 * <p>
 * 该函数此前在 `src/api/notification.ts` 定义但全仓零调用——后端端点与测试早已就绪，
 * 只是前端没人用。本测试钉住新链路：登录态下顶栏「消息」入口显示未读数，
 * 读为 0 时不显示，未登录不请求，收到 `amz:notifications-changed`（已读操作后派发）会重拉。
 */
const { getUserInfoMock, getUnreadCountMock } = vi.hoisted(() => ({
  getUserInfoMock: vi.fn(),
  getUnreadCountMock: vi.fn()
}))

vi.mock('../api/auth', () => ({
  getUserInfo: getUserInfoMock,
  default: { get: vi.fn(), post: vi.fn() }
}))

vi.mock('../api/notification', () => ({
  getUnreadCount: getUnreadCountMock,
  default: { get: vi.fn(), post: vi.fn() }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ path: '/' }),
  RouterLink: { template: '<a><slot /></a>' }
}))

import AppHeader from '../components/AppHeader.vue'

const okUser = () => ({
  code: 200,
  data: { user: { id: 1, nickname: '运营', role: 'ADMIN' }, shops: [] }
})

const badge = (wrapper: any) => wrapper.find('.unread-badge')

// AppHeader 在 onMounted 挂了 window 事件监听；不自动卸载会让上一个用例的实例
// 继续响应后续用例派发的事件，导致调用次数虚高（本文件曾实测 5 次而非 2 次）。
enableAutoUnmount(afterEach)

describe('AppHeader 未读红点', () => {
  beforeEach(() => {
    localStorage.clear()
    getUserInfoMock.mockReset()
    getUnreadCountMock.mockReset()
    getUserInfoMock.mockResolvedValue(okUser())
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('未登录时不请求未读数，也不显示红点', async () => {
    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(getUnreadCountMock).not.toHaveBeenCalled()
    expect(badge(wrapper).exists()).toBe(false)
  })

  it('登录且有未读时，顶栏显示未读数', async () => {
    localStorage.setItem('token', 'fake-token')
    getUnreadCountMock.mockResolvedValue({ code: 200, data: 3, message: 'ok' })

    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(getUnreadCountMock).toHaveBeenCalledTimes(1)
    expect(badge(wrapper).exists()).toBe(true)
    expect(badge(wrapper).text()).toBe('3')
  })

  it('未读数为 0 时不显示红点（0 不渲染成一个「0」徽标）', async () => {
    localStorage.setItem('token', 'fake-token')
    getUnreadCountMock.mockResolvedValue({ code: 200, data: 0, message: 'ok' })

    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(badge(wrapper).exists()).toBe(false)
  })

  it('收到「通知已变化」事件后重拉未读数（已读操作要让红点同步下降）', async () => {
    localStorage.setItem('token', 'fake-token')
    getUnreadCountMock.mockResolvedValue({ code: 200, data: 5, message: 'ok' })

    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()
    expect(badge(wrapper).text()).toBe('5')

    getUnreadCountMock.mockResolvedValue({ code: 200, data: 0, message: 'ok' })
    window.dispatchEvent(new Event('amz:notifications-changed'))
    await flushPromises()

    expect(getUnreadCountMock).toHaveBeenCalledTimes(2)
    expect(badge(wrapper).exists()).toBe(false)
  })

  it('未读数请求失败时静默降级：不显示红点，也不抛错打扰用户', async () => {
    localStorage.setItem('token', 'fake-token')
    getUnreadCountMock.mockRejectedValue(new Error('network down'))

    const wrapper = mount(AppHeader, { global: { stubs: { Icon: true } } })
    await flushPromises()

    expect(badge(wrapper).exists()).toBe(false)
  })
})
