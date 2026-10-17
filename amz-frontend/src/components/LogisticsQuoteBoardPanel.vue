<script setup lang="ts">
/**
 * 报价覆盖看板（从 LogisticsDashboard.vue 拆出，§S33.10）。
 *
 * ## 这块搬进来，包括「标记过期」这个写操作
 * 前三刀拆出的面板都只发事件、由页壳执行请求，理由统一是「loader 失败要进
 * 页级错误区」。本面板不同：**「标记过期」失败不该占页级错误区**——它是一次
 * 性的人工收口动作，失败只要 toast 说清原因就行，看板数据还在，
 * 不构成「这块数据加载不出来」。所以写操作直接放在这里，页壳不必再转一手。
 *
 * ## 但 loader 仍在页壳
 * 首屏看板由页壳的 `loadQuoteBoard` 拉（它还要顺手取 tab 角标要的 staleByDate），
 * 本面板只收 `board` prop 渲染。**不要在本面板里也存一份 board**——
 * 那是两个真相来源，页壳的角标会算在旧数据上。
 *
 * ## 成功后必须重拉
 * 「标记过期」改写了报价状态，看板数字随之过期，所以成功后 emit reload-board
 * 交回页壳重拉。不做这步的症状是：点了按钮、toast 说成功、但数字一个没变。
 */
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import { expireOutdatedQuotes, type QuoteBoard } from '../api/logistics'
import { useShopGuard } from '../composables/useShopGuard'
import { useToast } from '../composables/useToast'
import { fmtRate } from './logisticsFormat'

const props = defineProps<{
  /** 报价覆盖看板数据，由页壳的 loadQuoteBoard 拉取 */
  board: QuoteBoard | null
  /** 页级操作互斥标记 */
  busy: boolean
}>()

const emit = defineEmits<{
  /** 「标记过期」成功后：看板数字已过期，请求页壳重拉 */
  (e: 'reload-board'): void
}>()

const { refreshShop } = useShopGuard()
const { showToast } = useToast()

/**
 * 本面板自己的操作互斥标记。
 *
 * 不能只用 `props.busy`：标记过期是**本面板自己的写操作**，页壳的 busy
 * 不会因为它置位（页壳只管页壳的动作），于是请求期间按钮不禁用，
 * 连点会重复提交同一批状态改写。props.busy 仍要参与判断——
 * 用户在别的面板触发的页级操作进行时，这个按钮同样不该可点。
 */
const busyLocal = ref(false)

/** 单一来源航线数：无议价空间，是选商环节最该先补的缺口 */
const singleSourceRoutes = computed(
  () => (props.board?.routes || []).filter((r) => r.singleSource).length
)
const currencyCount = computed(() => Object.keys(props.board?.byCurrency || {}).length)

/* ---------- 航线展示用格式化（只服务报价域，原样搬来） ---------- */

/** 报价区间：两端相同只显示一个值，避免出现「3.50 ~ 3.50」这种噪音 */
const priceRange = (min?: number | null, max?: number | null) => {
  if (min === null || min === undefined) {
    return max === null || max === undefined ? '—' : String(max)
  }
  if (max === null || max === undefined || min === max) {
    return String(min)
  }
  return `${min} ~ ${max}`
}

const transitRange = (r: { minTransitDays: number; maxTransitDays: number }) => {
  if (r.minTransitDays < 0) return '未填写'
  return r.minTransitDays === r.maxTransitDays
    ? `${r.minTransitDays} 天`
    : `${r.minTransitDays} ~ ${r.maxTransitDays} 天`
}

/**
 * 把「已过失效日期但状态仍为 ACTIVE」的报价置为 EXPIRED。
 *
 * 会先报出条数并要求确认：这条写操作按 shopId 批量改状态，且不可逐条回滚
 * （EXPIRED 之后要恢复得逐条改回 ACTIVE）。条数为 0 时直接返回——
 * 按钮此时是禁用态，这里是防「数据在打开页面和点击之间变了」。
 */
const doExpireQuotes = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  const count = props.board?.staleByDate ?? 0
  if (!count) return
  if (!window.confirm(`确认把 ${count} 条已失效的报价状态置为 EXPIRED？`)) return
  busyLocal.value = true
  try {
    const res = await expireOutdatedQuotes(shopId)
    if (res?.code === 200) {
      showToast(`已标记 ${res.data ?? 0} 条过期报价`, 'success')
      emit('reload-board')
    } else {
      showToast(res?.message || '标记失败', 'error')
    }
  } catch (e) {
    showToast('标记失败，请稍后重试', 'error')
  } finally {
    busyLocal.value = false
  }
}
</script>

<template>
            <div v-if="board?.warnings?.length" class="warn-banner">
              <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
              <div class="wb-body">
                <div v-for="(w, i) in board.warnings" :key="i">{{ w }}</div>
              </div>
            </div>

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:file-document-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ board?.validQuotes ?? 0 }}</div>
                  <div class="kpi-label">有效报价</div>
                  <div class="kpi-sub">共登记 {{ board?.totalQuotes ?? 0 }} 条</div>
                </div>
              </div>
              <div
                class="kpi-card"
                :class="{ 'kpi-alert': (board?.staleByDate ?? 0) > 0 }"
              >
                <div class="kpi-icon tone-warn"><Icon icon="mdi:calendar-remove-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ board?.staleByDate ?? 0 }}</div>
                  <div class="kpi-label">已失效未收口</div>
                  <div class="kpi-sub">比价时已自动排除</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:timer-sand" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ board?.expiringIn30Days ?? 0 }}</div>
                  <div class="kpi-label">30 天内失效</div>
                  <div class="kpi-sub">需提前重新询价</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (board?.unpricedQuotes ?? 0) > 0 }">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:currency-usd-off" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ board?.unpricedQuotes ?? 0 }}</div>
                  <div class="kpi-label">未填单价</div>
                  <div class="kpi-sub">无法参与比价</div>
                </div>
              </div>
            </div>

            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">{{ board?.carrierCount ?? 0 }}</div>
                <div class="mini-label">覆盖承运商</div>
                <div class="mini-hint">有有效报价的承运商数</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ board?.routeCount ?? 0 }}</div>
                <div class="mini-label">覆盖航线</div>
                <div class="mini-hint">起运港 → 目的港</div>
              </div>
              <div
                class="mini-card"
                :class="{ alert: (singleSourceRoutes ?? 0) > 0 }"
              >
                <div class="mini-value">{{ singleSourceRoutes ?? 0 }}</div>
                <div class="mini-label">单一来源航线</div>
                <div class="mini-hint">只有一家承运商，无议价空间</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ currencyCount }}</div>
                <div class="mini-label">涉及币种</div>
                <div class="mini-hint">多币种报价不可直接比价</div>
              </div>
            </div>

            <div class="chart-card">
              <div class="card-head">
                <h3>航线报价覆盖</h3>
                <button
                  class="link-btn"
                  :disabled="busy || !(board?.staleByDate ?? 0)"
                  @click="doExpireQuotes"
                >
                  标记过期报价
                </button>
              </div>
              <p class="card-desc">
                按承运商数量升序排列——<strong>排最前面的航线最需要补供应商</strong>。
                报价生效靠人工或定时任务置位，因此存在一段「已失效但仍是 ACTIVE」的窗口，比价时会自动排除。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>航线</th>
                    <th>承运商</th>
                    <th>时效</th>
                    <th>每公斤价</th>
                    <th>每立方价</th>
                    <th>币种</th>
                    <th>价差</th>
                    <th>提示</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!board?.routes?.length">
                    <td colspan="8" class="empty-row">暂无有效报价</td>
                  </tr>
                  <tr v-for="r in board?.routes || []" :key="r.originPort + '→' + r.destinationPort">
                    <td>{{ r.originPort }} → {{ r.destinationPort }}</td>
                    <td>
                      <div>{{ r.carrierCount }} 家</div>
                      <div class="sub-line">{{ r.carriers.join('、') }}</div>
                    </td>
                    <td>{{ transitRange(r) }}</td>
                    <td class="mono">{{ priceRange(r.minPricePerKg, r.maxPricePerKg) }}</td>
                    <td class="mono">{{ priceRange(r.minPricePerCbm, r.maxPricePerCbm) }}</td>
                    <td>{{ r.currency }}</td>
                    <td>{{ fmtRate(r.priceSpreadRate) }}</td>
                    <td>
                      <span v-if="r.singleSource" class="status-tag tone-warn">单一来源</span>
                      <span v-else class="status-tag tone-muted">正常</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
</template>

<style scoped>
/* 不定义任何样式：warn-banner / kpi-* / mini-* / chart-card / card-head /
 * card-desc / data-table / sub-line / mono / empty-row / status-tag / link-btn
 * 全部来自共享样式表与 src/style.css。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
