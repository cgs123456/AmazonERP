import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '../views/OrderList.vue'
import type { OrderItem } from '@/api/order'

// mock @/api/order，避免触发真实请求
vi.mock('@/api/order', () => ({
  getOrderList: vi.fn(),
  confirmShipment: vi.fn(),
  allocateOrderStock: vi.fn()
}))

import { getOrderList, confirmShipment, allocateOrderStock } from '@/api/order'

const mockedGetOrderList = vi.mocked(getOrderList)
const mockedConfirmShipment = vi.mocked(confirmShipment)
const mockedAllocateOrderStock = vi.mocked(allocateOrderStock)

// 公共 stubs：shallow mount 已会 stub 子组件，这里额外提供 Icon 占位以防万一
const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('OrderList 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetOrderList.mockReset()
    mockedConfirmShipment.mockReset()
    mockedAllocateOrderStock.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与副标题', () => {
    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    expect(wrapper.find('.hero-section .hero-title').text()).toBe('订单管理')
    expect(wrapper.find('.hero-section .hero-subtitle').text()).toContain('Amazon 订单列表')
  })

  it('未选择店铺时应显示空店铺提示且不调用 getOrderList', async () => {
    // 无 current_shop_id、无 shops
    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(mockedGetOrderList).not.toHaveBeenCalled()
  })

  it('已选店铺且 API 返回数组时应渲染订单行', async () => {
    localStorage.setItem('current_shop_id', '1')
    const orders: OrderItem[] = [
      { id: 1, orderNo: '114-AAAAAAA-AAAAAAA', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-001', qty: 2, amount: '$59.98', profit: 18.50, status: '已发货', statusClass: 'shipped', date: '2026-07-06 14:30' },
      { id: 2, orderNo: '114-BBBBBBB-BBBBBBB', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-002', qty: 1, amount: '$29.99', profit: 12.30, status: '已完成', statusClass: 'completed', date: '2026-07-06 12:15' }
    ]
    mockedGetOrderList.mockResolvedValue({ code: 200, message: 'ok', data: orders })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    const rows = wrapper.findAll('.data-table tbody tr')
    // 2 条数据行，不应有"暂无订单数据"占位行
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('114-AAAAAAA-AAAAAAA')
    expect(wrapper.text()).toContain('114-BBBBBBB-BBBBBBB')
    // 分页信息应反映总数
    expect(wrapper.find('.page-info').text()).toContain('2 条')
  })

  it('已选店铺且 API 返回分页对象时应渲染 list 数据', async () => {
    localStorage.setItem('current_shop_id', '1')
    const orders: OrderItem[] = [
      { id: 10, orderNo: '114-CCCCCCC-CCCCCCC', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-010', qty: 1, amount: '$10.00', profit: 5.0, status: '待发货', statusClass: 'pending', date: '2026-07-07 09:00' }
    ]
    mockedGetOrderList.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { list: orders, total: 50, page: 1, size: 20 }
    })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).toContain('114-CCCCCCC-CCCCCCC')
    expect(wrapper.find('.page-info').text()).toContain('50 条')
    expect(wrapper.find('.page-info').text()).toContain('共 3 页')
  })

  it('订单号筛选应透传服务端查询（GET /order/list?orderNo=）', async () => {
    localStorage.setItem('current_shop_id', '1')
    const orders: OrderItem[] = [
      { id: 1, orderNo: '114-AAA-111', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-001', qty: 1, amount: '$1.00', profit: 1, status: '已发货', statusClass: 'shipped', date: '2026-07-06 14:30' },
      { id: 2, orderNo: '114-BBB-222', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-002', qty: 1, amount: '$2.00', profit: 2, status: '已完成', statusClass: 'completed', date: '2026-07-06 12:15' },
      { id: 3, orderNo: '114-AAA-333', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-003', qty: 1, amount: '$3.00', profit: 3, status: '待发货', statusClass: 'pending', date: '2026-07-06 10:00' }
    ]
    mockedGetOrderList.mockResolvedValue({ code: 200, message: 'ok', data: orders })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    // 初始 3 行
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(3)

    // 输入订单号并点击查询，应把 orderNo 透传给服务端（跨页可查）
    await wrapper.find('.filter-input').setValue('AAA')
    await wrapper.find('.filter-btn').trigger('click')
    await flushPromises()

    const lastParams = mockedGetOrderList.mock.calls[mockedGetOrderList.mock.calls.length - 1][0] as { orderNo?: string }
    expect(lastParams.orderNo).toBe('AAA')
  })

  it('API 返回非 200 时进错误横幅并清空列表（不降级 mock 假成功）', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockResolvedValue({ code: 500, message: 'err', data: null as any })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.error-zone').exists()).toBe(true)
    expect(wrapper.text()).toContain('err')
    expect(wrapper.text()).not.toContain('114-1234567-1234567')
  })

  it('API 抛异常时进错误横幅并清空列表', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockRejectedValue(new Error('network error'))

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.error-zone').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('114-1234567-1234567')
  })

  it('点击查询按钮应重置页码并再次调用 getOrderList', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockResolvedValue({ code: 200, message: 'ok', data: [] })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()
    // onMounted 调用一次
    expect(mockedGetOrderList).toHaveBeenCalledTimes(1)

    await wrapper.find('.filter-btn').trigger('click')
    await flushPromises()
    // 点击查询再调用一次
    expect(mockedGetOrderList).toHaveBeenCalledTimes(2)
  })

  it('已选店铺但 API 返回空数据时应显示"暂无订单数据"', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockResolvedValue({ code: 200, message: 'ok', data: [] })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.empty-row').exists()).toBe(true)
    expect(wrapper.find('.empty-row').text()).toContain('暂无订单数据')
  })
})

describe('OrderList 写入口角色门控', () => {
  // MFN + 未配货 + 未回传的订单：两个写按钮都可点
  const allocatable = (id: number): OrderItem => ({
    id, orderNo: `114-${id}`, shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-001', qty: 2,
    amount: '$59.98', profit: 18.5, status: '待发货', statusClass: 'pending', date: '2026-07-06 14:30',
    fulfillmentChannel: 'MFN'
  })

  const mountPage = async (role?: string) => {
    if (role !== undefined) localStorage.setItem('user_role', role)
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockResolvedValue({ code: 200, message: 'ok', data: [allocatable(1)] })
    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    localStorage.clear()
    mockedGetOrderList.mockReset()
    mockedConfirmShipment.mockReset()
    mockedAllocateOrderStock.mockReset()
  })

  it('OPERATOR 应看到可点的配货与确认发货按钮', async () => {
    const wrapper = await mountPage('OPERATOR')
    const btns = wrapper.findAll('.row-actions .row-btn')
    // 发货单 / 配货出库 / 确认发货
    expect(btns.length).toBe(3)
    expect(btns[1].attributes('disabled')).toBeUndefined()
    expect(btns[2].attributes('disabled')).toBeUndefined()
  })

  it('VIEWER 时两个写按钮应禁用并带权限说明（而不是可点后拿到业务拒绝）', async () => {
    const wrapper = await mountPage('VIEWER')
    const btns = wrapper.findAll('.row-actions .row-btn')
    expect(btns[1].attributes('disabled')).toBeDefined()
    expect(btns[2].attributes('disabled')).toBeDefined()
    expect(btns[1].attributes('title')).toContain('OPERATOR/ADMIN')
    expect(btns[2].attributes('title')).toContain('OPERATOR/ADMIN')
  })

  it('角色未知（getInfo 未回来）时不应隐藏写入口', async () => {
    const wrapper = await mountPage()
    const btns = wrapper.findAll('.row-actions .row-btn')
    expect(btns[1].attributes('disabled')).toBeUndefined()
    expect(btns[2].attributes('disabled')).toBeUndefined()
  })

  it('VIEWER 点击配货出库应被拦住且不发请求，并给出理由', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('9')
    const wrapper = await mountPage('VIEWER')
    // 直接调组件方法：按钮已禁用，但门控的边界在处理器里（弹窗可能在角色变更前打开）
    await (wrapper.vm as any).allocate(allocatable(1))
    await flushPromises()
    expect(mockedAllocateOrderStock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('需要 OPERATOR 或 ADMIN')
  })

  it('VIEWER 打开确认发货弹窗应被拦住，弹窗不出现', async () => {
    const wrapper = await mountPage('VIEWER')
    ;(wrapper.vm as any).openConfirm(allocatable(1))
    await flushPromises()
    expect(wrapper.find('.dialog-mask').exists()).toBe(false)
  })

  it('VIEWER 直接提交确认发货应被拦住且不发请求', async () => {
    const wrapper = await mountPage('OPERATOR')
    ;(wrapper.vm as any).openConfirm(allocatable(1))
    await flushPromises()
    // 弹窗已开，此时角色被改成 VIEWER：模拟 getInfo 竞态（角色比弹窗晚到）
    localStorage.setItem('user_role', 'VIEWER')
    window.dispatchEvent(new Event('amz:role-changed'))
    await flushPromises()
    ;(wrapper.vm as any).confirmForm = { carrierCode: 'USPS', carrierName: '', trackingNumber: 'TRK-1', shipDate: '' }
    await (wrapper.vm as any).submitConfirm()
    await flushPromises()
    expect(mockedConfirmShipment).not.toHaveBeenCalled()
  })

  it('OPERATOR 配货出库：后端返回 200 时才认为成功并刷新列表', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('9')
    mockedAllocateOrderStock.mockResolvedValue({ code: 200, message: 'ok', data: null as any })
    const wrapper = await mountPage('OPERATOR')
    await (wrapper.vm as any).allocate(allocatable(1))
    await flushPromises()
    expect(mockedAllocateOrderStock).toHaveBeenCalledWith(1, '9')
    // 成功后重拉列表
    expect(mockedGetOrderList).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('OPERATOR 配货出库：后端业务拒绝（HTTP 200 + code=400）必须进错误横幅，不能当成功', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('9')
    mockedAllocateOrderStock.mockResolvedValue({ code: 400, message: '库存不足：B08X4-001', data: null as any })
    const wrapper = await mountPage('OPERATOR')
    await (wrapper.vm as any).allocate(allocatable(1))
    await flushPromises()
    expect(mockedAllocateOrderStock).toHaveBeenCalled()
    expect(wrapper.find('.error-zone').exists()).toBe(true)
    expect(wrapper.text()).toContain('库存不足：B08X4-001')
  })
})
