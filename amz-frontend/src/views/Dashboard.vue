<template>
  <div class="dashboard-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>运营总览</h1>
        <p class="subtitle">今日运营数据一览</p>
      </div>

      <!-- KPI 卡片 -->
      <div class="kpi-grid">
        <div class="kpi-card" v-for="kpi in kpiData" :key="kpi.label">
          <div class="kpi-icon" :style="{ background: kpi.color }">
            <Icon :icon="kpi.icon" width="24" color="#fff" />
          </div>
          <div class="kpi-info">
            <div class="kpi-value">{{ kpi.value }}</div>
            <div class="kpi-label">{{ kpi.label }}</div>
            <div class="kpi-trend" :class="kpi.trend > 0 ? 'up' : 'down'">
              <Icon :icon="kpi.trend > 0 ? 'mdi:trending-up' : 'mdi:trending-down'" width="14" />
              {{ Math.abs(kpi.trend) }}% 较昨日
            </div>
          </div>
        </div>
      </div>

      <!-- 趋势图区域 -->
      <div class="chart-row">
        <div class="chart-card">
          <h3>近 7 天销售趋势</h3>
          <div class="bar-chart">
            <div class="bar-item" v-for="item in salesTrend" :key="item.day">
              <div class="bar" :style="{ height: (item.value / maxSales * 100) + '%' }"></div>
              <span class="bar-label">{{ item.day }}</span>
              <span class="bar-value">${{ item.value }}</span>
            </div>
          </div>
        </div>
        <div class="chart-card">
          <h3>店铺销售占比</h3>
          <div class="pie-chart">
            <div class="pie-item" v-for="item in shopDist" :key="item.name">
              <div class="pie-color" :style="{ background: item.color }"></div>
              <span class="pie-name">{{ item.name }}</span>
              <span class="pie-value">{{ item.percent }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Agent 快捷入口 -->
      <div class="agent-entry">
        <div class="agent-card" @click="agentVisible = true">
          <Icon icon="mdi:robot" width="32" color="#4f46e5" />
          <div>
            <div class="agent-title">运营助手</div>
            <div class="agent-desc">向 AI 提问运营问题，获取智能建议</div>
          </div>
          <Icon icon="mdi:chevron-right" width="24" color="#999" />
        </div>
      </div>
    </main>

    <!-- 运营助手 AI 聊天浮窗 -->
    <AgentChat v-model:visible="agentVisible" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AgentChat from '../components/AgentChat.vue'

const agentVisible = ref(false)

const kpiData = ref([
  { label: '今日订单', value: '23', trend: 15, icon: 'mdi:cart', color: '#4f46e5' },
  { label: '销售额', value: '$1,234', trend: 12, icon: 'mdi:currency-usd', color: '#10b981' },
  { label: '库存预警', value: '3', trend: -8, icon: 'mdi:alert', color: '#ef4444' },
  { label: '广告 ACoS', value: '24.9%', trend: -3, icon: 'mdi:chart-line', color: '#f59e0b' }
])

const salesTrend = ref([
  { day: '周一', value: 980 },
  { day: '周二', value: 1120 },
  { day: '周三', value: 1050 },
  { day: '周四', value: 1340 },
  { day: '周五', value: 1180 },
  { day: '周六', value: 1420 },
  { day: '周日', value: 1234 }
])
const maxSales = Math.max(...salesTrend.value.map(i => i.value))

const shopDist = ref([
  { name: 'Shop A (US)', percent: 45, color: '#4f46e5' },
  { name: 'Shop B (UK)', percent: 30, color: '#10b981' },
  { name: 'Shop C (DE)', percent: 15, color: '#f59e0b' },
  { name: 'Shop D (JP)', percent: 10, color: '#ef4444' }
])
</script>

<style scoped>
.dashboard-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 24px; }
.kpi-card { background: #fff; border-radius: 12px; padding: 20px; display: flex; gap: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.kpi-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kpi-value { font-size: 28px; font-weight: 700; color: #1a1a2e; }
.kpi-label { font-size: 13px; color: #666; margin-top: 2px; }
.kpi-trend { font-size: 12px; margin-top: 4px; display: flex; align-items: center; gap: 2px; }
.kpi-trend.up { color: #10b981; }
.kpi-trend.down { color: #ef4444; }

.chart-row { display: grid; grid-template-columns: 2fr 1fr; gap: 20px; margin-bottom: 24px; }
.chart-card { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.chart-card h3 { font-size: 16px; font-weight: 600; margin: 0 0 20px; color: #1a1a2e; }

.bar-chart { display: flex; align-items: flex-end; gap: 16px; height: 200px; padding-bottom: 30px; }
.bar-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; position: relative; }
.bar { width: 100%; max-width: 40px; background: linear-gradient(180deg, #6366f1, #4f46e5); border-radius: 6px 6px 0 0; transition: height 0.5s; min-height: 4px; }
.bar-label { font-size: 12px; color: #666; position: absolute; bottom: -20px; }
.bar-value { font-size: 11px; color: #4f46e5; font-weight: 600; }

.pie-chart { display: flex; flex-direction: column; gap: 12px; }
.pie-item { display: flex; align-items: center; gap: 8px; }
.pie-color { width: 12px; height: 12px; border-radius: 3px; }
.pie-name { flex: 1; font-size: 14px; color: #333; }
.pie-value { font-size: 14px; font-weight: 600; color: #1a1a2e; }

.agent-entry { margin-top: 8px; }
.agent-card { background: #fff; border-radius: 12px; padding: 20px; display: flex; align-items: center; gap: 16px; cursor: pointer; box-shadow: 0 2px 8px rgba(0,0,0,0.04); transition: transform 0.2s; }
.agent-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(79,70,229,0.12); }
.agent-title { font-size: 16px; font-weight: 600; color: #1a1a2e; }
.agent-desc { font-size: 13px; color: #666; margin-top: 2px; }
</style>
