import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InventoryMonitor from '../views/InventoryMonitor.vue'
import type { InventoryItem, InventoryHealth } from '@/api/inventory'

// mock @/api/inventory，避免触发真实请求
vi.mock('@/api/inventory', () => ({
  getInventoryList: vi.fn(),
  getInventoryHealth: vi.fn(),
  getReplenishSuggestion: vi.fn()
}))

import { getInventoryList, getInventoryHealth } from '@/api/inventory'

const mockedGetInventoryList = vi.mocked(getInventoryList)
const mockedGetInventoryHealth = vi.mocked(getInventoryHealth)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('InventoryMonitor 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetInventoryList.mockReset()
    mockedGetInventoryHealth.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与 4 个健康度卡片', () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    expect(wrapper.find('.page-header h1').text()).toBe('库存监控')
    expect(wrapper.findAll('.health-card').length).toBe(4)
    // 降级 mock 健康度数据：urgent=2 risk=2 healthy=2 overstock=1
    const labels = wrapper.findAll('.health-label').map(l => l.text())
    expect(labels).toEqual(['紧急补货', '风险库存', '健康库存', '滞销库存'])
  })

  it('降级 mock 健康度计数应被渲染', () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    const counts = wrapper.findAll('.health-count').map(c => c.text())
    expect(counts).toEqual(['2', '2', '2', '1'])
  })

  it('未选择店铺时应显示空店铺提示且不调用 inventory API', async () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(mockedGetInventoryList).not.toHaveBeenCalled()
    expect(mockedGetInventoryHealth).not.toHaveBeenCalled()
  })

  it('已选店铺且 API 返回 200 时应用接口数据覆盖列表与健康度', async () => {
    localStorage.setItem('current_shop_id', '1')
    const items: InventoryItem[] = [
      { sku: 'API-SKU-1', asin: 'ASIN001', shop: 'Shop A (US)', stock: 10, dailySales: 2, days: 5, level: 'urgent', levelText: '紧急', suggestQty: 100 },
      { sku: 'API-SKU-2', asin: 'ASIN002', shop: 'Shop A (US)', stock: 200, dailySales: 1, days: 200, level: 'overstock', levelText: '滞销', suggestQty: 0 }
    ]
    const health: InventoryHealth = { urgent: 1, risk: 0, healthy: 0, overstock: 1 }
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: items })
    mockedGetInventoryHealth.mockResolvedValue({ code: 200, message: 'ok', data: health })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    // 列表被接口数据覆盖（2 行）
    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('API-SKU-1')
    expect(wrapper.text()).toContain('API-SKU-2')
    // 原 mock SKU B08X4-001 不应再出现
    expect(wrapper.text()).not.toContain('B08X4-001')

    // 健康度计数被接口数据覆盖
    const counts = wrapper.findAll('.health-count').map(c => c.text())
    expect(counts).toEqual(['1', '0', '0', '1'])
  })

  it('API 抛异常时应降级到 mock 数据且不报错', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockRejectedValue(new Error('network error'))
    mockedGetInventoryHealth.mockRejectedValue(new Error('network error'))

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级 mock 列表 7 行
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(7)
    expect(wrapper.text()).toContain('B08X4-001')
    // 降级 mock 健康度计数
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['2', '2', '2', '1'])
    expect(wrapper.find('.loading-mask').exists()).toBe(false)
  })

  it('已选店铺但 API 返回空列表时应显示"暂无库存数据"', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetInventoryHealth.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { urgent: 0, risk: 0, healthy: 0, overstock: 0 }
    })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.empty-row').exists()).toBe(true)
    expect(wrapper.find('.empty-row').text()).toContain('暂无库存数据')
    // 健康度全部为 0
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['0', '0', '0', '0'])
  })

  it('已选店铺但 inventory API 返回非 200 时应保留降级列表', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 500, message: 'err', data: null as any })
    mockedGetInventoryHealth.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { urgent: 9, risk: 9, healthy: 9, overstock: 9 }
    })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    // 列表降级为 mock 7 行
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(7)
    // 健康度仍按接口 200 数据更新
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['9', '9', '9', '9'])
  })
})
