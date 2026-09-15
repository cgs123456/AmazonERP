import { describe, it, expect, beforeEach, vi } from 'vitest'
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
  listTransfers: vi.fn(),
  approveTransfer: vi.fn(),
  shipTransfer: vi.fn(),
  receiveTransfer: vi.fn(),
  investigateDiscrepancy: vi.fn(),
  resolveDiscrepancy: vi.fn()
}))

import {
  getLogisticsOverview,
  getLogisticsTrend,
  getCarrierPerformance,
  getLogisticsAlerts,
  listShipments,
  importShipments,
  getQuoteBoard,
  getTransferBoard,
  getFreightCostBoard,
  getReceiptBoard,
  compareQuotes,
  listTransfers,
  approveTransfer,
  resolveDiscrepancy
} from '@/api/logistics'

const mockedOverview = vi.mocked(getLogisticsOverview)
const mockedTrend = vi.mocked(getLogisticsTrend)
const mockedCarriers = vi.mocked(getCarrierPerformance)
const mockedAlerts = vi.mocked(getLogisticsAlerts)
const mockedShipments = vi.mocked(listShipments)
const mockedImportShipments = vi.mocked(importShipments)
const mockedQuoteBoard = vi.mocked(getQuoteBoard)
const mockedTransferBoard = vi.mocked(getTransferBoard)
const mockedFreightBoard = vi.mocked(getFreightCostBoard)
const mockedReceiptBoard = vi.mocked(getReceiptBoard)
const mockedCompare = vi.mocked(compareQuotes)
const mockedTransfers = vi.mocked(listTransfers)
const mockedApprove = vi.mocked(approveTransfer)
const mockedResolve = vi.mocked(resolveDiscrepancy)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: true
  }
}

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
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedTransfers.mockResolvedValue({ code: 200, message: 'ok', data: transferListFixture } as never)
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
    mockedShipments.mockResolvedValue({ code: 200, message: 'ok', data: [] } as never)
    mockedQuoteBoard.mockResolvedValue({ code: 200, message: 'ok', data: quoteBoardFixture() } as never)
    mockedTransferBoard.mockResolvedValue({ code: 200, message: 'ok', data: transferBoardFixture() } as never)
    mockedFreightBoard.mockResolvedValue({ code: 200, message: 'ok', data: freightBoardFixture() } as never)
    mockedReceiptBoard.mockResolvedValue({ code: 200, message: 'ok', data: receiptBoardFixture() } as never)
    mockedTransfers.mockResolvedValue({ code: 200, message: 'ok', data: transferListFixture } as never)
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
})
