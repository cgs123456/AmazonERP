import request from './auth'

/**
 * AI 域的两个 HTTP 契约。
 *
 * 放在 api/ 而不是散在组件里的原因有两个：端点清点工具只扫 src/api/*.ts，
 * 契约藏在组件里会被统计成「未接入」；更重要的是 Agent 的两条链路（SSE 主、POST 兜底）
 * 共用同一个基址与同一套超时口径，分两处写迟早漂移。
 */

/** 后端 Result<String> 经拦截器拆包后的形状 */
export interface ErpAgentResult {
  code: number
  message: string
  data: unknown
}

/**
 * POST /ai/erp/agent — 非流式兜底链路。
 * 不传 userId：后端只认认证上下文，它会用登录用户拼 ChatMemory 会话键。
 * signal 用于切换轮次/卸载时中止在途请求。
 */
export const erpAgentChat = (message: string, signal?: AbortSignal) =>
  request.post<void, ErpAgentResult>('/ai/erp/agent', { message }, { timeout: 60000, signal })

/**
 * GET /ai/chat-stream 的完整 URL（SSE 走原生 fetch，因为要读 ReadableStream）。
 * 基址与统一 request 实例一致；单测里 request 被 mock 成裸对象、defaults 缺失时回退同源 /api。
 */
export const chatStreamUrl = (message: string): string => {
  const params = new URLSearchParams({ message })
  return `${request.defaults?.baseURL || '/api'}/ai/chat-stream?${params.toString()}`
}
