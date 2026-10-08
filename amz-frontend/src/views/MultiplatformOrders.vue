<template>
  <div class="mp-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">多平台订单</h1>
        <p class="hero-subtitle">Temu · TikTok · Shein 统一订单 · 手动同步 · 发货回传</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：订单与同步都以选中店铺为范围。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四条边界：① 列表读的是<b>本地统一订单表</b>，只有点「同步」才会真的去平台拉单，
        所以「列表为空」不等于平台没订单，也可能只是从没同步过；
        ② 同步时<b>单个平台失败不影响另外两个</b>，失败的平台会点名——
        只看新增条数会把「三个平台全挂」读成「没有新单」；
        ③ <b>发货是真实回传给平台</b>的动作，平台接受后本地才变 SHIPPED，
        平台拒绝时本地状态不动；
        ④ 商品/消息/库存的同步端点在三家真实客户端里都还没实现（后端会返回业务失败），
        所以本页不提供那几个按钮，不做必然失败的入口。
      </div>

      <template v-if="currentShopId">
        <div class="table-card" data-panel="sync">
          <div class="filter-row">
            <button class="action-btn primary" :disabled="busy" @click="askSyncAll()">同步全部平台</button>
            <button
              v-for="p in PLATFORMS" :key="p"
              class="action-btn" :disabled="busy" @click="askSyncOne(p)"
            >仅同步 {{ p }}</button>
            <span class="muted">同步是「拉平台近期订单 + 按 (平台, 平台单号) 去重入库」</span>
          </div>
          <div v-if="lastSync" class="sync-result">
            <span class="sync-scope">{{ lastSyncScope }}</span>
            尝试 {{ lastSync.attempted }} · 成功 {{ lastSync.succeeded }} · 失败 {{ lastSync.failed }}
            · 新增入库 {{ lastSync.inserted }} 单
            <span v-if="lastSync.failedPlatforms && lastSync.failedPlatforms.length" class="sync-fail">
              失败平台：{{ lastSync.failedPlatforms.join('、') }}
            </span>
            <p class="muted">
              新增 0 单在失败平台为 0 时才等于「确实没有新单」；否则上面的数字只是没拉回来。
            </p>
          </div>
          <div v-if="lastPlatformSync !== null" class="sync-result">
            <span class="sync-scope">{{ lastPlatformScope }}</span>本次新增入库 {{ lastPlatformSync }} 单
            <p class="muted">这个数字是本次新增，不是平台总单量；重复单会被去重跳过。</p>
          </div>
        </div>

        <div class="table-card" data-panel="orders">
          <div class="filter-row">
            <label class="filter">平台
              <select v-model="platform" @change="loadOrders()">
                <option value="">全部平台</option>
                <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
              </select>
            </label>
            <button class="action-btn" :disabled="rows.loading.value" @click="loadOrders()">刷新</button>
            <button v-if="rows.truncated.value" class="action-btn"
                    :disabled="rows.loading.value" @click="loadOrders(true)">下一页</button>
            <span class="muted">{{ pagerText }}</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>统一单号</th><th>平台</th><th>平台单号</th><th>买家/国家</th>
                <th>金额</th><th>状态</th><th>运单号</th><th>平台下单时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="o in rows.rows.value" :key="o.id">
                <td class="mono">{{ o.unifiedOrderNo || '-' }}</td>
                <td><span class="status-tag plat">{{ o.platform }}</span></td>
                <td class="mono">{{ o.platformOrderNo }}</td>
                <td>{{ o.buyerNickname || '-' }} / {{ o.shipCountry || '-' }}</td>
                <td>
                  {{ money(o.originalAmount) }} {{ o.currency || '' }}
                  <span v-if="o.cnyAmount !== null && o.cnyAmount !== undefined" class="muted">
                    （≈ {{ money(o.cnyAmount) }} CNY）
                  </span>
                </td>
                <td><span class="status-tag" :class="statusClass(o.status)">{{ o.status || '-' }}</span></td>
                <td class="mono">{{ o.trackingNo || '-' }}</td>
                <td class="mono">{{ o.orderCreateTime || '-' }}</td>
                <td>
                  <button class="action-btn" :disabled="busy || !o.id" @click="askShip(o)">发货回传</button>
                </td>
              </tr>
              <tr v-if="!rows.loading.value && !rows.rows.value.length">
                <td colspan="9" class="empty-row">
                  这家店在本地还没有订单记录。可能是从没同步过（点上面的「同步全部平台」），
                  也可能平台确实没有近期订单——这两种情况在同步结果出来之前分不出来。
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </main>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p v-if="confirmBox.tracking !== undefined" class="field-line">
          运单号 <input v-model="trackingInput" class="cell-input" placeholder="平台可识别的物流单号" />
        </p>
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
import { computed, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import * as api from '@/api/multiplatform'
import { FINAL_STATUSES, PLATFORMS } from '@/api/multiplatform'
import type { OrderSyncSummary, UnifiedOrder } from '@/api/multiplatform'

const { currentShopId, refreshShop } = useShopGuard()
const errors = ref<string[]>([])
const busy = ref(false)
const platform = ref('')
const trackingInput = ref('')

const rows = {
  rows: ref<UnifiedOrder[]>([]),
  cursor: ref<string | null>(null),
  truncated: ref(false),
  loading: ref(false)
}

const lastSync = ref<OrderSyncSummary | null>(null)
const lastSyncScope = ref('')
const lastPlatformSync = ref<number | null>(null)
const lastPlatformScope = ref('')

const confirmBox = ref<null | {
  title: string; detail: string; tracking?: string; run: () => Promise<void>
}>(null)

const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
/** 每次主动操作都从干净的消息区开始，否则上一次被拦下的提示会一直挂在顶上 */
const beginAction = () => {
  errors.value = []
}

const pagerText = computed(() =>
  rows.truncated.value
    ? `已加载 ${rows.rows.value.length} 条 · 后端标记仍有下一页，本页不是全量`
    : `已加载 ${rows.rows.value.length} 条`)

const money = (v: unknown) => (v === null || v === undefined || v === '' ? '-' : String(v))
const statusClass = (s?: string | null) =>
  s === 'SHIPPED' || s === 'DELIVERED' || s === 'COMPLETED' ? 'healthy'
    : s === 'CANCELED' || s === 'REFUNDED' ? 'urgent'
      : s === 'PAID' ? 'risk' : 'unknown'
const isFinal = (s?: string | null) => !!s && FINAL_STATUSES.includes(s)

const call = async <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void) => {
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

const loadOrders = async (append = false) => {
  const shopId = shop()
  if (!shopId) return
  if (!append) {
    rows.rows.value = []
    rows.cursor.value = null
    // 只清本列表的错误（按「订单列表：」前缀），动作提示与并发 loader 互不擦
    errors.value = errors.value.filter((x) => !x.startsWith(`订单列表：`))
  }
  rows.loading.value = true
  const cursor = append ? rows.cursor.value ?? undefined : undefined
  const fetcher = platform.value
    ? () => api.listOrdersByPlatform(shopId, platform.value, { size: 20, cursor })
    : () => api.listOrders(shopId, { size: 20, cursor })
  try {
    const res = await fetcher()
    if (res?.code !== 200) {
      pushError(`订单列表：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    rows.rows.value = append ? [...rows.rows.value, ...batch] : batch
    rows.truncated.value = res._page ? res._page.truncated : false
    rows.cursor.value = res._page ? res._page.nextCursor : null
  } catch (e) {
    pushError(`订单列表：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    rows.loading.value = false
  }
}

const askSyncAll = () => {
  confirmBox.value = {
    title: '同步三个平台的近期订单',
    detail: '会逐个调用 Temu / TikTok / Shein 的订单接口并把新单写入本地统一订单表，'
      + '不改平台数据。单个平台失败不会中断另外两个，但失败的平台这一轮不会有任何新增。',
    run: async () => {
      beginAction()
      const ok = await call('全平台同步', () => api.syncAllPlatforms(shop()),
        (s) => { lastSync.value = s })
      if (!ok || !lastSync.value) return
      lastSyncScope.value = `全平台同步（店铺 ${shop()}）`
      lastPlatformSync.value = null
      await loadOrders()
    }
  }
}

const askSyncOne = (p: string) => {
  confirmBox.value = {
    title: `仅同步 ${p}`,
    detail: `只调用 ${p} 的订单接口。若这家平台的真实客户端尚未接入，`
      + '后端会返回「能力未接入」而不是空列表——那种情况下新增 0 不代表没有订单。',
    run: async () => {
      beginAction()
      const ok = await call(`${p} 同步`, () => api.syncPlatform(shop(), p),
        (n) => { lastPlatformSync.value = typeof n === 'number' ? n : 0 })
      if (!ok) return
      lastPlatformScope.value = `${p}（店铺 ${shop()}）`
      await loadOrders()
    }
  }
}

const askShip = (o: UnifiedOrder) => {
  trackingInput.value = o.trackingNo || ''
  confirmBox.value = {
    title: `回传发货：${o.platform} 单 ${o.platformOrderNo}`,
    detail: '这是会改动平台侧状态的操作：运单号会被提交给该平台，'
      + '平台接受后本地订单才变成 SHIPPED；平台拒绝时本地不写状态，也不会重试。'
      + (isFinal(o.status) ? ` 这单当前已是 ${o.status}，重复回传大概率被平台拒。` : ''),
    tracking: '',
    run: async () => {
      beginAction()
      const tracking = trackingInput.value.trim()
      if (!tracking) {
        pushError('发货回传：运单号不能为空')
        return
      }
      const ok = await call('发货回传', () => api.markOrderShipped(o.id as number, tracking),
        (applied) => { shipApplied.value = applied === true })
      if (!ok) return
      if (shipApplied.value === false) {
        pushError(`${o.platform} 未接受这次发货回传：本地订单状态未变，仍是 ${o.status || '未知'}`)
      }
      // 读回真实状态：loadOrders 只清「订单列表：」前缀，上面那句平台未接受的提示保留
      await loadOrders()
    }
  }
}

const shipApplied = ref<boolean | null>(null)

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

onMounted(() => {
  errors.value = []   // 整页入口清一次；loader 只按各自前缀追加/移除
  void loadOrders()
})
</script>

<style scoped>
.mp-page { background: var(--color-background); }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.table-card { margin-bottom: 1rem; }
.sync-result { margin: 0.75rem 1rem; padding: 0.5rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-surface-variant); font-size: 0.8125rem; color: var(--color-on-surface); }
.sync-result p { margin: 0.375rem 0 0; }
.sync-scope { font-weight: 600; margin-right: 0.25rem; }
.sync-fail { color: var(--color-error); }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.plat { background: var(--color-muted-light); color: var(--color-muted); }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.field-line { font-size: 0.8125rem; color: var(--color-muted); display: flex; align-items: center; gap: 0.5rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.empty-row { color: var(--color-muted); font-size: 0.8125rem; padding: 1rem; }
</style>
