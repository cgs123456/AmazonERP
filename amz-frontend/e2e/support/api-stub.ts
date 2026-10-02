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
 * 审单域（/order/audit/**）：桩数据取自后端真实返回形状。
 * unevaluatedRules 与 advisoryNote 是重点——E2E 要断言页面把「没判定」和「没风险」分开显示，
 * 以及路由结果的 warehouseName 为空时显示「未解析」而不是拼一个仓名。
 */
const AUDIT_RULES = [
  {
    id: 1, shopId: 1, ruleName: 'PO Box地址检测', ruleType: 'ADDRESS_CHECK',
    conditionField: 'shipping_address', conditionOp: 'CONTAINS', conditionValue: 'PO Box',
    action: 'FLAG', priority: 1, enabled: true, description: '检测PO Box地址标记高风险', actionParams: null
  },
  {
    id: 2, shopId: 1, ruleName: '同地址合并', ruleType: 'MERGE',
    conditionField: 'shipping_address', conditionOp: 'EQ', conditionValue: '__SAME_ADDRESS__',
    action: 'MERGE', priority: 10, enabled: false, description: '同收货地址订单建议合并发货', actionParams: null
  },
  {
    id: 3, shopId: 1, ruleName: '高额拦截', ruleType: 'AMOUNT_CHECK',
    conditionField: 'final_price', conditionOp: 'GT', conditionValue: '500',
    action: 'BLOCK', priority: 5, enabled: true, description: '金额>500 拦截', actionParams: null
  }
]
const AUDIT_RESULT_ONE = {
  orderId: '114-1111111-1111111', shopId: 1, verdict: 'REVIEW',
  alerts: [{ ruleId: 1, ruleName: 'PO Box地址检测', ruleType: 'ADDRESS_CHECK', action: 'FLAG', description: '检测PO Box地址标记高风险' }],
  actions: ['FLAG', 'MERGE'], alertCount: 1,
  unevaluatedRules: [{ ruleId: 2, ruleName: '同地址合并', action: 'MERGE', conditionField: 'shipping_address',
    conditionOp: 'EQ', reason: '条件字段 shipping_address 取不到值（字段未接入或订单该值为空）' }],
  unevaluatedCount: 1, advisoryActions: ['MERGE'],
  advisoryNote: 'MERGE/SPLIT 只是规则建议：本系统没有合并/拆单实现，amz_order_split_log 全仓没有任何插入点，订单数据不会被这条规则改变。',
  auditTime: '2026-10-02T10:00:00'
}
const AUDIT_BATCH = [
  { orderId: '114-1', shopId: 1, verdict: 'PASS', alerts: [], actions: [], alertCount: 0,
    unevaluatedRules: [], unevaluatedCount: 0, auditTime: '2026-10-02T10:00:00' },
  { orderId: '114-2', shopId: 1, verdict: 'BLOCKED',
    alerts: [{ ruleId: 3, ruleName: '高额拦截', ruleType: 'AMOUNT_CHECK', action: 'BLOCK', description: '金额>500 拦截' }],
    actions: ['BLOCK'], alertCount: 1, unevaluatedRules: [], unevaluatedCount: 0, auditTime: '2026-10-02T10:00:01' }
]
// warehouseName/warehouseId 为空是真实语义：订单模块不解析具体仓库
const AUDIT_ROUTE = {
  id: 88, shopId: 1, amazonOrderId: '114-1111111-1111111', sku: 'SKU-1', asin: 'B0TEST01', quantity: 2,
  warehouseId: null, warehouseName: null, warehouseType: 'FBA', carrierName: null, trackingNo: null,
  shippingCost: null, selectedReason: 'FBA主配送国家，仅仓库类型建议；具体发货仓未解析，需物流模块确认',
  routeTime: '2026-10-02T10:00:00'
}

/**
 * 搜索域（/search）：检索结果是 ES 文档，热搜是 Redis ZSET 元组，历史是 amz_history 行。
 * 三个 list 接口都返回数组，必须显式登记；热搜在 Redis 集合为空时后端返回的是 null，
 * 「没有计数」与「计数为 0」是两回事，所以另留一组可切换的 null 场景不在这里做。
 */
const SEARCH_PRODUCTS = [
  {
    id: 1, title: 'Wireless Earbuds Pro', content: 'anc true wireless', summary: '主动降噪耳机',
    image: 'http://img/1.jpg', price: 39.9, sku: 'SKU-WE-01', shopId: 7, userId: 3,
    user: { id: 3, nickname: '卖家A', phone: null }
  },
  {
    id: 2, title: 'Earbuds Case', content: 'protective case', summary: null, image: null,
    price: null, sku: null, shopId: null, userId: null, user: null
  }
]
const SEARCH_HOT = [{ key: 'earbuds', score: 12 }, { key: 'anc', score: 5 }]
const SEARCH_HISTORY = [{ history: 'earbuds', userId: 3 }, { history: 'anc', userId: 3 }]

/**
 * 采购域（/procurement）：页面挂载即并发拉供应商/计划/采购单/货件四个列表，
 * 四个都要登记成分页数组，落到 EMPTY_PAGE 对象兜底会让每个 loader 报「没有分页元数据」。
 * 审批留痕按 planId 区分：计划 12 有两行（一次驳回一次通过），计划 13 一行都没有，
 * 用来同时验「渲染真实留痕」与「没有留痕时不伪造」。
 */
const PROCUREMENT_SUPPLIERS = [
  { id: 1, shopId: 1, supplierName: '深圳市华强北电子科技有限公司', supplierCode: 'SUP-001',
    contactName: '张经理', status: 'ACTIVE', rating: 4.5, onTimeDeliveryRate: 95.5 }
]
const PROCUREMENT_PLANS = [
  { id: 12, shopId: 1, planNo: 'PLAN-0012', sku: 'SKU-002', plannedQty: 100, unitPrice: 22.0,
    totalAmount: 2200.0, urgency: 'NORMAL', source: 'AUTO', status: 'PENDING_APPROVAL' },
  { id: 13, shopId: 1, planNo: 'PLAN-0013', sku: 'SKU-003', plannedQty: 50, unitPrice: 18.0,
    totalAmount: 900.0, urgency: 'URGENT', source: 'MANUAL', status: 'APPROVED' }
]
const PROCUREMENT_TRAIL_PLAN_12 = [
  { id: 2, shopId: 1, refType: 'PLAN', refId: 12, action: 'APPROVE', operator: '张经理',
    comment: '价格已核', createTime: '2026-10-03T10:00:00' },
  { id: 1, shopId: 1, refType: 'PLAN', refId: 12, action: 'REJECT', operator: '李四',
    comment: null, createTime: '2026-10-02T09:00:00' }
]

/**
 * 海外仓库存与预警（/logistics/warehouse 的 stock/alert 部分）。
 * 第二条规则故意用 alertType=DAMAGE_RISK：后端 evaluateAlert 对未知类型走 default，
 * 页面必须自己标「不会被判定」而不是让运营以为它在生效。
 */
const WAREHOUSE_STOCKS = [
  {
    id: 71, shopId: 1, warehouseId: 5, warehouseName: '洛杉矶仓', warehouseType: 'OVERSEAS',
    sku: 'SKU-WH-01', asin: 'B0WH01', availableQty: 3, reservedQty: 1, inboundQty: 0,
    transferOutQty: 0, totalQty: 4, unitCost: 12.5, totalValue: 50.0,
    lastInboundDate: '2026-08-01', daysInStock: 62, snapshotTime: '2026-10-01T03:00:00'
  },
  {
    id: 72, shopId: 1, warehouseId: 6, warehouseName: '深圳仓', warehouseType: 'DOMESTIC',
    sku: 'SKU-WH-02', asin: null, availableQty: 0, reservedQty: null, inboundQty: null,
    transferOutQty: null, totalQty: null, unitCost: null, totalValue: null,
    lastInboundDate: null, daysInStock: null, snapshotTime: null
  }
]
const INVENTORY_ALERTS = [
  {
    id: 81, shopId: 1, sku: 'SKU-WH-01', warehouseId: 5, alertType: 'LOW_STOCK', thresholdValue: 5,
    thresholdUnit: 'QTY', alertLevel: 'CRITICAL', notifyChannels: 'EMAIL', enabled: true, description: '低库存'
  },
  {
    id: 82, shopId: 1, sku: null, warehouseId: null, alertType: 'DAMAGE_RISK', thresholdValue: 30,
    thresholdUnit: 'DAYS', alertLevel: 'WARNING', notifyChannels: null, enabled: false, description: null
  }
]
const ALERT_CHECK_REPORT = {
  shopId: 1, alertRulesChecked: 1, totalTriggered: 1, critical: 1, warning: 0, info: 0,
  alerts: [{
    alertId: 81, alertType: 'LOW_STOCK', alertLevel: 'CRITICAL', description: '低库存',
    sku: 'SKU-WH-01', warehouseName: '洛杉矶仓', availableQty: 3, daysInStock: 62, totalValue: 50.0
  }],
  stocksTruncated: true, scannedStockCount: 500
}

/**
 * SP-API 发件箱与限流观测（网关别名 /connectors/**）。
 * 桩里故意放三条不同状态：DLQ 一条（写方法 POST）、FAILED 一条（读方法 GET）、SUCCEEDED 一条，
 * 用来断言页面把「写方法重放有远端副作用」与「读方法只是再问一次」分开表述。
 */
const CONNECTOR_OUTBOX = [
  {
    id: 901, shopId: 1, operationId: 'createFeedDocument', httpMethod: 'POST',
    requestPath: '/feeds/2021-06-30/documents', status: 'DLQ', attemptCount: 5, maxAttempts: 5,
    responseStatus: 429, marketplaceId: 'ATVPDKIKX0DER', lastErrorCode: 'RATE_LIMITED',
    lastErrorMessage: 'Too Many Requests', createdAt: '2026-10-01T09:00:00'
  },
  {
    id: 902, shopId: 1, operationId: 'getOrders', httpMethod: 'GET',
    requestPath: '/orders?CreatedAfter=2026-09-01', status: 'FAILED', attemptCount: 2, maxAttempts: 5,
    responseStatus: 500, lastErrorCode: null, lastErrorMessage: 'upstream boom',
    createdAt: '2026-10-01T10:00:00'
  },
  {
    id: 903, shopId: 1, operationId: 'getOrders', httpMethod: 'GET',
    requestPath: '/orders?CreatedAfter=2026-09-02', status: 'SUCCEEDED', attemptCount: 1, maxAttempts: 5,
    responseStatus: 200, lastErrorCode: null, lastErrorMessage: null, createdAt: '2026-10-01T11:00:00'
  }
]
const CONNECTOR_RATE_LIMITS = [
  {
    shopId: 1, operationId: 'getOrders', variant: 'default', headerValue: '5;rate=0.45;burst=30',
    observedRatePerSecond: 0.45, effectiveRatePerSecond: 0.4, burst: 30, observedAt: '2026-10-01T12:00:00Z'
  }
]

/**
 * 搜索词分析与规则 /ad/search-term。
 * 执行结果必须带 appliedToAdAccount:false —— 后端的规则引擎不调广告 API，
 * 桩数据如果写成 true，就等于是测试替页面撒了谎。
 */
const AD_RULES = [
  {
    id: 41, shopId: 1, ruleName: '高ACoS自动暂停', ruleType: 'KEYWORD_PAUSE',
    scope: 'CAMPAIGN', scopeValue: 'camp-777', conditionField: 'ACOS', conditionOp: 'GT',
    conditionValue: 50, conditionValue2: null, action: 'PAUSE', actionValue: null,
    timeWindow: 14, priority: 10, enabled: 1, lastExecuted: null
  },
  {
    id: 42, shopId: 1, ruleName: 'BETWEEN 死规则', ruleType: 'KEYWORD_BID',
    scope: null, scopeValue: null, conditionField: 'ACOS', conditionOp: 'BETWEEN',
    conditionValue: 10, conditionValue2: null, action: 'DECREASE_BID', actionValue: 15,
    timeWindow: 7, priority: 8, enabled: 0, lastExecuted: '2026-10-01T08:00:00'
  }
]
const AD_SEARCH_TERMS = [
  {
    id: 1, shopId: 1, campaignId: 'camp-777', keywordId: 4242, searchTerm: 'yoga mat',
    matchType: 'EXACT', impressions: 1200, clicks: 24, cost: 48, sales: 12, orders: 1,
    acos: 400, cr: 4.17, ctr: 2, cpc: 2, reportDate: '2026-09-20'
  }
]
const AD_ANALYZE = {
  shopId: 1, analysisPeriod: 7, scannedRows: 1, totalSearchTerms: 1, convertingTerms: 1,
  wasteTerms: 0, highAcosTerms: 1, lowCrTerms: 1, totalCost: 48, totalSales: 12, wasteCost: 0,
  overallAcos: 400,
  topConvertingTerms: [{ searchTerm: 'yoga mat', orders: 1, sales: 12, cost: 48, acos: 400 }],
  topWasteTerms: []
}
const AD_CLUSTERS = {
  shopId: 1, scannedRows: 1, totalClusters: 2,
  topClusters: [
    { root: 'yoga', termCount: 1, totalImpressions: 1200, totalClicks: 24, totalCost: 48, totalSales: 12, totalOrders: 1 },
    { root: 'mat', termCount: 1, totalImpressions: 1200, totalClicks: 24, totalCost: 48, totalSales: 12, totalOrders: 1 }
  ]
}
const AD_CONVERTING = [
  {
    id: 7, shopId: 1, asin: null, searchTerm: 'yoga mat', campaignId: 'camp-777',
    totalOrders: 3, totalSales: 36, totalCost: 144, avgAcos: 400,
    firstSeen: '2026-09-14', lastSeen: '2026-09-20', isAddedToKeyword: 0, status: 'ACTIVE'
  }
]
const AD_ASIN_KEYWORDS = [
  {
    id: 3, shopId: 1, asin: 'B0ABC12345', keyword: 'yoga mat', organicRank: 12, adRank: 4,
    searchVolume: 40000, relevanceScore: 4.5, isIndexed: 1, lastChecked: '2026-09-28'
  }
]
const AD_EXEC_RESULT = {
  ruleId: 41, ruleName: '高ACoS自动暂停', ruleType: 'KEYWORD_PAUSE',
  appliedToAdAccount: false, actionCount: 1,
  note: '本接口只产出建议清单，未调用广告 API',
  matchedActions: [
    { searchTerm: 'yoga mat', matchedValue: 400, action: 'PAUSE', suggestion: '暂停该搜索词所在投放', applied: false }
  ]
}

/** 分时调价：V1 迁移预置的三行形态，倍率 >1 是抬价、<1 是压价 */
const AD_BID_SCHEDULES = [
  { id: 91, shopId: 1, campaignId: null, startHour: 20, endHour: 23, multiplier: 1.5, enabled: 1 },
  { id: 92, shopId: 1, campaignId: 'camp-777', startHour: 0, endHour: 6, multiplier: 0.7, enabled: 0 },
  { id: 93, shopId: 1, campaignId: null, startHour: 7, endHour: 9, multiplier: 1.2, enabled: 1 }
]

/** 多平台统一订单：本地表里的行，只有点同步才会去平台拉 */
const MP_ORDERS = [
  {
    id: 61, unifiedOrderNo: 'UO20260930001', platform: 'TEMU', platformOrderNo: 'TE-9001', shopId: 1,
    buyerNickname: 'Ana', shipCountry: 'US', sku: 'SKU-A', productName: 'Yoga mat', quantity: 2,
    originalAmount: 25.9, currency: 'USD', cnyAmount: 186.5, status: 'PAID',
    trackingNo: null, orderCreateTime: '2026-09-30 10:12:00'
  },
  {
    id: 62, unifiedOrderNo: 'UO20260929002', platform: 'SHEIN', platformOrderNo: 'SH-7001', shopId: 1,
    buyerNickname: 'Kai', shipCountry: 'DE', sku: 'SKU-B', productName: 'Desk lamp', quantity: 1,
    originalAmount: 12, currency: 'EUR', cnyAmount: 94.2, status: 'SHIPPED',
    trackingNo: 'TRK-OLD', orderCreateTime: '2026-09-29 18:02:11'
  }
]

/**
 * 多平台运营台数据。两条刻意保留的形状：
 * 1. 账号行里没有 apiKey/密文列的真实值——后端读取接口会把它们抹掉，桩必须照这个契约给，
 *    否则前端「凭证不回显」这条边界在 E2E 里是假绿（页面上本来就没有可泄露的值）。
 * 2. Webhook 行的 PROCESSED 只代表入库且处理未抛异常，processResult 直接写清这一点。
 */
// 利润下钻行：第二行的成本列是 null 且 dataComplete=false，
// 页面必须把它标成「数据不全」而不是当普通行一样渲染数字。
const PROFIT_DRILL_ROWS = [
  {
    id: 901, shopId: 1, amazonOrderId: '111-2222222-3333333', sku: 'SKU-A1', statDate: '2026-09-01',
    revenue: 5000, productCost: 3000, referralFee: 750, adCost: 250,
    fbaFulfillmentFee: 300, fbaStorageFee: 50, vat: 200, netProfit: 450, netMargin: 0.09, dataComplete: true
  },
  {
    id: 900, shopId: 1, amazonOrderId: '111-2222222-3333333', sku: 'SKU-B2', statDate: '2026-08-31',
    revenue: 120, productCost: null, referralFee: 18, adCost: null,
    fbaFulfillmentFee: null, fbaStorageFee: null, vat: null, netProfit: -4, netMargin: -0.03, dataComplete: false
  }
]

const PROFIT_MONTHLY_ROWS = [
  { shop_id: 1, sku: 'SKU-A1', month: '2026-09', total_revenue: 5000, total_cost: 3000, total_profit: 450, margin: 0.09 },
  { shop_id: 1, sku: 'SKU-B2', month: '2026-08', total_revenue: 800, total_cost: 900, total_profit: -100, margin: -0.125 }
]

const MP_ACCOUNTS = [
  {
    id: 7, shopId: 1, platform: 'TEMU', storeName: 'Temu US 旗舰店',
    apiEndpoint: 'https://open-api.temu.com', status: 'ACTIVE', apiKey: null,
    tokenExpiresAt: '2026-11-01 00:00:00', lastSyncTime: null, createTime: '2026-08-01 09:00:00'
  },
  {
    id: 8, shopId: 1, platform: 'SHEIN', storeName: 'SHEIN DE 店',
    apiEndpoint: null, status: 'ERROR', apiKey: null,
    tokenExpiresAt: null, lastSyncTime: '2026-09-30 02:00:00', createTime: '2026-08-05 11:00:00'
  }
]

const MP_PRODUCTS = [
  {
    id: 31, shopId: 1, platform: 'TEMU', platformProductId: 'TP-1001', title: 'Yoga mat',
    price: 19.9, currency: 'USD', stockQty: 120, status: 'ACTIVE',
    amazonAsin: 'B0ABC12345', amazonSku: 'AMZ-SKU-1'
  },
  {
    id: 32, shopId: 1, platform: 'TIKTOK', platformProductId: 'TT-2002', title: 'Desk lamp',
    price: 8, currency: 'EUR', stockQty: 0, status: 'OUT_OF_STOCK', amazonAsin: null, amazonSku: null
  }
]

const MP_MESSAGES = [
  {
    id: 41, shopId: 1, platform: 'TEMU', platformMessageId: 'PM-1', buyerName: 'Ana',
    subject: 'When will it ship?', direction: 'IN', status: 'UNREAD', assignedTo: null,
    receiveTime: '2026-10-01 08:00:00'
  },
  {
    id: 42, shopId: 1, platform: 'SHEIN', platformMessageId: 'PM-2', buyerName: 'Kai',
    subject: 'Thanks', direction: 'OUT', status: 'REPLIED', assignedTo: '客服甲',
    receiveTime: '2026-10-01 09:00:00'
  }
]

const MP_INVENTORY = [
  {
    id: 51, shopId: 1, platform: 'TEMU', platformProductId: 'TP-1001', sku: 'SKU-A',
    warehouse: 'GZ-01', availableQty: 0, reservedQty: 2, inboundQty: 30, snapshotTime: '2026-09-28 01:00:00'
  },
  {
    id: 52, shopId: 1, platform: 'TIKTOK', platformProductId: 'TT-2002', sku: 'SKU-B',
    warehouse: null, availableQty: 15, reservedQty: 0, inboundQty: null, snapshotTime: null
  }
]

const MP_AGGREGATE = {
  shopId: 1, grandTotalAvailable: 15,
  byPlatform: { TEMU: { 'SKU-A': 0 }, TIKTOK: { 'SKU-B': 15 } },
  bySku: { 'SKU-A': 0, 'SKU-B': 15 },
  computedAt: '2026-10-02 19:00:00'
}

const MP_WEBHOOKS = [
  {
    id: 61, shopId: 1, platform: 'TEMU', eventType: 'ORDER_CREATED', eventId: 'EV-1',
    status: 'PROCESSED', processResult: '已记录；当前事件分发只写日志，不触发业务动作',
    processTime: '2026-10-01 07:00:00', createTime: '2026-10-01 07:00:00'
  },
  {
    id: 62, shopId: 1, platform: 'SHEIN', eventType: 'REFUND_CREATED', eventId: 'EV-2',
    status: 'FAILED', processResult: 'payload 解析失败', processTime: null, createTime: '2026-10-01 07:30:00'
  }
]

const MP_APPS = [
  {
    id: 71, appName: 'WMS 对接', appKey: 'ak-visible-part', scopes: 'order.read,inventory.read',
    redirectUris: 'https://wms.example/cb', rateLimitRpm: 60, status: 'ACTIVE', ownerShopId: 1
  }
]

/** 明文密钥只在注册/轮换这一条响应里出现一次，列表接口不会有 */
const MP_SECRET_ISSUE = { appId: 71, appKey: 'ak-issued-e2e', appSecret: 'sk-issued-e2e-secret' }

/**
 * 运营预警台数据。告警行的 create_time 由数据库默认值写入，所以其中一行刻意留 null，
 * 页面必须显示「未记录」而不是补一个看起来像的时间。
 */
// Listing 监控台：概览是聚合对象、列表是分页数组，趋势/对比是带 null 的视图对象。
// 排名点里刻意放了 null：页面必须显示 —，补成 0 在排名语义里等于「比第一名还好」。
const LM_SUMMARY = {
  total: 2, ok: 1, warning: 0, critical: 1, avgScore: 69.0, healthRate: 50.0,
  worstListings: [{ asin: 'B000026108', score: 20, severity: 'CRITICAL', reason: '标题长度需80-200字符' }]
}
const LM_HEALTH = [
  {
    id: 71, shopId: 1, asin: 'B000026108', sku: 'SKU-1108', status: 'ACTIVE',
    titleOk: false, bulletPointsOk: true, descriptionOk: true, aplusOk: null, imagesOk: true,
    searchTermsOk: true, suppressedReason: '标题长度需80-200字符; A+内容未检查',
    healthScore: 20, severity: 'CRITICAL', checkTime: '2026-10-01T10:00:00'
  },
  {
    id: 72, shopId: 1, asin: 'B000026109', sku: 'SKU-1109', status: 'ACTIVE',
    titleOk: true, bulletPointsOk: true, descriptionOk: true, aplusOk: true, imagesOk: true,
    searchTermsOk: true, suppressedReason: null, healthScore: 100, severity: 'OK',
    checkTime: '2026-10-01T10:00:00'
  }
]
const LM_RANKINGS = [
  { id: 1, shopId: 1, asin: 'B00000000001', keyword: 'yoga mat', organicRank: 7, adRank: 3, searchVolume: 60500, rankDate: '2026-09-30' }
]
const LM_COMPETITORS = [
  { id: 1, shopId: 1, competitorAsin: 'B0COMPET01', competitorTitle: 'Competitor yoga mat', price: 25.99, bsRank: 88, reviewCount: 4120, reviewRating: 4.4, inStock: true, snapshotDate: '2026-10-01' }
]
const LM_BUYBOX = [
  { id: 1, shopId: 1, asin: 'B00000000001', sellerId: 'A2SELLER', isSelf: true, buyboxPrice: 23.5, ourPrice: 23.49, priceGap: -0.01, fulfillmentType: 'FBA', ownershipPct: 62.5, snapshotTime: '2026-10-02T08:30:02' }
]
const LM_CHANGELOGS = [
  { id: 1, shopId: 1, asin: 'B00000000001', field: 'price', oldValue: '25.99', newValue: '23.99', changeTime: '2026-10-01T09:00:00' }
]
const LM_MASTERS = [
  { id: 1, shopId: 1, sku: 'MASTER-1', asin: 'B0MASTER001', marketplaceId: 'ATVPDKIKX0DER', title: '瑜伽垫 6mm', brand: 'Akman', sizeTier: 'SMALL_LIGHT', weightG: 900, status: 'ACTIVE' }
]
const LM_TREND = {
  shopId: 1, asin: 'B00000000001', days: 30, truncated: false,
  keywords: {
    'yoga mat': [
      { date: '2026-09-29', organicRank: null, adRank: 12 },
      { date: '2026-09-30', organicRank: 7, adRank: null }
    ]
  }
}
const LM_COMPARE = {
  shopId: 1, myAsin: 'B0MINE00001', competitorAsin: 'B0COMPET01', days: 30,
  ownAsinCompared: false, truncated: false,
  latest: { competitorAsin: 'B0COMPET01', price: 25.99, bsRank: 88, reviewCount: 4120, reviewRating: 4.4, snapshotDate: '2026-10-01' },
  trendData: [
    { date: '2026-09-30', price: 25.99, bsRank: 88, reviewCount: 4120, reviewRating: 4.4, inStock: true, hasCoupon: null, hasDeal: false }
  ]
}
const LM_CHECK_RESULT = LM_HEALTH[0]

const OPS_REVIEWS = [
  {
    id: 81, shopId: 1, asin: 'B0REVIEW01', reviewId: 'R1001', rating: 1,
    title: 'Stopped working after a week', content: 'Very disappointed.',
    reviewer: 'John D.', status: 'NEW', createTime: '2026-10-01 10:00:00'
  },
  {
    id: 80, shopId: 1, asin: 'B0REVIEW02', reviewId: null, rating: 3,
    title: 'Zipper broke', reviewer: 'Kim', status: 'HANDLED', createTime: null
  }
]

const OPS_HIJACKS = [
  {
    id: 91, shopId: 1, asin: 'B0HIJACK01', hijackerSellerId: 'A777',
    hijackerName: 'Competitor Seller', hijackPrice: 19.99, buyBoxTaken: true,
    status: 'NEW', createTime: '2026-09-30 08:00:00'
  },
  {
    id: 90, shopId: 1, asin: 'B0HIJACK02', hijackerSellerId: null, hijackerName: null,
    hijackPrice: null, buyBoxTaken: null, status: 'IGNORED', createTime: null
  }
]

const OPS_TREND = [
  { id: 5, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 42, marketplace: 'US', captureTime: '2026-10-01 09:00:00' },
  { id: 6, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 12, marketplace: 'US', captureTime: '2026-10-02 09:00:00' },
  { id: 7, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 31, marketplace: 'US', captureTime: '2026-10-02 18:00:00' }
]

/**
 * 助手记忆页（/agent-memory）用的真实表结构。
 * 字段名抄自后端 UserPreference / ConversationMemory；userId 固定 1，
 * 因为上面的 /user/getInfo 桩把登录用户回成 id=1 —— 页面身份只认这个来源，
 * 不再读 localStorage 里由 main.ts 兜底写死的 user_id。
 */
const AGENT_PREFERENCE = {
  id: 12, userId: 1, nickname: 'E2E 用户', preferredShopId: 1,
  preferredShopName: null, preferredCategory: '瑜伽用品', language: 'ZH',
  lastActiveTime: '2026-10-01T10:00:00', createTime: '2026-09-01T10:00:00', updateTime: '2026-10-01T10:00:00'
}

const AGENT_HISTORY = [
  { id: 31, sessionId: 'sess-1', userId: 1, role: 'user', content: '最近7天销量如何？', createTime: '2026-10-01T09:00:00' },
  { id: 32, sessionId: 'sess-1', userId: 1, role: 'assistant', content: '近 7 天共 12 单。', createTime: '2026-10-01T09:00:05' }
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

  // ===== 利润下钻 /order/profit/{order,sku,summary} =====
  // 上面 report 那条是精确匹配，三条下钻互不重叠。
  { match: /^\/order\/profit\/order\//, data: PROFIT_DRILL_ROWS, page: FULL_PAGE(PROFIT_DRILL_ROWS.length) },
  { match: /^\/order\/profit\/sku\//, data: PROFIT_DRILL_ROWS },
  { match: /^\/order\/profit\/summary\//, data: PROFIT_MONTHLY_ROWS },
  { match: /^\/report\/dashboard\/kpi$/, data: DASHBOARD_KPI },
  { match: /^\/report\/dashboard\/sales-trend$/, data: DASHBOARD_SALES_TREND },
  { match: /^\/report\/dashboard\/shop-distribution$/, data: DASHBOARD_SHOP_DIST },
  { match: /^\/spapi\/inventory\/health\//, data: INVENTORY_HEALTH },
  { match: /^\/spapi\/replenish\/list\//, data: REPLENISH },
  // 手动重算：后端 data 是「本次生成的建议条数」，前端要据此重新拉列表
  { match: /^\/spapi\/replenish\/calc\//, data: 3 },
  // 库位就地编辑：返回更新后的整行，locationCode 取调用方提交值（等价于后端回显）
  {
    match: /^\/logistics\/warehouse\/inventory\/\d+\/location$/,
    data: (query: URLSearchParams) => ({ ...WAREHOUSE_INVENTORY[0], locationCode: query.get('locationCode') })
  },
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
  // 手动回补：单店与全店共用这个路径，靠有没有 shopId 参数分重载；这里给一份成功计数
  { match: /^\/ad\/reports\/sync$/, data: { shopId: 1, days: 7, attempted: 1, succeeded: 1, failed: 0, skipped: 0, upserted: 12, metadataWarnings: 0 } },
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

  // ===== 审单 /order-audit =====
  // 顺序敏感：/rule/{id} 的宽模式必须排在 /rule/list/{shopId} 之后，否则列表请求会被当成 id 命中。
  { match: /^\/order\/audit\/rule\/list\//, data: AUDIT_RULES },
  { match: /^\/order\/audit\/split-log\/list\//, data: [] },
  { match: /^\/order\/audit\/rule\/\d+\/toggle$/, data: true },
  { match: /^\/order\/audit\/order\//, data: AUDIT_RESULT_ONE },
  { match: /^\/order\/audit\/batch\//, data: AUDIT_BATCH },
  { match: /^\/order\/audit\/route\//, data: AUDIT_ROUTE },
  { match: /^\/order\/audit\/rule\/\d+$/, data: { id: 1, enabled: false } },
  { match: /^\/order\/audit\/rule$/, data: { id: 4 } },

  // ===== 搜索 /search =====
  { match: /^\/search\/search\//, data: SEARCH_PRODUCTS },
  { match: /^\/search\/getHotList$/, data: SEARCH_HOT },
  { match: /^\/search\/getHistoryList$/, data: SEARCH_HISTORY },
  { match: /^\/search\/deleteHistory$/, data: null },

  // ===== 采购 /procurement =====
  { match: /^\/procurement\/supplier\/list\//, data: PROCUREMENT_SUPPLIERS, page: FULL_PAGE(PROCUREMENT_SUPPLIERS.length) },
  { match: /^\/procurement\/plan\/list\//, data: PROCUREMENT_PLANS, page: FULL_PAGE(PROCUREMENT_PLANS.length) },
  { match: /^\/procurement\/order\/list\//, data: [], page: FULL_PAGE(0) },
  { match: /^\/procurement\/fba\/shipment\/list\//, data: [], page: FULL_PAGE(0) },
  // 顺序敏感：具体 planId 必须排在通配 \d+ 之前
  { match: /^\/procurement\/plan\/12\/approvals$/, data: PROCUREMENT_TRAIL_PLAN_12 },
  { match: /^\/procurement\/plan\/\d+\/approvals$/, data: [] },
  { match: /^\/procurement\/plan\/\d+\/approve$/, data: PROCUREMENT_PLANS[0] },

  // ===== 海外仓库存与预警 /logistics/warehouse（stock + alert 部分） =====
  { match: /^\/logistics\/warehouse\/stock\/list\//, data: WAREHOUSE_STOCKS, page: FULL_PAGE(WAREHOUSE_STOCKS.length) },
  { match: /^\/logistics\/warehouse\/alert\/check\//, data: ALERT_CHECK_REPORT },
  { match: /^\/logistics\/warehouse\/alert\/list\//, data: INVENTORY_ALERTS },
  { match: /^\/logistics\/warehouse\/alert\/\d+\/toggle$/, data: true },
  { match: /^\/logistics\/warehouse\/alert$/, data: INVENTORY_ALERTS[0] },

  // ===== 连接器队列 /connectors（outbox + rate-limits） =====
  { match: /^\/connectors\/outbox\/\d+\/replay$/, data: { success: true, outcome: 'REPLAYED', status: 'SUCCEEDED', message: null } },
  { match: /^\/connectors\/outbox$/, data: CONNECTOR_OUTBOX },
  { match: /^\/connectors\/rate-limits$/, data: CONNECTOR_RATE_LIMITS },

  // ===== 搜索词与规则 /ad/search-term =====
  // 顺序敏感：batch 必须排在 asin-reverse 通配之前；bare 根路径（POST /ad/search-term）
  // 是数据录入端点，路径最短，放最后。
  { match: /^\/ad\/search-term\/asin-reverse\/batch$/, data: AD_ASIN_KEYWORDS },
  { match: /^\/ad\/search-term\/asin-reverse\//, data: AD_ASIN_KEYWORDS, page: FULL_PAGE(AD_ASIN_KEYWORDS.length) },
  { match: /^\/ad\/search-term\/converting\/extract\//, data: AD_CONVERTING },
  { match: /^\/ad\/search-term\/converting\/list\//, data: AD_CONVERTING, page: FULL_PAGE(AD_CONVERTING.length) },
  { match: /^\/ad\/search-term\/rule\/execute\//, data: { shopId: 1, rulesExecuted: 2, totalActions: 1, ruleResults: [AD_EXEC_RESULT] } },
  { match: /^\/ad\/search-term\/rule\/list\//, data: AD_RULES, page: FULL_PAGE(AD_RULES.length) },
  { match: /^\/ad\/search-term\/rule\/\d+\/execute$/, data: AD_EXEC_RESULT },
  { match: /^\/ad\/search-term\/rule\/\d+\/toggle$/, data: true },
  { match: /^\/ad\/search-term\/rule\/\d+$/, data: AD_RULES[0] },
  { match: /^\/ad\/search-term\/rule$/, data: AD_RULES[0] },
  { match: /^\/ad\/search-term\/list\//, data: AD_SEARCH_TERMS, page: FULL_PAGE(AD_SEARCH_TERMS.length) },
  { match: /^\/ad\/search-term\/analyze\//, data: AD_ANALYZE },
  { match: /^\/ad\/search-term\/cluster\//, data: AD_CLUSTERS },
  { match: /^\/ad\/search-term$/, data: AD_SEARCH_TERMS[0] },

  // ===== 分时调价 /ad/bidSchedule（唯一会真实改广告账号竞价的通道）=====
  // 顺序敏感：toggle 要在 /\d+$/ 之前；店铺列表那条更要在前面，
  // 否则 GET /ad/bidSchedule/1 会先撞上「单对象」那条，页面拿到非数组只能显示空表。
  { match: /^\/ad\/bidSchedule\/1$/, data: AD_BID_SCHEDULES, page: FULL_PAGE(AD_BID_SCHEDULES.length) },
  { match: /^\/ad\/bidSchedule\/\d+\/toggle$/, data: true },
  { match: /^\/ad\/bidSchedule\/\d+$/, data: AD_BID_SCHEDULES[0] },
  { match: /^\/ad\/bidSchedule$/, data: AD_BID_SCHEDULES[0] },

  // ===== 多平台订单 /multiplatform =====
  // 顺序敏感：按平台的列表要在通用 list/ 之前；ship 要排在 list 之后但不冲突。
  { match: /^\/multiplatform\/order\/list\/1\/TEMU$/, data: [MP_ORDERS[0]] },
  { match: /^\/multiplatform\/order\/list\//, data: MP_ORDERS },
  { match: /^\/multiplatform\/order\/\d+\/ship$/, data: true },
  { match: /^\/multiplatform\/sync\/all\//, data: { attempted: 3, succeeded: 2, failed: 1, inserted: 2, failedPlatforms: ['TIKTOK'] } },
  { match: /^\/multiplatform\/sync\/1\/TEMU$/, data: 2 },

  // ===== Listing 监控台 /listings（读快照 + 人工登记 + 趋势 / 对比）=====
  // 顺序敏感：trend / compare / check 都要排在各自的通用 list/ 之前。
  { match: /^\/product\/listing-monitor\/health\/summary\//, data: LM_SUMMARY },
  { match: /^\/product\/listing-monitor\/health\/check$/, data: LM_CHECK_RESULT },
  { match: /^\/product\/listing-monitor\/health\/list\//, data: LM_HEALTH, page: FULL_PAGE(LM_HEALTH.length) },
  { match: /^\/product\/listing-monitor\/ranking\/trend\//, data: LM_TREND },
  { match: /^\/product\/listing-monitor\/ranking\/list\//, data: LM_RANKINGS, page: FULL_PAGE(LM_RANKINGS.length) },
  { match: /^\/product\/listing-monitor\/competitor\/compare\//, data: LM_COMPARE },
  { match: /^\/product\/listing-monitor\/competitor\/list\//, data: LM_COMPETITORS, page: FULL_PAGE(LM_COMPETITORS.length) },
  { match: /^\/product\/listing-monitor\/buybox\/list\//, data: LM_BUYBOX, page: FULL_PAGE(LM_BUYBOX.length) },
  { match: /^\/product\/listing-monitor\/change-log\/list\//, data: LM_CHANGELOGS, page: FULL_PAGE(LM_CHANGELOGS.length) },
  { match: /^\/product\/master\/list\//, data: LM_MASTERS, page: FULL_PAGE(LM_MASTERS.length) },

  // ===== 多平台运营台 /multiplatform-ops =====
  // 顺序敏感：oauth app 的 list 要排在 /oauth/app 之前，否则列表会拿到一次性密钥对象；
  // account 的 list 同理要排在 /account/\d+ 之前。
  { match: /^\/multiplatform\/account\/list\//, data: MP_ACCOUNTS, page: FULL_PAGE(MP_ACCOUNTS.length) },
  { match: /^\/multiplatform\/account\/\d+$/, data: MP_ACCOUNTS[0] },
  { match: /^\/multiplatform\/account$/, data: MP_ACCOUNTS[0] },
  { match: /^\/multiplatform\/product\/\d+\/map$/, data: true },
  { match: /^\/multiplatform\/product\/list\//, data: MP_PRODUCTS, page: FULL_PAGE(MP_PRODUCTS.length) },
  { match: /^\/multiplatform\/message\/\d+\/assign$/, data: true },
  { match: /^\/multiplatform\/message\/list\//, data: MP_MESSAGES, page: FULL_PAGE(MP_MESSAGES.length) },
  { match: /^\/multiplatform\/inventory\/aggregated\//, data: MP_AGGREGATE },
  { match: /^\/multiplatform\/inventory\/list\//, data: MP_INVENTORY, page: FULL_PAGE(MP_INVENTORY.length) },
  { match: /^\/multiplatform\/webhook\/list\//, data: MP_WEBHOOKS, page: FULL_PAGE(MP_WEBHOOKS.length) },
  { match: /^\/multiplatform\/oauth\/app\/list\//, data: MP_APPS, page: FULL_PAGE(MP_APPS.length) },
  { match: /^\/multiplatform\/oauth\/app\/\d+\/rotate$/, data: MP_SECRET_ISSUE },
  { match: /^\/multiplatform\/oauth\/app$/, data: MP_SECRET_ISSUE },

  // ===== 运营预警台 /ops-alerts（读告警表 + 差评的本地状态迁移）=====
  // 三个 scan 端点刻意不登记：页面没有按钮，若将来有人加了，请求会落到 EMPTY_PAGE 兜底
  // 变成「对象而不是数组」，页面会报「后端返回非 200」而不是安静地显示假数据。
  { match: /^\/ops\/review\/list\//, data: OPS_REVIEWS, page: FULL_PAGE(OPS_REVIEWS.length) },
  { match: /^\/ops\/review\/\d+\/handle$/, data: true },
  { match: /^\/ops\/hijack\/list\//, data: OPS_HIJACKS, page: FULL_PAGE(OPS_HIJACKS.length) },
  { match: /^\/ops\/rank\/trend$/, data: OPS_TREND },

  // ===== 助手记忆 /agent-memory（user_preference + conversation_memory 真实表）=====
  // POST /ai/agent/memory/chat 刻意不登记：它要求真实 deepseek.api-key，页面也没有入口。
  // POST /ai/agent/memory/reminder/scan 同样不登记：提醒正文是写死示例，非 mock 档后端直接拒绝。
  { match: /^\/ai\/agent\/memory\/preference\/\d+$/, data: AGENT_PREFERENCE },
  { match: /^\/ai\/agent\/memory\/preference$/, data: { ...AGENT_PREFERENCE, nickname: 'E2E 改名', preferredCategory: '健身器材' } },
  { match: /^\/ai\/agent\/memory\/language$/, data: { ...AGENT_PREFERENCE, language: 'EN' } },
  { match: /^\/ai\/agent\/memory\/history\//, data: AGENT_HISTORY },

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
