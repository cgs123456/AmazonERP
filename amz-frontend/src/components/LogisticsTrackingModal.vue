<script setup lang="ts">
/**
 * 货件轨迹时间线浮层（从 LogisticsDashboard.vue 拆出，§S33）。
 *
 * ## 为什么拆它
 * 它是页面上唯一一个「全屏遮罩 + 长列表」的模态，与九个 tab 面板的生命周期完全不同：
 * tab 面板是常驻 DOM 的一片区域，而这里是遮罩层。混在同一个 <template> 里会让
 * 「哪些 class 属于页面骨架、哪些属于浮层」分不清，改遮罩时要在一千多行里定位。
 *
 * ## 刻意只收渲染，不收 loader
 * loader（loadTracking / openTracking / loadMoreTracking）仍留在页壳：轨迹加载失败
 * 要进页级错误区（pushBlockError('加载轨迹', ...)），而错误区是页壳的 blockErrors。
 * 把 loader 搬进来就得让子组件写父组件的错误数组，那会多出一条隐式数据通道——
 * 与 §S32.7 对子域面板的同一结论：子组件只管渲染 + 发事件。
 *
 * 因此本组件**不自己决定何时加载**，只把「当前是什么状态」画出来，
 * 并把「用户想加载下一页 / 想关闭」报给页壳。
 *
 * ## truncated 与 pageMetaMissing 不合并
 * 二者互斥且语义不同：truncated 是服务端明确说「还有更多」，可以继续翻；
 * pageMetaMissing 是服务端没给分页元数据，完整性未知，此时**不该**显示「加载更多」
 * （点了也拿不到下一页，只会让人以为列表就这么长）。所以保留两个 prop，
 * 不合成一个 enum 或三态布尔。
 */
import type { TrackingEvent } from '../api/logistics'

const props = defineProps<{
  /** 浮层是否打开 */
  open: boolean
  /** 首屏加载中 */
  loading: boolean
  /** 已加载的轨迹事件 */
  timeline: TrackingEvent[]
  /** 服务端明确截断：还有更多，可继续加载 */
  truncated: boolean
  /** 服务端未返回分页元数据：完整性未知，不可视为全量 */
  pageMetaMissing: boolean
  /** 正在加载下一页 */
  loadingMore: boolean
  /** 还有下一页游标 */
  hasNext: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'load-more'): void
}>()

/**
 * 轨迹事件状态的中文名（从页壳原样搬来，未改一字）。
 *
 * 与页壳的货件状态表是**同一张表**：承运商回传的轨迹点用的就是货件那套状态枚举，
 * 只是这里比 tab 里的筛选下拉多展示了 DEPARTED / CUSTOMS_CLEARANCE / ARRIVED /
 * OUT_FOR_DELIVERY 四个中间态（下拉只列了八种「可筛选」的状态，这里列全部）。
 *
 * 拆出时**不改文案**：这五个键（IN_TRANSIT / DELIVERED / RECEIVED / DEPARTED /
 * CUSTOMS_CLEARANCE）我在第一版凭印象重写过，把「运输中」写成「在途」、
 * 「FBA已入库」写成「FBA已签收」——那是重构引入的用户可见行为变更，
 * 且没有任何测试会红（测试不断言这些中文串）。现已按原值还原。
 */
const STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  IN_TRANSIT: '运输中',
  CUSTOMS: '清关中',
  DELIVERED: '已送达',
  RECEIVED: 'FBA已入库',
  CLOSED: '已关闭',
  DELAYED: '延误',
  EXCEPTION: '异常',
  // 轨迹点状态（与货件状态命名不同，这里一并兜底展示）
  DEPARTED: '已起运',
  CUSTOMS_CLEARANCE: '清关中',
  ARRIVED: '已到港',
  OUT_FOR_DELIVERY: '派送中'
}

const statusLabel = (s?: string) => (s ? STATUS_LABELS[s] || s : '未知')

/** 状态 → 色点 class。延误/异常为红，其余按推进度分级。 */
const statusTone = (s?: string) => {
  switch (s) {
    case 'DELAYED':
    case 'EXCEPTION':
      return 'tone-danger'
    case 'DELIVERED':
    case 'RECEIVED':
      return 'tone-success'
    case 'IN_TRANSIT':
    case 'CUSTOMS':
    case 'DEPARTED':
    case 'CUSTOMS_CLEARANCE':
    case 'ARRIVED':
    case 'OUT_FOR_DELIVERY':
      return 'tone-info'
    default:
      return 'tone-muted'
  }
}

const fmtTime = (v?: string | null) => {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <div v-if="props.open" class="modal-mask" @click.self="emit('close')">
    <div class="modal" role="dialog" aria-label="轨迹时间线">
      <div class="modal-head">
        <h3>轨迹时间线</h3>
        <button class="link-btn" @click="emit('close')">关闭</button>
      </div>
      <div v-if="props.loading" class="empty-row">加载中…</div>
      <div v-else-if="!props.timeline.length" class="empty-row">
        暂无轨迹数据。可点「同步」拉取，或在「数据导入」中导入轨迹。
      </div>
      <ul v-else class="timeline">
        <li v-for="(e, i) in props.timeline" :key="i" class="timeline-item">
          <span class="tl-dot" :class="statusTone(e.eventStatus)"></span>
          <div class="tl-body">
            <div class="tl-head">
              <span class="tl-status">{{ statusLabel(e.eventStatus) }}</span>
              <span class="tl-time mono">{{ fmtTime(e.eventTime) }}</span>
            </div>
            <div class="tl-loc">{{ e.location || '—' }}</div>
            <div v-if="e.description" class="tl-desc">{{ e.description }}</div>
            <div class="tl-meta">
              <span v-if="e.rawStatus" class="tl-raw">原文：{{ e.rawStatus }}</span>
              <span v-if="e.source" class="tl-src">{{ e.source }}</span>
            </div>
          </div>
        </li>
      </ul>
      <div v-if="props.timeline.length || props.truncated || props.pageMetaMissing" class="pagination">
        <span class="page-info">
          已加载 {{ props.timeline.length }} 条<template v-if="props.truncated">，仍有更多未加载</template><template v-else-if="props.pageMetaMissing">，完整性未知</template>
        </span>
        <div class="page-actions">
          <button v-if="props.truncated" class="page-btn" :disabled="props.loadingMore || !props.hasNext" @click="emit('load-more')">
            {{ props.loadingMore ? '加载中...' : '加载更多' }}
          </button>
        </div>
      </div>
      <div v-if="props.truncated" class="truncated-tip" role="status">
        结果已被服务端截断：当前轨迹不是全量。请继续加载后再据此判断最新状态。
      </div>
      <div v-else-if="props.pageMetaMissing" class="truncated-tip" role="status">
        轨迹完整性无法确认：服务端未返回分页元数据，当前轨迹不能视为全量。
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 随组件搬移的样式（scoped CSS 不跨组件边界）。
 *
 * 只搬**本浮层专用**的：modal-mask / modal / modal-head / timeline / tl-*。
 * 已在 src/style.css 里全局定义的（pagination / page-info / page-actions /
 * page-btn / empty-row / mono / status-tag）不重复——重复会让两份定义走岔，
 * 而样式类名上看不出来。
 *
 * **不**在全局里的有 .link-btn 与 .truncated-tip：两者都一直定义在页壳的
 * scoped CSS 中，所以连同上面的浮层样式一并搬过来（见文件末尾的说明）。
 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

.modal {
  width: min(44rem, 100%);
  max-height: 80vh;
  overflow-y: auto;
  background: var(--color-surface);
  border-radius: var(--amz-radius-md);
  padding: 1.25rem;
  box-shadow: var(--shadow-md);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.modal-head h3 {
  margin: 0;
  font-size: var(--font-size-4);
  color: var(--color-on-surface);
}

.timeline { list-style: none; margin: 0; padding: 0; }
.timeline-item {
  display: flex;
  gap: 0.75rem;
  padding-bottom: 1rem;
  position: relative;
}

.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 0.3125rem;
  top: 1rem;
  bottom: 0;
  width: 1px;
  background: var(--color-border);
}

.tl-dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: var(--amz-radius-full);
  flex-shrink: 0;
  margin-top: 0.25rem;
  z-index: 1;
  background: var(--color-muted);
}

.tl-dot.tone-danger { background: var(--color-error); }
.tl-dot.tone-success { background: var(--color-success); }
.tl-dot.tone-info { background: var(--color-primary); }
.tl-body { flex: 1; }

.tl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}

.tl-status { font-size: var(--font-size-2); font-weight: 600; color: var(--color-on-surface); }
.tl-time { color: var(--color-muted); }
.tl-loc { font-size: var(--font-size-2); color: var(--color-on-surface); margin-top: 0.125rem; }
.tl-desc { font-size: var(--font-size-2); color: var(--color-muted); margin-top: 0.125rem; }

.tl-meta {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.25rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
}

.tl-raw { font-family: var(--font-mono); }
.tl-src {
  padding: 0 0.375rem;
  border-radius: var(--amz-radius-xs);
  background: var(--color-muted-light);
}

/* .link-btn **不在** src/style.css 里（只有 .mono / .status-tag 在），
 * 它一直定义在 LogisticsDashboard.vue 的 scoped CSS 中——而 scoped 样式不跨组件，
 * 所以必须原样搬一份过来，否则「关闭」按钮会掉成浏览器默认样式。
 * 值与页壳逐字一致（padding 0.125rem 0.375rem 那条最容易被看漏）。 */
.link-btn {
  padding: 0.125rem 0.375rem;
  border: none;
  background: transparent;
  color: var(--color-primary);
  font-size: var(--font-size-2);
  cursor: pointer;
}
.link-btn:hover { text-decoration: underline; }
.link-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.truncated-tip { margin-top: 0.75rem; padding: 0.625rem 0.875rem; border-radius: var(--amz-radius-md); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; line-height: 1.6; }
</style>
