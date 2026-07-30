import request from './auth'
import type { ApiResponse } from './types'

// 利润报表行
export interface ProfitRow {
  name: string
  revenue: string
  cost: string
  platformFee: string
  adFee: string
  shipping: string
  profit: number
  margin: number
}

// 利润汇总
export interface ProfitSummary {
  totalRevenue: string
  totalCost: string
  grossProfit: string
  grossMargin: string
}

// 获取利润报表
export const getProfitReport = (shopId: number | string, startDate: string, endDate: string) => {
  return request.get<void, ApiResponse<{ summary: ProfitSummary; rows: ProfitRow[] }>>('/order/profit/report', {
    params: { shopId, startDate, endDate }
  })
}

// 获取利润汇总
export const getProfitSummary = (shopId: number | string) => {
  return request.get<void, ApiResponse<ProfitSummary>>(`/order/profit/summary/${shopId}`)
}
