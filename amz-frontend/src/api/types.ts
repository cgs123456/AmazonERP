// 统一的 API 响应类型定义

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}
