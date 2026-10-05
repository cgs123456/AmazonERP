import request from './auth'
import type { ApiResponse } from './types'

/**
 * 经营报表接口层（amz-service-report，端口 8102）。
 *
 * 动因（2026-10-02 功能覆盖清点）：report 服务 23 个端点里前端只调了 3 个看板接口，
 * `/report/v2/**` 与 `/report/profit/**` 共 20 个端点零入口——利润明细、库存周转与滞销、
 * 日销趋势、经营概览、实时利润快照、成本分摊全都只能手工 curl。
 *
 * 两类刻意的做法：
 * 1. 不接 5 个 POST 入库端点（/report/v2/profit、/inventory-turnover、/sales-daily、
 *    /business-overview、/report/profit/allocation）。它们接收完整实体，是给调度器和其他
 *    服务写数用的；放进 UI 等于让人手工录入会计数据，而且这 5 个端点的 @ShopScoped
 *    是空转的（shopId 只在 body 里，切面按参数名找不到它），越权拦截形同不存在。
 * 2. 快照口径有已知缺口（后端把 vatCost/refundCost/otherCost 写成常量 0），
 *    页面必须显示这个缺口，不能把 netProfit 当最终利润。
 */

export interface ProfitDetail {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  asin?: string
  sku?: string
  reportDate?: string
  productSales?: number | null
  shippingCredits?: number | null
  promotionalRebates?: number | null
  productCost?: number | null
  fbaFees?: number | null
  referralFee?: number | null
  variableClosingFee?: number | null
  inboundFreight?: number | null
  inboundDuty?: number | null
  storageFee?: number | null
  advertisingCost?: number | null
  vatTax?: number | null
  otherFees?: number | null
  grossProfit?: number | null
  netProfit?: number | null
  margin?: number | null
  currency?: string
  exchangeRate?: number | null
}

export interface AsinSummary {
  asin: string
  orderCount: number
  totalSales: number
  totalCost: number
  totalAdSpend: number
  totalFees: number
  grossProfit: number
  netProfit: number
  margin: number
}

export interface ProfitSummaryReport {
  shopId?: number | string
  totalOrders?: number
  totalSales?: number
  totalCost?: number
  totalNetProfit?: number
  overallMargin?: number
  asinSummaries?: AsinSummary[]
}

export interface InventoryTurnover {
  id?: number
  shopId?: number | string
  asin?: string
  sku?: string
  reportDate?: string
  avgInventoryValue?: number | null
  cogs?: number | null
  turnoverRate?: number | null
  daysOfSupply?: number | null
  stockoutCount?: number | null
  overstockDays?: number | null
  deadStockValue?: number | null
}

export interface DeadStockReport {
  shopId?: number | string
  deadStockCount?: number
  totalDeadStockValue?: number
  details?: InventoryTurnover[]
}

export interface SalesDaily {
  id?: number
  shopId?: number | string
  asin?: string
  sku?: string
  reportDate?: string
  unitsOrdered?: number | null
  unitsRefunded?: number | null
  netUnits?: number | null
  grossSales?: number | null
  refundAmount?: number | null
  netSales?: number | null
  sessions?: number | null
  pageViews?: number | null
  conversionRate?: number | null
  buyBoxPercentage?: number | null
  unitsPerSession?: number | null
}

export interface SalesComparison {
  shopId?: number | string
  asin?: string | null
  currentPeriod?: string
  previousPeriod?: string
  currentUnits?: number
  previousUnits?: number
  unitGrowth?: number
  currentSales?: number
  previousSales?: number
  salesGrowth?: number
}

export interface BusinessOverview {
  id?: number
  shopId?: number | string
  reportDate?: string
  totalSales?: number | null
  totalOrders?: number | null
  totalUnits?: number | null
  avgOrderValue?: number | null
  totalCost?: number | null
  totalAdSpend?: number | null
  totalFees?: number | null
  netProfit?: number | null
  profitMargin?: number | null
  totalRefunds?: number | null
  refundRate?: number | null
  newReviews?: number | null
  avgRating?: number | null
  negativeReviews?: number | null
  customerMessages?: number | null
}

export interface ShopDashboard {
  shopId?: number | string
  reportDate?: string
  kpi?: {
    totalSales7d?: number
    totalProfit7d?: number
    totalOrders7d?: number
    totalAdSpend7d?: number
    profitMargin7d?: number
  }
  salesTrend30d?: Array<{
    date: string
    netUnits?: number
    netSales?: number
    sessions?: number
    conversionRate?: number
  }>
  profitSummary30d?: ProfitSummaryReport
  deadStockAnalysis?: DeadStockReport
}

export interface ProfitSnapshot {
  id?: number
  shopId?: number | string
  sku?: string
  asin?: string
  statTime?: string
  salesAmount?: number | null
  salesQuantity?: number | null
  productCost?: number | null
  fbaFees?: number | null
  referralFee?: number | null
  advertisingCost?: number | null
  storageFee?: number | null
  headhaulCost?: number | null
  /** 后端当前写死 0（占位），不是算出来的 */
  vatCost?: number | null
  /** 后端从不赋值：ProfitDetail 无退款字段，因此快照利润不含退款 */
  refundCost?: number | null
  /** 后端当前写死 0（占位） */
  otherCost?: number | null
  grossProfit?: number | null
  netProfit?: number | null
  margin?: number | null
  dataSource?: string
  createTime?: string
}

export interface ProfitTrend {
  shopId?: number | string
  sku?: string
  hours?: number
  snapshotCount?: number
  trendTruncated?: boolean
  totalSales?: number
  totalNetProfit?: number
  trendData?: Array<{
    time: string
    sales?: number
    grossProfit?: number
    netProfit?: number
    margin?: number
  }>
}

export interface RealtimeProfitSummary {
  shopId?: number | string
  totalSales?: number
  totalNetProfit?: number
  overallMargin?: number
  skuCount?: number
  skuSummaries?: Array<{
    sku?: string
    asin?: string
    sales?: number
    netProfit?: number
    margin?: number
    snapshotCount?: number
  }>
}

export interface CostAllocation {
  id?: number
  shopId?: number | string
  costType?: string
  sourceRef?: string
  sourceDesc?: string
  totalAmount?: number | null
  currency?: string
  allocMethod?: string
  /** 后端存的是 JSON 文本，需要调用方自己 parse */
  allocDetails?: string | null
  allocDate?: string
  createTime?: string
}

/** 快照口径缺口：后端把这三项写成常量，页面据此提示利润被高估。 */
export const SNAPSHOT_PLACEHOLDER_FIELDS = ['vatCost', 'refundCost', 'otherCost'] as const

const params = (q: Record<string, unknown>) => {
  const out: Record<string, unknown> = {}
  Object.entries(q).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = v
  })
  return out
}

/* ==================== /report/v2 ==================== */

export const listProfitDetails = (shopId: number | string,
                                  q: { asin?: string; startDate?: string; endDate?: string } = {}) =>
  request.get<void, ApiResponse<ProfitDetail[]>>(`/report/v2/profit/list/${shopId}`, { params: params(q) })

export const profitSummary = (shopId: number | string, q: { startDate?: string; endDate?: string } = {}) =>
  request.get<void, ApiResponse<ProfitSummaryReport>>(`/report/v2/profit/summary/${shopId}`, { params: params(q) })

export const listTurnover = (shopId: number | string, asin?: string) =>
  request.get<void, ApiResponse<InventoryTurnover[]>>(`/report/v2/inventory-turnover/list/${shopId}`,
    { params: params({ asin }) })

export const deadStock = (shopId: number | string) =>
  request.get<void, ApiResponse<DeadStockReport>>(`/report/v2/inventory-turnover/dead-stock/${shopId}`)

export const listSalesDaily = (shopId: number | string,
                              q: { asin?: string; startDate?: string; endDate?: string } = {}) =>
  request.get<void, ApiResponse<SalesDaily[]>>(`/report/v2/sales-daily/list/${shopId}`, { params: params(q) })

export const salesComparison = (shopId: number | string, q: { asin?: string; currentDate?: string; compareDays?: number } = {}) =>
  request.get<void, ApiResponse<SalesComparison>>(`/report/v2/sales-daily/comparison/${shopId}`, { params: params(q) })

export const listBusinessOverview = (shopId: number | string, q: { startDate?: string; endDate?: string } = {}) =>
  request.get<void, ApiResponse<BusinessOverview[]>>(`/report/v2/business-overview/list/${shopId}`, { params: params(q) })

export const shopDashboard = (shopId: number | string) =>
  request.get<void, ApiResponse<ShopDashboard>>(`/report/v2/dashboard/${shopId}`)

/* ==================== /report/profit（实时） ==================== */

export const listSnapshots = (shopId: number | string,
                              q: { sku?: string; startTime?: string; endTime?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<ProfitSnapshot[]>>(`/report/profit/snapshot/list/${shopId}`, { params: params(q) })

/** 按 SKU+ASIN 现算一条快照（参数式 POST，不接收 body）：这是唯一放进 UI 的写端点。 */
export const buildSnapshot = (shopId: number | string, sku: string, asin?: string) =>
  request.post<void, ApiResponse<ProfitSnapshot>>('/report/profit/snapshot', null, { params: params({ shopId, sku, asin }) })

export const profitTrend = (shopId: number | string, sku: string, hours?: number) =>
  request.get<void, ApiResponse<ProfitTrend>>(`/report/profit/trend/${shopId}`, { params: params({ sku, hours }) })

export const realtimeSummary = (shopId: number | string, q: { startTime?: string; endTime?: string } = {}) =>
  request.get<void, ApiResponse<RealtimeProfitSummary>>(`/report/profit/summary/${shopId}`, { params: params(q) })

export const listAllocations = (shopId: number | string,
                                q: { costType?: string; startDate?: string; endDate?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<CostAllocation[]>>(`/report/profit/allocation/list/${shopId}`, { params: params(q) })

/**
 * 成本分摊入账。
 *
 * entries 每项是 `SKU` 或 `SKU:金额`：带金额就按给定金额入账（后端校验合计必须等于 totalAmount，
 * 不一致直接 400，不静默补差），全不带则由后端均摊。
 * sourceRef 给了就幂等（同来源不重复入账）；currency 必须显式给，后端不猜。
 */
export const allocateCost = (
  shopId: number | string,
  costType: string,
  totalAmount: number,
  entries: string[],
  opts: { sourceRef?: string; currency?: string } = {}
) =>
  request.post<void, ApiResponse<Record<string, number>>>(`/report/profit/allocate/${shopId}`, entries,
    { params: params({ costType, totalAmount, sourceRef: opts.sourceRef, currency: opts.currency }) })
