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
  /**
   * 统一分页元数据（后端 Result._page）。有这个字段说明本次是分页响应；
   * 没有说明是普通响应（例如单对象查询）。
   */
  _page?: PageMeta
}

/**
 * 后端 Result._page 的结构。
 *
 * truncated 是这里最重要的字段：为 true 时当前列表不是全量，
 * 直接拿去做对账、导出或金额汇总会漏单。
 * total 未统计时为 null，必须当成「未知」，不能当成 0。
 */
export interface PageMeta {
  size: number
  returned: number
  hasMore: boolean
  /** true：本页被截断，仍有下一页，必须继续翻页 */
  truncated: boolean
  /** 下一页游标；hasMore=false 时为 null */
  nextCursor: string | null
  /** 符合条件的总行数；未做 COUNT(*) 时为 null（未知，不是 0） */
  total: number | null
}