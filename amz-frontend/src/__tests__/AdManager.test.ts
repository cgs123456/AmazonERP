import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdManager from '../views/AdManager.vue'

// mock 广告相关 API，避免触发真实请求
vi.mock('@/api/ad', () => ({
  getAdReports: vi.fn(),
  getAdTrend: vi.fn(),
  getKeywordOptimization: vi.fn()
}))

vi.mock('@/api/ad-ext', () => ({
  createCampaign: vi.fn(),
  updateCampaign: vi.fn(),
  listCampaigns: vi.fn(),
  batchCreateCampaigns: vi.fn(),
  batchUpdateStatus: vi.fn(),
  getShopSummary: vi.fn(),
  getSummaryByType: vi.fn(),
  createCreative: vi.fn(),
  updateCreative: vi.fn(),
  listCreatives: vi.fn(),
  reviewCreative: vi.fn(),
  createTargeting: vi.fn(),
  updateTargeting: vi.fn(),
  listTargeting: vi.fn(),
  deleteTargeting: vi.fn()
}))

import { getAdReports, getAdTrend } from '@/api/ad'
import { listCampaigns } from '@/api/ad-ext'

const mockedGetAdReports = vi.mocked(getAdReports)
const mockedGetAdTrend = vi.mocked(getAdTrend)
const mockedListCampaigns = vi.mocked(listCampaigns)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('AdManager 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedGetAdReports.mockReset()
    mockedGetAdTrend.mockReset()
    mockedListCampaigns.mockReset()
    mockedGetAdReports.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetAdTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListCampaigns.mockResolvedValue({ code: 200, message: 'ok', data: [] })
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('趋势接口有数据时应替换降级 mock', async () => {
    mockedGetAdTrend.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { day: '6/20', value: 22 },
        { day: '6/21', value: 26 }
      ]
    })

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedGetAdTrend).toHaveBeenCalledWith('1', 14, 'SP')
    expect(wrapper.text()).toContain('6/20')
    expect(wrapper.text()).toContain('6/21')
    // 降级 mock 日期不应再出现
    expect(wrapper.text()).not.toContain('7/1')
    // 真实数据下不应展示“示例数据”标识
    expect(wrapper.find('.mock-badge').exists()).toBe(false)
  })

  it('趋势接口返回空数组时应保留降级 mock 并展示示例标识', async () => {
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).toContain('7/1')
    expect(wrapper.find('.mock-badge').exists()).toBe(true)
    expect(wrapper.find('.mock-badge').text()).toBe('示例数据')
  })

  it('切换广告类型 Tab 应按类型重拉趋势', async () => {
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(mockedGetAdTrend).toHaveBeenCalledWith('1', 14, 'SP')

    const sbTab = wrapper.findAll('.ad-tab-item').find(t => t.text().includes('SB'))!
    await sbTab.trigger('click')
    await flushPromises()

    expect(mockedGetAdTrend).toHaveBeenLastCalledWith('1', 14, 'SB')
  })

  it('报表行应聚合成总览（花费/销售额求和）', async () => {
    mockedGetAdReports.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { campaignId: 'camp-001', cost: 480, sales: 3200 },
        { campaignId: 'camp-002', cost: 180, sales: 300 }
      ]
    })

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    // 660 / 3500 → ACoS 18.9%，ROAS 5.30
    expect(wrapper.text()).toContain('$660.00')
    expect(wrapper.text()).toContain('$3,500.00')
  })

  it('活动列表应取自 /ad/campaigns 并映射状态', async () => {
    mockedListCampaigns.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { shopId: 1, campaignId: 'camp-009', campaignName: 'API 活动', adType: 'SP', status: 'ENABLED', budget: 70, spend: 35, sales: 140, acos: 25 }
      ]
    })

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedListCampaigns).toHaveBeenCalledWith('1', 'SP')
    expect(wrapper.text()).toContain('API 活动')
    expect(wrapper.text()).not.toContain('关键词-蓝牙耳机-US')
  })
})
