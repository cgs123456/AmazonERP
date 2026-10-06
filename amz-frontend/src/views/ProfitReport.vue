<template>
  <div class="profit-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">利润报表</h1>
        <p class="hero-subtitle">按 SKU / 月度汇总利润分析，并可下钻到单个订单或单个 SKU 的利润行</p>
      </div>

      <!-- 骨架屏：汇总卡片 + 表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="summary-grid">
          <div v-for="i in 4" :key="i" class="summary-card">
            <div class="skeleton sk-line sk-line-sm"></div>
            <div class="skeleton sk-line sk-line-lg"></div>
          </div>
        </div>
        <div class="table-card sk-table-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
      </div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看利润报表。
      </div>

      <!-- 读取失败必须可见：下面仍会展示降级示例数据，但「哪一次请求失败了」不能只进 console -->
      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三个口径先说清：<b>①汇总卡片只统计下面选定的区间</b>（区间一改，四个数就跟着变，
        它不是「开店至今」）；<b>②「按 SKU×月」读的是后端 SQL 聚合</b>，
        一次最多 {{ MAX_SUMMARY_HINT }} 行，达到上限时只保证最近的月份在前；
        <b>③下钻行里的「数据不全」</b>表示这条利润有成本项没取全（利润按单逐笔算出后写入），
        这种数字不能和完整行同样当决策依据。本页不提供「按店铺」维度：后端没有按店铺聚合利润的端点。
      </div>

      <!-- 统计区间（此前日期写死在 onMounted 里，页面上看不到，等于用一个隐形的假设给四个数盖章） -->
      <div v-if="currentShopId && !loading" class="window-row">
        <label class="field">开始日期 *
          <input v-model="range.start" type="date" />
        </label>
        <label class="field">结束日期 *
          <input v-model="range.end" type="date" />
        </label>
        <button class="action-btn primary" :disabled="loading || !rangeReady" @click="loadReport()">
          按区间重算
        </button>
        <span class="muted">当前区间 {{ range.start }} ~ {{ range.end }}</span>
      </div>

      <!-- 汇总卡片（加载时仅显示骨架） -->
      <template v-if="!loading">
      <div class="summary-grid">
        <div class="summary-card">
          <div class="summary-label">总销售额（{{ range.start }} ~ {{ range.end }}）</div>
          <div class="summary-value">{{ summary.totalRevenue }}</div>
        </div>
        <div class="summary-card">
          <div class="summary-label">总成本</div>
          <div class="summary-value">{{ summary.totalCost }}</div>
        </div>
        <div class="summary-card">
          <div class="summary-label">毛利润</div>
          <div class="summary-value profit-positive">{{ summary.grossProfit }}</div>
        </div>
        <div class="summary-card">
          <div class="summary-label">毛利率</div>
          <div class="summary-value profit-positive">{{ summary.grossMargin }}</div>
        </div>
      </div>
      </template>

      <!-- 维度切换（降级 mock 时挂示例标识，避免误当真实利润决策） -->
      <div class="dim-tabs">
        <button :class="['dim-tab', { active: dim === 'sku' }]" @click="dim = 'sku'">按 SKU（区间日粒度）</button>
        <button :class="['dim-tab', { active: dim === 'month' }]" @click="switchToMonth">按 SKU×月（汇总）</button>
        <span v-if="isMock" class="mock-badge">示例数据</span>
      </div>

      <!-- 利润表格（加载时仅显示骨架） -->
      <template v-if="!loading">
      <div class="table-card">
        <div class="filter-row">
          <span class="muted">{{ currentRowsHint }}</span>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ dim === 'sku' ? 'SKU / 日期' : 'SKU · 月份' }}</th>
              <th>销售额</th>
              <th>产品成本</th>
              <th>平台费用</th>
              <th>广告费</th>
              <th>头程运费</th>
              <th>毛利润</th>
              <th>毛利率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in currentData" :key="row.name">
              <td class="mono">{{ row.name }}</td>
              <td>{{ row.revenue }}</td>
              <td>{{ row.cost }}</td>
              <td>{{ row.platformFee }}</td>
              <td>{{ row.adFee }}</td>
              <td>{{ row.shipping }}</td>
              <td :class="row.profit > 0 ? 'profit-positive' : 'profit-negative'">{{ row.profit > 0 ? '+' : '' }}{{ row.profit }}</td>
              <td :class="row.margin > 0 ? 'profit-positive' : 'profit-negative'">{{ row.margin }}%</td>
            </tr>
            <tr v-if="!loading && currentData.length === 0">
              <td colspan="8" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:chart-box-outline" width="32" class="empty-icon" />
                  <span>{{ dim === 'month' ? '这个店铺还没有月度聚合行' : '该区间暂无利润数据' }}</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      </template>

      <!-- ==================== 下钻查询 ==================== -->
      <div v-if="currentShopId && !loading" class="table-card drill-card" data-panel="drill">
        <h3 class="card-title">下钻：一单赚多少，或一个 SKU 的利润行</h3>
        <div class="filter-row">
          <label class="field">查询方式
            <select v-model="drill.mode">
              <option value="order">按亚马逊订单号</option>
              <option value="sku">按 SKU</option>
            </select>
          </label>
          <label class="field">{{ drill.mode === 'order' ? '订单号' : 'SKU' }}
            <input v-model="drill.value" :placeholder="drill.mode === 'order' ? '111-2222222-3333333' : 'B08X4-001'" />
          </label>
          <button class="action-btn primary" :disabled="drillBusy || !drillReady" @click="runDrill(false)">查询</button>
          <button v-if="drill.truncated" class="action-btn" :disabled="drillBusy" @click="runDrill(true)">
            下一页
          </button>
          <span class="muted">{{ drillPagerText }}</span>
        </div>
        <table v-if="drill.rows.length" class="data-table">
          <thead>
            <tr>
              <th>统计日期</th><th>订单号</th><th>SKU</th><th>销售额</th><th>产品成本</th>
              <th>平台费</th><th>广告费</th><th>FBA 费用</th><th>VAT</th>
              <th>净利润</th><th>净利率</th><th>数据</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in drill.rows" :key="r.id">
              <td class="mono">{{ r.statDate || '-' }}</td>
              <td class="mono cell-clip">{{ r.amazonOrderId || '-' }}</td>
              <td class="mono">{{ r.sku || '-' }}</td>
              <td>{{ money(r.revenue) }}</td>
              <td>{{ money(r.productCost) }}</td>
              <td>{{ money(r.referralFee) }}</td>
              <td>{{ money(r.adCost) }}</td>
              <td>{{ money(sum(r.fbaFulfillmentFee, r.fbaStorageFee)) }}</td>
              <td>{{ money(r.vat) }}</td>
              <td :class="num(r.netProfit) > 0 ? 'profit-positive' : 'profit-negative'">{{ money(r.netProfit) }}</td>
              <td>{{ pct(r.netMargin) }}</td>
              <td>
                <span v-if="r.dataComplete === false" class="flag incomplete">数据不全</span>
                <span v-else-if="r.dataComplete === true" class="flag">完整</span>
                <span v-else class="flag unknown">未标注</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="drillSearched" class="muted drill-empty">
          没有查到利润行。订单号与 SKU 都是精确匹配，查不到不等于这单没有利润，
          可能只是这家店下没有这个值，或利润还没被算出来写入。
        </p>
        <p v-else class="muted drill-empty">
          填一个订单号或 SKU 再查。订单列表不返回利润字段，所以利润只能在这里逐单/逐 SKU 取。
        </p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import {
  getMonthlyProfitSummary, getProfitReport, listProfitByOrder, listProfitBySku, mapMonthlyRows
} from '@/api/profit'
import type { ProfitReportRow, ProfitRow, ProfitSummary } from '@/api/profit'
import { useShopGuard } from '@/composables/useShopGuard'
import { useMockFlag } from '@/composables/useMockFlag'

const loading = ref(false)
const dim = ref<'sku' | 'month'>('sku')

// 后端月度汇总的行数上限（与 ProfitController.MAX_SUMMARY_ROWS 同一口径，这里只用于文案与截断判断）
const MAX_SUMMARY_HINT = 500

// 当前选中店铺（B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值）
const { currentShopId, refreshShop } = useShopGuard()

const errors = ref<string[]>([])
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

// 统计区间：之前这两个日期写死在 onMounted 里，页面上既看不到也改不了
const range = ref<{ start: string; end: string }>({ start: '2026-06-01', end: '2026-06-30' })
const rangeReady = computed(() => Boolean(range.value.start && range.value.end))

const num = (v: unknown): number => {
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}
const money = (v: unknown): string => {
  // 取不到的成本列显示 —，不是 $0.00：把「没数据」画成「零成本」会让人以为这一项真的花了 0
  if (v === null || v === undefined || v === '') return '—'
  const n = num(v)
  const body = Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return (n < 0 ? '-$' : '$') + body
}
const pct = (v: unknown): string =>
  (v === null || v === undefined || v === '' ? '-' : (num(v) * 100).toFixed(1) + '%')
// 两项费用合并展示时，两边都没取到要还是「没取到」，不能加成 0
const sum = (...vals: unknown[]): number | null => {
  const given = vals.filter((v) => v !== null && v !== undefined && v !== '')
  if (!given.length) return null
  return given.reduce((acc: number, v) => acc + num(v), 0)
}

// 降级用的 mock 数据
const mockSummary: ProfitSummary = {
  totalRevenue: '$12,456.80',
  totalCost: '$7,234.20',
  grossProfit: '$5,222.60',
  grossMargin: '41.9%'
}
const mockSkuData: ProfitRow[] = [
  { name: 'B08X4-001', revenue: '$3,200', cost: '$1,600', platformFee: '$480', adFee: '$320', shipping: '$160', profit: 640, margin: 20.0 },
  { name: 'B08X4-002', revenue: '$2,100', cost: '$1,050', platformFee: '$315', adFee: '$210', shipping: '$105', profit: 420, margin: 20.0 },
  { name: 'B08X4-003', revenue: '$1,800', cost: '$1,200', platformFee: '$270', adFee: '$180', shipping: '$90', profit: 60, margin: 3.3 },
  { name: 'B08X4-004', revenue: '$900', cost: '$450', platformFee: '$135', adFee: '$90', shipping: '$45', profit: 180, margin: 20.0 },
  { name: 'B08X4-005', revenue: '$4,456.80', cost: '$2,934.20', platformFee: '$668.52', adFee: '$445.68', shipping: '$222.84', profit: 185.56, margin: 4.2 }
]

// 是否正在展示降级 mock（B4 公共标识：初始 true；任何 200 响应后摘徽，含零数据）
const { isMock, markLive } = useMockFlag()

const summary = ref<ProfitSummary>({ ...mockSummary })
const skuData = ref<ProfitRow[]>([...mockSkuData])

const monthlyRows = ref<ProfitRow[]>([])
const monthlyLoaded = ref(false)
const monthlyLoading = ref(false)
const monthlyTruncated = ref(false)

const currentData = computed(() => (dim.value === 'sku' ? skuData.value : monthlyRows.value))
const currentRowsHint = computed(() => {
  if (dim.value === 'sku') return `区间内 ${currentData.value.length} 行（日粒度，来自利润报表）`
  if (!monthlyLoaded.value) return '这个维度读的是后端按 SKU×月 的 SQL 聚合，切过来才会去查'
  return `SKU×月 ${currentData.value.length} 行`
    + (monthlyTruncated.value ? ' · 行数已达后端上限，只保证最近月份在前' : '')
})

// ==================== 下钻 ====================
const drill = ref<{
  mode: 'order' | 'sku'; value: string; rows: ProfitReportRow[]; cursor: string | null; truncated: boolean
}>({ mode: 'order', value: '', rows: [], cursor: null, truncated: false })
const drillBusy = ref(false)
const drillSearched = ref(false)

const drillReady = computed(() => Boolean(drill.value.value.trim()))
const drillPagerText = computed(() => {
  if (!drill.value.rows.length) return ''
  return drill.value.truncated
    ? `已加载 ${drill.value.rows.length} 行 · 后端标记仍有下一页（按记录倒序）`
    : `共 ${drill.value.rows.length} 行（按记录倒序）`
})

const runDrill = async (append: boolean) => {
  const shopId = refreshShop()
  if (!shopId) return
  const value = drill.value.value.trim()
  if (!value) {
    pushError('下钻查询：订单号或 SKU 不能为空')
    return
  }
  if (!append) {
    drill.value.rows = []
    drill.value.cursor = null
    drill.value.truncated = false
    drillSearched.value = true
  }
  drillBusy.value = true
  try {
    const q = { size: 20, cursor: append ? drill.value.cursor ?? undefined : undefined }
    const res = drill.value.mode === 'order'
      ? await listProfitByOrder(shopId, value, q)
      : await listProfitBySku(shopId, value, q)
    if (res?.code !== 200) {
      pushError(`下钻查询：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    drill.value.rows = append ? [...drill.value.rows, ...batch] : batch
    drill.value.truncated = Boolean(res._page?.truncated)
    drill.value.cursor = res._page?.nextCursor ?? null
  } catch (e) {
    pushError(`下钻查询：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    drillBusy.value = false
  }
}

// ==================== 主报表与月度汇总 ====================
const loadReport = async () => {
  const shopId = refreshShop()
  // 未选择店铺时不发请求，/order/profit/* 路径受网关 shopId 校验
  if (!shopId) {
    loading.value = false
    return
  }
  if (!rangeReady.value) {
    pushError('统计区间：开始与结束日期都要填')
    return
  }
  loading.value = true
  // 只清主报表与统计区间自己的错误：下钻/月度的错误由各自 loader 管，不许互擦
  errors.value = errors.value.filter((x) => !x.startsWith('利润报表：') && !x.startsWith('统计区间：'))
  try {
    const res = await getProfitReport(shopId, range.value.start, range.value.end)
    if (res?.code === 200 && res.data) {
      if (res.data.summary) summary.value = res.data.summary
      skuData.value = res.data.rows ?? []
      // 200 即视为真实数据（含零数据空态），摘掉示例标识
      markLive()
    } else {
      pushError(`利润报表：${res?.message || '后端返回非 200'}，下面显示的是示例数据`)
    }
  } catch (e) {
    pushError(`利润报表：${e instanceof Error ? e.message : '调用失败'}，下面显示的是示例数据`)
  } finally {
    loading.value = false
  }
}

const loadMonthly = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  monthlyLoading.value = true
  try {
    const res = await getMonthlyProfitSummary(shopId)
    if (res?.code !== 200) {
      pushError(`月度汇总：${res?.message || '后端返回非 200'}`)
      return
    }
    const raw = Array.isArray(res.data) ? res.data : []
    monthlyRows.value = mapMonthlyRows(raw)
    // 汇总不分页，只能靠「行数正好等于上限」判断可能被截断了
    monthlyTruncated.value = raw.length >= MAX_SUMMARY_HINT
    monthlyLoaded.value = true
  } catch (e) {
    pushError(`月度汇总：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    monthlyLoading.value = false
  }
}

const switchToMonth = async () => {
  dim.value = 'month'
  if (!monthlyLoaded.value) await loadMonthly()
}

onMounted(() => {
  errors.value = []          // 整页入口清一次；loader 只按各自前缀追加/移除
  void loadReport()
})
</script>

<style scoped>
.profit-page { background: var(--color-background); }

/* 页头/主区/表格等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 60%; height: 1.5rem; margin-top: 0.5rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: var(--font-size-2); color: var(--color-muted); }
.summary-value { font-size: var(--font-size-6); font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }

.dim-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.dim-tab { padding: 0.5rem 1rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; font-size: var(--font-size-2); color: var(--color-muted); transition: all 0.2s; }
.dim-tab:hover { border-color: var(--color-primary); color: var(--color-primary); }
.dim-tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
/* .mock-badge 已收敛至全局 style.css */

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }

.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.window-row { display: flex; align-items: flex-end; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.field input, .field select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.mono { font-family: var(--font-mono, monospace); }
.cell-clip { max-width: 12rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0; }
.drill-card { padding: 0.875rem 1rem 1rem; display: flex; flex-direction: column; gap: 0.25rem; }
.drill-empty { padding: 0.5rem 1rem 0.75rem; margin: 0; line-height: 1.5; }
.action-btn { padding: 0.3rem 0.7rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.8125rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.flag { font-size: 0.6875rem; padding: 0.2rem 0.4rem; border-radius: var(--radius-sm); background: var(--color-primary-light); color: var(--color-success); white-space: nowrap; }
.flag.incomplete { background: var(--color-light-red); color: var(--color-error); }
.flag.unknown { background: var(--color-muted-light); color: var(--color-muted); }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .summary-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .summary-grid { grid-template-columns: 1fr; } }
</style>
