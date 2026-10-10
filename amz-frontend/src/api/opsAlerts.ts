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
 * 2. 跟卖告警原先只有读端点，2026-10-10 起补齐 handle/ignore 两个写入端点。
 * 3. 差评/跟卖的处置都是本地状态迁移（NEW → HANDLED / NEW → IGNORED），
 *    不会联系买家、不会发起申诉、也不会对跟卖方做任何动作。IGNORED 表示
 *    「判定为无需处理并关闭」，与 HANDLED 互斥：已处于终态的告警再次处置会被后端拒绝。
 *    IGNORED **不**抑制后续扫描重建告警——那是扫描侧职责，接真实数据源时再定。
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

/** 被追踪的 (关键词, ASIN) 组合：趋势查询的可选项 */
export interface TrackedKeyword {
  keyword: string
  asin: string
  pointCount?: number | null
  latestRank?: number | null
  lastCaptureTime?: string | null
  marketplace?: string | null
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

/** 标记差评告警已处理（NEW → HANDLED）；越权或不存在都会以业务错误返回，不返回可误读成成功的 false */
export const handleReviewAlert = (alertId: number) =>
  request.post<void, ApiResponse<boolean>>(`/ops/review/${alertId}/handle`)

/** 标记差评告警已忽略（NEW → IGNORED）：判定为无需处理，同样不做任何外部动作 */
export const ignoreReviewAlert = (alertId: number) =>
  request.post<void, ApiResponse<boolean>>(`/ops/review/${alertId}/ignore`)

/** 跟卖告警列表（keyset 分页） */
export const listHijackAlerts = (
  shopId: number | string,
  q: ListQuery & { status?: string } = {}
) =>
  request.get<void, ApiResponse<HijackAlert[]>>(`/ops/hijack/list/${shopId}`, { params: q })

/** 标记跟卖告警已处理（NEW → HANDLED） */
export const handleHijackAlert = (alertId: number) =>
  request.post<void, ApiResponse<boolean>>(`/ops/hijack/${alertId}/handle`)

/** 标记跟卖告警已忽略（NEW → IGNORED） */
export const ignoreHijackAlert = (alertId: number) =>
  request.post<void, ApiResponse<boolean>>(`/ops/hijack/${alertId}/ignore`)

/**
 * 本店被追踪的 (关键词, ASIN) 组合目录。
 *
 * 2026-10-10 新增：此前趋势查询要求 keyword 与 asin 都必填，而没有任何端点能列出
 * 「本店追踪了哪些组合」，运营只能凭记忆输入字面完全一致的关键词——输错一个空格
 * 就是「没有记录」，和「真的没抓过」在界面上无法区分。这个目录把可选项从表里读出来。
 *
 * 数据来源只有 amz_keyword_rank 表本身：只有抓过的组合才存在，不另建追踪清单。
 */
export const listTrackedKeywords = (shopId: number | string) =>
  request.get<void, ApiResponse<TrackedKeyword[]>>(`/ops/rank/keywords/${shopId}`)

/**
 * 关键词排名趋势：后端返回「最近若干个点」并按抓取时间升序。
 * keyword 与 asin 都是必填，但页面应先从 listTrackedKeywords 的目录里选，
 * 手输只是目录为空时的兜底。
 */
export const getRankTrend = (shopId: number | string, keyword: string, asin: string) =>
  request.get<void, ApiResponse<KeywordRankRecord[]>>('/ops/rank/trend', {
    params: { shopId, keyword, asin }
  })
