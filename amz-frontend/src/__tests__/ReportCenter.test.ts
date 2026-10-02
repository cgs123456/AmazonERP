import { describe, it, expect, beforeEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { mount, flushPromises } from '@vue/test-utils'
import ReportCenter from '../views/ReportCenter.vue'

vi.mock('@/api/report', async () => {
  const actual = await vi.importActual<typeof import('@/api/report')>('@/api/report')
  return {
    ...actual,
    shopDashboard: vi.fn(),
    listBusinessOverview: vi.fn(),
    listProfitDetails: vi.fn(),
    profitSummary: vi.fn(),
    listTurnover: vi.fn(),
    deadStock: vi.fn(),
    listSalesDaily: vi.fn(),
    salesComparison: vi.fn(),
    listSnapshots: vi.fn(),
    realtimeSummary: vi.fn(),
    profitTrend: vi.fn(),
    buildSnapshot: vi.fn(),
    listAllocations: vi.fn(),
    allocateCost: vi.fn()
  }
})

import * as rpt from '@/api/report'

const ok = <T,>(data: T, page?: unknown) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page ?? null }) as any
const pageOf = (nextCursor: string | null, truncated = nextCursor !== null) =>
  ({ size: 50, returned: 1, hasMore: truncated, truncated, nextCursor, total: null })

const DASH = ok({
  shopId: 1, reportDate: '2026-10-01',
  kpi: { totalSales7d: 18234.5, totalProfit7d: 2410.2, totalOrders7d: 118, totalAdSpend7d: 980.1, profitMargin7d: 13.22 },
  salesTrend30d: [{ date: '2026-09-30', netUnits: 12, netSales: 1200, sessions: 400, conversionRate: 3.0 }],
  profitSummary30d: { totalOrders: 500, totalSales: 60000, totalNetProfit: 8000 },
  deadStockAnalysis: { deadStockCount: 4, totalDeadStockValue: 1234.5 }
})

const OVERVIEW = ok([
  { id: 1, shopId: 1, reportDate: '2026-09-30', totalSales: 5000, totalOrders: 40, totalUnits: 55,
    avgOrderValue: 125, totalCost: 3000, totalAdSpend: 400, totalFees: 600, netProfit: 1000,
    profitMargin: 20, totalRefunds: 2, refundRate: 5, newReviews: 3, avgRating: 4.5,
    negativeReviews: 1, customerMessages: 6 }
])

const PROFIT_ROWS = ok([
  { id: 11, shopId: 1, amazonOrderId: '114-1', asin: 'B0A', sku: 'SKU-1', reportDate: '2026-09-30',
    productSales: 100, productCost: 40, fbaFees: 8, referralFee: 15, storageFee: 2,
    advertisingCost: 10, vatTax: 5, inboundFreight: 3, grossProfit: 30, netProfit: 17,
    margin: 17, currency: 'USD', exchangeRate: 7.1 }
], pageOf(null))

const PROFIT_SUMMARY = ok({
  shopId: 1, totalOrders: 1, totalSales: 100, totalCost: 78, totalNetProfit: 17, overallMargin: 17,
  asinSummaries: [{ asin: 'B0A', orderCount: 1, totalSales: 100, totalCost: 78, totalAdSpend: 10,
    totalFees: 25, grossProfit: 30, netProfit: 17, margin: 17 }]
})

const TURNOVER = ok([
  { id: 21, shopId: 1, asin: 'B0A', sku: 'SKU-1', reportDate: '2026-09-30', avgInventoryValue: 2000,
    cogs: 1200, turnoverRate: 0.6, daysOfSupply: 120, stockoutCount: 2, overstockDays: 45, deadStockValue: 800 }
])
const DEAD = ok({ shopId: 1, deadStockCount: 1, totalDeadStockValue: 800, details: [TURNOVER.data[0]] })

const SALES = ok([
  { id: 31, shopId: 1, asin: 'B0A', sku: 'SKU-1', reportDate: '2026-09-30', unitsOrdered: 12,
    unitsRefunded: 1, netUnits: 11, grossSales: 1200, refundAmount: 100, netSales: 1100,
    sessions: 400, pageViews: 900, conversionRate: 3, buyBoxPercentage: 88, unitsPerSession: 0.03 }
])

const SNAPSHOTS = ok([
  { id: 41, shopId: 1, sku: 'SKU-1', asin: 'B0A', statTime: '2026-10-01T10:00:00',
    salesAmount: 1000, salesQuantity: 10, productCost: 400, fbaFees: 80, referralFee: 150,
    advertisingCost: 100, storageFee: 20, headhaulCost: 30,
    vatCost: 0, refundCost: 0, otherCost: 0,
    grossProfit: 220, netProfit: 220, margin: 22, dataSource: 'CALC' }
], pageOf(null))

const REALTIME_SUMMARY = ok({
  shopId: 1, totalSales: 1000, totalNetProfit: 220, overallMargin: 22, skuCount: 1,
  skuSummaries: [{ sku: 'SKU-1', asin: 'B0A', sales: 1000, netProfit: 220, margin: 22, snapshotCount: 3 }]
})

const ALLOCATIONS = ok([
  { id: 51, shopId: 1, costType: 'HEADHAUL', sourceRef: 'FBA-SHIP-1', sourceDesc: '海运整柜',
    totalAmount: 900, currency: 'CNY', allocMethod: 'BY_QUANTITY',
    allocDetails: '{"SKU-1":600,"SKU-2":300}', allocDate: '2026-09-28' },
  { id: 52, shopId: 1, costType: 'ADVERTISING', totalAmount: 100, currency: 'CNY',
    allocDetails: 'not-json', allocDate: '2026-09-29' }
], pageOf(null))

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(rpt.shopDashboard).mockResolvedValue(DASH)
  vi.mocked(rpt.listBusinessOverview).mockResolvedValue(OVERVIEW)
  vi.mocked(rpt.listProfitDetails).mockResolvedValue(PROFIT_ROWS)
  vi.mocked(rpt.profitSummary).mockResolvedValue(PROFIT_SUMMARY)
  vi.mocked(rpt.listTurnover).mockResolvedValue(TURNOVER)
  vi.mocked(rpt.deadStock).mockResolvedValue(DEAD)
  vi.mocked(rpt.listSalesDaily).mockResolvedValue(SALES)
  vi.mocked(rpt.listSnapshots).mockResolvedValue(SNAPSHOTS)
  vi.mocked(rpt.realtimeSummary).mockResolvedValue(REALTIME_SUMMARY)
  vi.mocked(rpt.listAllocations).mockResolvedValue(ALLOCATIONS)
}

const mountPage = async () => {
  const wrapper = mount(ReportCenter, { global: stubs })
  await flushPromises()
  return wrapper
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.tab').find((b: any) => b.text() === label)
  await btn!.trigger('click')
  await flushPromises()
}

const clickBtn = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('button').find((b: any) => b.text() === label)
  expect(btn, `按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('ReportCenter 视图（经营报表）', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '900000000000001000')
    vi.clearAllMocks()
    happy()
  })

  it('渲染 5 个分区，且只在进入该分区时才请求对应端点', async () => {
    const wrapper = await mountPage()
    expect(wrapper.findAll('.tab').length).toBe(5)
    expect(rpt.shopDashboard).toHaveBeenCalledWith('900000000000001000')
    expect(rpt.listProfitDetails).not.toHaveBeenCalled()
    await openTab(wrapper, '利润明细')
    expect(rpt.listProfitDetails).toHaveBeenCalledTimes(1)
    expect(rpt.profitSummary).toHaveBeenCalledTimes(1)
  })

  it('口径缺口说明常驻页面：快照利润不能当结算依据', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.notice-zone').text()).toContain('VAT 成本、退款成本、其他成本写成常量 0')
    expect(wrapper.find('.notice-zone').text()).toContain('不能当结算依据')
  })

  it('概览卡显示后端 kpi 聚合值，滞销与利润合计来自看板内嵌结构', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('18234.5')
    expect(wrapper.text()).toContain('近 7 日毛利率')
    const panel = wrapper.find('[data-panel="overview"]')
    expect(panel.text()).toContain('125') // avgOrderValue 直接来自后端
    expect(panel.text()).toContain('近 30 日滞销')
    expect(panel.text()).toContain('1234.5')
  })

  it('利润汇总显示后端统计值，不前端求和；空汇总说明是真实统计结果', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '利润明细')
    expect(wrapper.text()).toContain('订单数（后端 COUNT）')
    expect(wrapper.text()).toContain('17')
    vi.mocked(rpt.profitSummary).mockResolvedValue(ok({
      shopId: 1, totalOrders: 0, totalSales: 0, totalCost: 0, totalNetProfit: 0, overallMargin: 0, asinSummaries: []
    }))
    const w2 = await mountPage()
    await openTab(w2, '利润明细')
    expect(w2.text()).toContain('汇总为 0 是后端的真实统计')
  })

  it('快照里三个常量字段用占位样式显示，不与真实成本混同', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '实时快照与成本分摊')
    const flagged = wrapper.findAll('.zero-flag')
    expect(flagged.length).toBe(3)
    expect(flagged.map((f: any) => f.text())).toEqual(['0', '0', '0'])
    expect(wrapper.text()).toContain('CALC')
  })

  it('服务端截断时给下一页入口并回传 cursor', async () => {
    vi.mocked(rpt.listProfitDetails).mockResolvedValue(ok(PROFIT_ROWS.data, pageOf('v1:11')))
    const wrapper = await mountPage()
    await openTab(wrapper, '利润明细')
    expect(wrapper.text()).toContain('后端标记仍有下一页')
    await clickBtn(wrapper, '加载下一页')
    expect(rpt.listProfitDetails).toHaveBeenLastCalledWith('900000000000001000',
      expect.objectContaining({ cursor: 'v1:11' }))
  })

  it('成本分摊执行前必须确认，SKU 列表按逗号（含全角）拆分', async () => {
    vi.mocked(rpt.allocateCost).mockResolvedValue(ok({ 'SKU-1': 50, 'SKU-2': 50 }))
    const wrapper = await mountPage()
    await openTab(wrapper, '实时快照与成本分摊')
    const inputs = wrapper.find('[data-panel="snapshot"]').findAll('input')
    // 顺序：SKU / 起 / 止 / 趋势小时 / 分摊总额 / SKU 列表
    await inputs[5].setValue('SKU-1，SKU-2 SKU-3')
    await inputs[4].setValue('100')
    await clickBtn(wrapper, '执行分摊')
    expect(rpt.allocateCost).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('重复执行会新增一条')
    await clickBtn(wrapper, '确认执行')
    expect(rpt.allocateCost).toHaveBeenCalledWith('900000000000001000', 'HEADHAUL', 100, ['SKU-1', 'SKU-2', 'SKU-3'])
    // 结果表按后端返回的 map 渲染（后端只回了两个 key，就不硬凑第三个）
    expect(wrapper.text()).toContain('分摊金额')
    expect(wrapper.text()).toContain('SKU-1')
  })

  it('分摊明细是后端存的 JSON 文本：能解析就展开，解析不了就原样显示', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '实时快照与成本分摊')
    expect(wrapper.text()).toContain('SKU-1:600 SKU-2:300')
    expect(wrapper.text()).toContain('not-json')
  })

  it('重算快照没有 SKU 时不发请求，也不伪装成成功', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '实时快照与成本分摊')
    await clickBtn(wrapper, '按 SKU 重算快照')
    await clickBtn(wrapper, '确认执行')
    expect(rpt.buildSnapshot).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('请先填 SKU')
  })

  it('滞销与周转分别走两个端点，前端不自己判定滞销', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '库存周转与滞销')
    expect(rpt.listTurnover).toHaveBeenCalledWith('900000000000001000', undefined)
    expect(rpt.deadStock).toHaveBeenCalledWith('900000000000001000')
    expect(wrapper.text()).toContain('滞销 SKU 数')
    expect(wrapper.text()).toContain('1234.5')
    expect(wrapper.text()).toContain('800')
  })

  it('同期对比把上期/本期与增长率并排显示，增长为负时标红', async () => {
    vi.mocked(rpt.salesComparison).mockResolvedValue(ok({
      shopId: 1, asin: 'B0A', currentPeriod: '2026-09-01 ~ 2026-09-30',
      previousPeriod: '2026-08-02 ~ 2026-08-31', currentUnits: 90, previousUnits: 100,
      unitGrowth: -10, currentSales: 9000, previousSales: 10000, salesGrowth: -10
    }))
    const wrapper = await mountPage()
    await openTab(wrapper, '日销与同期对比')
    await clickBtn(wrapper, '同期对比')
    expect(wrapper.text()).toContain('2026-08-02 ~ 2026-08-31')
    expect(wrapper.find('.neg').text()).toContain('-10')
  })

  it('端点失败：显示后端原因，空表说明是真的没有数据', async () => {
    vi.mocked(rpt.shopDashboard).mockResolvedValue({ code: 500, message: '报表服务不可用', data: null } as any)
    vi.mocked(rpt.listBusinessOverview).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('报表服务不可用')
    expect(wrapper.text()).toContain('该店铺没有每日概览数据')
  })

  it('5 个实体入库 POST 不接入 UI：只能由调度器/服务写入，且其 ShopScoped 是空转的', () => {
    const src = readFileSync('src/api/report.ts', 'utf8')
    const posts = src.match(/request\.post[^>]*/gs) || []
    expect(posts.length).toBe(2)
    expect(src).toMatch(/\/report\/profit\/snapshot'/)
    expect(src).toMatch(/\/report\/profit\/allocate\//)
    expect(src).not.toMatch(/request\.post[^;]*\/report\/v2\/(profit|inventory-turnover|sales-daily|business-overview)/)
    expect(src).not.toMatch(/request\.post[^;]*\/report\/profit\/allocation'/)
  })
})
