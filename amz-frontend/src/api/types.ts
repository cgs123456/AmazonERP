// 统一的 API 响应类型定义

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  /**
   * 字段级权限切面置空的字段名列表。
   * 后端 FieldPermissionAspect 在 Controller 返回 Result<T> 时填充；
   * 前端据此把对应字段渲染为 `***` 或隐藏列。无字段过滤时该字段不存在。
   */
  _hiddenFields?: string[]
}
