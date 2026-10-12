import request from './auth'
import type { ApiResponse } from './types'

/**
 * Listing 调价规则（P3.2）。
 *
 * 目标价与调整幅度二选一：targetPrice 直接定价；adjustPct 相对当前价调整
 * （正数上调、负数下调，-0.05 = 降价 5%）。后端按
 * (shopId, marketplaceId, sku) 做 upsert——同一组合再保存是更新而不是新增。
 *
 * 执行会真的经 listingsItems.patchListingsItem 下发 Amazon；无真实凭证时
 * 后端显式失败（API-ready 边界），规则上留 last_result 便于排查。
 */
export interface ListingPriceRuleRow {
  id?: number
  shopId?: number | string
  marketplaceId?: string
  sku?: string
  /** MANUAL_SCHEDULE（预留 TRACK_COMPETITOR 等） */
  ruleType?: string
  targetPrice?: number | string | null
  adjustPct?: number | string | null
  enabled?: number
  lastExecutedAt?: string | null
  lastResult?: string | null
  remark?: string | null
  createTime?: string
}

/**
 * 新增或更新调价规则。校验口径与后端一致（页面先挡一层，减少无效请求）：
 * shopId / marketplaceId / sku 必填；目标价与幅度至少一个；目标价 > 0；
 * 幅度不低于 -100%（价格不可为负）。
 */
export const savePriceRule = (body: Partial<ListingPriceRuleRow>) =>
  request.post<void, ApiResponse<ListingPriceRuleRow>>('/spapi/listing-price', body)

export const listPriceRules = (shopId: number | string) =>
  request.get<void, ApiResponse<ListingPriceRuleRow[]>>(`/spapi/listing-price/list/${shopId}`)

export const deletePriceRule = (id: number, shopId: number | string) =>
  request.delete<void, ApiResponse<boolean>>(`/spapi/listing-price/${id}`, { params: { shopId } })

/**
 * 手动执行一条规则：经 listingsItems patch 下发 Amazon。
 * 规则已停用时后端拒绝（要点名「请先启用」而不是静默什么都不做）。
 */
export const executePriceRule = (id: number, shopId: number | string) =>
  request.post<void, ApiResponse<ListingPriceRuleRow>>(`/spapi/listing-price/${id}/execute`, null, {
    params: { shopId }
  })
