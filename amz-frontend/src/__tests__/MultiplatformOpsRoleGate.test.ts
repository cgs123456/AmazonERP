import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 多平台页写入口的角色门控（P0 第 4 批，决策②定案后同步）。
 * <p>
 * 后端 MultiplatformController 的 17 个写端点此前一个 @RequireRole 都没有，
 * VIEWER 可代表店铺对第三方平台做写操作（最危险：标记任意平台订单已发货、
 * 轮换 ISV 应用密钥把对方系统踢下线）。现在人操作端点全部加了门禁，
 * 前端同步隐藏入口，否则用户点一个必然被拒的按钮。
 * <p>
 * **本测试只钉前端可见性**。后端那一半由两个测试分别守：
 * MultiplatformWriteEndpointGuardContractTest（反射钉注解）+
 * MultiplatformAuthorizationWebMvcIntegrationTest（真实链钉判得对不对、
 * 且钉 webhook/oauth 两个豁免端点没被误伤）。三层各管一段。
 */
// vi.mock 的工厂被提升到模块顶层，直接引用外层变量会 `Cannot access before initialization`
// （照 CustomerServiceRoleGate.test.ts 的既有写法用 vi.hoisted）。
const { mockedApi } = vi.hoisted(() => ({
  mockedApi: {
    listAccounts: vi.fn(),
    listProducts: vi.fn(),
    listMessages: vi.fn(),
    listInventory: vi.fn(),
    listWebhooks: vi.fn(),
    listApps: vi.fn(),
    ACCOUNT_STATUSES: [], MESSAGE_STATUSES: [], PLATFORMS: [], WEBHOOK_STATUSES: []
  }
}))

vi.mock('@/api/multiplatform', async () => {
  const actual = await vi.importActual<any>('@/api/multiplatform')
  return { ...actual, ...mockedApi }
})

import MultiplatformOps from '../views/MultiplatformOps.vue'

const mountPage = async () => {
  const wrapper = mount(MultiplatformOps, { global: { stubs: { AppHeader: true, AppSidebar: true, Icon: true } } })
  await flushPromises()
  return wrapper
}

const buttonByText = (wrapper: any, text: string) =>
  wrapper.findAll('button').find((b: any) => b.text().trim() === text)

describe('多平台页写入口角色门控', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()
  })

  it('VIEWER 时新增平台账号/注册应用入口隐藏，并给出角色说明', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新增平台账号')).toBeUndefined()
    expect(wrapper.text()).toContain('需 OPERATOR/ADMIN')
  })

  it('OPERATOR 时写入口可见', async () => {
    localStorage.setItem('user_role', 'OPERATOR')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新增平台账号')).toBeDefined()
  })

  it('ADMIN 时写入口可见', async () => {
    localStorage.setItem('user_role', 'ADMIN')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新增平台账号')).toBeDefined()
  })

  it('角色未知时不隐藏写入口（前端状态不否决后端能力）', async () => {
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '新增平台账号')).toBeDefined()
  })

  // 角色异步到达：AppHeader 的 getInfo 与各页面挂载并发，只在 setup 里读一次
  // 会稳定读到「未知」→ 入口该隐藏时也隐藏不了。
  it('角色从未知变为 VIEWER 后入口立即隐藏（响应 role-changed 事件）', async () => {
    const wrapper = await mountPage()
    expect(buttonByText(wrapper, '新增平台账号')).toBeDefined()

    localStorage.setItem('user_role', 'VIEWER')
    window.dispatchEvent(new Event('amz:role-changed'))
    await flushPromises()

    expect(buttonByText(wrapper, '新增平台账号')).toBeUndefined()
  })

  it('角色从 VIEWER 变为 OPERATOR 后入口立即出现', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const wrapper = await mountPage()
    expect(buttonByText(wrapper, '新增平台账号')).toBeUndefined()

    localStorage.setItem('user_role', 'OPERATOR')
    window.dispatchEvent(new Event('amz:role-changed'))
    await flushPromises()

    expect(buttonByText(wrapper, '新增平台账号')).toBeDefined()
  })

  // 只读入口不受影响：列表刷新/分页在 VIEWER 下必须仍可点（§S20.7 的教训反向钉）
  it('VIEWER 时只读的「刷新」入口仍可点（门控只拦写）', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const wrapper = await mountPage()

    expect(buttonByText(wrapper, '刷新')).toBeDefined()
  })
})
