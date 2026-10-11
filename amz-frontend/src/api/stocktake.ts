import request from './auth'
import type { ApiResponse } from './types'

/**
 * 库存盘点（P1.2，账实核对）。
 * 后端语义：建单快照账面 → 录实盘 → 落账（按「apply 时真实账面 → 实盘」SET 库存
 * 并写 STOCKTAKE_ADJUST 流水）；未盘行跳过不按 0（「没盘」≠「盘为 0」）。
 */
export interface StocktakeRow {
  id?: number
  shopId?: number
  stocktakeNo?: string
  warehouseId?: number
  status?: string
  remark?: string
  appliedAt?: string
  createTime?: string
}

export interface StocktakeItemRow {
  id?: number
  sku?: string
  expectedQty?: number
  countedQty?: number | null
  batchNo?: string
}

export interface StocktakeDetail {
  stocktake: StocktakeRow
  items: StocktakeItemRow[]
}

export const STOCKTAKE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '盘点中',
  COUNTED: '已录实盘',
  APPLIED: '已落账',
  CANCELLED: '已取消'
}

/** 建单（快照账面） */
export const createStocktake = (payload: {
  shopId: number | string
  warehouseId: number | string
  remark?: string
  skus: string[]
}) => request.post<void, ApiResponse<StocktakeRow>>('/logistics/stocktake', payload)

/** 盘点单列表 */
export const listStocktakes = (shopId: number | string, status?: string) =>
  request.get<void, ApiResponse<StocktakeRow[]>>(`/logistics/stocktake/list/${shopId}`, {
    params: status ? { status } : {}
  })

/** 详情（含明细） */
export const getStocktakeDetail = (id: number) =>
  request.get<void, ApiResponse<StocktakeDetail>>(`/logistics/stocktake/${id}`)

/** 录入实盘 */
export const countStocktake = (
  id: number,
  counts: Array<{ sku: string; countedQty: number }>
) => request.post<void, ApiResponse<StocktakeRow>>(`/logistics/stocktake/${id}/count`, counts)

/** 修正落账（按实盘 SET 库存 + 写流水） */
export const applyStocktake = (id: number) =>
  request.post<void, ApiResponse<{ adjusted: number; skippedUncounted: number; noDiff: number }>>(
    `/logistics/stocktake/${id}/apply`
  )

/** 取消 */
export const cancelStocktake = (id: number) =>
  request.post<void, ApiResponse<StocktakeRow>>(`/logistics/stocktake/${id}/cancel`)
