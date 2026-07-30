<template>
  <div class="selection-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>选品分析</h1>
        <p class="subtitle">蓝海机会发现 · 市场趋势分析 · 竞争程度评估</p>
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

      <div v-if="loading" class="loading-mask">加载中...</div>

      <!-- 分析结果区 -->
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
                <circle cx="60" cy="60" r="52" fill="none" stroke="#eef0f4" stroke-width="10" />
                <circle
                  cx="60" cy="60" r="52" fill="none"
                  :stroke="scoreColor(avgOpportunityScore)"
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
                <span class="legend-dot" :style="{ background: d.color }"></span>
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
              <td colspan="9" class="empty-row">暂无机会数据，请先进行市场分析</td>
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
    { label: '搜索量', value: searchVolScore.toFixed(0), color: '#4f46e5' },
    { label: '市场容量', value: marketSizeScore.toFixed(0), color: '#0ea5e9' },
    { label: '竞争弱', value: competitorScore.toFixed(0), color: '#10b981' },
    { label: '评论壁垒低', value: reviewBarrierScore.toFixed(0), color: '#f59e0b' },
    { label: '30天趋势', value: trendScore.toFixed(0), color: '#ef4444' },
    { label: '90天趋势', value: trend90Score.toFixed(0), color: '#8b5cf6' },
    { label: '评分', value: ratingScore.toFixed(0), color: '#ec4899' },
    { label: '价格', value: priceScore.toFixed(0), color: '#14b8a6' }
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

const scoreColor = (score: number) => {
  if (score >= 70) return '#10b981'
  if (score >= 40) return '#f59e0b'
  return '#ef4444'
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
.selection-page {
  min-height: 100vh;
  background: #f5f6fa;
}

.main-content {
  margin-left: 220px;
  padding: 80px 32px 32px;
  min-height: 100vh;
}

.page-header h1 {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0 0 8px;
}

.subtitle {
  color: #666;
  margin: 0 0 24px;
  font-size: 14px;
}

/* 搜索区 */
.search-card {
  background: white;
  border-radius: 16px;
  padding: 20px;
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.keyword-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  font-size: 15px;
  outline: none;
  transition: border-color 0.2s;
}

.keyword-input:focus {
  border-color: #4f46e5;
}

.market-select {
  padding: 12px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  font-size: 14px;
  background: white;
  outline: none;
  cursor: pointer;
}

.primary-btn {
  padding: 12px 28px;
  background: linear-gradient(135deg, #4f46e5 0%, #6366f1 100%);
  color: white;
  border: none;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.primary-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.3);
}

.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-mask {
  text-align: center;
  padding: 60px;
  color: #888;
}

/* 结果区 */
.result-section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0 0 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.tag {
  font-size: 12px;
  padding: 2px 10px;
  background: #eef2ff;
  color: #4f46e5;
  border-radius: 12px;
  font-weight: 500;
}

.tag.season {
  background: #fef3c7;
  color: #b45309;
}

.dashboard-row {
  display: grid;
  grid-template-columns: 1fr 1.4fr 1.6fr;
  gap: 20px;
  margin-bottom: 24px;
}

.dashboard-card {
  background: white;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 16px;
}

/* 仪表盘 */
.gauge-wrapper {
  position: relative;
  width: 200px;
  height: 200px;
  margin: 0 auto;
}

.gauge {
  width: 100%;
  height: 100%;
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
  font-size: 40px;
  font-weight: 700;
  color: #1a1a2e;
}

.gauge-label {
  font-size: 13px;
  color: #888;
  margin-top: 4px;
}

.gauge-desc {
  text-align: center;
  margin-top: 12px;
  font-size: 14px;
  font-weight: 600;
  color: #4f46e5;
}

/* 雷达图（纯 CSS） */
.radar-wrapper {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

.radar {
  position: relative;
  border-radius: 50%;
  background: repeating-radial-gradient(circle, transparent 0 18px, rgba(79, 70, 229, 0.06) 18px 19px);
  border: 1px solid rgba(79, 70, 229, 0.15);
}

.radar-axis {
  position: absolute;
  inset: 0;
  border-left: 1px dashed rgba(79, 70, 229, 0.2);
  transform-origin: center;
}

.radar-axis::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 50%;
  border-top: 1px dashed rgba(79, 70, 229, 0.2);
  transform-origin: left center;
  transform: rotate(45deg);
}

.radar-polygon {
  position: absolute;
  inset: 5%;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.35), rgba(99, 102, 241, 0.25));
  border: 2px solid #4f46e5;
  transition: clip-path 0.6s ease;
}

.radar-label {
  position: absolute;
  font-size: 11px;
  color: #1a1a2e;
  font-weight: 500;
  white-space: nowrap;
}

.radar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.legend-item {
  font-size: 12px;
  color: #555;
  display: flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

/* 指标卡片 */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.metric-item {
  padding: 10px 0;
}

.metric-label {
  font-size: 12px;
  color: #888;
  margin-bottom: 4px;
}

.metric-value {
  font-size: 18px;
  font-weight: 700;
  color: #1a1a2e;
}

.barrier-low { color: #10b981; }
.barrier-medium { color: #f59e0b; }
.barrier-high { color: #ef4444; }
.trend-up { color: #10b981; }
.trend-down { color: #ef4444; }
.trend-flat { color: #6b7280; }

/* AI 建议 */
.ai-card {
  background: linear-gradient(135deg, #fefce8 0%, #fef9c3 100%);
  border-radius: 16px;
  padding: 20px 24px;
  border: 1px solid #fde68a;
  margin-bottom: 24px;
}

.ai-loading {
  padding: 30px 0;
  text-align: center;
  color: #92400e;
}

.ai-empty {
  padding: 16px 0;
  color: #92400e;
  font-size: 14px;
}

.ai-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-label {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  color: #92400e;
  background: #fde68a;
  padding: 2px 8px;
  border-radius: 6px;
  margin-right: 8px;
}

.ai-summary {
  font-size: 14px;
  color: #422006;
  line-height: 1.6;
}

.ai-suggestion {
  font-size: 14px;
  color: #422006;
}

.ai-text {
  margin: 8px 0 0;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  color: #422006;
}

/* 表格 */
.table-card {
  background: white;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.sort-bar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.sort-label {
  font-size: 13px;
  color: #666;
}

.sort-btn {
  padding: 6px 14px;
  background: #f5f6fa;
  color: #666;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.sort-btn.active {
  background: #4f46e5;
  color: white;
  font-weight: 500;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  text-align: left;
  padding: 12px 14px;
  font-size: 13px;
  color: #666;
  font-weight: 500;
  border-bottom: 1px solid #e5e7eb;
}

.data-table td {
  padding: 14px;
  font-size: 14px;
  border-bottom: 1px solid #f0f0f0;
  color: #1a1a2e;
}

.asin-cell {
  font-family: 'Courier New', monospace;
  color: #4f46e5;
  font-weight: 600;
}

.title-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.tag-low { background: #d1fae5; color: #065f46; }
.tag-medium { background: #fef3c7; color: #92400e; }
.tag-high { background: #fee2e2; color: #991b1b; }

.score-good { color: #10b981; font-weight: 700; }
.score-warn { color: #f59e0b; font-weight: 600; }
.score-bad { color: #ef4444; font-weight: 600; }

.trend-mini {
  font-size: 13px;
  font-weight: 500;
}

.action-btn {
  padding: 6px 14px;
  background: #eef2ff;
  color: #4f46e5;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn:hover:not(:disabled) {
  background: #4f46e5;
  color: white;
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-row {
  text-align: center;
  color: #888;
  padding: 40px;
}

@media (max-width: 1024px) {
  .main-content { margin-left: 80px; }
  .dashboard-row { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .main-content { margin-left: 0; padding: 80px 16px 32px; }
  .search-card { flex-direction: column; }
  .metric-grid { grid-template-columns: 1fr; }
}
</style>
