import request from './auth'
import type { ApiResponse } from './types'

// KPI 数据
export interface KpiItem {
  label: string
  value: string | number
  trend: number
  icon: string
  color: string
}

// 销售趋势
export interface SalesTrendItem {
  day: string
  value: number
}

// 店铺销售占比
export interface ShopDistItem {
  name: string
  percent: number
  color: string
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

// 获取 Dashboard KPI 数据（shopId 为后端必填参数），并把原始 Map 结构适配为 KpiItem[]
export const getKpiData = (shopId: number | string) => {
  return request
    .get<void, ApiResponse<KpiRaw>>('/report/dashboard/kpi', {
      params: { shopId }
    })
    .then((res) => {
      if (res?.code === 200 && res.data) {
        const d = res.data
        // 适配为前端 KPI 卡片数组结构（trend 后端未提供，置 0 隐藏涨跌箭头语义）
        const items: KpiItem[] = [
          { label: '销售额', value: `$${d.totalSales ?? 0}`, trend: 0, icon: 'mdi:currency-usd', color: '#10b981' },
          { label: '订单数', value: d.totalOrders ?? 0, trend: 0, icon: 'mdi:cart', color: '#4f46e5' },
          { label: '转化率', value: `${d.conversionRate ?? 0}%`, trend: 0, icon: 'mdi:trending-up', color: '#f59e0b' },
          { label: '客单价', value: `$${d.avgOrderValue ?? 0}`, trend: 0, icon: 'mdi:chart-line', color: '#ef4444' }
        ]
        return { ...res, data: items }
      }
      return res
    })
}

// 获取近 N 天销售趋势
export const getSalesTrend = (days: number) => {
  return request.get<void, ApiResponse<SalesTrendItem[]>>('/report/dashboard/sales-trend', {
    params: { days }
  })
}

// 获取店铺销售占比
export const getShopDistribution = () => {
  return request.get<void, ApiResponse<ShopDistItem[]>>('/report/dashboard/shop-distribution')
}
