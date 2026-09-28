import request from './auth'
import type { ApiResponse } from './types'

// 会计凭证（业财一体化核心，复式记账）
export interface AccountingVoucher {
  id: number
  voucherNo: string
  shopId: number
  bizDate: string
  summary: string
  debitAccount: string
  creditAccount: string
  originalAmount: number | string
  currency: string
  exchangeRate: number | string
  cnyAmount: number | string
  /** 业务来源：ORDER/PROCUREMENT/PLATFORM_FEE/REFUND */
  sourceType: string
  sourceNo: string
  /** 同步金蝶状态：PENDING / SYNCED / SYNCING / FAILED */
  kingdeeSyncStatus: string
}

/**
 * 凭证列表（服务端游标分页）。
 *
 * 后端不再一次性返回最多 500 条：凭证由调度器持续写入，
 * 硬编码上限会让「看起来正常的 200 响应」成为漏单来源。
 * 这里改为按 cursor 逐页取，并用 _page.truncated 判断是否还有下一页。
 */
export const listVouchers = (
  shopId: number | string,
  sourceType?: string,
  size?: number,
  cursor?: string
) => {
  const params: Record<string, string | number> = {}
  if (sourceType) params.sourceType = sourceType
  if (size) params.size = size
  if (cursor) params.cursor = cursor
  return request.get<void, ApiResponse<AccountingVoucher[]>>(`/finance/voucher/list/${shopId}`, {
    params
  })
}

/** 金蝶同步结果的机器可读状态。MOCK 明确表示未真实入账。 */
export type KingdeeSyncStatus =
  | 'SYNCED'
  | 'MOCK'
  | 'SKIPPED'
  | 'FAILED'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  | 'NOT_CONFIGURED'

export interface KingdeeSyncResult {
  status: KingdeeSyncStatus
  voucherId: number
  kingdeeNo: string | null
  message: string
}

// 同步凭证到金蝶
export const syncToKingdee = (voucherId: number | string) => {
  return request.post<void, ApiResponse<KingdeeSyncResult>>(`/finance/voucher/${voucherId}/sync`)
}

// 查询店铺利润（CNY）
export const calculateProfit = (shopId: number | string, startDate?: string, endDate?: string) => {
  return request.get<void, ApiResponse<number | string>>(`/finance/profit/${shopId}`, {
    params: { startDate, endDate }
  })
}