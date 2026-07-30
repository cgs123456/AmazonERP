import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProfitReport from '../views/ProfitReport.vue'
import type { ProfitRow, ProfitSummary } from '@/api/profit'

// mock @/api/profit，避免触发真实请求
vi.mock('@/api/profit', () => ({
  getProfitReport: vi.fn(),
  getProfitSummary: vi.fn()
}))

import { getProfitReport } from '@/api/profit'

const mockedGetProfitReport = vi.mocked(getProfitReport)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('ProfitReport 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetProfitReport.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与汇总卡片', () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    expect(wrapper.find('.page-header h1').text()).toBe('利润报表')
    // 4 个汇总卡片
    const cards = wrapper.findAll('.summary-card')
    expect(cards.length).toBe(4)
    // 降级 mock 汇总数据应被渲染
    expect(wrapper.text()).toContain('$12,456.80')
  })

  it('未选择店铺时应显示空店铺提示且不调用 getProfitReport', async () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(mockedGetProfitReport).not.toHaveBeenCalled()
  })

  it('已选店铺且 API 返回 200 时应用接口数据覆盖汇总与 SKU 行', async () => {
    localStorage.setItem('current_shop_id', '1')
    const summary: ProfitSummary = {
      totalRevenue: '$99,999.00',
      totalCost: '$11,111.00',
      grossProfit: '$88,888.00',
      grossMargin: '88.8%'
    }
    const rows: ProfitRow[] = [
      { name: 'API-SKU-1', revenue: '$1,000', cost: '$500', platformFee: '$100', adFee: '$50', shipping: '$10', profit: 340, margin: 34.0 }
    ]
    mockedGetProfitReport.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { summary, rows }
    })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 汇总区被接口数据覆盖
    expect(wrapper.text()).toContain('$99,999.00')
    expect(wrapper.text()).toContain('88.8%')
    // SKU 行被接口数据覆盖，表格第一行显示 API-SKU-1
    expect(wrapper.text()).toContain('API-SKU-1')
    // 原 mock SKU 数据 B08X4-001 不应再出现（已被 rows 覆盖）
    expect(wrapper.text()).not.toContain('B08X4-001')
  })

  it('API 抛异常时应降级到 mock 数据且不报错', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockRejectedValue(new Error('network error'))

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级 mock 汇总仍存在
    expect(wrapper.text()).toContain('$12,456.80')
    // 降级 mock SKU 行存在
    expect(wrapper.text()).toContain('B08X4-001')
    expect(wrapper.find('.loading-mask').exists()).toBe(false)
  })

  it('默认维度为 SKU，切换到"按店铺"应显示店铺维度数据', async () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 默认 SKU 维度：表格首列标题为 SKU，且包含 B08X4-001
    expect(wrapper.text()).toContain('B08X4-001')

    // 点击"按店铺"
    const shopTab = wrapper.findAll('.dim-tab').find(t => t.text().includes('按店铺'))!
    await shopTab.trigger('click')

    // 店铺维度数据含 Shop A (US)，不应再含 SKU 编号 B08X4-001
    expect(wrapper.text()).toContain('Shop A (US)')
    expect(wrapper.text()).not.toContain('B08X4-001')
  })

  it('切换到"按月度"应显示月度维度数据', async () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    const monthTab = wrapper.findAll('.dim-tab').find(t => t.text().includes('按月度'))!
    await monthTab.trigger('click')

    expect(wrapper.text()).toContain('2026-01')
    expect(wrapper.text()).toContain('2026-06')
  })

  it('已选店铺但 API 返回非 200 时应保留降级数据', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({ code: 500, message: 'err', data: null as any })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级 mock 数据仍在
    expect(wrapper.text()).toContain('$12,456.80')
    expect(wrapper.text()).toContain('B08X4-001')
  })
})
