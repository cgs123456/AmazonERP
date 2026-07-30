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

// 凭证列表
export const listVouchers = (shopId: number | string, sourceType?: string) => {
  return request.get<void, ApiResponse<AccountingVoucher[]>>(`/finance/voucher/list/${shopId}`, {
    params: sourceType ? { sourceType } : {}
  })
}

// 同步凭证到金蝶
export const syncToKingdee = (voucherId: number | string) => {
  return request.post<void, ApiResponse<boolean>>(`/finance/voucher/${voucherId}/sync`)
}

// 查询店铺利润（CNY）
export const calculateProfit = (shopId: number | string, startDate?: string, endDate?: string) => {
  return request.get<void, ApiResponse<number | string>>(`/finance/profit/${shopId}`, {
    params: { startDate, endDate }
  })
}