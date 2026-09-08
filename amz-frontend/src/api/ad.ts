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

// 后端 GET /ad/reports 行结构（AdReport 实体：活动粒度聚合指标，无总览/趋势包装）
export interface AdReportRow {
  campaignId?: string
  keyword?: string | null
  impressions?: number
  clicks?: number
  cost?: number | string
  sales?: number | string
  orders?: number
  acos?: number | string
  roas?: number | string
}

// 获取广告报表（后端返回 AdReport 行数组；总览需前端聚合，见 AdManager）
export const getAdReports = (shopId: number | string) => {
  return request.get<void, ApiResponse<AdReportRow[]>>('/ad/reports', {
    params: { shopId }
  })
}

// 每日 ACoS 趋势（后端 GET /ad/trend 日报表聚合；空数组时调用方保留降级 mock）
// adType 可选（SP/SB/SD/DSP，由日报落库时 campaign_ext 表回填；不传为全类型汇总）
export const getAdTrend = (shopId: number | string, days = 14, adType?: string) => {
  return request.get<void, ApiResponse<AcosTrendItem[]>>('/ad/trend', {
    params: { shopId, days, adType }
  })
}


