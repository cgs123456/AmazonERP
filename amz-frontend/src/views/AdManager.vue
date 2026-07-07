<template>
  <div class="ad-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>广告管理</h1>
        <p class="subtitle">广告活动、ACoS 监控与分时调价</p>
      </div>

      <!-- ACoS 概览 -->
      <div class="acos-overview">
        <div class="acos-card">
          <div class="acos-label">整体 ACoS</div>
          <div class="acos-value" :class="acosLevel">{{ acosData.totalAcos }}%</div>
          <div class="acos-desc">{{ acosLevelText }}</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">广告花费</div>
          <div class="acos-value">${{ acosData.totalSpend }}</div>
          <div class="acos-desc">近 7 天</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">广告销售额</div>
          <div class="acos-value">${{ acosData.totalSales }}</div>
          <div class="acos-desc">近 7 天</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">ROAS</div>
          <div class="acos-value">{{ acosData.roas }}x</div>
          <div class="acos-desc">投资回报率</div>
        </div>
      </div>

      <!-- ACoS 趋势 -->
      <div class="chart-card">
        <h3>近 14 天 ACoS 趋势</h3>
        <div class="line-chart">
          <svg viewBox="0 0 600 200" class="chart-svg">
            <polyline :points="acosTrendPoints" fill="none" stroke="#4f46e5" stroke-width="2" />
            <circle v-for="(pt, i) in acosTrendDots" :key="i" :cx="pt.x" :cy="pt.y" r="3" fill="#4f46e5" />
          </svg>
          <div class="chart-labels">
            <span v-for="(d, i) in acosTrend" :key="i">{{ d.day }}</span>
          </div>
        </div>
      </div>

      <!-- 活动列表 -->
      <div class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th>活动名称</th>
              <th>状态</th>
              <th>日预算</th>
              <th>花费</th>
              <th>销售额</th>
              <th>ACoS</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="camp in campaigns" :key="camp.id">
              <td>{{ camp.name }}</td>
              <td><span class="status-tag" :class="camp.active ? 'active' : 'paused'">{{ camp.active ? '运行中' : '已暂停' }}</span></td>
              <td>${{ camp.budget }}</td>
              <td>${{ camp.spend }}</td>
              <td>${{ camp.sales }}</td>
              <td :class="camp.acos > 50 ? 'acos-bad' : camp.acos > 35 ? 'acos-warn' : 'acos-good'">{{ camp.acos }}%</td>
              <td><button class="action-btn">调价</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'

const acosData = ref({ totalAcos: 24.9, totalSpend: '1,098.50', totalSales: '4,412.80', roas: '4.02' })

const acosLevel = computed(() => {
  const a = acosData.value.totalAcos
  if (a < 25) return 'good'
  if (a < 35) return 'warn'
  return 'bad'
})
const acosLevelText = computed(() => {
  const a = acosData.value.totalAcos
  if (a < 25) return '优秀 (<25%)'
  if (a < 35) return '健康 (25-35%)'
  return '预警 (>35%)'
})

const acosTrend = ref([
  { day: '7/1', value: 28 }, { day: '7/2', value: 26 }, { day: '7/3', value: 30 },
  { day: '7/4', value: 25 }, { day: '7/5', value: 23 }, { day: '7/6', value: 26 },
  { day: '7/7', value: 24.9 }
])

const acosTrendPoints = computed(() => {
  const max = 40, min = 15
  const w = 600, h = 180, pad = 10
  const step = (w - pad * 2) / (acosTrend.value.length - 1)
  return acosTrend.value.map((d, i) => {
    const x = pad + i * step
    const y = h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
    return `${x},${y}`
  }).join(' ')
})
const acosTrendDots = computed(() => {
  const max = 40, min = 15
  const w = 600, h = 180, pad = 10
  const step = (w - pad * 2) / (acosTrend.value.length - 1)
  return acosTrend.value.map((d, i) => ({
    x: pad + i * step,
    y: h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
  }))
})

const campaigns = ref([
  { id: 1, name: '关键词-蓝牙耳机-US', active: true, budget: 50, spend: 32.50, sales: 158.00, acos: 20.6 },
  { id: 2, name: '自动广告-全店铺', active: true, budget: 100, spend: 68.30, sales: 210.50, acos: 32.4 },
  { id: 3, name: '品牌广告-Shop B', active: false, budget: 30, spend: 12.00, sales: 28.50, acos: 42.1 },
  { id: 4, name: '商品推广-新品', active: true, budget: 40, spend: 15.70, sales: 89.20, acos: 17.6 }
])
</script>

<style scoped>
.ad-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.acos-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 24px; }
.acos-card { background: #fff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.acos-label { font-size: 13px; color: #666; }
.acos-value { font-size: 28px; font-weight: 700; color: #1a1a2e; margin: 4px 0; }
.acos-value.good { color: #10b981; }
.acos-value.warn { color: #f59e0b; }
.acos-value.bad { color: #ef4444; }
.acos-desc { font-size: 12px; color: #999; }

.chart-card { background: #fff; border-radius: 12px; padding: 20px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.chart-card h3 { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
.line-chart { width: 100%; }
.chart-svg { width: 100%; height: 200px; }
.chart-labels { display: flex; justify-content: space-between; margin-top: 8px; }
.chart-labels span { font-size: 11px; color: #999; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; }
.status-tag.active { background: #d1fae5; color: #065f46; }
.status-tag.paused { background: #f3f4f6; color: #6b7280; }
.acos-good { color: #10b981; font-weight: 600; }
.acos-warn { color: #f59e0b; font-weight: 600; }
.acos-bad { color: #ef4444; font-weight: 600; }
.action-btn { padding: 4px 12px; background: #4f46e5; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 12px; }
</style>
