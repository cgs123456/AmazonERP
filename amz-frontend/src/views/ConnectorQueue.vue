<template>
  <div class="queue-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">调用队列与限流</h1>
        <p class="hero-subtitle">SP-API 发件箱 · 失败与 DLQ · 人工重放 · 限流观测</p>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条会影响你怎么读这张表的边界：① 本页不看右上角店铺选择——列表由 token 里的授权店铺过滤，
        非 ADMIN 且没有任何授权店铺时后端直接拒，只有 ADMIN 的空 shops 才被当成全局范围；
        ② 自动重放调度只碰 GET/HEAD，<b>人工点「重放」会按记录原方法重发</b>，
        POST/PUT/PATCH/DELETE 可能有远端副作用（重复下单/重复提报），所以一律二次确认，DLQ 也只能这样人工兜底；
        ③ outbox 未启用时后端返回的是失败而不是空列表——看到错误条不要理解成「队列为空」。
        限流观测同理：那是从真实响应头里学到的值，没跑过的操作不会出现在表里。
      </div>

      <div class="tabs">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <!-- ==================== 发件箱 ==================== -->
      <div v-if="tab === 'outbox'" class="tab-panel" data-panel="outbox">
        <div class="table-card">
          <div class="filter-row">
            <label class="filter">状态
              <select v-model="status" @change="loadOutbox()">
                <option value="">全部</option>
                <option v-for="s in OUTBOX_STATUSES" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <label class="filter">条数上限
              <input class="cell-input" type="number" min="1" max="200" v-model="limit" />
            </label>
            <button class="action-btn" :disabled="loadingOutbox" @click="loadOutbox()">刷新</button>
            <span class="muted">后端单次最多 200 条，超出的记录不在结论里</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>id</th><th>店铺</th><th>方法</th><th>路径</th><th>状态</th><th>尝试</th>
                <th>响应码</th><th>错误</th><th>创建时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td class="mono">{{ row.id }}</td>
                <td class="mono">{{ row.shopId ?? '-' }}</td>
                <td><span class="status-tag" :class="isWriteMethod(row.httpMethod) ? 'urgent' : 'unknown'">{{ row.httpMethod }}</span></td>
                <td class="cell-clip" :title="row.requestPath || ''">{{ row.requestPath }}</td>
                <td><span class="status-tag" :class="statusClass(row.status)">{{ row.status }}</span></td>
                <td>{{ row.attemptCount ?? '-' }} / {{ row.maxAttempts ?? '-' }}</td>
                <td>{{ row.responseStatus ?? '-' }}</td>
                <td class="reason">{{ row.lastErrorCode || row.lastErrorMessage || '-' }}</td>
                <td class="mono">{{ row.createdAt || '-' }}</td>
                <td>
                  <button class="action-btn danger" :disabled="busy || !row.id" @click="askReplay(row)">重放</button>
                </td>
              </tr>
              <tr v-if="!loadingOutbox && !rows.length">
                <td colspan="10" class="empty-row">当前筛选条件下没有记录（这是查询结果，不代表 outbox 未启用）</td>
              </tr>
            </tbody>
          </table>
          <p class="muted note-line">
            共 {{ rows.length }} 条；写方法（POST/PUT/PATCH/DELETE）重放会有远端副作用，读方法重放只是再问一次。
          </p>
        </div>
      </div>

      <!-- ==================== 限流观测 ==================== -->
      <div v-if="tab === 'ratelimit'" class="tab-panel" data-panel="ratelimit">
        <div class="table-card">
          <div class="filter-row">
            <button class="action-btn" :disabled="loadingRate" @click="loadRateLimits()">刷新</button>
            <span class="muted">观测值来自真实响应头，没跑过的操作不在表里</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>店铺</th><th>操作</th><th>变体</th><th>响应头原值</th>
                <th>观测速率/秒</th><th>生效速率/秒</th><th>burst</th><th>观测时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="(o, i) in rateRows" :key="'r' + i">
                <td class="mono">{{ o.shopId ?? '-' }}</td>
                <td class="mono">{{ o.operationId }}</td>
                <td>{{ o.variant || '-' }}</td>
                <td class="cell-clip mono" :title="o.headerValue || ''">{{ o.headerValue || '-' }}</td>
                <td>{{ o.observedRatePerSecond ?? '-' }}</td>
                <td>{{ o.effectiveRatePerSecond ?? '-' }}</td>
                <td>{{ o.burst ?? '-' }}</td>
                <td class="mono">{{ o.observedAt || '-' }}</td>
              </tr>
              <tr v-if="!loadingRate && !rateRows.length">
                <td colspan="8" class="empty-row">还没有任何限流观测：说明本进程的 SP-API 调用还没带回速率头</td>
              </tr>
            </tbody>
          </table>
        </div>
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
import { ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import type { ApiResponse } from '@/api/types'
import * as api from '@/api/connectors'
import { OUTBOX_STATUSES } from '@/api/connectors'
import type { OutboxRecord, RateLimitObservation, ReplayResult } from '@/api/connectors'

type TabKey = 'outbox' | 'ratelimit'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'outbox', label: '发件箱' },
  { key: 'ratelimit', label: '限流观测' }
]

const tab = ref<TabKey>('outbox')
const errors = ref<string[]>([])
const busy = ref(false)
const loadingOutbox = ref(false)
const loadingRate = ref(false)

const rows = ref<OutboxRecord[]>([])
const status = ref('')
const limit = ref<number | string>(50)
const rateRows = ref<RateLimitObservation[]>([])
const replayResult = ref<ReplayResult | null>(null)

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const isWriteMethod = (m?: string) => ['POST', 'PUT', 'PATCH', 'DELETE'].includes((m || '').toUpperCase())
const statusClass = (s?: string) =>
  s === 'SUCCEEDED' || s === 'REPLAYED' ? 'healthy'
    : s === 'DLQ' ? 'urgent'
      : s === 'FAILED' || s === 'REPLAYING' ? 'risk' : 'unknown'

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

const loadOutbox = async () => {
  loadingOutbox.value = true
  // 只清本列表的错误（按「发件箱：」前缀）：重放后刷新列表时，「重放结果：」那条
  // 未成功提示归它自己的前缀管，不会被这次刷新擦掉
  errors.value = errors.value.filter((x) => !x.startsWith(`发件箱：`))
  try {
    const size = Number(limit.value)
    const res = await api.listOutbox({
      status: status.value || undefined,
      limit: Number.isFinite(size) && size > 0 ? Math.min(size, 200) : undefined
    })
    if (res?.code !== 200) {
      pushError(`发件箱：${res?.message || '后端返回非 200'}`)
      rows.value = []
      return
    }
    rows.value = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    pushError(`发件箱：${e instanceof Error ? e.message : '调用失败'}`)
    rows.value = []
  } finally {
    loadingOutbox.value = false
  }
}

const loadRateLimits = async () => {
  loadingRate.value = true
  const ok = await call('限流观测', () => api.listRateLimits(),
    (list) => { rateRows.value = Array.isArray(list) ? list : [] })
  if (!ok) rateRows.value = []
  loadingRate.value = false
}

const askReplay = (row: OutboxRecord) => {
  const write = isWriteMethod(row.httpMethod)
  confirmBox.value = {
    title: `重放记录 #${row.id}`,
    detail: `将按原方法 ${row.httpMethod} ${row.requestPath} 重新发起 SP-API 调用`
      + `（店铺 ${row.shopId ?? '-'}，已尝试 ${row.attemptCount ?? 0}/${row.maxAttempts ?? '-'} 次）。`
      + (write
        ? '这是写操作：可能在平台侧产生真实副作用（重复提报、重复修改），确认前请核对原请求。'
        : '这是只读方法：重放只是再问一次。')
      + ' 状态会随之变化，重放后列表会自动刷新。',
    run: async () => {
      // 后端在重放未成功时返回的是非 200，apply 收不到值：那种情况由 call 里的错误条负责
      const ok = await call('重放', () => api.replayOutbox(row.id as number),
        (r) => { replayResult.value = r })
      if (ok && replayResult.value && !replayResult.value.success) {
        pushError(`重放结果：${replayResult.value.outcome || '未成功'}`)
      }
      await loadOutbox()
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const gotoTab = async (key: TabKey) => {
  tab.value = key
  // 切 Tab 是整页入口：清一次全部错误，此后 loader 只按各自前缀追加/移除
  errors.value = []
  if (key === 'outbox' && !outboxLoaded) {
    outboxLoaded = true
    await loadOutbox()
  }
  if (key === 'ratelimit' && !rateLoaded) {
    rateLoaded = true
    await loadRateLimits()
  }
}
let outboxLoaded = false
let rateLoaded = false

onMounted(() => {
  void gotoTab('outbox')
})
</script>

<style scoped>
.queue-page { background: var(--color-background); }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.reason { font-size: 0.75rem; color: var(--color-error); max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-clip { max-width: 18rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-card { margin-bottom: 1rem; }
.note-line { padding: 0.5rem 1rem 0.75rem; margin: 0; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
</style>
