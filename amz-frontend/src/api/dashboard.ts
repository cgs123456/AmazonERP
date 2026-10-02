import request from './auth'
import type { ApiResponse } from './types'

// KPI 数据（图标统一使用单 accent 渲染，color 保留为后端兼容可选字段）
export interface KpiItem {
  label: string
  value: string | number
  trend: number
  icon: string
  color?: string
}

// 销售趋势
export interface SalesTrendItem {
  day: string
  value: number
}

// 店铺销售占比（图例使用单 accent 深浅阶梯，color 保留为后端兼容可选字段）
export interface ShopDistItem {
  name: string
  percent: number
  color?: string
}

// 后端 GET /report/dashboard/kpi 返回的原始结构（ReportController#getKpi）
export interface KpiRaw {
  shopId?: number
  dateRange?: string
  totalSales?: number | string
  totalOrders?: number
  conversionRate?: number | string
  returnRate?: number | string
  avgOrderValue?: number | string
}

/**
 * 后端 KPI Map → 前端卡片数组。
 *
 * 转化率单独处理：真实报表实现（RealReportServiceImpl）从不给 conversionRate 赋值，
 * 它恒为 null。旧写法 `?? 0` 会把它显示成「0%」，看起来像量出来是 0，
 * 实际是「没统计」——两者对运营是完全不同的结论，所以缺值一律显示「未统计」。
 * trend 后端不提供，置 0 只用于隐藏涨跌箭头，不表示涨跌为 0。
 */
export const mapKpiItems = (d: KpiRaw): KpiItem[] => {
  const rateMissing = d.conversionRate === null || d.conversionRate === undefined
  return [
    { label: '销售额', value: `$${d.totalSales ?? 0}`, trend: 0, icon: 'mdi:currency-usd' },
    { label: '订单数', value: d.totalOrders ?? 0, trend: 0, icon: 'mdi:cart' },
    { label: '转化率', value: rateMissing ? '未统计' : `${d.conversionRate}%`, trend: 0, icon: 'mdi:trending-up' },
    { label: '客单价', value: `$${d.avgOrderValue ?? 0}`, trend: 0, icon: 'mdi:chart-line' }
  ]
}

// 获取 Dashboard KPI 数据（shopId 为后端必填参数），并把原始 Map 结构适配为 KpiItem[]
export const getKpiData = (shopId: number | string) => {
  return request
    .get<void, ApiResponse<KpiRaw>>('/report/dashboard/kpi', {
      params: { shopId }
    })
    .then((res) => {
      if (res?.code === 200 && res.data) {
        const d = res.data
        return { ...res, data: mapKpiItems(d) }
      }
      return res
    })
}

// 获取近 N 天销售趋势（shopId 可选；传入时后端按店铺过滤，否则返回全局聚合）
export const getSalesTrend = (days: number, shopId?: number | string) => {
  return request.get<void, ApiResponse<SalesTrendItem[]>>('/report/dashboard/sales-trend', {
    params: { days, shopId }
  })
}

// 获取店铺销售占比（shopId 可选；传入时按店铺过滤，否则返回全局分布）
export const getShopDistribution = (shopId?: number | string) => {
  return request.get<void, ApiResponse<ShopDistItem[]>>('/report/dashboard/shop-distribution', {
    params: { shopId }
  })
}
