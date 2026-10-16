<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import FinanceCursorBar from './FinanceCursorBar.vue'
import { makeCursor, useCursorLoader } from '@/composables/useCursorList'
import { useFinanceApi } from '@/composables/useFinanceApi'
import { useFinanceErrors } from '@/composables/useFinanceErrors'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'
import { WRITE_LOCKED_HINT } from '@/utils/identity'
import * as ext from '@/api/finance-ext'
import { CLAIM_STATUS, DISCREPANCY_STATUS } from '@/api/finance-ext'
import { listReceiptShortages, type ReceiptShortage } from '@/api/procurement'
import type {
  FeeDiscrepancy, FeeDiscrepancyScanReport, InboundShortageRequest, ReimbursementClaim,
  ReimbursementClaimSummary, ReimbursementReconcileReport
} from '@/api/finance-ext'
import type { ApiResponse } from '@/api/types'

/**
 * 费用差异 + 亚马逊索赔两个分区，以及短收/赔付登记表单。
 *
 * ## 为什么这两个分区必须住在同一个组件里
 *
 * 它们本来就互相缠结：`claimFrom`（差异 → 索赔单）与 `submitForm` 的短收分支
 * 都要同时回读两张表（`Promise.all([loadDiscrepancies(), loadClaims()])`，
 * 原先出现在 4 处）。按 tab 各切一个组件，就得让两个兄弟组件互相调用各自的
 * loader、或架一条事件总线来传「该刷新了」——那正是拆分想避免的形状。
 * 合在一个组件里，缠结从跨组件契约变成内部函数调用，代价只是模块大一点。
 *
 * 短收登记表单也在这里：它的入口按钮在差异 tab（`openShortage`），
 * 它的「按此行登记」在差异 tab 的短收清单里（`prefillShortage`），
 * 而赔付登记（`openReimburse`）的入口在索赔 tab。表单跟着两个入口走。
 *
 * ## 与 FinanceSettlementOps 同规：始终挂载，靠 tab prop 决定渲染哪个面板
 *
 * 清单数据必须活过「切走再切回」（`v-if` 挂卸载会让切回来变成空表 + 空态文案，
 * 那是主动误导）。「首次进入才拉」的把关留在页壳的 `loadedTabs`，本组件
 * 只 expose loader，不自己 watch tab——两处把关就是两套「已加载」集合。
 */
const props = defineProps<{
  /** 当前激活的 tab；不属于本组件时为 null。 */
  tab: 'discrepancy' | 'claim' | null
}>()

/** 确认框交给页壳（弹窗状态与 runConfirm 都在 Finance.vue）。 */
const emit = defineEmits<{ confirm: [box: { title: string; detail: string; run: () => Promise<void> }] }>()

const PAGE = 50
const { busy, financeWriteAllowed, read, call } = useFinanceApi()
const { pushError } = useFinanceErrors()
const { loadCursor } = useCursorLoader()
const { refreshShop } = useShopGuard()
const { showToast } = useToast()

const shop = () => refreshShop()

/** 一次性列表加载（不累积游标）：清单类端点数有限，用不着分页状态 */
const loadCursor2 = async <T>(target: { value: T[] }, name: string,
  fetcher: () => Promise<ApiResponse<T[]>>) => {
  try {
    const res = await fetcher()
    if (res?.code !== 200) { pushError(`${name}：${res?.message || '后端返回非 200'}`); return }
    target.value = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    pushError(`${name}：${e instanceof Error ? e.message : '调用失败'}`)
  }
}
/* ---------- 入库短收待登记（采购域 → 财务差异） ---------- */
const shortages = ref<ReceiptShortage[]>([])
const shortagesLoading = ref(false)

const loadShortages = async () => {
  shortagesLoading.value = true
  try {
    await loadCursor2(shortages, '入库短收清单', () => listReceiptShortages(shop(), { size: 50 }))
  } finally {
    shortagesLoading.value = false
  }
}

/** 用签收事实预填登记表单：件数与 SKU 是事实，金额与币种留给人确认。 */
const prefillShortage = (s: ReceiptShortage) => {
  shortage.value = {
    sku: s.sku || '',
    shipmentId: s.fbaShipmentId || s.shipmentNo || '',
    shortageUnits: s.shortUnits ?? 1,
    unitAmount: s.unitCost === null || s.unitCost === undefined ? undefined : Number(s.unitCost),
    currency: '',
    note: `系统短收事实：应发 ${s.expectedQty} / 实收 ${s.receivedQty}（明细 #${s.itemId}）`
  }
  formKind.value = 'shortage'
}

/* ---------- 费用差异 ---------- */
const disc = makeCursor<FeeDiscrepancy>()
const discStatus = ref('')
const scan = ref<FeeDiscrepancyScanReport | null>(null)

const loadDiscrepancies = (append = false) => {
  void loadCursor(disc, '费用差异', cursor =>
    ext.listDiscrepancies(shop(), { status: discStatus.value || undefined, size: PAGE, cursor }), append)
}
const runScan = async () => {
  const ok = await call('差异扫描', () => ext.scanDiscrepancies(shop()), (d) => { scan.value = d })
  if (ok) await loadDiscrepancies()
}
const openShortage = () => {
  shortage.value = { sku: '', shipmentId: '', shortageUnits: 1, unitAmount: undefined, currency: 'USD', note: '' }
  formKind.value = 'shortage'
}

/**
 * 费用差异详情：拉单条（含 evidence / detectedAt——列表不显示这两个字段，
 * 而它们正是「为什么判成差异」的依据）。
 * <p>
 * 后端返回空 data（记录不存在或跨店）时不铺详情面板：不能把空详情当成「拉到了」。
 * 失败原因由 call 按「差异详情：」前缀落到错误区。
 */
const discrepancyDetail = ref<FeeDiscrepancy | null>(null)
const openDiscrepancyDetail = async (d: FeeDiscrepancy) => {
  if (d.id == null) return
  discrepancyDetail.value = null
  await read('差异详情', () => ext.discrepancyDetail(d.id as number, shop()), (row) => {
    discrepancyDetail.value = row || null
  })
}
const dismissConfirm = (d: FeeDiscrepancy) => ({
  title: '忽略这条费用差异',
  detail: `标记为 DISMISSED 后不再进入索赔流程（${d.sku || '-'} / ${d.discrepancyType}，差额 ${d.difference}）。若之后发现判错，需要人工重新登记。`,
  run: async () => {
    const ok = await call('忽略差异', () => ext.dismissDiscrepancy(d.id as number, shop()), () => undefined)
    if (ok) await loadDiscrepancies()
  }
})
const claimFrom = (d: FeeDiscrepancy) => ({
  title: '由差异生成索赔单',
  detail: `按 ${d.sku || '-'} 的 ${d.discrepancyType} 差额 ${d.difference} ${d.currency || ''} 建一张 CANDIDATE 索赔单，后续仍需「提交平台」。`,
  run: async () => {
    const ok = await call('生成索赔单', () => ext.createClaimFromDiscrepancy(shop(), d.id as number), () => undefined)
    if (ok) {
      await Promise.all([loadDiscrepancies(), loadClaims()])
    }
  }
})

/* ---------- 索赔 ---------- */
const claimList = makeCursor<ReimbursementClaim>()
const claimStatus = ref('')
const claimSum = ref<ReimbursementClaimSummary | null>(null)
const recon = ref<ReimbursementReconcileReport | null>(null)

const loadClaims = (append = false) => {
  void loadCursor(claimList, '索赔单', cursor =>
    ext.listClaims(shop(), { status: claimStatus.value || undefined, size: PAGE, cursor }), append)
}
const loadClaimSummary = () => read('索赔汇总', () => ext.claimSummary(shop()), (d) => { claimSum.value = d })
const runReconcile = async () => {
  const ok = await read('索赔对账', () => ext.reconcileClaims(shop()), (d) => { recon.value = d })
  if (ok) showToast(`对账完成：平台侧 ${d0(recon.value?.platformAdjustmentCount)} 笔 / 系统侧 ${d0(recon.value?.matched)} 笔匹配`, 'info')
}
const d0 = (v: unknown) => Number(v ?? 0)
const runClaim = async (c: ReimbursementClaim, action: 'submit' | 'accept') => {
  const label = action === 'submit' ? '提交平台' : '登记受理'
  const fn = action === 'submit' ? () => ext.submitClaim(c.id as number, shop()) : () => ext.acceptClaim(c.id as number, shop())
  const ok = await call(label, fn, (res) => { replaceClaim(res) })
  if (ok) {
    await Promise.all([loadClaims(), loadClaimSummary()])
  }
}
const replaceClaim = (c: ReimbursementClaim) => {
  claimList.rows.value = claimList.rows.value.map(x => (x.id === c.id ? { ...x, ...c } : x))
}
const openReimburse = (c: ReimbursementClaim) => {
  formClaim.value = c
  reimburseAmount.value = c.claimAmount ?? ''
  formKind.value = 'reimburse'
}
const rejectConfirm = (c: ReimbursementClaim) => ({
  title: '驳回索赔单',
  detail: `${c.claimNo || c.id} 将被标记 REJECTED，并生成红字凭证冲回此前确认的赔付收入（后端 reimburse 链路决定）。`,
  run: async () => {
    const ok = await call('驳回索赔', () => ext.rejectClaim(c.id as number, shop()), () => undefined)
    if (ok) {
      await Promise.all([loadClaims(), loadClaimSummary()])
    }
  }
})
/* ---------- 短收 / 赔付登记表单 ---------- */
const formKind = ref<null | 'shortage' | 'reimburse'>(null)
const shortage = ref<InboundShortageRequest>({ sku: '', shipmentId: '', shortageUnits: 1, unitAmount: undefined, currency: 'USD', note: '' })
const formClaim = ref<ReimbursementClaim | null>(null)
const reimburseAmount = ref<number | string>('')

const submitForm = async () => {
  if (formKind.value === 'shortage') {
    const s = shortage.value
    if (!s.sku.trim() || !s.shipmentId.trim() || Number(s.shortageUnits) < 1) {
      pushError('入库短收：SKU、货件号必填，短收件数至少 1')
      return
    }
    if (!String(s.currency || '').trim()) {
      pushError('入库短收：必须显式选择赔付币种（我方成本口径没有记币种，不能默认）')
      return
    }
    const ok = await call('登记短收差异', () => ext.intakeInboundShortage(shop(), { ...s, shortageUnits: Number(s.shortageUnits) }),
      (id) => showToast(`已登记差异 #${id}`, 'success'))
    if (ok) {
      formKind.value = null
      await loadDiscrepancies()
    }
    return
  }
  const c = formClaim.value
  if (!c?.id) return
  const amount = Number(reimburseAmount.value)
  if (!Number.isFinite(amount) || amount < 0) { pushError('赔付金额必须是非负数字'); return }
  const ok = await call('登记赔付', () => ext.reimburseClaim(c.id as number, shop(), amount), (res) => { replaceClaim(res) })
  if (ok) {
    formKind.value = null
    await Promise.all([loadClaims(), loadClaimSummary()])
  }
}

/** 短收/赔付登记表单与两个分区都由本组件持有；页壳调用它们加载/刷新 */
defineExpose({ loadDiscrepancies, loadShortages, loadClaims, loadClaimSummary })
</script>

<template>
      <!-- 费用差异 -->
      <div v-if="props.tab === 'discrepancy'" class="tab-panel" data-panel="discrepancy">
        <div class="filter-bar">
          <select v-model="discStatus" class="filter-select">
            <option value="">全部状态</option>
            <option v-for="s in DISCREPANCY_STATUS" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="filter-btn" :disabled="disc.loading.value" @click="loadDiscrepancies(false)">刷新</button>
          <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '从结算明细比对出费用差异' : `扫描差异${WRITE_LOCKED_HINT}`" @click="runScan">扫描差异</button>
          <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '登记一条入库短收差异' : `登记入库短收${WRITE_LOCKED_HINT}`" @click="openShortage()">登记入库短收</button>
        </div>

        <div v-if="scan" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">本次新建</div>
            <div class="summary-value small">{{ scan.created ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">已存在跳过</div>
            <div class="summary-value small">{{ scan.skippedExisting ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">无预估费用可对照</div>
            <div class="summary-value small">{{ scan.skippedNoEstimate ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">可索赔金额</div>
            <div class="summary-value small profit-positive">{{ scan.claimableAmount ?? 0 }}</div>
          </div>
        </div>
        <p v-if="scan?.warnings?.length" class="profit-note">扫描提示：{{ scan.warnings.join('；') }}</p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr><th>SKU</th><th>货件</th><th>差异类型</th><th>应收</th><th>实收</th><th>差额</th><th>币种</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="d in disc.rows.value" :key="d.id">
                <td class="mono">{{ d.sku || '-' }}</td>
                <td class="mono">{{ d.shipmentId || '-' }}</td>
                <td>{{ d.discrepancyType }}</td>
                <td>{{ d.expectedAmount }}</td>
                <td>{{ d.actualAmount }}</td>
                <td class="profit-negative">{{ d.difference }}</td>
                <td>{{ d.currency || '-' }}</td>
                <td><span class="status-tag">{{ d.status }}</span></td>
                <td>
                  <!-- 详情是个只读 GET，后端 GET /finance/discrepancy/{id} 只加了
                       @ShopScoped 没有 @RequireRole（FeeDiscrepancyController:104），
                       VIEWER 查得到，所以刻意不套 financeWriteAllowed。
                       它右侧两个按钮才是写，按 d.status 与角色分别禁用。 -->
                  <button class="sync-btn" :disabled="busy" title="查看这条差异的判定依据"
                    @click="openDiscrepancyDetail(d)">详情</button>
                  <button class="sync-btn" :disabled="busy || d.status !== 'OPEN' || !financeWriteAllowed"
                    :title="d.status !== 'OPEN' ? `仅 OPEN 状态可生成索赔单；当前 ${d.status} 已终结` : (financeWriteAllowed ? '以这条差异生成亚马逊索赔单' : `生成索赔单${WRITE_LOCKED_HINT}`)"
                    @click="emit('confirm', claimFrom(d))">生成索赔单</button>
                  <button class="sync-btn" :disabled="busy || d.status !== 'OPEN' || !financeWriteAllowed"
                    :title="d.status !== 'OPEN' ? `仅 OPEN 状态可忽略；当前 ${d.status} 已终结` : (financeWriteAllowed ? '把这条差异标记为不索赔' : `忽略差异${WRITE_LOCKED_HINT}`)"
                    @click="emit('confirm', dismissConfirm(d))">忽略</button>
                </td>
              </tr>
              <tr v-if="!disc.loading.value && disc.rows.value.length === 0">
                <td colspan="9" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:scale-balance" width="32" class="empty-icon" />
                    <span>暂无费用差异记录：点「扫描差异」从结算明细里比对</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <FinanceCursorBar :state="disc" :load="loadDiscrepancies" />

        <div v-if="discrepancyDetail" class="table-card" data-panel="discrepancy-detail">
          <div class="panel-title">
            费用差异详情：{{ discrepancyDetail.sku || '-' }} / {{ discrepancyDetail.discrepancyType }}
            <button class="filter-btn" @click="discrepancyDetail = null">收起</button>
          </div>
          <div class="detail-grid">
            <div><span class="muted">差异 ID</span><span class="mono">{{ discrepancyDetail.id }}</span></div>
            <div><span class="muted">SKU</span><span class="mono">{{ discrepancyDetail.sku || '-' }}</span></div>
            <div><span class="muted">货件</span><span class="mono">{{ discrepancyDetail.shipmentId || '-' }}</span></div>
            <div><span class="muted">类型</span><span>{{ discrepancyDetail.discrepancyType || '-' }}</span></div>
            <div><span class="muted">应收</span><span>{{ discrepancyDetail.expectedAmount ?? '-' }}</span></div>
            <div><span class="muted">实收</span><span>{{ discrepancyDetail.actualAmount ?? '-' }}</span></div>
            <div><span class="muted">差额</span><span class="profit-negative">{{ discrepancyDetail.difference ?? '-' }}</span></div>
            <div><span class="muted">币种</span><span>{{ discrepancyDetail.currency || '-' }}</span></div>
            <div><span class="muted">状态</span><span>{{ discrepancyDetail.status || '-' }}</span></div>
            <div><span class="muted">判出时间</span><span>{{ (discrepancyDetail.detectedAt || '').slice(0, 19).replace('T', ' ') || '-' }}</span></div>
          </div>
          <p class="profit-note">
            <b>证据说明：</b>{{ discrepancyDetail.evidence || '无证据说明（后端未记录这条差异的判定依据）' }}
          </p>
        </div>

        <div class="table-card">
          <div class="panel-title">入库短收待登记（来自采购域签收事实）</div>
          <div class="filter-bar">
            <button class="filter-btn" :disabled="shortagesLoading" @click="loadShortages">刷新待登记清单</button>
            <span class="muted">
              单位成本是我方口径且没有记币种，亚马逊赔付按站点币种结算：
              金额与币种请在登记时确认，不要直接采信带入的成本数。
            </span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>亚马逊货件号</th><th>内部货件号</th><th>SKU</th><th>应发</th><th>实收</th><th>短收</th><th>我方单位成本口径</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in shortages" :key="s.itemId">
                <td class="mono">{{ s.fbaShipmentId || '-' }}</td>
                <td class="mono">{{ s.shipmentNo || s.shipmentId }}</td>
                <td class="mono">{{ s.sku || '-' }}</td>
                <td>{{ s.expectedQty }}</td>
                <td>{{ s.receivedQty }}</td>
                <td class="profit-negative">{{ s.shortUnits }}</td>
                <td>{{ s.unitCost ?? '-' }}</td>
                <td><button class="sync-btn" :disabled="busy" @click="prefillShortage(s)">按此行登记</button></td>
              </tr>
              <tr v-if="!shortages.length"><td colspan="8" class="empty-row">没有待登记的入库短收</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 亚马逊索赔 -->
      <div v-if="props.tab === 'claim'" class="tab-panel" data-panel="claim">
        <div class="filter-bar">
          <select v-model="claimStatus" class="filter-select">
            <option value="">全部状态</option>
            <option v-for="s in CLAIM_STATUS" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="filter-btn" :disabled="claimList.loading.value" @click="loadClaims(false)">刷新</button>
          <!-- GET /finance/claim/reconcile/{shopId} 后端无 @RequireRole（ReimbursementClaimController:140
               只加了 @ShopScoped），VIEWER 可查，所以这个「对账」按钮不按角色禁用——
               它读平台状态，不改任何数据。真正写的是 submit/accept/reject/reimburse。 -->
          <button class="filter-btn" :disabled="busy" title="拉取平台侧的索赔状态" @click="runReconcile">与平台对账</button>
        </div>

        <div v-if="claimSum" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">索赔单总数</div>
            <div class="summary-value small">{{ claimSum.total ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">待提交 / 已提交</div>
            <div class="summary-value small">{{ claimSum.candidate ?? 0 }} / {{ claimSum.submitted ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">已获赔付</div>
            <div class="summary-value small profit-positive">{{ claimSum.reimbursedAmountTotal ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">成功率 / 平均结案天数</div>
            <div class="summary-value small">{{ claimSum.successRate ?? '-' }}% / {{ claimSum.avgSettlementDays ?? '-' }}</div>
          </div>
        </div>

        <div v-if="recon" class="reconcile">
          <div class="recon-head">平台 Adjustment 与系统索赔单对账</div>
          <div class="recon-grid">
            <span>matched {{ recon.matched ?? 0 }}</span>
            <span>平台侧 {{ recon.platformAdjustmentCount ?? 0 }} 笔 / {{ recon.platformTotal ?? 0 }}</span>
            <span>系统侧合计 {{ recon.systemTotal ?? 0 }}</span>
            <span :class="Number(recon.difference) ? 'profit-negative' : ''">差额 {{ recon.difference ?? 0 }}</span>
          </div>
          <div class="recon-cols">
            <div>
              <div class="recon-sub">只有平台有（系统未登记 → 可能漏提索赔）</div>
              <div v-for="(p, i) in recon.platformOnly || []" :key="`p${i}`" class="recon-line">
                {{ p.reference || '-' }} / {{ p.sku || '-' }} / {{ p.amount }} {{ p.currency || '' }}
              </div>
              <div v-if="!(recon.platformOnly || []).length" class="muted">无</div>
            </div>
            <div>
              <div class="recon-sub">只有系统有（平台未赔付或口径不同）</div>
              <div v-for="(s, i) in recon.systemOnly || []" :key="`s${i}`" class="recon-line">
                {{ s.reference || '-' }} / {{ s.sku || '-' }} / {{ s.amount }} {{ s.currency || '' }}
              </div>
              <div v-if="!(recon.systemOnly || []).length" class="muted">无</div>
            </div>
          </div>
          <p v-if="recon.warnings?.length" class="profit-note">对账提示：{{ recon.warnings.join('；') }}</p>
        </div>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr><th>索赔单号</th><th>SKU</th><th>货件</th><th>差异类型</th><th>索赔额</th><th>已赔付</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="c in claimList.rows.value" :key="c.id">
                <td class="mono">{{ c.claimNo || c.id }}</td>
                <td class="mono">{{ c.sku || '-' }}</td>
                <td class="mono">{{ c.shipmentId || '-' }}</td>
                <td>{{ c.discrepancyType || '-' }}</td>
                <td>{{ c.claimAmount }}</td>
                <td>{{ c.reimbursedAmount ?? 0 }}</td>
                <td><span class="status-tag">{{ c.status }}</span></td>
                <td>
                  <button v-if="c.status === 'CANDIDATE'" class="sync-btn" :disabled="busy || !financeWriteAllowed" @click="runClaim(c, 'submit')">提交平台</button>
                  <button v-if="c.status === 'SUBMITTED'" class="sync-btn" :disabled="busy || !financeWriteAllowed" @click="runClaim(c, 'accept')">登记受理</button>
                  <button v-if="c.status === 'ACCEPTED'" class="sync-btn" :disabled="busy || !financeWriteAllowed" @click="openReimburse(c)">登记赔付</button>
                  <button v-if="c.status !== 'REIMBURSED' && c.status !== 'REJECTED'" class="sync-btn" :disabled="busy || !financeWriteAllowed" @click="emit('confirm', rejectConfirm(c))">驳回</button>
                </td>
              </tr>
              <tr v-if="!claimList.loading.value && claimList.rows.value.length === 0">
                <td colspan="8" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:shield-check-outline" width="32" class="empty-icon" />
                    <span>暂无索赔单：可从费用差异页「生成索赔单」</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <FinanceCursorBar :state="claimList" :load="loadClaims" />
      </div>



      <!-- 短收登记 / 赔付登记 -->
      <div v-if="formKind" class="modal-mask" @click.self="formKind = null">
        <div class="modal" role="dialog" aria-modal="true">
          <h3>{{ formKind === 'shortage' ? '登记入库短收差异' : '登记平台赔付金额' }}</h3>
          <div v-if="formKind === 'shortage'" class="form-grid">
            <label>SKU *<input v-model="shortage.sku" /></label>
            <label>货件号 *<input v-model="shortage.shipmentId" /></label>
            <label>短收件数 *<input type="number" min="1" v-model="shortage.shortageUnits" /></label>
            <label>单件金额<input type="number" step="0.01" min="0" v-model="shortage.unitAmount" /></label>
            <label>币种 *<input v-model="shortage.currency" placeholder="亚马逊赔付币种，如 USD" /></label>
            <label>备注<input v-model="shortage.note" /></label>
          </div>
          <div v-else class="form-grid">
            <label>索赔单<input :value="formClaim?.claimNo || formClaim?.id" disabled /></label>
            <label>索赔额<input :value="formClaim?.claimAmount" disabled /></label>
            <label>实际赔付 *<input type="number" step="0.01" min="0" v-model="reimburseAmount" /></label>
          </div>
          <div class="modal-actions">
            <button class="filter-btn" @click="formKind = null">取消</button>
            <button class="filter-btn" :disabled="busy || !financeWriteAllowed" @click="submitForm">提交</button>
          </div>
        </div>
      </div>
</template>

<style scoped>
/* 从 Finance.vue 的 <style scoped> 抄来：scoped 样式不跨组件边界，搬出 markup
   就必须带上它依赖的类，否则按钮/弹窗会渲染成无样式——症状看起来像逻辑 bug。
   照 MasterCsvImport.vue 的先例在本组件内重新声明。
   `data-table` / `mono` / `status-tag` / `empty-row` / `empty-state` / `pagination`
   在 src/style.css 里是全局的，不必抄；下面这些是 Finance.vue 自己的。
   `.table-card` 只抄 margin-bottom：背景与圆角来自全局那条同名规则。 */
.table-card { margin-bottom: 1rem; }
.filter-bar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-select, .filter-date { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover { border-color: var(--color-primary); }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.sync-btn { padding: 0.25rem 0.75rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; }
.sync-btn:hover:not(:disabled) { background: var(--color-primary-dark); }
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: 0.8125rem; color: var(--color-muted); }
.summary-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }
.summary-value.small { font-size: 1rem; font-weight: 600; }
.detail-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0.5rem 1.5rem; margin-bottom: 0.75rem; }
.detail-grid > div { display: flex; justify-content: space-between; gap: 1rem; font-size: 0.8125rem; padding: 0.25rem 0; border-bottom: 1px solid var(--color-border); }
.panel-title { display: flex; align-items: center; gap: 0.5rem; font-size: 0.9375rem; font-weight: 600; color: var(--color-on-surface); margin-bottom: 0.5rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.reconcile { background: var(--color-surface); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 1rem; margin-bottom: 1rem; }
.recon-head { font-weight: 600; margin-bottom: 0.5rem; color: var(--color-on-surface); }
.recon-grid { display: flex; gap: 1.5rem; flex-wrap: wrap; font-size: 0.8125rem; color: var(--color-muted); }
.recon-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-top: 0.75rem; }
.recon-sub { font-size: 0.8125rem; color: var(--color-on-surface); margin-bottom: 0.25rem; font-weight: 600; }
.recon-line { font-size: 0.8125rem; color: var(--color-muted); font-family: var(--font-mono); }
.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }
.profit-flat { color: var(--color-muted); font-weight: 600; }
.profit-note { padding: 0.75rem 1rem; background: var(--color-surface); color: var(--color-muted); border-radius: var(--radius-md); font-size: 0.8125rem; line-height: 1.6; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-background); color: var(--color-on-surface); }

@media (max-width: 768px) { .recon-cols { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } }
</style>
