import request from './auth'
import type { ApiResponse } from './types'

/**
 * Agent 记忆化接口（后端 AgentMemoryController，类级路径 /ai/agent/memory）。
 *
 * 身份口径：这四个端点都只认认证上下文。
 * - 路径里的 userId 仅用于「读谁的」，后端 requireSelfOrAdmin 会把它和登录用户比对，
 *   普通用户读别人的 id 直接 403；
 * - POST /preference 的 body 里 id 会被后端清空、userId 会被强制改写成登录用户，
 *   所以本页不需要（也不应该）上报 userId 作为写入归属。
 */

export interface AgentPreference {
  id?: number | null
  userId?: number | null
  nickname?: string | null
  preferredShopId?: number | null
  preferredShopName?: string | null
  preferredCategory?: string | null
  language?: string | null
  lastActiveTime?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface ConversationMemoryRow {
  id: number
  sessionId: string
  userId: number
  role: string
  content: string
  createTime: string
}

/** 与后端 LanguageEnum 同集合；顺序也按枚举声明顺序展示 */
export const AGENT_LANGUAGES = [
  { code: 'ZH', label: '简体中文' },
  { code: 'EN', label: 'English' },
  { code: 'JA', label: '日本語' },
  { code: 'DE', label: 'Deutsch' }
] as const

// 路径按字面量内联（与其余 api/*.ts 同风格）：抽成 BASE 常量会让端点清点工具
// 在源码里找不到完整路径，把「已接入」误判成「未接入」。
export const getAgentPreference = (userId: number | string) =>
  request.get<void, ApiResponse<AgentPreference>>(`/ai/agent/memory/preference/${userId}`)

/**
 * 更新偏好。只提交显式填写的字段：
 * 后端走 MyBatis-Plus updateById（非空字段才更新），留空提交不会清空原值，
 * 清空请走输入框删除后再保存（删除后是空串，这里仍会过滤掉，因此本页不承诺「清空」语义）。
 */
export const saveAgentPreference = (payload: {
  nickname?: string
  preferredShopId?: number
  preferredCategory?: string
}) => request.post<void, ApiResponse<AgentPreference>>('/ai/agent/memory/preference', payload)

/** 语言切换（便捷端点，未识别代码由后端拒绝而非静默回落中文） */
export const switchAgentLanguage = (language: string) =>
  request.post<void, ApiResponse<AgentPreference>>('/ai/agent/memory/language', null, {
    params: { language }
  })

/**
 * 对话记忆列表。limit 由后端校验 1..500，越界直接报参数错误，
 * 因此本页把候选限制在 20/50/100/200，不给「全部」。
 */
export const getAgentHistory = (userId: number | string, limit = 50) =>
  request.get<void, ApiResponse<ConversationMemoryRow[]>>(`/ai/agent/memory/history/${userId}`, {
    params: { limit }
  })
