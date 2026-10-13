<template>
  <div class="return-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">退货管理</h1>
        <p class="hero-subtitle">买家退货入仓 · 处置判定 · 良品回补可售库存</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺后再管理退货单。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四条判读边界：① 退款金额在财务域（结算报表），本页只记<b>实物退回</b>——
        亚马逊存在「退款不退货」，两者不是一对一，因此退款金额可为空；
        ② 收货时确定处置：只有<b>良品（GOOD）</b>回补可售库存并写库存流水，
        残次/报废虽收到货但<b>不进可售库存</b>（否则补货建议会虚高）；
        ③ <b>「从 Amazon 同步」只覆盖 FBA</b>——SP-API 没有卖家自发货（FBM）退货报表，
        FBM 退货只能手工登记；同步是幂等的（按退货日期+订单+SKU 去重），
        报表未就绪会明确报错而不是当成「本期无退货」；
        ④ <b>收货一律人工确认，不按报表状态自动收货</b>——Amazon 报表里的
        「货已回 Amazon FC 库存」不等于「货已回到你的本地仓」，此时若自动回补可售库存，
        系统会显示有货而实际未到，直接造成超卖。
      </div>

      <template v-if="currentShopId">
        <!-- 从 Amazon 同步（FBA 客户退货报表） -->
        <div class="table-card">
          <div class="filter-row">
            <label class="filter">站点 marketplaceId
              <input v-model="syncMarketplaceId" placeholder="ATVPDKIKX0DER" />
            </label>
            <label class="filter">回溯天数
              <input v-model="syncWindowDays" type="number" min="1" style="width: 5rem" />
            </label>
            <button class="action-btn" :disabled="syncing" @click="syncFromAmazon">
              {{ syncing ? '同步中…' : '从 Amazon 同步（FBA）' }}
            </button>
            <span class="muted">仅 FBA；幂等，可重复点</span>
          </div>
          <p v-if="syncMsg" class="sync-msg" role="status">{{ syncMsg }}</p>
        </div>

        <!-- 新建 -->
        <div class="table-card">
          <div class="filter-row">
            <label class="filter">SKU<input v-model="form.sku" placeholder="必填" /></label>
            <label class="filter">数量<input v-model="form.quantity" type="number" min="1" /></label>
            <label class="filter">Amazon 订单号<input v-model="form.amazonOrderId" placeholder="可空（退款不退货）" /></label>
            <label class="filter">来源
              <select v-model="form.source">
                <option value="MANUAL">手工录入</option>
                <option value="AMAZON_FBA">Amazon FBA</option>
                <option value="AMAZON_FBM">Amazon 自发货</option>
              </select>
            </label>
            <label class="filter">退货原因<input v-model="form.returnReason" placeholder="可空" /></label>
            <button class="action-btn" :disabled="creating" @click="submitCreate">
              {{ creating ? '提交中…' : '登记退货单' }}
            </button>
          </div>
        </div>

        <!-- 列表 -->
        <div class="table-card">
          <div class="filter-row">
            <label class="filter">状态
              <select v-model="statusFilter" @change="load()">
                <option value="">全部</option>
                <option v-for="(label, key) in RETURN_STATUS_LABELS" :key="key" :value="key">{{ label }}</option>
              </select>
            </label>
            <button class="action-btn" :disabled="loading" @click="load()">刷新</button>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>退货单号</th><th>SKU</th><th>数量</th><th>Amazon 订单号</th>
                <th>来源</th><th>处置</th><th>状态</th><th>收货时间</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td class="mono">{{ row.returnNo }}</td>
                <td class="mono">{{ row.sku }}</td>
                <td>{{ row.quantity }}</td>
                <td class="mono">{{ row.amazonOrderId || '—' }}</td>
                <td>{{ RETURN_SOURCE_LABELS[row.source || ''] || row.source || '—' }}</td>
                <td>{{ DISPOSITION_LABELS[row.disposition || ''] || '—' }}</td>
                <td><span class="status-tag" :class="statusClass(row.status)">{{ RETURN_STATUS_LABELS[row.status || ''] || row.status }}</span></td>
                <td class="mono">{{ (row.receivedAt || '').slice(0, 16).replace('T', ' ') || '—' }}</td>
                <td>
                  <div class="row-actions">
                    <button v-if="row.status === 'PENDING'" class="row-btn primary" @click="openReceive(row)">收货</button>
                    <button v-if="row.status === 'RECEIVED'" class="row-btn" @click="doClose(row)">结案</button>
                    <button v-if="row.status === 'PENDING'" class="row-btn" @click="doCancel(row)">取消</button>
                    <span v-if="row.status === 'CLOSED' || row.status === 'CANCELLED'" class="muted">—</span>
                  </div>
                </td>
              </tr>
              <tr v-if="!loading && !rows.length">
                <td colspan="9" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:package-variant-remove" width="32" class="empty-icon" />
                    <span>暂无退货单</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </main>

    <!-- 收货对话框 -->
    <div v-if="receiveTarget" class="dialog-mask" @click.self="receiveTarget = null">
      <div class="dialog" role="dialog" aria-modal="true" aria-label="退货收货">
        <h3 class="dialog-title">退货收货</h3>
        <p class="dialog-sub">退货单 {{ receiveTarget.returnNo }}（{{ receiveTarget.quantity }} 件）。
          处置选 <b>良品</b> 才会回补可售库存并写库存流水；残次/报废只留流水。</p>
        <div class="dialog-field">
          <label>收货仓库 ID *</label>
          <input v-model="receiveForm.warehouseId" class="dialog-input" placeholder="仓库 ID" />
        </div>
        <div class="dialog-field">
          <label>处置 *</label>
          <select v-model="receiveForm.disposition" class="dialog-input">
            <option value="GOOD">良品（回补可售库存）</option>
            <option value="DEFECTIVE">残次（不回补）</option>
            <option value="SCRAP">报废（不回补）</option>
          </select>
        </div>
        <p v-if="receiveError" class="dialog-error">{{ receiveError }}</p>
        <div class="dialog-actions">
          <button class="row-btn" :disabled="receiving" @click="receiveTarget = null">取消</button>
          <button class="row-btn primary" :disabled="receiving || !receiveForm.warehouseId" @click="submitReceive">
            {{ receiving ? '提交中…' : '确认收货' }}
          </button>
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
import {
  listReturnOrders,
  createReturnOrder,
  receiveReturnOrder,
  closeReturnOrder,
  cancelReturnOrder,
  syncReturnsFromAmazon,
  RETURN_STATUS_LABELS,
  DISPOSITION_LABELS,
  RETURN_SOURCE_LABELS,
  type ReturnOrderRow
} from '@/api/returnOrder'
import { useShopGuard } from '@/composables/useShopGuard'

const { currentShopId, refreshShop } = useShopGuard()

const rows = ref<ReturnOrderRow[]>([])
const loading = ref(false)
const creating = ref(false)
const statusFilter = ref('')
const errors = ref<string[]>([])

/* ===== 从 Amazon 同步（FBA 客户退货报表） ===== */
const syncing = ref(false)
const syncMsg = ref('')
const syncMarketplaceId = ref('ATVPDKIKX0DER')
const syncWindowDays = ref(7)

const syncFromAmazon = async () => {
  const shopId = refreshShop() || currentShopId.value
  if (!shopId) return
  if (!syncMarketplaceId.value.trim()) {
    pushError('同步退货：站点 marketplaceId 必填')
    return
  }
  syncing.value = true
  syncMsg.value = ''
  try {
    const res = await syncReturnsFromAmazon(
      shopId,
      syncMarketplaceId.value.trim(),
      Number(syncWindowDays.value) || 7
    )
    if (res?.code === 200) {
      const d = res.data || {}
      syncMsg.value = `同步完成：拉取 ${d.fetched ?? 0} 行，新导入 ${d.imported ?? 0}，跳过（已存在）${d.skipped ?? 0}，行错误 ${d.rowErrors ?? 0}`
      await load()
    } else {
      pushError(`同步退货：${res?.message || '后端返回非 200'}`)
    }
  } catch (e) {
    pushError(`同步退货：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    syncing.value = false
  }
}

const form = ref({ sku: '', quantity: 1, amazonOrderId: '', source: 'MANUAL', returnReason: '' })

const receiveTarget = ref<ReturnOrderRow | null>(null)
const receiveError = ref('')
const receiving = ref(false)
const receiveForm = ref({ warehouseId: '', disposition: 'GOOD' })

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const statusClass = (status?: string): string => {
  switch (status) {
    case 'PENDING':
      return 'pending'
    case 'RECEIVED':
      return 'completed'
    case 'CLOSED':
      return 'shipped'
    case 'CANCELLED':
      return 'refunded'
    default:
      return 'pending'
  }
}

const load = async () => {
  const shopId = refreshShop() || currentShopId.value
  if (!shopId) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const res = await listReturnOrders(shopId, statusFilter.value || undefined)
    if (res?.code === 200 && Array.isArray(res.data)) {
      rows.value = res.data
      errors.value = []
    } else {
      pushError(`加载退货单：${res?.message || '后端返回非 200'}`)
      rows.value = []
    }
  } catch (e) {
    pushError(`加载退货单：${e instanceof Error ? e.message : '调用失败'}`)
    rows.value = []
  } finally {
    loading.value = false
  }
}

const submitCreate = async () => {
  const shopId = refreshShop() || currentShopId.value
  if (!shopId) return
  if (!form.value.sku.trim()) {
    pushError('登记退货单：SKU 必填')
    return
  }
  const qty = Number(form.value.quantity)
  if (!Number.isFinite(qty) || qty <= 0) {
    pushError('登记退货单：数量必须是正整数')
    return
  }
  creating.value = true
  try {
    const res = await createReturnOrder({
      shopId,
      sku: form.value.sku.trim(),
      quantity: qty,
      amazonOrderId: form.value.amazonOrderId.trim() || undefined,
      source: form.value.source,
      returnReason: form.value.returnReason.trim() || undefined
    })
    if (res?.code === 200) {
      form.value = { sku: '', quantity: 1, amazonOrderId: '', source: 'MANUAL', returnReason: '' }
      await load()
    } else {
      pushError(`登记退货单：${res?.message || '后端返回非 200'}`)
    }
  } catch (e) {
    pushError(`登记退货单：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    creating.value = false
  }
}

const openReceive = (row: ReturnOrderRow) => {
  receiveTarget.value = row
  receiveError.value = ''
  receiveForm.value = { warehouseId: '', disposition: 'GOOD' }
}

const submitReceive = async () => {
  const target = receiveTarget.value
  if (!target?.id || !receiveForm.value.warehouseId) return
  receiving.value = true
  receiveError.value = ''
  try {
    const res = await receiveReturnOrder(target.id, receiveForm.value.warehouseId, receiveForm.value.disposition)
    if (res?.code === 200) {
      receiveTarget.value = null
      await load()
    } else {
      receiveError.value = res?.message || '收货失败'
    }
  } catch (e) {
    receiveError.value = e instanceof Error ? e.message : '调用失败'
  } finally {
    receiving.value = false
  }
}

const doClose = async (row: ReturnOrderRow) => {
  if (!row.id) return
  try {
    const res = await closeReturnOrder(row.id)
    if (res?.code === 200) await load()
    else pushError(`结案：${res?.message || '后端返回非 200'}`)
  } catch (e) {
    pushError(`结案：${e instanceof Error ? e.message : '调用失败'}`)
  }
}

const doCancel = async (row: ReturnOrderRow) => {
  if (!row.id) return
  try {
    const res = await cancelReturnOrder(row.id)
    if (res?.code === 200) await load()
    else pushError(`取消：${res?.message || '后端返回非 200'}`)
  } catch (e) {
    pushError(`取消：${e instanceof Error ? e.message : '调用失败'}`)
  }
}

onMounted(load)
</script>

<style scoped>
.return-page { background: var(--color-background); }
.error-zone { display: flex; align-items: center; gap: 0.5rem; border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; background: var(--color-light-red); color: var(--color-error); }
.filter-row { display: flex; gap: 0.75rem; flex-wrap: wrap; align-items: center; padding: 0.75rem 1rem; }
.filter { display: flex; align-items: center; gap: 0.375rem; font-size: 0.8125rem; color: var(--color-on-surface-muted); }
.filter input, .filter select { padding: 0.375rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.action-btn { padding: 0.375rem 0.875rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.8125rem; cursor: pointer; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.row-actions { display: flex; gap: 0.375rem; white-space: nowrap; }
.row-btn { padding: 0.25rem 0.625rem; font-size: 0.8125rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; }
.row-btn.primary { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.row-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.muted { color: var(--color-on-surface-muted); }
.sync-msg { margin: 0; padding: 0.5rem 1rem 0.75rem; font-size: 0.8125rem; color: var(--color-success); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.completed { background: var(--color-success-light); color: var(--color-success); }
.status-tag.shipped { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.refunded { background: var(--color-light-red); color: var(--color-error); }
.dialog-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.dialog { background: var(--color-surface); border-radius: var(--radius-lg); padding: 1.25rem; width: min(440px, 92vw); box-shadow: var(--shadow-lg); }
.dialog-title { margin: 0 0 0.375rem; font-size: 1.0625rem; }
.dialog-sub { margin: 0 0 1rem; font-size: 0.8125rem; color: var(--color-on-surface-muted); line-height: 1.5; }
.dialog-field { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 0.75rem; }
.dialog-field label { font-size: 0.8125rem; color: var(--color-on-surface-muted); }
.dialog-input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); }
.dialog-error { margin: 0 0 0.75rem; font-size: 0.8125rem; color: var(--color-error); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 0.5rem; }
</style>
