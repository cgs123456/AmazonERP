import request from './auth'
import type { ApiResponse } from './types'

export type ConnectorOperationStatus = 'IMPLEMENTED' | 'NOT_IMPLEMENTED'

export interface ConnectorOperation {
  id: string
  path: string
  status: ConnectorOperationStatus
  note: string
}

export interface ConnectorCapability {
  code: string
  name: string
  enabled: boolean
  profile: string
  mockActive: boolean
  credentialSource: 'db' | 'none' | string
  credentialCount: number
  implementedCount: number
  notImplementedCount: number
  operations: ConnectorOperation[]
  evidenceLevel: string
  apiReady: boolean
  reachable: boolean
  displayText: string
  blockerSummary: string
  criteria: Record<string, string>
  lastCallAt: string | null
  lastResult: 'SUCCESS' | 'FAILED' | 'NEVER_RUN' | string
  lastOutcomeCode: string
}

export type ConnectorPreflightStageStatus = 'PASS' | 'FAIL' | 'SKIP'

export interface ConnectorPreflightStage {
  name: 'CREDENTIAL' | 'LWA_TOKEN' | 'READ_API' | string
  status: ConnectorPreflightStageStatus
  durationMs: number
  errorCode: string | null
  platformStatus: number | null
  remediation: string | null
  detail: string
}

export interface ConnectorPreflightReport {
  shopId: number
  marketplaceId: string | null
  region: string | null
  host: string | null
  endpointOverridden: boolean
  checkedAt: string
  ready: boolean
  stages: ConnectorPreflightStage[]
  nextAction: string
}

export interface ConnectorSelfTestData {
  connector: string
  operation: string
  ok: boolean
  outcomeCode: string
  detail: string
  elapsedMs: number
  itemCount: number
  at: string
  evidenceLevel?: string
  displayText?: string
  error?: unknown
}

/** 能力清单只读端点（网关别名 /api/connectors）。 */
export const listConnectors = () => {
  return request.get<void, ApiResponse<ConnectorCapability[]>>('/connectors')
}


/**
 * SP-API 调用发件箱与限流观测（原 `/spapi/connectors/**` 的队列部分）。
 *
 * 动因（2026-10-03 端点覆盖清点）：outbox/replay/rate-limits 三条只有 curl 能碰，
 * 而这是「同步失败之后怎么办」的唯一现场——DLQ 记录堆在后端没人看得见。
 *
 * 三条会影响判断的事实：
 * 1. 列表按 JWT 里的授权店铺过滤：非 ADMIN 且没有任何授权店铺时后端直接拒，
 *    只有 ADMIN 的空 shops 才被解释成全局范围；
 * 2. 自动重放调度只碰 GET/HEAD；**人工点「重放」会按原方法重发**，
 *    POST/PUT/PATCH/DELETE 可能有远端副作用，所以这一步必须走二次确认；
 * 3. outbox 未启用（对应 Bean 不存在）时后端返回失败而不是空列表，前端不能显示成「队列为空」。
 */

/** 状态常量取自 SpApiCallOutboxService，DLQ 只能人工重放 */
export const OUTBOX_STATUSES = ['PENDING', 'SUCCEEDED', 'FAILED', 'REPLAYING', 'REPLAYED', 'DLQ']

export interface OutboxRecord {
  id?: number
  shopId?: number | string | null
  operationId?: string
  httpMethod?: string
  requestPath?: string
  status?: string
  attemptCount?: number | null
  maxAttempts?: number | null
  responseStatus?: number | string | null
  marketplaceId?: string | null
  responseRequestId?: string | null
  lastErrorCode?: string | null
  lastErrorMessage?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  completedAt?: string | null
  rateLimitVariant?: string | null
}

export interface ReplayResult {
  success: boolean
  outcome?: string
  status?: string | null
  message?: string | null
}

export interface RateLimitObservation {
  shopId?: number | string | null
  operationId?: string
  variant?: string | null
  headerValue?: string | null
  observedRatePerSecond?: number | string | null
  effectiveRatePerSecond?: number | string | null
  burst?: number | null
  observedAt?: string | null
}

export const listOutbox = (q: { status?: string; limit?: number } = {}) => {
  const params: Record<string, unknown> = {}
  if (q.status) params.status = q.status
  if (q.limit) params.limit = q.limit
  return request.get<void, ApiResponse<OutboxRecord[]>>('/connectors/outbox', { params })
}

/** 人工重放：按记录原方法重发，写操作会有远端副作用 */
export const replayOutbox = (id: number) =>
  request.post<void, ApiResponse<ReplayResult>>(`/connectors/outbox/${id}/replay`)

/** 观测到的限流参数（来自真实响应头），没有跑过的操作不会出现在这里 */
export const listRateLimits = () =>
  request.get<void, ApiResponse<RateLimitObservation[]>>('/connectors/rate-limits')

/** 只读自检端点；当前后端仅允许 OPERATOR / ADMIN 调用。 */
export const selfTestConnector = (code: string, shopId: number) => {
  return request.post<void, ApiResponse<ConnectorSelfTestData>>(
    `/connectors/${encodeURIComponent(code)}/self-test`,
    { shopId }
  )
}

/**
 * SP-API 分层连通性自检：凭证 → LWA token → 只读 API。
 *
 * <p>forceTokenRefresh=true 会先失效 token 缓存，刚更换 refresh_token 时必须使用；
 * 否则旧 token 可能产生假绿灯。</p>
 */
export const preflightConnector = (
  code: string,
  shopId: number,
  forceTokenRefresh = false
) => {
  if (code !== 'spapi') {
    return Promise.reject(new Error(`连接器 ${code} 暂不支持分层连通性自检`))
  }
  return request.post<void, ApiResponse<ConnectorPreflightReport>>(
    `/preflight/shop/${shopId}`,
    undefined,
    { params: { forceTokenRefresh } }
  )
}
export interface ConnectorCredentialStatus {
  configured: boolean
  clientIdConfigured: boolean
  clientSecretConfigured: boolean
  refreshTokenConfigured: boolean
  awsKeysConfigured: boolean
  region: string | null
  marketplaceId: string | null
  sellerId: string | null
  updatedAt: string | null
}

/** 仅写不回显的 SP-API 凭证部分更新；空白字段由后端保留旧值。 */
export interface ConnectorCredentialUpdate {
  clientId?: string
  clientSecret?: string
  refreshToken?: string
  accessKey?: string
  secretKey?: string
  region?: string
  marketplaceId?: string
  sellerId?: string
  clearAwsKeys?: boolean
}

/** 读取店铺 SP-API 凭证的脱敏配置状态；不存在密文字段。 */
export const getConnectorCredentialStatus = (shopId: number) => {
  return request.get<void, ApiResponse<ConnectorCredentialStatus>>(
    `/credentials/shop/${shopId}/status`
  )
}

/** 新建、轮换或部分更新店铺 SP-API 凭证。 */
export const saveConnectorCredential = (shopId: number, payload: ConnectorCredentialUpdate) => {
  return request.put<void, ApiResponse<ConnectorCredentialStatus>>(
    `/credentials/shop/${shopId}`,
    payload
  )
}

/** 删除店铺 SP-API 凭证；后端同时失效对应 LWA token。 */
export const deleteConnectorCredential = (shopId: number) => {
  return request.delete<void, ApiResponse<ConnectorCredentialStatus>>(
    `/credentials/shop/${shopId}`
  )
}