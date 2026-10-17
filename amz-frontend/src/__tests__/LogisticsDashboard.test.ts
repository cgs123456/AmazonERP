import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import LogisticsDashboard from '../views/LogisticsDashboard.vue'
// 直接引用真实文案表：这条要钉的正是「表里的字没被改」，
// 若从组件内部取，就无法分辨「表被改了组件也跟着改」和「表没变」两种情况。
import { TRANSFER_STATUS_LABELS } from '../components/logisticsTransferStatus'
// 直接引用真实文案表：钉的是「表里的字没被改」，从组件内部取就无法区分
// 「表被改了组件也跟着改」和「表没变」两种情况。
import { BASIS_LABELS, basisLabel } from '../components/logisticsQuoteLabels'
import { STATUS_LABELS, statusLabel, statusTone } from '../components/logisticsStatus'
import { METHOD_LABELS, methodLabel } from '../components/logisticsMethodLabels'

// 整体 mock 物流 API，避免测试触发真实请求
vi.mock('@/api/logistics', () => ({
  getLogisticsOverview: vi.fn(),
  getLogisticsTrend: vi.fn(),
  getCarrierPerformance: vi.fn(),
  getLogisticsAlerts: vi.fn(),
  listShipments: vi.fn(),
  syncShipment: vi.fn(),
  closeShipment: vi.fn(),
  getShipmentTracking: vi.fn(),
  importShipments: vi.fn(),
  importTracking: vi.fn(),
  // 运营子域看板（比价 / 调拨 / 头程成本 / 签收差异）
  getQuoteBoard: vi.fn(),
  getTransferBoard: vi.fn(),
  getFreightCostBoard: vi.fn(),
  getReceiptBoard: vi.fn(),
  compareQuotes: vi.fn(),
  expireOutdatedQuotes: vi.fn(),
  listQuotes: vi.fn(),
  listTransfers: vi.fn(),
  approveTransfer: vi.fn(),
  shipTransfer: vi.fn(),
  receiveTransfer: vi.fn(),
  calculateFreight: vi.fn(),
  investigateDiscrepancy: vi.fn(),
  resolveDiscrepancy: vi.fn()
}))

import {
  getLogisticsOverview,
  getLogisticsTrend,
  getCarrierPerformance,
  getLogisticsAlerts,
  listShipments,
  getShipmentTracking,
  importShipments,
  getQuoteBoard,
  getTransferBoard,
  getFreightCostBoard,
  getReceiptBoard,
  compareQuotes,
  expireOutdatedQuotes,
  listQuotes,
  listTransfers,
  approveTransfer,
  calculateFreight,
  resolveDiscrepancy,
  investigateDiscrepancy,
  syncShipment,
  closeShipment
} from '@/api/logistics'

const mockedOverview = vi.mocked(getLogisticsOverview)
const mockedTrend = vi.mocked(getLogisticsTrend)
const mockedCarriers = vi.mocked(getCarrierPerformance)
const mockedAlerts = vi.mocked(getLogisticsAlerts)
const mockedShipments = vi.mocked(listShipments)
const mockedTracking = vi.mocked(getShipmentTracking)
const mockedImportShipments = vi.mocked(importShipments)
const mockedQuoteBoard = vi.mocked(getQuoteBoard)
const mockedTransferBoard = vi.mocked(getTransferBoard)
const mockedFreightBoard = vi.mocked(getFreightCostBoard)
const mockedReceiptBoard = vi.mocked(getReceiptBoard)
const mockedCompare = vi.mocked(compareQuotes)
const mockedExpire = vi.mocked(expireOutdatedQuotes)
const mockedQuotes = vi.mocked(listQuotes)
const mockedTransfers = vi.mocked(listTransfers)
const mockedApprove = vi.mocked(approveTransfer)
const mockedCalculate = vi.mocked(calculateFreight)
const mockedResolve = vi.mocked(resolveDiscrepancy)
const mockedInvestigate = vi.mocked(investigateDiscrepancy)
const mockedSync = vi.mocked(syncShipment)
const mockedClose = vi.mocked(closeShipment)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: true
  }
}

/** 统一分页元数据夹具；未指定时表示当前页已取完。 */
const pageMeta = (rows: unknown[], overrides: Record<string, unknown> = {}) => ({
  size: 50,
  returned: rows.length,
  hasMore: false,
  truncated: false,
  nextCursor: null,
  total: null,
  ...overrides
})

/** 后端概览返回的标准结构（含全部字段，避免测试因缺字段而失真） */
const overviewFixture = (overrides: Record<string, unknown> = {}) => ({
  totalShipments: 12,
  statusCounts: {
    CREATED: 1,
    IN_TRANSIT: 5,
    CUSTOMS: 2,
    DELIVERED: 2,
    RECEIVED: 0,
    CLOSED: 0,
    DELAYED: 2,
    EXCEPTION: 0
  },
  activeShipments: 10,
  delayed: 2,
  exception: 0,
  arrivingIn7Days: 3,
  etaOverdue: 1,
  missingTrackingNo: 2,
  staleShipments: 1,
  staleThresholdHours: 48,
  avgTransitDays: 28.44,
  transitStatWindowDays: 90,
  lastTrackTime: '2026-08-18T10:30:00',
  autoSyncAvailable: true,
  dataSourceCounts: { IMPORT: 4, API: 6, AUTO: 2 },
  ...overrides
})

const alertsFixture = [
  {
    type: 'DELAYED',
    severity: 'HIGH',
    shipmentId: 101,
    shipmentNo: 'SHP-101',
    carrier: 'COSCO',
    masterTrackingNo: 'COSU001',
    status: 'DELAYED',
    eta: '2026-08-01',
    daysOverdue: 17,
    lastTrackTime: '2026-08-18T09:00:00',
    message: '预计到港 2026-08-01，已超期 17 天仍未推进',
    actionHint: '联系承运商核实实际位置，必要时调整补货计划'
  },
  {
    type: 'MISSING_TRACKING_NO',
    severity: 'LOW',
    shipmentId: 102,
    shipmentNo: 'SHP-102',
    carrier: 'DHL',
    masterTrackingNo: null,
    status: 'IN_TRANSIT',
    eta: '2026-08-30',
    daysOverdue: -12,
    lastTrackTime: null,
    message: '缺少主运单号，无法自动获取轨迹',
    actionHint: '补录主运单号后即纳入自动跟踪'
  }
]

const mountView = () => mount(LogisticsDashboard, { global: globalStubs })

/**
 * 取页壳实例。用于调子组件 defineExpose 出来的方法（如 shipmentPanel.load()）——
 * 有些分支（无店铺清空列表）在真实 UI 路径上会被「整块卸载」掩盖，只能直接调。
 */
const vmOf = (wrapper: ReturnType<typeof mountView>) => wrapper.vm as unknown as {
  shipmentPanel: { load: () => Promise<void> }
}

// ------------------------------------------------------------
// 运营子域看板 fixtures
// ------------------------------------------------------------

const quoteBoardFixture = (overrides: Record<string, unknown> = {}) => ({
  shopId: 1,
  totalQuotes: 6,
  validQuotes: 3,
  staleByDate: 2,
  expiringIn30Days: 1,
  inactiveQuotes: 1,
  unpricedQuotes: 1,
  carrierCount: 3,
  routeCount: 2,
  byServiceType: { SEA: 2, EXPRESS: 1 },
  byCurrency: { USD: 3 },
  routes: [
    {
      originPort: 'Ningbo',
      destinationPort: 'NYC',
      carrierCount: 1,
      carriers: ['DHL'],
      minTransitDays: 5,
      maxTransitDays: 5,
      minPricePerKg: 8.5,
      maxPricePerKg: 8.5,
      minPricePerCbm: null,
      maxPricePerCbm: null,
      currency: 'USD',
      singleSource: true,
      priceSpreadRate: null
    },
    {
      originPort: 'Shenzhen',
      destinationPort: 'Los Angeles',
      carrierCount: 2,
      carriers: ['COSCO', 'Maersk'],
      minTransitDays: 22,
      maxTransitDays: 25,
      minPricePerKg: 3.5,
      maxPricePerKg: 5,
      minPricePerCbm: 850,
      maxPricePerCbm: 950,
      currency: 'USD',
      singleSource: false,
      priceSpreadRate: 0.4286
    }
  ],
  warnings: ['2 条报价已过失效日期但状态仍为 ACTIVE，比价时会自动排除；可执行「标记过期」收口'],
  ...overrides
})

/**
 * GET /logistics/v2/quote/list/{shopId} 的元素（CarrierQuote）。
 * 后端只返回「有效期内且 ACTIVE」的报价——这正是看板与比价之外的逐条明细面。
 */
const quoteListFixture = [
  { id: 301, shopId: 1, carrierName: 'Matson', serviceType: 'SEA', originPort: 'CNSZX', destinationPort: 'USLAX',
    transitDays: 18, pricePerKg: 3.8, pricePerCbm: 880, minCharge: 500, fuelSurchargeRate: 0.15,
    currency: 'USD', effectiveDate: '2026-09-01', expiryDate: '2026-12-31', status: 'ACTIVE' },
  { id: 300, shopId: 1, carrierName: 'Evergreen', serviceType: 'SEA', originPort: 'CNSZX', destinationPort: 'USLAX',
    transitDays: 22, pricePerKg: 3.2, pricePerCbm: 820, minCharge: 450, fuelSurchargeRate: 0.12,
    currency: 'USD', effectiveDate: '2026-09-01', expiryDate: '2026-12-31', status: 'ACTIVE' }
]

const transferBoardFixture = (overrides: Record<string, unknown> = {}) => ({
  shopId: 1,
  total: 5,
  byStatus: { DRAFT: 1, PENDING_APPROVAL: 0, APPROVED: 1, IN_TRANSIT: 2, RECEIVED: 1, CANCELLED: 0 },
  pendingApproval: 1,
  approvedNotShipped: 1,
  inTransit: 2,
  received: 1,
  cancelled: 0,
  totalShippingCost: 400,
  staleInTransit: 1,
  staleThresholdDays: 7,
  risks: [
    {
      id: 3,
      transferNo: 'TRF-3',
      fromWarehouseId: 1,
      toWarehouseId: 2,
      asin: 'B0STALE',
      sku: 'SKU-STALE',
      quantity: 30,
      carrier: 'SF',
      trackingNo: 'SF999',
      shippingCost: 120,
      status: 'IN_TRANSIT',
      daysSinceCreated: 12,
      updateTime: '2026-09-03T10:00:00',
      type: 'STALE_IN_TRANSIT',
      severity: 'HIGH',
      message: '已发出 12 天仍未确认到货（阈值 7 天）',
      actionHint: '核对调拨物流单号的在途位置，确认是正常在途还是已丢件'
    },
    {
      id: 5,
      transferNo: 'TRF-5',
      fromWarehouseId: 1,
      toWarehouseId: 2,
      asin: 'B0NOTRK',
      sku: 'SKU-NOTRK',
      quantity: 10,
      carrier: 'SF',
      trackingNo: null,
      shippingCost: 80,
      status: 'IN_TRANSIT',
      daysSinceCreated: 2,
      updateTime: '2026-09-13T10:00:00',
      type: 'MISSING_TRACKING_NO',
      severity: 'MEDIUM',
      message: '已确认发出但未登记物流单号，无法追踪',
      actionHint: '补录承运商与物流单号，后续才能自动跟单'
    }
  ],
  warnings: ['1 张调拨单已发出超过 7 天仍未确认到货'],
  ...overrides
})

const transferListFixture = [
  {
    id: 1,
    transferNo: 'TRF-1',
    fromWarehouseId: 1,
    toWarehouseId: 2,
    asin: 'B0DRAFT',
    sku: 'SKU-DRAFT',
    quantity: 10,
    carrier: null,
    trackingNo: null,
    shippingCost: 50,
    status: 'DRAFT',
    createTime: '2026-09-14T09:00:00'
  },
  {
    id: 3,
    transferNo: 'TRF-3',
    fromWarehouseId: 1,
    toWarehouseId: 2,
    asin: 'B0STALE',
    sku: 'SKU-STALE',
    quantity: 30,
    carrier: 'SF',
    trackingNo: 'SF999',
    shippingCost: 120,
    status: 'IN_TRANSIT',
    createTime: '2026-09-03T10:00:00'
  },
  {
    id: 6,
    transferNo: 'TRF-6',
    fromWarehouseId: 1,
    toWarehouseId: 2,
    asin: 'B0DONE',
    sku: 'SKU-DONE',
    quantity: 20,
    carrier: 'SF',
    trackingNo: 'SF888',
    shippingCost: 100,
    status: 'RECEIVED',
    createTime: '2026-08-01T10:00:00'
  }
]

const freightBoardFixture = (overrides: Record<string, unknown> = {}) => ({
  shopId: 1,
  totalAllocationRows: 4,
  totalQuantity: 150,
  coveredShipments: 2,
  uncoveredShipments: 3,
  coverageRate: 0.4,
  totalFreight: 1500,
  totalDuty: 300,
  totalInsurance: 100,
  totalOther: 100,
  totalCost: 2000,
  avgUnitCost: 13.33,
  methodMix: { WEIGHT: 4 },
  uncoveredList: [
    {
      shipmentId: 102,
      shipmentNo: 'SHP-102',
      carrier: 'COSCO',
      status: 'IN_TRANSIT',
      eta: '2026-10-01',
      freightCost: 8000,
      hasDeclaredFreight: true
    },
    {
      shipmentId: 103,
      shipmentNo: 'SHP-103',
      carrier: 'DHL',
      status: 'CREATED',
      eta: null,
      freightCost: null,
      hasDeclaredFreight: false
    }
  ],
  topUnitCostItems: [
    {
      shipmentId: 101,
      shipmentNo: 'SHP-101',
      asin: 'B0COST',
      sku: 'SKU-COST',
      quantity: 10,
      totalCost: 900,
      unitCost: 90,
      allocationMethod: 'VOLUME'
    }
  ],
  warnings: ['1 个货件已登记运费但未做头程分摊，其运费尚未计入商品成本'],
  ...overrides
})

const receiptBoardFixture = (overrides: Record<string, unknown> = {}) => ({
  shopId: 1,
  total: 5,
  pending: 2,
  investigating: 0,
  resolved: 3,
  matched: 1,
  totalExpected: 480,
  totalReceived: 476,
  totalDifference: -4,
  shortageRows: 3,
  overreceivedRows: 1,
  shortageUnits: 12,
  overreceivedUnits: 8,
  openShortageUnits: 10,
  discrepancyRate: 0.0417,
  topShortageAsins: [
    {
      asin: 'B0SHORT',
      sku: 'SKU-SHORT',
      shipmentCount: 2,
      expected: 300,
      received: 285,
      shortageUnits: 15,
      overreceivedUnits: 0,
      netDifference: -15
    }
  ],
  pendingItems: [
    {
      id: 401,
      shipmentId: 401,
      shipmentNo: 'SHP-401',
      asin: 'B0SHORT',
      sku: 'SKU-SHORT',
      expectedQuantity: 100,
      receivedQuantity: 90,
      difference: -10,
      discrepancyType: 'UNDERRECEIVED',
      status: 'PENDING',
      createTime: '2026-09-10T10:00:00'
    }
  ],
  warnings: ['尚有 10 件少收未结案，可直接发起索赔或安排补发'],
  ...overrides
})

describe('LogisticsDashboard 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('token', 'test-token')
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()

    mockedOverview.mockResolvedValue({ code: 200, message: 'ok', data: overviewFixture() } as never)
    mockedTrend.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { date: '2026-08-17', created: 2, delivered: 1 },
        { date: '2026-08-18', created: 4, delivered: 3 }
      ]
    } as never)
    mockedCarriers.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        {
          carrier: 'COSCO',
          total: 8,
          active: 6,
          delivered: 2,
          delayed: 2,
          exception: 0,
          avgTransitDays: 30.5,
          delayedRate: 0.25,
          exceptionRate: 0
        }
      ]
    } as never)
    mockedAlerts.mockResolvedValue({ code: 200, message: 'ok', data: alertsFixture } as never)
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedTracking.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedTransfers.mockResolvedValue({ code: 200, message: 'ok', data: transferListFixture, _page: pageMeta(transferListFixture) } as never)
  })

  it('未登录时展示登录提示，且不发起任何请求', async () => {
    localStorage.removeItem('token')
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('请先登录后查看物流数据')
    expect(mockedOverview).not.toHaveBeenCalled()
    expect(mockedAlerts).not.toHaveBeenCalled()
  })

  it('已登录时按后端数据渲染概览 KPI 与时效指标', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    // 在跟踪 / 延误 / 7 天内到港
    expect(text).toContain('在跟踪货件')
    expect(text).toContain('已判定延误')
    expect(text).toContain('28.44 天')
    expect(text).toContain('平均头程时效')
    // ETA 已过未标延误、取数过期、缺运单号三项「不作为就会被忽略」的指标
    expect(text).toContain('ETA 已过未标延误')
    expect(text).toContain('取数过期')
    expect(text).toContain('缺运单号')
    expect(mockedOverview).toHaveBeenCalledWith('1')
  })

  it('自动取数可用时展示启用状态与最近取数时间', async () => {
    const wrapper = mountView()
    await flushPromises()

    const bar = wrapper.find('.sync-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.classes()).toContain('on')
    expect(bar.text()).toContain('自动同步已启用')
    expect(bar.text()).toContain('2026-08-18 10:30:00')
  })

  // ------------------------------------------------------------
  // 承运商表现表（回归：拆 LogisticsCarrierPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试 6/6 全 MISSED——承运商 tab 此前零测试。**
   *
   * 它是独立的一个 tab，从没有任何断言碰过。下面六条逐一对上那六个变异。
   */

  /**
   * 延误与异常只在**非零**时标红。全列都灰等于没标，而这张表要回答的正是
   * 「哪几家有问题」——用户扫一眼红色就能定位，不用逐行读数字。
   */
  it('承运商表的延误与异常只在非零时标红，零值不标', async () => {
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    expect(tab, '未找到「承运商」tab').toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()

    const row = wrapper.findAll('tbody tr').find((r) => r.text().includes('COSCO'))
    expect(row, '未找到 COSCO 行').toBeTruthy()
    const cells = row!.findAll('td')
    expect(cells.length, '承运商表应有 9 列').toBe(9)

    // 夹具：COSCO delayed=2 / exception=0 → 延误（第 5 列）标红，异常（第 6 列）不标。
    // 按下标取而不是「找出带 cell-alert 的那些」——后者在「全都标红」时也会通过。
    expect(cells[4].classes()).toContain('cell-alert')
    expect(cells[5].classes()).not.toContain('cell-alert')
  })

  /**
   * 平均时效样本不足时显示「暂无样本」而不是 0。示 0 天会让运营以为这家
   * 承运商快得不合常理，**从而在选商时优先选它**——那是用错误数据做决策。
   */
  it('平均时效无样本时显示「暂无样本」，不得显示 0 天', async () => {
    mockedCarriers.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ carrier: 'NOSAMPLE', total: 1, active: 1, delivered: 0, delayed: 0,
               exception: 0, avgTransitDays: null, delayedRate: null, exceptionRate: null }]
    } as never)
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    await tab!.trigger('click')
    await flushPromises()

    const row = wrapper.findAll('tbody tr').find((r) => r.text().includes('NOSAMPLE'))
    expect(row!.text()).toContain('暂无样本')
    expect(row!.text()).not.toContain('0 天')
  })

  /**
   * 比率无样本时显示占位而不是 0%。0% 会被读成「零延误」，那是结论性的错——
   * 与平均时效同一个道理：没有基数就不给数字。
   */
  it('延误率与异常率无样本时显示占位，不得显示 0%', async () => {
    mockedCarriers.mockResolvedValue({
      code: 200, message: 'ok',
      // 两行：一行全零（该全灰），一行延误非零（该标红）。
      // 夹具默认只有 COSCO 一行且 delayed=2——那样 `> 0` 恒真，
      // 把它改成 `true` 是等价变异，任何断言都抓不到。
      data: [
        { carrier: 'ALLZERO', total: 4, active: 4, delivered: 0, delayed: 0,
          exception: 0, avgTransitDays: 5, delayedRate: null, exceptionRate: null },
        { carrier: 'HASDELAY', total: 4, active: 2, delivered: 0, delayed: 2,
          exception: 0, avgTransitDays: 5, delayedRate: 0.5, exceptionRate: 0 }
      ]
    } as never)
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    await tab!.trigger('click')
    await flushPromises()

    const zero = wrapper.findAll('tbody tr').find((r) => r.text().includes('ALLZERO'))!
    const zCells = zero.findAll('td')
    expect(zCells[7].text()).toBe('—')
    expect(zCells[8].text()).toBe('—')
    expect(zero.text()).not.toContain('0%')

    // 全零行两列都不得标红：这是 `> 0` 这个条件唯一的可观测之处
    expect(zCells[4].classes()).not.toContain('cell-alert')
    expect(zCells[5].classes()).not.toContain('cell-alert')

    // 有延误的那行要标红（否则上面那条「不标红」是因为压根没渲染数据）
    const delayed = wrapper.findAll('tbody tr').find((r) => r.text().includes('HASDELAY'))!
    expect(delayed.findAll('td')[4].classes()).toContain('cell-alert')
  })

  /** 有样本时比率要真按百分比显示（25% 而不是 0.25）。 */
  it('有样本时延误率按百分比显示', async () => {
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    await tab!.trigger('click')
    await flushPromises()

    const row = wrapper.findAll('tbody tr').find((r) => r.text().includes('COSCO'))!
    const cells = row.findAll('td')
    // 夹具 delayedRate 0.25 → 25.0%
    expect(cells[7].text()).toBe('25.0%')
  })

  /** 空态文案。删掉它会让用户看到一张没有行的表，而不是「暂无数据」。 */
  it('没有承运商数据时显示空态', async () => {
    mockedCarriers.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    await tab!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂无数据')
  })

  /**
   * 表尾说明。它解释的是「平均时效」的算法口径与「为什么不是 0」——
   * 删掉之后上面那个「暂无样本」就成了没有解释的怪数字。
   */
  it('表尾说明口径：平均时效以确认送达为终点，样本不足不显示 0', async () => {
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('承运商'))
    await tab!.trigger('click')
    await flushPromises()

    const note = wrapper.find('.foot-note')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('承运商确认送达')
    expect(note.text()).toContain('暂无样本')
  })
  it('自动取数不可用时引导走数据导入，而不是让人反复刷新', async () => {
    mockedOverview.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: overviewFixture({ autoSyncAvailable: false, lastTrackTime: null })
    } as never)

    const wrapper = mountView()
    await flushPromises()

    const bar = wrapper.find('.sync-bar')
    expect(bar.classes()).toContain('off')
    expect(bar.text()).toContain('未启用')
    expect(bar.text()).toContain('数据导入')
  })

  it('待处理清单展示级别、超期天数与建议动作', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 切到「待处理」标签
    const alertTab = wrapper.findAll('.tab').find((t) => t.text().includes('待处理'))
    expect(alertTab).toBeTruthy()
    await alertTab!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('SHP-101')
    expect(text).toContain('17 天')
    expect(text).toContain('延误')
    expect(text).toContain('建议：联系承运商核实实际位置')
    expect(text).toContain('缺运单号')
    // 标签页徽标展示待处理数量
    expect(alertTab!.text()).toContain('2')
  })

  // ------------------------------------------------------------
  // 待处理面板（回归：拆 LogisticsAlertPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试 6 条抓到 4 处空洞。** 上面那条既有测试只断言文案，
   * 没碰配色、没碰三个按钮的行为、也没碰 v-else-if 在链里的位置（删掉它的表现是
   * 编译失败/测试文件加载失败，那是**构建期**错误而不是行为错误，所以另行验证）。
   */

  /**
   * 级别配色。HIGH 必须红、MEDIUM 必须黄：用户扫「待处理」表时先看级别列，
   * 决定先处理哪一张。抹成同色之后这张表的排序意义就没了——
   * 而文字在两种级别下都只有「高」「中」两个字，`toContain` 抓不到。
   *
   * 顺带说明：调拨风险清单也有 severityTone，但那是**另一个文件里的另一份实现**
   * （`LogisticsTransferRiskPanel.vue` 自己定义），服务的是风险行不是告警行。
   * 两边碰巧同值，但没有合成一个函数的真实用例，所以不提前抽象。
   */
  it('告警级别配色：高危红、中危黄、低危灰，不得抹成同色', async () => {
    // 夹具默认只有 HIGH 与 LOW 两档，中危那一档（tone-warn）此前没人覆盖——
    // 而 severityTone 是三级分支，少测一档等于没测那半个条件表达式。
    mockedAlerts.mockResolvedValue({
      code: 200, message: 'ok',
      data: [
        { ...alertsFixture[0], severity: 'HIGH' },
        { ...alertsFixture[1], shipmentId: 103, shipmentNo: 'SHP-103', severity: 'MEDIUM' },
        { ...alertsFixture[1], shipmentId: 104, shipmentNo: 'SHP-104', severity: 'LOW' }
      ]
    } as never)

    const wrapper = mountView()
    await flushPromises()
    // 本 describe 块没有 openTab 辅助函数（它在另一个 describe 里定义），
    // 与上面几条既有测试一致地内联切 tab
    const alertsTab = wrapper.findAll('.tab').find((x) => x.text().includes('待处理'))
    await alertsTab!.trigger('click')
    await flushPromises()

    const tagOf = (no: string) =>
      wrapper.findAll('tbody tr').find((r) => r.text().includes(no))!.find('.status-tag')

    const high = tagOf('SHP-101')
    const medium = tagOf('SHP-103')
    const low = tagOf('SHP-104')

    expect(high.classes()).toContain('tone-danger')
    expect(medium.classes()).toContain('tone-warn')
    expect(low.classes()).toContain('tone-muted')
    // 三档必须互不相同，抹成同色就该红
    expect(new Set([
      high.classes().join(' '),
      medium.classes().join(' '),
      low.classes().join(' ')
    ]).size).toBe(3)
  })

  /**
   * 三个按钮必须真的把货件号交给页壳。改成空函数后按钮还在、字还在，
   * 点下去什么都不会发生——而**没有任何既有测试点过这三个按钮**
   * （「待处理清单展示级别、超期天数与建议动作」只断言了静态文案）。
   */
  it('待处理的同步/轨迹/关单按钮必须把货件号交给页壳', async () => {
    const wrapper = mountView()
    await flushPromises()
    // 本 describe 块没有 openTab 辅助函数（它在另一个 describe 里定义），
    // 与上面几条既有测试一致地内联切 tab
    const alertsTab = wrapper.findAll('.tab').find((x) => x.text().includes('待处理'))
    await alertsTab!.trigger('click')
    await flushPromises()

    mockedSync.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    const row = wrapper.findAll('tbody tr').find((r) => r.text().includes('SHP-101'))!

    await row.findAll('button').find((b) => b.text() === '同步')!.trigger('click')
    await flushPromises()
    expect(mockedSync).toHaveBeenCalledWith(101)

    await row.findAll('button').find((b) => b.text() === '轨迹')!.trigger('click')
    await flushPromises()
    // 开浮层：这一按要让轨迹请求真的发出去，而不是只翻一个标记
    expect(mockedTracking).toHaveBeenCalledWith(101, expect.anything(), undefined)

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockedClose.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    await row.findAll('button').find((b) => b.text() === '关单')!.trigger('click')
    await flushPromises()
    expect(mockedClose).toHaveBeenCalledWith(101, '1')
  })

  /**
   * 未知告警类型要原样显示后端给的码。后端加了新类型而前端表没跟上时，
   * 显示成「其他」会让运营不知道这个提醒说的是什么；显示原始码至少能查到。
   */
  it('未知告警类型原样透出，不归成「其他」', async () => {
    mockedAlerts.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ ...alertsFixture[0], type: 'CUSTOMS_HOLD' }]
    } as never)
    const wrapper = mountView()
    await flushPromises()
    // 本 describe 块没有 openTab 辅助函数（它在另一个 describe 里定义），
    // 与上面几条既有测试一致地内联切 tab
    const alertsTab = wrapper.findAll('.tab').find((x) => x.text().includes('待处理'))
    await alertsTab!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('CUSTOMS_HOLD')
  })

  // ------------------------------------------------------------
  // 概览：状态分布 / 取数来源 / 折线图几何（回归：拆 LogisticsOverviewPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试 7/7 全 MISSED——概览此前只有 KPI 与折线的冒烟测试。**
   *
   * 概览是打开页面就看到的第一屏，却没有任何断言覆盖：状态中文表、状态配色、
   * 分布百分比、分布排序、折线图几何、X 轴抽稀、取数来源标签。
   * 下面七条逐一对上那七个变异，每条注明它抓的是哪种改坏。
   */

  /**
   * 状态分布用的中文表与配色，和待处理 tab（告警表）、在途货件 tab（货件表）
   * **共用同一份**（logisticsStatus.ts）。抽共享模块正是为了避免出现
   * 「同一张单在两处显示成两个状态」。这里钉住文案本身。
   */
  it('状态中文名表必须与拆分前逐字一致', () => {
    expect(STATUS_LABELS).toEqual({
      CREATED: '已创建',
      IN_TRANSIT: '运输中',
      CUSTOMS: '清关中',
      DELIVERED: '已送达',
      RECEIVED: 'FBA已入库',
      CLOSED: '已关闭',
      DELAYED: '延误',
      EXCEPTION: '异常',
      DEPARTED: '已起运',
      CUSTOMS_CLEARANCE: '清关中',
      ARRIVED: '已到港',
      OUT_FOR_DELIVERY: '派送中'
    })
    // 未知状态原样显示，而不是被映射成某个具体状态（那会给错信息）
    expect(statusLabel('SOME_NEW_STATE')).toBe('SOME_NEW_STATE')
    expect(statusLabel(undefined)).toBe('—')
  })

  /**
   * 状态配色是用户扫表时最先看到的东西：延误/异常必须红、正常推进蓝/绿、
   * 兜底灰。全部抹成同一个色之后，「哪几张单最要紧」这件事就看不见了。
   */
  it('状态配色：延误与异常为红，在途为蓝，未知为灰', () => {
    expect(statusTone('DELAYED')).toBe('tone-danger')
    expect(statusTone('EXCEPTION')).toBe('tone-danger')
    expect(statusTone('IN_TRANSIT')).toBe('tone-info')
    expect(statusTone('DELIVERED')).toBe('tone-success')
    expect(statusTone('SOMETHING_UNKNOWN')).toBe('tone-muted')
  })

  /**
   * 状态分布的百分比分母必须是**总货件数**。写成 0 或写成状态数之和时，
   * 条形长度就不反映真实占比——「运输中占 42%」变成「占 100%」，
   * 用户会误判船队结构。
   */
  it('状态分布条形宽度按占总货件数的比例计算', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 夹具：totalShipments 12，IN_TRANSIT 5 → 约 41.67%
    const inTransit = wrapper.findAll('.dist-item').find((d) => d.text().includes('运输中'))
    expect(inTransit, '未找到「运输中」分布条').toBeTruthy()
    const fill = inTransit!.find('.dist-fill')
    expect(fill.exists()).toBe(true)
    const width = fill.attributes('style') || ''
    // 5/12 → 41.666…%（模板直接拼百分数，不做四舍五入）
    expect(width).toContain('41.66666666666667%')

    // 计数本身要对：12 个货件里 5 个在途
    expect(inTransit!.find('.dist-value').text()).toBe('5')
  })

  /**
   * 分布条按「流程顺序」排，不按数量也不按字母序。运营看状态分布是顺着
   * 货态流转看的，顺序打乱后要从右往左读。夹具里 IN_TRANSIT 在 CUSTOMS 之前。
   */
  it('状态分布按货态转顺序列，不按数量排序', async () => {
    const wrapper = mountView()
    await flushPromises()

    const labels = wrapper.findAll('.dist-item .status-tag').map((s) => s.text())
    expect(labels.length).toBeGreaterThan(1)
    expect(labels.indexOf('运输中')).toBeLessThan(labels.indexOf('清关中'))
    // 计数为 0 的状态不出现（夹具里 RECEIVED / CLOSED / EXCEPTION 都是 0）
    expect(labels).not.toContain('FBA已入库')
    expect(labels).not.toContain('已关闭')
  })

  /**
   * 折线图的 Y 轴上界必须由数据最大值决定。`maxV = 0` 会让所有点塌到坐标轴上，
   * 而**画面仍然渲染得出来**——一条平线，看不出任何问题，只会被当成「没有波动」。
   */
  it('折线图 Y 轴由上界由数据最大值决定，不得塌成平线', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 夹具趋势里 created 最大 4，网格线应标到 4（以及 0、2）
    const gridLabels = wrapper.findAll('text').map((n) => n.text())
    expect(gridLabels.some((t) => t === '4')).toBe(true)

    // 两条折线的 points 不能全部落在同一条水平线上（塌成平线的直接特征）
    const polys = wrapper.findAll('polyline')
    expect(polys.length).toBe(2)
    const ys = (polys[0].attributes('points') || '').split(' ').map((p) => Number(p.split(',')[1]))
    expect(new Set(ys).size).toBeGreaterThan(1)
  })

  /**
   * 30 天趋势若每条都打一个日期标签会重叠成一团黑。抽稀后仍要能看到
   * 「起点日期」和「终点日期」这两个锚点，否则用户无法判断时间范围。
   */
  it('X 轴日期标签按间隔抽稀，且保留首尾可读', async () => {
    const wrapper = mountView()
    await flushPromises()

    const svg = wrapper.find('svg.chart')
    expect(svg.exists()).toBe(true)
    // 夹具给出 2 个点位，抽稀后标签数 ≤ 点，且不能一个都不显示
    const pointCount = 2
    // 标签文本是 date.slice(5)，即 MM-DD（年月挤在轴外）
    const labels = svg.findAll('text.axis-text').filter((n) => /^\d{2}-\d{2}$/.test(n.text()))
    expect(labels.length).toBeGreaterThan(0)
    expect(labels.length).toBeLessThanOrEqual(pointCount)
    // 首末日期要能在标签里找到（夹具 2026-08-17 / 2026-08-18）
    expect(labels.some((l) => l.text() === '08-17')).toBe(true)
  })

  /**
   * 取数来源标签（外部导入 / 第三方API / 自动）是三个**来源口径**，
   * 改成单字会让运营分不清这批轨迹是导进来的还是接口拉的——
   * 出问题时该找谁查全靠这三行字。
   */
  it('取数来源标签区分三种口径，且只显示有数据的来源', async () => {
    const wrapper = mountView()
    await flushPromises()

    const chips = wrapper.findAll('.source-chip')
    const texts = chips.map((c) => c.text())
    // 夹具 dataSourceCounts: IMPORT 4 / API 6 / AUTO 2
    expect(texts.some((x) => x.includes('外部导入'))).toBe(true)
    expect(texts.some((x) => x.includes('第三方API'))).toBe(true)
    expect(texts.some((x) => x.includes('自动'))).toBe(true)
    expect(chips.length).toBe(3)

    // 没有数据的来源不渲染（否则满屏 0）
    mockedOverview.mockResolvedValue({
      code: 200, message: 'ok',
      data: overviewFixture({ dataSourceCounts: { API: 3 } })
    } as never)
    const wrapper2 = mountView()
    await flushPromises()
    const texts2 = wrapper2.findAll('.source-chip').map((c) => c.text())
    expect(texts2.length).toBe(1)
    expect(texts2[0]).toContain('第三方API')
  })
  it('趋势数据存在时渲染折线图', async () => {
    const wrapper = mountView()
    await flushPromises()

    const svg = wrapper.find('svg.chart')
    expect(svg.exists()).toBe(true)
    expect(wrapper.findAll('polyline').length).toBe(2)
    expect(wrapper.text()).toContain('建单 6')
    expect(wrapper.text()).toContain('送达 4')
  })

  it('导入内容 JSON 语法错误时提示具体原因，且不调用接口', async () => {
    const wrapper = mountView()
    await flushPromises()

    const importTab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await importTab!.trigger('click')
    await flushPromises()

    // 语法错误（键未加引号）：应暴露解析器给出的位置，便于定位文件里的问题行
    await wrapper.find('textarea').setValue('{ not-an-array }')
    const importBtn = wrapper.findAll('button').find((b) => b.text().includes('导入货件主单'))
    expect(importBtn).toBeTruthy()
    await importBtn!.trigger('click')
    await flushPromises()

    expect(mockedImportShipments).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('JSON 解析失败')
  })

  it('导入内容语法合法但不是数组时给出明确提示，且不调用接口', async () => {
    const wrapper = mountView()
    await flushPromises()

    const importTab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await importTab!.trigger('click')
    await flushPromises()

    // 合法 JSON 但结构不对：错误性质与语法错误不同，提示必须能区分
    await wrapper.find('textarea').setValue('{"shipmentNo": "SHP-1"}')
    const importBtn = wrapper.findAll('button').find((b) => b.text().includes('导入货件主单'))
    await importBtn!.trigger('click')
    await flushPromises()

    expect(mockedImportShipments).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('内容必须是 JSON 数组')
  })

  // ------------------------------------------------------------
  // 数据导入面板（回归：拆 LogisticsImportPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试 6 条抓到 4 处空洞。** 导入面板原有的两条测试只覆盖
   * 「JSON 解析失败」与「结构不是数组」两种前置校验，导入**成功之后**
   * 发生的事（刷新看板、toast 级别、结果展示、清除）一条都没测。
   */

  const importReportOk = {
    totalRows: 3,
    succeededRows: 3,
    failedRows: 0,
    createdShipments: 1,
    updatedShipments: 0,
    acceptedEvents: 5,
    skippedEvents: 0,
    statusChangedShipments: 1,
    unmatched: [],
    errors: [],
    errorsTruncated: false
  }

  /** 填一段合法 JSON 并点导入货件主单。 */
  const runImportOk = async (wrapper: ReturnType<typeof mountView>) => {
    await wrapper.find('textarea').setValue('[{"shipmentNo":"SHP-IMP-1"}]')
    await flushPromises()
    const btn = wrapper.findAll('button').find((b) => b.text().includes('导入货件主单'))
    await btn!.trigger('click')
    await flushPromises()
  }

  /**
   * 导入成功后必须重拉看板。不改的话：结果弹出来了、概览数字还是旧的，
   * 用户会以为没导进去而再导一次（重复写入虽幂等，但白等一次）。
   */
  it('导入成功后须重拉看板数据', async () => {
    mockedImportShipments.mockResolvedValue({ code: 200, message: 'ok', data: importReportOk } as never)

    const wrapper = mountView()
    await flushPromises()
    // 在**挂载之后**取计数。取在挂载前的话，首屏那四个接口的调用也会算进增量，
    // 「导入后有没有重拉」就永远为真——这个错误让这条测试第一版完全失效。
    const overviewCallsBefore = mockedOverview.mock.calls.length

    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await tab!.trigger('click')
    await flushPromises()
    await runImportOk(wrapper)

    expect(mockedImportShipments).toHaveBeenCalledWith('1', [{ shipmentNo: 'SHP-IMP-1' }])
    expect(mockedOverview.mock.calls.length).toBeGreaterThan(overviewCallsBefore)
  })

  /**
   * 有失败行时 toast 必须是 error。用 success 会让运营以为整批都进去了，
   * 而实际有几行被拒——那几行货件/轨迹就静默缺失。
   */
  it('部分行失败时提示必须为 error 级，并如实报出成功/失败数', async () => {
    mockedImportShipments.mockResolvedValue({
      code: 200, message: 'ok',
      data: { ...importReportOk, succeededRows: 2, failedRows: 1,
              errors: ['第 3 行：承运商不能为空'], errorsTruncated: false }
    } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await tab!.trigger('click')
    await flushPromises()
    await runImportOk(wrapper)

    const toast = wrapper.find('.toast')
    expect(toast.classes()).toContain('error')
    expect(toast.text()).toContain('成功 2 / 失败 1')
    // 行级错误原文要出现在结果区，否则用户不知道该改哪一行
    expect(wrapper.text()).toContain('第 3 行：承运商不能为空')
  })

  /**
   * 结果面板必须真的渲染出来。整块删掉之后导入照样成功、toast 照样弹，
   * 但用户看不到「写进多少条、跳过多少条」——那正是导入最该反馈的东西。
   */
  it('导入功后展示统计结果，且失败数标红', async () => {
    mockedImportShipments.mockResolvedValue({ code: 200, message: 'ok', data: importReportOk } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await tab!.trigger('click')
    await flushPromises()
    await runImportOk(wrapper)

    const card = wrapper.find('.report-card')
    expect(card.exists(), '导入成功后必须展示结果面板').toBe(true)
    expect(card.text()).toContain('总行数')
    expect(card.text()).toContain('写入轨迹点')
    // 全成功时「失败」那一项不标红（标红条件是可观测的：failedRows > 0）
    const failedItem = card.findAll('.report-item').find((i) => i.text().includes('失败'))!
    expect(failedItem.classes()).not.toContain('cell-alert')
  })

  /** 结果面板的「清除」按钮。不生效的话上一次的结果会一直压在页面上。 */
  it('导入结果的清除按钮必须真的清掉结果面板', async () => {
    mockedImportShipments.mockResolvedValue({ code: 200, message: 'ok', data: importReportOk } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('数据导入'))
    await tab!.trigger('click')
    await flushPromises()
    await runImportOk(wrapper)
    expect(wrapper.find('.report-card').exists()).toBe(true)

    await wrapper.findAll('button').find((b) => b.text() === '清除')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.report-card').exists()).toBe(false)
  })

  it('货件列表：截断必须可见，加载更多必须携带游标并追加数据', async () => {
    const first = {
      id: 11,
      shipmentNo: 'SHP-PAGE-1',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU-PAGE-1',
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: '2026-10-01',
      lastTrackTime: '2026-09-20T08:00:00',
      dataSource: 'API'
    }
    const second = {
      ...first,
      id: 10,
      shipmentNo: 'SHP-PAGE-2',
      masterTrackingNo: 'COSU-PAGE-2'
    }
    mockedShipments
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [first],
        _page: pageMeta([first], { hasMore: true, truncated: true, nextCursor: 'SHIP-CUR' })
      } as never)
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [second],
        _page: pageMeta([second])
      } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    expect(mockedShipments).toHaveBeenCalledWith('1', undefined, 50, undefined)
    expect(wrapper.text()).toContain('仍有更多未加载')
    expect(wrapper.text()).toContain('当前列表不是全量')

    const loadMore = wrapper.findAll('button').find((b) => b.text().includes('加载更多'))
    expect(loadMore).toBeTruthy()
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedShipments).toHaveBeenLastCalledWith('1', undefined, 50, 'SHIP-CUR')
    expect(wrapper.text()).toContain('SHP-PAGE-1')
    expect(wrapper.text()).toContain('SHP-PAGE-2')
    expect(wrapper.text()).not.toContain('仍有更多未加载')
  })

  it('货件列表：_page 缺失时不得冒充全量', async () => {
    const row = {
      id: 21,
      shipmentNo: 'SHP-NO-META',
      carrier: 'DHL',
      masterTrackingNo: null,
      shippingMethod: 'EXPRESS',
      status: 'CREATED',
      eta: null,
      lastTrackTime: null,
      dataSource: 'IMPORT'
    }
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [row] } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('完整性未知')
    expect(wrapper.text()).toContain('列表完整性无法确认')
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载更多'))).toBe(false)
  })

  it('轨迹时间线：截断必须可见，加载更多必须携带游标并追加数据', async () => {
    const shipment = {
      id: 11,
      shipmentNo: 'SHP-TRACK-1',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU-TRACK-1',
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: '2026-10-01',
      lastTrackTime: '2026-09-20T08:00:00',
      dataSource: 'API'
    }
    const firstEvent = {
      id: 1,
      shipmentId: 11,
      eventStatus: 'IN_TRANSIT',
      location: 'Ningbo',
      description: '已开船',
      eventTime: '2026-09-18T08:00:00'
    }
    const secondEvent = {
      id: 2,
      shipmentId: 11,
      eventStatus: 'ARRIVED',
      location: 'Los Angeles',
      description: '已到港',
      eventTime: '2026-09-25T08:00:00'
    }
    mockedShipments.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [shipment],
      _page: pageMeta([shipment])
    } as never)
    mockedTracking
      .mockResolvedValueOnce({
        code: 200,
        message: '操作成功（结果已截断，请携带 nextCursor 继续翻页）',
        data: [firstEvent],
        _page: pageMeta([firstEvent], { size: 200, hasMore: true, truncated: true, nextCursor: 'TRK-CUR' })
      } as never)
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [secondEvent],
        _page: pageMeta([secondEvent], { size: 200 })
      } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()
    const trackingBtn = wrapper.findAll('button').find((b) => b.text() === '轨迹')
    await trackingBtn!.trigger('click')
    await flushPromises()

    expect(mockedTracking).toHaveBeenCalledWith(11, 200, undefined)
    expect(wrapper.text()).toContain('已开船')
    expect(wrapper.text()).toContain('仍有更多未加载')
    expect(wrapper.text()).toContain('当前轨迹不是全量')

    const loadMore = wrapper.findAll('button').find((b) => b.text().includes('加载更多'))
    expect(loadMore).toBeTruthy()
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedTracking).toHaveBeenLastCalledWith(11, 200, 'TRK-CUR')
    expect(wrapper.text()).toContain('已开船')
    expect(wrapper.text()).toContain('已到港')
    expect(wrapper.text()).not.toContain('仍有更多未加载')
  })

  it('轨迹时间线：_page 缺失时不得冒充全量', async () => {
    const shipment = {
      id: 21,
      shipmentNo: 'SHP-TRACK-NO-META',
      carrier: 'DHL',
      masterTrackingNo: null,
      shippingMethod: 'EXPRESS',
      status: 'CREATED',
      eta: null,
      lastTrackTime: null,
      dataSource: 'IMPORT'
    }
    mockedShipments.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [shipment],
      _page: pageMeta([shipment])
    } as never)
    mockedTracking.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [{ id: 9, shipmentId: 21, eventStatus: 'CREATED', description: '仅有一条轨迹' }]
    } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()
    const trackingBtn = wrapper.findAll('button').find((b) => b.text() === '轨迹')
    await trackingBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('完整性未知')
    expect(wrapper.text()).toContain('轨迹完整性无法确认')
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载更多'))).toBe(false)
  })
  it('货件筛选变化时重置游标，不得把旧筛选的第二页拼进新筛选', async () => {
    const first = {
      id: 31,
      shipmentNo: 'SHP-ALL',
      carrier: 'COSCO',
      masterTrackingNo: null,
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: null,
      lastTrackTime: null,
      dataSource: 'IMPORT'
    }
    const filtered = { ...first, id: 30, shipmentNo: 'SHP-DELAYED', status: 'DELAYED' }
    mockedShipments
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [first],
        _page: pageMeta([first], { hasMore: true, truncated: true, nextCursor: 'OLD-CUR' })
      } as never)
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [filtered],
        // 第二页**不**给新游标：这样「换筛选后有没有重置」就变成可观测的——
        // 若没重置，残留的 OLD-CUR 会出现在这里的请求参数里。
        _page: pageMeta([filtered])
      } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    await wrapper.find('#lg-status').setValue('DELAYED')
    await flushPromises()

    // 关键：游标必须是 undefined。只断言「带了 DELAYED 筛选」不够——若忘了重置游标，
    // 这里会变成 ('1', 'DELAYED', 50, 'OLD-CUR')，服务端从旧筛选的第二页开始取，
    // 把两批不同筛选的结果拼在一起给用户看。
    expect(mockedShipments).toHaveBeenLastCalledWith('1', 'DELAYED', 50, undefined)
    expect(mockedShipments).not.toHaveBeenCalledWith('1', 'DELAYED', 50, 'OLD-CUR')
    expect(wrapper.text()).toContain('SHP-DELAYED')
    expect(wrapper.text()).not.toContain('SHP-ALL')

    // 换筛选后不得还有「加载更多」：第二页没给游标，说明这一筛选已取完。
    // 若重置失效，残留的 OLD-CUR 会让按钮继续可点，并把旧筛选的第二页拼进来。
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载更多'))).toBe(false)
  })

  // ------------------------------------------------------------
  // 在途货件面板（回归：拆 LogisticsShipmentPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试抓到 5 处空洞。** 拆出货件面板后，原页壳上那些
   * 「游标 / 分页 / 清空 / 按钮」的保障都还在测页面整体，但没有一条是
   * 对着**新面板自己的那段代码**的——变异全部落在面板里，测试全绿。
   *
   * 调拨列表、报价明细拆时都逐个补过，这一刀照同一份清单补齐。
   */

  /**
   * 无店铺时必须清空列表。切走店铺后 `refreshShop()` 返回空串，
   * 不清空则**上一个店铺的货件继续显示在新店铺下**——多租户隔离缺口。
   * UI 上只表现为「数字没变」，没有任何错误文案可断言，所以只能盯接口调用与文案。
   */
  it('切走店铺后货件列表必须清空，不得残留上一个店铺的货件', async () => {
    const row = {
      id: 11,
      shipmentNo: 'SHP-OTHER-SHOP',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU1',
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: null,
      lastTrackTime: null,
      dataSource: 'IMPORT'
    }
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [row], _page: pageMeta([row]) } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('SHP-OTHER-SHOP')

    // 直接调面板的 load()，让「无店铺则清空」那段真正执行。
    //
    // 为什么不能靠切 tab 模拟：shopId 为空时页壳走「请先选择店铺」分支，
    // 整块 tab 区（含货件面板）卸载，清不清空都看不出来，断言恒真。
    // 这也是这条测试必须直接调 load() 的唯一原因。
    localStorage.setItem('current_shop_id', '')
    await vmOf(wrapper).shipmentPanel.load()
    await flushPromises()

    expect(mockedShipments).not.toHaveBeenCalledWith('', undefined, 50, undefined)
    expect(wrapper.text()).not.toContain('SHP-OTHER-SHOP')
    expect(wrapper.text()).toContain('暂无货件')
  })

  /**
   * 缺分页元数据时不得冒充全量。「不是全量」配一个点不动的按钮，
   * 等于告诉用户「你能看完」——货件漏看一张就可能漏一次延误处理。
   * 这条与调拨/报价明细同属 `truncated` / `pageMetaMissing` 互斥约定。
   */
  it('有更多但没有游标时必须说「完整性未知」，且不给加载下一页', async () => {
    mockedShipments.mockResolvedValue({
      code: 200, message: 'ok', data: [],
      _page: pageMeta([], { hasMore: true, truncated: false, nextCursor: null })
    } as never)
    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('完整性未知')
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载更多'))).toBe(false)
  })

  /**
   * 三个按钮必须真的把货件号交出去。改成空函数后按钮和文字都在，
   * 点下去毫无反应——而面板是刚拆出来的，没有任何测试点过它们。
   * （待处理 tab 那三个按钮已补过同样一条，这里是对货件表的对应版本。）
   */
  it('货件行的同步/轨迹/关单按钮必须把货件号交给页壳', async () => {
    const row = {
      id: 11,
      shipmentNo: 'SHP-BTN',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU1',
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: null,
      lastTrackTime: null,
      dataSource: 'IMPORT'
    }
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [row], _page: pageMeta([row]) } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    const rowEl = wrapper.findAll('tbody tr').find((r) => r.text().includes('SHP-BTN'))
    expect(rowEl, '未找到货件行').toBeTruthy()

    mockedSync.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    await rowEl!.findAll('button').find((b) => b.text() === '同步')!.trigger('click')
    await flushPromises()
    expect(mockedSync).toHaveBeenCalledWith(11)

    await rowEl!.findAll('button').find((b) => b.text() === '轨迹')!.trigger('click')
    await flushPromises()
    expect(mockedTracking).toHaveBeenCalledWith(11, expect.anything(), undefined)

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockedClose.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    await rowEl!.findAll('button').find((b) => b.text() === '关单')!.trigger('click')
    await flushPromises()
    expect(mockedClose).toHaveBeenCalledWith(11, '1')
  })

  /**
   * 运输方式中文表。SEA/AIR/EXPRESS/TRUCK 四个值的字数不同，
   * 改一个字在表格里几乎看不出来，但它是运营判断走货方式的直接依据。
   */
  it('运输方式中文名表必须与拆分前逐字一致', () => {
    expect(METHOD_LABELS).toEqual({
      SEA: '海运',
      AIR: '空运',
      EXPRESS: '快递',
      TRUCK: '卡车'
    })
    // 未知方式原样显示，而不是归成「其他」
    expect(methodLabel('RAIL')).toBe('RAIL')
    expect(methodLabel(undefined)).toBe('—')
  })
})

/**
 * 运营子域看板（比价 / 调拨 / 头程成本 / 签收差异）。
 * <p>
 * 测试重点是「看板有没有把该说的说清楚」：有效性判定、无样本 vs 0、
 * 以及在哪个状态下才允许操作——这些都是后端口径在前端的可见面。
 */
describe('LogisticsDashboard 运营子域看板', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('token', 'test-token')
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()

    mockedOverview.mockResolvedValue({ code: 200, message: 'ok', data: overviewFixture() } as never)
    mockedTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedCarriers.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedAlerts.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedTracking.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedQuotes.mockResolvedValue({ code: 200, message: 'ok', data: quoteListFixture, _page: pageMeta(quoteListFixture) } as never)
    mockedTransfers.mockResolvedValue({ code: 200, message: 'ok', data: transferListFixture, _page: pageMeta(transferListFixture) } as never)
  })

  const openTab = async (wrapper: ReturnType<typeof mountView>, label: string) => {
    const t = wrapper.findAll('.tab').find((x) => x.text().includes(label))
    expect(t, `未找到「${label}」标签`).toBeTruthy()
    await t!.trigger('click')
    await flushPromises()
    return t!
  }

  it('子域看板按标签懒加载，未打开时不请求对应接口', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 首屏只拉概览相关的四个接口，子域看板一次都不该被请求
    expect(mockedQuoteBoard).not.toHaveBeenCalled()
    expect(mockedTransferBoard).not.toHaveBeenCalled()
    expect(mockedFreightBoard).not.toHaveBeenCalled()
    expect(mockedReceiptBoard).not.toHaveBeenCalled()

    await openTab(wrapper, '比价')
    expect(mockedQuoteBoard).toHaveBeenCalledWith('1')
    // 只打开了一个标签，其余子域仍不应被请求
    expect(mockedTransferBoard).not.toHaveBeenCalled()
  })

  it('比价看板区分有效报价与「已失效未收口」，并标出单一来源航线', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const text = wrapper.text()
    expect(text).toContain('有效报价')
    // 已失效但状态还是 ACTIVE 的数量必须可见，否则会以为报价都可用
    expect(text).toContain('已失效未收口')
    expect(text).toContain('比价时已自动排除')
    expect(text).toContain('单一来源航线')
    expect(text).toContain('单一来源')
    expect(text).toContain('Ningbo → NYC')
    // 多承运商航线的价差以百分比展示
    expect(text).toContain('42.9%')
    // 数据质量提示原样透出
    expect(text).toContain('2 条报价已过失效日期')
  })

  it('比价看板在缺少重量与体积时禁用计算按钮，避免发出必然失败的请求', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const calcBtn = wrapper.findAll('button').find((b) => b.text().includes('计算比价'))
    expect(calcBtn).toBeTruthy()
    expect(calcBtn!.attributes('disabled')).toBeDefined()

    // 只填航线、不填货量仍然不可算
    const inputs = wrapper.findAll('.cmp-form input')
    await inputs[0].setValue('Shenzhen')
    await inputs[1].setValue('Los Angeles')
    await flushPromises()
    expect(calcBtn!.attributes('disabled')).toBeDefined()

    await inputs[2].setValue('100')
    await flushPromises()
    expect(calcBtn!.attributes('disabled')).toBeUndefined()

    mockedCompare.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        shopId: 1,
        originPort: 'Shenzhen',
        destinationPort: 'Los Angeles',
        weightKg: 100,
        billingBasis: '按重量价与体积价取高，再叠加最低收费与燃油附加费',
        quotes: [
          {
            quoteId: 1,
            carrierName: 'COSCO',
            serviceType: 'SEA',
            transitDays: 25,
            currency: 'USD',
            chargeableBasis: 'WEIGHT',
            costByWeight: 350,
            costByVolume: null,
            minChargeApplied: false,
            fuelSurchargeRate: 15,
            freightCost: 350,
            fuelSurcharge: 52.5,
            totalCost: 402.5
          }
        ],
        byCurrency: { USD: [] },
        recommendedByCurrency: { USD: 'COSCO' },
        recommended: 'COSCO',
        excludedExpiredCount: 0,
        scannedQuoteCount: 1,
        scanLimit: 5000,
        scanTruncated: false,
        comparisonComplete: true,
        warnings: []
      }
    } as never)

    await calcBtn!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('推荐承运商')
    expect(text).toContain('COSCO')
    // 计费口径必须展示，否则看不懂金额为什么和「重量 × 单价」对不上
    expect(text).toContain('按重量')
    expect(text).toContain('402.50')
  })

  it('比价看板在多币种时不给唯一结论，改为提示按币种分别判断', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    mockedCompare.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        shopId: 1,
        billingBasis: '按重量价与体积价取高',
        quotes: [
          {
            quoteId: 1,
            carrierName: 'COSCO',
            currency: 'USD',
            chargeableBasis: 'WEIGHT',
            costByWeight: 350,
            costByVolume: null,
            minChargeApplied: false,
            freightCost: 350,
            fuelSurcharge: 0,
            totalCost: 350
          },
          {
            quoteId: 2,
            carrierName: 'SF',
            currency: 'CNY',
            chargeableBasis: 'WEIGHT',
            costByWeight: 2000,
            costByVolume: null,
            minChargeApplied: false,
            freightCost: 2000,
            fuelSurcharge: 0,
            totalCost: 2000
          }
        ],
        byCurrency: { USD: [], CNY: [] },
        recommendedByCurrency: { USD: 'COSCO', CNY: 'SF' },
        recommended: null,
        excludedExpiredCount: 0,
        scannedQuoteCount: 2,
        scanLimit: 5000,
        scanTruncated: false,
        comparisonComplete: true,
        warnings: ['本航线存在多种币种（USD / CNY），金额不可直接比较，已按币种分别排序']
      }
    } as never)

    const inputs = wrapper.findAll('.cmp-form input')
    await inputs[0].setValue('Shenzhen')
    await inputs[1].setValue('Los Angeles')
    await inputs[2].setValue('100')
    await flushPromises()

    const calcBtn = wrapper.findAll('button').find((b) => b.text().includes('计算比价'))
    await calcBtn!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    // 跨币种给单一「最便宜」是错结论，UI 必须说明这一点
    expect(text).not.toContain('推荐承运商')
    expect(text).toContain('无法给出唯一的「最便宜」结论')
    expect(text).toContain('该币种最低')
  })

  // ------------------------------------------------------------
  // 报价覆盖看板（回归：拆 LogisticsQuoteBoardPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀变异测试 4/4 全 MISSED——覆盖看板此前零测试。**
   *
   * 它是报价 tab 的第一屏，却没有任何断言碰过它：KPI 数字、航线表、区间格式、
   * 单一来源数、tab 角标、「标记过期」这条写操作，全都没人看。
   * 下面四条逐一对上那四个变异，每条都注明它抓的是哪种改坏。
   */

  /**
   * 角标是报价 tab 在**没打开时**唯一的提示（「有 2 条报价该收口了」）。
   * 它是 `quoteBoard` 唯一一个区块外的消费者，也是这一刀唯一需要
   * 跨组件仍然成立的行为——所以单独钉一条。
   */
  it('报价 tab 角标显示已失效未收口条数，且拉过一次后切走 tab 仍在', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 打开前没有角标：报价看板是**懒加载**的，没打开过就没有 staleByDate。
    // 这一点本身要钉住——否则有人把它改成首屏全量拉，进来就打六个接口。
    const before = wrapper.findAll('.tab').find((x) => x.text().includes('比价'))
    expect(before!.text()).toBe('比价')

    await openTab(wrapper, '比价')
    // 角标是 tab 里的 .tab-badge span，tone class 也在它上面而不是 .tab 上
    const openedBadge = quoteTabBadge(wrapper)
    expect(openedBadge.text()).toBe('2')
    expect(openedBadge.classes().join(' ')).toContain('badge-warn')

    // 切走再回来：角标属于 tab 条，不该随面板卸载消失
    await openTab(wrapper, '在途货件')
    await flushPromises()
    const afterBadge = quoteTabBadge(wrapper)
    expect(afterBadge.text()).toBe('2')
    expect(afterBadge.classes().join(' ')).toContain('badge-warn')
  })

  /** 取「比价」tab 内的角标元素。角标不存在时 find 会抛，所以调用方先断言存在。 */
  const quoteTabBadge = (wrapper: ReturnType<typeof mountView>) => {
    const tab = wrapper.findAll('.tab').find((x) => x.text().includes('比价'))
    expect(tab, '未找到「比价」tab').toBeTruthy()
    return tab!.find('.tab-badge')
  }

  /**
   * 覆盖表里每公斤/每立方价是区间。只显示下限会让「这条航线报价 3.5 ~ 5.8」
   * 看起来像「固定 3.5」——**运营据此估运费会低估约 40%**。
   * 两端相同时必须只显示一个值，否则出现「8.5 ~ 8.5」这种噪音。
   */
  it('航线价格区间两端不同时显示区间，两端相同时只显示一个值', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const text = wrapper.text()
    // Shenzhen→LA：3.5 ~ 5，两端不同 → 必须成区间
    expect(text).toContain('3.5 ~ 5')
    // Ningbo→NYC：8.5 ~ 8.5，两端相同 → 不得出现噪音区间
    expect(text).not.toContain('8.5 ~ 8.5')
  })

  /**
   * 单一来源航线数。`.filter(...).length` 若被误写成 `.length`（ routes 总数），
   * 数字会从 1 变 2——**看板上多一条「无议价空间」的航线，运营会去找一家
   * 本来就有两家的供应商谈价**，纯属白跑。这类算法退化没有任何文案会变。
   */
  it('单一来源航线数只数 singleSource 为真的航线', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const mini = wrapper.findAll('.mini-card').find((c) => c.text().includes('单一来源航线'))
    expect(mini, '未找到「单一来源航线」小指标').toBeTruthy()
    // 夹具里两条航线，只有 Ningbo→NYC 是 singleSource: true
    expect(mini!.find('.mini-value').text()).toBe('1')
    // 整块高亮：有单一来源时该卡要 alert（否则用户扫不到重点）
    expect(mini!.classes().join(' ')).toContain('alert')
  })

  /**
   * 「标记过期」是报价 tab 唯一的写操作按 shopId **批量改报价状态、不可逐条回滚**
   * （EXPIRED 之后要逐条改回 ACTIVE）。两个必须钉住的点：
   * 1. 成功后必须重拉看板——不改的话 toast 说成功、数字一个没变，用户会重复点；
   * 2. 服务端拒绝时要把原因原样透出，而不是笼统的「标记失败」。
   */
  it('标记过期：确认后调用接口、重拉看板，服务端拒绝时原样透出原因', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const btn = wrapper.findAll('button').find((b) => b.text().includes('标记过期报价'))
    expect(btn, '未找到「标记过期报价」按钮').toBeTruthy()
    // staleByDate = 2 > 0 时才可用；为 0 时点了也是空操作
    expect(btn!.attributes('disabled')).toBeUndefined()

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockedQuoteBoard.mockClear()   // 只数这一次点击引发的重拉，排除打开 tab 时的那次
    const boardCallsBefore = mockedQuoteBoard.mock.calls.length
    mockedExpire.mockResolvedValue({ code: 200, message: 'ok', data: 2 } as never)
    await btn!.trigger('click')
    await flushPromises()

    expect(mockedExpire).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('已标记 2 条过期报价')
    // 关键：数字已过期，必须重拉。少了这步就是「点了没反应」
    expect(mockedQuoteBoard.mock.calls.length).toBeGreaterThan(boardCallsBefore)

    // 用户取消确认：不得发出任何请求（批量写操作不能误触）
    confirmSpy.mockReturnValue(false)
    mockedExpire.mockClear()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockedExpire).not.toHaveBeenCalled()

    // 服务端拒绝：原因原样透出
    confirmSpy.mockReturnValue(true)
    mockedExpire.mockResolvedValue({ code: 400, message: '该店铺无过期报价可标记', data: null } as never)
    await btn!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('该店铺无过期报价可标记')
  })

  /**
   * 没有可收口的报价时按钮必须禁用。让它可点会让用户点进一个必然空转的确认框，
   * 点完接口再告诉他「没有」——那是把校验推给用户。
   */
  it('没有已失效报价时禁用「标记过期报价」按钮', async () => {
    mockedQuoteBoard.mockResolvedValue({
      code: 200, message: 'ok',
      data: quoteBoardFixture({ staleByDate: 0 })
    } as never)
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    const btn = wrapper.findAll('button').find((b) => b.text().includes('标记过期报价'))
    expect(btn).toBeTruthy()
    expect(btn!.attributes('disabled')).toBeDefined()
  })

  // ------------------------------------------------------------
  // 计费口径文案与登出清理（回归：拆 LogisticsQuoteComparePanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这一刀的变异测试抓到两处空洞，这是第一处。**
   *
   * `chargeableBasis` 是「为什么这个金额和重量 × 单价对不上」的唯一解释：
   * 后端可能按体积价计费、也可能因为缺单价而根本没算。把它显示成别的字，
   * 用户会拿页面上的运费去和承运商对账然后得出错误结论——那是业务损失，
   * 而测试不红，因为没有任何断言钉住这三个词。
   *
   * 逐字比对整张表，不用 toContain：toContain 证明不了「没被改成别的」。
   */
  it('计费口径中文名表必须与拆分前逐字一致', () => {
    expect(BASIS_LABELS).toEqual({
      WEIGHT: '按重量',
      VOLUME: '按体积',
      NO_PRICING_DATA: '无可用单价'
    })
    // 兜底：未知口径原样显示，而不是被映射成某个具体口径（那会给出错的解释）
    expect(basisLabel('WEIGHT_OR_SOMETHING_NEW')).toBe('WEIGHT_OR_SOMETHING_NEW')
    expect(basisLabel(undefined)).toBe('—')
  })

  /**
   * **第二处空洞，也是本刀唯一一处「职责搬家」的正确性验证。**
   *
   * 比价表单状态（含 `cmpResult`）搬进 LogisticsQuoteComparePanel 后，
   * 「登出/切店铺时清空」这条责任也跟着搬——原先在页壳的 `onAuthChanged` 里。
   * 忘记搬的后果很具体：**下一个用户登进来，看到上一个店铺算出来的推荐承运商**。
   * 页面此时显示「请先登录」，那段比价结果却还挂在 DOM 里。
   *
   * 这条就是盯「有没有搬」。它保护的是一类更广的问题：
   * 状态搬进子组件时，同一事件上的清理责任必须一起搬。
   */
  /**
   * **这条测试的两版都失败了，记下来免得下一班再走一遍。**
   *
   * 第一版断言「登出后页面不含推荐承运商」——**永远通过**。整个 tab 区在
   * `<template v-else>` 下，登出后 `hasToken` 变 false，**整块 DOM 卸载**，
   * 结果根本不在页面上：断言一个结构上不可能出现的串，当然不红。
   *
   * 第二版改成「登出再登录后 cmp-result 不存在」，红了也能被变异躲过。
   * 我一度以为找到了真 bug，在面板里加了 `clearCompareState`，变异测试
   * （把它改成 no-op）显示测试**照样绿**——于是加挂载计数器实测，
   * **`mounts: 2`**：`v-else` 子树翻转时 Vue 重建了面板，状态自然丢弃，
   * 那段清理代码是死代码。已删（CLAUDE.md 规则 10）。
   *
   * 所以这条测试现在钉的是**行为契约**而非实现：无论靠卸载重建还是主动清理，
   * 「登出再登录后，上一个店铺的比价推荐不得出现在页面上」。覆盖的是
   * 「以后有人给面板加 keep-alive 或把 active 改成常驻挂载」这种情况——
   * 那时 remount 不再兜底，这条测试就会抓到残留。
   */
  it('登出再登录后比价结果必须已清掉，不能残留上一个店铺的推荐', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    mockedCompare.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        shopId: 1,
        billingBasis: '按重量价与体积价取高',
        quotes: [
          {
            quoteId: 1,
            carrierName: 'COSCO',
            currency: 'USD',
            chargeableBasis: 'WEIGHT',
            costByWeight: 350,
            costByVolume: null,
            minChargeApplied: false,
            freightCost: 350,
            fuelSurcharge: 0,
            totalCost: 350
          }
        ],
        byCurrency: { USD: [] },
        recommendedByCurrency: { USD: 'COSCO' },
        recommended: 'COSCO',
        excludedExpiredCount: 0,
        scannedQuoteCount: 1,
        scanLimit: 5000,
        scanTruncated: false,
        comparisonComplete: true,
        warnings: []
      }
    } as never)

    const inputs = wrapper.findAll('.cmp-form input')
    await inputs[0].setValue('Shenzhen')
    await inputs[1].setValue('Los Angeles')
    await inputs[2].setValue('100')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('计算比价'))!.trigger('click')
    await flushPromises()
    console.log('DBG1 cmp-result exists:', wrapper.find('.cmp-result').exists())
    expect(wrapper.text()).toContain('推荐承运商')

    // 登出：整块卸载（所以这一步看不出问题），再登录让面板回到 DOM
    localStorage.removeItem('token')
    window.dispatchEvent(new Event('amz:auth-changed'))
    await flushPromises()
    localStorage.setItem('token', 'test-token')
    window.dispatchEvent(new Event('amz:auth-changed'))
    await flushPromises()

    // 面板回到页面上了，但结果必须已经不在。
    // 断言限定在 .cmp-result 上：整个页面文本里 COSCO 也会出现在「航线报价覆盖」
    // 表的承运商列里，那是看板自己的数据，与比价结果无关——用整页文本断言会误判。
    console.log('DBG2 mounts:', (window as any).__cmpMounts)
    console.log('DBG2 cmp-result exists:', wrapper.find('.cmp-result').exists(), 'form:', wrapper.find('.cmp-form').exists(), 'has400:', wrapper.text().includes('推荐承运商'))
    expect(wrapper.find('.cmp-form').exists()).toBe(true)
    expect(wrapper.find('.cmp-result').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('推荐承运商')
  })

  it('比价扫描截断时明确结果不完整，并隐藏所有最低价推荐', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')

    mockedCompare.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        shopId: 1,
        billingBasis: '按重量价与体积价取高',
        quotes: [
          {
            quoteId: 1,
            carrierName: 'COSCO',
            currency: 'USD',
            chargeableBasis: 'WEIGHT',
            costByWeight: 350,
            costByVolume: null,
            minChargeApplied: false,
            freightCost: 350,
            fuelSurcharge: 0,
            totalCost: 350
          }
        ],
        byCurrency: { USD: [] },
        recommendedByCurrency: {},
        recommended: null,
        excludedExpiredCount: 0,
        scannedQuoteCount: 5000,
        scanLimit: 5000,
        scanTruncated: true,
        comparisonComplete: false,
        warnings: ['报价扫描达到上限（5000 条），本次结果不完整，已禁止给出最低价推荐']
      }
    } as never)

    const inputs = wrapper.findAll('.cmp-form input')
    await inputs[0].setValue('Shenzhen')
    await inputs[1].setValue('Los Angeles')
    await inputs[2].setValue('100')
    await flushPromises()

    const calcBtn = wrapper.findAll('button').find((b) => b.text().includes('计算比价'))
    await calcBtn!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('报价扫描达到上限')
    expect(text).toContain('本次结果不完整')
    expect(text).toContain('已禁用最低价推荐')
    expect(text).not.toContain('推荐承运商')
    expect(text).not.toContain('该币种最低')
  })

  it('调拨看板展示卡单风险，且只在允许的状态下提供操作入口', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    const text = wrapper.text()
    expect(text).toContain('在途卡单')
    expect(text).toContain('待审批')
    expect(text).toContain('已批待发出')
    expect(text).toContain('TRF-3')
    expect(text).toContain('在途超期')
    expect(text).toContain('缺物流单号')
    expect(text).toContain('12 天')
    expect(text).toContain('建议：核对调拨物流单号的在途位置')

    // 只在「调拨单」列表里判断操作入口：TRF-3 同时出现在上方风险清单中，
    // 那张表本身没有操作按钮，不限定范围会取到错的行
    const listRows = wrapper.findAll('.chart-card.mt tbody tr')
    expect(listRows.length).toBe(transferListFixture.length)

    // 草稿单提供审批入口，但不提供收货入口（否则就是「没发货就算收货」）
    const draftRow = listRows.find((r) => r.text().includes('TRF-1'))
    expect(draftRow!.text()).toContain('通过')
    expect(draftRow!.text()).toContain('驳回')
    expect(draftRow!.text()).not.toContain('确认收货')

    // 在途单提供收货，不提供审批
    const inTransitRow = listRows.find((r) => r.text().includes('TRF-3'))
    expect(inTransitRow!.text()).toContain('确认收货')
    expect(inTransitRow!.text()).not.toContain('通过')

    // 已收货的单已结束，不再给任何操作
    const doneRow = listRows.find((r) => r.text().includes('TRF-6'))
    expect(doneRow!.text()).toContain('已结束')
    expect(doneRow!.text()).not.toContain('确认收货')
  })

  it('调拨审批：确认后调用接口并刷新看板与列表', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockedApprove.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)

    const appRow = wrapper.findAll('.chart-card.mt tbody tr').find((r) => r.text().includes('TRF-1'))
    const approveBtn = appRow!.findAll('button').find((b) => b.text() === '通过')
    await approveBtn!.trigger('click')
    await flushPromises()

    expect(mockedApprove).toHaveBeenCalledWith(1, true)

    // 服务端拒绝（例如状态已变）时把原因原样透出，而不是笼统的「操作失败」
    mockedApprove.mockResolvedValue({
      code: 400,
      message: '调拨单当前状态为「IN_TRANSIT」，不可审批通过（已发出或已收货的单据不能改判）',
      data: null
    } as never)
    await approveBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已发出或已收货的单据不能改判')
  })

  it('头程成本看板：无样本时显示占位而不是 0，并标出「有费用未摊」的货件', async () => {
    mockedFreightBoard.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: freightBoardFixture({ avgUnitCost: null, coverageRate: null })
    } as never)

    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '头程成本')

    const text = wrapper.text()
    // 0 会被读成「成本极低」，实际含义是「没有可算的数据」
    expect(text).toContain('暂无样本')
    expect(text).toContain('未核算货件')
    expect(text).toContain('SHP-102')
    expect(text).toContain('有费用未摊，直接抬高利润')
    expect(text).toContain('8000.00')
    expect(text).toContain('SHP-101')
    expect(text).toContain('按体积')
  })

  it('签收差异看板：未结案少收单独成指标，并说明净差异会抵消问题', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '签收差异')

    const text = wrapper.text()
    expect(text).toContain('未结案少收 (件)')
    expect(text).toContain('少收合计 (件)')
    expect(text).toContain('多收合计 (件)')
    expect(text).toContain('差异率')
    expect(text).toContain('4.2%')
    expect(text).toContain('B0SHORT')
    expect(text).toContain('SHP-401')
    expect(text).toContain('少收')
    expect(text).toContain('尚有 10 件少收未结案')
  })

  it('签收差异结案：处理结果为空时本地拦下，不发出必然失败的请求', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '签收差异')

    vi.spyOn(window, 'prompt').mockReturnValue('   ')
    const resolveBtn = wrapper.findAll('button').find((b) => b.text() === '结案')
    expect(resolveBtn).toBeTruthy()
    await resolveBtn!.trigger('click')
    await flushPromises()

    expect(mockedResolve).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('结案必须填写处理结果')

    vi.spyOn(window, 'prompt').mockReturnValue('已向亚马逊提交索赔')
    mockedResolve.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    await resolveBtn!.trigger('click')
    await flushPromises()

    expect(mockedResolve).toHaveBeenCalledWith(401, '已向亚马逊提交索赔')
  })

  it('开始核查：把差异转入核查中，并重拉看板', async () => {
    // **这条钉住一个真实的覆盖空洞**：investigateDiscrepancy 一直被 mock，
    // 但此前没有任何测试点过「开始核查」按钮——拆成 LogisticsReceiptPanel 后
    // 我把它的 @click 从 investigate 换成 resolve，37 条测试全绿。
    // 一个从不断言的按钮，等于没有测试。
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '签收差异')

    mockedInvestigate.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {}
    } as never)
    const investigateBtn = wrapper.findAll('button').find((b) => b.text() === '开始核查')
    expect(investigateBtn).toBeTruthy()
    await investigateBtn!.trigger('click')
    await flushPromises()

    expect(mockedInvestigate).toHaveBeenCalledWith(401)
    expect(mockedResolve).not.toHaveBeenCalled()
    // 核查成功后要重拉看板，否则状态栏还停在「待处理」
    expect(mockedReceiptBoard).toHaveBeenCalledTimes(2)
  })

  it('登出后清空子域看板数据，避免残留上一个店铺的数字', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '比价')
    expect(wrapper.text()).toContain('有效报价')

    localStorage.removeItem('token')
    window.dispatchEvent(new Event('amz:auth-changed'))
    await flushPromises()

    expect(wrapper.text()).toContain('请先登录后查看物流数据')
    expect(wrapper.text()).not.toContain('已失效未收口')
  })

  it('调拨列表：截断必须可见，加载更多必须携带游标并追加数据', async () => {
    const first = transferListFixture[1]
    const second = transferListFixture[2]
    mockedTransfers
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [first],
        _page: pageMeta([first], { hasMore: true, truncated: true, nextCursor: 'TRF-CUR' })
      } as never)
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [second],
        _page: pageMeta([second])
      } as never)

    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    expect(mockedTransfers).toHaveBeenCalledWith('1', undefined, 50, undefined)
    expect(wrapper.text()).toContain('仍有更多未加载')

    const loadMore = wrapper.findAll('button').find((b) => b.text().includes('加载更多'))
    expect(loadMore).toBeTruthy()
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedTransfers).toHaveBeenLastCalledWith('1', undefined, 50, 'TRF-CUR')
    expect(wrapper.text()).toContain('TRF-3')
    expect(wrapper.text()).toContain('TRF-6')
    expect(wrapper.text()).not.toContain('仍有更多未加载')
  })

  it('调拨列表：_page 缺失时不得冒充全量', async () => {
    const row = transferListFixture[0]
    mockedTransfers.mockResolvedValue({ code: 200, message: 'ok', data: [row] } as never)

    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    expect(wrapper.text()).toContain('完整性未知')
    expect(wrapper.text()).toContain('列表完整性无法确认')
    expect(wrapper.findAll('button').some((b) => b.text().includes('加载更多'))).toBe(false)
  })

  it('调拨筛选变化时重置游标，不得拼接旧筛选结果', async () => {
    const first = transferListFixture[0]
    const filtered = transferListFixture[1]
    mockedTransfers
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [first],
        _page: pageMeta([first], { hasMore: true, truncated: true, nextCursor: 'OLD-TRF-CUR' })
      } as never)
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [filtered],
        _page: pageMeta([filtered])
      } as never)

    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    await wrapper.find('#trf-status').setValue('IN_TRANSIT')
    await flushPromises()

    expect(mockedTransfers).toHaveBeenLastCalledWith('1', 'IN_TRANSIT', 50, undefined)
    expect(wrapper.text()).toContain('TRF-3')
    expect(wrapper.text()).not.toContain('TRF-1')
  })

  // ------------------------------------------------------------
  // 写操作后的刷新路径（回归：拆 LogisticsTransferPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **这条钉住本班实际犯的错**。把调拨列表拆成 LogisticsTransferPanel 时，
   * 我把 `refreshTransferViews`（审批/发出/收货成功后重拉列表+看板）从记忆里重写了一遍，
   * 结果漏掉了一个分支、调换了一次调用顺序，而且**变异测试证明这两种改动都不会让任何既有测试变红**：
   * 1. 无店铺时不再清空列表 —— 切走店铺后旧店的调拨单还挂在屏上，那是跨租户数据残留；
   * 2. 成功后不重拉列表 —— 看板数字更新了、用户在看的列表还是旧状态，只能靠手动刷新。
   *
   * 断言刻意用 mock 的调用次数而不是文案：这两个缺陷在 UI 上都表现为「数字没变」，
   * 没有专属错误文案，用 toContain 抓不到。
   */
  it('调拨写操作成功后必须重拉列表与看板，且切走店铺要清空旧店数据', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')
    expect(wrapper.text()).toContain('TRF-1')

    // 基准调用数（进 tab 时的那一拉），后面只关心「写操作后有没有再加拉」
    const beforeApprove = mockedTransfers.mock.calls.length
    const boardCallsBeforeApprove = mockedTransferBoard.mock.calls.length

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockedApprove.mockResolvedValue({ code: 200, message: 'ok', data: {} } as never)
    const appRow = wrapper.findAll('.chart-card.mt tbody tr').find((r) => r.text().includes('TRF-1'))
    await appRow!.findAll('button').find((b) => b.text() === '通过')!.trigger('click')
    await flushPromises()

    // 两个都得拉：只拉看板会让用户看的列表停在旧状态，只拉列表会让看板数字过期
    expect(mockedTransfers.mock.calls.length).toBeGreaterThan(beforeApprove)
    expect(mockedTransferBoard.mock.calls.length).toBeGreaterThan(boardCallsBeforeApprove)

    // 店铺被切走（换店铺不清 localStorage 也不会重挂载组件）时，列表必须清空——
    // 否则旧店的调拨单继续显示在新店铺下，是多租户隔离缺口
    localStorage.setItem('current_shop_id', '')
    mockedTransfers.mockClear()
    await wrapper.find('#trf-status').setValue('')
    await flushPromises()

    expect(mockedTransfers).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('TRF-1')
  })

  // ------------------------------------------------------------
  // 调拨状态文案与配色（回归：拆 LogisticsTransferRiskPanel 时的教训）
  // ------------------------------------------------------------

  /**
   * **第二次拆分（风险预警从调拨面板拆出）的变异测试抓到三处测试空洞**，
   * 其中第一处是本班已经犯过四次的同一个错：凭记忆重写中文文案。
   *
   * 拆出 LogisticsTransferRiskPanel 时，「状态」列要用中文名，而同一张表
   * 调拨单列表也要用——我把表抽成共享模块 logisticsTransferStatus.ts，
   * 这是对的（两处不一致会让同一张单在同一个 tab 里显示成两个状态）。
   * 但把 APPROVED 的「已审批待发出」写成「已审批未」时，**没有任何测试变红**：
   * 已有的中文表回归测试只覆盖轨迹浮层那 12 条，不覆盖调拨这 6 条。
   *
   * 所以这里把调拨状态表一并钉死。断言用 JSON 字面量逐字比对，不用 toContain——
   * toContain 只能证明某个串出现过，证明不了「没被改成别的」。
   */
  it('调拨状态中文名表必须与拆分前逐字一致', () => {
    expect(TRANSFER_STATUS_LABELS).toEqual({
      DRAFT: '草稿',
      PENDING_APPROVAL: '待审批',
      APPROVED: '已审批待发出',
      IN_TRANSIT: '运输中',
      RECEIVED: '已收货',
      CANCELLED: '已取消'
    })
  })

  /**
   * 同一张单的「风险行状态」与「列表行状态」必须显示成同一个中文名。
   *
   * 拆成两个组件后，两边各自 import 同一个表；一旦有人为图省事在其中一处
   * 内联一份，两处就会各说各话——用户看到同一张 TRF-3 在上下两处状态不同，
   * 会认为系统有 bug。这条同时钉住「不要内联复制」这个决定本身。
   */
  it('风险清单与调拨单列表对同一状态必须显示同一个中文名', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    // TRF-3 同时出现在风险清单（IN_TRANSIT）与调拨单列表里，两处文案必须一致
    const riskRow = wrapper.findAll('.data-table tbody tr').find((r) => r.text().includes('TRF-3'))
    const listRow = wrapper.findAll('.chart-card.mt tbody tr').find((r) => r.text().includes('TRF-3'))
    expect(riskRow!.text()).toContain('运输中')
    expect(listRow!.text()).toContain('运输中')
  })

  /**
   * 风险级别配色（高=红 / 中=黄 / 低=灰）是用户扫表时最先看到的东西：
   * 「哪几张单最要紧」全凭这一眼。抹平成同一个色（或让 HIGH 落到灰色）
   * 不会让任何文案断言变红，但会把「优先处理」这件事整个毁掉。
   *
   * 所以直接断言 class，而不是只断言文字——文字在三种级别下本来就相同。
   */
  it('风险级别配色：高/中必须落到不同的状态色', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    const highTag = wrapper
      .findAll('.data-table tbody tr')
      .find((r) => r.text().includes('TRF-3'))!
      .find('.status-tag')
    const mediumTag = wrapper
      .findAll('.data-table tbody tr')
      .find((r) => r.text().includes('TRF-5'))!
      .find('.status-tag')

    expect(highTag.classes()).toContain('tone-danger')
    expect(mediumTag.classes()).toContain('tone-warn')
    expect(mediumTag.classes()).not.toContain('tone-danger')
  })

  /**
   * 在途单的状态色。`tone-info` 是「正常在途」的中性提示色；
   * 若被改成 `tone-muted`，运输中的调拨单会和已取消单一个颜色——
   * 用户会分不清「这批在路上」和「这批已经作废」。
   */
  it('在途调拨单状态必须用 info 色，不得与已取消同色', async () => {
    const wrapper = mountView()
    await flushPromises()
    await openTab(wrapper, '调拨')

    const inTransitTag = wrapper
      .findAll('.chart-card.mt tbody tr')
      .find((r) => r.text().includes('TRF-3'))!
      .find('.status-tag')

    expect(inTransitTag.classes()).toContain('tone-info')
    expect(inTransitTag.classes()).not.toContain('tone-muted')
  })
  // ------------------------------------------------------------
  // 轨迹状态中文名（回归：拆 LogisticsTrackingModal 时的教训）
  // ------------------------------------------------------------

  /**
   * **这条钉住本班实际犯的错**：拆 LogisticsTrackingModal 时，我凭印象重写了状态中文表，
   * 把 IN_TRANSIT 的「运输中」写成「在途」、RECEIVED 的「FBA已入库」写成「FBA已签收」，
   * 另外三处同样。**没有任何既有测试断言这些中文串**，所以 vue-tsc / vitest / vite build
   * 全绿，五处用户可见文案却在一次纯重构里静默改变了。
   *
   * 所以这里把整张表钉死。它保护的是一类更广的问题：重构改了文案但机器查不出来。
   * 断言用 JSON 字面量逐字比对，不用 toContain——toContain 只能证明某个串出现过，
   * 证明不了「没被改成别的」。
   */
  it('轨迹状态中文名表必须与拆分前逐字一致', async () => {
    const statuses = [
      ['CREATED', '已创建'],
      ['IN_TRANSIT', '运输中'],
      ['CUSTOMS', '清关中'],
      ['DELIVERED', '已送达'],
      ['RECEIVED', 'FBA已入库'],
      ['CLOSED', '已关闭'],
      ['DELAYED', '延误'],
      ['EXCEPTION', '异常'],
      ['DEPARTED', '已起运'],
      ['CUSTOMS_CLEARANCE', '清关中'],
      ['ARRIVED', '已到港'],
      ['OUT_FOR_DELIVERY', '派送中']
    ]
    const events = statuses.map(([eventStatus, description], idx) => ({
      id: 1000 + idx,
      shipmentId: 11,
      eventStatus,
      description,
      eventTime: '2026-09-01T08:00:00'
    }))

    const shipment = {
      id: 11,
      shipmentNo: 'SHP-LABELS',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU-LABELS',
      shippingMethod: 'SEA',
      status: 'IN_TRANSIT',
      eta: null,
      lastTrackTime: null,
      dataSource: 'API'
    }
    mockedShipments.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [shipment],
      _page: pageMeta([shipment])
    } as never)
    mockedTracking.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: events
    } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()
    const trackingBtn = wrapper.findAll('button').find((b) => b.text() === '轨迹')
    await trackingBtn!.trigger('click')
    await flushPromises()

    const rendered = wrapper.findAll('.tl-status').map((el) => el.text())
    expect(rendered).toEqual(statuses.map(([, label]) => label))
  })

})

describe('LogisticsDashboard 有效报价明细（此前 listQuotes 零调用）', () => {
  /**
   * 比价页此前只有「看板聚合 + 航线覆盖 + 运费比价」，没有逐条报价明细——
   * 运营看不出「某条航线上具体有哪些报价、单价多少、什么时候失效」。后端
   * GET /logistics/v2/quote/list/{shopId} 只返回有效期内且 ACTIVE 的报价（keyset 分页），
   * 前端 listQuotes 却零调用。本组钉住明细表：字段来自后端、截断时披露、
   * 失败时点名原因而不铺假报价。
   */
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('token', 'test-token')
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()

    mockedOverview.mockResolvedValue({ code: 200, message: 'ok', data: overviewFixture() } as never)
    mockedTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedCarriers.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedAlerts.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedTracking.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedQuotes.mockResolvedValue({ code: 200, message: 'ok', data: quoteListFixture, _page: pageMeta(quoteListFixture) } as never)
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const mountQuotes = async () => {
    const wrapper = mountView()
    await flushPromises()
    const t = wrapper.findAll('.tab').find((x) => x.text().includes('比价'))
    await t!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('比价页展示逐条有效报价：承运商/航线/单价/失效日', async () => {
    const wrapper = await mountQuotes()
    const panel = wrapper.find('[data-panel="quotes"]')
    expect(panel.exists()).toBe(true)
    expect(mockedQuotes).toHaveBeenCalledWith('1', undefined, 50, undefined)
    const text = panel.text()
    expect(text).toContain('Matson')
    expect(text).toContain('CNSZX')
    expect(text).toContain('USLAX')
    expect(text).toContain('2026-12-31')
  })

  it('服务类型筛选作为参数下推，不是前端本地过滤', async () => {
    const wrapper = await mountQuotes()
    const panel = wrapper.find('[data-panel="quotes"]')
    await panel.find('select').setValue('AIR')
    await flushPromises()
    expect(mockedQuotes).toHaveBeenLastCalledWith('1', 'AIR', 50, undefined)
  })

  it('截断时披露「不是全量」并给下一页（游标带上游）', async () => {
    mockedQuotes.mockResolvedValue({
      code: 200, message: 'ok', data: quoteListFixture,
      _page: pageMeta(quoteListFixture, { hasMore: true, truncated: true, nextCursor: 'c1' })
    })
    const wrapper = await mountQuotes()
    const panel = wrapper.find('[data-panel="quotes"]')
    expect(panel.text()).toContain('不是全量')

    await panel.findAll('button').find((b: any) => b.text().includes('加载下一页'))!.trigger('click')
    await flushPromises()
    expect(mockedQuotes).toHaveBeenLastCalledWith('1', undefined, 50, 'c1')
  })

  /**
   * **这一刀变异测试抓到两处空洞，这是第一处。**
   *
   * `_page` 存在、`hasMore: true`、但**没有 nextCursor** 时，只能得出
   * 「后端说还有更多，却没给翻页凭据」。此时必须显示「完整性未知」并**不给按钮**，
   * 不能显示成「不是全量」再配一个点不动的下一页——那等于告诉用户
   * 「你能看完，点那里」，而点了什么都不会发生，报价永远看不全。
   *
   * 原有测试只覆盖了两个分支：`truncated` 有游标（显示不是全量 + 按钮可用）、
   * `_page` 整个缺失。中间这个组合没人测，所以把 `quotesTruncated = hasMore`
   * （丢掉 metaMissing 判断）改坏也不会红。
   */
  it('有更多但没有游标时必须说「完整性未知」，且不给下一页按钮', async () => {
    mockedQuotes.mockResolvedValue({
      code: 200, message: 'ok', data: quoteListFixture,
      _page: pageMeta(quoteListFixture, { hasMore: true, truncated: false, nextCursor: null })
    } as never)
    const wrapper = await mountQuotes()
    const panel = wrapper.find('[data-panel="quotes"]')

    // 本面板自己的那份文案（与 LogisticsCursorBar 内的措辞不同，两块各自成文）
    expect(panel.text()).toContain('未拿到分页元数据，无法确认是否全量')
    // 关键：不得伪装成「有下一页」。给了按钮却带不了游标，用户点了毫无反应
    expect(panel.text()).not.toContain('后端声明还有更多未加载')
    expect(panel.findAll('button').some((b: any) => b.text().includes('加载下一页'))).toBe(false)
  })

  /**
   * **第二处空洞：抛异常时不再报页级错误区。**
   *
   * 非 200 有测试（上面那条），catch 分支没有——两条路都把原因写进页顶
   * `.error-zone`，但只有前者被断言。改成静默吞掉后列表显示「暂无有效报价」，
   * **看起来像真的没有报价，而不是加载失败**，运营会据此判断「这条航线没得选」。
   */
  it('报价列表请求抛异常时也要把原因落到页级错误区，不显示成「暂无报价」', async () => {
    mockedQuotes.mockRejectedValue(new Error('网关超时') as never)
    const wrapper = await mountQuotes()

    // 走页顶 error-zone（与货件/调拨同模式），不在面板内自说自话
    expect(wrapper.find('.error-zone').text()).toContain('网关超时')
    const panel = wrapper.find('[data-panel="quotes"]')
    expect(panel.text()).not.toContain('Matson')
    expect(panel.text()).toContain('暂无有效报价')
  })

  it('报价列表拉取失败时点名原因，不铺假报价', async () => {
    mockedQuotes.mockResolvedValue({ code: 400, message: '无权访问该店铺的报价', data: null as any })
    const wrapper = await mountQuotes()
    // 区块错误走页面顶部 error-zone（与货件/调拨同模式），不在面板内自说自话
    expect(wrapper.find('.error-zone').text()).toContain('无权访问该店铺的报价')
    const panel = wrapper.find('[data-panel="quotes"]')
    expect(panel.text()).not.toContain('Matson')
    expect(panel.text()).toContain('暂无有效报价')
  })
})

describe('LogisticsDashboard 头程分摊计算（此前 calculateFreight 零调用）', () => {
  /**
   * 未核算表只说「有费用未摊」，不给把钱摊下去的入口——运营看到 SHP-102 登记了 8000
   * 运费，却只能去别的页面想办法。后端 POST /logistics/v2/freight/{shipmentId}/calculate
   * 早已就绪（按重量/体积/数量重算并写回明细，返回分摊之和与 balanced 校验量），
   * 前端 calculateFreight 却零调用。本组钉住：逐行入口、主单运费预填、提交参数、
   * 结果校验量展示、失败透出、OPERATOR/ADMIN 门控。
   */
  const CALC_RESULT = {
    shipmentId: 102, method: 'WEIGHT',
    totalFreight: 8000, totalDuty: 0, totalInsurance: 0, totalOther: 0,
    grandTotal: 8000,
    allocations: [
      { shipmentId: 102, sku: 'SKU-A', quantity: 100, freightCost: 5000, totalCost: 5000, unitCost: 50 },
      { shipmentId: 102, sku: 'SKU-B', quantity: 60, freightCost: 3000, totalCost: 3000, unitCost: 50 }
    ],
    allocatedFreight: 8000, allocatedDuty: 0, allocatedInsurance: 0,
    balanced: true
  }

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('token', 'test-token')
    localStorage.setItem('current_shop_id', '1')
    vi.clearAllMocks()

    mockedOverview.mockResolvedValue({ code: 200, message: 'ok', data: overviewFixture() } as never)
    mockedTrend.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedCarriers.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedAlerts.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedTracking.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: pageMeta([]) } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedCalculate.mockResolvedValue({ code: 200, message: 'ok', data: CALC_RESULT } as never)
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const mountFreight = async () => {
    const wrapper = mountView()
    await flushPromises()
    const t = wrapper.findAll('.tab').find((x) => x.text().includes('头程'))
    expect(t, '未找到「头程」标签').toBeTruthy()
    await t!.trigger('click')
    await flushPromises()
    return wrapper
  }

  const calcRow = (wrapper: any, text: string) => {
    const row = wrapper.findAll('tbody tr').find((r: any) => r.text().includes(text))
    expect(row, `未找到含「${text}」的行`).toBeTruthy()
    return row!
  }

  it('未核算表每行有「分摊」入口，点开后运费预填主单已登记值', async () => {
    const wrapper = await mountFreight()
    const row = calcRow(wrapper, 'SHP-102')
    const btn = row.findAll('button').find((b: any) => b.text() === '分摊')
    expect(btn, 'SHP-102 行没有「分摊」按钮').toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    const form = wrapper.find('[data-panel="freight-calc"]')
    expect(form.exists(), '没有分摊表单').toBe(true)
    // 主单已登记 8000 运费：预填的是「这笔钱」，不是凭空编的默认值
    const inputs = form.findAll('input')
    expect((inputs[0].element as HTMLInputElement).value).toBe('8000')
  })

  it('运费总额留空时本地拦下，不得把 0 元提交上去', async () => {
    // **这条钉住本班实测到的覆盖空洞**：把「空输入转 NaN 再拦」这段删掉后，
    // 38 条测试全绿。而空输入的后果不是报错，是 Number('') === 0，
    // 于是该票全部明细行的分摊金额被**静默写成 0**——钱没了看不出来。
    const wrapper = await mountFreight()

    // 打开表单（主单预填 8000），再把运费总额清空
    const row = calcRow(wrapper, 'SHP-102')
    await row.findAll('button').find((b: any) => b.text() === '分摊')!.trigger('click')
    await flushPromises()
    // 打开表单（主单预填 8000），再把运费总额清空
    const form = wrapper.find('[data-panel="freight-calc"]')
    const freightInput = form.findAll('input')[0]
    await freightInput.setValue('')
    await form.findAll('button').find((b: any) => b.text() === '提交分摊')!.trigger('click')
    await flushPromises()

    // 不得发出请求：后端一收到就会按 0 元重写全部明细行
    expect(mockedCalculate).not.toHaveBeenCalled()
    // 也不得留下假结果：用户会以为分摊成功
    expect(wrapper.find('[data-panel="freight-calc-result"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('请填写运费总额')
  })

  it('提交走 calculateFreight，成功后展示总额与平衡校验并重拉看板', async () => {
    const wrapper = await mountFreight()
    await calcRow(wrapper, 'SHP-102').findAll('button').find((b: any) => b.text() === '分摊')!.trigger('click')
    await flushPromises()
    await wrapper.find('[data-panel="freight-calc"]').findAll('button').find((b: any) => b.text() === '提交分摊')!.trigger('click')
    await flushPromises()
    expect(mockedCalculate).toHaveBeenCalledWith(102, expect.objectContaining({ method: 'WEIGHT', totalFreight: 8000 }))
    const result = wrapper.find('[data-panel="freight-calc-result"]')
    expect(result.exists(), '没有分摊结果面板').toBe(true)
    expect(result.text()).toContain('8000')
    expect(result.text()).toContain('分摊平衡')
    // 分摊改写了明细行，看板数字已过期，必须重拉
    expect(mockedFreightBoard.mock.calls.length).toBe(2)
  })

  it('分摊之和对不上总额时给出不平警告，不报成成功', async () => {
    mockedCalculate.mockResolvedValue({
      code: 200, message: 'ok',
      data: { ...CALC_RESULT, allocatedFreight: 7999.98, balanced: false }
    } as never)
    const wrapper = await mountFreight()
    await calcRow(wrapper, 'SHP-102').findAll('button').find((b: any) => b.text() === '分摊')!.trigger('click')
    await flushPromises()
    await wrapper.find('[data-panel="freight-calc"]').findAll('button').find((b: any) => b.text() === '提交分摊')!.trigger('click')
    await flushPromises()
    const result = wrapper.find('[data-panel="freight-calc-result"]')
    expect(result.text()).toContain('分摊不平')
    expect(result.text()).not.toContain('分摊平衡')
  })

  it('后端点名失败（无分摊明细）时 toast 透出原因，不留假结果', async () => {
    mockedCalculate.mockResolvedValue({ code: 400, message: '该货件尚未登记分摊明细，无法计算头程费用', data: null as any } as never)
    const wrapper = await mountFreight()
    await calcRow(wrapper, 'SHP-102').findAll('button').find((b: any) => b.text() === '分摊')!.trigger('click')
    await flushPromises()
    await wrapper.find('[data-panel="freight-calc"]').findAll('button').find((b: any) => b.text() === '提交分摊')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.toast').text()).toContain('尚未登记分摊明细')
    expect(wrapper.find('[data-panel="freight-calc-result"]').exists()).toBe(false)
  })

  it('VIEWER 看不到分摊入口；未知角色看得到（后端才是权威判定）', async () => {
    localStorage.setItem('user_role', 'VIEWER')
    const viewer = await mountFreight()
    expect(calcRow(viewer, 'SHP-102').findAll('button').some((b: any) => b.text() === '分摊')).toBe(false)

    localStorage.removeItem('user_role')
    const unknown = await mountFreight()
    expect(calcRow(unknown, 'SHP-102').findAll('button').some((b: any) => b.text() === '分摊')).toBe(true)
  })
})
