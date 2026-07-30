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

// 获取 Dashboard KPI 数据
export const getKpiData = () => {
  return request.get<void, ApiResponse<KpiItem[]>>('/report/dashboard/kpi')
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
