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
    updateMaster: vi.fn(),
    runListingCheck: vi.fn(),
    getRankingTrend: vi.fn(),
    compareCompetitors: vi.fn()
  }
})

import {
  getListingSummary, getListingHealthList, getRankings, getCompetitors,
  getBuyBoxList, getChangeLogs, estimateFees, listMaster, createMaster, updateMaster,
  runListingCheck, getRankingTrend, compareCompetitors
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

  // ==================== 7i：人工自查 / 趋势 / 竞品对比 ====================
  const CHECK_RESULT = ok({
    id: 71, shopId: 900000000000001000, asin: 'B0NEWASIN', sku: null, status: 'ACTIVE',
    titleOk: false, bulletPointsOk: false, descriptionOk: false, aplusOk: null, imagesOk: false,
    searchTermsOk: false, suppressedReason: '标题长度需80-200字符; A+内容未检查',
    healthScore: 20, severity: 'CRITICAL', checkTime: '2026-10-03T10:00:00'
  })

  const TREND = ok({
    shopId: 900000000000001000, asin: 'B00000000001', days: 30, truncated: false,
    keywords: {
      'yoga mat': [
        { date: '2026-09-30', organicRank: 7, adRank: null },
        { date: '2026-09-29', organicRank: null, adRank: 12 }
      ]
    }
  })

  it('人工自查：ASIN 没填不能提交，提交带的是我填的判定项，A+ 未检查要如实显示', async () => {
    happyPath()
    vi.mocked(runListingCheck).mockResolvedValue(CHECK_RESULT)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    const panel = wrapper.find('[data-panel="check"]')
    expect(panel.text()).toContain('会写入健康度表')
    expect(panel.find('.action-btn').attributes('disabled')).toBeDefined()

    await panel.find('input').setValue('B0NEWASIN')
    const inputs = panel.findAll('input')
    await inputs[1].setValue('8')                      // 图片张数（面板里第一个 number 输入）
    await inputs[2].setValue('x'.repeat(90))           // 标题原文
    // 五点必须能带换行：单行 input 会把换行吃掉，后端的「≥5 条」就会误判
    await panel.find('textarea').setValue('a\nb\nc\nd\ne')
    const selects = panel.findAll('select')
    await selects[0].setValue('ACTIVE')               // Listing 状态
    await selects[1].setValue('')                     // A+ 保持未检查
    await panel.find('.action-btn').trigger('click')
    await flushPromises()

    expect(runListingCheck).toHaveBeenCalledWith(
      '900000000000001000', 'B0NEWASIN',
      expect.objectContaining({ imageCount: 8, status: 'ACTIVE', aplus: null })
    )
    const sent = vi.mocked(runListingCheck).mock.calls[0][2]
    expect('title' in sent).toBe(true)
    expect(sent.bullets).toBe('a\nb\nc\nd\ne')
    // 结果区：分数、严重度、以及「这一项没参与判定」
    const result = wrapper.find('.check-result').text()
    expect(result).toContain('20')
    expect(result).toContain('CRITICAL')
    expect(result).toContain('未检查')
    // 写库之后必须重算列表与概览
    expect(vi.mocked(getListingHealthList).mock.calls.length).toBe(2)
    expect(vi.mocked(getListingSummary).mock.calls.length).toBe(2)
  })

  it('自查被后端拒绝时把原因留在错误区，也不刷新成「好像登记过了」', async () => {
    happyPath()
    vi.mocked(runListingCheck).mockResolvedValue({ code: 400, message: '无权访问该店铺', data: null } as any)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    const panel = wrapper.find('[data-panel="check"]')
    await panel.find('input').setValue('B0NEWASIN')
    await panel.find('.action-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('登记自查')
    expect(wrapper.find('.error-zone').text()).toContain('无权访问该店铺')
    expect(wrapper.find('.check-result').exists()).toBe(false)
    expect(vi.mocked(getListingHealthList).mock.calls.length).toBe(1)
  })

  it('排名趋势：按 asin+keyword+days 查，缺失的排名显示 — 而不是 0', async () => {
    happyPath()
    vi.mocked(getRankingTrend).mockResolvedValue(TREND)
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[1].trigger('click')
    const panel = wrapper.find('[data-panel="trend"]')
    expect(panel.find('.action-btn').attributes('disabled')).toBeDefined()
    await panel.findAll('input')[0].setValue('B00000000001')
    await panel.findAll('input')[1].setValue('yoga mat')
    await panel.find('.action-btn').trigger('click')
    await flushPromises()

    expect(getRankingTrend).toHaveBeenCalledWith('900000000000001000', 'B00000000001', 'yoga mat', 30)
    const text = panel.text()
    expect(text).toContain('yoga mat · 2 个点')
    expect(text).not.toContain('命中后端单读上限')
    // 必须逐格断言：整段文本里「—」会因为另一列也存在而恒真，
    // 补成 0 的那一格就永远抓不到（第一轮变异就是这样漏掉的）
    const first = panel.findAll('tbody tr')[0].findAll('td')
    expect(first[0].text()).toBe('2026-09-30')
    expect(first[1].text()).toBe('7')
    expect(first[2].text()).toBe('—')
    const second = panel.findAll('tbody tr')[1].findAll('td')
    expect(second[1].text()).toBe('—')
    expect(second[2].text()).toBe('12')
  })

  it('趋势：命中上限要说明只是窗口内的点；空关键词组说清是没采集', async () => {
    happyPath()
    vi.mocked(getRankingTrend).mockResolvedValue(ok({
      shopId: 1, asin: 'B00000000009', days: 90, truncated: true, keywords: {}
    }))
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[1].trigger('click')
    const panel = wrapper.find('[data-panel="trend"]')
    await panel.findAll('input')[0].setValue('B00000000009')
    await panel.find('.action-btn').trigger('click')
    await flushPromises()

    expect(panel.text()).toContain('没有排名快照')
    expect(panel.text()).toContain('空不等于「没有排名」')
    // 关键词留空时不能把 days 之外多塞一个空参数
    expect(getRankingTrend).toHaveBeenCalledWith('900000000000001000', 'B00000000009', undefined, 30)
  })

  it('竞品对比：明说只查了竞品一侧，三态列不能把未知画成「否」', async () => {
    happyPath()
    vi.mocked(compareCompetitors).mockResolvedValue(ok({
      shopId: 1, myAsin: 'B0MINE00001', competitorAsin: 'B0COMPET01', days: 30,
      ownAsinCompared: false, truncated: false,
      latest: { competitorAsin: 'B0COMPET01', price: 25.99, bsRank: 88, reviewCount: 4120, reviewRating: 4.4, snapshotDate: '2026-10-01' },
      trendData: [
        { date: '2026-09-30', price: 25.99, bsRank: 88, reviewCount: 4120, reviewRating: 4.4, inStock: true, hasCoupon: null, hasDeal: false }
      ]
    }))
    const wrapper = mount(ListingMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.tab')[2].trigger('click')
    const panel = wrapper.find('[data-panel="compare"]')
    await panel.findAll('input')[1].setValue('B0COMPET01')
    await panel.find('.action-btn').trigger('click')
    await flushPromises()

    expect(compareCompetitors).toHaveBeenCalledWith('900000000000001000', '-', 'B0COMPET01', 30)
    const text = panel.text()
    expect(text).toContain('不是「并排对比」')
    expect(text).toContain('2026-10-01')
    const row = panel.findAll('tbody tr')[0].text()
    expect(row).toContain('是')
    expect(row).toContain('—')
    expect(row).toContain('否')
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })
})
