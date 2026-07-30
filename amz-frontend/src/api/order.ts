import request from './auth'
import type { ApiResponse } from './types'

// 订单列表项
export interface OrderItem {
  id: number
  orderNo: string
  shop: string
  shopId: string
  sku: string
  qty: number
  amount: string
  profit: number
  status: string
  statusClass: string
  date: string
}

// 订单列表查询参数
export interface OrderListParams {
  shopId?: number | string
  startDate?: string
  endDate?: string
  page?: number
  size?: number
}

// 分页结果
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

// 获取订单列表
export const getOrderList = (params: OrderListParams) => {
  return request.get<void, ApiResponse<PageResult<OrderItem> | OrderItem[]>>('/order/list', {
    params
  })
}

// 获取订单详情
export const getOrderDetail = (orderId: number | string) => {
  return request.get<void, ApiResponse<OrderItem>>(`/order/${orderId}`)
}
