<script setup lang="ts">
/**
 * 头程成本分摊面板（从 LogisticsDashboard.vue 拆出，§S33）。
 *
 * ## 与 receipts 面板的区别：表单状态搬进来了
 * 上一个拆的 LogisticsReceiptPanel 只收数据、只发事件，因为它的两个动作
 * 没有本地状态。本面板不同：分摊表单的 `target / form / result / busy`
 * 四件套**只服务这块面板**（实测全文件无第二处引用），搬进来自包含，
 * 页壳不必为了一份表单状态持有四个 ref。
 *
 * ## 但仍然不自己拉看板
 * 提交成功后必须重拉看板（分摊改写了明细行，看板数字已过期），
 * 而重拉失败要进页壳的页级错误区。所以「拉看板」这件事通过
 * `reload-board` 事件交回页壳执行——本组件只负责发起请求与展示结果。
 *
 * ## 两处不能省的校验（都在提交前，且都有注释说明为什么）
 * 1. **空输入不等于 0**：`<input type="number">` 清空后 v-model 给 `''`，
 *    而 `Number('') === 0`。若不排除，用户没填运费就提交，会把该票全部
 *    明细行的分摊金额写成 0——那是悄悄毁数据，且看不出来。
 *    所以 `''` / null / undefined 一律转成 NaN，再由 `!Number.isFinite` 拦下。
 * 2. **总额必须人工确认**：预填值只是主单已登记的那个数，只是建议不是结论。
 *    后端会重写该票全部明细行，填错的后果不可逐行回滚。
 */
import { ref } from 'vue'
import Icon from './Icon.vue'
import {
  calculateFreight,
  type FreightCostBoard,
  type FreightCalculateResult,
  type UncoveredShipment
} from '../api/logistics'
import { useToast } from '../composables/useToast'
import { canUseRole } from '../utils/identity'

const props = defineProps<{
  /** 头程成本看板数据 */
  board: FreightCostBoard | null
  /** 页级操作互斥标记 */
  busy: boolean
}>()

const emit = defineEmits<{
  /** 分摊提交成功后请求页壳重拉看板（本组件不自己拉，见文件头） */
  (e: 'reload-board'): void
}>()

const { showToast } = useToast()

/** 分摊入口门控：OPERATOR/ADMIN 才能点（后端同口径 @RequireRole）。 */
const canAllocateFreight = canUseRole(['OPERATOR', 'ADMIN'])

const CALC_METHODS = ['WEIGHT', 'VOLUME', 'QUANTITY'] as const

const calcTarget = ref<UncoveredShipment | null>(null)
// number 输入框清空后 v-model 给 null：字段类型必须容纳它，否则 tsc 收窄后
// 「空」会被当成 0 提交（Number(null) === 0），0 元分摊会把明细金额全写成 0
const calcForm = ref<{
  method: string
  totalFreight?: number | string | null
  totalDuty?: number | string | null
  totalInsurance?: number | string | null
}>({ method: 'WEIGHT' })
const calcResult = ref<FreightCalculateResult | null>(null)
const calcBusy = ref(false)

const openCalcForm = (u: UncoveredShipment) => {
  calcTarget.value = u
  calcResult.value = null
  calcForm.value = {
    method: 'WEIGHT',
    // 运费预填主单已登记值——「有费用未摊」的那类货件钱已经花了，只是没进成本
    totalFreight: u.freightCost ?? undefined,
    totalDuty: undefined,
    totalInsurance: undefined
  }
}

const closeCalcForm = () => {
  calcTarget.value = null
  calcResult.value = null
}

const doCalculateFreight = async () => {
  const target = calcTarget.value
  if (!target) return
  // 见文件头第 1 条：空输入经 v-model 可能是 null/''，Number(null) === 0
  const rawFreight = calcForm.value.totalFreight
  const totalFreight = rawFreight === null || rawFreight === undefined || rawFreight === ''
    ? NaN : Number(rawFreight)
  if (!Number.isFinite(totalFreight) || totalFreight < 0) {
    showToast('请填写运费总额（≥0 的数字）', 'error')
    return
  }
  const numOrUndefined = (v: unknown) => {
    if (v === null || v === undefined || v === '') return undefined
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  calcBusy.value = true
  try {
    const res = await calculateFreight(target.shipmentId, {
      method: calcForm.value.method,
      totalFreight,
      totalDuty: numOrUndefined(calcForm.value.totalDuty),
      totalInsurance: numOrUndefined(calcForm.value.totalInsurance)
    })
    if (res?.code === 200 && res.data) {
      calcResult.value = res.data
      // 分摊改写了明细行，看板数字已过期，必须重拉
      emit('reload-board')
    } else {
      // 后端会点明具体缺什么（无明细行/基准为 0/方法非法），直接透出比笼统提示有用
      showToast(res?.message || '分摊计算失败', 'error')
    }
  } catch (e) {
    showToast('分摊计算失败，请稍后重试', 'error')
  } finally {
    calcBusy.value = false
  }
}

/* ---------- 展示用格式化（从页壳原样搬来，未改一字） ---------- */

/** null / undefined 显示占位符而不是 0；NaN 同样占位（0 会冒充真实值）。 */
const fmtAmount = (v?: number | null) =>
  v === null || v === undefined ? '—' : v.toFixed(2)

/**  BigDecimal 反序列化可能给字符串：先转数字再格式化，NaN 一律占位 */
const calcNum = (v: unknown): number | null => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 比率，0~1 → 百分比；null / undefined 表示没有基数，显示占位而不是 0% */
const fmtRate = (v?: number | null) =>
  v === null || v === undefined ? '—' : (v * 100).toFixed(1) + '%'

const statusLabel = (s?: string) => (s ? s : '未知')

const ALLOCATION_METHOD_LABELS: Record<string, string> = {
  WEIGHT: '按重量',
  VOLUME: '按体积',
  QUANTITY: '按数量'
}
const allocationMethodLabel = (m?: string) =>
  m ? ALLOCATION_METHOD_LABELS[m] || m : '—'

/** 各费用占比。分母用合计，合计为 0 时全部显示 —（没有基数就没有占比）。 */
const costShare = (part: 'freight' | 'duty' | 'insurance' | 'other') => {
  const total = props.board?.totalCost ?? 0
  if (!total) return '—'
  const part_value = props.board?.[
    part === 'freight' ? 'totalFreight'
      : part === 'duty' ? 'totalDuty'
        : part === 'insurance' ? 'totalInsurance'
          : 'totalOther'
  ] ?? 0
  return ((part_value / total) * 100).toFixed(1) + '%'
}
</script>

<template>
  <div data-panel="freight">
    <div v-if="props.board?.warnings?.length" class="warn-banner">
      <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
      <div class="wb-body">
        <div v-for="(w, i) in props.board.warnings" :key="i">{{ w }}</div>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:cash-multiple" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ fmtAmount(props.board?.totalCost) }}</div>
          <div class="kpi-label">头程成本合计</div>
          <div class="kpi-sub">{{ props.board?.totalAllocationRows ?? 0 }} 条分摊明细</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:calculator-variant-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">
            {{ props.board?.avgUnitCost == null ? '暂无样本' : fmtAmount(props.board.avgUnitCost) }}
          </div>
          <div class="kpi-label">加权单件头程成本</div>
          <div class="kpi-sub">总成本 / {{ props.board?.totalQuantity ?? 0 }} 件</div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:check-decagram-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.coveredShipments ?? 0 }}</div>
          <div class="kpi-label">已核算货件</div>
          <div class="kpi-sub">覆盖率 {{ fmtRate(props.board?.coverageRate) }}</div>
        </div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': (props.board?.uncoveredShipments ?? 0) > 0 }">
        <div class="kpi-icon tone-danger"><Icon icon="mdi:alert-circle-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ props.board?.uncoveredShipments ?? 0 }}</div>
          <div class="kpi-label">未核算货件</div>
          <div class="kpi-sub">成本尚未进入利润计算</div>
        </div>
      </div>
    </div>

    <div class="mini-grid">
      <div class="mini-card">
        <div class="mini-value">{{ fmtAmount(props.board?.totalFreight) }}</div>
        <div class="mini-label">运费分摊</div>
        <div class="mini-hint">占比 {{ costShare('freight') }}</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ fmtAmount(props.board?.totalDuty) }}</div>
        <div class="mini-label">关税分摊</div>
        <div class="mini-hint">占比 {{ costShare('duty') }}</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ fmtAmount(props.board?.totalInsurance) }}</div>
        <div class="mini-label">保险分摊</div>
        <div class="mini-hint">占比 {{ costShare('insurance') }}</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ fmtAmount(props.board?.totalOther) }}</div>
        <div class="mini-label">其他费用</div>
        <div class="mini-hint">占比 {{ costShare('other') }}</div>
      </div>
    </div>

    <div class="chart-card">
      <h3>未核算头程成本的货件</h3>
      <p class="card-desc">
        没有分摊明细的货件，运费不会摊到商品上，表现为<strong>利润被高估</strong>。
        其中「主单已登记运费却没做分摊」的一类最要紧：钱已经花了，只是没进成本。
        未核算的货件需要先在货件下登记 ASIN / SKU / 重量或体积，再执行费用分摊。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>货件号</th>
            <th>承运商</th>
            <th>状态</th>
            <th>ETA</th>
            <th>主单已登记运费</th>
            <th>提示</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!props.board?.uncoveredList?.length">
            <td colspan="7" class="empty-row">所有货件都已做头程分摊</td>
          </tr>
          <tr v-for="u in props.board?.uncoveredList || []" :key="u.shipmentId">
            <td class="mono">{{ u.shipmentNo || u.shipmentId }}</td>
            <td>{{ u.carrier || '—' }}</td>
            <td>{{ statusLabel(u.status) }}</td>
            <td class="mono">{{ u.eta || '—' }}</td>
            <td class="mono" :class="{ 'cell-alert': u.hasDeclaredFreight }">
              {{ u.freightCost == null ? '未登记' : fmtAmount(u.freightCost) }}
            </td>
            <td>
              <span v-if="u.hasDeclaredFreight" class="status-tag tone-danger">
                有费用未摊，直接抬高利润
              </span>
              <span v-else class="status-tag tone-muted">无费用信息</span>
            </td>
            <td>
              <button v-if="canAllocateFreight" class="link-btn" :disabled="props.busy || calcBusy" @click="openCalcForm(u)">分摊</button>
            </td>
          </tr>
        </tbody>
      </table>
      <!-- 头程分摊表单：把主单已登记的运费按方法摊到明细行（写操作，提交即改明细金额） -->
      <div v-if="calcTarget" class="cmp-form" data-panel="freight-calc">
        <strong>分摊 {{ calcTarget.shipmentNo || calcTarget.shipmentId }}</strong>
        <span class="muted">提交后按所选方法重写该票全部明细行的分摊金额；总额请按实际支出确认，预填的只是主单登记值。</span>
        <label>
          方法
          <select v-model="calcForm.method">
            <option v-for="m in CALC_METHODS" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label>
          运费总额 *
          <input type="number" min="0" step="0.01" v-model="calcForm.totalFreight" />
        </label>
        <label>
          关税
          <input type="number" min="0" step="0.01" v-model="calcForm.totalDuty" placeholder="可留空" />
        </label>
        <label>
          保险
          <input type="number" min="0" step="0.01" v-model="calcForm.totalInsurance" placeholder="可留空" />
        </label>
        <button class="primary-btn" :disabled="calcBusy" @click="doCalculateFreight">提交分摊</button>
        <button class="link-btn" @click="closeCalcForm">关闭</button>
      </div>
      <div v-if="calcResult" class="cmp-result" data-panel="freight-calc-result">
        <div class="cmp-best">
          分摊结果（{{ calcResult.method }}）：总额 {{ fmtAmount(calcNum(calcResult.grandTotal)) }}，
          共 {{ (calcResult.allocations || []).length }} 行明细
        </div>
        <div v-if="calcResult.balanced" class="muted">分摊平衡：各行之和等于总额。</div>
        <div v-else class="cmp-best multi">分摊不平：各行之和不等于总额，请核对后再用这批数字。</div>
      </div>
    </div>

    <div class="chart-card mt">
      <h3>单件头程成本 Top</h3>
      <p class="card-desc">
        按单件成本降序。若同一条航线上某个 SKU 的单件成本明显高于其他，
        通常意味着装箱方案不经济（体积重占比高）或分摊口径与其他货件不同。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>货件号</th>
            <th>ASIN</th>
            <th>SKU</th>
            <th>数量</th>
            <th>分摊总成本</th>
            <th>单件成本</th>
            <th>分摊方法</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!props.board?.topUnitCostItems?.length">
            <td colspan="7" class="empty-row">暂无分摊明细</td>
          </tr>
          <tr v-for="(c, i) in props.board?.topUnitCostItems || []" :key="i">
            <td class="mono">{{ c.shipmentNo || c.shipmentId || '—' }}</td>
            <td>{{ c.asin || '—' }}</td>
            <td class="mono">{{ c.sku || '—' }}</td>
            <td>{{ c.quantity ?? '—' }}</td>
            <td class="mono">{{ fmtAmount(c.totalCost) }}</td>
            <td class="mono"><b>{{ fmtAmount(c.unitCost) }}</b></td>
            <td>{{ allocationMethodLabel(c.allocationMethod) }}</td>
          </tr>
        </tbody>
      </table>
      <p class="foot-note">
        分摊方法说明：按重量 / 按体积 / 按数量。同一批货件混用不同方法时，
        单件成本不可直接横向比较——看板会在上方提示。
      </p>
    </div>
  </div>
</template>

<style scoped>
/* 不定义任何样式：用到的类全部来自 amz-frontend/src/styles/logistics-panels.css
 * （kpi-* / mini-* / chart-card / warn-banner / status-tag / link-btn /
 *   cell-alert / muted / foot-note）与 src/style.css（data-table / mono /
 *   empty-row），以及本页面原有的 cmp-form / cmp-result / cmp-best / primary-btn
 * ——后四个仍留在页壳的 scoped 块中，本组件不重复定义。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
