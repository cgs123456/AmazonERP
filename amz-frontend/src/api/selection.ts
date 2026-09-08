import request from './auth'
import type { ApiResponse } from './types'

// 选品机会
export interface SelectionOpportunity {
  id?: number
  shopId?: number
  asin: string
  title?: string
  category?: string
  marketplace?: string
  // 市场指标
  avgPrice: number
  avgReviews: number
  avgRating: number
  searchVolume: number
  // 竞争指标
  competitorCount: number
  reviewBarrier: string
  opportunityScore: number
  // 趋势
  trend30d?: string
  trend90d?: string
  // AI 建议
  aiSummary?: string
  aiSuggestion?: string
  status?: string
  createTime?: string
  updateTime?: string
}

// 关键词调研
export interface KeywordResearch {
  id?: number
  shopId?: number
  keyword: string
  marketplace?: string
  searchVolume: number
  clickShare: number
  conversionShare: number
  topAsin?: string
  difficultyScore: number
  recommendedBid: number
  createTime?: string
}

// 市场分析摘要
export interface MarketAnalysisSummary {
  keyword: string
  marketplace: string
  category: string
  marketSize: number
  avgPrice: number
  avgReviews: number
  avgRating: number
  searchVolume: number
  competitorCount: number
  reviewBarrier: string
  trend30d?: string
  trend90d?: string
  seasonality?: string
  opportunities: SelectionOpportunity[]
  keywordResearch?: KeywordResearch
}

// 市场分析请求
export interface MarketAnalysisRequest {
  keyword: string
  marketplace?: string
}

// 分析市场（生成机会列表并落库）
export const analyzeMarket = (data: MarketAnalysisRequest) => {
  return request.post<void, ApiResponse<MarketAnalysisSummary>>('/ops/selection/market', data)
}

// 蓝海机会列表
export const findOpportunities = (
  shopId: number | string,
  category?: string,
  sortBy: 'score' | 'volume' | 'competition' = 'score',
  limit = 20
) => {
  return request.get<void, ApiResponse<SelectionOpportunity[]>>('/ops/selection/opportunities', {
    params: { shopId, category, sortBy, limit }
  })
}

// AI 选品建议
export const getAiSuggestion = (opportunityId: number | string) => {
  return request.post<void, ApiResponse<SelectionOpportunity>>(`/ops/selection/ai-suggestion/${opportunityId}`)
}
