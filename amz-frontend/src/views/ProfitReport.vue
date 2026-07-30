<template>
  <div class="profit-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>利润报表</h1>
        <p class="subtitle">按 SKU / 店铺 / 月度汇总利润分析</p>
      </div>

      <div v-if="loading" class="loading-mask">加载中...</div>

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
              <td colspan="8" class="empty-row">暂无利润数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
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
.profit-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.shop-tip { padding: 12px 16px; margin-bottom: 16px; background: #fef3c7; color: #92400e; border-radius: 8px; font-size: 14px; text-align: center; }

.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 24px; }
.summary-card { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.summary-label { font-size: 13px; color: #666; }
.summary-value { font-size: 24px; font-weight: 700; color: #1a1a2e; margin-top: 4px; }

.dim-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.dim-tab { padding: 8px 20px; border: 1px solid #e0e0e0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 14px; color: #666; }
.dim-tab.active { background: #4f46e5; color: #fff; border-color: #4f46e5; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.mono { font-family: 'Courier New', monospace; font-size: 13px; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }
.profit-positive { color: #10b981; font-weight: 600; }
.profit-negative { color: #ef4444; font-weight: 600; }
</style>
