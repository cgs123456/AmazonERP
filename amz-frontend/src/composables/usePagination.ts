import { ref, computed } from 'vue'

/**
 * 客户端分页组合式函数。
 * 与 Finance.vue 既有分页交互保持一致（上一页/下一页 + 页码信息），
 * 供大数据量表格复用，避免每页重复手写切片逻辑。
 */
export function usePagination<T>(source: () => T[], initialSize = 10) {
    const page = ref(1)
    const size = ref(initialSize)

    const total = computed(() => source().length)
    const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
    const paged = computed(() => {
        const start = (page.value - 1) * size.value
        return source().slice(start, start + size.value)
    })

    /** 数据源变化（筛选/刷新）时回到第一页 */
    const resetPage = () => {
        page.value = 1
    }
    const prevPage = () => {
        if (page.value > 1) page.value--
    }
    const nextPage = () => {
        if (page.value < totalPages.value) page.value++
    }

    return { page, size, total, totalPages, paged, resetPage, prevPage, nextPage }
}
