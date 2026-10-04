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


/* ==================== B2C 手工下单（POST /order/saveOrder、GET /order/getOrderList） ==================== */

/** 商品自定义属性：label + 取值数组，后端只取每个 label 的第一个值落 amz_order_attribute */
export interface CustomAttribute {
  label: string
  value: string[]
}

/**
 * 下单请求体。**不要**从页面传 userId 与 messageId：
 * - userId 由后端取认证上下文里的登录用户（原来信任请求体，任何登录者都能给别人造订单，
 *   而下完在「我的下单」里查不到——读的是登录用户）；
 * - messageId 由后端每次新生成（原来信任请求体，而消费端拿它做幂等占位，
 *   塞一个已占用的 id 就能让订单被静默丢弃）。
 * 副作用是**重复点击会各下一单**，所以提交期间按钮必须禁用。
 */
export interface B2cOrderDraft {
  productId: number
  price: number | string
  selectAttributes?: CustomAttribute[]
}

/** 后端落库的 B2C 订单行（Order 实体子集：这条链路不写 shop_id / amazon_order_id） */
export interface B2cOrderRow {
  id?: number
  productId?: number
  userId?: number
  quantity?: number | null
  finalPrice?: number | string | null
  /** 0=待付款 1=已付款 2=已取消（OrderStatusEnum） */
  status?: number | null
}

export const B2C_STATUS_LABELS: Record<string, string> = {
  0: '待付款',
  1: '已付款',
  2: '已取消'
}

export const saveB2cOrder = (draft: B2cOrderDraft) =>
  request.post<void, ApiResponse<null>>('/order/saveOrder', draft)

/** 我这个登录账号提交出来的订单（后端按认证上下文读，没有分页） */
export const getMyB2cOrders = () =>
  request.get<void, ApiResponse<B2cOrderRow[]>>('/order/getOrderList')
