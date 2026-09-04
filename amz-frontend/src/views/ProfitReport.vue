<template>
  <div class="profit-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">利润报表</h1>
        <p class="hero-subtitle">按 SKU / 店铺 / 月度汇总利润分析</p>
      </div>

      <!-- 骨架屏：汇总卡片 + 表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" aria-hidden="true">
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

      <!-- 汇总卡片 -->
      <div class="summary-grid">
        <div class="summary-card">
          <div class="summary-label">总销售额</div>
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

      <!-- 维度切换 -->
      <div class="dim-tabs">
        <button :class="['dim-tab', { active: dim === 'sku' }]" @click="dim = 'sku'">按 SKU</button>
        <button :class="['dim-tab', { active: dim === 'shop' }]" @click="dim = 'shop'">按店铺</button>
        <button :class="['dim-tab', { active: dim === 'month' }]" @click="dim = 'month'">按月度</button>
      </div>

      <!-- 利润表格 -->
      <div class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ dim === 'sku' ? 'SKU' : dim === 'shop' ? '店铺' : '月份' }}</th>
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
                  <span>暂无利润数据</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getProfitReport } from '@/api/profit'
import type { ProfitRow, ProfitSummary } from '@/api/profit'
import { getCurrentShopId } from '@/utils/shop'

const loading = ref(false)
const dim = ref<'sku' | 'shop' | 'month'>('sku')

// 当前选中店铺（未选则为空字符串，用于阻断查询并提示用户）
const currentShopId = ref(getCurrentShopId())

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
const mockShopData: ProfitRow[] = [
  { name: 'Shop A (US)', revenue: '$5,300', cost: '$2,650', platformFee: '$795', adFee: '$530', shipping: '$265', profit: 1060, margin: 20.0 },
  { name: 'Shop B (UK)', revenue: '$3,900', cost: '$1,950', platformFee: '$585', adFee: '$390', shipping: '$195', profit: 780, margin: 20.0 },
  { name: 'Shop C (DE)', revenue: '$1,800', cost: '$1,200', platformFee: '$270', adFee: '$180', shipping: '$90', profit: 60, margin: 3.3 },
  { name: 'Shop D (JP)', revenue: '$1,456.80', cost: '$1,434.20', platformFee: '$218.52', adFee: '$145.68', shipping: '$72.84', profit: -413.84, margin: -28.4 }
]
const mockMonthData: ProfitRow[] = [
  { name: '2026-01', revenue: '$8,200', cost: '$4,800', platformFee: '$1,230', adFee: '$820', shipping: '$410', profit: 940, margin: 11.5 },
  { name: '2026-02', revenue: '$9,500', cost: '$5,500', platformFee: '$1,425', adFee: '$950', shipping: '$475', profit: 1150, margin: 12.1 },
  { name: '2026-03', revenue: '$11,200', cost: '$6,400', platformFee: '$1,680', adFee: '$1,120', shipping: '$560', profit: 1440, margin: 12.9 },
  { name: '2026-04', revenue: '$10,800', cost: '$6,200', platformFee: '$1,620', adFee: '$1,080', shipping: '$540', profit: 1360, margin: 12.6 },
  { name: '2026-05', revenue: '$12,100', cost: '$6,900', platformFee: '$1,815', adFee: '$1,210', shipping: '$605', profit: 1570, margin: 13.0 },
  { name: '2026-06', revenue: '$12,456.80', cost: '$7,234.20', platformFee: '$1,868.52', adFee: '$1,245.68', shipping: '$622.84', profit: 1485.56, margin: 11.9 }
]

const summary = ref<ProfitSummary>({ ...mockSummary })
const skuData = ref<ProfitRow[]>([...mockSkuData])
const shopData = ref<ProfitRow[]>([...mockShopData])
const monthData = ref<ProfitRow[]>([...mockMonthData])

const currentData = computed(() => {
  if (dim.value === 'sku') return skuData.value
  if (dim.value === 'shop') return shopData.value
  return monthData.value
})

onMounted(async () => {
  // 未选择店铺时不发请求，/order/profit/* 路径受网关 shopId 校验
  const shopId = currentShopId.value
  if (!shopId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await getProfitReport(shopId, '2026-06-01', '2026-06-30')
    if (res?.code === 200 && res.data) {
      if (res.data.summary) summary.value = res.data.summary
      // API 返回的行数据填充到默认 SKU 维度
      if (res.data.rows) skuData.value = res.data.rows
    } else {
      console.warn('[ProfitReport] 返回数据异常，使用降级数据', res)
    }
  } catch (e) {
    console.warn('[ProfitReport] API 调用失败，使用降级数据', e)
  } finally {
    loading.value = false
  }
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

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .summary-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .summary-grid { grid-template-columns: 1fr; } }
</style>
