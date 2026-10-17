import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import LogisticsDashboard from '../views/LogisticsDashboard.vue'

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
  listQuotes,
  listTransfers,
  approveTransfer,
  calculateFreight,
  resolveDiscrepancy
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
const mockedQuotes = vi.mocked(listQuotes)
const mockedTransfers = vi.mocked(listTransfers)
const mockedApprove = vi.mocked(approveTransfer)
const mockedCalculate = vi.mocked(calculateFreight)
const mockedResolve = vi.mocked(resolveDiscrepancy)

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
        _page: pageMeta([filtered])
      } as never)

    const wrapper = mountView()
    await flushPromises()
    const tab = wrapper.findAll('.tab').find((t) => t.text().includes('在途货件'))
    await tab!.trigger('click')
    await flushPromises()

    await wrapper.find('#lg-status').setValue('DELAYED')
    await flushPromises()

    expect(mockedShipments).toHaveBeenLastCalledWith('1', 'DELAYED', 50, undefined)
    expect(wrapper.text()).toContain('SHP-DELAYED')
    expect(wrapper.text()).not.toContain('SHP-ALL')
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
