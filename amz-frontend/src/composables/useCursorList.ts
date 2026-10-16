import { ref } from 'vue'
import type { Ref } from 'vue'

/**
 * 服务端游标分页状态（Finance 页专用）。
 *
 * 与 `usePagination` 是**不同的东西**，别合并：`usePagination.ts` 是客户端切片
 * （先取全量再分页），这里是服务端游标（一次一页、`_page.nextCursor` 续读），
 * 且必须保留 `truncated`——它决定「列表不是全量」这个警告要不要显示。
 *
 * 从 Finance.vue 原样搬来，形状未改。
 */
export interface CursorList<T> {
  rows: Ref<T[]>
  cursor: Ref<string | null>
  truncated: Ref<boolean>
  loading: Ref<boolean>
}

export const makeCursor = <T>(): CursorList<T> => ({
  rows: ref<T[]>([]) as Ref<T[]>,
  cursor: ref<string | null>(null),
  truncated: ref(false),
  loading: ref(false)
})
