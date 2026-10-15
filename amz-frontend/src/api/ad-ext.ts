import request from './auth'
import type { ApiResponse } from './types'

// 广告类型：SP/SB/SD/DSP
export type AdType = 'SP' | 'SB' | 'SD' | 'DSP'

// 广告活动扩展（支持全广告类型）
export interface AdCampaignExt {
  id?: number
  /**
   * 请求体里可省略：后端 createCampaign/updateCampaign 用查询参数里的 shopId 覆盖它，
   * 且两者不一致时明确拒绝（`活动归属与请求店铺不一致`）。
   * 声明成必填只会诱导前端把店铺 ID 塞进 body——那是给「改店铺」留一条后端不认的路。
   * 响应里它一定有值（列表/详情都返回）。
   */
  shopId?: number
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
export const createCampaign = (shopId: number | string, data: AdCampaignExt) => {
  return request.post<void, ApiResponse<AdCampaignExt>>('/ad/campaigns', data, { params: { shopId } })
}

export const updateCampaign = (shopId: number | string, data: AdCampaignExt) => {
  return request.put<void, ApiResponse<AdCampaignExt>>('/ad/campaigns', data, { params: { shopId } })
}

export const listCampaigns = (shopId: number | string, adType?: AdType) => {
  return request.get<void, ApiResponse<AdCampaignExt[]>>(`/ad/campaigns/list/${shopId}`, {
    params: { adType }
  })
}

export const batchCreateCampaigns = (shopId: number | string, data: AdCampaignExt[]) => {
  return request.post<void, ApiResponse<AdCampaignExt[]>>('/ad/campaigns/batch', data, { params: { shopId } })
}

export const batchUpdateStatus = (shopId: number | string, ids: number[], status: string) => {
  return request.put<void, ApiResponse<AdCampaignExt[]>>('/ad/campaigns/batch/status', null, {
    params: { shopId, ids: ids.join(','), status }
  })
}

// ===== 综合报表 =====
export const getSummaryByType = (shopId: number | string) => {
  return request.get<void, ApiResponse<Record<string, AdSummary>>>(`/ad/campaigns/summary/type/${shopId}`)
}

// ===== SB 广告素材 =====
export const createCreative = (shopId: number | string, data: AdCreative) => {
  return request.post<void, ApiResponse<AdCreative>>('/ad/creatives', data, { params: { shopId } })
}

export const updateCreative = (shopId: number | string, data: AdCreative) => {
  return request.put<void, ApiResponse<AdCreative>>('/ad/creatives', data, { params: { shopId } })
}

export const listCreatives = (shopId: number | string, campaignId: string) => {
  return request.get<void, ApiResponse<AdCreative[]>>(`/ad/creatives/list/${campaignId}`, {
    params: { shopId }
  })
}

export const reviewCreative = (shopId: number | string, id: number, status: 'APPROVED' | 'REJECTED') => {
  return request.put<void, ApiResponse<AdCreative>>(`/ad/creatives/${id}/review`, null, {
    params: { shopId, status }
  })
}

// ===== SD 受众定向 =====
export const createTargeting = (shopId: number | string, data: AdTargeting) => {
  return request.post<void, ApiResponse<AdTargeting>>('/ad/targeting', data, { params: { shopId } })
}

export const updateTargeting = (shopId: number | string, data: AdTargeting) => {
  return request.put<void, ApiResponse<AdTargeting>>('/ad/targeting', data, { params: { shopId } })
}

export const listTargeting = (shopId: number | string, campaignId: string, targetingType?: string) => {
  return request.get<void, ApiResponse<AdTargeting[]>>(`/ad/targeting/list/${campaignId}`, {
    params: { shopId, targetingType }
  })
}

export const deleteTargeting = (shopId: number | string, id: number) => {
  return request.delete<void, ApiResponse<void>>(`/ad/targeting/${id}`, {
    params: { shopId }
  })
}
