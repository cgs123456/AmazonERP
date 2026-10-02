import request from './auth'
import type { ApiResponse } from './types'

/**
 * 多平台订单接口层（amz-service-multiplatform，`/multiplatform/**`）。
 *
 * 动因（2026-10-03 端点覆盖清点）：这个模块 26 个端点在前端零命中，
 * 网关也已经把 `/multiplatform/**` 路由好了，所以「多平台」在界面上完全不存在。
 * 本文件只接**订单链路**，其余端点的判断都记在下面第 2、3 条，不是遗漏。
 *
 * 三条会误导判断的事实：
 * 1. 列表读的是本地 `amz_unified_order`，只有「同步」会真的去平台拉单。
 *    所以列表为空不等于平台没订单，可能只是从没同步过。
 * 2. 商品/消息/库存的 `POST /multiplatform/{product|message|inventory}/sync/**`
 *    三家的 RealClient 一律抛 `UnsupportedOperationException`（只有 mock Profile 有样例数据），
 *    后端会把这种「能力未接入」翻译成业务失败而不是 500，但那是必然失败的按钮，这里不提供。
 * 3. 发货是**真实回传给平台**的动作：平台接受后本地才变 SHIPPED，
 *    平台返回 false 时本地状态与运单号都不写，所以页面要把返回值读出来而不是假定成功。
 *
 * 枚举取自 V1 迁移的列注释：platform = TEMU/TIKTOK/SHEIN，
 * status = UNPAID/PAID/SHIPPED/DELIVERED/COMPLETED/CANCELED/REFUNDED。
 * 「上次同步时间」这类字段后端没有，页面也不许摆一个看起来像的数。
 */

export const PLATFORMS = ['TEMU', 'TIKTOK', 'SHEIN']
export const ORDER_STATUSES = ['UNPAID', 'PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELED', 'REFUNDED']
/** 已经发过货或终态的订单：页面提示重复回传会被平台拒，但不替用户判断业务 */
export const FINAL_STATUSES = ['SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELED', 'REFUNDED']

export interface UnifiedOrder {
  id?: number
  unifiedOrderNo?: string
  platform: string
  platformOrderNo: string
  shopId?: number | string
  buyerNickname?: string | null
  shipCountry?: string | null
  sku?: string | null
  productName?: string | null
  quantity?: number | null
  items?: Array<{ sku?: string; name?: string; quantity?: number }> | null
  originalAmount?: number | string | null
  currency?: string | null
  cnyAmount?: number | string | null
  status?: string | null
  trackingNo?: string | null
  orderCreateTime?: string | null
}

export interface OrderSyncSummary {
  attempted: number
  succeeded: number
  failed: number
  inserted: number
  failedPlatforms: string[]
}

interface ListQuery {
  size?: number
  cursor?: string | null
}

export const listOrders = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<UnifiedOrder[]>>(`/multiplatform/order/list/${shopId}`, { params: q })

export const listOrdersByPlatform = (shopId: number | string, platform: string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<UnifiedOrder[]>>(
    `/multiplatform/order/list/${shopId}/${platform}`,
    { params: q }
  )

/** 三平台逐个拉单：单个平台失败不影响另外两个，失败的平台会在 failedPlatforms 里点名 */
export const syncAllPlatforms = (shopId: number | string) =>
  request.post<void, ApiResponse<OrderSyncSummary>>(`/multiplatform/sync/all/${shopId}`)

/** 单平台同步：返回本次新增入库的条数（去重后），不是平台总单量 */
export const syncPlatform = (shopId: number | string, platform: string) =>
  request.post<void, ApiResponse<number>>(`/multiplatform/sync/${shopId}/${platform}`)

/** 真实回传给平台的发货动作；只有平台接受后本地订单才变成 SHIPPED */
export const markOrderShipped = (orderId: number, trackingNo: string) =>
  request.post<void, ApiResponse<boolean>>(
    `/multiplatform/order/${orderId}/ship`,
    undefined,
    { params: { trackingNo } }
  )
