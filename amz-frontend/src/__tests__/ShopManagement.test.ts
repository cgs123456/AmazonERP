import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * 店铺管理页（/shops，ADMIN）：店铺主数据 CRUD + 授权状态 + 用户-店铺授权。
 * <p>
 * 后端 ShopController 六个端点与 ShopServiceImplTest（10 例）早已就绪，前端 6 个 api
 * 函数却是**零调用孤儿**——多店铺 ERP 的「店铺管理」用户一直碰不到。本测试钉住新页面：
 * 列表渲染、创建/编辑前置校验、授权状态三态、以及授权弹窗的读侧（先看到当前绑定再操作，
 * 不做盲写）。
 */
const {
  getMyShopsMock, createShopMock, updateShopMock, updateShopStatusMock,
  listShopUsersMock, assignShopUserMock, unassignShopUserMock
} = vi.hoisted(() => ({
  getMyShopsMock: vi.fn(),
  createShopMock: vi.fn(),
  updateShopMock: vi.fn(),
  updateShopStatusMock: vi.fn(),
  listShopUsersMock: vi.fn(),
  assignShopUserMock: vi.fn(),
  unassignShopUserMock: vi.fn()
}))

vi.mock('@/api/auth', () => ({
  getMyShops: getMyShopsMock,
  createShop: createShopMock,
  updateShop: updateShopMock,
  updateShopStatus: updateShopStatusMock,
  listShopUsers: listShopUsersMock,
  assignShopUser: assignShopUserMock,
  unassignShopUser: unassignShopUserMock
}))

import ShopManagement from '../views/ShopManagement.vue'

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const ok = (data: unknown) => ({ code: 200, data, message: 'ok' })
const fail = (message: string) => ({ code: 400, data: null, message })

const SHOPS = [
  { id: 9001, shopName: '美国站主店', marketplaceId: 'ATVPDKIKX0DER', region: 'NA', sellerId: 'A1SELLER', status: 1, statusText: '已授权', credentialConfigured: true, role: 'ADMIN' },
  { id: 9002, shopName: '德国站', marketplaceId: 'A1PA6795UKMFR9', region: 'EU', sellerId: null, status: 0, statusText: '未授权', credentialConfigured: false, role: 'ADMIN' }
]

const BOUND = [
  { userId: 7, nickname: '运营甲', phone: '13800000077', role: 'OPERATOR' },
  { userId: 8, nickname: '访客乙', phone: '{{PHONE_3hvl2nzp}}', role: 'VIEWER' }
]

const mountPage = async () => {
  const wrapper = mount(ShopManagement, { global: stubs })
  await flushPromises()
  return wrapper
}

const rows = (wrapper: any) => wrapper.findAll('tbody tr')
const btnByText = (wrapper: any, text: string) => wrapper.findAll('button').filter((b: any) => b.text() === text)

describe('店铺管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    getMyShopsMock.mockResolvedValue(ok(SHOPS))
    createShopMock.mockResolvedValue(ok(SHOPS[0]))
    updateShopMock.mockResolvedValue(ok(SHOPS[0]))
    updateShopStatusMock.mockResolvedValue(ok(SHOPS[1]))
    listShopUsersMock.mockResolvedValue(ok(BOUND))
    assignShopUserMock.mockResolvedValue(ok(true))
    unassignShopUserMock.mockResolvedValue(ok(true))
  })

  it('渲染店铺列表：名称/marketplace/区域/授权状态/凭证配置', async () => {
    const wrapper = await mountPage()
    expect(rows(wrapper)).toHaveLength(2)
    const text = wrapper.find('table').text()
    expect(text).toContain('美国站主店')
    expect(text).toContain('ATVPDKIKX0DER')
    expect(text).toContain('已授权')
    expect(text).toContain('未授权')
    // 凭证未配置必须可见——运营要知道「这家店还没去连接器中心配凭证」
    expect(text).toContain('未配置')
  })

  it('说明区把边界讲清楚：凭证不在本页维护 + JWT 24h 时效', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('连接器中心')
    expect(note).toContain('24 小时')
    expect(note).toContain('重新登录')
  })

  it('列表加载失败时点名「店铺列表」，不静默变空表', async () => {
    getMyShopsMock.mockResolvedValue(fail('无权限执行该操作（需要角色：ADMIN）'))
    const wrapper = await mountPage()
    const zone = wrapper.find('.error-zone')
    expect(zone.text()).toContain('店铺列表')
    expect(zone.text()).toContain('ADMIN')
    expect(wrapper.text()).toContain('暂无店铺')
  })

  it('新建店铺：缺名称或 marketplace 时保存禁用（与后端同口径前置校验）', async () => {
    const wrapper = await mountPage()
    await btnByText(wrapper, '+ 新建店铺')[0].trigger('click')
    await flushPromises()

    const modal = wrapper.find('[data-panel="shop-save"]')
    expect(modal.exists()).toBe(true)
    const save = modal.findAll('button').find((b: any) => b.text() === '保存')!
    expect(save.attributes('disabled')).toBeDefined()

    const inputs = modal.findAll('input')
    await inputs[0].setValue('日本站')
    expect(save.attributes('disabled')).toBeDefined() // 还缺 marketplaceId
    await inputs[1].setValue('A1VC38T7YXB528')
    expect(save.attributes('disabled')).toBeUndefined()
  })

  it('新建店铺成功后重拉列表', async () => {
    const wrapper = await mountPage()
    await btnByText(wrapper, '+ 新建店铺')[0].trigger('click')
    await flushPromises()
    const modal = wrapper.find('[data-panel="shop-save"]')
    const inputs = modal.findAll('input')
    await inputs[0].setValue('日本站')
    await inputs[1].setValue('A1VC38T7YXB528')
    await modal.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(createShopMock).toHaveBeenCalledWith(expect.objectContaining({
      shopName: '日本站', marketplaceId: 'A1VC38T7YXB528'
    }))
    expect(getMyShopsMock).toHaveBeenCalledTimes(2)
  })

  it('新建被后端拒绝：原因留在弹窗里，不关弹窗不重拉', async () => {
    createShopMock.mockResolvedValue(fail('店铺名称不能为空'))
    const wrapper = await mountPage()
    await btnByText(wrapper, '+ 新建店铺')[0].trigger('click')
    await flushPromises()
    const modal = wrapper.find('[data-panel="shop-save"]')
    await modal.findAll('input')[0].setValue('X')
    await modal.findAll('input')[1].setValue('ATVPDKIKX0DER')
    await modal.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-panel="shop-save"]').exists()).toBe(true)
    expect(wrapper.find('[data-panel="shop-save"] .error-zone').text()).toContain('店铺名称不能为空')
    expect(getMyShopsMock).toHaveBeenCalledTimes(1)
  })

  it('改授权状态：下拉三态，选中后把 status 打给后端并就地更新（不额外重拉）', async () => {
    const wrapper = await mountPage()
    const select = rows(wrapper)[1].find('select.status-select')
    const el = select.element as HTMLSelectElement
    el.value = '1'
    el.dispatchEvent(new Event('change'))
    await flushPromises()

    expect(updateShopStatusMock).toHaveBeenCalledWith(9002, 1)
    // 成功后就地更新行状态，不必重拉（与用户管理改角色同口径）
    expect((rows(wrapper)[1].find('select.status-select').element as HTMLSelectElement).value).toBe('1')
    expect(getMyShopsMock).toHaveBeenCalledTimes(1)
  })

  it('改授权状态被拒绝：原因留在页面上并重拉回服务端真值', async () => {
    updateShopStatusMock.mockResolvedValue(fail('状态只支持 1=已授权 / 0=未授权 / -1=授权过期'))
    const wrapper = await mountPage()
    const el = rows(wrapper)[1].find('select.status-select').element as HTMLSelectElement
    el.value = '1'
    el.dispatchEvent(new Event('change'))
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('状态只支持')
    expect(getMyShopsMock).toHaveBeenCalledTimes(2)
  })

  it('授权弹窗先展示当前绑定用户（读侧），不盲写', async () => {
    const wrapper = await mountPage()
    await rows(wrapper)[0].findAll('button').find((b: any) => b.text() === '授权用户')!.trigger('click')
    await flushPromises()

    expect(listShopUsersMock).toHaveBeenCalledWith(9001)
    const modal = wrapper.find('[data-panel="shop-users"]')
    const text = modal.text()
    expect(text).toContain('运营甲')
    expect(text).toContain('OPERATOR')
    expect(text).toContain('访客乙')
    expect(text).toContain('VIEWER')
  })

  it('授权弹窗解绑：二次确认后调用后端并重拉绑定列表', async () => {
    const wrapper = await mountPage()
    await rows(wrapper)[0].findAll('button').find((b: any) => b.text() === '授权用户')!.trigger('click')
    await flushPromises()

    const modal = wrapper.find('[data-panel="shop-users"]')
    const boundRows = modal.findAll('tbody tr')
    await boundRows[0].findAll('button').find((b: any) => b.text() === '解绑')!.trigger('click')
    await flushPromises()
    await btnByText(wrapper, '确认执行')[0].trigger('click')
    await flushPromises()

    expect(unassignShopUserMock).toHaveBeenCalledWith(9001, 7)
    expect(listShopUsersMock).toHaveBeenCalledTimes(2)
  })

  it('授权弹窗分配：填 userId 与角色后调用后端并重拉', async () => {
    const wrapper = await mountPage()
    await rows(wrapper)[0].findAll('button').find((b: any) => b.text() === '授权用户')!.trigger('click')
    await flushPromises()

    const modal = wrapper.find('[data-panel="shop-users"]')
    const assignInput = modal.find('[data-testid="assign-user-id"]')
    await assignInput.setValue('9')
    await modal.findAll('button').find((b: any) => b.text() === '分配')!.trigger('click')
    await flushPromises()

    expect(assignShopUserMock).toHaveBeenCalledWith(9001, 9, 'VIEWER')
    expect(listShopUsersMock).toHaveBeenCalledTimes(2)
  })

  it('分配被拒绝时原因留在弹窗里', async () => {
    assignShopUserMock.mockResolvedValue(fail('用户不存在：id=9'))
    const wrapper = await mountPage()
    await rows(wrapper)[0].findAll('button').find((b: any) => b.text() === '授权用户')!.trigger('click')
    await flushPromises()
    const modal = wrapper.find('[data-panel="shop-users"]')
    await modal.find('[data-testid="assign-user-id"]').setValue('9')
    await modal.findAll('button').find((b: any) => b.text() === '分配')!.trigger('click')
    await flushPromises()

    expect(modal.find('.error-zone').text()).toContain('用户不存在')
  })
})
