import request from './auth'
import type { ApiResponse } from './types'

/**
 * Agent 评测回归接口（后端 AiController，`POST /ai/eval/run`）。
 *
 * 两种模式（语义取自 AiController:102-125，不是推测）：
 * - `keyword`（缺省）：只跑本地 12 条用例的关键字判定，**不需要模型 key**，
 *   每次运行由后端 best-effort 落库 `amz_agent_eval_log`；
 * - `both`：额外用 LLM 打分，需 `AGENT_LLM_EVAL_ENABLED=true` 且配置 `deepseek.api-key`，
 *   不满足时后端直接返回业务失败（code 400 + 明确文案），不会静默降级成 keyword 结果。
 *
 * 因此页面拿到失败就报失败，不能显示「0 通过」——那是两种完全不同的事实。
 */

export type EvalMode = 'keyword' | 'both'

export const EVAL_MODES: { code: EvalMode; label: string; needsKey: boolean }[] = [
  { code: 'keyword', label: '关键词判定（无需模型密钥）', needsKey: false },
  { code: 'both', label: '关键词 + LLM 打分（需模型密钥）', needsKey: true }
]

export interface AgentEvalCaseResult {
  caseId?: string
  passed?: boolean
  actualResponse?: string | null
  matchedKeywords?: string[] | null
  missedKeywords?: string[] | null
  durationMs?: number | null
  errorMessage?: string | null
  llmScore?: unknown | null
}

export interface AgentEvalReport {
  timestamp?: string | null
  totalCases?: number | null
  passedCount?: number | null
  failedCount?: number | null
  passRate?: number | null
  totalDurationMs?: number | null
  results?: AgentEvalCaseResult[] | null
  agentVersion?: string | null
  evalMode?: string | null
}

// 路径按字面量内联：抽成 BASE 常量会让端点清点工具在源码里找不到完整路径，
// 把「已接入」误判成「未接入」。mode 走查询参数（后端是 @RequestParam）。
export const runAgentEval = (mode: EvalMode) =>
  request.post<void, ApiResponse<AgentEvalReport>>(`/ai/eval/run?mode=${mode}`)
