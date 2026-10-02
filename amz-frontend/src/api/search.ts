import request from './auth'
import type { ApiResponse } from './types'

/**
 * 商品搜索接口层（amz-service-search，端口 8090，`/search/**`）。
 *
 * 动因（2026-10-02 功能覆盖清点 N4）：4 个端点前端零调用——ES 混合检索、Redis 热搜、
 * 用户搜索历史都在后端工作，但只能靠 curl 验证。
 *
 * 这一域和「店铺」无关：历史与热搜按**登录用户**归属（UserContext），检索也没有 shopId 维度，
 * 所以页面不受右上角店铺选择影响，也不该拿店铺当过滤条件。
 *
 * 三条会改变结果显示的事实：
 * 1. 检索打在 Elasticsearch 索引 `amz_product` 上（BM25，向量可用时再 RRF 融合）。
 *    ES 不在线时 `elasticsearchOperations.search` 直接抛出，接口返回错误——前端不退回示例数据。
 *    只有查询向量化失败才降级为纯 BM25（`hybridSearch` 里的 warn 分支）。
 * 2. 每搜一次会写一条 `amz_history`（同用户同关键词不重复插）并给热搜 ZSET +1 分；
 *    热搜 7 天无写入自动过期，超过 1000 条从低分端裁剪。
 * 3. 热搜为空时后端返回 `data = null`（不是空数组），清空历史会删掉当前用户的全部历史，
 *    没有按条删除的端点。
 */

export interface SearchProduct {
  id?: number
  title?: string
  content?: string
  summary?: string
  image?: string
  price?: number | null
  sku?: string
  shopId?: number | string | null
  userId?: number | null
  /** 由用户服务批量装配，Feign 失败时为 null，不代表商品没有卖家 */
  user?: { id?: number; nickname?: string; phone?: string } | null
}

export interface HotWord {
  key: string | number | null
  score: number | null
}

/** amz_history 只有这两列：没有 id，所以无法按条删除 */
export interface SearchHistory {
  history?: string
  userId?: number
}

export const searchProducts = (key: string) =>
  request.get<void, ApiResponse<SearchProduct[]>>(`/search/search/${encodeURIComponent(key)}`)

export const getHotList = () =>
  request.get<void, ApiResponse<HotWord[] | null>>('/search/getHotList')

export const getHistoryList = () =>
  request.get<void, ApiResponse<SearchHistory[]>>('/search/getHistoryList')

export const deleteHistory = () =>
  request.delete<void, ApiResponse<void>>('/search/deleteHistory')
