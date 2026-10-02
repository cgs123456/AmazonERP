/**
 * Playwright E2E 的 hermetic API 打桩。
 *
 * 为什么必须打桩：这套 E2E 原本自称「连接本地真实后端栈」，但 CI runner 与本地
 * 都没有 16 个微服务在跑，所有 /api/* 请求必然失败；本地还叠加了系统代理，失败
 * 请求会挂住而不是快速返回，导致 waitForLoadState('networkidle') 永不达成。
 * 实测基线（2026-09-30）：39 个用例 23 passed / 16 failed，耗时 10.3 分钟，
 * 16 个失败全部是超时——不是产品缺陷，是测试依赖不可控的外部状态。
 *
 * 打桩后的边界（写下来以免后人误读）：
 * - 只证明「前端拿到这样的响应时会这样渲染/交互」，不证明后端真的返回这个结构。
 * - 后端改字段名而前端未同步时，这套测试查不出来（那是后端集成测试的职责）。
 * - 打桩数据是固定的，不含时间戳/随机值，因此结果可复现。
 */
import type { Page, Route } from '@playwright/test'

const ok = (data: unknown, page?: PageMeta) =>
  page ? { code: 200, data, msg: 'ok', _page: page } : { code: 200, data, msg: 'ok' }

/** 列表类接口的空分页兜底。注意：它是「对象」形状，只适用于返回 {list,total,...}
 * 的分页接口；返回数组的接口必须像上面 dashboard 那样显式登记，否则前端遍历时抛异常。 */
const EMPTY_PAGE = { list: [] as unknown[], total: 0, page: 1, size: 20 }

/**
 * Dashboard 三组数据的形状必须逐个对齐后端 ReportController 的返回：
 *   /report/dashboard/kpi               -> 对象（KpiRaw）
 *   /report/dashboard/sales-trend       -> 数组
 *   /report/dashboard/shop-distribution -> 数组
 * 形状错了会把页面打进不可恢复的渲染异常：sales-trend 被 Dashboard#maxSales 用
 * for...of 遍历，非数组直接抛 TypeError，骨架屏永不消失、页面等于白屏。
 * 所以这三个端点必须显式登记，绝不能落到下面的 EMPTY_PAGE 对象兜底。
 */
const DASHBOARD_KPI = {
  shopId: 1,
  dateRange: '7d',
  totalSales: 12345.67,
  totalOrders: 23,
  conversionRate: 12.5,
  returnRate: 1.8,
  avgOrderValue: 536.77
}

const DASHBOARD_SALES_TREND = [
  { day: '周一', value: 980 },
  { day: '周二', value: 1120 },
  { day: '周三', value: 1050 },
  { day: '周四', value: 1340 },
  { day: '周五', value: 1180 },
  { day: '周六', value: 1420 },
  { day: '周日', value: 1234 }
]

const DASHBOARD_SHOP_DIST = [
  { name: 'Shop A (US)', percent: 45 },
  { name: 'Shop B (UK)', percent: 30 },
  { name: 'Shop C (DE)', percent: 15 },
  { name: 'Shop D (JP)', percent: 10 }
]

const ORDERS = [
  {
    id: 1,
    shopId: '1',
    amazonOrderId: '114-1234567-8901234',
    orderStatus: 'SHIPPED',
    quantity: 2,
    finalPrice: 59.98,
    purchaseDate: '2026-09-01T10:00:00'
  },
  {
    id: 2,
    shopId: '1',
    amazonOrderId: '113-7654321-0987654',
    orderStatus: 'PENDING',
    quantity: 1,
    finalPrice: 19.99,
    purchaseDate: '2026-09-02T11:30:00'
  },
  {
    id: 3,
    shopId: '1',
    amazonOrderId: '112-1111111-2222222',
    orderStatus: 'CANCELED',
    quantity: 5,
    finalPrice: 149.95,
    purchaseDate: '2026-09-03T09:15:00'
  }
]

const INVENTORY_HEALTH = [
  { sku: 'SKU-A1', asin: 'B0AAAAA1', shopId: '1', availableQuantity: 120, avg7Days: 10, daysOfSupply: 12, healthStatus: 'HEALTHY' },
  { sku: 'SKU-B2', asin: 'B0BBBBB2', shopId: '1', availableQuantity: 5, avg7Days: 8, daysOfSupply: 1, healthStatus: 'URGENT' },
  { sku: 'SKU-C3', asin: 'B0CCCCC3', shopId: '1', availableQuantity: 900, avg7Days: 1, daysOfSupply: 900, healthStatus: 'OVERSTOCK' }
]

const REPLENISH = [
  { sku: 'SKU-B2', asin: 'B0BBBBB2', statDate: '2026-09-20', suggestedReplenishQty: 50, urgencyLevel: 'URGENT' }
]

const PROFIT_REPORT = {
  totalRevenue: 10000,
  totalCost: 6000,
  totalProfit: 4000,
  margin: 0.4,
  reports: [
    {
      statDate: '2026-09-01',
      sku: 'SKU-A1',
      revenue: 5000,
      productCost: 3000,
      referralFee: 750,
      adCost: 250,
      fbaFulfillmentFee: 400,
      fbaStorageFee: 50,
      netProfit: 550,
      netMargin: 0.11
    },
    {
      statDate: '2026-09-02',
      sku: 'SKU-B2',
      revenue: 5000,
      productCost: 3000,
      referralFee: 750,
      adCost: 250,
      fbaFulfillmentFee: 400,
      fbaStorageFee: 50,
      netProfit: 550,
      netMargin: 0.11
    }
  ]
}

const LOGISTICS_OVERVIEW = {
  totalShipments: 12,
  statusCounts: { CREATED: 2, IN_TRANSIT: 5, DELIVERED: 4, EXCEPTION: 1 },
  activeShipments: 7,
  delayed: 2,
  exception: 1,
  arrivingIn7Days: 3,
  etaOverdue: 1,
  missingTrackingNo: 0,
  staleShipments: 1,
  staleThresholdHours: 24,
  avgTransitDays: 18.5,
  transitStatWindowDays: 90,
  lastTrackTime: '2026-09-20T08:00:00',
  autoSyncAvailable: false,
  dataSourceCounts: { MANUAL: 10, CARRIER_API: 2 }
}

const LOGISTICS_TREND = [
  { date: '2026-09-01', created: 3, delivered: 1 },
  { date: '2026-09-02', created: 2, delivered: 2 }
]

const LOGISTICS_CARRIER = [
  { carrier: 'DHL', total: 6, active: 3, delivered: 3, delayed: 0, exception: 0, avgTransitDays: 15.2, delayedRate: 0, exceptionRate: 0 }
]

const LOGISTICS_ALERTS = [
  {
    type: 'ETA_OVERDUE',
    severity: 'HIGH',
    shipmentId: 101,
    shipmentNo: 'FBA-2026-0101',
    carrier: 'DHL',
    masterTrackingNo: 'TRK0000001',
    status: 'IN_TRANSIT',
    eta: '2026-09-15',
    daysOverdue: 5,
    lastTrackTime: '2026-09-20T08:00:00',
    message: 'ETA 已过 5 天仍未签收',
    actionHint: '联系承运商确认'
  }
]

const SHIPMENTS = [
  {
    id: 101,
    shipmentNo: 'FBA-2026-0101',
    fbaShipmentId: 'FBA16KQ9W2',
    shopId: 1,
    shippingMethod: 'SEA',
    carrier: 'DHL',
    masterTrackingNo: 'TRK0000001',
    originPort: 'Shenzhen',
    destinationPort: 'Los Angeles',
    fbaWarehouseAddress: 'LAX9',
    boxCount: 20,
    weight: 320.5,
    freightCost: 1800,
    status: 'IN_TRANSIT',
    eta: '2026-09-15',
    dataSource: 'MANUAL',
    lastTrackTime: '2026-09-20T08:00:00',
    createTime: '2026-09-01T09:00:00'
  }
]

const QUOTE_BOARD = {
  shopId: 1,
  totalQuotes: 4,
  validQuotes: 3,
  staleByDate: 1,
  expiringIn30Days: 0,
  inactiveQuotes: 0,
  unpricedQuotes: 0,
  carrierCount: 2,
  routeCount: 2,
  byServiceType: { SEA: 2, AIR: 2 },
  byCurrency: { USD: 4 },
  routes: [],
  warnings: []
}

const TRANSFER_BOARD = {
  shopId: 1,
  total: 3,
  byStatus: { PENDING: 1, IN_TRANSIT: 1, RECEIVED: 1 },
  pendingApproval: 1,
  approvedNotShipped: 0,
  inTransit: 1,
  received: 1,
  cancelled: 0,
  totalShippingCost: 420,
  staleInTransit: 0,
  staleThresholdDays: 14,
  risks: [],
  warnings: []
}

const FREIGHT_BOARD = {
  shopId: 1,
  totalAllocationRows: 2,
  totalQuantity: 320,
  coveredShipments: 1,
  uncoveredShipments: 1,
  coverageRate: 0.5,
  totalFreight: 1800,
  totalDuty: 120,
  totalInsurance: 30,
  totalOther: 0,
  totalCost: 1950,
  avgUnitCost: 6.09,
  methodMix: { BY_VALUE: 2 },
  uncoveredList: [],
  topUnitCostItems: [],
  warnings: []
}

const RECEIPT_BOARD = {
  shopId: 1,
  total: 2,
  pending: 1,
  investigating: 0,
  resolved: 1,
  matched: 0,
  totalExpected: 200,
  totalReceived: 190,
  totalDifference: -10,
  shortageRows: 1,
  overreceivedRows: 0,
  shortageUnits: 10,
  overreceivedUnits: 0,
  openShortageUnits: 10,
  discrepancyRate: 0.05,
  topShortageAsins: [],
  pendingItems: [],
  warnings: []
}

type PageMeta = {
  size: number
  returned: number
  hasMore: boolean
  truncated: boolean
  nextCursor: string | null
  total: number | null
}

/**
 * 第二批显式打桩：广告 / 财务 / 选品 / 海外仓。
 *
 * 为什么必须逐个登记而不是让它们落到 EMPTY_PAGE 兜底：
 * - EMPTY_PAGE 是 {list,total,page,size} 的「对象」形状。Finance / Warehouse 的
 *   list* 接口后端返回的是**数组**（分页元数据在 Result._page 里，不在 data 里），
 *   /ad/reports、/ad/trend、/ops/selection/opportunities 同理。落到对象兜底时
 *   `Array.isArray(res.data)` 为 false，前端静默走空态分支：页面不报错、也不显示
 *   任何真实数据——这正是「测试绿了但其实什么都没测到」的来源。
 * - 反过来，若把对象形状塞给期望数组的字段，Vue 的 v-for 会去遍历对象的值，
 *   渲染出一堆无意义的行。形状必须逐个对齐 src/api/*.ts 的泛型参数。
 */

// GET /ad/reports -> AdReportRow[]（前端聚合出总览；这里刻意让 花费/销售额 是整数，
// 这样 ACoS 与 ROAS 的期望值可以手算出来写进断言：500/2500=20.0%，2500/500=5.00x）
const AD_REPORTS = [
  { campaignId: 'C-1001', keyword: null, impressions: 12000, clicks: 480, cost: 320, sales: 1600, orders: 64 },
  { campaignId: 'C-1002', keyword: 'bluetooth speaker', impressions: 8000, clicks: 260, cost: 180, sales: 900, orders: 30 }
]

// GET /ad/trend -> AcosTrendItem[]
const AD_TREND = [
  { day: '9/1', value: 22 },
  { day: '9/2', value: 24 },
  { day: '9/3', value: 21 },
  { day: '9/4', value: 25 },
  { day: '9/5', value: 23 },
  { day: '9/6', value: 20 },
  { day: '9/7', value: 22 }
]

// GET /ad/campaigns/list/{shopId} -> 活动行数组（AdManager 里映射成 {name,active,...}）
const AD_CAMPAIGNS = [
  { id: 11, campaignId: 'C-1001', campaignName: '关键词-蓝牙耳机-US', status: 'ENABLED', budget: 50, spend: 32.5, sales: 158, acos: 20.6 },
  { id: 12, campaignId: 'C-1002', campaignName: '自动广告-全店铺', status: 'PAUSED', budget: 100, spend: 68.3, sales: 210.5, acos: 32.4 }
]

// GET /ad/campaigns/summary/type/{shopId} -> Record<adType, AdSummary>（对象，不是数组）
// 字段名按前端 AdSummary（src/api/ad-ext.ts）给：spend / orders / roas 是前端模板直接读的键。
// 注意这里不能证明后端真的返回这些键——打桩只保证前端渲染路径被测到。
const AD_SUMMARY_BY_TYPE = {
  SP: { impressions: 20000, clicks: 740, spend: 500, sales: 2500, orders: 94, acos: 20, roas: 5 },
  SB: { impressions: 4000, clicks: 120, spend: 90, sales: 420, orders: 18, acos: 21.4, roas: 4.67 }
}

// GET /ad/creatives/list/{campaignId}、/ad/targeting/list/{campaignId} -> 数组
const AD_CREATIVES = [
  { id: 21, campaignId: 'C-1001', creativeType: 'VIDEO', headline: 'Earbuds Pro 2026', brandName: 'E2E Brand', status: 'PENDING' }
]
const AD_TARGETING = [
  { id: 31, campaignId: 'C-1002', targetingType: 'CONTEXTUAL', targetingValue: 'electronics', bid: 0.85 }
]

// GET /finance/voucher/list/{shopId} -> AccountingVoucher[]（游标分页，元数据在 _page）
// 两页不同：第一页 truncated=true 且给 nextCursor，第二页收口。
// 这样「加载更多」这条路径才真的被测到——单页桩会让按钮永远不出现。
const VOUCHERS_PAGE1 = [
  { id: 1, voucherNo: 'V-2026-0001', shopId: 1, bizDate: '2026-09-01', summary: '订单收入', debitAccount: '1122 应收账款', creditAccount: '6001 主营业务收入', originalAmount: 1000, currency: 'USD', exchangeRate: 7.1, cnyAmount: 7100, sourceType: 'ORDER', sourceNo: '114-1234567-8901234', kingdeeSyncStatus: 'PENDING' },
  { id: 2, voucherNo: 'V-2026-0002', shopId: 1, bizDate: '2026-09-02', summary: '采购成本', debitAccount: '1405 库存商品', creditAccount: '2202 应付账款', originalAmount: 3000, currency: 'CNY', exchangeRate: 1, cnyAmount: 3000, sourceType: 'PROCUREMENT', sourceNo: 'PO-2026-0007', kingdeeSyncStatus: 'SYNCED' },
  { id: 3, voucherNo: 'V-2026-0003', shopId: 1, bizDate: '2026-09-03', summary: '退款', debitAccount: '6001 主营业务收入', creditAccount: '1122 应收账款', originalAmount: 500, currency: 'CNY', exchangeRate: 1, cnyAmount: 500, sourceType: 'REFUND', sourceNo: '114-7654321-0987654', kingdeeSyncStatus: 'FAILED' }
]
const VOUCHERS_PAGE2 = [
  { id: 4, voucherNo: 'V-2026-0004', shopId: 1, bizDate: '2026-09-04', summary: '平台费用', debitAccount: '6601 销售费用', creditAccount: '1122 应收账款', originalAmount: 120, currency: 'CNY', exchangeRate: 1, cnyAmount: 120, sourceType: 'PLATFORM_FEE', sourceNo: 'FEE-2026-0011', kingdeeSyncStatus: 'PENDING' }
]

// GET /finance/profit/{shopId} -> number（不是对象）
const FINANCE_PROFIT = 12345.67

// GET /ops/selection/opportunities -> SelectionOpportunity[]
const SELECTION_OPPORTUNITIES = [
  { id: 101, shopId: 1, asin: 'B0SELECT1', title: 'Wireless Earbuds Pro', category: 'Electronics', marketplace: 'US', avgPrice: 39.99, avgReviews: 1200, avgRating: 4.5, searchVolume: 90000, competitorCount: 12, reviewBarrier: 'LOW', opportunityScore: 82, trend30d: 'UP', trend90d: 'UP' },
  { id: 102, shopId: 1, asin: 'B0SELECT2', title: 'Bluetooth Speaker Mini', category: 'Electronics', marketplace: 'US', avgPrice: 25.5, avgReviews: 340, avgRating: 4.1, searchVolume: 45000, competitorCount: 30, reviewBarrier: 'HIGH', opportunityScore: 46, trend30d: 'FLAT', trend90d: 'DOWN' }
]

// POST /ops/selection/market -> MarketAnalysisSummary（对象，opportunities 是它的子数组）
const SELECTION_MARKET = {
  keyword: 'wireless earbuds',
  marketplace: 'US',
  category: 'Electronics',
  marketSize: 1200000,
  avgPrice: 32.75,
  avgReviews: 770,
  avgRating: 4.3,
  searchVolume: 135000,
  competitorCount: 21,
  reviewBarrier: 'MEDIUM',
  trend30d: 'UP',
  trend90d: 'UP',
  seasonality: 'MODERATE_SEASONAL',
  opportunities: SELECTION_OPPORTUNITIES
}

// POST /ops/selection/ai-suggestion/{id} -> SelectionOpportunity（对象）
const SELECTION_AI = {
  id: 101,
  asin: 'B0SELECT1',
  aiSummary: '需求上升、竞争度低，建议小批量试单验证转化率。',
  aiSuggestion: '首批 300 件，定价 $34.99，观察 30 天动销。',
  status: 'SUGGESTED'
}

// GET /logistics/warehouse/list/{shopId} -> Warehouse[]
const WAREHOUSES = [
  { id: 1, shopId: 1, warehouseName: 'US-West-FBA', warehouseCode: 'USW-01', warehouseType: 'FBA', country: 'US', city: 'Chino', capacityCbm: 500, usedCbm: 320, status: 'ACTIVE' },
  { id: 2, shopId: 1, warehouseName: 'DE-ThirdParty', warehouseCode: 'DEU-02', warehouseType: 'THIRD_PARTY', country: 'DE', city: 'Hamburg', capacityCbm: 200, usedCbm: 40, status: 'INACTIVE' }
]

// GET /logistics/warehouse/inventory -> WarehouseInventory[]
const WAREHOUSE_INVENTORY = [
  { id: 1, warehouseId: 1, shopId: 1, sku: 'SKU-A1', asin: 'B0AAAAA1', quantity: 100, reservedQuantity: 10, availableQuantity: 90, inboundQuantity: 20, locationCode: 'A-01-03', batchNo: 'B2026-09' }
]

// GET /logistics/inbound/list/{shopId}、/logistics/outbound/list/{shopId} -> 数组
const INBOUND_ORDERS = [
  { id: 1, shopId: 1, warehouseId: 1, inboundNo: 'IN-2026-0001', source: 'FBA_TRANSFER', referenceNo: 'FBA16KQ9W2', status: 'IN_TRANSIT', totalItems: 100, receivedItems: 0, expectedArrival: '2026-09-20' }
]
const OUTBOUND_ORDERS = [
  { id: 1, shopId: 1, warehouseId: 1, outboundNo: 'OUT-2026-0001', orderType: 'ORDER', referenceNo: '114-1234567-8901234', status: 'PENDING', carrier: 'DHL', trackingNo: 'TRK0000001', totalItems: 5, shippedItems: 0 }
]

/**
 * 凭证列表的游标分页 + 来源类型过滤。
 *
 * sourceType 过滤必须在桩里实现（后端是按 sourceType 查库的）：
 * 桩若忽略该参数，「筛选下拉」用例只会重复渲染同一批数据，看起来绿了其实没测到筛选。
 */
const voucherPage = (query: URLSearchParams): { rows: unknown[]; page: PageMeta } => {
  const sourceType = query.get('sourceType')
  const pick = (rows: typeof VOUCHERS_PAGE1) =>
    sourceType ? rows.filter((v) => v.sourceType === sourceType) : rows
  if (query.get('cursor') === 'cursor-2') {
    const rows = pick(VOUCHERS_PAGE2)
    return { rows, page: FULL_PAGE(rows.length) }
  }
  const rows = pick(VOUCHERS_PAGE1)
  return {
    rows,
    page: { size: 50, returned: rows.length, hasMore: true, truncated: true, nextCursor: 'cursor-2', total: null }
  }
}

// 单页、未截断的 _page：列表就是全量，前端不应显示「加载更多」
const FULL_PAGE = (returned: number): PageMeta => ({
  size: 50,
  returned,
  hasMore: false,
  truncated: false,
  nextCursor: null,
  total: returned
})

/**
 * 客服域（/customer）：这些 list 接口后端返回**数组**，分页元数据在 _page，
 * 所以必须显式登记成数组，绝不能落到 EMPTY_PAGE 对象兜底。
 * 桩里的 category/sentiment/status 是后端关键词规则与状态机的真实取值，
 * 前端只展示不自造；matchedOrderId 留 null，因为「匹配订单」在真实环境要抛错。
 */
const CUSTOMER_TICKETS = [
  {
    id: 1, shopId: 1, amazonOrderId: '114-7712567-000001', buyerName: 'Ana', channel: 'MESSAGE',
    category: 'LOGISTICS', sentiment: 'NEGATIVE', priority: 'HIGH', status: 'PENDING',
    content: 'package not received', reply: ''
  },
  {
    id: 2, shopId: 1, amazonOrderId: '114-7712567-000002', buyerId: 'buyer-2', channel: 'RETURN',
    category: 'REFUND', sentiment: 'NEUTRAL', priority: 'LOW', status: 'RESOLVED',
    content: 'refund ok', reply: 'done'
  }
]
const CUSTOMER_TEMPLATES = [
  {
    id: 11, shopId: 1, templateName: 'Sorry for the delay', templateType: 'AFTERSALE', language: 'en',
    triggerEvent: 'NEGATIVE_REVIEW', triggerDelayHours: 24, enabled: 1,
    subject: 'About your order', body: 'Dear buyer,'
  },
  {
    id: 12, shopId: 1, templateName: 'Shipping notice', templateType: 'SHIPPING', language: 'en',
    triggerEvent: 'SHIPPING_DELAY', triggerDelayHours: 0, enabled: 0,
    subject: 'Tracking', body: 'Your tracking no is'
  }
]
const CUSTOMER_TASKS = [
  {
    id: 21, shopId: 1, amazonOrderId: '114-7712567-000001', asin: 'B0CUST01',
    buyerEmail: 'ana@example.com', subject: 'About your order', status: 'PENDING',
    scheduledTime: '2026-10-02T10:00:00', sentTime: null, source: 'MANUAL', failureReason: null
  }
]
const CUSTOMER_REVIEWS = [
  {
    id: 31, shopId: 1, asin: 'B0CUST01', reviewerName: 'Ana', reviewRating: 1,
    reviewTitle: 'Terrible', reviewDate: '2026-09-28', verifiedPurchase: 1, status: 'DETECTED',
    matchedOrderId: null, contactEmailTaskId: null
  }
]
const CUSTOMER_SOLICITATIONS = [
  { id: 41, shopId: 1, amazonOrderId: '114-7712567-000009', asin: 'B0CUST01', channel: 'EMAIL', status: 'SENT', failureReason: null }
]
const CUSTOMER_RMAS = [
  {
    id: 51, shopId: 1, rmaNo: 'RMA-0001', amazonOrderId: '114-7712567-000003', asin: 'B0CUST01',
    sku: 'SKU-1', returnType: 'REFUND', productCondition: 'OPENED', refundAmount: 12.5,
    status: 'PENDING', labelUrl: null, trackingNo: null
  }
]

/**
 * 非 JSON 的打桩：目前只有 AI 助手的 SSE 流式接口。
 * /api/ai/chat-stream 若按 JSON 兜底返回，fetch 会拿到 200 + 非 SSE 正文，
 * readSseStream 解析不出任何事件，占位气泡永远是空串——页面看起来「没坏」但也没回复。
 * 所以这里必须按 SSE 帧协议返回，才能真的测到「发消息 -> 收到回复」这条链路。
 */
const SSE_STUBS: Array<{ match: RegExp; contentType: string; body: string }> = [
  {
    match: /^\/ai\/chat-stream$/,
    contentType: 'text/event-stream',
    body: [
      'event: tool_call',
      'data: {"name":"queryOrderStats"}',
      '',
      'event: tool_result',
      'data: {"name":"queryOrderStats","ok":true}',
      '',
      'event: final',
      'data: {"content":"近 7 天共 23 笔订单，销售额 $12,345.67，转化率 12.5%。"}',
      '',
      'event: done',
      'data: {}',
      ''
    ].join('\n')
  }
]

/**
 * 按 /api 之后的路径匹配；顺序敏感，先命中先返回。
 * 这里的 URL 与 src/api/*.ts 里的实际请求路径一一对应，改后端路径时要同步改这里。
 */
type StubData = unknown | ((query: URLSearchParams) => unknown)
type StubPage = PageMeta | ((query: URLSearchParams) => PageMeta)

const STUBS: Array<{ match: RegExp; data: StubData; page?: StubPage }> = [
  // orderNo 由后端 OrderController#listOrders 做 LIKE 模糊匹配（服务端过滤，不是前端过滤），
  // 桩必须复现这个语义，否则「搜索后行数变化」这条断言测不到任何东西。
  {
    match: /^\/order\/list$/,
    data: (query: URLSearchParams) => {
      const orderNo = (query.get('orderNo') || '').trim()
      const list = orderNo ? ORDERS.filter((o) => o.amazonOrderId.includes(orderNo)) : ORDERS
      return { list, total: list.length, page: 1, size: 20 }
    }
  },
  { match: /^\/order\/profit\/report$/, data: PROFIT_REPORT },
  { match: /^\/report\/dashboard\/kpi$/, data: DASHBOARD_KPI },
  { match: /^\/report\/dashboard\/sales-trend$/, data: DASHBOARD_SALES_TREND },
  { match: /^\/report\/dashboard\/shop-distribution$/, data: DASHBOARD_SHOP_DIST },
  { match: /^\/spapi\/inventory\/health\//, data: INVENTORY_HEALTH },
  { match: /^\/spapi\/replenish\/list\//, data: REPLENISH },
  { match: /^\/logistics\/dashboard\/overview$/, data: LOGISTICS_OVERVIEW },
  { match: /^\/logistics\/dashboard\/trend$/, data: LOGISTICS_TREND },
  { match: /^\/logistics\/dashboard\/carrier-performance$/, data: LOGISTICS_CARRIER },
  { match: /^\/logistics\/dashboard\/alerts$/, data: LOGISTICS_ALERTS },
  { match: /^\/logistics\/dashboard\/quotes$/, data: QUOTE_BOARD },
  { match: /^\/logistics\/dashboard\/transfers$/, data: TRANSFER_BOARD },
  { match: /^\/logistics\/dashboard\/freight-cost$/, data: FREIGHT_BOARD },
  { match: /^\/logistics\/dashboard\/receipts$/, data: RECEIPT_BOARD },
  { match: /^\/logistics\/shipment\/list\//, data: SHIPMENTS },

  // ===== 广告 /ads =====
  { match: /^\/ad\/reports$/, data: AD_REPORTS },
  { match: /^\/ad\/trend$/, data: AD_TREND },
  { match: /^\/ad\/campaigns\/list\//, data: AD_CAMPAIGNS },
  { match: /^\/ad\/campaigns\/summary\/type\//, data: AD_SUMMARY_BY_TYPE },
  { match: /^\/ad\/creatives\/list\//, data: AD_CREATIVES },
  { match: /^\/ad\/targeting\/list\//, data: AD_TARGETING },

  // ===== 财务 /finance =====
  // 游标分页：带 cursor 的请求返回第二页并收口，否则返回第一页且 truncated=true
  {
    match: /^\/finance\/voucher\/list\//,
    data: (query: URLSearchParams) => voucherPage(query).rows,
    page: (query: URLSearchParams) => voucherPage(query).page
  },
  // POST /finance/voucher/{id}/sync -> KingdeeSyncResult。
  // status 用 MOCK：同步结果必须区分「模拟调用成功」与「真的入账了」，
  // 前端据此给不同 toast 文案，断言的就是这个区分。
  {
    match: /^\/finance\/voucher\/\d+\/sync$/,
    data: {
      status: 'MOCK',
      voucherId: 1,
      kingdeeNo: null,
      message: '模拟同步完成，未真实入账（E2E 打桩）'
    }
  },
  { match: /^\/finance\/profit\//, data: FINANCE_PROFIT },

  // ===== 选品 /selection =====
  { match: /^\/ops\/selection\/opportunities$/, data: SELECTION_OPPORTUNITIES },
  { match: /^\/ops\/selection\/market$/, data: SELECTION_MARKET },
  { match: /^\/ops\/selection\/ai-suggestion\//, data: SELECTION_AI },

  // ===== 海外仓 /warehouse =====
  { match: /^\/logistics\/warehouse\/list\//, data: WAREHOUSES },
  { match: /^\/logistics\/warehouse\/inventory$/, data: WAREHOUSE_INVENTORY, page: FULL_PAGE(WAREHOUSE_INVENTORY.length) },
  { match: /^\/logistics\/inbound\/list\//, data: INBOUND_ORDERS, page: FULL_PAGE(INBOUND_ORDERS.length) },
  { match: /^\/logistics\/outbound\/list\//, data: OUTBOUND_ORDERS, page: FULL_PAGE(OUTBOUND_ORDERS.length) },

  // ===== 客服 /customer =====
  { match: /^\/customer\/ticket\/list\//, data: CUSTOMER_TICKETS, page: FULL_PAGE(CUSTOMER_TICKETS.length) },
  { match: /^\/customer\/email\/template\/list\//, data: CUSTOMER_TEMPLATES, page: FULL_PAGE(CUSTOMER_TEMPLATES.length) },
  { match: /^\/customer\/email\/task\/list\//, data: CUSTOMER_TASKS, page: FULL_PAGE(CUSTOMER_TASKS.length) },
  { match: /^\/customer\/email\/negative-review\/list\//, data: CUSTOMER_REVIEWS, page: FULL_PAGE(CUSTOMER_REVIEWS.length) },
  { match: /^\/customer\/review\/list\//, data: CUSTOMER_SOLICITATIONS, page: FULL_PAGE(CUSTOMER_SOLICITATIONS.length) },
  { match: /^\/customer\/email\/rma\/list\//, data: CUSTOMER_RMAS, page: FULL_PAGE(CUSTOMER_RMAS.length) },
  // 通道受限的三个动作：桩返回的是后端真实会返回的形状（处理报告 / 计数 / 匹配结果），
  // 用来说明「点了确认才会真的发请求」，不证明真的发了信。
  { match: /^\/customer\/email\/process\//, data: { processed: 1, failed: 0, skipped: 0 } },
  { match: /^\/customer\/review\/solicit\//, data: 2 },
  { match: /^\/customer\/email\/negative-review\/\d+\/match$/, data: { reviewId: 31, matchedOrderId: '114-7712567-000001' } },

  { match: /^\/user\/getInfo$/, data: { user: { id: 1, phone: '13800000000', nickname: 'E2E' } } }
]

/**
 * 在给定 page 上安装打桩。
 * - 同源 /api/** 走桩；同源非 /api（静态资源、路由）放行；
 * - 外部域（图标 CDN 等）直接 abort：让它们快速失败，避免请求挂住拖死 networkidle。
 */
export async function installApiStub(page: Page): Promise<void> {
  await page.route('**/*', async (route: Route) => {
    const url = route.request().url()
    let parsed: URL
    try {
      parsed = new URL(url)
    } catch {
      return route.continue()
    }
    const isLocal = ['localhost', '127.0.0.1'].includes(parsed.hostname) && parsed.port === '5173'
    if (!isLocal) {
      return route.abort()
    }
    if (!parsed.pathname.startsWith('/api/')) {
      return route.continue()
    }
    const apiPath = parsed.pathname.slice('/api'.length)
    // SSE 接口先匹配：它们不是 JSON，走 JSON 兜底会让流式消费静默拿不到任何事件
    for (const stub of SSE_STUBS) {
      if (stub.match.test(apiPath)) {
        return route.fulfill({ status: 200, contentType: stub.contentType, body: stub.body })
      }
    }
    for (const stub of STUBS) {
      if (stub.match.test(apiPath)) {
        const data = typeof stub.data === 'function' ? stub.data(parsed.searchParams) : stub.data
        const page = typeof stub.page === 'function' ? stub.page(parsed.searchParams) : stub.page
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(ok(data, page))
        })
      }
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ok(EMPTY_PAGE))
    })
  })
}
