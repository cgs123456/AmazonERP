import request from './auth'
import type { ApiResponse } from './types'

// 广告类型：SP/SB/SD/DSP
export type AdType = 'SP' | 'SB' | 'SD' | 'DSP'

// 广告活动扩展（支持全广告类型）
export interface AdCampaignExt {
  id?: number
  shopId: number
  campaignId: string
  campaignName: string
  adType: AdType
  campaignType?: string
  budget?: number
  budgetType?: 'DAILY' | 'LIFETIME'
  biddingStrategy?: string
  status?: 'ENABLED' | 'PAUSED' | 'ARCHIVED'
  startDate?: string
  endDate?: string
  impressions?: number
  clicks?: number
  spend?: number
  sales?: number
  orders?: number
  acos?: number
  roas?: number
}

// SB 广告素材
export interface AdCreative {
  id?: number
  campaignId: string
  creativeType?: 'VIDEO' | 'IMAGE' | 'STORE_SPOTLIGHT' | 'CUSTOM_HEADLINE'
  headline?: string
  brandName?: string
  logoUrl?: string
  videoUrl?: string
  landingPageUrl?: string
  asin?: string
  status?: 'PENDING' | 'APPROVED' | 'REJECTED'
}

// SD 受众定向
export interface AdTargeting {
  id?: number
  campaignId: string
  targetingType?: 'CONTEXTUAL' | 'REMARKETING' | 'AUDIENCE' | 'LOOKALIKE'
  targetingValue?: string
  bid?: number
  impressions?: number
  clicks?: number
  spend?: number
  sales?: number
}

// 汇总指标
export interface AdSummary {
  impressions: number
  clicks: number
  spend: number
  sales: number
  orders: number
  acos: number
  roas: number
}

// ===== 广告活动 =====
export const createCampaign = (data: AdCampaignExt) => {
  return request.post<void, ApiResponse<AdCampaignExt>>('/ad/campaigns', data)
}

export const updateCampaign = (data: AdCampaignExt) => {
  return request.put<void, ApiResponse<AdCampaignExt>>('/ad/campaigns', data)
}

export const listCampaigns = (shopId: number | string, adType?: AdType) => {
  return request.get<void, ApiResponse<AdCampaignExt[]>>(`/ad/campaigns/list/${shopId}`, {
    params: { adType }
  })
}

export const batchCreateCampaigns = (data: AdCampaignExt[]) => {
  return request.post<void, ApiResponse<AdCampaignExt[]>>('/ad/campaigns/batch', data)
}

export const batchUpdateStatus = (ids: number[], status: string) => {
  return request.put<void, ApiResponse<AdCampaignExt[]>>('/ad/campaigns/batch/status', null, {
    params: { ids: ids.join(','), status }
  })
}

// ===== 综合报表 =====
export const getShopSummary = (shopId: number | string) => {
  return request.get<void, ApiResponse<AdSummary>>(`/ad/campaigns/summary/${shopId}`)
}

export const getSummaryByType = (shopId: number | string) => {
  return request.get<void, ApiResponse<Record<string, AdSummary>>>(`/ad/campaigns/summary/type/${shopId}`)
}

// ===== SB 广告素材 =====
export const createCreative = (data: AdCreative) => {
  return request.post<void, ApiResponse<AdCreative>>('/ad/creatives', data)
}

export const updateCreative = (data: AdCreative) => {
  return request.put<void, ApiResponse<AdCreative>>('/ad/creatives', data)
}

export const listCreatives = (campaignId: string) => {
  return request.get<void, ApiResponse<AdCreative[]>>(`/ad/creatives/list/${campaignId}`)
}

export const reviewCreative = (id: number, status: 'APPROVED' | 'REJECTED') => {
  return request.put<void, ApiResponse<AdCreative>>(`/ad/creatives/${id}/review`, null, {
    params: { status }
  })
}

// ===== SD 受众定向 =====
export const createTargeting = (data: AdTargeting) => {
  return request.post<void, ApiResponse<AdTargeting>>('/ad/targeting', data)
}

export const updateTargeting = (data: AdTargeting) => {
  return request.put<void, ApiResponse<AdTargeting>>('/ad/targeting', data)
}

export const listTargeting = (campaignId: string, targetingType?: string) => {
  return request.get<void, ApiResponse<AdTargeting[]>>(`/ad/targeting/list/${campaignId}`, {
    params: { targetingType }
  })
}

export const deleteTargeting = (id: number) => {
  return request.delete<void, ApiResponse<void>>(`/ad/targeting/${id}`)
}
