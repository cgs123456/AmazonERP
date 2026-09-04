<template>
  <div class="finance-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">财务管理</h1>
        <p class="hero-subtitle">业财一体化：凭证列表 / 利润查询 / 金蝶同步</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看财务数据。
      </div>

      <!-- 维度切换 -->
      <div class="dim-tabs">
        <button :class="['dim-tab', { active: tab === 'voucher' }]" @click="switchTab('voucher')">凭证列表</button>
        <button :class="['dim-tab', { active: tab === 'profit' }]" @click="switchTab('profit')">利润查询</button>
      </div>

      <!-- 凭证列表 -->
      <div v-show="tab === 'voucher'">
        <div class="filter-bar">
          <select v-model="filterSourceType" class="filter-select">
            <option value="">全部类型</option>
            <option value="ORDER">订单收入</option>
            <option value="PROCUREMENT">采购成本</option>
            <option value="PLATFORM_FEE">平台费用</option>
            <option value="REFUND">退款</option>
          </select>
          <button class="filter-btn" @click="loadVouchers">查询</button>
        </div>

        <div v-if="loading" class="skeleton-zone" aria-hidden="true">
          <div class="table-card sk-table-card">
            <div v-for="i in 5" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
          </div>
        </div>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>凭证号</th>
                <th>类型</th>
                <th>原币金额</th>
                <th>CNY 金额</th>
                <th>店铺</th>
                <th>金蝶状态</th>
                <th>业务日期</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in pagedVouchers" :key="v.id">
                <td class="mono">{{ v.voucherNo }}</td>
                <td><span class="status-tag" :class="sourceTypeClass(v.sourceType)">{{ sourceTypeText(v.sourceType) }}</span></td>
                <td>{{ v.originalAmount }} {{ v.currency }}</td>
                <td class="mono">¥{{ v.cnyAmount }}</td>
                <td>{{ v.shopId }}</td>
                <td><span class="status-tag" :class="syncStatusClass(v.kingdeeSyncStatus)">{{ v.kingdeeSyncStatus }}</span></td>
                <td class="mono">{{ v.bizDate }}</td>
                <td><button class="sync-btn" :disabled="syncing === v.id" @click="handleSync(v)">{{ syncing === v.id ? '同步中...' : '同步金蝶' }}</button></td>
              </tr>
              <tr v-if="!loading && vouchers.length === 0">
                <td colspan="8" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:book-open-outline" width="32" class="empty-icon" />
                    <span>暂无凭证数据</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pagination">
          <span class="page-info">第 {{ page }} 页 / 共 {{ totalPages }} 页（{{ vouchers.length }} 条）</span>
          <div class="page-actions">
            <button class="page-btn" :disabled="page <= 1" @click="prevPage">上一页</button>
            <button class="page-btn" :disabled="page >= totalPages" @click="nextPage">下一页</button>
          </div>
        </div>
      </div>

      <!-- 利润查询 -->
      <div v-show="tab === 'profit'">
        <div class="filter-bar">
          <input type="date" v-model="profitStart" class="filter-date" />
          <span class="range-sep">至</span>
          <input type="date" v-model="profitEnd" class="filter-date" />
          <button class="filter-btn" @click="loadProfit">查询利润</button>
        </div>

        <div v-if="profitLoading" class="skeleton-zone" aria-hidden="true">
          <div class="summary-grid">
            <div v-for="i in 2" :key="i" class="summary-card">
              <div class="skeleton sk-line sk-line-sm"></div>
              <div class="skeleton sk-line sk-line-lg"></div>
            </div>
          </div>
        </div>

        <div class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">店铺利润（CNY）</div>
            <div class="summary-value" :class="profitNum > 0 ? 'profit-positive' : 'profit-negative'">¥{{ profitDisplay }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">统计区间</div>
            <div class="summary-value small">{{ profitStart || '不限' }} ~ {{ profitEnd || '不限' }}</div>
          </div>
        </div>

        <div class="profit-note">
          利润 = 订单收入(ORDER) - 采购成本(PROCUREMENT) - 平台费用(PLATFORM_FEE) - 退款(REFUND)，按借贷方向汇总，单位 CNY。
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { listVouchers, syncToKingdee, calculateProfit } from '@/api/finance'
import type { AccountingVoucher } from '@/api/finance'
import { getCurrentShopId } from '@/utils/shop'

const tab = ref<'voucher' | 'profit'>('voucher')
const currentShopId = ref(getCurrentShopId())

// 凭证列表
const loading = ref(false)
const vouchers = ref<AccountingVoucher[]>([])
const filterSourceType = ref('')
const page = ref(1)
const size = ref(10)
const syncing = ref<number | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(vouchers.value.length / size.value)))
const pagedVouchers = computed(() => {
  const start = (page.value - 1) * size.value
  return vouchers.value.slice(start, start + size.value)
})

const switchTab = (t: 'voucher' | 'profit') => {
  tab.value = t
}

const loadVouchers = async () => {
  const shopId = currentShopId.value
  if (!shopId) {
    vouchers.value = []
    return
  }
  loading.value = true
  page.value = 1
  try {
    const res = await listVouchers(shopId, filterSourceType.value || undefined)
    if (res?.code === 200 && res.data) {
      vouchers.value = Array.isArray(res.data) ? res.data : []
    } else {
      console.warn('[Finance] 凭证列表返回异常', res)
      vouchers.value = []
    }
  } catch (e) {
    console.warn('[Finance] 凭证列表调用失败', e)
    vouchers.value = []
  } finally {
    loading.value = false
  }
}

const handleSync = async (v: AccountingVoucher) => {
  syncing.value = v.id
  try {
    const res = await syncToKingdee(v.id)
    if (res?.code === 200 && res.data === true) {
      // 同步成功后刷新列表以获取最新状态
      await loadVouchers()
    } else {
      console.warn('[Finance] 金蝶同步返回异常', res)
    }
  } catch (e) {
    console.warn('[Finance] 金蝶同步调用失败', e)
  } finally {
    syncing.value = null
  }
}

const prevPage = () => {
  if (page.value > 1) page.value--
}
const nextPage = () => {
  if (page.value < totalPages.value) page.value++
}

// 利润查询
const profitLoading = ref(false)
const profitStart = ref('')
const profitEnd = ref('')
const profitNum = ref<number>(0)
const profitDisplay = computed(() => {
  const n = profitNum.value
  return typeof n === 'number' && !isNaN(n) ? n.toFixed(2) : '0.00'
})

const loadProfit = async () => {
  const shopId = currentShopId.value
  if (!shopId) {
    profitNum.value = 0
    return
  }
  profitLoading.value = true
  try {
    const res = await calculateProfit(shopId, profitStart.value || undefined, profitEnd.value || undefined)
    if (res?.code === 200 && res.data !== null && res.data !== undefined) {
      const raw = res.data
      const num = typeof raw === 'number' ? raw : parseFloat(String(raw))
      profitNum.value = isNaN(num) ? 0 : num
    } else {
      console.warn('[Finance] 利润查询返回异常', res)
      profitNum.value = 0
    }
  } catch (e) {
    console.warn('[Finance] 利润查询调用失败', e)
    profitNum.value = 0
  } finally {
    profitLoading.value = false
  }
}

const sourceTypeText = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: '订单收入',
    PROCUREMENT: '采购成本',
    PLATFORM_FEE: '平台费用',
    REFUND: '退款'
  }
  return map[t] || t
}

const sourceTypeClass = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: 'src-order',
    PROCUREMENT: 'src-proc',
    PLATFORM_FEE: 'src-fee',
    REFUND: 'src-refund'
  }
  return map[t] || 'src-other'
}

const syncStatusClass = (s: string): string => {
  const map: Record<string, string> = {
    PENDING: 'sync-pending',
    SYNCED: 'sync-synced',
    SYNCING: 'sync-syncing',
    FAILED: 'sync-failed'
  }
  return map[s] || 'sync-pending'
}

onMounted(() => {
  loadVouchers()
})
</script>

<style scoped>
.finance-page { background: var(--color-background); }
/* 页头/主区/表格等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 60%; height: 1.5rem; margin-top: 0.5rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.dim-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.dim-tab { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; font-size: 0.875rem; color: var(--color-muted); transition: all 0.2s; }
.dim-tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }

.filter-bar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-select, .filter-date { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover { border-color: var(--color-primary); }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.range-sep { color: var(--color-muted); font-size: 0.875rem; margin: 0 0.5rem; }

.src-order { background: var(--color-primary-light); color: var(--color-primary); }
.src-proc { background: var(--color-muted-light); color: var(--color-muted); }
.src-fee { background: var(--color-muted-light); color: var(--color-muted); }
.src-refund { background: var(--color-light-red); color: var(--color-error); }
.src-other { background: var(--color-surface); color: var(--color-muted); }

/* 同步状态语义色：SYNCED=成功 / PENDING=待处理 / FAILED=失败 */
.sync-pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-synced { background: var(--color-success-light); color: var(--color-success); }
.sync-syncing { background: var(--color-primary-light); color: var(--color-primary); }
.sync-failed { background: var(--color-light-red); color: var(--color-error); }

.sync-btn { padding: 0.25rem 0.75rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; }
.sync-btn:hover:not(:disabled) { background: var(--color-primary-dark); }
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: 0.8125rem; color: var(--color-muted); }
.summary-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }
.summary-value.small { font-size: 1rem; font-weight: 600; }

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }
.profit-note { padding: 0.75rem 1rem; background: var(--color-surface); color: var(--color-muted); border-radius: var(--radius-md); font-size: 0.8125rem; line-height: 1.6; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .summary-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .filter-bar { flex-wrap: wrap; } .summary-grid { grid-template-columns: 1fr; } }
</style>