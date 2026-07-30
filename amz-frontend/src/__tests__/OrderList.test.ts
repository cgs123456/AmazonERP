import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderList from '../views/OrderList.vue'
import type { OrderItem } from '@/api/order'

// mock @/api/order，避免触发真实请求
vi.mock('@/api/order', () => ({
  getOrderList: vi.fn()
}))

import { getOrderList } from '@/api/order'

const mockedGetOrderList = vi.mocked(getOrderList)

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
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与副标题', () => {
    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    expect(wrapper.find('.page-header h1').text()).toBe('订单管理')
    expect(wrapper.find('.subtitle').text()).toContain('Amazon 订单列表')
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

  it('订单号筛选应在前端过滤展示的订单', async () => {
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

    // 输入订单号筛选，应只显示包含 "AAA" 的两行
    await wrapper.find('.filter-input').setValue('AAA')
    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('114-AAA-111')
    expect(wrapper.text()).toContain('114-AAA-333')
    expect(wrapper.text()).not.toContain('114-BBB-222')
  })

  it('API 返回非 200 时应降级到 mock 数据并渲染', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockResolvedValue({ code: 500, message: 'err', data: null as any })

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级数据含 6 条 mock 订单
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(6)
    expect(wrapper.text()).toContain('114-1234567-1234567')
  })

  it('API 抛异常时应降级到 mock 数据', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetOrderList.mockRejectedValue(new Error('network error'))

    const wrapper = mount(OrderList, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.findAll('.data-table tbody tr').length).toBe(6)
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
