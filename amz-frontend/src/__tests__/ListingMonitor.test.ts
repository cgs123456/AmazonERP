import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ListingMonitor from '../views/ListingMonitor.vue'

vi.mock('@/api/listing', async () => {
  const actual = await vi.importActual<typeof import('@/api/listing')>('@/api/listing')
  return {
    ...actual,
    getListingSummary: vi.fn(),
    getListingHealthList: vi.fn(),
    getRankings: vi.fn(),
    getCompetitors: vi.fn(),
    getBuyBoxList: vi.fn(),
    getChangeLogs: vi.fn(),
    estimateFees: vi.fn(),
    listMaster: vi.fn(),
    createMaster: vi.fn(),
    updateMaster: vi.fn()
  }
})

import {
  getListingSummary, getListingHealthList, getRankings, getCompetitors,
  getBuyBoxList, getChangeLogs, estimateFees, listMaster, createMaster, updateMaster
} from '@/api/listing'

const ok = <T,>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null }) as any

// 下面这些 fixture 是从真跑的 amz-service-product 响应里原样抄下来的字段名，
// 不是按想象编的：ranking 用 organicRank/adRank/rankDate，竞品用 bsRank/reviewRating/inStock，
// buybox 用 isSelf/buyboxPrice/ourPrice/priceGap/ownershipPct。
const SUMMARY = ok({
  total: 50000, ok: 30000, warning: 10000, critical: 5000,
  avgScore: 70.0, healthRate: 60.0,
  worstListings: [{ asin: 'B000000019', score: null, severity: 'ok', reason: '' }]
})

const HEALTH_ROWS = ok([
  { id: 26108, shopId: 900000000000001000, asin: 'B000026108', sku: 'SKU-1108', status: 'ACTIVE',
    titleOk: true, bulletPointsOk: true, descriptionOk: true, aplusOk: true, imagesOk: true,
    searchTermsOk: true, suppressedReason: 'image_suppressed', healthScore: 42, severity: 'CRITICAL',
    checkTime: '2026-10-01T10:00:00' },
  { id: 26109, shopId: 900000000000001000, asin: 'B000026109', sku: 'SKU-1109', status: 'ACTIVE',
    titleOk: true, bulletPointsOk: true, descriptionOk: true, aplusOk: true, imagesOk: true,
    searchTermsOk: true, suppressedReason: null, healthScore: 96, severity: 'OK',
    checkTime: '2026-10-01T10:00:00' }
])

const RANKINGS = ok([
  { id: 2, shopId: 900000000000001000, asin: 'B00000000002', keyword: ' Resistance Band ',
    organicRank: 41, adRank: null, searchVolume: 12000, rankDate: '2026-10-01', marketplaceId: null },
  { id: 1, shopId: 900000000000001000, asin: 'B00000000001', keyword: 'yoga mat',
    organicRank: 7, adRank: 3, searchVolume: 60500, rankDate: '2026-10-01', marketplaceId: null }
])

const COMPETITORS = ok([
  { id: 1, shopId: 900000000000001000, competitorAsin: 'B0COMPET01', competitorTitle: 'Competitor yoga mat',
    price: 25.99, priceChange: null, bsRank: 88, reviewCount: 4120, reviewRating: 4.4,
    ratingChange: null, inStock: true, snapshotDate: '2026-10-01' }
])

const BUYBOX = ok([
  { id: 1, shopId: 900000000000001000, asin: 'B00000000001', sellerId: 'A2SELLER', isSelf: true,
    buyboxPrice: 23.50, ourPrice: 23.49, priceGap: -0.01, fulfillmentType: 'FBA',
    ownershipPct: 62.50, snapshotTime: '2026-10-02T08:30:02' }
])

const MASTERS = ok([
  { id: 1, shopId: 900000000000001000, sku: 'MASTER-1', asin: 'B0MASTER001',
    marketplaceId: 'ATVPDKIKX0DER', title: '瑜伽垫 6mm', brand: 'Akman',
    sizeTier: 'SMALL_LIGHT', weightG: 900, status: 'ACTIVE' }
])

const globalStubs = {
  stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' } }
}

const withShop = () => localStorage.setItem('current_shop_id', '900000000000001000')

const happyPath = () => {
  vi.mocked(getListingSummary).mockResolvedValue(SUMMARY)
  vi.mocked(getListingHealthList).mockResolvedValue(HEALTH_ROWS)
  vi.mocked(getRankings).mockResolvedValue(RANKINGS)
  vi.mocked(getCompetitors).mockResolvedValue(COMPETITORS)
  vi.mocked(getBuyBoxList).mockResolvedValue(BUYBOX)
  vi.mocked(getChangeLogs).mockResolvedValue(ok([]))
  vi.mocked(listMaster).mockResolvedValue(MASTERS)
}

describe('ListingMonitor 视图（商品与 Listing）', () => {
  beforeEach(() => {
    localStorage.clear()
    withShop()
    vi.clearAllMocks()
  })

  it('渲染标题、4 张概览卡与 6 个分区', async () => {
    happyPath()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.hero-title').text()).toBe('商品与 Listing')
    expect(wrapper.findAll('.health-card').length).toBe(4)
    expect(wrapper.findAll('.tab').length).toBe(6)
  })

  it('概览卡显示后端聚合出来的真实数字，不是页面常量', async () => {
    happyPath()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    const counts = wrapper.findAll('.health-count').map(c => c.text())
    expect(counts).toEqual(['50000', '70', '10000', '5000'])
    expect(wrapper.text()).toContain('达标率 60%')
  })

  it('健康度表按真实字段渲染，并带上严重度标签与抑制原因', async () => {
    happyPath()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('B000026108')
    expect(rows[0].text()).toContain('CRITICAL')
    expect(rows[0].text()).toContain('image_suppressed')
    expect(rows[1].text()).toContain('OK')
  })

  it('切到关键词排名 / 竞品 / Buybox 时渲染各自的真实字段', async () => {
    happyPath()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[1].trigger('click')
    const rankingTable = wrapper.find('.table-card').text()
    expect(rankingTable).toContain('yoga mat')
    expect(rankingTable).toContain('60500')
    expect(rankingTable).toContain('B00000000002')

    await wrapper.findAll('.tab')[2].trigger('click')
    expect(wrapper.find('.data-table tbody tr').text()).toContain('B0COMPET01')
    expect(wrapper.text()).toContain('Competitor yoga mat')

    await wrapper.findAll('.tab')[3].trigger('click')
    expect(wrapper.find('.data-table tbody tr').text()).toContain('A2SELLER')
    expect(wrapper.text()).toContain('我方')
  })

  it('严重度筛选要把 severity 传给后端而不是本地过滤', async () => {
    happyPath()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    vi.mocked(getListingHealthList).mockClear()
    // 走真实 DOM：select 的 change 才是用户点的那一下
    await wrapper.find('.filter select').setValue('CRITICAL')
    await flushPromises()
    expect(getListingHealthList).toHaveBeenCalledWith('900000000000001000', 'CRITICAL')
  })

  it('接口失败时显示错误区且不铺任何样例数据', async () => {
    vi.mocked(getListingSummary).mockResolvedValue({ code: 500, message: '凭证未配置', data: null } as any)
    vi.mocked(getListingHealthList).mockResolvedValue({ code: 500, message: '凭证未配置', data: [] } as any)
    vi.mocked(getRankings).mockRejectedValue(new Error('连接被拒绝'))
    vi.mocked(getCompetitors).mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(getBuyBoxList).mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(getChangeLogs).mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(listMaster).mockResolvedValue(ok([]))

    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    const errorText = wrapper.find('.error-zone').text()
    expect(errorText).toContain('凭证未配置')
    expect(errorText).toContain('连接被拒绝')
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(1)  // 只剩空态行
    expect(wrapper.find('.empty-state').text()).toContain('暂无 Listing 健康度记录')
  })

  it('主数据分区渲染行，并能把编辑后的尺寸档保存回去', async () => {
    happyPath()
    vi.mocked(updateMaster).mockResolvedValue(ok({ ...MASTERS.data[0], sizeTier: 'STANDARD' }) as any)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[5].trigger('click')
    expect(wrapper.find('.data-table tbody tr').text()).toContain('MASTER-1')
    expect(wrapper.find('.data-table tbody tr').text()).toContain('ATVPDKIKX0DER')

    // 第 2 个可编辑输入框是"尺寸档"（第 1 个是品牌）
    const inputs = wrapper.findAll('.cell-input')
    await inputs[1].setValue('STANDARD')
    await wrapper.find('tbody .page-btn').trigger('click')
    await flushPromises()
    expect(updateMaster).toHaveBeenCalledWith(
      '900000000000001000', 1, expect.objectContaining({ sizeTier: 'STANDARD' }))
  })

  it('创建主数据时把后端 NOT NULL 的拒绝原因显示出来', async () => {
    happyPath()
    vi.mocked(createMaster).mockResolvedValue({ code: 400, message: 'marketplaceId 不能为空（唯一键成员，DDL 为 NOT NULL）', data: null } as any)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[5].trigger('click')
    await wrapper.find('.master-grid .page-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.master-create').text()).toContain('marketplaceId 不能为空')
  })

  it('未选择店铺时不发请求', async () => {
    localStorage.clear()
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(getListingSummary).not.toHaveBeenCalled()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
  })

  it('FBA 费用试算把 ASIN 传给后端并展示返回', async () => {
    happyPath()
    vi.mocked(estimateFees).mockResolvedValue(ok({ feeTotal: 8.31, source: 'amazon' }) as any)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.find('.fee-input').setValue('B00000000001')
    await wrapper.find('.fee-form .page-btn').trigger('click')
    await flushPromises()

    expect(estimateFees).toHaveBeenCalledWith({ asin: 'B00000000001' })
    expect(wrapper.find('.fee-result').text()).toContain('feeTotal')
  })
})
