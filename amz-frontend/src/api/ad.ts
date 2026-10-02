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

/**
 * 广告日报手动回补（POST /ad/reports/sync）。
 *
 * 动因：AdManager 的空态一直写着「请先配置 API 并执行日报同步」，但直到本轮之前
 * 这句话在全系统都没有对应的操作入口——只有 cron 会写 amz_ad_daily_report
 * （AdReportSyncScheduler 是唯一 upsert 方），想回补某天的数据只能等第二天或进数据库。
 *
 * 两条必读事实：
 * 1. 单店入口与全店入口是同一个路径的两个重载，靠 shopId 这个查询参数区分：
 *    带 shopId 需 OPERATOR/ADMIN 且必须显式给出（缺了就不会退化成全店同步）；
 *    不带 shopId 是运维/回补入口，需 ADMIN。
 * 2. 同步是「读广告 API + 本地 upsert」，本身不改平台数据，但会消耗配额，
 *    并且窗口上限 30 天（MAX_DAYS），超出请按多次回补。
 */
export interface AdSyncSummary {
  shopId: number | string | null
  days: number
  attempted: number
  succeeded: number
  failed: number
  skipped: number
  upserted: number
  metadataWarnings: number
}

/** 同步天数上限，与 AdReportSyncScheduler.MAX_DAYS 一致 */
export const AD_SYNC_MAX_DAYS = 30

export const syncAdReports = (shopId: number | string, days = 7) => {
  return request.post<void, ApiResponse<AdSyncSummary>>('/ad/reports/sync', undefined, {
    params: { shopId, days }
  })
}

/** 全店铺回补：不传 shopId 才会命中 ADMIN 专用重载，传了就会变成单店同步 */
export const syncAllAdReports = (days = 7) => {
  return request.post<void, ApiResponse<AdSyncSummary>>('/ad/reports/sync', undefined, {
    params: { days }
  })
}


