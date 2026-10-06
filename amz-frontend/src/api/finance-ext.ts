import request from './auth'
import type { ApiResponse } from './types'
import { params } from '@/utils/query'

/**
 * 财务运营接口层（amz-service-finance，端口 8093）——回款 / 结算 / 费用差异 / 索赔 / VAT / 单品利润 / 凭证补数。
 *
 * 动因（2026-10-02 功能覆盖清点）：finance 25 个端点里前端只调了 4 个（凭证明细、金蝶同步、利润合计），
 * 剩下的回款对账、结算原表、费用差异、亚马逊索赔、VAT 月结、SKU 级利润全部没有入口。
 * 这些恰恰是「钱到底对不对」的那一环：差异不登记就没人追，索赔不提交就是白丢。
 *
 * 约定与 api/procurement.ts 相同：
 * - 列表保留后端 `_page`，截断必须由页面提示，不能把首页当全量；
 * - 失败不兜底成页面常量，由页面显示错误态。
 */

/* ==================== 回款 ==================== */

export interface PaymentCollection {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  currency?: string
  receivable?: number | string | null
  feeDeducted?: number | string | null
  refunded?: number | string | null
  reimbursed?: number | string | null
  netReceived?: number | string | null
  shortfall?: number | string | null
  depositDate?: string | null
  status?: string
  lastCalculatedAt?: string
}

export interface PaymentCollectionSummary {
  shopId?: number | string
  totalOrders?: number
  pendingOrders?: number
  inTransitOrders?: number
  settledOrders?: number
  refundedOrders?: number
  shortfallOrders?: number
  receivableTotal?: number | string | null
  netReceivedTotal?: number | string | null
  inTransitAmount?: number | string | null
  settledAmount?: number | string | null
  shortfallAmount?: number | string | null
  currencies?: string[]
  /** 后端口径提示（例如存在无法归类的交易类型）；页面必须显示，不能吞掉 */
  warnings?: string[]
}

export const COLLECTION_STATUS = ['PENDING', 'IN_TRANSIT', 'SETTLED', 'REFUNDED', 'SHORTFALL']

export const listCollections = (shopId: number | string, q: { status?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<PaymentCollection[]>>(`/finance/collection/list/${shopId}`, { params: params(q) })

export const collectionSummary = (shopId: number | string) =>
  request.get<void, ApiResponse<PaymentCollectionSummary>>(`/finance/collection/summary/${shopId}`)

/** 重算回款：从结算原表重新聚合，返回受影响行数。这是唯一能让回款跟上补录结算的入口。 */
export const rebuildCollections = (shopId: number | string) =>
  request.post<void, ApiResponse<number>>('/finance/collection/rebuild', null, { params: { shopId } })

/* ==================== 结算原表 ==================== */

export interface SettlementDetail {
  id?: number
  shopId?: number | string
  settlementId?: string
  amazonOrderId?: string
  sku?: string
  transactionType?: string
  amountType?: string
  amount?: number | string | null
  currency?: string
  depositDate?: string | null
  rowKey?: string
  source?: string
}

export interface SettlementIngestReport {
  shopId?: number | string
  reportType?: string
  reportId?: string
  reportStatus?: string
  dataLineCount?: number
  inserted?: number
  skipped?: number
  failed?: number
  sumAmount?: number | string | null
  currencies?: string[]
  rowErrors?: Array<{ rowKey?: string; message?: string }>
}

export const listSettlements = (shopId: number | string,
                                q: { amazonOrderId?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<SettlementDetail[]>>(`/finance/settlement/list/${shopId}`, { params: params(q) })

/** 从 SP-API 拉结算报表并入库；返回「读了多少行 / 入了几行 / 跳过几行」三个分开的数。 */
export const syncSettlement = (shopId: number | string, q: { marketplaceId?: string; dataStartTime?: string; dataEndTime?: string } = {}) =>
  request.post<void, ApiResponse<SettlementIngestReport>>('/finance/settlement/sync', null,
    { params: params({ shopId, ...q }) })

/* ==================== 费用差异 ==================== */

export interface FeeDiscrepancy {
  id?: number
  shopId?: number | string
  sku?: string
  shipmentId?: string
  discrepancyType?: string
  expectedAmount?: number | string | null
  actualAmount?: number | string | null
  difference?: number | string | null
  currency?: string
  evidence?: string
  status?: string
  detectedAt?: string
}

export interface FeeDiscrepancyScanReport {
  shopId?: number | string
  scannedSkus?: number
  scannedSettlementRows?: number
  created?: number
  skippedExisting?: number
  skippedNoEstimate?: number
  unattributedRows?: number
  byType?: Record<string, number>
  claimableAmount?: number | string | null
  toleranceAmount?: number | string | null
  candidates?: FeeDiscrepancy[]
  warnings?: string[]
}

export interface InboundShortageRequest {
  sku: string
  shipmentId: string
  shortageUnits: number
  unitAmount?: number
  currency?: string
  note?: string
}

export const DISCREPANCY_STATUS = ['OPEN', 'CLAIMED', 'DISMISSED']

export const listDiscrepancies = (shopId: number | string,
                                  q: { status?: string; type?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<FeeDiscrepancy[]>>(`/finance/discrepancy/list/${shopId}`, { params: params(q) })

/** 扫描：把结算行与预估费用对不上的差额登记成差异。 */
export const scanDiscrepancies = (shopId: number | string, marketplaceId?: string) =>
  request.post<void, ApiResponse<FeeDiscrepancyScanReport>>('/finance/discrepancy/scan', null,
    { params: params({ shopId, marketplaceId }) })

/** 入库短收登记成差异（采购域 FBA 签收只记日志、不自动流过来的那条链路的入口）。 */
export const intakeInboundShortage = (shopId: number | string, body: InboundShortageRequest) =>
  request.post<void, ApiResponse<number>>('/finance/discrepancy/inbound-shortage', body, { params: { shopId } })

export const discrepancyDetail = (id: number, shopId: number | string) =>
  request.get<void, ApiResponse<FeeDiscrepancy>>(`/finance/discrepancy/${id}`, { params: { shopId } })

export const dismissDiscrepancy = (id: number, shopId: number | string) =>
  request.post<void, ApiResponse<boolean>>(`/finance/discrepancy/${id}/dismiss`, null, { params: { shopId } })

/* ==================== 亚马逊索赔 ==================== */

export interface ReimbursementClaim {
  id?: number
  shopId?: number | string
  claimNo?: string
  discrepancyId?: number | null
  discrepancyType?: string
  sku?: string
  shipmentId?: string
  claimReason?: string
  claimAmount?: number | string | null
  reimbursedAmount?: number | string | null
  currency?: string
  status?: string
  submittedAt?: string
  acceptedAt?: string
  settledAt?: string
  rejectReason?: string | null
  voucherId?: number | null
}

export interface ReimbursementClaimSummary {
  shopId?: number | string
  total?: number
  candidate?: number
  submitted?: number
  accepted?: number
  reimbursed?: number
  rejected?: number
  claimAmountTotal?: number | string | null
  reimbursedAmountTotal?: number | string | null
  inFlightAmount?: number | string | null
  successRate?: number | string | null
  avgSettlementDays?: number | string | null
  byType?: Record<string, { claims?: number; reimbursed?: number; claimAmount?: number | string; reimbursedAmount?: number | string }>
  warnings?: string[]
}

export interface ReimbursementReconcileReport {
  shopId?: number | string
  matched?: number
  platformAdjustmentCount?: number
  platformTotal?: number | string | null
  systemTotal?: number | string | null
  difference?: number | string | null
  platformOnly?: Array<{ reference?: string; sku?: string; amount?: number | string; currency?: string; postedAt?: string }>
  systemOnly?: Array<{ reference?: string; sku?: string; amount?: number | string; currency?: string; postedAt?: string }>
  warnings?: string[]
}

export const CLAIM_STATUS = ['CANDIDATE', 'SUBMITTED', 'ACCEPTED', 'REIMBURSED', 'REJECTED']

export const listClaims = (shopId: number | string, q: { status?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<ReimbursementClaim[]>>(`/finance/claim/list/${shopId}`, { params: params(q) })

export const claimSummary = (shopId: number | string) =>
  request.get<void, ApiResponse<ReimbursementClaimSummary>>(`/finance/claim/summary/${shopId}`)

/** 平台 Adjustment 与系统索赔单对账：两个方向都列出来，谁漏了看得到。 */
export const reconcileClaims = (shopId: number | string) =>
  request.get<void, ApiResponse<ReimbursementReconcileReport>>(`/finance/claim/reconcile/${shopId}`)

export const createClaimFromDiscrepancy = (shopId: number | string, discrepancyId: number) =>
  request.post<void, ApiResponse<number>>('/finance/claim/from-discrepancy', null,
    { params: { shopId, discrepancyId } })

export const submitClaim = (id: number, shopId: number | string) =>
  request.post<void, ApiResponse<ReimbursementClaim>>(`/finance/claim/${id}/submit`, null, { params: { shopId } })

export const acceptClaim = (id: number, shopId: number | string) =>
  request.post<void, ApiResponse<ReimbursementClaim>>(`/finance/claim/${id}/accept`, null, { params: { shopId } })

export const reimburseClaim = (id: number, shopId: number | string, reimbursedAmount: number) =>
  request.post<void, ApiResponse<ReimbursementClaim>>(`/finance/claim/${id}/reimburse`, null,
    { params: { shopId, reimbursedAmount } })

export const rejectClaim = (id: number, shopId: number | string, reason?: string) =>
  request.post<void, ApiResponse<ReimbursementClaim>>(`/finance/claim/${id}/reject`, null, { params: params({ shopId, reason }) })

/* ==================== SKU 级利润 ==================== */

export interface SkuProfit {
  sku?: string
  unitsSold?: number
  revenue?: number | string | null
  commission?: number | string | null
  fulfillmentFee?: number | string | null
  storageFee?: number | string | null
  otherPlatformFees?: number | string | null
  refunded?: number | string | null
  reimbursed?: number | string | null
  cogs?: number | string | null
  profit?: number | string | null
  profitPerUnit?: number | string | null
  marginRate?: number | string | null
  avgUnitCost?: number | string | null
  /** 采购成本取不到（批次缺失或采购域降级）：利润不是确定值，页面必须标出来 */
  costMissing?: boolean
  profitIsComplete?: boolean
  dataNotes?: string[]
}

export interface SkuProfitReport {
  shopId?: number | string
  depositAfter?: string | null
  depositBefore?: string | null
  skuCount?: number
  entries?: SkuProfit[]
  totals?: SkuProfit
  costDataComplete?: boolean
  incompleteSkuCount?: number
  warnings?: string[]
}

export const skuProfit = (shopId: number | string, q: { depositAfter?: string; depositBefore?: string; sku?: string } = {}) =>
  request.get<void, ApiResponse<SkuProfitReport>>(`/finance/profit/sku/${shopId}`, { params: params(q) })

/* ==================== VAT ==================== */

/**
 * VAT 三个查询端点后端返回的是给人看的字符串（"VAT: 19.00 (rate: 0.19)"、"EXCEEDED"），
 * 这里原样透出、页面原样显示。不在前端正则解析成数字：那样会把后端口径变成隐式契约。
 */
export const calculateVat = (amount: number, country: string) =>
  request.get<void, ApiResponse<string>>('/finance/vat/calculate', { params: { amount, country } })

export const vatRate = (country: string) =>
  request.get<void, ApiResponse<string>>(`/finance/vat/rate/${encodeURIComponent(country)}`)

export const vatThreshold = (country: string, annualSales: number) =>
  request.get<void, ApiResponse<string>>('/finance/vat/threshold-check', { params: { country, annualSales } })

export const vatMonthlyClose = (shopId: number | string, year: number, month: number) =>
  request.post<void, ApiResponse<Record<string, unknown>>>(`/finance/vat/monthly-close/${shopId}/${year}/${month}`)

/* ==================== 凭证补数（三类成本来源） ==================== */

export interface SettlementVoucherReport {
  shopId?: number | string
  scanned?: number
  feeVouchers?: number
  refundVouchers?: number
  existing?: number
  skippedByKind?: number
  skippedNoCurrency?: number
  skippedZeroAmount?: number
  originalAmountSum?: number | string | null
  /** true = 命中扫描上限，仍有结算行没处理，需要再跑一次 */
  capped?: boolean
}

export interface ProcurementVoucherReport {
  shopId?: number | string
  scanned?: number
  generated?: number
  existing?: number
  skippedZeroAmount?: number
  skippedNoOrderNo?: number
  pagesRead?: number
  capped?: boolean
  /** true = 采购域没读到数据；此时 generated 不代表完整结果 */
  remoteDegraded?: boolean
  remoteMessage?: string | null
  originalAmountSum?: number | string | null
}

export const generateSettlementVouchers = (shopId: number | string) =>
  request.post<void, ApiResponse<SettlementVoucherReport>>('/finance/voucher/from-settlement', null, { params: { shopId } })

export const generateProcurementVouchers = (shopId: number | string) =>
  request.post<void, ApiResponse<ProcurementVoucherReport>>('/finance/voucher/procurement', null, { params: { shopId } })

/* ==================== 内部：query 清洗 ==================== */

