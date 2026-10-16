import { ref } from 'vue'
import type { Ref } from 'vue'
import { useFinanceErrors } from '@/composables/useFinanceErrors'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'

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

/**
 * 游标列表的统一 loader（回款 / 结算 / 费用差异 / 索赔四个列表共用）。
 *
 * 原先是 Finance.vue 里的一个局部函数；四个列表被拆进两个子组件后，
 * 复制两份会让「`_page` 缺失时怎么算」「清错误是按前缀还是整体」这类口径
 * 出现两个版本——所以收在这里，只留一份。
 *
 * 依赖 `useFinanceErrors`（错误按 `name：` 前缀落页级错误区）与
 * `useShopGuard`（发请求前同步 localStorage 的店铺，切店不重挂载时快照会过期）。
 * 这两个依赖就是它属于「Finance 页专用」而不是通用工具的原因。
 */
export function useCursorLoader() {
  const { pushError, clearErrorsFor } = useFinanceErrors()
  const { refreshShop } = useShopGuard()

  /**
   * @param append true 表示按当前 cursor 追加下一页；false 表示从首页重载。
   * @returns 本次是否拿到 200（`false` 含「没选店铺」与「失败」，两者都不该被当成加载成功）
   */
  const loadCursor = async <T>(
    state: CursorList<T>,
    name: string,
    fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
    append: boolean
  ): Promise<boolean> => {
    const shopId = refreshShop()
    if (!shopId) return false
    if (!append) {
      state.rows.value = []
      state.cursor.value = null
      // 只清本列表的错误（按「name：」前缀），并发 loader 互不擦横幅
      clearErrorsFor(name)
    }
    state.loading.value = true
    try {
      const res = await fetcher(append ? state.cursor.value ?? undefined : undefined)
      if (res?.code !== 200) {
        pushError(`${name}：${res?.message || '后端返回非 200'}`)
        return false
      }
      const rows = Array.isArray(res.data) ? res.data : []
      state.rows.value = append ? state.rows.value.concat(rows) : rows
      // 是否还有下一页只认服务端 _page；缺失时按「未知」处理，与凭证列表同口径
      state.cursor.value = res._page ? res._page.nextCursor : null
      state.truncated.value = res._page ? res._page.truncated : false
      return true
    } catch (e) {
      pushError(`${name}：${e instanceof Error ? e.message : '调用失败'}`)
      return false
    } finally {
      state.loading.value = false
    }
  }

  return { loadCursor }
}
