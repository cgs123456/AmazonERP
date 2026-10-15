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
import {
  listCampaigns,
  createCampaign,
  updateCampaign,
  batchCreateCampaigns,
  batchUpdateStatus,
  listCreatives,
  updateCreative,
  listTargeting,
  updateTargeting
} from '@/api/ad-ext'

const mockedGetAdReports = vi.mocked(getAdReports)
const mockedGetAdTrend = vi.mocked(getAdTrend)
const mockedListCampaigns = vi.mocked(listCampaigns)
const mockedSyncShop = vi.mocked(syncAdReports)
const mockedSyncAll = vi.mocked(syncAllAdReports)
const mockedCreateCampaign = vi.mocked(createCampaign)
const mockedUpdateCampaign = vi.mocked(updateCampaign)
const mockedBatchCreateCampaigns = vi.mocked(batchCreateCampaigns)
const mockedBatchUpdateStatus = vi.mocked(batchUpdateStatus)
const mockedListCreatives = vi.mocked(listCreatives)
const mockedUpdateCreative = vi.mocked(updateCreative)
const mockedListTargeting = vi.mocked(listTargeting)
const mockedUpdateTargeting = vi.mocked(updateTargeting)

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
    // 页面现在有两张可收起的卡片（日报同步 / 活动写操作），按钮文案都是「展开」。
    // 按文案找会命中先出现的那张（活动写操作在 DOM 里更靠前），必须缩到本卡内。
    const btn = wrapper.find('[data-panel="sync"]').findAll('button').find((b: any) => b.text() === '展开')
    expect(btn, '日报同步卡片没有展开入口').toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
  }

  const clickOps = async (wrapper: any, label: string) => {
    const matches = wrapper.find('[data-panel="sync"]').findAll('button').filter((b: any) => b.text() === label)
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

describe('AdManager SP 表格的花费/销售额来源', () => {
  // amz_ad_campaign_ext 的 spend/sales/acos 三列在库里是 DEFAULT 0，
  // 全仓没有任何写入路径（日报同步写的是 amz_ad_daily_report），
  // 所以直接读这三列会把「没有数据」渲染成 $0.00 / 0%。
  const extRow = (campaignId: string, name: string) => ({
    id: 1, shopId: 1, campaignId, campaignName: name, adType: 'SP',
    budget: 50, status: 'ENABLED', spend: 0, sales: 0, acos: 0
  })

  const spTable = (wrapper: any) => {
    const t = wrapper.findAll('table.data-table').filter((n: any) => n.text().includes('蓝牙耳机'))
    expect(t.length, 'SP 表格没找到').toBe(1)
    return t[0]
  }

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.stubEnv('VITE_AD_DEMO_MODE', 'false')
    mockedGetAdTrend.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllEnvs()
  })

  it('按 campaignId 关联日报：花费/销售额/ACoS 用真实聚合值', async () => {
    mockedGetAdReports.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ campaignId: 'SP-1', cost: 32.5, sales: 158, impressions: 10, clicks: 4 }]
    } as any)
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok', data: [extRow('SP-1', '关键词-蓝牙耳机-US')]
    } as any)

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    const row = spTable(wrapper).findAll('tbody tr')[0]
    expect(row.text()).toContain('$32.50')
    expect(row.text()).toContain('$158.00')
    expect(row.text()).toContain('20.6%')
    // 日预算仍来自 ext 表（这列日报同步确实会写）
    expect(row.text()).toContain('$50')
  })

  it('活动没有日报行时显示 —，不显示 $0.00', async () => {
    mockedGetAdReports.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok', data: [extRow('SP-9', '关键词-蓝牙耳机-US')]
    } as any)

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    const text = spTable(wrapper).findAll('tbody tr')[0].text()
    expect(text).toContain('—')
    expect(text).not.toContain('$0.00')
    expect(text).not.toContain('0%')
  })

  it('有花费但销售额为 0 时 ACoS 是 —，不是 0%（0% 会被读成免费流量）', async () => {
    mockedGetAdReports.mockResolvedValue({
      code: 200, message: 'ok', data: [{ campaignId: 'SP-1', cost: 32.5, sales: 0 }]
    } as any)
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok', data: [extRow('SP-1', '关键词-蓝牙耳机-US')]
    } as any)

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    const text = spTable(wrapper).findAll('tbody tr')[0].text()
    expect(text).toContain('$32.50')
    expect(text).toContain('—')
    expect(text).not.toContain('0%')
  })

  it('表格说明数据来源与 — 的含义', async () => {
    mockedGetAdReports.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok', data: [extRow('SP-1', '关键词-蓝牙耳机-US')]
    } as any)

    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()

    const note = wrapper.find('[data-note="sp-source"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('日报')
    expect(note.text()).toContain('—')
  })
})

// ===== ad 域 CRUD 收口（曾零调用：createCampaign/updateCampaign/batchCreateCampaigns/
//      batchUpdateStatus 与 updateCreative/updateTargeting）=====
// 这些 api 函数定义完整但页面从未调用（只在测试里被 mock），SP 活动表长期只读、
// 素材与定向只有「新建」没有「编辑」。后端五个写端点全部 @RequireRole(OPERATOR/ADMIN)，
// 入口门控沿用采购页取消付款 / 搜索词批量出建议的先例：未知角色放行让后端做权威判定。
describe('AdManager ad 域 CRUD 收口', () => {
  const CAMPAIGN_ROW = {
    id: 11, shopId: 1, campaignId: 'C-1001', campaignName: '关键词-蓝牙耳机-US',
    adType: 'SP', status: 'ENABLED', budget: 50
  }

  const mountAds = async () => {
    const wrapper = mount(AdManager, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  const spTable = (wrapper: any) =>
    wrapper.findAll('table.data-table').filter((t: any) => t.text().includes('活动名称'))[0]

  const openSpOps = async (wrapper: any) => {
    const card = wrapper.find('[data-panel="campaign-ops"]')
    await card.findAll('button').find((b: any) => b.text() === '展开')!.trigger('click')
    await flushPromises()
    return card
  }

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.stubEnv('VITE_AD_DEMO_MODE', 'false')
    mockedGetAdReports.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedGetAdTrend.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedListCampaigns.mockReset().mockResolvedValue({
      code: 200, message: 'ok', data: [CAMPAIGN_ROW]
    } as any)
    mockedListCreatives.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedListTargeting.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedCreateCampaign.mockReset()
    mockedUpdateCampaign.mockReset()
    mockedBatchCreateCampaigns.mockReset()
    mockedBatchUpdateStatus.mockReset()
    mockedUpdateCreative.mockReset()
    mockedUpdateTargeting.mockReset()
    localStorage.setItem('user_role', 'ADMIN')
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllEnvs()
  })

  // ===== 入口门控 =====
  it('ADMIN/OPERATOR 看到新建/批量/编辑入口；VIEWER 看不到', async () => {
    const wrapper = await mountAds()
    expect(wrapper.find('[data-panel="campaign-ops"]').exists()).toBe(true)
    expect(spTable(wrapper).text()).toContain('编辑')

    localStorage.setItem('user_role', 'VIEWER')
    const viewer = await mountAds()
    expect(viewer.find('[data-panel="campaign-ops"]').exists()).toBe(false)
    expect(spTable(viewer).text()).not.toContain('编辑')
  })

  it('角色未知时不隐藏入口（后端才是权限权威判定）', async () => {
    localStorage.removeItem('user_role')
    const wrapper = await mountAds()
    expect(wrapper.find('[data-panel="campaign-ops"]').exists()).toBe(true)
  })

  // ===== 新建活动 =====
  it('新建活动走 createCampaign，成功后重拉列表', async () => {
    mockedCreateCampaign.mockResolvedValue({ code: 200, message: 'ok', data: CAMPAIGN_ROW } as any)
    const wrapper = await mountAds()
    const before = mockedListCampaigns.mock.calls.length

    await openSpOps(wrapper)
    await wrapper.findAll('button').find((b: any) => b.text() === '新建活动')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('新建广告活动')
    // 必填项与后端 validateCreateFields 同口径：活动ID / 活动名称 / 广告类型
    expect(dialog.findAll('input').length).toBeGreaterThanOrEqual(2)

    await dialog.findAll('input')[0].setValue('C-2001')
    await dialog.findAll('input')[1].setValue('新品推广-US')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedCreateCampaign).toHaveBeenCalledTimes(1)
    expect(mockedCreateCampaign.mock.calls[0][0]).toBe('1')
    const body = mockedCreateCampaign.mock.calls[0][1] as any
    expect(body.campaignId).toBe('C-2001')
    expect(body.campaignName).toBe('新品推广-US')
    expect(body.adType).toBe('SP')
    // 预算没填就必须整个不带这个键：填 0 会把活动设成「花不出钱」，
    // 那不是「保持原值」（后端 NOT_NULL 策略下 0 是真值、null 才是不写）。
    expect('budget' in body).toBe(false)
    expect(mockedListCampaigns.mock.calls.length).toBe(before + 1)
  })

  it('编辑活动只提交用户改过的字段，未填预算不带 0', async () => {
    mockedUpdateCampaign.mockResolvedValue({ code: 200, message: 'ok', data: CAMPAIGN_ROW } as any)
    const wrapper = await mountAds()

    await spTable(wrapper).findAll('button').find((b: any) => b.text() === '编辑')!.trigger('click')
    await flushPromises()
    // 列表行预填了 budget=50，表单里清空它 = 「不改预算」
    const dialog = wrapper.find('.modal-mask')
    const budgetInput = dialog.findAll('input').find((i: any) => i.element.value === '50')!
    await budgetInput.setValue('')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    const body = mockedUpdateCampaign.mock.calls[0][1] as any
    expect('budget' in body).toBe(false)
  })

  it('必填项缺失时前端就拦住，不发请求', async () => {
    const wrapper = await mountAds()
    await openSpOps(wrapper)
    await wrapper.findAll('button').find((b: any) => b.text() === '新建活动')!.trigger('click')
    await flushPromises()

    const save = wrapper.find('.modal-mask').findAll('button').find((b: any) => b.text() === '保存')!
    expect(save.attributes('disabled')).toBeDefined()
    expect(mockedCreateCampaign).not.toHaveBeenCalled()
  })

  it('创建被后端拒绝时点名原因，不关弹窗也不假装成功', async () => {
    mockedCreateCampaign.mockResolvedValue({
      code: 400, message: '活动状态仅支持 ENABLED / PAUSED / ARCHIVED', data: null as any
    })
    const wrapper = await mountAds()
    await openSpOps(wrapper)
    await wrapper.findAll('button').find((b: any) => b.text() === '新建活动')!.trigger('click')
    await flushPromises()
    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('input')[0].setValue('C-2002')
    await dialog.findAll('input')[1].setValue('会被拒的活动')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.ops-error').text()).toContain('活动状态仅支持')
    expect(wrapper.find('.modal-mask').exists()).toBe(true)
  })

  // ===== 编辑活动 =====
  it('编辑活动先按列表行回填，提交走 updateCampaign 且不改活动ID', async () => {
    mockedUpdateCampaign.mockResolvedValue({ code: 200, message: 'ok', data: CAMPAIGN_ROW } as any)
    const wrapper = await mountAds()
    const before = mockedListCampaigns.mock.calls.length

    const editBtn = spTable(wrapper).findAll('button').find((b: any) => b.text() === '编辑')!
    await editBtn.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('编辑广告活动')
    // 活动ID 是后端拒改字段：编辑态只读展示，改了也发不出去
    const idInput = dialog.findAll('input').find((i: any) => i.element.value === 'C-1001')!
    expect(idInput).toBeTruthy()
    expect(idInput.attributes('readonly')).toBeDefined()
    // 名称/预算应已回填该行真值
    expect(dialog.findAll('input').some((i: any) => i.element.value === '关键词-蓝牙耳机-US')).toBe(true)

    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateCampaign).toHaveBeenCalledTimes(1)
    const body = mockedUpdateCampaign.mock.calls[0][1] as any
    expect(body.campaignId).toBe('C-1001')
    expect(body.id).toBe(11)
    expect(mockedListCampaigns.mock.calls.length).toBe(before + 1)
  })

  it('多行时编辑第二行回填的是第二行（按活动ID取行，不靠下标对齐）', async () => {
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok',
      data: [
        CAMPAIGN_ROW,
        { ...CAMPAIGN_ROW, id: 12, campaignId: 'C-2002', campaignName: '第二个活动', budget: 88 }
      ]
    } as any)
    mockedUpdateCampaign.mockResolvedValue({ code: 200, message: 'ok', data: CAMPAIGN_ROW } as any)
    const wrapper = await mountAds()

    const rows = spTable(wrapper).findAll('tbody tr')
    expect(rows.length).toBe(2)
    // 表格里有两行都带「编辑」，按所在行找第二个
    await rows[1].findAll('button').find((b: any) => b.text() === '编辑')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.findAll('input').some((i: any) => i.element.value === 'C-2002')).toBe(true)
    expect(dialog.findAll('input').some((i: any) => i.element.value === 'C-1001')).toBe(false)

    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    const body = mockedUpdateCampaign.mock.calls[0][1] as any
    expect(body.campaignId).toBe('C-2002')
    expect(body.id).toBe(12)
  })

  it('编辑被拒时点名原因且不重拉列表', async () => {
    mockedUpdateCampaign.mockResolvedValue({
      code: 400, message: '广告活动不存在或无权访问', data: null as any
    })
    const wrapper = await mountAds()
    const before = mockedListCampaigns.mock.calls.length

    await spTable(wrapper).findAll('button').find((b: any) => b.text() === '编辑')!.trigger('click')
    await flushPromises()
    await wrapper.find('.modal-mask').findAll('button')
      .find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.ops-error').text()).toContain('无权访问')
    expect(mockedListCampaigns.mock.calls.length).toBe(before)
  })

  // ===== 批量新建 =====
  it('批量新建按行收集后一次性 POST /ad/campaigns/batch', async () => {
    mockedBatchCreateCampaigns.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    const wrapper = await mountAds()
    const before = mockedListCampaigns.mock.calls.length

    await openSpOps(wrapper)
    await wrapper.findAll('button').find((b: any) => b.text() === '批量新建')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('批量新建广告活动')
    // 说明区必须披露：这是一个数组体的单次请求，与逐条 create 不同
    expect(dialog.text()).toContain('一次请求')

    const areas = dialog.findAll('textarea')
    await areas[0].setValue('C-3001, 批量活动一\nC-3002, 批量活动二')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedBatchCreateCampaigns).toHaveBeenCalledTimes(1)
    const rows = mockedBatchCreateCampaigns.mock.calls[0][1] as any[]
    expect(rows.map((r) => r.campaignId)).toEqual(['C-3001', 'C-3002'])
    expect(rows[0].adType).toBe('SP')
    expect(mockedListCampaigns.mock.calls.length).toBe(before + 1)
  })

  it('批量新建行格式错误时逐行点名，不发请求', async () => {
    const wrapper = await mountAds()
    await openSpOps(wrapper)
    await wrapper.findAll('button').find((b: any) => b.text() === '批量新建')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('textarea')[0].setValue('C-3001, 合规的活动\n只有一行没有逗号')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedBatchCreateCampaigns).not.toHaveBeenCalled()
    // 报错必须点出是第几行：批量场景下「有一行格式不对」没法定位
    expect(dialog.text()).toContain('第 2 行')
  })

  // ===== 批量改状态 =====
  it('批量改状态带二次确认，按勾选行一次 PUT，完成后重拉', async () => {
    mockedBatchUpdateStatus.mockResolvedValue({
      code: 200, message: 'ok', data: [{ ...CAMPAIGN_ROW, status: 'PAUSED' }]
    } as any)
    const wrapper = await mountAds()
    const before = mockedListCampaigns.mock.calls.length

    await openSpOps(wrapper)
    // 勾选当前 SP 面板里唯一的行
    await spTable(wrapper).find('input[type="checkbox"]').setValue(true)
    // 按钮文案带勾选数，用前缀匹配
    const pauseBtn = wrapper.findAll('button').find((b: any) => b.text().startsWith('批量暂停'))!
    expect(pauseBtn.text()).toContain('1')
    await pauseBtn.trigger('click')
    await flushPromises()

    // 未确认前不发请求，且确认框必须说明这是不可逆的真实平台改动
    expect(mockedBatchUpdateStatus).not.toHaveBeenCalled()
    const confirm = wrapper.find('.modal-mask')
    expect(confirm.text()).toContain('无法帮你撤销')
    expect(confirm.text()).toContain('立刻')

    await wrapper.findAll('button').find((b: any) => b.text() === '确认暂停')!.trigger('click')
    await flushPromises()

    expect(mockedBatchUpdateStatus).toHaveBeenCalledTimes(1)
    expect(mockedBatchUpdateStatus.mock.calls[0][0]).toBe('1')
    expect(mockedBatchUpdateStatus.mock.calls[0][1]).toEqual([11])
    expect(mockedBatchUpdateStatus.mock.calls[0][2]).toBe('PAUSED')
    expect(mockedListCampaigns.mock.calls.length).toBe(before + 1)
  })

  it('未勾选任何行时批量改状态不可点', async () => {
    const wrapper = await mountAds()
    await openSpOps(wrapper)
    const btn = wrapper.findAll('button').find((b: any) => b.text().startsWith('批量暂停'))!
    expect(btn.attributes('disabled')).toBeDefined()
    expect(mockedBatchUpdateStatus).not.toHaveBeenCalled()
  })

  it('批量改状态只回来部分行时不报成功，点名勾选数与实际更新数', async () => {
    // 后端按 ids 原序返回更新后的行：勾 2 个只回 1 个 = 有一个被归属校验拦下
    mockedListCampaigns.mockResolvedValue({
      code: 200, message: 'ok', data: [CAMPAIGN_ROW, { ...CAMPAIGN_ROW, id: 12, campaignId: 'C-1002' }]
    } as any)
    mockedBatchUpdateStatus.mockResolvedValue({ code: 200, message: 'ok', data: [CAMPAIGN_ROW] } as any)
    const wrapper = await mountAds()
    await openSpOps(wrapper)
    const boxes = spTable(wrapper).findAll('input[type="checkbox"]')
    await boxes[0].setValue(true)
    await boxes[1].setValue(true)

    await wrapper.findAll('button').find((b: any) => b.text().startsWith('批量暂停'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b: any) => b.text() === '确认暂停')!.trigger('click')
    await flushPromises()

    // 勾 2 个只回 1 个：不能报成「已暂停 2 个」
    expect(wrapper.find('.ops-error').text()).toContain('勾选 2 个活动，只有 1 个被更新')
  })

  // ===== 素材编辑 =====
  it('素材行编辑回填表单，提交走 updateCreative 而非 create', async () => {
    mockedListCreatives.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ id: 21, campaignId: 'C-1001', creativeType: 'VIDEO', headline: '旧标题', brandName: 'E2E Brand', status: 'PENDING' }]
    } as any)
    mockedUpdateCreative.mockResolvedValue({ code: 200, message: 'ok', data: { id: 21 } } as any)
    const wrapper = await mountAds()

    await wrapper.findAll('.ad-tab-item').find((t: any) => t.text().includes('SB'))!.trigger('click')
    await flushPromises()
    const sb = wrapper.findAll('.ext-section').filter((s: any) => s.text().includes('SB 广告素材'))[0]
    await sb.findAll('input')[0].setValue('C-1001')
    await sb.findAll('button').find((b: any) => b.text() === '查询')!.trigger('click')
    await flushPromises()
    // 行必须已经渲染出来，否则下面的「编辑」找不到
    expect(sb.findAll('tbody tr').length).toBe(1)

    await sb.findAll('button').find((b: any) => b.text() === '编辑')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('编辑 SB 广告素材')
    const headline = dialog.findAll('input').find((i: any) => i.element.value === '旧标题')!
    await headline.setValue('新标题')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateCreative).toHaveBeenCalledTimes(1)
    const body = mockedUpdateCreative.mock.calls[0][1] as any
    expect(body.id).toBe(21)
    expect(body.headline).toBe('新标题')
  })

  // ===== 定向编辑 =====
  it('定向行编辑回填表单，提交走 updateTargeting 而非 create', async () => {
    mockedListTargeting.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ id: 31, campaignId: 'C-1002', targetingType: 'CONTEXTUAL', targetingValue: 'electronics', bid: 0.85 }]
    } as any)
    mockedUpdateTargeting.mockResolvedValue({ code: 200, message: 'ok', data: { id: 31 } } as any)
    const wrapper = await mountAds()

    await wrapper.findAll('.ad-tab-item').find((t: any) => t.text().includes('SD'))!.trigger('click')
    await flushPromises()
    const sd = wrapper.findAll('.ext-section').filter((s: any) => s.text().includes('SD 受众定向'))[0]
    await sd.findAll('input')[0].setValue('C-1002')
    await sd.findAll('button').find((b: any) => b.text() === '查询')!.trigger('click')
    await flushPromises()
    expect(sd.findAll('tbody tr').length).toBe(1)

    await sd.findAll('button').find((b: any) => b.text() === '编辑')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('编辑 SD 受众定向')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateTargeting).toHaveBeenCalledTimes(1)
    const body = mockedUpdateTargeting.mock.calls[0][1] as any
    expect(body.id).toBe(31)
    expect(body.targetingValue).toBe('electronics')
  })
})
