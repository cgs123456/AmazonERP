import request from './auth'
import type { ApiResponse } from './types'

/**
 * 退货单（买家退货入仓）。
 * <p>
 * 后端语义：收货时确定处置（GOOD/DEFECTIVE/SCRAP），**只有 GOOD 才回补可售库存**；
 * 残次/报废只留库存流水，不进可售库存。因此前端不应把「已收货」等同于「库存已加」。
 */
export interface ReturnOrderRow {
  id?: number
  shopId?: number
  returnNo?: string
  amazonOrderId?: string
  sku?: string
  asin?: string
  warehouseId?: number
  quantity?: number
  /** GOOD / DEFECTIVE / SCRAP（收货时确定） */
  disposition?: string
  /** AMAZON_FBA / AMAZON_FBM / MANUAL */
  source?: string
  returnReason?: string
  refundAmount?: number | string
  currency?: string
  /** PENDING / RECEIVING / RECEIVED / CLOSED / CANCELLED */
  status?: string
  receivedAt?: string
  closedAt?: string
  createTime?: string
}

export const RETURN_STATUS_LABELS: Record<string, string> = {
  PENDING: '待收货',
  RECEIVING: '收货中',
  RECEIVED: '已收货',
  CLOSED: '已结案',
  CANCELLED: '已取消'
}

export const DISPOSITION_LABELS: Record<string, string> = {
  GOOD: '良品（回补可售）',
  DEFECTIVE: '残次（不回补）',
  SCRAP: '报废（不回补）'
}

export const RETURN_SOURCE_LABELS: Record<string, string> = {
  AMAZON_FBA: 'Amazon FBA',
  AMAZON_FBM: 'Amazon 自发货',
  MANUAL: '手工录入'
}

/** 退货单列表（按店铺，可选状态过滤） */
export const listReturnOrders = (shopId: number | string, status?: string) =>
  request.get<void, ApiResponse<ReturnOrderRow[]>>(`/logistics/return/list/${shopId}`, {
    params: status ? { status } : {}
  })

/** 创建退货单 */
export const createReturnOrder = (payload: {
  shopId: number | string
  sku: string
  quantity: number
  amazonOrderId?: string
  asin?: string
  source?: string
  returnReason?: string
  refundAmount?: number | string
  currency?: string
}) => request.post<void, ApiResponse<ReturnOrderRow>>('/logistics/return', payload)

/** 收货：确定处置，良品回补库存 */
export const receiveReturnOrder = (
  returnId: number,
  warehouseId: number | string,
  disposition: string
) =>
  request.post<void, ApiResponse<ReturnOrderRow>>(`/logistics/return/${returnId}/receive`, null, {
    params: { warehouseId, disposition }
  })

/** 结案（RECEIVED → CLOSED） */
export const closeReturnOrder = (returnId: number) =>
  request.post<void, ApiResponse<ReturnOrderRow>>(`/logistics/return/${returnId}/close`)

/** 取消（仅 PENDING） */
export const cancelReturnOrder = (returnId: number) =>
  request.post<void, ApiResponse<ReturnOrderRow>>(`/logistics/return/${returnId}/cancel`)

/**
 * 从 Amazon 拉取 FBA 客户退货报表并导入（幂等：重复跑不会重复建单）。
 * <p>
 * 后端走 Reports API 三段式（createReport → 轮询 → download），报表未就绪会**明确失败**
 * 而不是返回空——「没拉到」与「本期无退货」是两件事，调用方不要把它当成「没有退货」。
 * <p>
 * <b>仅 FBA</b>：SP-API 没有卖家自发货（FBM）退货报表，FBM 退货只能手工登记。
 */
export const syncReturnsFromAmazon = (
  shopId: number | string,
  marketplaceId: string,
  windowDays?: number
) =>
  request.post<void, ApiResponse<Record<string, number>>>(`/logistics/return/sync/${shopId}`, null, {
    params: { marketplaceId, ...(windowDays ? { windowDays } : {}) }
  })
