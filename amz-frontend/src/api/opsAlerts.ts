import request from './auth'
import type { ApiResponse } from './types'

/**
 * 运营预警接口层（amz-service-ops，`/ops/**`）。
 *
 * 动因（2026-10-03 端点覆盖清点）：OpsController 的 7 条端点在零前端命中，
 * 差评告警、跟卖告警、关键词排名这三张表在界面上完全不存在——即使行已经躺在库里，
 * 也没有任何入口能看见或处置它们。
 *
 * 三条必须知道的事实，页面文案要照这个说：
 * 1. `POST /ops/review/scan/{shopId}`、`POST /ops/hijack/scan/{shopId}`、
 *    `POST /ops/rank/capture/{shopId}` 三个「扫描」是 ThreadLocalRandom 造数，
 *    并且只有 mock profile 才放行（生产环境直接返回 0）。所以本文件不提供这三个调用，
 *    页面也不放扫描按钮——放了一个只会往告警表里写随机 ASIN 的按钮，比没有按钮更糟。
 * 2. 跟卖告警**只有读端点**：后端没有 handle/ignore 的写入路径，
 *    所以「已处理」这个动作只能出现在差评告警上。
 * 3. 差评告警的处理是本地状态迁移（NEW → HANDLED），不会联系买家、不会发起申诉，
 *    DDL 里还有第三种状态 IGNORED，但没有对应的写入端点，页面不能替它编一个。
 *
 * 状态枚举取自 V1 迁移的列注释：status VARCHAR(10) COMMENT 'NEW/HANDLED/IGNORED'。
 * 告警行有 create_time（数据库默认值），排名行的 capture_time 是 'yyyy-MM-dd HH:mm:ss' 字符串。
 */

export const ALERT_STATUSES = ['NEW', 'HANDLED', 'IGNORED']

export interface NegativeReviewAlert {
  id?: number
  shopId?: number | string
  asin: string
  reviewId?: string | null
  rating?: number | null
  title?: string | null
  content?: string | null
  reviewer?: string | null
  status?: string | null
  createTime?: string | null
}

export interface HijackAlert {
  id?: number
  shopId?: number | string
  asin: string
  hijackerSellerId?: string | null
  hijackerName?: string | null
  hijackPrice?: number | string | null
  buyBoxTaken?: boolean | null
  status?: string | null
  createTime?: string | null
}

export interface KeywordRankRecord {
  id?: number
  shopId?: number | string
  keyword: string
  asin: string
  rank?: number | null
  marketplace?: string | null
  captureTime?: string | null
}

interface ListQuery {
  size?: number
  cursor?: string | null
}

/** 差评告警列表（keyset 分页，按 id 倒序；status 可选 NEW/HANDLED/IGNORED） */
export const listReviewAlerts = (
  shopId: number | string,
  q: ListQuery & { status?: string } = {}
) =>
  request.get<void, ApiResponse<NegativeReviewAlert[]>>(`/ops/review/list/${shopId}`, { params: q })

/** 标记差评告警已处理；越权或不存在都会以业务错误返回，不返回可误读成成功的 false */
export const handleReviewAlert = (alertId: number) =>
  request.post<void, ApiResponse<boolean>>(`/ops/review/${alertId}/handle`)

/** 跟卖告警列表（keyset 分页；这张表没有处理端点，页面只提供读） */
export const listHijackAlerts = (
  shopId: number | string,
  q: ListQuery & { status?: string } = {}
) =>
  request.get<void, ApiResponse<HijackAlert[]>>(`/ops/hijack/list/${shopId}`, { params: q })

/**
 * 关键词排名趋势：后端返回「最近若干个点」并按抓取时间升序。
 * keyword 与 asin 都是必填——没有「列出本店所有被追踪关键词」的端点，
 * 所以页面必须先让人填这两项，不能假装这是一个列表。
 */
export const getRankTrend = (shopId: number | string, keyword: string, asin: string) =>
  request.get<void, ApiResponse<KeywordRankRecord[]>>('/ops/rank/trend', {
    params: { shopId, keyword, asin }
  })
