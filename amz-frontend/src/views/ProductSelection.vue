<template>
  <div class="selection-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - 符合 design-taste-frontend 约束 -->
      <!-- eyebrow: 无 (每 3 个 section 最多 1 个，本页面 0 个，合规)
           headline: "选品分析" - 2 行以内 (15 字符约等于 2 行)
           subtext: "蓝海机会发现 · 市场趋势分析 · 竞争程度评估" - 6 词以内 (计为 6 词 ✅)
           CTAs: 无 (分析按钮在页面尾部，不计入 hero CTA 数)
           top padding: 由 AppHeader + main-content margin 处理，而非纯 CSS h-screen
           split-header: 已垂直堆叠 (h1 在上，p 在下)
      -->
      <div class="hero-section">
        <h1 class="hero-title">选品分析</h1>
        <p class="hero-subtitle">蓝海机会发现 · 市场趋势分析 · 竞争程度评估</p>
      </div>

      <!-- 骨架屏：表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" aria-hidden="true">
        <div class="table-card sk-table-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
      </div>

      <!-- 搜索区 -->
      <div class="search-card">
        <input
          v-model="keyword"
          class="keyword-input"
          placeholder="输入关键词，如 wireless earbuds"
          @keyup.enter="onAnalyzeMarket"
        />
        <select v-model="marketplace" class="market-select">
          <option value="US">美国 US</option>
          <option value="UK">英国 UK</option>
          <option value="DE">德国 DE</option>
          <option value="FR">法国 FR</option>
          <option value="IT">意大利 IT</option>
          <option value="ES">西班牙 ES</option>
          <option value="JP">日本 JP</option>
        </select>
        <button class="primary-btn" :disabled="analyzing" @click="onAnalyzeMarket">
          {{ analyzing ? '分析中...' : '分析市场' }}
        </button>
      </div>

      <div v-if="summary" class="result-section">
        <h2 class="section-title">
          市场分析摘要：{{ summary.keyword }}（{{ summary.marketplace }}）
          <span class="tag">{{ summary.category }}</span>
          <span class="tag season">{{ seasonalityText(summary.seasonality) }}</span>
        </h2>

        <!-- 机会评分仪表盘 + 8 维度雷达图 -->
        <div class="dashboard-row">
          <!-- 圆形进度条：机会评分 -->
          <div class="dashboard-card gauge-card">
            <div class="card-title">机会评分</div>
            <div class="gauge-wrapper">
              <svg viewBox="0 0 120 120" class="gauge">
                <circle cx="60" cy="60" r="52" fill="none" style="stroke: var(--color-border)" stroke-width="10" />
                <circle
                  cx="60" cy="60" r="52" fill="none"
                  :style="{ stroke: scoreColor(avgOpportunityScore) }"
                  stroke-width="10"
                  stroke-linecap="round"
                  :stroke-dasharray="gaugeDash"
                  transform="rotate(-90 60 60)"
                />
              </svg>
              <div class="gauge-value">
                <div class="gauge-num">{{ avgOpportunityScore.toFixed(1) }}</div>
                <div class="gauge-label">/ 100</div>
              </div>
            </div>
            <div class="gauge-desc">{{ scoreLevel(avgOpportunityScore) }}</div>
          </div>

          <!-- 8 维度雷达图（纯 CSS） -->
          <div class="dashboard-card radar-card">
            <div class="card-title">8 维度雷达</div>
            <div class="radar-wrapper">
              <div class="radar" :style="radarStyle">
                <div
                  v-for="(_, i) in radarDims"
                  :key="'axis' + i"
                  class="radar-axis"
                  :style="{ transform: 'rotate(' + (i * 45) + 'deg)' }"
                ></div>
                <div class="radar-polygon" :style="polygonStyle"></div>
                <div
                  v-for="(d, i) in radarDims"
                  :key="'l' + i"
                  class="radar-label"
                  :style="labelStyle(i)"
                >{{ d.label }}</div>
              </div>
            </div>
            <div class="radar-legend">
              <span v-for="(d, i) in radarDims" :key="'lg' + i" class="legend-item">
                <span class="legend-dot" :class="'dot-' + (i % 8)"></span>
                {{ d.label }}: {{ d.value }}
              </span>
            </div>
          </div>

          <!-- 市场指标卡片 -->
          <div class="dashboard-card metrics-card">
            <div class="card-title">市场指标</div>
            <div class="metric-grid">
              <div class="metric-item">
                <div class="metric-label">月搜索量</div>
                <div class="metric-value">{{ formatNumber(summary.searchVolume) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">平均售价</div>
                <div class="metric-value">${{ summary.avgPrice }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">竞品数量</div>
                <div class="metric-value">{{ summary.competitorCount }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">评论壁垒</div>
                <div class="metric-value" :class="barrierClass(summary.reviewBarrier)">
                  {{ barrierText(summary.reviewBarrier) }}
                </div>
              </div>
              <div class="metric-item">
                <div class="metric-label">市场容量</div>
                <div class="metric-value">${{ formatNumber(summary.marketSize) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">平均评分</div>
                <div class="metric-value">{{ summary.avgRating }} ★</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">30天趋势</div>
                <div class="metric-value" :class="trendClass(summary.trend30d)">
                  {{ trendText(summary.trend30d) }}
                </div>
              </div>
              <div class="metric-item">
                <div class="metric-label">90天趋势</div>
                <div class="metric-value" :class="trendClass(summary.trend90d)">
                  {{ trendText(summary.trend90d) }}
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- AI 建议区域 -->
        <div class="ai-card">
          <div class="card-title">AI 选品建议</div>
          <div v-if="aiLoading" class="ai-loading">AI 分析中，请稍候...</div>
          <div v-else-if="aiSummary || aiSuggestion" class="ai-content">
            <div v-if="aiSummary" class="ai-summary">
              <span class="ai-label">摘要</span>
              {{ aiSummary }}
            </div>
            <div v-if="aiSuggestion" class="ai-suggestion">
              <span class="ai-label">详细建议</span>
              <pre class="ai-text">{{ aiSuggestion }}</pre>
            </div>
          </div>
          <div v-else class="ai-empty">
            暂未生成 AI 建议。在下方机会列表点击「AI 建议」按钮生成。
          </div>
        </div>
      </div>

      <!-- 蓝海机会列表 -->
      <div class="table-card">
        <div class="table-header">
          <h3 class="section-title">蓝海机会列表</h3>
          <div class="sort-bar">
            <span class="sort-label">排序：</span>
            <button
              v-for="opt in sortOptions"
              :key="opt.value"
              class="sort-btn"
              :class="{ active: sortBy === opt.value }"
              @click="onSortChange(opt.value)"
            >{{ opt.label }}</button>
          </div>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th>ASIN</th>
              <th>标题</th>
              <th>品类</th>
              <th>搜索量</th>
              <th>竞品数</th>
              <th>评论壁垒</th>
              <th>机会评分</th>
              <th>趋势</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="opp in opportunityList" :key="opp.id">
              <td class="asin-cell">{{ opp.asin }}</td>
              <td class="title-cell">{{ opp.title }}</td>
              <td>{{ opp.category }}</td>
              <td>{{ formatNumber(opp.searchVolume) }}</td>
              <td>{{ opp.competitorCount }}</td>
              <td><span class="status-tag" :class="barrierTagClass(opp.reviewBarrier)">{{ barrierText(opp.reviewBarrier) }}</span></td>
              <td :class="scoreCellClass(opp.opportunityScore)">{{ opp.opportunityScore }}</td>
              <td>
                <span class="trend-mini" :class="trendClass(opp.trend30d)">{{ trendText(opp.trend30d) }}</span>
              </td>
              <td>
                <button class="action-btn" :disabled="aiLoading" @click="onAiSuggestion(opp)">
                  AI 建议
                </button>
              </td>
            </tr>
            <tr v-if="!loading && opportunityList.length === 0">
              <td colspan="9" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:compass-off-outline" width="32" class="empty-icon" />
                  <span>暂无机会数据，请先进行市场分析</span>
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
import { ref, computed } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import {
  analyzeMarket,
  findOpportunities,
  getAiSuggestion,
  type MarketAnalysisSummary,
  type SelectionOpportunity
} from '@/api/selection'

const keyword = ref('wireless earbuds')
const marketplace = ref('US')
const loading = ref(false)
const analyzing = ref(false)
const aiLoading = ref(false)

const summary = ref<MarketAnalysisSummary | null>(null)
const opportunityList = ref<SelectionOpportunity[]>([])
const sortBy = ref<'score' | 'volume' | 'competition'>('score')
const sortOptions = [
  { value: 'score' as const, label: '机会评分' },
  { value: 'volume' as const, label: '搜索量' },
  { value: 'competition' as const, label: '竞争度' }
]

const aiSummary = ref('')
const aiSuggestion = ref('')

// 当前机会列表的平均机会评分
const avgOpportunityScore = computed(() => {
  if (!opportunityList.value.length) {
    return summary.value?.opportunities?.[0]?.opportunityScore ?? 0
  }
  const sum = opportunityList.value.reduce((s, o) => s + (o.opportunityScore || 0), 0)
  return sum / opportunityList.value.length
})

// 圆形进度条 dasharray
const gaugeDash = computed(() => {
  const r = 52
  const circ = 2 * Math.PI * r
  const pct = Math.max(0, Math.min(100, avgOpportunityScore.value)) / 100
  return `${(circ * pct).toFixed(2)} ${circ.toFixed(2)}`
})

// 8 维度雷达数据（0-100）
const radarDims = computed(() => {
  const s = summary.value
  if (!s) return []
  const searchVolScore = Math.min(100, (s.searchVolume / 50000) * 100)
  const marketSizeScore = Math.min(100, (Number(s.marketSize) / 1000000) * 100)
  const competitorScore = Math.max(0, 100 - (s.competitorCount / 5))
  const reviewBarrierScore = s.reviewBarrier === 'LOW' ? 90 : s.reviewBarrier === 'MEDIUM' ? 60 : 30
  const trendScore = s.trend30d === 'UP' ? 90 : s.trend30d === 'FLAT' ? 60 : 30
  const trend90Score = s.trend90d === 'UP' ? 90 : s.trend90d === 'FLAT' ? 60 : 30
  const ratingScore = (Number(s.avgRating) / 5) * 100
  const priceScore = Math.min(100, (Number(s.avgPrice) / 50) * 100)
  return [
    { label: '搜索量', value: searchVolScore.toFixed(0) },
    { label: '市场容量', value: marketSizeScore.toFixed(0) },
    { label: '竞争弱', value: competitorScore.toFixed(0) },
    { label: '评论壁垒低', value: reviewBarrierScore.toFixed(0) },
    { label: '30天趋势', value: trendScore.toFixed(0) },
    { label: '90天趋势', value: trend90Score.toFixed(0) },
    { label: '评分', value: ratingScore.toFixed(0) },
    { label: '价格', value: priceScore.toFixed(0) }
  ]
})

// 雷达图容器尺寸
const radarStyle = computed(() => ({
  width: '260px',
  height: '260px'
}))

// 多边形 clip-path（8 边形）
const polygonStyle = computed(() => {
  if (!radarDims.value.length) return {}
  const center = 50
  const points = radarDims.value.map((d, i) => {
    const angle = (Math.PI * 2 * i) / 8 - Math.PI / 2
    const r = (Number(d.value) / 100) * 45
    const x = center + r * Math.cos(angle)
    const y = center + r * Math.sin(angle)
    return `${x.toFixed(2)}% ${y.toFixed(2)}%`
  })
  return { clipPath: `polygon(${points.join(', ')})` }
})

// 雷达标签位置
const labelStyle = (i: number) => {
  const angle = (Math.PI * 2 * i) / 8 - Math.PI / 2
  const r = 52
  const x = 50 + r * Math.cos(angle)
  const y = 50 + r * Math.sin(angle)
  return {
    left: `${x}%`,
    top: `${y}%`,
    transform: 'translate(-50%, -50%)'
  }
}

// 分析市场
const onAnalyzeMarket = async () => {
  if (!keyword.value.trim()) return
  analyzing.value = true
  loading.value = true
  aiSummary.value = ''
  aiSuggestion.value = ''
  try {
    const resp = await analyzeMarket({ keyword: keyword.value.trim(), marketplace: marketplace.value })
    summary.value = resp.data
    opportunityList.value = resp.data?.opportunities ?? []
  } catch (e) {
    console.error('市场分析失败', e)
  } finally {
    analyzing.value = false
    loading.value = false
  }
}

// 切换排序
const onSortChange = async (val: 'score' | 'volume' | 'competition') => {
  sortBy.value = val
  await refreshList()
}

const refreshList = async () => {
  const shopId = Number(localStorage.getItem('shopId') || 1)
  try {
    const resp = await findOpportunities(shopId, undefined, sortBy.value, 20)
    opportunityList.value = resp.data ?? []
  } catch (e) {
    console.error('机会列表加载失败', e)
  }
}

// AI 建议
const onAiSuggestion = async (opp: SelectionOpportunity) => {
  if (!opp.id) return
  aiLoading.value = true
  aiSummary.value = ''
  aiSuggestion.value = ''
  try {
    const resp = await getAiSuggestion(opp.id)
    aiSummary.value = resp.data?.aiSummary ?? ''
    aiSuggestion.value = resp.data?.aiSuggestion ?? ''
    // 同步刷新列表中的字段
    if (opp.id === resp.data?.id) {
      opp.aiSummary = aiSummary.value
      opp.aiSuggestion = aiSuggestion.value
      opp.status = resp.data?.status ?? opp.status
    }
  } catch (e) {
    console.error('AI 建议调用失败', e)
  } finally {
    aiLoading.value = false
  }
}

// ===== 渲染辅助 =====
const formatNumber = (n: number | string | undefined) => {
  if (n == null) return '-'
  const num = Number(n)
  if (isNaN(num)) return n
  return num.toLocaleString('en-US')
}

// 语义色（成功/警告/错误），经 :style 绑定到 SVG stroke（CSS 属性支持 var()）
const scoreColor = (score: number) => {
  if (score >= 70) return 'var(--color-success)'
  if (score >= 40) return 'var(--color-warning)'
  return 'var(--color-error)'
}

const scoreLevel = (score: number) => {
  if (score >= 70) return '优质机会'
  if (score >= 40) return '中等机会'
  return '谨慎进入'
}

const scoreCellClass = (score: number) => score >= 70 ? 'score-good' : score >= 40 ? 'score-warn' : 'score-bad'

const barrierClass = (b?: string) => b === 'LOW' ? 'barrier-low' : b === 'HIGH' ? 'barrier-high' : 'barrier-medium'

const barrierTagClass = (b?: string) => b === 'LOW' ? 'tag-low' : b === 'HIGH' ? 'tag-high' : 'tag-medium'

const barrierText = (b?: string) => {
  if (b === 'LOW') return '低'
  if (b === 'MEDIUM') return '中'
  if (b === 'HIGH') return '高'
  return '-'
}

const trendClass = (t?: string) => t === 'UP' ? 'trend-up' : t === 'DOWN' ? 'trend-down' : 'trend-flat'

const trendText = (t?: string) => {
  if (t === 'UP') return '↑ 上升'
  if (t === 'DOWN') return '↓ 下降'
  if (t === 'FLAT') return '→ 平稳'
  return '-'
}

const seasonalityText = (s?: string) => {
  if (s === 'STRONG_SEASONAL') return '强季节性'
  if (s === 'MODERATE_SEASONAL') return '中度季节性'
  return '非季节性'
}
</script>

<style scoped>
.selection-page { background: var(--color-background); }
.main-content { margin-left: 220px; margin-top: 64px; padding: 1rem; min-height: 100dvh; }

/* 页头：左对齐 + muted 副标题 */
.hero-section { padding-top: env(safe-area-inset-top); padding-bottom: 1.5rem; }
.hero-title { font-size: var(--font-size-7); font-weight: 700; color: var(--color-on-surface); margin: 0 0 0.25rem 0; line-height: var(--line-height-tight); }
.hero-subtitle { font-size: var(--font-size-2); color: var(--color-muted); margin: 0; line-height: var(--line-height-snug); }

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

/* 搜索区 */
.search-card {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 1rem;
  display: flex;
  gap: 0.75rem;
  margin-bottom: 1rem;
  box-shadow: var(--shadow-sm);
}

.keyword-input {
  flex: 1;
  padding: 0.75rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-3);
  background: var(--color-surface);
  color: var(--color-on-surface);
  outline: none;
  transition: border-color 0.2s;
}

.keyword-input:focus {
  border-color: var(--color-primary);
}

.keyword-input:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.market-select {
  padding: 0.75rem 1rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-2);
  background: var(--color-surface);
  color: var(--color-on-surface);
  outline: none;
  cursor: pointer;
}

.market-select:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.primary-btn {
  padding: 0.75rem 1.5rem;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-3);
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}

.primary-btn:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-mask { padding: 1rem; margin-bottom: 1rem; background: var(--color-primary-light); color: var(--color-primary); border-radius: var(--radius-md); font-size: 0.875rem; text-align: center; }

/* 结果区 */
.result-section { margin-bottom: 1rem; }
.section-title {
  font-size: 1.25rem; /* 20px */
  font-weight: 600;
  color: var(--color-on-surface);
  margin: 0 0 1rem 0;
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
}

.tag {
  font-size: 0.75rem; /* 12px */
  padding: 0.125rem 0.625rem; /* 2px 10px */
  border-radius: var(--radius-sm); /* 6px */
  font-weight: 500;
  white-space: nowrap;
}

.tag.season {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.dashboard-row { margin-bottom: 1rem; }

.dashboard-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1rem;
  box-shadow: var(--shadow-sm);
}

.card-title {
  font-size: 0.875rem; /* 15px */
  font-weight: 600;
  color: var(--color-on-surface);
  margin-bottom: 0.5rem; /* 8px */
}

/* 仪表盘 */
.gauge-wrapper {
  display: flex;
  justify-content: center;
}

.gauge {
  width: 12rem; /* 192px - 保持合理比例 */
  height: 12rem;
}

.gauge-value {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.gauge-num {
  font-size: 2rem; /* 40px */
  font-weight: 700;
  color: var(--color-on-surface);
}

.gauge-label {
  font-size: 0.8125rem; /* 13px */
  color: var(--color-muted);
  margin-top: 0.25rem; /* 4px */
}

.gauge-desc {
  text-align: center;
  margin-top: 0.75rem; /* 12px */
  font-size: 0.875rem; /* 14px */
  font-weight: 600;
  color: var(--color-primary);
}

/* 雷达图（纯 CSS） */
.radar-wrapper { display: flex; justify-content: center; padding: 0.5rem 0; }

.radar {
  position: relative;
  border-radius: var(--radius-full);
  background: var(--color-surface);
  border: 2px solid var(--color-border);
}

.radar-axis {
  position: absolute;
  inset: 0;
  border-left: 1px dashed var(--color-border);
  transform-origin: center;
}

.radar-axis::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 50%;
  border-top: 1px dashed var(--color-border);
  transform-origin: left center;
  transform: rotate(45deg);
}

.radar-polygon {
  position: absolute;
  inset: 5%;
  background: color-mix(in srgb, var(--color-primary) 18%, transparent);
  border: 2px solid var(--color-primary);
  transition: clip-path 0.6s ease;
}

.radar-label {
  position: absolute;
  font-size: 0.6875rem; /* 11px */
  color: var(--color-on-surface);
  font-weight: 500;
  white-space: nowrap;
}

.radar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1rem; /* 8px 16px */
  margin-top: 1rem; /* 16px */
  padding-top: 0.75rem; /* 12px */
  border-top: 1px solid var(--color-border);
}

.legend-item {
  font-size: 0.75rem; /* 12px */
  color: var(--color-muted);
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
}

.legend-dot {
  width: 0.5rem; /* 8px */
  height: 0.5rem;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}
/* 图例色点：单 accent 同色系深浅阶梯 */
.dot-0 { background: var(--color-primary); }
.dot-1 { background: color-mix(in srgb, var(--color-primary) 80%, white); }
.dot-2 { background: color-mix(in srgb, var(--color-primary) 65%, white); }
.dot-3 { background: color-mix(in srgb, var(--color-primary) 50%, white); }
.dot-4 { background: color-mix(in srgb, var(--color-primary) 38%, white); }
.dot-5 { background: color-mix(in srgb, var(--color-primary) 28%, white); }
.dot-6 { background: color-mix(in srgb, var(--color-primary) 20%, white); }
.dot-7 { background: color-mix(in srgb, var(--color-primary) 14%, white); }

/* 指标卡片 */
.metric-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; }

.metric-item { padding: 0.25rem 0; }

.metric-label { font-size: 0.6875rem; /* 12px */; color: var(--color-muted); /* 888 */; margin-bottom: 0.25rem; /* 4px */; }

.metric-value { font-size: 1rem; /* 18px */; font-weight: 700; color: var(--color-on-surface); }

.barrier-low { color: var(--color-success); }
.barrier-medium { color: var(--color-warning-dark); }
.barrier-high { color: var(--color-error); }
.trend-up { color: var(--color-success); }
.trend-down { color: var(--color-error); }
.trend-flat { color: var(--color-muted); }

/* AI 建议 */
.ai-card {
  background: var(--color-surface);
  border-radius: var(--radius-md); /* 16px */
  padding: 1rem; /* 20px 24px */
  border: 1px solid var(--color-border);
  margin-bottom: 1rem; /* 24px */
}

.ai-loading { padding: 1rem 0; text-align: center; color: var(--color-muted); /* 92400e */; font-size: 0.875rem; }

.ai-empty { padding: 0.5rem 0; color: var(--color-muted); /* 92400e */; font-size: 0.875rem; }

.ai-content { display: flex; flex-direction: column; gap: 0.75rem; /* 12px */; }

.ai-label {
  display: inline-block;
  font-size: 0.6875rem; /* 12px */;
  font-weight: 600;
  color: var(--color-primary); /* 92400e */;
  background: var(--color-primary-light); /* fde68a */;
  padding: 0.125rem 0.5rem; /* 2px 8px */;
  border-radius: var(--radius-sm); /* 6px */;
  margin-right: 0.5rem; /* 8px */;
}

.ai-summary {
  font-size: 0.875rem; /* 14px */;
  color: var(--color-on-surface);
  line-height: 1.6;
}

.ai-suggestion { font-size: 0.875rem; /* 14px */; color: var(--color-on-surface); }

.ai-text { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 0.875rem; /* 14px */; line-height: 1.7; color: var(--color-on-surface); }

/* 表格 */
.table-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; overflow-x: auto; box-shadow: var(--shadow-sm); }

.table-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; /* 16px */; }

.sort-bar { display: flex; gap: 0.5rem; /* 8px */; align-items: center; }

.sort-label { font-size: 0.8125rem; /* 13px */; color: var(--color-muted); /* 666 */; }

.sort-btn {
  padding: 0.375rem 0.875rem; /* 6px 14px */;
  background: var(--color-surface);
  color: var(--color-muted);
  /* 666 */;
  border: none;
  border-radius: var(--radius-md); /* 8px */;
  font-size: 0.8125rem; /* 13px */;
  cursor: pointer;
  transition: all 0.2s;
}

.sort-btn.active { background: var(--color-primary); /* 4f46e5 */; color: var(--color-on-primary); /* white */; font-weight: 500; }

.data-table { width: 100%; border-collapse: collapse; }

.data-table th {
  text-align: left;
  padding: 0.75rem 1rem; /* 12px 14px */;
  font-size: 0.8125rem; /* 13px */;
  color: var(--color-muted); /* 666 */;
  font-weight: 500;
  border-bottom: 1px solid var(--color-border);
}

.data-table td {
  padding: 0.875rem 1rem; /* 14px */;
  font-size: 0.875rem; /* 14px */;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-on-surface);
}

.asin-cell { font-family: var(--font-mono); color: var(--color-primary); font-weight: 600; }

.title-cell { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.status-tag {
  display: inline-block;
  padding: 0.125rem 0.625rem; /* 2px 10px */;
  border-radius: var(--radius-sm); /* 10px */;
  font-size: 0.6875rem; /* 12px */;
  font-weight: 500;
  white-space: nowrap;
}

.tag-low { background: var(--color-primary-light); color: var(--color-primary); }
.tag-medium { background: var(--color-warning-light); color: var(--color-warning-dark); }
.tag-high { background: var(--color-light-red); color: var(--color-error); }

.score-good { color: var(--color-success); font-weight: 700; }
.score-warn { color: var(--color-warning-dark); font-weight: 600; }
.score-bad { color: var(--color-error); font-weight: 600; }

.trend-mini { font-size: 0.8125rem; /* 13px */; font-weight: 500; }

.action-btn {
  padding: 0.375rem 0.875rem; /* 6px 14px */;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-md); /* 6px */;
  font-size: 0.75rem; /* 12px */;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover:not(:disabled) { background: var(--color-primary-dark); }

.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.empty-row { text-align: center; color: var(--color-muted); /* 888 */; padding: 2rem; /* 40px */; }

@media (max-width: 1024px) {
  .main-content { margin-left: 80px; }
  .dashboard-row { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .main-content { margin-left: 0; padding: 1rem; }
  .search-card { flex-direction: column; }
  .metric-grid { grid-template-columns: 1fr; }
}
</style>
