import request from './auth'
import type { ApiResponse } from './types'

/**
 * 广告搜索词分析与规则接口层（amz-service-ad，`/ad/search-term/**`）。
 *
 * 动因（2026-10-03 端点覆盖清点）：这一组 15 个端点全部只有 curl 能碰，
 * 广告侧「词怎么判、规则怎么定」在界面上完全不可见。
 *
 * 四条会直接影响判断、但又不在前端能改的事实：
 * 1. **搜索词报表没有自动来源**。`AdvertisingApiClient` 只有 campaigns/keywords/日报表四个
 *    方法，没有搜索词报表；全仓唯一的写入口就是本文件的 `saveSearchTerm`。
 *    所以没有先录入数据时，分析与规则命中都必然是空的，这不是「搜索词表现良好」。
 * 2. **规则执行只产出建议**。`AdAutoRuleServiceImpl#executeRule` 不调用任何广告 API，
 *    返回体固定带 `appliedToAdAccount:false`；真实改价只有分时调价一条通道（后端定时任务）。
 * 3. **没有调度器跑规则**。全仓找不到 executeRules 的定时调用，「自动」= 人工点。
 * 4. `rule_type` 只是分类标签，判定不看它；改判定要改 `condition_*` 与 `action`。
 *
 * 枚举取自 V1 迁移的列注释与执行分支：非法值在写入时就会被后端拒掉，
 * 所以页面不提供 `ENABLE`（DDL 注释里有，代码无分支）和 `ASIN` 范围（报表表无该字段）。
 */

export const RULE_CONDITION_FIELDS = ['ACOS', 'CR', 'CTR', 'CPC', 'SPEND', 'SALES', 'IMPRESSIONS']
export const RULE_CONDITION_OPS = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'BETWEEN']
export const RULE_ACTIONS = ['PAUSE', 'INCREASE_BID', 'DECREASE_BID', 'ADD_NEGATIVE', 'INCREASE_BUDGET', 'DECREASE_BUDGET']
/** 后端可落地的作用范围；ASIN 会被明确拒绝 */
export const RULE_SCOPES = ['KEYWORD', 'CAMPAIGN']
/** 分类标签：取自 V1 预置规则，不参与判定，允许自定义 */
export const RULE_TYPE_PRESETS = ['KEYWORD_PAUSE', 'KEYWORD_BID', 'CAMPAIGN_BUDGET', 'NEGATIVE_KEYWORD']
/** 比例类动作按百分数解释 */
export const PERCENT_ACTIONS = ['INCREASE_BID', 'DECREASE_BID', 'INCREASE_BUDGET', 'DECREASE_BUDGET']

export interface AdAutoRule {
  id?: number
  shopId?: number | string
  ruleName: string
  ruleType: string
  scope?: string | null
  scopeValue?: string | null
  conditionField: string
  conditionOp: string
  conditionValue: number | string
  conditionValue2?: number | string | null
  action: string
  actionValue?: number | string | null
  timeWindow?: number | null
  priority?: number | null
  enabled?: number | null
  lastExecuted?: string | null
  createTime?: string
}

export interface AdSearchTerm {
  id?: number
  shopId?: number | string
  campaignId: string
  keywordId?: number | string | null
  searchTerm: string
  matchType?: string | null
  impressions?: number | null
  clicks?: number | null
  cost?: number | string | null
  sales?: number | string | null
  orders?: number | null
  acos?: number | string | null
  cr?: number | string | null
  ctr?: number | string | null
  cpc?: number | string | null
  reportDate?: string | null
  createTime?: string
}

export interface ConvertingTerm {
  id?: number
  shopId?: number | string
  /** 自动提取不带 ASIN，只有外部写入的才有值 */
  asin?: string | null
  searchTerm: string
  campaignId?: string | null
  totalOrders?: number | null
  totalSales?: number | string | null
  totalCost?: number | string | null
  avgAcos?: number | string | null
  firstSeen?: string | null
  lastSeen?: string | null
  isAddedToKeyword?: number | null
  status?: string | null
}

export interface AdAsinKeyword {
  id?: number
  shopId?: number | string
  asin: string
  keyword: string
  organicRank?: number | null
  adRank?: number | null
  searchVolume?: number | null
  relevanceScore?: number | string | null
  isIndexed?: number | null
  lastChecked?: string | null
}

export interface AnalyzeResult {
  shopId?: number | string
  analysisPeriod: number
  scannedRows: number
  totalSearchTerms: number
  convertingTerms: number
  wasteTerms: number
  highAcosTerms: number
  lowCrTerms: number
  totalCost: number | string
  totalSales: number | string
  wasteCost: number | string
  overallAcos: number | string
  topConvertingTerms: Array<{
    searchTerm: string
    orders?: number | null
    sales?: number | string | null
    cost?: number | string | null
    acos?: number | string | null
  }>
  topWasteTerms: Array<{
    searchTerm: string
    cost?: number | string | null
    impressions?: number | null
    clicks?: number | null
  }>
}

export interface ClusterResult {
  shopId?: number | string
  scannedRows: number
  totalClusters: number
  topClusters: Array<{
    root: string
    termCount: number
    totalImpressions: number
    totalClicks: number
    totalCost: number | string
    totalSales: number | string
    totalOrders: number
  }>
}

export interface RuleActionSuggestion {
  searchTerm: string
  matchedValue?: number | string | null
  action?: string
  suggestion?: string
  applied?: boolean
}

export interface RuleExecutionResult {
  ruleId?: number
  ruleName?: string
  ruleType?: string
  actionCount: number
  appliedToAdAccount?: boolean
  skipped?: string
  note?: string
  matchedActions?: RuleActionSuggestion[]
  error?: string
}

export interface RuleBatchResult {
  shopId?: number | string
  rulesExecuted: number
  totalActions: number
  ruleResults: RuleExecutionResult[]
}

interface Query {
  size?: number
  cursor?: string | null
}

// ==================== 搜索词报表 ====================

/** 唯一的数据入口：手工录入一行搜索词报表（后端会重算 ctr/cpc/cr/acos） */
export const saveSearchTerm = (payload: AdSearchTerm) =>
  request.post<void, ApiResponse<AdSearchTerm>>('/ad/search-term', payload)

export const listSearchTerms = (shopId: number | string, q: Query & {
  campaignId?: string
  searchTerm?: string
} = {}) =>
  request.get<void, ApiResponse<AdSearchTerm[]>>(`/ad/search-term/list/${shopId}`, { params: q })

export const analyzeSearchTerms = (shopId: number | string, q: { campaignId?: string; days?: number } = {}) =>
  request.get<void, ApiResponse<AnalyzeResult>>(`/ad/search-term/analyze/${shopId}`, { params: q })

export const clusterSearchTerms = (shopId: number | string, q: { campaignId?: string; days?: number } = {}) =>
  request.get<void, ApiResponse<ClusterResult>>(`/ad/search-term/cluster/${shopId}`, { params: q })

// ==================== 出单词库 ====================

/** 覆盖式提取：按请求窗口重算并 upsert，重复点不会把同一批订单再累加一次 */
export const extractConvertingTerms = (shopId: number | string, days = 7) =>
  request.post<void, ApiResponse<ConvertingTerm[]>>(
    `/ad/search-term/converting/extract/${shopId}`,
    undefined,
    { params: { days } }
  )

export const listConvertingTerms = (shopId: number | string, q: Query & { asin?: string } = {}) =>
  request.get<void, ApiResponse<ConvertingTerm[]>>(
    `/ad/search-term/converting/list/${shopId}`,
    { params: q }
  )

// ==================== ASIN 关键词反查 ====================

export const reverseLookupAsin = (shopId: number | string, asin: string, q: Query = {}) =>
  request.get<void, ApiResponse<AdAsinKeyword[]>>(
    `/ad/search-term/asin-reverse/${shopId}`,
    { params: { asin, ...q } }
  )

/** 批量导入反查快照：给外部抓取/导入脚本用，页面只提供整批 JSON 粘贴 */
export const saveAsinKeywords = (keywords: AdAsinKeyword[]) =>
  request.post<void, ApiResponse<AdAsinKeyword[]>>('/ad/search-term/asin-reverse/batch', keywords)

// ==================== 规则 ====================

export const createRule = (rule: AdAutoRule) =>
  request.post<void, ApiResponse<AdAutoRule>>('/ad/search-term/rule', rule)

/** PUT 可只带被改字段，后端按「库里现值 + 本次非空覆盖」校验 */
export const updateRule = (id: number, rule: Partial<AdAutoRule>) =>
  request.put<void, ApiResponse<AdAutoRule>>(`/ad/search-term/rule/${id}`, rule)

export const listRules = (shopId: number | string, q: Query & { ruleType?: string } = {}) =>
  request.get<void, ApiResponse<AdAutoRule[]>>(`/ad/search-term/rule/list/${shopId}`, { params: q })

export const toggleRule = (id: number, enabled: boolean) =>
  request.post<void, ApiResponse<boolean>>(
    `/ad/search-term/rule/${id}/toggle`,
    undefined,
    { params: { enabled } }
  )

export const deleteRule = (id: number) =>
  request.delete<void, ApiResponse<boolean>>(`/ad/search-term/rule/${id}`)

/** 逐条扫描全部启用规则，只出建议 */
export const executeRules = (shopId: number | string) =>
  request.post<void, ApiResponse<RuleBatchResult>>(`/ad/search-term/rule/execute/${shopId}`)

/** 单条规则出建议；未启用的规则后端直接 skipped */
export const executeRule = (ruleId: number) =>
  request.post<void, ApiResponse<RuleExecutionResult>>(`/ad/search-term/rule/${ruleId}/execute`)

/** 供页面复用：把后端返回的任意数值安全转成可展示的字符串 */
export const asText = (value: unknown): string =>
  value === null || value === undefined || value === '' ? '-' : String(value)

/** 金额/比率格式化：后端 BigDecimal 序列化成字符串，null 与 0 必须区分开 */
export const asNumber = (value: unknown, digits = 2): string | null => {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(digits) : String(value)
}
