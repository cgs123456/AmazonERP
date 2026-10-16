import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 客服页写入口的角色门控（P0 加锁后的前端侧）。
 * <p>
 * 后端 CustomerController / CustomerEmailController 的写端点此前几乎都没有 @RequireRole，
 * VIEWER 与 OPERATOR 等权。现在后端逐条补上了门禁，前端同步隐藏入口——
 * 否则用户会点到一个必然被后端拒绝的按钮。
 * <p>
 * <b>为什么这组测试必须存在</b>：门控是「前端隐藏」和「后端拒绝」两处独立实现，
 * 只有测试能发现它们漂开。后端那边由 CustomerWriteEndpointGuardContractTest 反射钉住，
 * 这里钉前端。
 */
vi.mock('@/api/customer', async () => {
  const actual = await vi.importActual<any>('@/api/customer')
  return {
    ...actual,
    listTickets: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    listTemplates: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    listEmailTasks: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    listNegativeReviews: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    listSolicitations: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    listRmas: vi.fn().mockResolvedValue({ code: 200, data: [] }),
    replyTicket: vi.fn(),
    closeTicket: vi.fn(),
    solicitReviews: vi.fn(),
    processPendingEmails: vi.fn(),
    createTemplate: vi.fn(),
    toggleTemplate: vi.fn(),
    createRma: vi.fn(),
    updateRmaStatus: vi.fn()
  }
})

import CustomerService from '../views/CustomerService.vue'

const mountPage = async () => {
  const wrapper = mount(CustomerService, { global: { stubs: { AppHeader: true, AppSidebar: true, Icon: true } } })
  await flushPromises()
  return wrapper
}

/** 找到文本匹配的按钮（页面上同名按钮只可能有一个在某个 tab 内） */
const buttonByText = (wrapper: any, text: string) =>
  wrapper.findAll('button').find((b: any) => b.text().trim() === text)

describe('客服页写入口角色门控', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()
  })

  it('VIEWER 时新建工单入口隐藏，并给出角色说明', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新建工单')).toBeUndefined()
    expect(wrapper.text()).toContain('需 OPERATOR/ADMIN')
  })

  it('OPERATOR 时新建工单入口可见', async () => {
    localStorage.setItem('user_role', 'OPERATOR')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新建工单')).toBeDefined()
  })

  it('ADMIN 时新建工单入口可见', async () => {
    localStorage.setItem('user_role', 'ADMIN')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新建工单')).toBeDefined()
  })

  // canUseRole 的既有语义：角色未知时不隐藏（硬刷新时 getInfo 可能还没回来，
  // 因为「还没取到角色」就把功能藏掉等于让前端状态否决后端能力）。
  it('角色未知时不隐藏写入口', async () => {
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新建工单')).toBeDefined()
  })

  // 角色是**异步**到达的：AppHeader 的 getInfo 与各页面的挂载并发，
  // 只在 setup 里读一次会稳定读到「角色未知」→ 入口该隐藏时也隐藏不了。
  // 所以门控必须监听 ROLE_CHANGED_EVENT 并重算。
  it('角色从未知变为 VIEWER 后入口立即隐藏（响应 role-changed 事件）', async () => {
    const wrapper = await mountPage()
    expect(buttonByText(wrapper, '新建工单')).toBeDefined()

    localStorage.setItem('user_role', 'VIEWER')
    window.dispatchEvent(new Event('amz:role-changed'))
    await flushPromises()

    expect(buttonByText(wrapper, '新建工单')).toBeUndefined()
  })

  it('角色从 VIEWER 变为 OPERATOR 后入口立即出现', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const wrapper = await mountPage()
    expect(buttonByText(wrapper, '新建工单')).toBeUndefined()

    localStorage.setItem('user_role', 'OPERATOR')
    window.dispatchEvent(new Event('amz:role-changed'))
    await flushPromises()

    expect(buttonByText(wrapper, '新建工单')).toBeDefined()
  })
})
