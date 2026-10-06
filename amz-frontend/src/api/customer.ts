import request from './auth'
import type { ApiResponse } from './types'
import { params } from '@/utils/query'

/**
 * 客服域接口层（amz-service-customer，端口 8099）。
 *
 * 动因（2026-10-02 功能覆盖清点）：客服 21 个端点零前端入口——工单分类、邮件模板与任务、
 * 差评跟进、RMA 全链路后端都在落库，但运营只能靠 curl。
 *
 * 三个「通道没接」的事实必须在界面上说清，否则 UI 会把没发生的事显示成已办：
 * 1. 邮件发送通道未接入（模块内没有 SMTP/Messaging 客户端）。非 mock 档 `process/{shopId}`
 *    只会把任务标 FAILED 并写 failureReason；mock 档是模拟发送，不是真发信。
 * 2. 索评（review/solicit）与差评匹配订单（negative-review/{id}/match）在 mock 档会造
 *    SIMULATED-* 行，非 mock 直接抛错。
 * 3. 工单分类是关键词 contains，不是模型判定（TicketClassifier）。
 */

export interface CustomerTicket {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  buyerId?: string
  buyerName?: string
  channel?: string
  content?: string
  category?: string
  priority?: string
  sentiment?: string
  status?: string
  reply?: string
  createTime?: string
}

export interface EmailTemplate {
  id?: number
  shopId?: number | string
  templateName: string
  templateType?: string
  subject?: string
  body: string
  language?: string
  triggerEvent?: string
  triggerDelayHours?: number | null
  /** 后端存 0/1 整数，不是布尔 */
  enabled?: number | boolean | null
}

export interface EmailTask {
  id?: number
  shopId?: number | string
  templateId?: number | null
  amazonOrderId?: string
  asin?: string
  buyerEmail?: string
  buyerName?: string
  subject?: string
  body?: string
  status?: string
  scheduledTime?: string
  sentTime?: string
  failureReason?: string | null
  source?: string
}

export interface NegativeReview {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  asin: string
  reviewerName?: string
  reviewRating: number
  reviewTitle?: string
  reviewContent?: string
  reviewDate?: string
  reviewId?: string
  verifiedPurchase?: number | null
  status?: string
  matchedOrderId?: string | null
  contactEmailTaskId?: number | null
}

export interface Rma {
  id?: number
  shopId?: number | string
  rmaNo?: string
  amazonOrderId: string
  asin?: string
  sku?: string
  returnReason?: string
  returnType?: string
  productCondition?: string
  refundAmount?: number | string | null
  labelUrl?: string
  labelCost?: number | string | null
  carrier?: string
  trackingNo?: string
  status?: string
  remark?: string
}

export interface ReviewSolicitation {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  asin?: string
  status?: string
  channel?: string
  failureReason?: string | null
}

/** 工单状态取自 DDL；分类是关键词规则的结果，不是模型判定 */
export const TICKET_STATUS = ['PENDING', 'ASSIGNED', 'REPLIED', 'RESOLVED', 'ESCALATED']
export const TICKET_CHANNELS = ['MESSAGE', 'REVIEW', 'RETURN', 'A_TO_Z']
export const TASK_STATUS = ['PENDING', 'SENT', 'FAILED', 'OPTED_OUT', 'SKIPPED']
export const REVIEW_STATUS = ['DETECTED', 'MATCHED', 'CONTACTED', 'RESOLVED', 'IGNORED']
export const RMA_STATUS = ['PENDING', 'APPROVED', 'LABEL_SENT', 'IN_TRANSIT', 'RECEIVED', 'PROCESSED', 'CANCELLED']
export const RMA_TYPES = ['REFUND', 'REPLACE', 'RETURN']
export const RMA_CONDITIONS = ['NEW', 'OPENED', 'UNOPENED', 'USED_DAMAGED']
/** 非 mock 环境下必然失败/抛错的三个动作，界面上要标出来 */
export const CHANNEL_NOT_INTEGRATED = ['process', 'match', 'solicit'] as const


/* ==================== 工单 ==================== */

export const receiveTicket = (body: Partial<CustomerTicket>) =>
  request.post<void, ApiResponse<CustomerTicket>>('/customer/ticket', body)

export const replyTicket = (ticketId: number, reply: string) =>
  request.post<void, ApiResponse<CustomerTicket>>(`/customer/ticket/${ticketId}/reply`, null, { params: { reply } })

export const listTickets = (shopId: number | string,
                            q: { status?: string; category?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<CustomerTicket[]>>(`/customer/ticket/list/${shopId}`, { params: params(q) })

/* ==================== 索评 ==================== */

/** ⚠ 只发一次"请评价"给真实订单需要 SP-API Request a Review；非 mock 档后端直接抛错 */
export const solicitReviews = (shopId: number | string) =>
  request.post<void, ApiResponse<number>>(`/customer/review/solicit/${shopId}`)

export const listSolicitations = (shopId: number | string, q: { size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<ReviewSolicitation[]>>(`/customer/review/list/${shopId}`, { params: params(q) })

/* ==================== 邮件模板 ==================== */

export const createTemplate = (body: Partial<EmailTemplate>) =>
  request.post<void, ApiResponse<EmailTemplate>>('/customer/email/template', body)

export const updateTemplate = (id: number, body: Partial<EmailTemplate>) =>
  request.put<void, ApiResponse<EmailTemplate>>(`/customer/email/template/${id}`, body)

export const listTemplates = (shopId: number | string,
                              q: { templateType?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<EmailTemplate[]>>(`/customer/email/template/list/${shopId}`, { params: params(q) })

export const toggleTemplate = (id: number, enabled: boolean) =>
  request.post<void, ApiResponse<boolean>>(`/customer/email/template/${id}/toggle`, null, { params: { enabled } })

/* ==================== 邮件任务 ==================== */

/** 按事件匹配启用中的模板生成任务；eventType 用模板自己的 triggerEvent 值，不在前端编枚举 */
export const triggerEmail = (shopId: number | string, body: {
  eventType: string; orderId: string; asin?: string; buyerEmail?: string; buyerName?: string; trackingNo?: string
}) =>
  request.post<void, ApiResponse<EmailTask>>(`/customer/email/trigger/${shopId}`, null, { params: params({ ...body }) })

/** ⚠ 通道未接入：非 mock 只会把 PENDING 任务标 FAILED 并写原因 */
export const processPendingEmails = (shopId: number | string) =>
  request.post<void, ApiResponse<Record<string, unknown>>>(`/customer/email/process/${shopId}`)

export const listEmailTasks = (shopId: number | string, q: { status?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<EmailTask[]>>(`/customer/email/task/list/${shopId}`, { params: params(q) })

export const createManualTask = (body: Partial<EmailTask>) =>
  request.post<void, ApiResponse<EmailTask>>('/customer/email/task/manual', body)

/* ==================== 差评跟进 ==================== */

export const saveNegativeReview = (body: Partial<NegativeReview>) =>
  request.post<void, ApiResponse<NegativeReview>>('/customer/email/negative-review', body)

export const listNegativeReviews = (shopId: number | string,
                                    q: { status?: string; minRating?: number; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<NegativeReview[]>>(`/customer/email/negative-review/list/${shopId}`, { params: params(q) })

/** ⚠ mock 档才可用：非 mock 抛错，mock 档会写 SIMULATED-MATCH-* 假订单号 */
export const matchReviewToOrder = (reviewId: number) =>
  request.post<void, ApiResponse<Record<string, unknown>>>(`/customer/email/negative-review/${reviewId}/match`)

export const followUpNegativeReview = (reviewId: number) =>
  request.post<void, ApiResponse<EmailTask>>(`/customer/email/negative-review/${reviewId}/followup`)

/* ==================== RMA ==================== */

export const createRma = (body: Partial<Rma>) =>
  request.post<void, ApiResponse<Rma>>('/customer/email/rma', body)

export const listRmas = (shopId: number | string, q: { status?: string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<Rma[]>>(`/customer/email/rma/list/${shopId}`, { params: params(q) })

export const rmaDetail = (rmaId: number) =>
  request.get<void, ApiResponse<Rma>>(`/customer/email/rma/${rmaId}`)

export const updateRmaStatus = (rmaId: number, status: string) =>
  request.post<void, ApiResponse<Rma>>(`/customer/email/rma/${rmaId}/status`, null, { params: { status } })
