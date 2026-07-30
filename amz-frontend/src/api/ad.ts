import request from './auth'
import type { ApiResponse } from './types'

// ACoS 概览数据
export interface AdOverview {
  totalAcos: number
  totalSpend: string
  totalSales: string
  roas: string
}

// ACoS 趋势点
export interface AcosTrendItem {
  day: string
  value: number
}

// 广告活动
export interface AdCampaign {
  id: number
  name: string
  active: boolean
  budget: number
  spend: number
  sales: number
  acos: number
}

// 广告报表聚合结果
export interface AdReport {
  overview: AdOverview
  trend: AcosTrendItem[]
  campaigns: AdCampaign[]
}

// 获取广告报表
export const getAdReports = (shopId: number | string) => {
  return request.get<void, ApiResponse<AdReport>>('/ad/reports', {
    params: { shopId }
  })
}

// 获取关键词优化建议
export const getKeywordOptimization = (shopId: number | string) => {
  return request.get<void, ApiResponse<unknown>>('/ad/keywords/optimize', {
    params: { shopId }
  })
}
