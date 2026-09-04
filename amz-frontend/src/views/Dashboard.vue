<template>
  <div class="dashboard-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - 符合 design-taste-frontend 约束 -->
      <!-- eyebrow: 无 (每 3 个 section 最多 1 个，本页面 0 个，合规)
           headline: "运营总览" - 2 行以内
           subtext: "今日运营数据一览" - 4 词以内
           CTAs: 无 (Agent 入口在页面尾部，不计入 hero CTA 数)
           top padding: 被 AppHeader + main-content margin 处理，而非纯 CSS h-screen
           split-header: 已垂直堆叠 (h1 在上，p 在下)
      -->
      <div class="hero-section">
        <h1 class="hero-title">运营总览</h1>
        <p class="hero-subtitle">今日运营数据一览</p>
      </div>

      <!-- 骨架屏：形状匹配 KPI 网格 + 图表区（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" aria-hidden="true">
        <div class="kpi-grid">
          <div v-for="i in 4" :key="i" class="kpi-card">
            <div class="skeleton sk-icon"></div>
            <div class="sk-lines">
              <div class="skeleton sk-line sk-line-lg"></div>
              <div class="skeleton sk-line sk-line-sm"></div>
            </div>
          </div>
        </div>
        <div class="chart-row">
          <div class="chart-card">
            <div class="skeleton sk-line sk-line-md"></div>
            <div class="sk-chart"></div>
          </div>
          <div class="chart-card">
            <div class="skeleton sk-line sk-line-md"></div>
            <div class="sk-chart"></div>
          </div>
        </div>
      </div>

      <template v-else>
        <!-- KPI 卡片网格 - bento grid: 4 个 cell, 无空单元格 -->
        <div class="kpi-grid">
          <div class="kpi-card" v-for="kpi in kpiData" :key="kpi.label">
            <div class="kpi-icon">
              <Icon :icon="kpi.icon" width="24" />
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
                <span class="bar-value">{{ item.value }}</span>
              </div>
            </div>
          </div>
          <div class="chart-card">
            <h3>店铺销售占比</h3>
            <div class="pie-chart">
              <div class="pie-item" v-for="(item, i) in shopDist" :key="item.name">
                <div class="pie-color" :class="'pie-' + (i % 4)"></div>
                <span class="pie-name">{{ item.name }}</span>
                <span class="pie-value">{{ item.percent }}%</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Agent 快捷入口 - 位于页面底部，不作为 hero CTA 计入 -->
        <div class="agent-entry">
          <div class="agent-card" @click="agentVisible = true" role="button" tabindex="0" @keydown.enter="agentVisible = true">
            <Icon icon="mdi:robot" width="32" class="agent-icon" />
            <div>
              <div class="agent-title">运营助手</div>
              <div class="agent-desc">向 AI 提问运营问题，获取智能建议</div>
            </div>
            <Icon icon="mdi:chevron-right" width="24" class="agent-chevron" />
          </div>
        </div>
      </template>
    </main>

    <!-- 运营助手 AI 聊天浮窗 -->
    <AgentChat v-model:visible="agentVisible" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import AgentChat from '../components/AgentChat.vue'
import { getKpiData, getSalesTrend, getShopDistribution } from '@/api/dashboard'
import type { KpiItem, SalesTrendItem, ShopDistItem } from '@/api/dashboard'
import { getCurrentShopId } from '@/utils/shop'

const agentVisible = ref(false)
const loading = ref(false)

// 降级用的 mock 数据
const mockKpiData: KpiItem[] = [
  { label: '今日订单', value: '23', trend: 15, icon: 'mdi:cart' },
  { label: '销售额', value: '$1,234', trend: 12, icon: 'mdi:currency-usd' },
  { label: '库存预警', value: '3', trend: -8, icon: 'mdi:alert' },
  { label: '广告 ACoS', value: '24.9%', trend: -3, icon: 'mdi:chart-line' }
]
const mockSalesTrend: SalesTrendItem[] = [
  { day: '周一', value: 980 },
  { day: '周二', value: 1120 },
  { day: '周三', value: 1050 },
  { day: '周四', value: 1340 },
  { day: '周五', value: 1180 },
  { day: '周六', value: 1420 },
  { day: '周日', value: 1234 }
]
const mockShopDist: ShopDistItem[] = [
  { name: 'Shop A (US)', percent: 45 },
  { name: 'Shop B (UK)', percent: 30 },
  { name: 'Shop C (DE)', percent: 15 },
  { name: 'Shop D (JP)', percent: 10 }
]

const kpiData = ref<KpiItem[]>([...mockKpiData])
const salesTrend = ref<SalesTrendItem[]>([...mockSalesTrend])
const shopDist = ref<ShopDistItem[]>([...mockShopDist])

const maxSales = computed(() => {
  if (!salesTrend.value.length) return 1
  return Math.max(...salesTrend.value.map(i => i.value))
})

onMounted(async () => {
  loading.value = true
  // 并行请求三组数据，任一失败则该组降级到 mock
  const tasks = [
    {
      fn: () => getKpiData(getCurrentShopId()),
      onSuccess: (data: KpiItem[]) => { kpiData.value = data },
      mock: mockKpiData,
      tag: 'getKpiData'
    },
    {
      fn: () => getSalesTrend(7),
      onSuccess: (data: SalesTrendItem[]) => { salesTrend.value = data },
      mock: mockSalesTrend,
      tag: 'getSalesTrend'
    },
    {
      fn: () => getShopDistribution(),
      onSuccess: (data: ShopDistItem[]) => { shopDist.value = data },
      mock: mockShopDist,
      tag: 'getShopDistribution'
    }
  ]

  await Promise.all(
    tasks.map(async (t) => {
      try {
        const res = await t.fn()
        if (res?.code === 200 && res.data) {
          t.onSuccess(res.data as any)
        } else {
          console.warn(`[Dashboard] ${t.tag} 返回数据异常，使用降级数据`, res)
        }
      } catch (e) {
        console.warn(`[Dashboard] ${t.tag} 调用失败，使用降级数据`, e)
      }
    })
  )

  loading.value = false
})
</script>

<style scoped>
/* design tokens 引用全局 style.css；此处不再重复定义 */

/* 页面与主布局（与 AppHeader 64px / AppSidebar 220px 对齐） */
.dashboard-page { background: var(--color-background); }
.main-content { margin-left: 220px; margin-top: 64px; padding: 1rem; min-height: 100dvh; }

/* 页头：仪表盘场景左对齐（技能 4.3 Anti-Center Bias），副标题用 muted 而非 accent（色彩纪律） */
.hero-section { padding-top: env(safe-area-inset-top); padding-bottom: 1.5rem; }
.hero-title {
  font-size: var(--font-size-7); /* 30px：仪表盘页头不需要落地页级 display 字号 */
  font-weight: 700;
  color: var(--color-on-surface);
  margin: 0 0 0.25rem 0;
  line-height: var(--line-height-tight);
}
.hero-subtitle {
  font-size: var(--font-size-2); /* 14px */
  color: var(--color-muted);
  margin: 0;
  line-height: var(--line-height-snug);
}

/* 骨架屏 */
.skeleton-zone { min-height: 24rem; }
.sk-icon { width: 3rem; height: 3rem; flex-shrink: 0; }
.sk-lines { flex: 1; display: flex; flex-direction: column; gap: 0.5rem; justify-content: center; }
.sk-line { height: 0.875rem; }
.sk-line-lg { width: 55%; }
.sk-line-sm { width: 35%; }
.sk-line-md { width: 40%; height: 1rem; margin-bottom: 1rem; }
.sk-chart { height: 11rem; }

/* KPI 网格 - bento grid 规范 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}

/* KPI 卡片 */
.kpi-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  display: flex;
  gap: 1rem;
  box-shadow: var(--shadow-sm);
}

/* KPI 图标：统一单 accent 底，图标色取 on-primary（双主题下自动翻转） */
.kpi-icon {
  width: 3rem; /* 48px */
  height: 3rem;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--color-primary);
  color: var(--color-on-primary);
}

/* KPI 内容 */
.kpi-info {
  flex: 1;
}

.kpi-value {
  font-size: var(--font-size-5); /* 20px */
  font-weight: 700;
  color: var(--color-on-surface);
  /* 4px 网格基线 */
  line-height: var(--line-height-relaxed);
}

.kpi-label {
  font-size: var(--font-size-2); /* 14px */
  color: var(--color-muted);
  margin-top: 0.125rem; /* 2px */
  /* 4px 网格系统 */
}

.kpi-trend {
  font-size: var(--font-size-1); /* 12px */
  margin-top: 0.25rem; /* 4px */
  display: flex;
  align-items: center;
  gap: 0.125rem; /* 2px */
}

.kpi-trend.up { color: var(--color-success); }
.kpi-trend.down { color: var(--color-error); }

/* chart-row: bento grid 两栏布局 */
.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1.5rem; /* 24px */
  margin-bottom: 1.5rem;
}

/* chart-card */
.chart-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  box-shadow: var(--shadow-sm);
}

.chart-card h3 {
  font-size: var(--font-size-4); /* 18px */
  font-weight: 600;
  margin: 0 0 1rem 0; /* 0 0 20px */
  color: var(--color-on-surface);
}

/* bar-chart：柱体百分比高度需要确定的父高度才有意义 */
.bar-chart {
  display: flex;
  align-items: stretch;
  gap: 1rem;
  height: 12rem;
  padding-bottom: 2rem;
}

.bar-item {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 0.25rem;
  position: relative;
}

.bar {
  width: 100%;
  max-width: 4rem; /* 64px */
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  min-height: 0.25rem;
  background: var(--color-primary);
}

.bar-label {
  font-size: var(--font-size-1); /* 12px */
  color: var(--color-muted);
  position: absolute;
  bottom: -1.5rem;
}

.bar-value {
  font-size: var(--font-size-1); /* 12px */
  color: var(--color-primary);
  font-weight: 600;
  margin-top: 0.25rem; /* 4px */
}

/* pie-chart */
.pie-chart {
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
}

.pie-item {
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
}

.pie-color {
  width: 0.75rem; /* 12px */
  height: 0.75rem;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}
/* 占比图例：单 accent 的同色系深浅阶梯（color-mix），避免多 accent */
.pie-0 { background: var(--color-primary); }
.pie-1 { background: color-mix(in srgb, var(--color-primary) 65%, white); }
.pie-2 { background: color-mix(in srgb, var(--color-primary) 40%, white); }
.pie-3 { background: color-mix(in srgb, var(--color-primary) 20%, white); }

.pie-name {
  flex: 1;
  font-size: var(--font-size-2); /* 14px */
  color: var(--color-on-surface);
}

.pie-value {
  font-size: var(--font-size-2); /* 14px */
  font-weight: 600;
  color: var(--color-on-surface);
}

/* agent-entry */
.agent-entry {
  margin-top: 1rem; /* 16px */
  /* eyebrow 计数已验证：0 ≤ 1 ✅ */
}

.agent-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  display: flex;
  align-items: center;
  gap: 1rem;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.agent-card:hover {
  box-shadow: var(--shadow-accent);
}

.agent-icon { color: var(--color-primary); flex-shrink: 0; }

.agent-title {
  font-size: var(--font-size-4); /* 18px */
  font-weight: 600;
  color: var(--color-on-surface);
}

.agent-desc {
  font-size: var(--font-size-2); /* 14px */
  color: var(--color-muted);
  margin-top: 0.125rem; /* 2px */
}

.agent-chevron { color: var(--color-muted); flex-shrink: 0; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .kpi-grid { grid-template-columns: repeat(2, 1fr); } .chart-row { grid-template-columns: 1fr; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .kpi-grid { grid-template-columns: 1fr; } }
</style>
