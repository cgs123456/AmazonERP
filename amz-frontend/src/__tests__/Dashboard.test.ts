import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Dashboard from '../views/Dashboard.vue'

// mock dashboard 相关 API，避免触发真实请求
vi.mock('@/api/dashboard', () => ({
  getKpiData: vi.fn(),
  getSalesTrend: vi.fn(),
  getShopDistribution: vi.fn()
}))

import { getKpiData, getSalesTrend, getShopDistribution } from '@/api/dashboard'

const mockedGetKpiData = vi.mocked(getKpiData)
const mockedGetSalesTrend = vi.mocked(getSalesTrend)
const mockedGetShopDistribution = vi.mocked(getShopDistribution)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    AgentChat: { template: '<div />' }
  }
}

describe('Dashboard 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetKpiData.mockReset()
    mockedGetSalesTrend.mockReset()
    mockedGetShopDistribution.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('未登录时不发任何业务请求并提示登录（防 401 重载循环）', async () => {
    const wrapper = mount(Dashboard, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedGetKpiData).not.toHaveBeenCalled()
    expect(mockedGetSalesTrend).not.toHaveBeenCalled()
    expect(mockedGetShopDistribution).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先登录后查看经营数据')
  })

  it('登录事件后应补拉数据（免手动刷新）', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetKpiData.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetSalesTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetShopDistribution.mockResolvedValue({ code: 200, message: 'ok', data: [] })

    mount(Dashboard, { shallow: true, global: globalStubs })
    await flushPromises()
    // 未登录：无请求
    expect(mockedGetKpiData).not.toHaveBeenCalled()

    // 模拟 AppHeader 登录成功广播
    localStorage.setItem('token', 'fake-token')
    window.dispatchEvent(new Event('amz:auth-changed'))
    await flushPromises()

    expect(mockedGetKpiData).toHaveBeenCalledWith('1')
  })

  it('已登录时正常拉取三组数据', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('current_shop_id', '1')
    mockedGetKpiData.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetSalesTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetShopDistribution.mockResolvedValue({ code: 200, message: 'ok', data: [] })

    mount(Dashboard, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedGetKpiData).toHaveBeenCalledWith('1')
    expect(mockedGetSalesTrend).toHaveBeenCalled()
    expect(mockedGetShopDistribution).toHaveBeenCalled()
  })

  it('trend 为 0 时应中性展示而非红色下跌', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('current_shop_id', '1')
    mockedGetKpiData.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [{ label: '销售额', value: '$100', trend: 0, icon: 'mdi:currency-usd' }]
    })
    mockedGetSalesTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetShopDistribution.mockResolvedValue({ code: 200, message: 'ok', data: [] })

    const wrapper = mount(Dashboard, { shallow: true, global: globalStubs })
    await flushPromises()

    const trend = wrapper.find('.kpi-trend')
    expect(trend.classes()).toContain('flat')
    expect(trend.text()).toContain('暂无对比')
  })
})
