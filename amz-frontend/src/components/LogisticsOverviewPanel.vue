<script setup lang="ts">
/**
 * 物流概览（从 LogisticsDashboard.vue 拆出，§S33.12）。
 *
 * ## 为什么这块能整个搬进来
 * 概览只读两份数据：`overview`（汇总）与 `trend`（近 30 天建单/送达）。
 * **没有任何写操作、不引用任何其他 tab 的状态**——它是唯一打开页面就看到
 * 的那一屏，也因此是「不引用子域状态」判断的最佳起点（§S32.7 的结论）。
 * 所以连三个派生 computed（statusDist / sourceDist / chart）一起搬进来，
 * 页壳只留 overview / trend 两个 ref 供 loader 使用。
 *
 * ## 状态中文名与配色为什么走共享模块
 * `statusLabel` / `statusTone` 不只概览在用：待处理 tab（告警表）、
 * 在途货件 tab（货件表）都用同一张 STATUS_LABELS。复制一份到本组件，
 * 两个 tab 就会给同一个状态显示成两个中文名——那是系统里出现两个真相。
 * 所以放 logisticsStatus.ts，三处共用。
 *
 * ## 手写 SVG 折线图
 * chart computed 随本组件搬来，一字未改。它依赖 trend，而 trend 只服务这块。
 * viewBox 固定宽度、随容器缩放，故不需要监听 resize 做重算。
 */
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { DashboardOverview, TrendPoint } from '../api/logistics'
import { statusLabel, statusTone } from './logisticsStatus'

const props = defineProps<{
  /** 当前是否激活（页壳的 tab 是不是本面板） */
  active: boolean
  /** 概览汇总数据，由页壳的 loadOverview 拉取 */
  overview: DashboardOverview | null
  /** 近 30 天趋势点位 */
  trend: TrendPoint[]
}>()

/** 折线图标题里的天数。与页壳请求 trend 的天数是**两个**数，不要试图同步它们：
 * 标题该显示「实际拉了多少天」，而这个常量只是图形学上的抽样间隔基准。
 * 若以后要让它跟随请求天数，应由页壳作为 prop 传进来，不要在两边各写一个魔法数。 */
const trendDays = 30

const trendTotal = computed(() => {
  let created = 0
  let delivered = 0
  for (const p of props.trend) {
    created += p.created
    delivered += p.delivered
  }
  return { created, delivered }
})

const statusDist = computed(() => {
  const counts = props.overview?.statusCounts || {}
  const total = props.overview?.totalShipments || 0
  const order = ['IN_TRANSIT', 'CUSTOMS', 'CREATED', 'DELAYED', 'EXCEPTION', 'DELIVERED', 'RECEIVED', 'CLOSED']
  const rows = order
    .filter((k) => (counts[k] || 0) > 0)
    .map((k) => ({
      key: k,
      label: statusLabel(k),
      count: counts[k] || 0,
      tone: statusTone(k),
      percent: total > 0 ? ((counts[k] || 0) / total) * 100 : 0
    }))
  return rows
})

const sourceDist = computed(() => {
  const counts = props.overview?.dataSourceCounts || {}
  const labels: Record<string, string> = { IMPORT: '外部导入', API: '第三方API', AUTO: '自动' }
  return Object.keys(labels)
    .filter((k) => (counts[k] || 0) > 0)
    .map((k) => ({ key: k, label: labels[k], count: counts[k] || 0 }))
})

/**
 * 手写 SVG 折线图。
 * viewBox 固定宽度：随容器自适应缩放，避免监听 resize 做重算。
 */
const chart = computed(() => {
  const points = props.trend
  if (!points.length) return null
  const W = 640
  const H = 200
  const PAD = 34
  const innerH = H - PAD - 18

  let maxV = 1
  for (const p of points) {
    if (p.created > maxV) maxV = p.created
    if (p.delivered > maxV) maxV = p.delivered
  }
  const stepX = points.length > 1 ? (W - PAD * 2) / (points.length - 1) : 0
  const xOf = (i: number) => PAD + i * stepX
  const yOf = (v: number) => H - 18 - (v / maxV) * innerH

  const toPoly = (key: 'created' | 'delivered') =>
    points.map((p, i) => `${xOf(i).toFixed(1)},${yOf(p[key]).toFixed(1)}`).join(' ')

  const grid = [0, 0.5, 1].map((r) => ({
    y: Number(yOf(maxV * r).toFixed(1)),
    value: Math.round(maxV * r)
  }))

  // X 轴标签按间隔抽稀，避免 30 条标签重叠成一团
  const every = Math.max(1, Math.ceil(points.length / 6))
  const xLabels = points
    .map((p, i) => ({ x: Number(xOf(i).toFixed(1)), date: p.date }))
    .filter((_, i) => i % every === 0)

  return {
    W,
    H,
    PAD,
    grid,
    xLabels,
    createdPath: toPoly('created'),
    deliveredPath: toPoly('delivered')
  }
})
</script>

<template>
  <div v-if="props.active">

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:ship-wheel" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.activeShipments ?? 0 }}</div>
                  <div class="kpi-label">在跟踪货件</div>
                  <div class="kpi-sub">共 {{ overview?.totalShipments ?? 0 }} 个货件</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:clock-alert-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.delayed ?? 0 }}</div>
                  <div class="kpi-label">已判定延误</div>
                  <div class="kpi-sub">按 ETA 自动判定</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:alert-octagon-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.exception ?? 0 }}</div>
                  <div class="kpi-label">异常货件</div>
                  <div class="kpi-sub">扣关 / 查验 / 退件</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:calendar-clock" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.arrivingIn7Days ?? 0 }}</div>
                  <div class="kpi-label">7 天内到港</div>
                  <div class="kpi-sub">需提前准备入库</div>
                </div>
              </div>
            </div>

            <!-- 次级指标：都是「不作为就会被忽略」的那几类 -->
            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">
                  {{ overview?.avgTransitDays === null ? '暂无样本' : overview?.avgTransitDays + ' 天' }}
                </div>
                <div class="mini-label">平均头程时效</div>
                <div class="mini-hint">近 {{ overview?.transitStatWindowDays ?? 90 }} 天内送达样本</div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.etaOverdue ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.etaOverdue ?? 0 }}</div>
                <div class="mini-label">ETA 已过未标延误</div>
                <div class="mini-hint">延误重判任务可能尚未执行</div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.staleShipments ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.staleShipments ?? 0 }}</div>
                <div class="mini-label">取数过期</div>
                <div class="mini-hint">
                  超过 {{ overview?.staleThresholdHours ?? 48 }} 小时未取数
                </div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.missingTrackingNo ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.missingTrackingNo ?? 0 }}</div>
                <div class="mini-label">缺运单号</div>
                <div class="mini-hint">补录后才能自动跟踪</div>
              </div>
            </div>

            <div class="chart-row">
              <div class="chart-card">
                <div class="card-head">
                  <h3>近 {{ trendDays }} 天建单 / 送达</h3>
                  <div class="legend">
                    <span class="lg"><i class="dot dot-created"></i>建单 {{ trendTotal.created }}</span>
                    <span class="lg"><i class="dot dot-delivered"></i>送达 {{ trendTotal.delivered }}</span>
                  </div>
                </div>
                <!-- 折线图用手写 SVG：项目未引入图表库，两条线的需求不值得为此加依赖 -->
                <svg
                  v-if="chart"
                  class="chart"
                  :viewBox="`0 0 ${chart.W} ${chart.H}`"
                  role="img"
                  aria-label="建单量与送达量趋势"
                >
                  <line
                    v-for="g in chart.grid"
                    :key="'g' + g.y"
                    :x1="chart.PAD"
                    :x2="chart.W - chart.PAD"
                    :y1="g.y"
                    :y2="g.y"
                    class="grid-line"
                  />
                  <text
                    v-for="g in chart.grid"
                    :key="'t' + g.y"
                    :x="chart.PAD - 6"
                    :y="g.y + 3"
                    class="axis-text"
                    text-anchor="end"
                  >
                    {{ g.value }}
                  </text>
                  <polyline :points="chart.createdPath" class="line line-created" />
                  <polyline :points="chart.deliveredPath" class="line line-delivered" />
                  <text
                    v-for="l in chart.xLabels"
                    :key="l.date"
                    :x="l.x"
                    :y="chart.H - 6"
                    class="axis-text"
                    text-anchor="middle"
                  >
                    {{ l.date.slice(5) }}
                  </text>
                </svg>
                <div v-else class="empty-row">该时间窗内暂无数据</div>
              </div>

              <div class="chart-card">
                <h3>状态分布</h3>
                <div class="dist-list">
                  <div v-for="s in statusDist" :key="s.key" class="dist-item">
                    <span class="status-tag" :class="s.tone">{{ s.label }}</span>
                    <div class="dist-bar">
                      <div class="dist-fill" :style="{ width: s.percent + '%' }"></div>
                    </div>
                    <span class="dist-value">{{ s.count }}</span>
                  </div>
                </div>
                <h3 class="mt">取数来源</h3>
                <div class="source-list">
                  <span v-for="d in sourceDist" :key="d.key" class="source-chip">
                    {{ d.label }} <b>{{ d.count }}</b>
                  </span>
                </div>
              </div>
            </div>
          
  </div>
</template>

<style scoped>
/* ------------------------------------------------------------
 * 以下规则从 LogisticsDashboard.vue 的 scoped 块搬来（§S33.17 review）。
 *
 * 它们原本留在页壳里，而使用它们的模板已经搬进本组件——scoped 不跨组件边界，
 * 于是这些样式对本组件**完全无效**：概览的折线图与分布条、导入的表单与结果卡片
 * 全都掉成浏览器默认样式。所有测试与构建都是绿的，因为没人断言样式。
 *
 * 搬运时未改一个声明；等价性由 tools/release/test_css_scope_parity.py 的
 * test_no_orphan_rules_left_in_view_after_extraction 盯住。
 * ------------------------------------------------------------ */

/* 看板提示条：口径说明与数据质量提醒。
   单独做一个条而不是塞进正文，是为了让「有口径需要注意」这件事无法被忽略 */

/* KPI */
/* KPI 卡在「这项不该有值却有值」时描红边：否则一眼扫过去数字大小看不出异常 */

/* 次级指标 */
/* 需要处理时描红边：这些指标为 0 才是正常，非 0 就要有人去看 */

/* 图表区 */
.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1.5rem;
}

/* 卡片之间的间距：同层级卡片紧跟，跨层级的卡片用 .mt 拉开 */
.legend {
  display: flex;
  gap: 0.75rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
}

.lg { display: inline-flex; align-items: center; gap: 0.25rem; }

.dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: var(--radius-full);
  display: inline-block;
}

.dot-created { background: var(--color-primary); }

.dot-delivered { background: var(--color-success); }

.chart { width: 100%; height: auto; display: block; }

.grid-line { stroke: var(--color-border); stroke-width: 1; }

.axis-text {
  font-size: 10px;
  fill: var(--color-muted);
}

.line {
  fill: none;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.line-created { stroke: var(--color-primary); }

.line-delivered { stroke: var(--color-success); }

/* 状态分布 */
.dist-list { display: flex; flex-direction: column; gap: 0.625rem; }

.dist-item { display: flex; align-items: center; gap: 0.5rem; }

.dist-bar {
  flex: 1;
  height: 0.5rem;
  border-radius: var(--radius-full);
  background: var(--color-muted-light);
  overflow: hidden;
}

.dist-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: var(--radius-full);
}

.dist-value {
  width: 2.5rem;
  text-align: right;
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
}

.source-list { display: flex; flex-wrap: wrap; gap: 0.5rem; }

.source-chip {
  padding: 0.25rem 0.625rem;
  border-radius: var(--radius-sm);
  background: var(--color-muted-light);
  color: var(--color-muted);
  font-size: var(--font-size-1);
}

/* 响应式覆盖：随 .chart-row 一起从页壳搬来，保留 @media 包裹
   （丢掉包裹会让窄屏规则在宽屏生效——§S33 立项时的第三类失败） */
@media (max-width: 1024px) {
  .chart-row { grid-template-columns: 1fr; }
}
</style>
