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

// 后端 GET /order/profit/report 原始结构（OrderController#profitReport）：
// 扁平聚合字段 + reports 日粒度 ProfitReport 行；无 { summary, rows } 包装）
export interface ProfitReportRaw {
  totalRevenue?: number | string
  totalCost?: number | string
  totalProfit?: number | string
  margin?: number | string
  reports?: Array<{
    statDate?: string
    sku?: string
    revenue?: number | string
    productCost?: number | string
    referralFee?: number | string
    adCost?: number | string
    fbaFulfillmentFee?: number | string
    fbaStorageFee?: number | string
    netProfit?: number | string
    netMargin?: number | string
  }>
}

const toNum = (v: unknown): number => {
  const n = Number(v)
  return isNaN(n) ? 0 : n
}

const fmtMoney = (v: unknown): string =>
  '$' + toNum(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

// 后端扁平结构 → 前端 { summary, rows } 展示模型（与 dashboard kpi 适配同模式）
export const mapProfitReport = (raw: ProfitReportRaw): { summary: ProfitSummary; rows: ProfitRow[] } => {
  const reports = Array.isArray(raw.reports) ? raw.reports : []
  return {
    summary: {
      totalRevenue: fmtMoney(raw.totalRevenue),
      totalCost: fmtMoney(raw.totalCost),
      grossProfit: fmtMoney(raw.totalProfit),
      grossMargin: `${(toNum(raw.margin) * 100).toFixed(1)}%`
    },
    rows: reports.map((r) => ({
      name: String(r.statDate ?? r.sku ?? ''),
      revenue: fmtMoney(r.revenue),
      cost: fmtMoney(r.productCost),
      platformFee: fmtMoney(r.referralFee),
      adFee: fmtMoney(r.adCost),
      shipping: fmtMoney(toNum(r.fbaFulfillmentFee) + toNum(r.fbaStorageFee)),
      profit: toNum(r.netProfit),
      margin: Number((toNum(r.netMargin) * 100).toFixed(1))
    }))
  }
}

// 获取利润报表（空 reports 时原样返回，调用方保留降级 mock）
export const getProfitReport = (shopId: number | string, startDate: string, endDate: string) => {
  return request
    .get<void, ApiResponse<ProfitReportRaw>>('/order/profit/report', {
      params: { shopId, startDate, endDate }
    })
    .then((res) => {
      if (res?.code === 200 && res.data && Array.isArray(res.data.reports) && res.data.reports.length > 0) {
        return { ...res, data: mapProfitReport(res.data) }
      }
      return { ...res, data: res?.data as unknown as { summary: ProfitSummary; rows: ProfitRow[] } }
    })
}
