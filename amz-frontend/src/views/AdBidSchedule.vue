<template>
  <div class="bs-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">分时调价</h1>
        <p class="hero-subtitle">按小时把「基准价 × 倍率」下发到广告账号 · 全系统唯一的真实改价通道</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：调价规则按店铺隔离。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        这一页和「搜索词与规则」不一样：那里的规则只出建议，<b>这里保存的启用规则会由后端每小时整点
        真实修改广告账号竞价</b>。三条必须知道的边界：
        ① 新竞价一律按 <b>基准价 × 倍率</b> 算，基准价首次触达时从广告账号认领并落库，
        所以 1.5x 不会逐小时连乘成指数；
        ② 命中条件是 <code>start_hour ≤ 当前小时 ≤ end_hour</code>，<b>不支持跨零点</b>，
        想让 22:00 到次日 02:00 生效得拆成两条；
        ③ 同一小时命中多条启用规则时按顺序依次应用，同一个关键词留下的是最后一条的倍率——
        不会叠乘，但结果取决于顺序，所以时段别重叠。
        停用或删除只影响之后的小时，<b>已经改出去的价不会自动回滚</b>，需要人工或下一条规则再改回来。
      </div>

      <template v-if="currentShopId">
        <div class="table-card" data-panel="list">
          <div class="filter-row">
            <button class="action-btn" :disabled="rows.loading.value" @click="loadList()">刷新</button>
            <button v-if="rows.truncated.value" class="action-btn"
                    :disabled="rows.loading.value" @click="loadList(true)">下一页</button>
            <button class="action-btn primary" @click="resetForm()">新建调价规则</button>
            <span class="muted">{{ pagerText }}</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>id</th><th>作用活动</th><th>生效时段</th><th>倍率</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in rows.rows.value" :key="s.id">
                <td class="mono">{{ s.id }}</td>
                <td class="mono">{{ s.campaignId || '全部活动' }}</td>
                <td class="mono">{{ hourRangeText(s.startHour, s.endHour) }}</td>
                <td>
                  <span class="status-tag" :class="multiplierClass(s.multiplier)">× {{ s.multiplier }}</span>
                </td>
                <td>
                  <span class="status-tag" :class="truthy(s.enabled) ? 'live' : 'unknown'">
                    {{ truthy(s.enabled) ? '启用中（会改价）' : '已停用' }}
                  </span>
                </td>
                <td class="row-actions">
                  <button class="action-btn" :disabled="busy" @click="edit(s)">编辑</button>
                  <button class="action-btn" :disabled="busy" @click="askToggle(s)">
                    {{ truthy(s.enabled) ? '停用' : '启用' }}
                  </button>
                  <button class="action-btn danger" :disabled="busy" @click="askDelete(s)">删除</button>
                </td>
              </tr>
              <tr v-if="!rows.loading.value && !rows.rows.value.length">
                <td colspan="6" class="empty-row">
                  这家店还没有调价规则。没有规则时广告账号保持现有竞价，不会被本页改动。
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="formOpen" class="table-card form-card">
          <h3 class="card-title">{{ form.id ? '编辑规则 #' + form.id : '新建调价规则' }}</h3>
          <div class="form-grid">
            <label class="field">活动 ID（留空＝该店铺全部活动）
              <input v-model="form.campaignId" type="text" />
            </label>
            <label class="field">起始小时
              <select v-model.number="form.startHour">
                <option v-for="h in HOURS" :key="'s' + h" :value="h">{{ h }}:00</option>
              </select>
            </label>
            <label class="field">结束小时（含）
              <select v-model.number="form.endHour">
                <option v-for="h in HOURS" :key="'e' + h" :value="h">{{ h }}:00</option>
              </select>
            </label>
            <label class="field">倍率（{{ MIN_MULTIPLIER }}~{{ MAX_MULTIPLIER }}）
              <input v-model="form.multiplier" type="number" step="0.05" />
            </label>
            <label class="field checkbox">启用
              <input v-model="form.enabled" type="checkbox" />
            </label>
          </div>
          <p class="muted effect-line">
            效果预览：命中时段内每个整点，这些活动的关键词竞价会被改成
            <b>基准价 × {{ form.multiplier || '-' }}</b>
            {{ form.enabled ? '（保存后下一个整点就生效）' : '（当前不启用，不会改价）' }}
          </p>
          <p v-if="formProblem" class="muted reject-line">按后端的规则这样写会被拒：{{ formProblem }}</p>
          <div class="form-actions">
            <button class="page-btn" :disabled="busy || !formReady" @click="askSubmit()">
              {{ form.id ? '保存修改' : '创建规则' }}
            </button>
            <button class="page-btn" @click="formOpen = false">取消</button>
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
import * as api from '@/api/adBidSchedule'
import { MAX_MULTIPLIER, MIN_MULTIPLIER, hourRangeText } from '@/api/adBidSchedule'
import type { BidSchedule } from '@/api/adBidSchedule'

const HOURS = Array.from({ length: 24 }, (_, i) => i)

const { currentShopId, refreshShop } = useShopGuard()
const errors = ref<string[]>([])
const busy = ref(false)
const rows = {
  rows: ref<BidSchedule[]>([]),
  cursor: ref<string | null>(null),
  truncated: ref(false),
  loading: ref(false)
}
const formOpen = ref(false)
const form = reactive<Record<string, any>>({})
const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const truthy = (v: unknown) => v === true || v === 1 || v === '1'
const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const pagerText = computed(() =>
  rows.truncated.value
    ? `已加载 ${rows.rows.value.length} 条 · 后端标记仍有下一页，本页不是全量`
    : `已加载 ${rows.rows.value.length} 条`)

/** 倍率越界多半是手滑，用颜色提醒而不是替用户改数 */
const multiplierClass = (m: unknown) => {
  const n = Number(m)
  if (!Number.isFinite(n)) return 'unknown'
  if (n > 1) return 'up'
  if (n < 1) return 'down'
  return 'unknown'
}

const multiplierNumber = computed(() => Number(form.multiplier))
const formProblem = computed(() => {
  if (form.startHour > form.endHour) {
    return `调度按 start ≤ 当前小时 ≤ end 命中，${form.startHour}-${form.endHour} 跨零点，永远不会生效`
  }
  if (!Number.isFinite(multiplierNumber.value)) return '倍率必须是数字'
  if (multiplierNumber.value < MIN_MULTIPLIER || multiplierNumber.value > MAX_MULTIPLIER) {
    return `倍率只能落在 ${MIN_MULTIPLIER}~${MAX_MULTIPLIER}`
  }
  return ''
})
const formReady = computed(() => formProblem.value === '')

const loadList = async (append = false) => {
  const shopId = shop()
  if (!shopId) return
  if (!append) {
    rows.rows.value = []
    rows.cursor.value = null
    // 只清本列表的错误（按「调价规则：」前缀），动作类错误互不擦
    errors.value = errors.value.filter((x) => !x.startsWith(`调价规则：`))
  }
  rows.loading.value = true
  try {
    const res = await api.listBidSchedules(shopId, { size: 20, cursor: append ? rows.cursor.value ?? undefined : undefined })
    if (res?.code !== 200) {
      pushError(`调价规则：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    rows.rows.value = append ? [...rows.rows.value, ...batch] : batch
    rows.truncated.value = res._page ? res._page.truncated : false
    rows.cursor.value = res._page ? res._page.nextCursor : null
  } catch (e) {
    pushError(`调价规则：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    rows.loading.value = false
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

const resetForm = () => {
  Object.assign(form, {
    id: undefined,
    campaignId: '',
    startHour: 20,
    endHour: 23,
    multiplier: 1.2,
    enabled: true
  })
  formOpen.value = true
}

const edit = (s: BidSchedule) => {
  resetForm()
  Object.assign(form, {
    id: s.id,
    campaignId: s.campaignId || '',
    startHour: Number(s.startHour),
    endHour: Number(s.endHour),
    multiplier: Number(s.multiplier),
    enabled: truthy(s.enabled)
  })
}

const bodyFromForm = (): Partial<BidSchedule> => {
  const body: Record<string, any> = {
    campaignId: String(form.campaignId || '').trim() || null,
    startHour: form.startHour,
    endHour: form.endHour,
    multiplier: multiplierNumber.value,
    enabled: form.enabled ? 1 : 0
  }
  if (form.id) {
    // 编辑只提交这四个字段：shopId 由后端按 id 反查，不许前端指定归属
    return body
  }
  body.shopId = Number(shop())
  return body as BidSchedule
}

const askSubmit = () => {
  const creating = !form.id
  const scopeText = form.campaignId ? `活动 ${String(form.campaignId).trim()}` : '该店铺全部活动'
  confirmBox.value = {
    title: creating ? '创建分时调价规则' : `保存规则 #${form.id}`,
    detail: `${hourRangeText(form.startHour, form.endHour)} 内的${scopeText}，`
      + `每个整点会把关键词竞价改成 基准价 × ${multiplierNumber.value}。`
      + (form.enabled
        ? '保存后启用，下一个整点即开始改价。'
        : '保存后处于停用状态，不会改价。')
      + ' 基准价首次触达时从广告账号认领，之后的调整都相对基准价计算，不会逐小时叠乘。',
    run: async () => {
      const id = form.id as number | undefined
      const saved = await run(creating ? '创建调价规则' : '保存调价规则', () =>
        creating ? api.createBidSchedule(bodyFromForm() as BidSchedule)
          : api.updateBidSchedule(id as number, bodyFromForm()))
      if (!saved) return
      formOpen.value = false
      await loadList()
    }
  }
}

const askToggle = (s: BidSchedule) => {
  const next = !truthy(s.enabled)
  confirmBox.value = {
    title: `${next ? '启用' : '停用'}规则 #${s.id}`,
    detail: next
      ? `启用后 ${hourRangeText(s.startHour, s.endHour)} 内每个整点都会按 基准价 × ${s.multiplier} 改价，`
        + '广告账号上的变化不会自动回滚。'
      : '停用只影响之后的小时：已经按这条规则改过的竞价会留在广告账号上，'
        + '需要人工改回或让另一条规则覆盖。',
    run: async () => {
      const ok = await run('启停调价规则', () => api.toggleBidSchedule(s.id as number, next))
      if (ok === null) return
      await loadList()
    }
  }
}

const askDelete = (s: BidSchedule) => {
  confirmBox.value = {
    title: `删除规则 #${s.id}`,
    detail: `删除 ${hourRangeText(s.startHour, s.endHour)} × ${s.multiplier} 这条规则，是物理删除、没有回收站。`
      + ' 已经改到广告账号上的竞价不会因为删除而恢复。',
    run: async () => {
      const ok = await run('删除调价规则', () => api.deleteBidSchedule(s.id as number))
      if (ok === null) return
      if (form.id === s.id) formOpen.value = false
      await loadList()
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

onMounted(() => {
  errors.value = []          // 整页入口清一次；loader 只按各自前缀追加/移除
  void loadList()
})
</script>

<style scoped>
.bs-page { background: var(--color-background); }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.notice-zone code { font-family: var(--font-mono, monospace); font-size: 0.8125rem; }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.table-card { margin-bottom: 1rem; }
.form-card { padding: 0.875rem 1rem 1rem; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0 0 0.5rem; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.field input, .field select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.field.checkbox { flex-direction: row; align-items: center; }
.effect-line, .reject-line { margin: 0.625rem 0 0; line-height: 1.5; }
.reject-line { color: var(--color-error); }
.form-actions { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.75rem; }
.row-actions { white-space: nowrap; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.up { background: var(--color-light-red); color: var(--color-error); }
.status-tag.down { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.live { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.empty-row { color: var(--color-muted); font-size: 0.8125rem; padding: 1rem; }
</style>
