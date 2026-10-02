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

// 零值汇总：后端 200 但 reports 为空时返回，调用方展示空态；
// 禁止把扁平原结构强转后让视图误用 mock 当真实数据。
export const zeroProfitReport = (): { summary: ProfitSummary; rows: ProfitRow[] } => ({
  summary: { totalRevenue: '$0.00', totalCost: '$0.00', grossProfit: '$0.00', grossMargin: '0.0%' },
  rows: []
})

// 获取利润报表（统一收敛为展示模型：200 有行 → 映射；200 空 → 零值空态；
// 非 200 → 零值占位，调用方按 code 走降级 mock）
export const getProfitReport = (
  shopId: number | string,
  startDate: string,
  endDate: string
): Promise<ApiResponse<{ summary: ProfitSummary; rows: ProfitRow[] }>> => {
  return request
    .get<void, ApiResponse<ProfitReportRaw>>('/order/profit/report', {
      params: { shopId, startDate, endDate }
    })
    .then((res) => {
      if (res?.code === 200 && res.data) {
        const data =
          Array.isArray(res.data.reports) && res.data.reports.length > 0
            ? mapProfitReport(res.data)
            : zeroProfitReport()
        return { ...res, data }
      }
      return { ...res, data: zeroProfitReport() }
    })
}

/**
 * 利润下钻（ProfitController，`/order/profit/**`）。
 *
 * 三条各自解决一个此前没有入口的问题：
 * - 按订单：这单到底赚了多少钱（含每一项费用与 dataComplete 标记）；
 * - 按 SKU：某个 SKU 的逐日利润行；
 * - 月度汇总：后端 SQL 按 SKU×月 GROUP BY 的真实聚合，替代页面上那个永远填不上的「按店铺」维度。
 *
 * dataComplete=false 表示这条利润的某项成本没拿全（利润由 ProfitMQConsumer 逐单算出），
 * 这种行不能和完整行同样当成决策依据，所以页面必须把它标出来而不是照常渲染数字。
 * 两条明细查询是 keyset 分页（`_page` 给出 truncated/nextCursor）；月度汇总不分页但有后端上限。
 */
export interface ProfitReportRow {
  id?: number
  shopId?: number | string
  amazonOrderId?: string | null
  sku?: string | null
  statDate?: string | null
  revenue?: number | string | null
  productCost?: number | string | null
  fbaFulfillmentFee?: number | string | null
  fbaStorageFee?: number | string | null
  referralFee?: number | string | null
  adCost?: number | string | null
  vat?: number | string | null
  grossProfit?: number | string | null
  netProfit?: number | string | null
  netMargin?: number | string | null
  dataComplete?: boolean | null
}

/** 后端 selectMonthlySummary 的聚合行（列名就是 SQL 里的下划线别名） */
export interface MonthlyProfitRow {
  shop_id?: number | string
  sku: string
  month: string
  total_revenue?: number | string | null
  total_cost?: number | string | null
  total_profit?: number | string | null
  margin?: number | string | null
}

interface PageQuery {
  size?: number
  cursor?: string | null
}

export const listProfitByOrder = (
  shopId: number | string,
  amazonOrderId: string,
  q: PageQuery = {}
) =>
  request.get<void, ApiResponse<ProfitReportRow[]>>(
    `/order/profit/order/${shopId}/${encodeURIComponent(amazonOrderId)}`,
    { params: q }
  )

export const listProfitBySku = (shopId: number | string, sku: string, q: PageQuery = {}) =>
  request.get<void, ApiResponse<ProfitReportRow[]>>(
    `/order/profit/sku/${shopId}/${encodeURIComponent(sku)}`,
    { params: q }
  )

export const getMonthlyProfitSummary = (shopId: number | string) =>
  request.get<void, ApiResponse<MonthlyProfitRow[]>>(`/order/profit/summary/${shopId}`)

/** 聚合行 → 现有表格展示模型；margin 后端是 0-1 小数（ROUND(...,4)） */
export const mapMonthlyRows = (rows: MonthlyProfitRow[]): ProfitRow[] =>
  rows.map((r) => {
    const revenue = toNum(r.total_revenue)
    const profit = toNum(r.total_profit)
    return {
      name: `${r.sku} · ${r.month}`,
      revenue: fmtMoney(revenue),
      cost: fmtMoney(r.total_cost),
      platformFee: '—',
      adFee: '—',
      shipping: '—',
      profit,
      margin: Number((toNum(r.margin) * 100).toFixed(1))
    }
  })
