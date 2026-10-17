<script setup lang="ts">
/**
 * 有效报价明细列表（从 LogisticsDashboard.vue 拆出，§S33.11）。
 *
 * ## 为什么这块能整个搬进来（含 loader）
 * 它是报价 tab 里最后一块，与另两块（覆盖看板 / 运费比价）**数据源不同**：
 * 另两块读 `quoteBoard`，这块读 `listQuotes`（keyset 分页）。
 * 所以它的状态（`quotes` + 4 个分页标志 + 1 个服务类型筛选）在全文件
 * 无第二个使用者，整块搬进来最自然——页壳不必为一张别人的表持有 6 个 ref。
 * 与调拨列表（LogisticsTransferPanel）同一处理方式：那里也是列表状态跟着组件走。
 *
 * ## 失败仍然走页级错误区
 * 与调拨/货件一致：loader 失败要把原因写进页壳的页级错误区（`blockErrors`），
 * 那属于页壳。所以本组件不持有错误数组，通过 `report-error` 事件把
 * （标签, 详情）交回页壳，由 `pushBlockError` 落账。
 * 不打到 toast 是因为列表失败时**用户看到的是一片空白**，
 * 只有 toast 会消失，说不出「为什么是空的」。
 *
 * ## 截断与缺元数据是两件事，不能合并
 * 服务端可能声明还有更多（`truncated`/`hasMore`），也可能**没回分页元数据**。
 * 前者是「我知道还有，给你下一页按钮」，后者是「我无法确认是否全量」——
 * 把后者显示成前者会让用户以为已经看全了，从而漏看报价、选错承运商。
 */
import { ref } from 'vue'
import { listQuotes, type CarrierQuote } from '../api/logistics'
import { useShopGuard } from '../composables/useShopGuard'

const props = defineProps<{
  /** 当前是否激活（页壳的 tab 是不是本面板）——首次进入由页壳调 load() */
  active: boolean
}>()

const emit = defineEmits<{
  /** loader 失败：把（标签, 详情）交回页壳落进页级错误区 */
  (e: 'report-error', tag: string, detail: unknown): void
}>()

const { refreshShop } = useShopGuard()

const PAGE_SIZE = 50

const quotes = ref<CarrierQuote[]>([])
const quotesNextCursor = ref<string | null>(null)
const quotesTruncated = ref(false)
const quotesPageMetaMissing = ref(false)
const quotesLoadingMore = ref(false)
const quoteServiceType = ref('')

/**
 * 逐条有效报价（keyset 分页，与货件/调拨同模式）。
 * <p>
 * 服务端已按「有效期内」过滤，页面上直接呈现即可；_page 缺失时披露「无法确认是否全量」，
 * 不把第一页冒充全部报价——报价漏看一条可能就选错承运商。
 */
const loadQuotes = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) return
  if (!append) {
    quotesNextCursor.value = null
    quotesTruncated.value = false
    quotesPageMetaMissing.value = false
  }
  if (append) quotesLoadingMore.value = true
  try {
    const cursor = append ? (quotesNextCursor.value ?? undefined) : undefined
    const res = await listQuotes(shopId, quoteServiceType.value || undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      quotes.value = append ? quotes.value.concat(rows) : rows
      const page = res._page
      const hasMore = page ? (page.truncated || page.hasMore) : false
      const metaMissing = !page || (hasMore && !page.nextCursor)
      quotesPageMetaMissing.value = metaMissing
      quotesTruncated.value = !metaMissing && hasMore
      quotesNextCursor.value = metaMissing ? null : (page?.nextCursor ?? null)
    } else {
      emit('report-error', '有效报价', res?.message)
      if (!append) quotes.value = []
    }
  } catch (e) {
    console.warn('[Logistics] 加载有效报价失败', e)
    emit('report-error', '有效报价', e)
    if (!append) quotes.value = []
  } finally {
    if (append) quotesLoadingMore.value = false
  }
}

const loadMoreQuotes = () => {
  if (!quotesTruncated.value || !quotesNextCursor.value || quotesLoadingMore.value) return
  void loadQuotes(true)
}

/**
 * 供页壳取两样东西：
 * - `load`：首次进入本 tab 时才拉数据（把关在页壳，与调拨/货件同一先例）；
 * - `reload`：切店铺后重拉（`onAuthChanged` 走这条）。
 */
/**
 * 「已加载」判据归本组件，且按**店铺**记（理由同 LogisticsShipmentPanel：
 * 只看「列表非空」会把新店铺当成没加载过，而旧店铺数据又可能仍然非空）。
 *
 * `load` 只负责首次进入；`reload` 是强制重拉（刷新按钮 / 切店铺 / 登录态变化）。
 */
const loadedShopId = ref<string | null>(null)

const load = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    loadedShopId.value = null
    await loadQuotes()
    return
  }
  if (loadedShopId.value === shopId) return
  await loadQuotes()
  loadedShopId.value = shopId
}

const reload = async () => {
  const shopId = refreshShop()
  await loadQuotes()
  loadedShopId.value = shopId || null
}

defineExpose({ load, reload })
</script>

<template>
  <div v-if="props.active" data-panel="quotes">
    <div class="card-head">
      <h3>有效报价明细</h3>
      <label>
        服务类型
        <select v-model="quoteServiceType" @change="loadQuotes()">
          <option value="">全部</option>
          <option value="SEA">海运</option>
          <option value="AIR">空运</option>
          <option value="EXPRESS">快递</option>
        </select>
      </label>
    </div>
    <p class="card-desc">
      只列<b>有效期内且已启用</b>的报价（后端过滤），比价用的就是这批。
      已过失效日期但状态仍为 ACTIVE 的报价不在这里出现，需到看板里「标记过期」收口。
    </p>
    <table class="data-table">
      <thead>
        <tr>
          <th>承运商</th><th>服务类型</th><th>航线</th><th>时效</th>
          <th>每公斤价</th><th>每立方价</th><th>最低收费</th><th>燃油附加</th>
          <th>币种</th><th>生效</th><th>失效</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="q in quotes" :key="q.id">
          <td>{{ q.carrierName || '-' }}</td>
          <td>{{ q.serviceType || '-' }}</td>
          <td>{{ q.originPort || '?' }} → {{ q.destinationPort || '?' }}</td>
          <td>{{ q.transitDays ?? '-' }} 天</td>
          <td class="mono">{{ q.pricePerKg ?? '-' }}</td>
          <td class="mono">{{ q.pricePerCbm ?? '-' }}</td>
          <td class="mono">{{ q.minCharge ?? '-' }}</td>
          <td>{{ q.fuelSurchargeRate == null ? '-' : `${(Number(q.fuelSurchargeRate) * 100).toFixed(1)}%` }}</td>
          <td>{{ q.currency || '-' }}</td>
          <td>{{ q.effectiveDate || '-' }}</td>
          <td>{{ q.expiryDate || '长期' }}</td>
        </tr>
        <tr v-if="!quotes.length">
          <td colspan="11" class="empty-row">暂无有效报价</td>
        </tr>
      </tbody>
    </table>
    <div class="table-pager">
      <span class="page-info">已加载 {{ quotes.length }} 条</span>
      <div class="page-actions">
        <button v-if="quotesTruncated" class="page-btn"
                :disabled="quotesLoadingMore || !quotesNextCursor"
                @click="loadMoreQuotes">加载下一页</button>
      </div>
    </div>
    <div v-if="quotesTruncated" class="truncated-tip" role="status">
      后端声明还有更多未加载，当前列表不是全量。
    </div>
    <div v-else-if="quotesPageMetaMissing" class="truncated-tip" role="status">
      未拿到分页元数据，无法确认是否全量。
    </div>
  </div>
</template>

<style scoped>
/* 不定义任何样式：card-head / card-desc / data-table / mono / empty-row /
 * table-pager / page-info / page-actions / page-btn / truncated-tip
 * 全部来自共享样式表与 src/style.css。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
