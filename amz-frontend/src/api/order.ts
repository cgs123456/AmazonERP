import request from './auth'
import type { ApiResponse } from './types'
import { getShops } from '../utils/shop'

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
  orderNo?: string
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

// 后端 GET /order/list 返回的 Order 实体行（OrderController#listOrders）。
// 注意字段与前端展示模型不同：amazonOrderId（非 orderNo）、quantity（非 qty）、
// finalPrice（非 amount）、orderStatus（Amazon 原始状态，非中文）、purchaseDate
//（ISO 时间，非展示字符串）；无 sku / profit 字段。
export interface OrderRow {
  id?: number
  shopId?: number | string
  productId?: number
  quantity?: number
  finalPrice?: number | string
  amazonOrderId?: string
  orderStatus?: string
  purchaseDate?: string
}

// Amazon 订单状态 → 前端展示状态（statusClass 复用全局 .status-tag 样式类）
export const mapOrderStatus = (status?: string): { status: string; statusClass: string } => {
  switch ((status || '').toUpperCase()) {
    case 'SHIPPED':
      return { status: '已发货', statusClass: 'shipped' }
    case 'PENDING':
      return { status: '待处理', statusClass: 'pending' }
    case 'UNSHIPPED':
      return { status: '待发货', statusClass: 'pending' }
    case 'CANCELED':
    case 'CANCELLED':
      return { status: '已取消', statusClass: 'refunded' }
    default:
      return { status: '未知', statusClass: 'pending' }
  }
}

const toNum = (v: unknown): number => {
  const n = Number(v)
  return isNaN(n) ? 0 : n
}

// 后端 Order 实体行 → 前端展示行。
// sku：实体无 SKU 字段（仅 productId 外键），展示占位 '-'；
// profit：列表接口未返回利润，置 0（明细利润走利润报表）。
export const mapOrderRow = (row: OrderRow, shopName: string): OrderItem => {
  const { status, statusClass } = mapOrderStatus(row.orderStatus)
  const date = (row.purchaseDate || '').slice(0, 16).replace('T', ' ')
  return {
    id: toNum(row.id),
    orderNo: row.amazonOrderId || '-',
    shop: shopName,
    shopId: String(row.shopId ?? ''),
    sku: '-',
    qty: Math.max(0, Math.round(toNum(row.quantity))),
    amount: '$' + toNum(row.finalPrice).toFixed(2),
    profit: 0,
    status,
    statusClass,
    date
  }
}

const resolveShopName = (shopId: number | string): string => {
  const hit = getShops().find((s) => String(s.id) === String(shopId))
  return hit?.name || `店铺 ${shopId}`
}

// 获取订单列表（后端返回 Order 实体结构，此处统一适配为 OrderItem[] 展示模型）
export const getOrderList = (params: OrderListParams) => {
  return request
    .get<void, ApiResponse<PageResult<OrderRow> | OrderRow[]>>('/order/list', {
      params
    })
    .then((res) => {
      if (res?.code !== 200 || !res.data) return res as unknown as ApiResponse<PageResult<OrderItem> | OrderItem[]>
      const shopName = resolveShopName(params.shopId ?? '')
      if (Array.isArray(res.data)) {
        return { ...res, data: res.data.map((row) => mapOrderRow(row, shopName)) }
      }
      return {
        ...res,
        data: {
          ...res.data,
          list: (res.data.list || []).map((row) => mapOrderRow(row, shopName))
        }
      }
    })
}

