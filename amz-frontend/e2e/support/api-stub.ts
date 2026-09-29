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

const ok = (data: unknown) => ({ code: 200, data, msg: 'ok' })

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

const STUBS: Array<{ match: RegExp; data: StubData }> = [
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
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(ok(data))
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
