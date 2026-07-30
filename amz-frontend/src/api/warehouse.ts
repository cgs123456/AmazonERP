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
export const listInventory = (params: { warehouseId?: number; sku?: string; shopId?: number }) => {
  return request.get<void, ApiResponse<WarehouseInventory[]>>('/logistics/warehouse/inventory', {
    params
  })
}

export const updateLocationCode = (inventoryId: number, locationCode: string) => {
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

export const listInboundOrders = (shopId: number | string, status?: string) => {
  return request.get<void, ApiResponse<InboundOrder[]>>(`/logistics/inbound/list/${shopId}`, {
    params: { status }
  })
}

export const transitInbound = (id: number) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/transit`)
}

export const receiveInbound = (id: number, items: WarehouseInventory[]) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/receive`, items)
}

export const cancelInbound = (id: number) => {
  return request.post<void, ApiResponse<InboundOrder>>(`/logistics/inbound/${id}/cancel`)
}

// ===== 出库单 =====
export const createOutboundOrder = (data: OutboundOrder) => {
  return request.post<void, ApiResponse<OutboundOrder>>('/logistics/outbound', data)
}

export const listOutboundOrders = (shopId: number | string, status?: string) => {
  return request.get<void, ApiResponse<OutboundOrder[]>>(`/logistics/outbound/list/${shopId}`, {
    params: { status }
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
