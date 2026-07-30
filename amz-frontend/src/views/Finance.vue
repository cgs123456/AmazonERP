<template>
  <div class="finance-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>财务管理</h1>
        <p class="subtitle">业财一体化：凭证列表 / 利润查询 / 金蝶同步</p>
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

        <div v-if="loading" class="loading-mask">加载中...</div>

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
                <td colspan="8" class="empty-row">暂无凭证数据</td>
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

        <div v-if="profitLoading" class="loading-mask">加载中...</div>

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
.finance-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.shop-tip { padding: 12px 16px; margin-bottom: 16px; background: #fef3c7; color: #92400e; border-radius: 8px; font-size: 14px; text-align: center; }

.dim-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.dim-tab { padding: 8px 20px; border: 1px solid #e0e0e0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 14px; color: #666; }
.dim-tab.active { background: #4f46e5; color: #fff; border-color: #4f46e5; }

.filter-bar { display: flex; gap: 12px; align-items: center; margin-bottom: 20px; }
.filter-select, .filter-date { padding: 8px 12px; border: 1px solid #e0e0e0; border-radius: 8px; font-size: 14px; background: #fff; }
.filter-btn { padding: 8px 20px; background: #4f46e5; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; }
.filter-btn:hover { background: #4338ca; }
.range-sep { color: #666; font-size: 14px; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.mono { font-family: 'Courier New', monospace; font-size: 13px; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 500; }
.src-order { background: #d1fae5; color: #065f46; }
.src-proc { background: #fef3c7; color: #92400e; }
.src-fee { background: #dbeafe; color: #1e40af; }
.src-refund { background: #fee2e2; color: #991b1b; }
.src-other { background: #f3f4f6; color: #4b5563; }

.sync-pending { background: #f3f4f6; color: #4b5563; }
.sync-synced { background: #d1fae5; color: #065f46; }
.sync-syncing { background: #dbeafe; color: #1e40af; }
.sync-failed { background: #fee2e2; color: #991b1b; }

.sync-btn { padding: 4px 12px; border: 1px solid #4f46e5; border-radius: 6px; background: #fff; color: #4f46e5; cursor: pointer; font-size: 12px; }
.sync-btn:hover:not(:disabled) { background: #4f46e5; color: #fff; }
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.pagination { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; }
.page-info { font-size: 13px; color: #666; }
.page-actions { display: flex; gap: 8px; }
.page-btn { padding: 6px 16px; border: 1px solid #e0e0e0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 13px; color: #333; }
.page-btn:hover:not(:disabled) { background: #f9fafb; border-color: #4f46e5; color: #4f46e5; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20px; margin-bottom: 16px; }
.summary-card { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.summary-label { font-size: 13px; color: #666; }
.summary-value { font-size: 28px; font-weight: 700; color: #1a1a2e; margin-top: 4px; }
.summary-value.small { font-size: 16px; font-weight: 600; }

.profit-positive { color: #10b981; font-weight: 600; }
.profit-negative { color: #ef4444; font-weight: 600; }

.profit-note { padding: 12px 16px; background: #f9fafb; color: #6b7280; border-radius: 8px; font-size: 13px; line-height: 1.6; }
</style>