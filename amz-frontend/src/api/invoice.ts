import request from './auth'
import type { ApiResponse } from './types'

/**
 * 采购发票（三单匹配第三单，A3）。
 *
 * 与「供应商付款」的分工：那边管钱，这边管票。付款前置可引用本域的三方核对结论，
 * 但两边不交叉写状态。
 *
 * purchaseOrderId 可空：供应商常先开票、后对账，允许先登记后关联。
 */
export interface PurchaseInvoiceRow {
  id?: number
  shopId?: number | string
  invoiceNo: string
  purchaseOrderId?: number | null
  supplierName?: string | null
  invoiceAmount: number | string
  taxAmount?: number | string | null
  invoiceDate?: string | null
  /** UNMATCHED（已登记未关联）/ MATCHED（三方一致）/ CANCELLED（作废） */
  status?: string
  remark?: string | null
  createTime?: string
}

/** 三方核对结果。缺发票时结论是 MISSING_INVOICE，绝不是「匹配」。 */
export interface ThreeWayMatch {
  purchaseOrderId: number
  purchaseOrderNo: string
  poAmount: number | string
  receivedAmount: number | string | null
  invoiceAmount: number | string | null
  invoiceCount: number
  poMatchesInvoice: boolean
  receivedMatchesInvoice: boolean
  /** MATCHED / MISSING_INVOICE / PO_ONLY / MISMATCH */
  conclusion: string
  detail: string
}

/** 结论 → 页面文案与样式。未列出的结论按「未知」处理，不伪装成通过。 */
export const MATCH_CONCLUSION_LABEL: Record<string, string> = {
  MATCHED: '三方一致',
  MISSING_INVOICE: '缺发票',
  PO_ONLY: '票已到、货未到',
  MISMATCH: '金额不符'
}

export const registerInvoice = (body: Partial<PurchaseInvoiceRow>) =>
  request.post<void, ApiResponse<PurchaseInvoiceRow>>('/procurement/invoice', body)

export const listInvoices = (shopId: number | string, purchaseOrderId?: number) =>
  request.get<void, ApiResponse<PurchaseInvoiceRow[]>>(`/procurement/invoice/list/${shopId}`, {
    params: purchaseOrderId ? { purchaseOrderId } : {}
  })

/** 未关联任何 PO 的发票（先登记后对账的那批）。 */
export const listUnlinkedInvoices = (shopId: number | string) =>
  request.get<void, ApiResponse<PurchaseInvoiceRow[]>>(`/procurement/invoice/unlinked/${shopId}`)

export const matchInvoiceThreeWay = (shopId: number | string, purchaseOrderId: number) =>
  request.get<void, ApiResponse<ThreeWayMatch>>(
    `/procurement/invoice/match/${shopId}/${purchaseOrderId}`)

export const cancelInvoice = (id: number, shopId: number | string) =>
  request.post<void, ApiResponse<PurchaseInvoiceRow>>(`/procurement/invoice/${id}/cancel`, null, {
    params: { shopId }
  })
