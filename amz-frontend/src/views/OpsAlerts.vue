<template>
  <div class="alert-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">运营预警台</h1>
        <p class="hero-subtitle">差评告警 · 跟卖告警 · 关键词排名趋势（读 amz-service-ops 的本地告警表）</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：三类数据都按选中店铺读取。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条边界：<b>①「扫描」按钮本页不提供</b>——差评扫描、跟卖扫描、排名抓取的实现是
        ThreadLocalRandom 造数，并且只有 mock profile 才放行（生产环境直接返回 0）。
        所以这里的告警行都来自「曾经写入」，本页不能声称它们是刚采到的。
        <b>② 差评与跟卖都只能 NEW → HANDLED 或 NEW → IGNORED</b>：两条都是本地状态迁移，
        不会联系买家、不会发起申诉、也不会对跟卖方做任何动作；已处于终态的告警再点会被后端拒绝。
        <b>③ IGNORED 不等于「以后别再报」</b>：当前扫描是 mock 造数，后续扫描仍可能产生新的告警行。
      </div>

      <div class="tabs">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <template v-if="currentShopId">
        <!-- ==================== 差评告警 ==================== -->
        <div v-if="tab === 'reviews'" class="tab-panel" data-panel="reviews">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="reviewStatus" @change="loadReviews()">
                  <option value="">全部</option>
                  <option v-for="s in ALERT_STATUSES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="reviews.loading.value" @click="loadReviews()">刷新</button>
              <button v-if="reviews.truncated.value" class="action-btn"
                      :disabled="reviews.loading.value" @click="loadReviews(true)">下一页</button>
              <span class="muted">{{ pagerText(reviews) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>落库时间</th><th>ASIN</th><th>评分</th><th>评论标题</th><th>买家</th>
                  <th>评论 ID</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in reviews.rows.value" :key="r.id">
                  <td class="mono">{{ r.createTime || '未记录' }}</td>
                  <td class="mono">{{ r.asin }}</td>
                  <td :class="{ neg: (r.rating ?? 5) <= 2 }">{{ r.rating ?? '-' }}</td>
                  <td class="cell-clip" :title="r.title || ''">{{ r.title || '-' }}</td>
                  <td>{{ r.reviewer || '-' }}</td>
                  <td class="mono cell-clip" :title="r.reviewId || ''">{{ r.reviewId || '-' }}</td>
                  <td><span class="status-tag" :class="alertClass(r.status)">{{ r.status || '-' }}</span></td>
                  <td class="row-actions">
                    <button class="action-btn" :disabled="busy || r.status !== 'NEW'"
                            @click="askReview(r, 'HANDLED')">标记已处理</button>
                    <button class="action-btn" :disabled="busy || r.status !== 'NEW'"
                            @click="askReview(r, 'IGNORED')">忽略</button>
                  </td>
                </tr>
                <tr v-if="!reviews.loading.value && !reviews.rows.value.length">
                  <td colspan="8" class="empty-row">
                    这家店没有差评告警行。只有 mock 环境跑过扫描才会有行，
                    所以「空」可能是从没扫描过，不是「真的没有差评」。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 跟卖告警 ==================== -->
        <div v-if="tab === 'hijacks'" class="tab-panel" data-panel="hijacks">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="hijackStatus" @change="loadHijacks()">
                  <option value="">全部</option>
                  <option v-for="s in ALERT_STATUSES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="hijacks.loading.value" @click="loadHijacks()">刷新</button>
              <button v-if="hijacks.truncated.value" class="action-btn"
                      :disabled="hijacks.loading.value" @click="loadHijacks(true)">下一页</button>
              <span class="muted">{{ pagerText(hijacks) }}；处置只改本地状态，不会对跟卖方做任何动作</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>落库时间</th><th>被跟卖 ASIN</th><th>跟卖卖家</th><th>卖家 ID</th>
                  <th>对方报价</th><th>Buy Box</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="h in hijacks.rows.value" :key="h.id">
                  <td class="mono">{{ h.createTime || '未记录' }}</td>
                  <td class="mono">{{ h.asin }}</td>
                  <td class="cell-clip" :title="h.hijackerName || ''">{{ h.hijackerName || '-' }}</td>
                  <td class="mono cell-clip" :title="h.hijackerSellerId || ''">{{ h.hijackerSellerId || '-' }}</td>
                  <td>{{ h.hijackPrice ?? '-' }}</td>
                  <td :class="{ neg: h.buyBoxTaken === true }">{{ buyBoxText(h.buyBoxTaken) }}</td>
                  <td><span class="status-tag" :class="alertClass(h.status)">{{ h.status || '-' }}</span></td>
                  <td class="row-actions">
                    <button class="action-btn" :disabled="busy || h.status !== 'NEW'"
                            @click="askHijack(h, 'HANDLED')">标记已处理</button>
                    <button class="action-btn" :disabled="busy || h.status !== 'NEW'"
                            @click="askHijack(h, 'IGNORED')">忽略</button>
                  </td>
                </tr>
                <tr v-if="!hijacks.loading.value && !hijacks.rows.value.length">
                  <td colspan="8" class="empty-row">这家店没有跟卖告警行（同上：没有采集器写入过就是空）。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 关键词排名 ==================== -->
        <div v-if="tab === 'rank'" class="tab-panel" data-panel="rank">
          <div class="table-card form-card">
            <h3 class="card-title">查一个关键词 + ASIN 的排名趋势</h3>
            <div class="form-grid">
              <label class="field">关键词 *<input v-model="rankQuery.keyword" placeholder="wireless earbuds" /></label>
              <label class="field">ASIN *<input v-model="rankQuery.asin" placeholder="B0123456789" /></label>
            </div>
            <p class="muted form-hint">
              后端要求两个条件都填：没有「列出本店所有被追踪关键词」的端点，所以这里不是列表页。
              返回的是最近 {{ MAX_TREND_POINTS }} 个点，按抓取时间升序。
            </p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !rankQueryReady" @click="loadTrend()">查询趋势</button>
              <button v-if="trendLoaded" class="page-btn" @click="clearTrend()">清空结果</button>
            </div>
          </div>

          <div v-if="trend.length" class="table-card">
            <div class="metric-grid">
              <div class="metric"><span class="metric-label">最新排名</span>
                <span class="metric-value">{{ latestRank }}</span></div>
              <div class="metric"><span class="metric-label">最好</span>
                <span class="metric-value">{{ bestRank }}</span></div>
              <div class="metric"><span class="metric-label">最差</span>
                <span class="metric-value">{{ worstRank }}</span></div>
              <div class="metric"><span class="metric-label">点数</span>
                <span class="metric-value">{{ trend.length }}</span></div>
            </div>
            <p class="muted aggregate-note">
              排名数字越小越好；上面三个值都来自本页拿到的这些点，
              最后一次抓取的时刻是 <span class="mono small">{{ trend[trend.length - 1].captureTime || '未记录' }}</span>。
            </p>
            <table class="data-table">
              <thead><tr><th>抓取时间</th><th>排名</th><th>站点</th></tr></thead>
              <tbody>
                <tr v-for="(p, i) in trend" :key="p.id ?? i">
                  <td class="mono">{{ p.captureTime || '未记录' }}</td>
                  <td :class="{ neg: (p.rank ?? 999) > 30 }">{{ p.rank ?? '-' }}</td>
                  <td>{{ p.marketplace || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else-if="trendLoaded" class="table-card">
            <p class="muted aggregate-note">
              这个关键词 + ASIN 组合没有排名记录。排名只由「抓取」写入，而抓取是 mock 造数，
              所以空记录不等于「没有排名」。
            </p>
          </div>
        </div>
      </template>
    </main>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p class="confirm-detail">{{ confirmBox.detail }}</p>
        <div class="modal-actions">
          <button class="page-btn" @click="confirmBox = null">取消</button>
          <button class="page-btn" :disabled="busy" @click="runConfirm()">确认执行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import {
  ALERT_STATUSES, getRankTrend, handleHijackAlert, handleReviewAlert,
  ignoreHijackAlert, ignoreReviewAlert, listHijackAlerts, listReviewAlerts
} from '@/api/opsAlerts'
import type { HijackAlert, KeywordRankRecord, NegativeReviewAlert } from '@/api/opsAlerts'

type TabKey = 'reviews' | 'hijacks' | 'rank'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'reviews', label: '差评告警' },
  { key: 'hijacks', label: '跟卖告警' },
  { key: 'rank', label: '关键词排名' }
]

// 与后端 OpsServiceImpl.MAX_RANK_TREND_POINTS 同步：趋势不翻页但有上限
const MAX_TREND_POINTS = 200

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('reviews')
const errors = ref<string[]>([])
const busy = ref(false)

const reviews = mkList<NegativeReviewAlert>()
const hijacks = mkList<HijackAlert>()

const reviewStatus = ref('')
const hijackStatus = ref('')
const rankQuery = reactive<{ keyword: string; asin: string }>({ keyword: '', asin: '' })
const trend = ref<KeywordRankRecord[]>([])
const trendLoaded = ref(false)

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

function mkList<T>() {
  return {
    rows: ref<T[]>([]) as unknown as import('vue').Ref<T[]>,
    cursor: ref<string | null>(null),
    truncated: ref(false),
    loading: ref(false)
  }
}

interface ListState<T> {
  rows: { value: T[] }
  cursor: { value: string | null }
  truncated: { value: boolean }
  loading: { value: boolean }
}

const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const pagerText = (list: ListState<unknown>): string =>
  list.truncated.value
    ? `已加载 ${list.rows.value.length} 条 · 后端标记仍有下一页，本页不是全量`
    : `已加载 ${list.rows.value.length} 条`
const alertClass = (s?: string | null) =>
  s === 'NEW' ? 'urgent' : s === 'HANDLED' ? 'healthy' : 'unknown'
const buyBoxText = (v?: boolean | null) => (v === true ? '已被抢走' : v === false ? '还在自己手里' : '未知')

const rankQueryReady = computed(() =>
  Boolean(rankQuery.keyword.trim() && rankQuery.asin.trim()))

const ranked = computed(() => trend.value.map(p => p.rank).filter((r): r is number => typeof r === 'number'))
const latestRank = computed(() => {
  const last = trend.value[trend.value.length - 1]
  return last && typeof last.rank === 'number' ? last.rank : '-'
})
const bestRank = computed(() => (ranked.value.length ? Math.min(...ranked.value) : '-'))
const worstRank = computed(() => (ranked.value.length ? Math.max(...ranked.value) : '-'))

const loadList = async <T>(
  list: ListState<T>,
  label: string,
  fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  append: boolean
) => {
  const shopId = shop()
  if (!shopId) return
  if (!append) {
    list.rows.value = []
    list.cursor.value = null
    // 只清本列表的错误（按「label：」前缀），并发 loader 互不擦横幅
    errors.value = errors.value.filter((x) => !x.startsWith(`${label}：`))
  }
  list.loading.value = true
  try {
    const res = await fetcher(append ? list.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    list.rows.value = append ? [...list.rows.value, ...batch] : batch
    list.truncated.value = res._page ? res._page.truncated : false
    list.cursor.value = res._page ? res._page.nextCursor : null
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    list.loading.value = false
  }
}

const run = async <T>(label: string, fn: () => Promise<ApiResponse<T>>): Promise<T | null> => {
  busy.value = true
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return null
    }
    return res.data as T
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
    return null
  } finally {
    busy.value = false
  }
}

const loadReviews = (append = false) => loadList(reviews, '差评告警',
  (cursor) => listReviewAlerts(shop(), { status: reviewStatus.value || undefined, size: 20, cursor }), append)
const loadHijacks = (append = false) => loadList(hijacks, '跟卖告警',
  (cursor) => listHijackAlerts(shop(), { status: hijackStatus.value || undefined, size: 20, cursor }), append)

const loadTrend = async () => {
  const keyword = rankQuery.keyword.trim()
  const asin = rankQuery.asin.trim().toUpperCase()
  if (!keyword || !asin) {
    pushError('排名趋势：关键词与 ASIN 都要填，后端两个条件都是必填')
    return
  }
  const rows = await run('排名趋势', () => getRankTrend(shop(), keyword, asin))
  if (rows === null) return
  trend.value = Array.isArray(rows) ? rows : []
  trendLoaded.value = true
}

const clearTrend = () => {
  trend.value = []
  trendLoaded.value = false
}

/** 差评告警处置：HANDLED=已介入，IGNORED=判定为无需处理。两者互斥且都只接受 NEW。 */
const askReview = (r: NegativeReviewAlert, next: 'HANDLED' | 'IGNORED') => {
  const verb = next === 'HANDLED' ? '标记已处理' : '忽略'
  confirmBox.value = {
    title: `${verb}差评告警 #${r.id}`,
    detail: next === 'HANDLED'
      ? `只会把这条告警在本地从 NEW 改成 HANDLED，不会联系买家、不会发起申诉，`
        + `也不会改动 ${r.asin} 的评论。改完之后再点一次会被后端拒绝。`
      : `只会把这条告警在本地从 NEW 改成 IGNORED，表示「判定为无需处理」。`
        + `不会联系买家、不会发起申诉，也不会阻止后续扫描为 ${r.asin} 再产生新的告警行。`
        + `改完之后再点一次会被后端拒绝。`,
    run: async () => {
      const ok = await run(verb, () => next === 'HANDLED'
        ? handleReviewAlert(r.id as number)
        : ignoreReviewAlert(r.id as number))
      if (ok === null) return
      await loadReviews()
    }
  }
}

/** 跟卖告警处置：与差评同口径的本地状态迁移，不会对跟卖方做任何动作。 */
const askHijack = (h: HijackAlert, next: 'HANDLED' | 'IGNORED') => {
  const verb = next === 'HANDLED' ? '标记已处理' : '忽略'
  confirmBox.value = {
    title: `${verb}跟卖告警 #${h.id}`,
    detail: next === 'HANDLED'
      ? `只会把这条告警在本地从 NEW 改成 HANDLED。不会对跟卖方发起任何动作，`
        + `也不代表已申诉或已交涉——这里记录的只是本店的处置状态。`
      : `只会把这条告警在本地从 NEW 改成 IGNORED，表示「判定为无需处理」。`
        + `同样不会对跟卖方做任何动作，也不会阻止后续扫描为 ${h.asin} 再产生新的告警行。`,
    run: async () => {
      const ok = await run(verb, () => next === 'HANDLED'
        ? handleHijackAlert(h.id as number)
        : ignoreHijackAlert(h.id as number))
      if (ok === null) return
      await loadHijacks()
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const loaded = new Set<TabKey>()
const gotoTab = async (key: TabKey) => {
  tab.value = key
  // 切 Tab 是整页入口：清一次全部错误，此后 loader 只按各自前缀追加/移除
  errors.value = []
  // 排名 Tab 没有「默认查哪个词」，所以不自动查
  if (key === 'rank' || !currentShopId.value || loaded.has(key)) return
  loaded.add(key)
  if (key === 'reviews') await loadReviews()
  if (key === 'hijacks') await loadHijacks()
}

onMounted(() => {
  void gotoTab('reviews')
})
</script>

<style scoped>
.alert-page { background: var(--color-background); }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter select, .field input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.mono { font-family: var(--font-mono, monospace); }
.small { font-size: 0.75rem; }
.cell-clip { max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.neg { color: var(--color-error); }
.table-card { margin-bottom: 1rem; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0 0 0.5rem; }
.form-card { padding: 0.875rem 1rem 1rem; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.form-hint { margin: 0.625rem 0 0; line-height: 1.5; }
.form-actions { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.75rem; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr)); gap: 0.625rem; padding: 0.75rem 1rem 0; }
.metric { background: var(--color-surface-variant); border-radius: var(--radius-md); padding: 0.5rem 0.625rem; display: flex; flex-direction: column; gap: 0.25rem; }
.metric-label { font-size: 0.75rem; color: var(--color-muted); }
.metric-value { font-size: 1.125rem; color: var(--color-on-surface); }
.aggregate-note { padding: 0.5rem 1rem 0; margin: 0; line-height: 1.5; }
.row-actions { white-space: nowrap; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.255rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
</style>
