<template>
  <div class="search-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">商品搜索</h1>
        <p class="hero-subtitle">ES 混合检索 · 热搜 Top10 · 我的搜索历史</p>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        这一域与店铺无关：历史与热搜按<b>登录用户</b>归属，检索也没有 shopId 维度，
        所以右上角选不选店铺都不影响结果。三件会改变你怎么读结果的事：
        ① 检索打在 Elasticsearch 索引 <code>amz_product</code> 上（BM25，向量可用时再 RRF 融合）；
        ES 不在线时接口直接报错，页面不会退回示例数据，只有查询向量化失败才降级为纯 BM25。
        ② 每搜一次会写一条搜索历史（同用户同关键词不重复插）并给热搜 +1 分，
        所以搜完下面的历史和热搜会跟着变。
        ③ 热搜取 Redis ZSET 前 10 名，7 天无写入自动过期；「清空历史」删掉当前用户全部历史且不可恢复，
        后端没有按条删除的端点。
      </div>

      <div class="table-card">
        <div class="filter-row">
          <label class="filter grow">关键词
            <input v-model="keyword" placeholder="商品标题 / 内容 / 摘要" @keyup.enter="runSearch()" />
          </label>
          <button class="action-btn" :disabled="busy" @click="runSearch()">搜索</button>
          <span class="muted">按回车同样提交；关键词会写进历史并计入热搜</span>
        </div>
      </div>

      <!-- 搜索结果 -->
      <div class="table-card">
        <div class="block-title">
          搜索结果
          <span v-if="results !== null" class="muted">{{ results.length }} 条</span>
        </div>
        <table v-if="results !== null" class="data-table">
          <thead>
            <tr><th>商品</th><th>SKU</th><th>价格</th><th>店铺ID</th><th>卖家</th><th>摘要</th></tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in results" :key="p.id ?? 'idx' + i">
              <td>
                <div class="title-cell">{{ p.title || '（无标题）' }}</div>
                <div v-if="p.image" class="muted cell-clip">图 {{ p.image }}</div>
              </td>
              <td class="mono">{{ p.sku || '-' }}</td>
              <td>{{ p.price ?? '-' }}</td>
              <td class="mono">{{ p.shopId ?? '-' }}</td>
              <td>{{ p.user?.nickname || p.user?.phone || (p.user ? '（无昵称）' : '-') }}</td>
              <td class="cell-clip" :title="p.summary || ''">{{ p.summary || p.content || '-' }}</td>
            </tr>
            <tr v-if="!results.length">
              <td colspan="6" class="empty-row">索引里没有匹配的商品：这是 Elasticsearch 返回的 0 条，不是没查</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block muted">还没搜索。上方输入关键词后回车即可。</div>
      </div>

      <!-- 热搜 -->
      <div class="table-card">
        <div class="block-title">
          热搜 Top10
          <button class="action-btn" :disabled="busy" @click="loadHot()">刷新</button>
        </div>
        <div v-if="hotLoading" class="muted empty-block">正在读取 Redis 计数…</div>
        <table v-else-if="hot && hot.length" class="data-table">
          <thead><tr><th>排名</th><th>关键词</th><th>热度分</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(h, i) in hot" :key="'hot' + i">
              <td>{{ i + 1 }}</td>
              <td>{{ h.key }}</td>
              <td>{{ h.score }}</td>
              <td><button class="action-btn" :disabled="busy" @click="searchWord(h.key)">搜这个词</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-block muted">
          暂无热搜：接口返回的是 null（Redis 集合为空，或 7 天无写入已过期），不是 0 分的热词。
        </div>
      </div>

      <!-- 我的搜索历史 -->
      <div class="table-card">
        <div class="block-title">
          我的搜索历史
          <span class="muted">共 {{ history.length }} 条</span>
          <button class="action-btn" :disabled="busy" @click="loadHistory()">刷新</button>
          <button class="action-btn danger" :disabled="busy || !history.length" @click="askClearHistory()">清空历史</button>
        </div>
        <table class="data-table">
          <thead><tr><th>关键词</th><th>归属用户ID</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(h, i) in history" :key="'h' + i">
              <td>{{ h.history }}</td>
              <td class="mono">{{ h.userId ?? '-' }}</td>
              <td><button class="action-btn" :disabled="busy" @click="searchWord(h.history)">再搜一次</button></td>
            </tr>
            <tr v-if="!history.length">
              <td colspan="3" class="empty-row">还没有搜索记录：搜索一次才会写入</td>
            </tr>
          </tbody>
        </table>
        <p class="muted note-line">
          后端这张表只有 keyword 与 userId 两列，没有 id，所以只能整体清空、不能按条删除。
        </p>
      </div>
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
import { onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import type { ApiResponse } from '@/api/types'
import * as api from '@/api/search'
import type { HotWord, SearchHistory, SearchProduct } from '@/api/search'

const keyword = ref('')
const results = ref<SearchProduct[] | null>(null)
const hot = ref<HotWord[] | null>(null)
const history = ref<SearchHistory[]>([])
const errors = ref<string[]>([])
const busy = ref(false)
const hotLoading = ref(true)
const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const call = async <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void): Promise<boolean> => {
  busy.value = true
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return false
    }
    apply(res.data as T)
    return true
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
    return false
  } finally {
    busy.value = false
  }
}

const loadHot = async () => {
  hotLoading.value = true
  const ok = await call('热搜列表', () => api.getHotList(), (rows) => {
    // 后端空集合返回的是 null，不是 []：保留 null 才能区分「没有计数」与「计数为 0」
    hot.value = Array.isArray(rows) ? rows : null
  })
  if (!ok) hot.value = null
  hotLoading.value = false
}

const loadHistory = () => call('搜索历史', () => api.getHistoryList(),
  (rows) => { history.value = Array.isArray(rows) ? rows : [] })

const runSearch = async () => {
  const key = keyword.value.trim()
  if (!key) {
    pushError('搜索：关键词不能为空')
    return
  }
  const ok = await call('商品搜索', () => api.searchProducts(key),
    (rows) => { results.value = Array.isArray(rows) ? rows : [] })
  if (!ok) {
    results.value = null
    return
  }
  // 一次搜索会同时写历史与热搜计数，所以两个块都要跟着刷新，
  // 否则页面显示的是上一次的状态。
  await Promise.all([loadHistory(), loadHot()])
}

const searchWord = async (word: string | number | null | undefined) => {
  if (word === null || word === undefined || String(word).trim() === '') {
    pushError('这个词是空的，无法搜索')
    return
  }
  keyword.value = String(word)
  await runSearch()
}

const askClearHistory = () => {
  confirmBox.value = {
    title: '清空我的搜索历史',
    detail: `会删除当前用户全部 ${history.value.length} 条搜索历史，不可恢复。`
      + '后端只有整体删除，没有按条删除；热搜计数与已经返回过的搜索结果不受影响。',
    run: async () => {
      const ok = await call('清空搜索历史', () => api.deleteHistory(), () => undefined)
      if (ok) await loadHistory()
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

onMounted(async () => {
  await Promise.all([loadHot(), loadHistory()])
})
</script>

<style scoped>
.search-page { background: var(--color-background); }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.notice-zone code { font-size: 0.8125rem; }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter.grow { flex: 1 1 18rem; }
.filter input { flex: 1; padding: 0.4rem 0.6rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.875rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.cell-clip { max-width: 18rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.title-cell { font-weight: 600; color: var(--color-on-surface); }
.block-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); padding: 0.75rem 1rem 0.25rem; display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.table-card { margin-bottom: 1rem; }
.empty-block { padding: 1rem; font-size: 0.8125rem; }
.note-line { padding: 0.25rem 1rem 0.75rem; margin: 0; }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 520px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
</style>
