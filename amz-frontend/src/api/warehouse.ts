import request from './auth'
import type { ApiResponse } from './types'

// 海外仓
export interface Warehouse {
  id?: number
  shopId: number
  warehouseName: string
  warehouseCode: string
  warehouseType?: 'FBA' | 'AWD' | 'THIRD_PARTY'
  country: string
  city?: string
  address?: string
  contactName?: string
  contactPhone?: string
  capacityCbm?: number
  usedCbm?: number
  status?: 'ACTIVE' | 'INACTIVE'
}

// 仓库库存
export interface WarehouseInventory {
  id?: number
  warehouseId: number
  shopId: number
  sku: string
  asin?: string
  quantity?: number
  reservedQuantity?: number
  availableQuantity?: number
  inboundQuantity?: number
  locationCode?: string
  batchNo?: string
  expireDate?: string
}

// 入库单
export interface InboundOrder {
  id?: number
  shopId: number
  warehouseId: number
  inboundNo?: string
  source?: 'FBA_TRANSFER' | '1688_PURCHASE' | 'OTHER'
  referenceNo?: string
  status?: 'PENDING' | 'IN_TRANSIT' | 'RECEIVED' | 'PARTIAL' | 'CANCELLED'
  totalItems?: number
  receivedItems?: number
  expectedArrival?: string
  actualArrival?: string
  remark?: string
}

// 出库单
export interface OutboundOrder {
  id?: number
  shopId: number
  warehouseId: number
  outboundNo?: string
  orderType?: 'ORDER' | 'TRANSFER' | 'RETURN' | 'SCRAP'
  referenceNo?: string
  status?: 'PENDING' | 'PICKING' | 'PACKED' | 'SHIPPED' | 'CANCELLED'
  carrier?: string
  trackingNo?: string
  totalItems?: number
  shippedItems?: number
  shipDate?: string
  remark?: string
}

// ===== 仓库 =====
export const createWarehouse = (data: Warehouse) => {
  return request.post<void, ApiResponse<Warehouse>>('/logistics/warehouse', data)
}

export const updateWarehouse = (data: Warehouse) => {
  return request.put<void, ApiResponse<Warehouse>>('/logistics/warehouse', data)
}

export const listWarehouses = (shopId: number | string, warehouseType?: string) => {
  return request.get<void, ApiResponse<Warehouse[]>>(`/logistics/warehouse/list/${shopId}`, {
    params: { warehouseType }
  })
}

// ===== 库存 =====
export const listInventory = (params: {
  warehouseId?: number
  sku?: string
  shopId?: number
  size?: number
  cursor?: string
}) => {
  return request.get<void, ApiResponse<WarehouseInventory[]>>('/logistics/warehouse/inventory', {
    params
  })
}

/**
 * 改库位码。PUT /logistics/warehouse/inventory/{inventoryId}/location?locationCode=
 * <p>
 * 后端 @RequireRole({"OPERATOR","ADMIN"}) + 按行归属校验（inventoryId 不是 shopId，
 * 切面管不到，服务里 selectById 后再判权），所以：
 * - 空串/空白会被后端拒绝，不会静默清空库位；
 * - 上限 50 字符（与 DDL 的 VARCHAR(50) 对齐），这里也挡一层，避免拿 500 回来。
 */
export const LOCATION_CODE_MAX_LENGTH = 50

export const updateInventoryLocation = (inventoryId: number, locationCode: string) => {
  return request.put<void, ApiResponse<WarehouseInventory>>(
    `/logistics/warehouse/inventory/${inventoryId}/location`,
    null,
    { params: { locationCode } }
  )
}

// ===== 入库单 =====
export const createInboundOrder = (data: InboundOrder) => {
  return request.post<void, ApiResponse<InboundOrder>>('/logistics/inbound', data)
}

export const listInboundOrders = (
  shopId: number | string,
  status?: string,
  size?: number,
  cursor?: string
) => {
  return request.get<void, ApiResponse<InboundOrder[]>>(`/logistics/inbound/list/${shopId}`, {
    params: { status, size, cursor }
  })
}

export const transitInbound = (id: number) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/transit`)
}

export const receiveInbound = (id: number, items: WarehouseInventory[]) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/receive`, items)
}

/** 批量到货的一行：入库单 + 该单的到货明细（SKU/数量/批次/库位）。 */
export interface BatchReceiveLine {
  inboundId: number
  items: WarehouseInventory[]
}

/** 批量到货的逐单结果行（后端逐单独立事务，失败不互相影响）。 */
export interface BatchReceiveReportRow {
  inboundId: number
  ok: boolean
  status?: string
  error?: string
}

/**
 * 批量到货验收：一次对多张入库单签收。
 * <p>
 * 返回**逐单报告**而不是整体成败——后端对每单单独 try/catch，一单失败
 * （状态不对 / 库存行不存在）不挡住其余单。UI 必须把每行的 ok 与 error
 * 原样显示出来，把失败行汇总成「全部成功」会让仓库漏收货。
 * <p>
 * items 不能省：后端 items==null 时只改单据状态、不增加库存。
 */
export const batchReceiveInbound = (lines: BatchReceiveLine[]) => {
  return request.post<void, ApiResponse<BatchReceiveReportRow[]>>('/logistics/inbound/batch-receive', lines)
}

export const cancelInbound = (id: number) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/cancel`)
}

// ===== 出库单 =====
export const createOutboundOrder = (data: OutboundOrder) => {
  return request.post<void, ApiResponse<OutboundOrder>>('/logistics/outbound', data)
}

export const listOutboundOrders = (
  shopId: number | string,
  status?: string,
  size?: number,
  cursor?: string
) => {
  return request.get<void, ApiResponse<OutboundOrder[]>>(`/logistics/outbound/list/${shopId}`, {
    params: { status, size, cursor }
  })
}

export const pickOutbound = (id: number) => {
  return request.post<void, ApiResponse<OutboundOrder>>(`/logistics/outbound/${id}/pick`)
}

export const packOutbound = (id: number) => {
  return request.post<void, ApiResponse<OutboundOrder>>(`/logistics/outbound/${id}/pack`)
}

export const shipOutbound = (
  id: number,
  data: { carrier?: string; trackingNo?: string; items: WarehouseInventory[] }
) => {
  return request.post<void, ApiResponse<OutboundOrder>>(
    `/logistics/outbound/${id}/ship`,
    data.items,
    { params: { carrier: data.carrier, trackingNo: data.trackingNo } }
  )
}

export const cancelOutbound = (id: number) => {
  return request.post<void, ApiResponse<OutboundOrder>>(`/logistics/outbound/${id}/cancel`)
}

/* ==================== 库龄分析（GET /logistics/warehouse/stock/aging/{shopId}） ==================== */

export interface AgingBucket {
  count: number
  /** 金额：后端是 BigDecimal，JSON 里可能是数字或字符串 */
  value: number | string
  /** 占总库存价值的比例（0–1，四位小数） */
  pct: number | string
}

export interface AgingTopRow {
  sku: string
  /** 仓库名在 DDL 里可空，缺失时后端回空串（不是 null，避免整页 500） */
  warehouse: string
  days: number
  qty: number
  value: number | string
}

/**
 * 库龄分段。两条口径要记住：
 * 1. `aging` 的键是 **snake_case**：后端返回的是 Map，Jackson 不对 Map 键做驼峰转换，
 *    前端改名就会读到 undefined；
 * 2. `days` 来自快照列 `days_in_stock`，快照滞后时该值会偏高，所以它是「按快照算的库龄」，
 *    不是实时天数。`stocksTruncated` 为真时结果只覆盖扫描上限内的行。
 */
export interface StockAging {
  shopId?: number | string
  totalSkus?: number
  scannedStockCount?: number
  stocksTruncated?: boolean
  aging: Record<'fresh_30d' | 'mid_31_90d' | 'old_91_180d' | 'dead_181d_plus', AgingBucket>
  oldestTop10: AgingTopRow[]
}

export const AGING_BUCKETS = [
  { key: 'fresh_30d', label: '≤30 天' },
  { key: 'mid_31_90d', label: '31–90 天' },
  { key: 'old_91_180d', label: '91–180 天' },
  { key: 'dead_181d_plus', label: '181 天以上' }
] as const

export const getStockAging = (shopId: number | string) =>
  request.get<void, ApiResponse<StockAging>>(`/logistics/warehouse/stock/aging/${shopId}`)

/* ==================== 库存元数据 CSV 批量维护（A6 第二个域） ==================== */

/**
 * 导入报告。刻意没有 insertedRows——库存行只能由单据产生，CSV 只更新已存在行的
 * 元数据（ASIN/库位码/过期日期）；出现数量列会被整行拒绝（改量必须走盘点/出入库，
 * 变更要进库存流水账）。
 */
export interface InventoryImportReport {
  totalRows: number
  updatedRows: number
  skippedRows: number
  failedRows: number
  /** 文件行号（1 基、含表头行）→ 原因 */
  errors: Record<string, string>
  headers: string[]
}

/**
 * CSV 批量维护库存元数据。
 * 模板必需列 warehouse_id / sku / batch_no（行定位键，batch_no 值可空=匹配无批次行）。
 */
export const importInventoryCsv = (shopId: number | string, file: File) => {
  const form = new FormData()
  form.append('shopId', String(shopId))
  form.append('file', file)
  return request.post<void, ApiResponse<InventoryImportReport>>(
    '/logistics/warehouse/inventory/import/csv', form)
}
