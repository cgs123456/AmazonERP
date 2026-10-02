import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdManager from '../views/AdManager.vue'

// mock 广告相关 API，避免触发真实请求
vi.mock('@/api/ad', () => ({
  getAdReports: vi.fn(),
  getAdTrend: vi.fn(),
  syncAdReports: vi.fn(),
  syncAllAdReports: vi.fn(),
  // 页面用它做窗口上限的显示与夹取，mock 掉会退化成 undefined
  AD_SYNC_MAX_DAYS: 30
}))

vi.mock('@/api/ad-ext', () => ({
  createCampaign: vi.fn(),
  updateCampaign: vi.fn(),
  listCampaigns: vi.fn(),
  batchCreateCampaigns: vi.fn(),
  batchUpdateStatus: vi.fn(),
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

import { getAdReports, getAdTrend, syncAdReports, syncAllAdReports } from '@/api/ad'
import { listCampaigns } from '@/api/ad-ext'

const mockedGetAdReports = vi.mocked(getAdReports)
const mockedGetAdTrend = vi.mocked(getAdTrend)
const mockedListCampaigns = vi.mocked(listCampaigns)
const mockedSyncShop = vi.mocked(syncAdReports)
const mockedSyncAll = vi.mocked(syncAllAdReports)

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
    vi.stubEnv('VITE_AD_DEMO_MODE', 'false')
    mockedGetAdReports.mockReset()
    mockedGetAdTrend.mockReset()
    mockedListCampaigns.mockReset()
    mockedSyncShop.mockReset()
    mockedSyncAll.mockReset()
    mockedGetAdReports.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetAdTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListCampaigns.mockResolvedValue({ code: 200, message: 'ok', data: [] })
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllEnvs()
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

  it('生产模式接口返回空数组时不得把 mock 当作真实数据展示', async () => {
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).not.toContain('7/1')
    expect(wrapper.text()).not.toContain('关键词-蓝牙耳机-US')
    expect(wrapper.text()).toContain('暂无趋势数据')
    expect(wrapper.find('.mock-badge').exists()).toBe(false)
  })

  it('显式 demo 模式才允许空数据降级为示例数据', async () => {
    vi.stubEnv('VITE_AD_DEMO_MODE', 'true')

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).toContain('7/1')
    expect(wrapper.text()).toContain('关键词-蓝牙耳机-US')
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

  // ===== 日报回补（POST /ad/reports/sync 的两个重载）=====
  const SYNC_OK = {
    shopId: 1, days: 7, attempted: 1, succeeded: 1, failed: 0, skipped: 0, upserted: 12, metadataWarnings: 0
  }

  const openOps = async (wrapper: any) => {
    const btn = wrapper.findAll('button').find((b: any) => b.text() === '展开')
    expect(btn, '日报同步卡片没有展开入口').toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
  }

  const clickOps = async (wrapper: any, label: string) => {
    const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
    expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
    await matches[0].trigger('click')
    await flushPromises()
  }

  it('回补入口默认收起，展开后才说明窗口上限', async () => {
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('[data-panel="sync"]').text()).not.toContain('回补窗口')
    await openOps(wrapper)
    const card = wrapper.find('[data-panel="sync"]')
    expect(card.text()).toContain('amz_ad_daily_report')
    expect(card.text()).toContain('最多 30 天')
    expect(mockedSyncShop).not.toHaveBeenCalled()
  })

  it('同步当前店铺带上 shopId 与窗口，并把后端计数原样报出来', async () => {
    mockedSyncShop.mockResolvedValue({ code: 200, message: 'ok', data: SYNC_OK })
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    const before = mockedGetAdReports.mock.calls.length

    await openOps(wrapper)
    await clickOps(wrapper, '同步当前店铺')

    expect(mockedSyncShop).toHaveBeenCalledWith('1', 7)
    expect(mockedSyncAll).not.toHaveBeenCalled()
    const result = wrapper.find('.ops-result').text()
    expect(result).toContain('店铺 1')
    expect(result).toContain('尝试 1')
    expect(result).toContain('落库 12 行')
    // 真的写进了日报表才重拉本页数据
    expect(mockedGetAdReports.mock.calls.length).toBe(before + 1)
  })

  it('有店铺失败时不能把落库行数说成已补齐', async () => {
    mockedSyncShop.mockResolvedValue({
      code: 200, message: 'ok', data: { ...SYNC_OK, succeeded: 0, failed: 1, upserted: 0 }
    })
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    await openOps(wrapper)
    await clickOps(wrapper, '同步当前店铺')

    expect(wrapper.find('.ops-error').text()).toContain('不代表窗口已补齐')
    expect(wrapper.find('.ops-sub').text()).toContain('有店铺没同步成功')
  })

  it('一个店铺都没成功时说明 skipped 不等于完成，并且不空转重拉', async () => {
    mockedSyncShop.mockResolvedValue({
      code: 200, message: 'ok',
      data: { ...SYNC_OK, attempted: 1, succeeded: 0, skipped: 1, upserted: 0 }
    })
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    const before = mockedGetAdReports.mock.calls.length
    await openOps(wrapper)
    await clickOps(wrapper, '同步当前店铺')

    expect(wrapper.find('.ops-sub').text()).toContain('跳过（skipped）不等于同步完成')
    expect(mockedGetAdReports.mock.calls.length).toBe(before)
  })

  it('全店同步走不带 shopId 的重载，否则会退化成单店', async () => {
    mockedSyncAll.mockResolvedValue({
      code: 200, message: 'ok',
      data: { ...SYNC_OK, shopId: null, attempted: 5, succeeded: 5, upserted: 60 }
    })
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    await openOps(wrapper)
    await clickOps(wrapper, '同步全部店铺')

    expect(mockedSyncAll).toHaveBeenCalledWith(7)
    expect(mockedSyncShop).not.toHaveBeenCalled()
    expect(wrapper.find('.ops-scope').text()).toBe('全部店铺')
    expect(wrapper.find('[data-panel="sync"]').text()).toContain('只有 ADMIN 能过')
  })

  it('后端拒绝时把原因显示出来，不留下半截结果', async () => {
    mockedSyncAll.mockResolvedValue({ code: 400, message: '权限不足：需要角色 ADMIN', data: null as any })
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    await openOps(wrapper)
    await clickOps(wrapper, '同步全部店铺')

    expect(wrapper.find('.ops-error').text()).toContain('需要角色 ADMIN')
    expect(wrapper.find('.ops-result').exists()).toBe(false)
  })

  it('未选店铺时同步当前店铺既禁用也不发请求', async () => {
    localStorage.removeItem('current_shop_id')
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    await openOps(wrapper)
    const btn = wrapper.findAll('button').find((b: any) => b.text() === '同步当前店铺')
    expect(btn!.attributes('disabled')).toBeDefined()
    await btn!.trigger('click')
    expect(mockedSyncShop).not.toHaveBeenCalled()
  })
})
