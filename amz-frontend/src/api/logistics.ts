import request from './auth'
import type { ApiResponse } from './types'

// ============================================================
// 类型定义（与后端 DTO 字段对齐，命名保持 camelCase）
// ============================================================

/** 看板概览 */
export interface DashboardOverview {
  totalShipments: number
  /** 各状态货件数，后端已补齐全部状态（无数据为 0），前端无需兜底 */
  statusCounts: Record<string, number>
  activeShipments: number
  delayed: number
  exception: number
  arrivingIn7Days: number
  /** ETA 已过但状态未标延误 —— 延误重判任务可能尚未执行 */
  etaOverdue: number
  /** 在途但缺运单号，无法自动取数 */
  missingTrackingNo: number
  /** 在途但取数时间超期，自动同步可能未覆盖 */
  staleShipments: number
  staleThresholdHours: number
  /** 平均头程时效（天）；无样本时为 null（不是 0） */
  avgTransitDays: number | null
  transitStatWindowDays: number
  lastTrackTime: string | null
  /** 第三方自动取数当前是否可用，用于解释「为什么没有自动更新」 */
  autoSyncAvailable: boolean
  dataSourceCounts: Record<string, number>
}

/** 趋势单日数据点 */
export interface TrendPoint {
  date: string
  created: number
  delivered: number
}

/** 承运商时效表现 */
export interface CarrierPerformance {
  carrier: string
  total: number
  active: number
  delivered: number
  delayed: number
  exception: number
  avgTransitDays: number | null
  delayedRate: number | null
  exceptionRate: number | null
}

/** 待处理提醒 */
export interface ShipmentAlert {
  type: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  shipmentId: number
  shipmentNo: string
  carrier?: string
  masterTrackingNo?: string
  status: string
  eta?: string
  daysOverdue: number | null
  lastTrackTime: string | null
  message: string
  actionHint: string
}

/** 货件 */
export interface Shipment {
  id?: number
  shipmentNo?: string
  fbaShipmentId?: string
  shopId?: number
  shippingMethod?: string
  carrier?: string
  masterTrackingNo?: string
  originPort?: string
  destinationPort?: string
  fbaWarehouseAddress?: string
  boxCount?: number
  weight?: number
  freightCost?: number
  status?: string
  eta?: string
  dataSource?: string
  lastTrackTime?: string | null
  createTime?: string
}

/** 轨迹点 */
export interface TrackingEvent {
  id?: number
  shipmentId?: number
  eventStatus?: string
  location?: string
  description?: string
  eventTime?: string
  longitude?: number
  latitude?: number
  source?: string
  rawStatus?: string
}

/** 导入结果报告 */
export interface ImportReport {
  totalRows: number
  succeededRows: number
  failedRows: number
  createdShipments: number
  updatedShipments: number
  acceptedEvents: number
  skippedEvents: number
  statusChangedShipments: number
  /** 未匹配到货件的运单标识，需提示使用者补录货件主单 */
  unmatched: string[]
  errors: string[]
  errorsTruncated: boolean
}

/** 待导入的货件行（字段与后端 ImportShipmentDTO 对齐） */
export interface ImportShipmentRow {
  shipmentNo: string
  fbaShipmentId?: string
  shippingMethod?: string
  carrier?: string
  masterTrackingNo?: string
  originPort?: string
  destinationPort?: string
  fbaWarehouseAddress?: string
  boxCount?: number
  weight?: number
  freightCost?: number
  eta?: string
  status?: string
  dataSource?: string
}

/** 待导入的轨迹点（字段与后端 ImportTrackingEventDTO 对齐） */
export interface ImportTrackingEventRow {
  status?: string
  statusCode?: string
  location?: string
  description?: string
  eventTime?: string
  longitude?: number
  latitude?: number
}

/** 待导入的运单轨迹（字段与后端 ImportTrackingDTO 对齐） */
export interface ImportTrackingRow {
  shipmentNo?: string
  trackingNo?: string
  events: ImportTrackingEventRow[]
}

// ============================================================
// 运营子域：比价 / 调拨 / 头程成本 / 签收差异
// ============================================================

/** 单条航线的报价覆盖度 */
export interface RouteCoverage {
  originPort: string
  destinationPort: string
  carrierCount: number
  carriers: string[]
  /** -1 表示该航线均未填写运输天数 */
  minTransitDays: number
  maxTransitDays: number
  minPricePerKg: number | null
  maxPricePerKg: number | null
  minPricePerCbm: number | null
  maxPricePerCbm: number | null
  /** 统一币种；多币种时为 MIXED，此时价格区间仅供参考 */
  currency: string
  /** 承运商不足两家：无议价空间 */
  singleSource: boolean
  /** 最高价相对最低价的溢价率；多币种或最低价缺失时为 null */
  priceSpreadRate: number | null
}

/** 报价看板 */
export interface QuoteBoard {
  shopId?: number
  totalQuotes: number
  validQuotes: number
  /** 已过失效日期但状态仍为 ACTIVE，比价时会自动排除 */
  staleByDate: number
  expiringIn30Days: number
  inactiveQuotes: number
  /** 未填任何单价，无法参与比价 */
  unpricedQuotes: number
  carrierCount: number
  routeCount: number
  byServiceType: Record<string, number>
  byCurrency: Record<string, number>
  routes: RouteCoverage[]
  warnings: string[]
}

/** 调拨卡单待跟进项 */
export interface TransferRisk {
  id: number
  transferNo: string
  fromWarehouseId: number
  toWarehouseId: number
  asin: string
  sku: string
  quantity: number
  carrier?: string
  trackingNo?: string
  shippingCost?: number
  status: string
  /** 建单至今的天数；时间缺失时为 null */
  daysSinceCreated: number | null
  updateTime?: string | null
  type: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  message: string
  actionHint: string
}

/** 调拨看板 */
export interface TransferBoard {
  shopId?: number
  total: number
  byStatus: Record<string, number>
  pendingApproval: number
  approvedNotShipped: number
  inTransit: number
  received: number
  cancelled: number
  totalShippingCost: number
  staleInTransit: number
  staleThresholdDays: number
  risks: TransferRisk[]
  warnings: string[]
}

/** 未核算头程成本的货件 */
export interface UncoveredShipment {
  shipmentId: number
  shipmentNo?: string
  carrier?: string
  status?: string
  eta?: string
  freightCost?: number
  /** 主单已登记运费却没做分摊：是「有费用未摊」而不是「这笔没费用」 */
  hasDeclaredFreight: boolean
}

/** 单件头程成本明细 */
export interface FreightCostItem {
  shipmentId?: number
  shipmentNo?: string
  asin?: string
  sku?: string
  quantity?: number
  totalCost?: number
  unitCost?: number
  allocationMethod?: string
}

/** 头程成本看板 */
export interface FreightCostBoard {
  shopId?: number
  totalAllocationRows: number
  totalQuantity: number
  coveredShipments: number
  uncoveredShipments: number
  /** 无货件时为 null（不是 0） */
  coverageRate: number | null
  totalFreight: number
  totalDuty: number
  totalInsurance: number
  totalOther: number
  totalCost: number
  /** 总数量为 0 时为 null */
  avgUnitCost: number | null
  methodMix: Record<string, number>
  uncoveredList: UncoveredShipment[]
  topUnitCostItems: FreightCostItem[]
  warnings: string[]
}

/** 按 ASIN 归并的签收差异 */
export interface AsinShortage {
  asin: string
  sku?: string
  shipmentCount: number
  expected: number
  received: number
  shortageUnits: number
  overreceivedUnits: number
  netDifference: number
}

/** 待处理签收差异 */
export interface ReceiptDiscrepancyItem {
  id: number
  shipmentId?: number
  shipmentNo?: string | null
  asin?: string
  sku?: string
  expectedQuantity?: number
  receivedQuantity?: number
  difference?: number
  discrepancyType?: string
  status: string
  createTime?: string | null
}

/** 签收差异看板 */
export interface ReceiptBoard {
  shopId?: number
  total: number
  pending: number
  investigating: number
  resolved: number
  /** 对账无差异（difference = 0），不计入差异统计 */
  matched: number
  totalExpected: number
  totalReceived: number
  totalDifference: number
  shortageRows: number
  overreceivedRows: number
  shortageUnits: number
  overreceivedUnits: number
  /** 未结案的少收件数 —— 还在等处理的差额 */
  openShortageUnits: number
  discrepancyRate: number | null
  topShortageAsins: AsinShortage[]
  pendingItems: ReceiptDiscrepancyItem[]
  warnings: string[]
}

/** 物流商报价 */
export interface CarrierQuote {
  id?: number
  shopId?: number
  carrierName?: string
  serviceType?: string
  originPort?: string
  destinationPort?: string
  transitDays?: number
  pricePerKg?: number
  pricePerCbm?: number
  minCharge?: number
  fuelSurchargeRate?: number
  currency?: string
  effectiveDate?: string
  expiryDate?: string
  status?: string
}

/** 比价结果中的单条承运商报价 */
export interface QuoteComparison {
  quoteId: number
  carrierName: string
  serviceType?: string
  transitDays?: number
  currency: string
  expiryDate?: string
  /** WEIGHT / VOLUME / NO_PRICING_DATA：实际按哪个口径计的费 */
  chargeableBasis: string
  costByWeight: number | null
  costByVolume: number | null
  minChargeApplied: boolean
  fuelSurchargeRate?: number
  freightCost: number
  fuelSurcharge: number
  totalCost: number
}

/** 比价结果 */
export interface QuoteCompareResult {
  shopId: number
  originPort?: string
  destinationPort?: string
  weightKg?: number
  volumeCbm?: number
  billingBasis: string
  quotes: QuoteComparison[]
  byCurrency: Record<string, QuoteComparison[]>
  recommendedByCurrency: Record<string, string>
  /** 仅当航线只有一种币种时给出；多币种时为 null */
  recommended: string | null
  excludedExpiredCount: number
  warnings: string[]
}

/** 库存调拨单 */
export interface InventoryTransfer {
  id?: number
  shopId?: number
  transferNo?: string
  fromWarehouseId?: number
  toWarehouseId?: number
  asin?: string
  sku?: string
  quantity?: number
  carrier?: string
  trackingNo?: string
  shippingCost?: number
  status?: string
  remark?: string
  createTime?: string | null
  updateTime?: string | null
}

/** 签收差异记录 */
export interface FbaReceiptDiscrepancy {
  id?: number
  shopId?: number
  shipmentId?: number
  asin?: string
  sku?: string
  expectedQuantity?: number
  receivedQuantity?: number
  difference?: number
  discrepancyType?: string
  status?: string
  resolution?: string
  createTime?: string | null
  updateTime?: string | null
}

// ============================================================
// 看板聚合
// ============================================================

export const getLogisticsOverview = (shopId: number | string) => {
  return request.get<void, ApiResponse<DashboardOverview>>('/logistics/dashboard/overview', {
    params: { shopId }
  })
}

export const getLogisticsTrend = (shopId: number | string, days = 30) => {
  return request.get<void, ApiResponse<TrendPoint[]>>('/logistics/dashboard/trend', {
    params: { shopId, days }
  })
}

export const getCarrierPerformance = (shopId: number | string) => {
  return request.get<void, ApiResponse<CarrierPerformance[]>>(
    '/logistics/dashboard/carrier-performance',
    { params: { shopId } }
  )
}

export const getLogisticsAlerts = (shopId: number | string) => {
  return request.get<void, ApiResponse<ShipmentAlert[]>>('/logistics/dashboard/alerts', {
    params: { shopId }
  })
}

// ============================================================
// 货件与轨迹
// ============================================================

export const listShipments = (shopId: number | string, status?: string) => {
  return request.get<void, ApiResponse<Shipment[]>>(`/logistics/shipment/list/${shopId}`, {
    params: { status }
  })
}

/** 手动触发一次轨迹同步（拉取承运商最新轨迹） */
export const syncShipment = (shipmentId: number) => {
  return request.post<void, ApiResponse<Shipment>>(`/logistics/shipment/${shipmentId}/sync`)
}

export const getShipmentTracking = (shipmentId: number) => {
  return request.get<void, ApiResponse<TrackingEvent[]>>(
    `/logistics/shipment/${shipmentId}/tracking`
  )
}

/** 手工关闭货件（补齐状态机终点，幂等） */
export const closeShipment = (shipmentId: number, shopId: number | string) => {
  return request.post<void, ApiResponse<Shipment>>(`/logistics/shipment/${shipmentId}/close`, null, {
    params: { shopId }
  })
}

// ============================================================
// 外部导入（取数入口 A）
// ============================================================

/** 导入货件主单：按货件编号 upsert，可安全重复导入 */
export const importShipments = (shopId: number | string, rows: ImportShipmentRow[]) => {
  return request.post<void, ApiResponse<ImportReport>>('/logistics/import/shipment', rows, {
    params: { shopId }
  })
}

/** 导入运单轨迹：已存在的轨迹点会记入 skippedEvents，可重复提交全量历史 */
export const importTracking = (shopId: number | string, rows: ImportTrackingRow[]) => {
  return request.post<void, ApiResponse<ImportReport>>('/logistics/import/tracking', rows, {
    params: { shopId }
  })
}

// ============================================================
// 运营子域看板：比价 / 调拨 / 头程成本 / 签收差异
// ============================================================

export const getQuoteBoard = (shopId: number | string) => {
  return request.get<void, ApiResponse<QuoteBoard>>('/logistics/dashboard/quotes', { params: { shopId } })
}

export const getTransferBoard = (shopId: number | string) => {
  return request.get<void, ApiResponse<TransferBoard>>('/logistics/dashboard/transfers', { params: { shopId } })
}

export const getFreightCostBoard = (shopId: number | string) => {
  return request.get<void, ApiResponse<FreightCostBoard>>('/logistics/dashboard/freight-cost', {
    params: { shopId }
  })
}

export const getReceiptBoard = (shopId: number | string) => {
  return request.get<void, ApiResponse<ReceiptBoard>>('/logistics/dashboard/receipts', { params: { shopId } })
}

// ============================================================
// 比价
// ============================================================

/** 列出当前有效的报价（已过失效日期的不返回） */
export const listQuotes = (shopId: number | string, serviceType?: string) => {
  return request.get<void, ApiResponse<CarrierQuote[]>>(`/logistics/v2/quote/list/${shopId}`, {
    params: { serviceType }
  })
}

/** 运费比价：重量与体积至少传一个，服务端按两者取高计费 */
export const compareQuotes = (
  shopId: number | string,
  params: { originPort: string; destinationPort: string; weightKg?: number; volumeCbm?: number }
) => {
  return request.get<void, ApiResponse<QuoteCompareResult>>(`/logistics/v2/quote/compare/${shopId}`, {
    params
  })
}

/** 把已过失效日期但状态仍为 ACTIVE 的报价批量置为 EXPIRED */
export const expireOutdatedQuotes = (shopId: number | string) => {
  return request.post<void, ApiResponse<number>>(`/logistics/v2/quote/expire-outdated/${shopId}`)
}

// ============================================================
// 调拨
// ============================================================

export const listTransfers = (shopId: number | string, status?: string) => {
  return request.get<void, ApiResponse<InventoryTransfer[]>>(`/logistics/v2/transfer/list/${shopId}`, {
    params: { status }
  })
}

export const approveTransfer = (id: number, approved: boolean) => {
  return request.post<void, ApiResponse<InventoryTransfer>>(`/logistics/v2/transfer/${id}/approve`, null, {
    params: { approved }
  })
}

export const shipTransfer = (id: number, carrier: string, trackingNo: string) => {
  return request.post<void, ApiResponse<InventoryTransfer>>(`/logistics/v2/transfer/${id}/ship`, null, {
    params: { carrier, trackingNo }
  })
}

export const receiveTransfer = (id: number) => {
  return request.post<void, ApiResponse<InventoryTransfer>>(`/logistics/v2/transfer/${id}/receive`)
}

// ============================================================
// 头程成本分摊
// ============================================================

export const listAllocations = (shipmentId: number) => {
  return request.get<void, ApiResponse<FreightCostItem[]>>(`/logistics/v2/freight/${shipmentId}`)
}

/** 按分摊方法重算头程费用；服务端保证各行之和等于费用总额 */
export const calculateFreight = (
  shipmentId: number,
  params: { method: string; totalFreight: number; totalDuty?: number; totalInsurance?: number }
) => {
  return request.post<void, ApiResponse<Record<string, unknown>>>(
    `/logistics/v2/freight/${shipmentId}/calculate`,
    null,
    { params }
  )
}

// ============================================================
// 签收差异
// ============================================================

export const listDiscrepancies = (shopId: number | string, status?: string) => {
  return request.get<void, ApiResponse<FbaReceiptDiscrepancy[]>>(
    `/logistics/v2/discrepancy/list/${shopId}`,
    { params: { status } }
  )
}

export const investigateDiscrepancy = (id: number) => {
  return request.post<void, ApiResponse<FbaReceiptDiscrepancy>>(
    `/logistics/v2/discrepancy/${id}/investigate`
  )
}

/** 结案必须填写处理结果，服务端会拒绝空结论 */
export const resolveDiscrepancy = (id: number, resolution: string) => {
  return request.post<void, ApiResponse<FbaReceiptDiscrepancy>>(
    `/logistics/v2/discrepancy/${id}/resolve`,
    null,
    { params: { resolution } }
  )
}
