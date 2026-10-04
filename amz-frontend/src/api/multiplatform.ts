import request from './auth'
import type { ApiResponse } from './types'

/**
 * 多平台订单接口层（amz-service-multiplatform，`/multiplatform/**`）。
 *
 * 动因（2026-10-03 端点覆盖清点）：这个模块 26 个端点在前端零命中，
 * 网关也已经把 `/multiplatform/**` 路由好了，所以「多平台」在界面上完全不存在。
 * 本文件接订单链路（/multiplatform）与运营台链路（/multiplatform-ops），
 * 不提供按钮的端点都单独写明原因，不是遗漏。
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

/**
 * 运营台部分：账号 / 商品映射 / 消息 / 库存 / Webhook / ISV 应用。
 *
 * 两条必须知道：
 * 1. 账号读取接口不返回凭证列（apiKey 明文、三个密文列都在服务端抹掉），
 *    所以页面没有「查看密钥」这种能力，写入也只能靠提交新值覆盖；
 * 2. `POST account/{id}/test` 是**真探测**（2026-10-03 改）：后端复用各家已鉴权的订单读发一次
 *    真实请求，平台回话才写 ACTIVE，凭证缺失/签名被拒/网络不通写 ERROR；它不写 lastSyncTime，
 *    所以「最后同步」只反映真同步。亚马逊账号会被点名拒绝（本模块不探测亚马逊），
 *    页面拿到的是 code 400 的业务失败，不是「探测失败」——两者不要混为一谈。
 *    `message/{id}/reply` 仍然不提供按钮，但语义已经换了（2026-10-03）：后端不再「只写一条本地
 *    OUT 备注」，而是先向平台真发、拿到平台消息 ID 才写本地记账。三家真实客户端都还没接
 *    买家站内信发送的 Open API method，所以生产档一律显式拒绝（code 400 点名「未接入」），
 *    本地一行都不写；只有 mock 档会返回带 MOCK-OUT- 前缀的假 ID。
 *    换句话说：接上按钮在当前环境只会稳定报错，等真有 method 依据再接。
 */

export const ACCOUNT_STATUSES = ['ACTIVE', 'INACTIVE', 'ERROR']
/** 消息状态列在 DDL 里是字符串枚举；后端曾按 Integer 传参，比较退化成数值隐式转换（已在 7e 修回 String） */
export const MESSAGE_STATUSES = ['UNREAD', 'READ', 'REPLIED', 'ARCHIVED']
export const WEBHOOK_STATUSES = ['RECEIVED', 'PROCESSED', 'FAILED']
// 商品列另有 ACTIVE/INACTIVE/OUT_OF_STOCK、应用列另有 ACTIVE/SUSPENDED/REVOKED，
// 但两个列表后端都不支持按状态筛选（只有 platform/status 里的其中一半），
// 所以页面只做颜色显示，不放一个筛了没用的前端过滤器。

export interface PlatformAccount {
  id?: number
  shopId?: number | string
  platform: string
  storeName?: string | null
  apiEndpoint?: string | null
  status?: string | null
  tokenExpiresAt?: string | null
  lastSyncTime?: string | null
  createTime?: string
}

export interface PlatformProduct {
  id?: number
  shopId?: number | string
  platform: string
  platformProductId: string
  platformProductSku?: string | null
  amazonAsin?: string | null
  amazonSku?: string | null
  title?: string | null
  price?: number | string | null
  currency?: string | null
  stockQty?: number | null
  status?: string | null
}

export interface PlatformMessage {
  id?: number
  shopId?: number | string
  platform: string
  platformMessageId?: string
  buyerName?: string | null
  buyerEmail?: string | null
  platformOrderNo?: string | null
  subject?: string | null
  content?: string | null
  direction?: string | null
  status?: string | null
  assignedTo?: string | null
  isUrgent?: boolean | number | null
  receiveTime?: string | null
}

export interface PlatformInventory {
  id?: number
  shopId?: number | string
  platform: string
  platformProductId?: string
  sku: string
  warehouse?: string | null
  availableQty?: number | null
  reservedQty?: number | null
  inboundQty?: number | null
  snapshotTime?: string | null
}

export interface InventoryAggregate {
  shopId?: number | string
  grandTotalAvailable: number
  byPlatform: Record<string, Record<string, number>>
  bySku: Record<string, number>
  /** 本次聚合的计算时刻，不是平台侧库存快照时间 */
  computedAt: string
}

export interface WebhookEvent {
  id?: number
  shopId?: number | string
  platform: string
  eventType?: string
  eventId?: string | null
  status?: string | null
  processResult?: string | null
  processTime?: string | null
  createTime?: string
}

export interface OauthApp {
  id?: number
  appName: string
  appKey?: string
  redirectUris?: string | null
  scopes?: string | null
  ownerShopId?: number | string
  rateLimitRpm?: number | null
  status?: string | null
  description?: string | null
}

/** 注册/轮换响应里出现的明文密钥只此一次，后端不存储也不再有读取接口 */
export interface OauthSecretIssue {
  appId: number
  appKey: string
  appSecret: string
}

export const listAccounts = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<PlatformAccount[]>>(`/multiplatform/account/list/${shopId}`, { params: q })

export const createAccount = (account: PlatformAccount) =>
  request.post<void, ApiResponse<PlatformAccount>>('/multiplatform/account', account)

export const updateAccount = (id: number, account: Partial<PlatformAccount>) =>
  request.put<void, ApiResponse<PlatformAccount>>(`/multiplatform/account/${id}`, account)

export const deleteAccount = (id: number) =>
  request.delete<void, ApiResponse<boolean>>(`/multiplatform/account/${id}`)

/**
 * 真探测账号连通性：后端拿这家店铺的凭证向平台发一次已鉴权订单读，
 * data=true 表示平台回话了（账号写成 ACTIVE），false 表示没回话（写成 ERROR）。
 * 探测不改 lastSyncTime；亚马逊账号会得到 code 400 的点名拒绝。
 */
export const testAccountConnection = (id: number | string) =>
  request.post<void, ApiResponse<boolean>>(`/multiplatform/account/${id}/test`)

export const listProducts = (shopId: number | string, q: ListQuery & { platform?: string } = {}) =>
  request.get<void, ApiResponse<PlatformProduct[]>>(`/multiplatform/product/list/${shopId}`, { params: q })

export const mapProduct = (productId: number, amazonAsin: string, amazonSku: string) =>
  request.post<void, ApiResponse<boolean>>(
    `/multiplatform/product/${productId}/map`,
    undefined,
    { params: { amazonAsin, amazonSku } }
  )

export const listMessages = (shopId: number | string, q: ListQuery & { platform?: string; status?: string } = {}) =>
  request.get<void, ApiResponse<PlatformMessage[]>>(`/multiplatform/message/list/${shopId}`, { params: q })

/** 内部分配处理人：只动本行记录，不会给平台或买家发任何东西 */
export const assignMessage = (messageId: number, assignedTo: string) =>
  request.post<void, ApiResponse<boolean>>(
    `/multiplatform/message/${messageId}/assign`,
    undefined,
    { params: { assignedTo } }
  )

export const listInventory = (shopId: number | string, q: ListQuery & { platform?: string } = {}) =>
  request.get<void, ApiResponse<PlatformInventory[]>>(
    `/multiplatform/inventory/list/${shopId}`,
    { params: q }
  )

export const aggregatedInventory = (shopId: number | string) =>
  request.get<void, ApiResponse<InventoryAggregate>>(
    `/multiplatform/inventory/aggregated/${shopId}`
  )

export const listWebhookEvents = (shopId: number | string, q: ListQuery & { status?: string } = {}) =>
  request.get<void, ApiResponse<WebhookEvent[]>>(
    `/multiplatform/webhook/list/${shopId}`,
    { params: q }
  )

export const registerApp = (app: OauthApp) =>
  request.post<void, ApiResponse<OauthSecretIssue>>('/multiplatform/oauth/app', app)

export const rotateAppSecret = (appId: number) =>
  request.post<void, ApiResponse<OauthSecretIssue>>(`/multiplatform/oauth/app/${appId}/rotate`)

export const listApps = (shopId: number | string, q: ListQuery = {}) =>
  request.get<void, ApiResponse<OauthApp[]>>(`/multiplatform/oauth/app/list/${shopId}`, { params: q })
