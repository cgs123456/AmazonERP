<template>
  <div class="alerts-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">海外仓库存与预警</h1>
        <p class="hero-subtitle">多仓库存快照 · 预警规则 · 立即检查</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺后再查看库存与预警。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四条判读边界：① 页面不提供手工录入库存——数量应由海外仓回传，手填等于往快照表造数
        （后端有 <code>POST /stock</code>，这里刻意不接）；
        ② 预警类型只有 5 个值会被真的判定
        （{{ ALERT_TYPES.join(' / ') }}），其它类型在 <code>evaluateAlert</code> 里落到 default，
        规则存得进去但<b>永远不会触发</b>；
        ③ 阈值单位只有字面 <code>DAYS</code> 走天数分支，其余一律按数量比；且 LOW_STOCK/STOCKOUT
        选 DAYS 时后端比的是<b>在库天数 ≤ 阈值</b>（源码注释自陈是按在库天数反算的简化），不是可售天数；
        ④「立即检查」只做一次判定并返回结果：不发任何通知（<code>notify_channels</code> 是只存不读的字段），
        也不写库；扫描有 500 行上限，返回 <code>stocksTruncated=true</code> 时结论不是全量。
      </div>

      <template v-if="currentShopId">
        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="gotoTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- ==================== 库存快照 ==================== -->
        <div v-if="tab === 'stock'" class="tab-panel" data-panel="stock">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">SKU<input v-model="stockSku" @keyup.enter="loadStock()" /></label>
              <label class="filter">仓库
                <select v-model="stockWarehouseId">
                  <option value="">全部</option>
                  <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="stock.loading.value" @click="loadStock()">刷新</button>
              <span class="muted">快照按仓库+SKU+id 排序，游标分页</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>SKU</th><th>仓库</th><th>类型</th><th>可用</th><th>预留</th><th>在途入</th>
                  <th>调拨出</th><th>合计</th><th>在库天数</th><th>库存货值</th><th>快照时间</th></tr>
              </thead>
              <tbody>
                <tr v-for="s in stock.rows.value" :key="s.id">
                  <td class="mono">{{ s.sku }}</td>
                  <td>{{ s.warehouseName || s.warehouseId }}</td>
                  <td>{{ s.warehouseType || '-' }}</td>
                  <td :class="numClass(s.availableQty)">{{ s.availableQty ?? '-' }}</td>
                  <td>{{ s.reservedQty ?? '-' }}</td>
                  <td>{{ s.inboundQty ?? '-' }}</td>
                  <td>{{ s.transferOutQty ?? '-' }}</td>
                  <td>{{ s.totalQty ?? '-' }}</td>
                  <td>{{ s.daysInStock ?? '-' }}</td>
                  <td>{{ s.totalValue ?? '-' }}</td>
                  <td class="mono">{{ s.snapshotTime || '-' }}</td>
                </tr>
                <tr v-if="!stock.loading.value && !stock.rows.value.length">
                  <td colspan="11" class="empty-row">该店铺还没有库存快照：快照由海外仓回传写入，页面不提供手填</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(stock) }}</span>
              <div class="page-actions">
                <button v-if="stock.truncated.value" class="page-btn" :disabled="stock.loading.value"
                        @click="loadStock(true)">加载下一页</button>
              </div>
            </div>
          </div>
        </div>

        <!-- ==================== 预警规则 ==================== -->
        <div v-if="tab === 'alert'" class="tab-panel" data-panel="alert">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">启用状态
                <select v-model="alertEnabled" @change="loadAlerts()">
                  <option value="">全部</option>
                  <option value="true">仅启用</option>
                  <option value="false">仅停用</option>
                </select>
              </label>
              <button class="action-btn" :disabled="alerts.loading.value" @click="loadAlerts()">刷新</button>
              <button class="action-btn" @click="openRuleModal()">新建规则</button>
              <button class="action-btn danger" :disabled="busy" @click="askCheck()">立即检查</button>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>类型</th><th>阈值</th><th>单位</th><th>级别</th><th>SKU</th><th>仓库</th>
                  <th>通知渠道</th><th>状态</th><th>说明</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="a in alerts.rows.value" :key="a.id">
                  <td>{{ a.alertType }}<span v-if="!isJudgedType(a.alertType)" class="neg">（不会被判定）</span></td>
                  <td>{{ a.thresholdValue }}</td>
                  <td>{{ a.thresholdUnit || '-' }}</td>
                  <td><span class="status-tag" :class="levelClass(a.alertLevel)">{{ a.alertLevel || '-' }}</span></td>
                  <td class="mono">{{ a.sku || '全部' }}</td>
                  <td>{{ a.warehouseId ? (warehouseName(a.warehouseId) || a.warehouseId) : '全部' }}</td>
                  <td class="cell-clip" :title="a.notifyChannels || ''">
                    <span v-if="a.notifyChannels">{{ a.notifyChannels }}（只存不读）</span>
                    <span v-else>-</span>
                  </td>
                  <td><span class="status-tag" :class="truthy(a.enabled) ? 'healthy' : 'unknown'">{{ truthy(a.enabled) ? '启用' : '停用' }}</span></td>
                  <td class="cell-clip" :title="a.description || ''">{{ a.description || '-' }}</td>
                  <td>
                    <button class="action-btn" :disabled="busy" @click="toggle(a)">{{ truthy(a.enabled) ? '停用' : '启用' }}</button>
                  </td>
                </tr>
                <tr v-if="!alerts.loading.value && !alerts.rows.value.length">
                  <td colspan="10" class="empty-row">该店铺还没有预警规则，「立即检查」会一条不报</td>
                </tr>
              </tbody>
            </table>
            <p class="muted note-line">共 {{ alerts.rows.value.length }} 条规则（后端不分页）。</p>
          </div>
        </div>

        <!-- ==================== 检查结果 ==================== -->
        <div v-if="tab === 'check'" class="tab-panel" data-panel="check">
          <div class="table-card">
            <div class="filter-row">
              <button class="action-btn danger" :disabled="busy" @click="askCheck()">立即检查</button>
              <span class="muted">检查是只读的：不发通知、不写库</span>
            </div>
            <div v-if="!report" class="empty-block muted">还没检查过。点「立即检查」按当前启用中的规则跑一次。</div>
            <template v-else>
              <div class="filter-row">
                <span class="status-tag unknown">规则 {{ report.alertRulesChecked }} 条</span>
                <span class="status-tag" :class="report.totalTriggered ? 'urgent' : 'healthy'">触发 {{ report.totalTriggered }} 条</span>
                <span class="status-tag urgent">CRITICAL {{ report.critical }}</span>
                <span class="status-tag risk">WARNING {{ report.warning }}</span>
                <span class="status-tag unknown">INFO {{ report.info }}</span>
                <span class="muted">本次扫描库存 {{ report.scannedStockCount ?? '-' }} 行</span>
              </div>
              <p v-if="report.stocksTruncated" class="advisory">
                库存扫描已到 500 行上限，这个结论只覆盖被扫到的那部分，不是全量库存的判断结果。
              </p>
              <table class="data-table">
                <thead><tr><th>SKU</th><th>仓库</th><th>可用</th><th>在库天数</th><th>货值</th><th>规则</th><th>级别</th></tr></thead>
                <tbody>
                  <tr v-for="(h, i) in report.alerts" :key="'h' + i">
                    <td class="mono">{{ h.sku || '-' }}</td>
                    <td>{{ h.warehouseName || '-' }}</td>
                    <td :class="numClass(h.availableQty)">{{ h.availableQty ?? '-' }}</td>
                    <td>{{ h.daysInStock ?? '-' }}</td>
                    <td>{{ h.totalValue ?? '-' }}</td>
                    <td>{{ h.alertType }}<span class="muted"> #{{ h.alertId }}</span></td>
                    <td><span class="status-tag" :class="levelClass(h.alertLevel)">{{ h.alertLevel || '-' }}</span></td>
                  </tr>
                  <tr v-if="!report.alerts.length"><td colspan="7" class="empty-row">没有规则触发：这是本次判定的真实结果</td></tr>
                </tbody>
              </table>
            </template>
          </div>
        </div>
      </template>
    </main>

    <!-- 新建规则 -->
    <div v-if="ruleModal" class="modal-mask" @click.self="ruleModal = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>新建预警规则</h3>
        <p class="modal-note">
          类型只有 {{ ALERT_TYPES.join(' / ') }} 会被真的判定；阈值单位只有 DAYS 走天数分支，
          其它值一律按数量比；通知渠道存了也没人读。
        </p>
        <div class="form-grid">
          <label>预警类型 *
            <select v-model="ruleForm.alertType">
              <option v-for="t in ALERT_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </label>
          <label>级别
            <select v-model="ruleForm.alertLevel">
              <option v-for="l in ALERT_LEVELS" :key="l" :value="l">{{ l }}</option>
            </select>
          </label>
          <label>阈值 *<input type="number" min="0" v-model="ruleForm.thresholdValue" /></label>
          <label>阈值单位
            <select v-model="ruleForm.thresholdUnit">
              <option v-for="u in THRESHOLD_UNITS" :key="u" :value="u">{{ u }}</option>
            </select>
          </label>
          <label>限定 SKU<input v-model="ruleForm.sku" placeholder="留空 = 全部 SKU" /></label>
          <label>限定仓库
            <select v-model="ruleForm.warehouseId">
              <option value="">全部仓库</option>
              <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
            </select>
          </label>
          <label class="span2">说明<input v-model="ruleForm.description" /></label>
          <label class="span2">通知渠道（仅存字段，系统里没有读取方）<input v-model="ruleForm.notifyChannels" placeholder="如 EMAIL,FEISHU" /></label>
        </div>
        <div class="modal-actions">
          <button class="page-btn" @click="ruleModal = false">取消</button>
          <button class="page-btn" :disabled="busy" @click="submitRule()">提交</button>
        </div>
      </div>
    </div>

    <!-- 二次确认 -->
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
import { reactive, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import * as api from '@/api/warehouseAlerts'
import { ALERT_TYPES, ALERT_LEVELS, THRESHOLD_UNITS } from '@/api/warehouseAlerts'
import type { AlertCheckReport, InventoryAlert, WarehouseStock } from '@/api/warehouseAlerts'
import { listWarehouses } from '@/api/warehouse'
import type { Warehouse } from '@/api/warehouse'

type TabKey = 'stock' | 'alert' | 'check'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'stock', label: '库存快照' },
  { key: 'alert', label: '预警规则' },
  { key: 'check', label: '检查结果' }
]

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('stock')
const errors = ref<string[]>([])
const busy = ref(false)
const warehouses = ref<Warehouse[]>([])

const stock = mkList<WarehouseStock>()
const stockSku = ref('')
const stockWarehouseId = ref<number | string>('')
const alerts = mkList<InventoryAlert>()
const alertEnabled = ref('')
const report = ref<AlertCheckReport | null>(null)

const ruleModal = ref(false)
const ruleForm = reactive<Record<string, any>>({})

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

const pagerText = (list: ListState<unknown>): string =>
  list.truncated.value ? `已加载 ${list.rows.value.length} 条 · 后端标记仍有下一页` : `已加载 ${list.rows.value.length} 条`

const truthy = (v: unknown) => v === true || v === 1 || v === '1'
const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const isJudgedType = (t?: string) => !!t && ALERT_TYPES.includes(t)
const numClass = (v?: number | null) => (v === 0 || v === null ? 'neg' : '')
const levelClass = (l?: string | null) => (l === 'CRITICAL' ? 'urgent' : l === 'WARNING' ? 'risk' : 'unknown')
const warehouseName = (id?: number | string | null) => warehouses.value.find((w) => w.id === id)?.warehouseName

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
    errors.value = []
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

const loadStock = (append = false) => loadList(stock, '库存快照', (cursor) =>
  api.listStock(shop(), {
    sku: stockSku.value || undefined,
    warehouseId: stockWarehouseId.value || undefined,
    size: 50, cursor
  }), append)

const loadAlerts = (append = false) => loadList(alerts, '预警规则', () =>
  api.listAlerts(shop(), alertEnabled.value === '' ? undefined : alertEnabled.value === 'true'), append)

const loadWarehouses = async () => {
  const ok = await call('仓库列表', () => listWarehouses(shop()), (rows) => {
    warehouses.value = Array.isArray(rows) ? rows : []
  })
  if (!ok) warehouses.value = []
}

const openRuleModal = () => {
  Object.keys(ruleForm).forEach((k) => delete ruleForm[k])
  Object.assign(ruleForm, {
    alertType: 'LOW_STOCK',
    alertLevel: 'WARNING',
    thresholdValue: 0,
    thresholdUnit: 'QTY',
    sku: '',
    warehouseId: '',
    description: '',
    notifyChannels: ''
  })
  ruleModal.value = true
}

const submitRule = async () => {
  const threshold = Number(ruleForm.thresholdValue)
  if (!String(ruleForm.alertType || '').trim()) {
    pushError('新建规则：预警类型必选')
    return
  }
  if (!Number.isFinite(threshold) || threshold < 0) {
    pushError('新建规则：阈值必须是不小于 0 的数字')
    return
  }
  const body: Record<string, unknown> = {
    shopId: shop(),
    alertType: ruleForm.alertType,
    alertLevel: ruleForm.alertLevel,
    thresholdValue: threshold,
    thresholdUnit: ruleForm.thresholdUnit,
    sku: String(ruleForm.sku || '').trim() || null,
    warehouseId: ruleForm.warehouseId === '' ? null : Number(ruleForm.warehouseId),
    description: String(ruleForm.description || '').trim() || null,
    notifyChannels: String(ruleForm.notifyChannels || '').trim() || null,
    enabled: true
  }
  const ok = await call('新建预警规则', () => api.createAlert(body as Partial<InventoryAlert>), () => undefined)
  if (ok) {
    ruleModal.value = false
    await loadAlerts()
  }
}

const toggle = async (a: InventoryAlert) => {
  const next = !truthy(a.enabled)
  const ok = await call(next ? '启用规则' : '停用规则', () => api.toggleAlert(a.id as number, next), () => undefined)
  if (ok) await loadAlerts()
}

const askCheck = () => {
  confirmBox.value = {
    title: '立即执行预警检查',
    detail: '会按当前启用中的规则扫一遍库存快照（上限 500 行）。这一步只读：不发任何通知'
      + '（notify_channels 存了也没有读取方），也不写库；停用的规则不参与检查。',
    run: async () => {
      const ok = await call('预警检查', () => api.checkAlerts(shop()), (r) => { report.value = r })
      if (ok) tab.value = 'check'
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const TAB_LOADERS: Record<TabKey, () => Promise<unknown>> = {
  stock: () => loadStock(),
  alert: () => loadAlerts(),
  check: async () => undefined
}
const loaded = new Set<TabKey>()
const gotoTab = async (key: TabKey) => {
  tab.value = key
  if (loaded.has(key)) return
  loaded.add(key)
  await TAB_LOADERS[key]()
}

onMounted(async () => {
  if (!shop()) return
  await loadWarehouses()
  await gotoTab('stock')
})
</script>

<style scoped>
.alerts-page { background: var(--color-background); }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.notice-zone code { font-size: 0.8125rem; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.neg { color: var(--color-error); font-weight: 600; font-size: 0.75rem; }
.cell-clip { max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-card { margin-bottom: 1rem; }
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; }
.note-line { padding: 0.5rem 1rem 0.75rem; margin: 0; }
.empty-block { padding: 1rem; font-size: 0.8125rem; }
.advisory { margin: 0.5rem 1rem; padding: 0.5rem 0.75rem; background: var(--color-warning-light); color: var(--color-warning-dark); border-radius: var(--radius-sm); font-size: 0.8125rem; line-height: 1.5; }
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
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 620px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-note { font-size: 0.75rem; color: var(--color-muted); margin: 0 0 1rem; line-height: 1.5; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid .span2 { grid-column: 1 / -1; }
.form-grid input, .form-grid select, .form-grid textarea { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-background); color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
@media (max-width: 768px) { .form-grid { grid-template-columns: 1fr; } }
</style>
