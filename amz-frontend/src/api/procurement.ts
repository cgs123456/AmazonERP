import request from './auth'
import type { ApiResponse } from './types'

/**
 * 采购供应链接口层（amz-service-procurement，端口 8098）。
 *
 * 动因（2026-10-02 功能覆盖清点）：procurement 33 个端点整条链后端都已实现
 * （供应商 / 计划审批流 / 1688 下单 / 质检 / FBA 货件与头程分摊 / FIFO 批次），
 * 但前端一个入口都没有——审批只能在状态机外干等，实际业务闭环断在 UI。
 *
 * 两处刻意的"不兜底"：
 * 1. 列表返回保留后端 `_page`：截断时（truncated=true）页面必须提示"还有下一页"，
 *    把第一页当全量做审批会漏单，这是采购场景里最贵的错误。
 * 2. 「提交 1688」「取消采购单」在 !mock 档会真的在 1688 下单/关单，
 *    所以页面必须二次确认，不能在加载时自动触发。
 *
 * 唯一故意没接的端点：GET /procurement/promotion/plan。它在 Controller 里直接
 * 返回写死的 Lightning Deal / 20% / "$500 广告预算"，与 shopId、asin 都无关，
 * 接进 UI 等于把模板文案显示成经营建议（属功能覆盖清点里的"造数"一类）。
 */

export interface Supplier {
  id?: number
  shopId?: number | string
  supplierName: string
  supplierCode?: string
  contactName?: string
  contactPhone?: string
  contactEmail?: string
  address?: string
  alibabaShopUrl?: string
  alibabaMemberId?: string
  paymentTerms?: string
  rating?: number | string | null
  onTimeDeliveryRate?: number | string | null
  qualityPassRate?: number | string | null
  priceCompetitiveness?: number | string | null
  responseSpeed?: number | string | null
  totalOrders?: number | null
  totalAmount?: number | string | null
  status?: string
  remark?: string
}

export interface SupplierProduct {
  id?: number
  supplierId: number
  shopId?: number | string
  sku: string
  asin?: string
  supplierOfferId?: string
  supplierSkuCode?: string
  supplyPrice?: number | string | null
  moq?: number | null
  leadTimeDays?: number | null
  packagingSpec?: string
  unitWeight?: number | string | null
  unitVolume?: number | string | null
  /** 后端是 0/1 整数（不是布尔） */
  isPreferred?: number | boolean | null
  status?: string
}

export interface PurchasePlan {
  id?: number
  planNo?: string
  shopId?: number | string
  sku: string
  asin?: string
  suggestedQty?: number | null
  plannedQty: number
  unitPrice?: number | string | null
  totalAmount?: number | string | null
  supplierId?: number | null
  urgency?: string
  source?: string
  /** 补货算法输入的 JSON 快照；当前无生产者写入，读到即为空 */
  replenishmentData?: string | null
  status?: string
  approvedBy?: string
  approvedTime?: string
  remark?: string
}

export interface PurchaseOrder {
  id?: number
  orderNo?: string
  shopId?: number | string
  supplierOfferId?: string
  supplierName?: string
  sku: string
  quantity: number
  unitPrice: number | string
  totalAmount?: number | string | null
  status?: string
  alibabaOrderNo?: string | null
  expectedDeliveryDate?: string | null
  trackingNo?: string | null
  remark?: string
}

export interface QualityCheck {
  id?: number
  purchaseOrderId?: number
  sampleCount?: number
  passedCount?: number
  failedCount?: number
  passRate?: number | string | null
  defectDescription?: string
  result?: 'PASS' | 'FAIL' | 'CONDITIONAL' | string
  inspector?: string
}

export interface FbaShipment {
  id?: number
  shopId?: number | string
  shipmentNo?: string
  fbaShipmentId?: string
  warehouseId?: number | null
  destinationFbaCode?: string
  destinationAddress?: string
  shippingMethod?: string
  carrier?: string
  masterTrackingNo?: string
  boxCount?: number | null
  totalWeight?: number | string | null
  totalVolume?: number | string | null
  freightCost?: number | string | null
  customsCost?: number | string | null
  taxCost?: number | string | null
  otherCost?: number | string | null
  totalCost?: number | string | null
  status?: string
  eta?: string | null
  actualArrival?: string | null
}

export interface FbaShipmentItem {
  id?: number
  fbaShipmentId?: number
  sku: string
  asin?: string
  quantity: number
  receivedQuantity?: number | null
  batchNo?: string | null
  unitCost?: number | string | null
  freightAllocation?: number | string | null
  customsAllocation?: number | string | null
  totalCost?: number | string | null
}

export interface InventoryBatch {
  id?: number
  shopId?: number | string
  batchNo?: string
  purchaseOrderId?: number | null
  inboundOrderId?: number | null
  shipmentItemId?: number | null
  sku?: string
  asin?: string
  warehouseId?: number | null
  quantity?: number | null
  availableQuantity?: number | null
  unitCost?: number | string | null
  freightCost?: number | string | null
  customsCost?: number | string | null
  otherCost?: number | string | null
  totalCost?: number | string | null
  inboundDate?: string
  expireDate?: string | null
  status?: string
}

export interface BatchCostSummary {
  batchCount?: number | string
  totalQuantity?: number | string
  totalBatchCost?: number | string
}

/** 比价行：后端 LinkedHashMap，键固定为这几个 */
export interface PriceCompareRow {
  supplierId: number
  supplierName: string
  supplierRating?: number | string | null
  supplyPrice?: number | string | null
  moq?: number | null
  leadTimeDays?: number | null
  isPreferred: boolean
  overallScore?: number | string | null
}

export interface SupplierKpi {
  supplierId: number
  supplierName?: string
  rating?: number | string | null
  onTimeDeliveryRate?: number | string | null
  qualityPassRate?: number | string | null
  priceCompetitiveness?: number | string | null
  responseSpeed?: number | string | null
  totalOrders?: number | null
  totalAmount?: number | string | null
  compositeScore?: number | string | null
  grade?: string
}

/** 头程费用分摊结果（allocateCosts） */
export interface AllocationResult {
  shipmentId: number
  shipmentNo?: string
  totalQuantity?: number
  totalFreight?: number | string
  totalCustoms?: number | string
  totalOther?: number | string
  totalCost?: number | string
  allocationDetails?: Array<{
    sku: string
    asin?: string
    quantity: number
    freightAllocation?: number | string
    customsAllocation?: number | string
    otherAllocation?: number | string
    totalCost?: number | string
    unitCost?: number | string
  }>
}

/** 签收结果（processReceipt）——差异只在这里暴露，页面必须显式提示有差异 */
export interface ReceiptResult {
  shipmentId: number
  shipmentNo?: string
  hasDiscrepancy?: boolean
  allReceived?: boolean
  pendingItemCount?: number
  itemResults?: Array<{
    sku: string
    expectedQty?: number
    receivedQty?: number
    discrepancy?: number
    status?: string
  }>
}

export type ListQuery = {
  status?: string
  keyword?: string
  sku?: string
  size?: number
  cursor?: string
}

/** 空串/undefined 不进 query：后端把空 keyword 当条件拼进 LIKE */
const params = (q: Record<string, unknown>) => {
  const out: Record<string, unknown> = {}
  Object.entries(q).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = v
  })
  return out
}

/* ==================== 供应商 ==================== */

export const createSupplier = (body: Partial<Supplier>) =>
  request.post<void, ApiResponse<Supplier>>('/procurement/supplier', body)

export const updateSupplier = (id: number, body: Partial<Supplier>) =>
  request.put<void, ApiResponse<Supplier>>(`/procurement/supplier/${id}`, body)

export const listSuppliers = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<Supplier[]>>(`/procurement/supplier/list/${shopId}`, { params: params(q) })

export const getSupplier = (id: number) =>
  request.get<void, ApiResponse<Supplier>>(`/procurement/supplier/${id}`)

/** status 取值见 DDL：ACTIVE / BLACKLISTED / DISABLED（后端不做枚举校验，UI 限定下拉） */
export const updateSupplierStatus = (id: number, status: string) =>
  request.post<void, ApiResponse<boolean>>(`/procurement/supplier/${id}/status`, null, { params: { status } })

export const addSupplierProduct = (body: Partial<SupplierProduct>) =>
  request.post<void, ApiResponse<SupplierProduct>>('/procurement/supplier/product', body)

export const suppliersBySku = (shopId: number | string, sku: string) =>
  request.get<void, ApiResponse<SupplierProduct[]>>(`/procurement/supplier/by-sku/${shopId}`, { params: { sku } })

export const compareSuppliers = (shopId: number | string, sku: string) =>
  request.get<void, ApiResponse<PriceCompareRow[]>>(`/procurement/supplier/compare/${shopId}`, { params: { sku } })

export const supplierKpi = (id: number) =>
  request.get<void, ApiResponse<SupplierKpi>>(`/procurement/supplier/${id}/kpi`)

/* ==================== 采购计划 ==================== */

export const createPlan = (body: Partial<PurchasePlan>) =>
  request.post<void, ApiResponse<PurchasePlan>>('/procurement/plan', body)

export const submitPlan = (planId: number) =>
  request.post<void, ApiResponse<PurchasePlan>>(`/procurement/plan/${planId}/submit`)

export const approvePlan = (planId: number, operator: string, approved: boolean, comment?: string) =>
  request.post<void, ApiResponse<PurchasePlan>>(`/procurement/plan/${planId}/approve`, null,
    { params: params({ operator, approved, comment }) })

/** 转采购单：成功返回 {planId, planNo, orderId, orderNo, status} */
export const convertPlan = (planId: number) =>
  request.post<void, ApiResponse<Record<string, unknown>>>(`/procurement/plan/${planId}/convert`)

export const listPlans = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<PurchasePlan[]>>(`/procurement/plan/list/${shopId}`, { params: params(q) })

export const cancelPlan = (planId: number) =>
  request.post<void, ApiResponse<boolean>>(`/procurement/plan/${planId}/cancel`)

/**
 * 审批留痕（amz_purchase_approval）：每一次通过/驳回写一行，含操作人与意见。
 * 计划上的 status/approvedBy/approvedTime 只存当前值，二次改批会覆盖上一次，
 * 所以「审批过程」只能从这张表读。留痕从 2026-10-03 开始写，之前审批过的计划没有记录。
 */
export interface PurchaseApproval {
  id?: number
  shopId?: number | string | null
  refType?: string
  refId?: number
  action?: string
  operator?: string
  comment?: string | null
  createTime?: string
}

export const listPlanApprovals = (planId: number) =>
  request.get<void, ApiResponse<PurchaseApproval[]>>(`/procurement/plan/${planId}/approvals`)

/* ==================== 采购单（1688） ==================== */

export const createOrder = (body: Partial<PurchaseOrder>) =>
  request.post<void, ApiResponse<PurchaseOrder>>('/procurement/order', body)

/** ⚠ 真实下单：非 mock 档会在 1688 生成真订单，失败回滚为 DRAFT 可重试 */
export const submitOrderTo1688 = (orderId: number) =>
  request.post<void, ApiResponse<PurchaseOrder>>(`/procurement/order/${orderId}/submit`)

export const syncOrderStatus = (orderId: number) =>
  request.post<void, ApiResponse<PurchaseOrder>>(`/procurement/order/${orderId}/sync`)

/** ⚠ 已提交过 1688 时会先关远程单，关不掉则本地取消中止 */
export const cancelOrder = (orderId: number) =>
  request.post<void, ApiResponse<boolean>>(`/procurement/order/${orderId}/cancel`)

export const listOrders = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<PurchaseOrder[]>>(`/procurement/order/list/${shopId}`, { params: params(q) })

/** 质检：仅 QC_PENDING 可提交；后端按合格率判 PASS/CONDITIONAL/FAIL */
export const submitQualityCheck = (purchaseOrderId: number, body: {
  sampleCount: number; failedCount: number; inspector: string; defectDescription?: string
}) =>
  request.post<void, ApiResponse<QualityCheck>>(`/procurement/qc/${purchaseOrderId}`, null, { params: params({ ...body }) })

/* ==================== FBA 货件 ==================== */

export const createShipment = (body: Partial<FbaShipment>) =>
  request.post<void, ApiResponse<FbaShipment>>('/procurement/fba/shipment', body)

export const updateShipment = (id: number, body: Partial<FbaShipment>) =>
  request.put<void, ApiResponse<FbaShipment>>(`/procurement/fba/shipment/${id}`, body)

export const addShipmentItem = (shipmentId: number, body: Partial<FbaShipmentItem>) =>
  request.post<void, ApiResponse<FbaShipmentItem>>(`/procurement/fba/shipment/${shipmentId}/item`, body)

export const listShipmentItems = (shipmentId: number, q: ListQuery = {}) =>
  request.get<void, ApiResponse<FbaShipmentItem[]>>(`/procurement/fba/shipment/${shipmentId}/items`, { params: params(q) })

export const listShipments = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<FbaShipment[]>>(`/procurement/fba/shipment/list/${shopId}`, { params: params(q) })

export const getShipment = (id: number) =>
  request.get<void, ApiResponse<FbaShipment>>(`/procurement/fba/shipment/${id}`)

export const updateShipmentStatus = (id: number, status: string) =>
  request.post<void, ApiResponse<FbaShipment>>(`/procurement/fba/shipment/${id}/status`, null, { params: { status } })

export const confirmShipment = (id: number, carrier: string, trackingNo: string) =>
  request.post<void, ApiResponse<FbaShipment>>(`/procurement/fba/shipment/${id}/ship`, null,
    { params: { carrier, trackingNo } })

/** 头程费用按数量分摊到明细，返回分摊后每行 unitCost */
export const allocateShipmentCosts = (shipmentId: number) =>
  request.post<void, ApiResponse<AllocationResult>>(`/procurement/fba/shipment/${shipmentId}/allocate`)

export const receiveShipment = (shipmentId: number, lines: Array<{ itemId: number; receivedQty: number }>) =>
  request.post<void, ApiResponse<ReceiptResult>>(`/procurement/fba/shipment/${shipmentId}/receive`, lines)

/* ==================== 入库短收（财务差异闭环的事实来源） ==================== */

/**
 * 签收数 < 发货数 的明细行。财务侧「登记入库短收」表单用它当数据来源，
 * 这样亚马逊该赔的钱不再只停在 processReceipt 的一条 warn 日志里。
 * unitCost 是我方成本口径且未记币种，所以金额与币种要人在登记时确认。
 */
export interface ReceiptShortage {
  itemId: number
  shipmentId: number
  shipmentNo?: string
  /** 亚马逊侧货件号：财务差异表认它，不认内部 shipmentId */
  fbaShipmentId?: string
  sku?: string
  asin?: string
  expectedQty?: number
  receivedQty?: number
  shortUnits?: number
  unitCost?: number | string | null
  totalCost?: number | string | null
  shipmentStatus?: string
}

export const listReceiptShortages = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<ReceiptShortage[]>>(`/procurement/fba/shipment/receipt-shortages/${shopId}`,
    { params: params(q) })

/* ==================== 库存批次 ==================== */

export const listBatches = (shopId: number | string, sku: string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<InventoryBatch[]>>(`/procurement/batch/list/${shopId}`, { params: params({ ...q, sku }) })

export const batchCostSummary = (shopId: number | string, sku: string) =>
  request.get<void, ApiResponse<BatchCostSummary>>(`/procurement/batch/cost-summary/${shopId}`, { params: { sku } })

/** FIFO 出库：扣减 availableQuantity，返回被扣的批次明细 */
export const fifoOutbound = (shopId: number | string, sku: string, quantity: number) =>
  request.post<void, ApiResponse<Array<Record<string, unknown>>>>('/procurement/batch/fifo-outbound', null,
    { params: { shopId, sku, quantity } })
