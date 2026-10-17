<script setup lang="ts">
/**
 * 签收差异面板（从 LogisticsDashboard.vue 拆出，§S33）。
 *
 * ## 为什么是它第一个拆
 * 五个子域面板里它依赖最少：只看 `receiptBoard` 一个数据源，
 * 只有「开始核查 / 结案」两个动作，没有游标分页、没有表单、没有嵌套弹窗。
 * 用最小的一块验证拆分方法论，成本最低。
 *
 * ## 数据与 loader 都留在页壳
 * 本组件只收 `board` prop、只发事件。理由与 §S32.7 对子域面板的同一结论：
 * 两个动作失败后都要重拉看板（`safeLoad('receipts', ...)`），
 * 而失败要进页壳的页级错误区 `blockErrors`。把 loader 搬进来就得让子组件
 * 写父组件的错误数组，那会多出一条隐式数据通道。
 *
 * 页壳因此仍持有 `receiptBoard` ref——顺带也是 tab 角标的数据源
 * （`receiptBoard.pending`），角标必须留在页壳，否则切走这个 tab 后角标会空。
 *
 * ## 两个动作的共通过与差异
 * 「开始核查」与「结案」都先 `busy` 互斥、都 toast 结果、都重拉看板，
 * 但**不能合并成一个函数**：结案要先 `window.prompt` 收处理结果，
 * 且空值在本地拦下（不发请求）；核查没有输入。
 * 合成一个带可选参数的函数会让"空值拦截"这条分支消失在默认值里。
 */
import { Icon } from '@iconify/vue'
import type { ReceiptBoard } from '../api/logistics'

const props = defineProps<{
  /** 签收差异看板数据 */
  board: ReceiptBoard | null
  /** 页级操作互斥标记：为真时禁用本面板所有操作按钮 */
  busy: boolean
}>()

const emit = defineEmits<{
  (e: 'investigate', id: number): void
  (e: 'resolve', id: number): void
}>()

/** 差异类型的中文名与色点。负差异（少收）才是要动手的那类。 */
const DISC_TYPE_LABELS: Record<string, string> = {
  OVERRECEIVED: '多收',
  UNDERRECEIVED: '少收',
  MATCHED: '无差异',
  DAMAGED: '破损',
  NEGATIVE: '负差异'
}

const DISC_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  INVESTIGATING: '核查中',
  RESOLVED: '已结案'
}

const discTypeLabel = (t?: string) => (t ? DISC_TYPE_LABELS[t] || t : '—')
const discStatusLabel = (s?: string) => (s ? DISC_STATUS_LABELS[s] || s : '—')

const discTypeTone = (t?: string) => {
  if (t === 'UNDERRECEIVED') return 'tone-danger'
  if (t === 'OVERRECEIVED') return 'tone-warn'
  if (t === 'MATCHED') return 'tone-success'
  return 'tone-muted'
}

/** 0~1 比率 → 百分比；null / undefined 表示没有基数，显示占位而不是 0%。 */
const fmtRate = (v?: number | null) =>
  v === null || v === undefined ? '—' : (v * 100).toFixed(1) + '%'

const fmtTime = (v?: string | null) => {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <div data-panel="receipts">
    <div v-if="props.board?.warnings?.length" class="warn-banner">
      <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
      <div class="wb-body">
        <div v-for="(w, i) in props.board.warnings" :key="i">{{ w }}</div>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:clipboard-check-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.total ?? 0 }}</div>
          <div class="kpi-label">差异记录</div>
          <div class="kpi-sub">含 {{ props.board?.matched ?? 0 }} 条对账无差异</div>
        </div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': (props.board?.openShortageUnits ?? 0) > 0 }">
        <div class="kpi-icon tone-danger"><Icon icon="mdi:package-variant-minus" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.openShortageUnits ?? 0 }}</div>
          <div class="kpi-label">未结案少收 (件)</div>
          <div class="kpi-sub">还在等处理，可直接索赔</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon tone-warn"><Icon icon="mdi:trending-down" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.shortageUnits ?? 0 }}</div>
          <div class="kpi-label">少收合计 (件)</div>
          <div class="kpi-sub">{{ props.board?.shortageRows ?? 0 }} 条记录</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:trending-up" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.overreceivedUnits ?? 0 }}</div>
          <div class="kpi-label">多收合计 (件)</div>
          <div class="kpi-sub">{{ props.board?.overreceivedRows ?? 0 }} 条记录</div>
        </div>
      </div>
    </div>

    <div class="mini-grid">
      <div class="mini-card" :class="{ alert: (props.board?.pending ?? 0) > 0 }">
        <div class="mini-value">{{ props.board?.pending ?? 0 }}</div>
        <div class="mini-label">待处理</div>
        <div class="mini-hint">尚未开始核查</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ props.board?.investigating ?? 0 }}</div>
        <div class="mini-label">核查中</div>
        <div class="mini-hint">已有人跟进</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ props.board?.resolved ?? 0 }}</div>
        <div class="mini-label">已结案</div>
        <div class="mini-hint">已有处理结论</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ fmtRate(props.board?.discrepancyRate) }}</div>
        <div class="mini-label">差异率</div>
        <div class="mini-hint">
          应收 {{ props.board?.totalExpected ?? 0 }} / 实收 {{ props.board?.totalReceived ?? 0 }}
        </div>
      </div>
    </div>

    <div v-if="props.board?.overreceivedUnits && props.board?.shortageUnits" class="warn-banner strong">
      <Icon icon="mdi:swap-horizontal-bold" width="16" class="wb-icon" />
      <div class="wb-body">
        同时存在少收与多收：净差异（{{ props.board?.totalDifference ?? 0 }} 件）
        会把两个方向互相抵消。差异率因此按「绝对差异之和 / 应收合计」计算，
        避免量级很大的问题被净额掩盖。
      </div>
    </div>

    <div class="chart-card">
      <h3>少收最多的 ASIN</h3>
      <p class="card-desc">
        按少收件数降序。集中少收通常指向一个具体环节：某个 SKU 的装箱数量申报不准、
        或某个 FBA 仓对该品类的收货流程有系统性问题。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>ASIN</th>
            <th>SKU</th>
            <th>涉及货件</th>
            <th>应收</th>
            <th>实收</th>
            <th>少收</th>
            <th>多收</th>
            <th>净差异</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!props.board?.topShortageAsins?.length">
            <td colspan="8" class="empty-row">没有差异记录</td>
          </tr>
          <tr v-for="a in props.board?.topShortageAsins || []" :key="a.asin">
            <td>{{ a.asin }}</td>
            <td class="mono">{{ a.sku || '—' }}</td>
            <td>{{ a.shipmentCount }}</td>
            <td>{{ a.expected }}</td>
            <td>{{ a.received }}</td>
            <td :class="{ 'cell-alert': a.shortageUnits > 0 }">{{ a.shortageUnits }}</td>
            <td>{{ a.overreceivedUnits }}</td>
            <td>{{ a.netDifference }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="chart-card mt">
      <h3>待处理签收差异</h3>
      <p class="card-desc">
        差异数量由「实收 − 应收」自动算出，不接受手工填写，
        因此不会出现「差异值与两个数量互相矛盾」的记录。
        结案必须填写处理结果——否则这条差异会变成一条没有下文的数据，后续无法复盘。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>货件号</th>
            <th>ASIN</th>
            <th>SKU</th>
            <th>应收</th>
            <th>实收</th>
            <th>差异</th>
            <th>类型</th>
            <th>状态</th>
            <th>登记时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!props.board?.pendingItems?.length">
            <td colspan="10" class="empty-row">没有待处理的签收差异</td>
          </tr>
          <tr v-for="d in props.board?.pendingItems || []" :key="d.id">
            <td class="mono">{{ d.shipmentNo || d.shipmentId || '—' }}</td>
            <td>{{ d.asin || '—' }}</td>
            <td class="mono">{{ d.sku || '—' }}</td>
            <td>{{ d.expectedQuantity ?? '—' }}</td>
            <td>{{ d.receivedQuantity ?? '—' }}</td>
            <td :class="{ 'cell-alert': (d.difference ?? 0) < 0 }">
              {{ d.difference ?? 0 }}
            </td>
            <td>
              <span class="status-tag" :class="discTypeTone(d.discrepancyType)">
                {{ discTypeLabel(d.discrepancyType) }}
              </span>
            </td>
            <td>{{ discStatusLabel(d.status) }}</td>
            <td class="mono">{{ fmtTime(d.createTime) }}</td>
            <td class="ops">
              <button
                v-if="d.status === 'PENDING'"
                class="link-btn"
                :disabled="props.busy"
                @click="emit('investigate', d.id)"
              >
                开始核查
              </button>
              <button class="link-btn" :disabled="props.busy" @click="emit('resolve', d.id)">结案</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
/* 本组件不定义任何样式：它用到的类全部来自
 * amz-frontend/src/styles/logistics-panels.css（KPI / mini / chart-card /
 * warn-banner / status-tag / link-btn / ops / cell-alert / truncated-tip）
 * 与 src/style.css（data-table / mono / empty-row）。
 *
 * 这里**刻意留空**而不是把那些规则复制一份。scoped 不跨边界，
 * 但共享样式表是全局引入的，对本组件同样可见——复制只会造出第二份真相来源。
 * 由 tools/release/test_css_scope_parity.py 盯住这件事。
 */
</style>
