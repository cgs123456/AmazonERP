import request from './auth'
import type { ApiResponse } from './types'
import { asNumber } from '@/utils/format'

/**
 * 商品与 Listing 运营接口层（amz-service-product, 端口 8095）。
 *
 * 动因（2026-10-02 功能覆盖清点）：product 模块 24 个端点此前**没有任何前端入口**，
 * 而它们的后端实现是真的（ListingsRealClient 走 Feign、缺凭证显式抛错，不返回样例数据）。
 * 也就是说运营每天要看的 listing 健康度、关键词排名、竞品对比、Buybox 抢占、Keepa 趋势
 * 全都只能靠手工 curl。本文件把这些能力接进 UI。
 *
 * 与 InventoryMonitor 的差别：这里**不做"接口失败就用页面常量兜底"**——
 * 那种写法会让"没数据"和"数据长这样"看起来一样。失败时返回空列表 + errorByZone，由页面显示错误态。
 */

export interface ListingHealthRow {
  id?: number
  shopId?: number | string
  asin: string
  sku?: string
  status?: string
  titleOk?: number | boolean
  bulletPointsOk?: number | boolean
  descriptionOk?: number | boolean
  aplusOk?: number | boolean
  imagesOk?: number | boolean
  searchTermsOk?: number | boolean
  suppressedReason?: string | null
  healthScore?: number | null
  severity?: string | null
  checkTime?: string
}

export interface ListingSummary {
  total: number
  ok: number
  warning: number
  critical: number
  avgScore: number
  healthRate: number
  worstListings: Array<{ asin: string; score?: number | null; severity?: string; reason?: string }>
}

export interface KeywordRankingRow {
  id?: number
  asin: string
  keyword: string
  organicRank?: number | null
  adRank?: number | null
  searchVolume?: number | null
  rankDate?: string
  marketplaceId?: string | null
}

export interface CompetitorRow {
  id?: number
  competitorAsin: string
  competitorTitle?: string
  price?: number | string | null
  priceChange?: number | string | null
  bsRank?: number | null
  reviewCount?: number | null
  reviewRating?: number | string | null
  ratingChange?: number | string | null
  inStock?: boolean | number | null
  snapshotDate?: string
}

export interface BuyBoxRow {
  id?: number
  asin: string
  sellerId?: string
  /** 后端给的是布尔：true 表示这个 Buybox winner 是我们自己 */
  isSelf?: boolean | number | null
  buyboxPrice?: number | string | null
  ourPrice?: number | string | null
  priceGap?: number | string | null
  fulfillmentType?: string
  ownershipPct?: number | string | null
  snapshotTime?: string
}

export interface KeepaPoint {
  time?: string
  date?: string
  price?: number | string | null
  rank?: number | string | null
}

/** severity 由后端按 utf8mb4_bin 精确比较产出，这里只做展示归类，不做大小写猜测。 */
export const severityClass = (severity?: string | null): 'ok' | 'warning' | 'critical' | 'unknown' => {
  if (severity === 'OK') return 'ok'
  if (severity === 'WARNING') return 'warning'
  if (severity === 'CRITICAL') return 'critical'
  return 'unknown'
}

export const emptySummary = (): ListingSummary => ({
  total: 0, ok: 0, warning: 0, critical: 0, avgScore: 0, healthRate: 0, worstListings: []
})

const okList = <T>(res: ApiResponse<T[]> | undefined | null): T[] =>
  res && res.code === 200 && Array.isArray(res.data) ? res.data : []

/** 与其他 getXxx 一致，返回统一信封：调用方按 code 判成败，不在这里吞掉失败。 */
export const getListingSummary = async (shopId: number | string): Promise<ApiResponse<ListingSummary>> => {
  const res = await request.get<void, ApiResponse<Partial<ListingSummary>>>(
    `/product/listing-monitor/health/summary/${shopId}`)
  if (res?.code !== 200 || !res.data) {
    return { ...res, data: emptySummary() } as ApiResponse<ListingSummary>
  }
  const d = res.data
  return {
    ...res,
    data: {
      total: asNumber(d.total),
      ok: asNumber(d.ok),
      warning: asNumber(d.warning),
      critical: asNumber(d.critical),
      avgScore: asNumber(d.avgScore),
      healthRate: asNumber(d.healthRate),
      worstListings: Array.isArray(d.worstListings) ? d.worstListings : []
    }
  } as ApiResponse<ListingSummary>
}

export const getListingHealthList = async (shopId: number | string, severity?: string) => {
  const res = await request.get<void, ApiResponse<ListingHealthRow[]>>(
    `/product/listing-monitor/health/list/${shopId}`,
    severity ? { params: { severity } } : undefined)
  return { ...res, data: okList<ListingHealthRow>(res) }
}

export const getRankings = async (shopId: number | string, asin?: string, keyword?: string) => {
  const res = await request.get<void, ApiResponse<KeywordRankingRow[]>>(
    `/product/listing-monitor/ranking/list/${shopId}`,
    { params: { ...(asin ? { asin } : {}), ...(keyword ? { keyword } : {}) } })
  return { ...res, data: okList<KeywordRankingRow>(res) }
}

export const getCompetitors = async (shopId: number | string) => {
  const res = await request.get<void, ApiResponse<CompetitorRow[]>>(
    `/product/listing-monitor/competitor/list/${shopId}`)
  return { ...res, data: okList<CompetitorRow>(res) }
}

export const getBuyBoxList = async (shopId: number | string) => {
  const res = await request.get<void, ApiResponse<BuyBoxRow[]>>(
    `/product/listing-monitor/buybox/list/${shopId}`)
  return { ...res, data: okList<BuyBoxRow>(res) }
}

export const getBuyBoxSummary = async (shopId: number | string) => {
  return request.get<void, ApiResponse<Record<string, unknown>>>(
    `/product/listing-monitor/buybox/summary/${shopId}`)
}

export const getChangeLogs = async (shopId: number | string, asin?: string) => {
  const res = await request.get<void, ApiResponse<Array<Record<string, unknown>>>>(
    `/product/listing-monitor/change-log/list/${shopId}`,
    asin ? { params: { asin } } : undefined)
  return { ...res, data: okList<Record<string, unknown>>(res) }
}

export const getKeepaPrice = (asin: string) =>
  request.get<void, ApiResponse<KeepaPoint[]>>(`/product/keepa/price/${encodeURIComponent(asin)}`)

export const getKeepaRank = (asin: string) =>
  request.get<void, ApiResponse<KeepaPoint[]>>(`/product/keepa/rank/${encodeURIComponent(asin)}`)

/** FBA 费用试算：asin 或 price/weight/sizeTier 二选一，后端都可算。 */
export const estimateFees = (params: { asin?: string; price?: number; weight?: number; sizeTier?: string }) =>
  request.get<void, ApiResponse<Record<string, unknown>>>('/product/fees/estimate', { params })

/* ==================== 商品主数据（/product/master） ==================== */

export interface MasterRow {
  id?: number
  shopId?: number | string
  sku: string
  asin?: string | null
  marketplaceId?: string | null
  title?: string | null
  brand?: string | null
  price?: number | string | null
  currency?: string | null
  category?: string | null
  productType?: string | null
  sizeTier?: string | null
  weightG?: number | null
  status?: string | null
  createTime?: string
}

export const listMaster = async (shopId: number | string, asin?: string, keyword?: string) => {
  const res = await request.get<void, ApiResponse<MasterRow[]>>(
    `/product/master/list/${shopId}`,
    { params: { ...(asin ? { asin } : {}), ...(keyword ? { keyword } : {}) } })
  return { ...res, data: okList<MasterRow>(res) }
}

/** 建一条主数据。marketplaceId 与 title 在 DDL 里是 NOT NULL，后端会先挡空。 */
export const createMaster = (body: Partial<MasterRow>) =>
  request.post<void, ApiResponse<MasterRow>>('/product/master', body)

export const updateMaster = (shopId: number | string, id: number, patch: Partial<MasterRow>) =>
  request.put<void, ApiResponse<MasterRow>>(`/product/master/${shopId}/${id}`, patch)

/** 同一 ASIN 在我管的各店铺里的登记情况（跨站复制的入口数据）。 */
export const masterByAsin = (asin: string) =>
  request.get<void, ApiResponse<MasterRow[]>>('/product/master/by-asin', { params: { asin } })
