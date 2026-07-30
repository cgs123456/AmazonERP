import request from './auth'
import type { ApiResponse } from './types'

// 库存列表项
export interface InventoryItem {
  sku: string
  asin: string
  shop: string
  stock: number
  dailySales: number
  days: number
  level: 'urgent' | 'risk' | 'healthy' | 'overstock'
  levelText: string
  suggestQty: number
}

// 库存健康度统计
export interface InventoryHealth {
  urgent: number
  risk: number
  healthy: number
  overstock: number
}

// 获取店铺库存列表
export const getInventoryList = (shopId: number | string) => {
  return request.get<void, ApiResponse<InventoryItem[]>>(`/spapi/inventory/${shopId}`)
}

// 获取库存健康度
export const getInventoryHealth = (shopId: number | string) => {
  return request.get<void, ApiResponse<InventoryHealth>>(`/spapi/inventory/health/${shopId}`)
}

// 获取补货建议
export const getReplenishSuggestion = (shopId: number | string, sku: string) => {
  return request.get<void, ApiResponse<{ sku: string; suggestQty: number }>>('/spapi/replenish/suggest', {
    params: { shopId, sku }
  })
}
