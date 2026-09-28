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