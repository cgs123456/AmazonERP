<script setup lang="ts">
import { ref } from 'vue'
import Icon from './Icon.vue'
import FinanceCursorBar from './FinanceCursorBar.vue'
import { makeCursor, useCursorLoader } from '@/composables/useCursorList'
import { useFinanceApi } from '@/composables/useFinanceApi'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'
import { WRITE_LOCKED_HINT } from '@/utils/identity'
import * as ext from '@/api/finance-ext'
import { COLLECTION_STATUS } from '@/api/finance-ext'
import type {
  PaymentCollection, PaymentCollectionSummary, SettlementDetail, SettlementIngestReport
} from '@/api/finance-ext'

/**
 * 回款对账 + 结算原表两个分区。数据与 loader 都在本组件内。
 *
 * ## 为什么用 `tab` prop 而不是 `v-if` 由父组件挂卸载
 *
 * 清单数据必须活过「切走再切回」。原实现在 Finance.vue 里用 `v-if` 渲染面板、
 * 数据放在父组件的 ref 里：切走时面板卸载但数据还在，切回来直接显示上次的结果。
 * 若把数据搬进本组件再让父组件 `v-if` 挂卸载，切回来就是**空表 + 空态文案**
 * （「暂无回款记录：需先同步结算并重算回款」）——那是主动误导，不是没数据。
 *
 * 所以本组件**始终挂载**，靠 `tab` prop 决定渲染哪个面板（内部 `v-if`，
 * DOM 只在该 tab 激活时存在，不影响任何按选择器取元素的测试）。
 *
 * ## 首次进入才加载的把关仍在 Finance.vue（本组件**不 watch** tab）
 *
 * 财务端点重，`loadedTabs` 那套「首次进入才拉」的机制留在页壳，由它调本组件
 * `defineExpose` 出去的 `load(tab)`。**不要把把关也放进本组件**：两处把关就是
 * 两套「已加载」集合，改一处漏一处的症状是重复请求或永远不请求，而
 * `FinanceOps.test.ts` 的「挂载只拉凭证…切回不重拉」只守得住一处。
 * 本组件因此只渲染，不自己决定什么时候去拉。
 */
const props = defineProps<{
  /** 当前激活的 tab；不属于本组件时为 null。 */
  tab: 'collection' | 'settlement' | null
}>()

/** 把确认框交给页壳：弹窗状态、`confirmBox` 与 `runConfirm` 都在 Finance.vue。 */
const emit = defineEmits<{ confirm: [box: { title: string; detail: string; run: () => Promise<void> }] }>()

const PAGE = 50
const { busy, financeWriteAllowed, read, call } = useFinanceApi()
const { loadCursor } = useCursorLoader()
const { refreshShop } = useShopGuard()
const { showToast } = useToast()

const shop = () => refreshShop()

/* ---------- 回款 ---------- */
const col = makeCursor<PaymentCollection>()
const colStatus = ref('')
const colSummary = ref<PaymentCollectionSummary | null>(null)

const loadCollections = (append = false) => {
  void loadCursor(col, '回款列表', cursor => ext.listCollections(shop(), { status: colStatus.value || undefined, size: PAGE, cursor }), append)
}
const loadCollectionSummary = () =>
  read('回款汇总', () => ext.collectionSummary(shop()), (d) => { colSummary.value = d })

const rebuildConfirm = {
  title: '重算回款',
  detail: '从结算原表重新聚合每个订单的应回 / 扣费 / 退款 / 实收。结算原表若还没同步，重算只会得到空结果。',
  run: async () => {
    const ok = await call('重算回款', () => ext.rebuildCollections(shop()), (n) => showToast(`重算完成，影响 ${n} 个订单`, 'success'))
    if (ok) {
      await Promise.all([loadCollections(), loadCollectionSummary()])
    }
  }
}

/* ---------- 结算 ---------- */
const settle = makeCursor<SettlementDetail>()
const settleOrder = ref('')
const ingest = ref<SettlementIngestReport | null>(null)

const loadSettlements = (append = false) => {
  void loadCursor(settle, '结算明细', cursor =>
    ext.listSettlements(shop(), { amazonOrderId: settleOrder.value || undefined, size: PAGE, cursor }), append)
}
const settlementSyncConfirm = {
  title: '从 SP-API 同步结算报表',
  detail: '会向亚马逊发起真实报表请求（创建报告 + 拉取 + 入库），数据量大时耗时较长；重复行按业务指纹跳过，不会重复入账。',
  run: async () => {
    const ok = await call('结算同步', () => ext.syncSettlement(shop()), (d) => { ingest.value = d })
    if (ok) await loadSettlements()
  }
}

/** 供页壳调用的 loader（确认框里的重活也要用，故 rebuild/sync 的 confirm 对象一并出去） */
defineExpose({ loadCollections, loadCollectionSummary, loadSettlements, rebuildConfirm, settlementSyncConfirm })
</script>

<template>
  <div v-if="props.tab === 'collection'" class="tab-panel" data-panel="collection">
    <div class="filter-bar">
      <select v-model="colStatus" class="filter-select">
        <option value="">全部状态</option>
        <option v-for="s in COLLECTION_STATUS" :key="s" :value="s">{{ s }}</option>
      </select>
      <button class="filter-btn" :disabled="col.loading.value" @click="loadCollections(false)">刷新</button>
      <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
              :title="financeWriteAllowed ? '从结算原表重新聚合回款' : `重算回款${WRITE_LOCKED_HINT}`" @click="emit('confirm', rebuildConfirm)">重算回款</button>
    </div>

    <div class="summary-grid">
      <div class="summary-card">
        <div class="summary-label">应回合计</div>
        <div class="summary-value small">¥{{ colSummary?.receivableTotal ?? '-' }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">实收合计</div>
        <div class="summary-value small">¥{{ colSummary?.netReceivedTotal ?? '-' }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">在途金额</div>
        <div class="summary-value small">¥{{ colSummary?.inTransitAmount ?? '-' }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">短款（需追）</div>
        <div class="summary-value small">{{ colSummary?.shortfallOrders ?? 0 }} 单 / ¥{{ colSummary?.shortfallAmount ?? '-' }}</div>
      </div>
    </div>
    <p v-if="colSummary?.warnings?.length" class="profit-note">口径提示：{{ colSummary.warnings.join('；') }}</p>

    <div class="table-card">
      <table class="data-table">
        <thead>
          <tr>
            <th>亚马逊订单号</th><th>币种</th><th>应回</th><th>平台扣费</th><th>退款</th>
            <th>赔付</th><th>实收</th><th>短款</th><th>到账日</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in col.rows.value" :key="c.id">
            <td class="mono">{{ c.amazonOrderId }}</td>
            <td>{{ c.currency || '-' }}</td>
            <td>{{ c.receivable }}</td>
            <td>{{ c.feeDeducted }}</td>
            <td>{{ c.refunded }}</td>
            <td>{{ c.reimbursed }}</td>
            <td>{{ c.netReceived }}</td>
            <td :class="(Number(c.shortfall) || 0) > 0 ? 'profit-negative' : ''">{{ c.shortfall ?? 0 }}</td>
            <td class="mono">{{ c.depositDate || '未到账' }}</td>
            <td><span class="status-tag">{{ c.status }}</span></td>
          </tr>
          <tr v-if="!col.loading.value && col.rows.value.length === 0">
            <td colspan="10" class="empty-row">
              <div class="empty-state">
                <Icon icon="mdi:bank-outline" width="32" class="empty-icon" />
                <span>暂无回款记录：需先同步结算并「重算回款」</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <FinanceCursorBar :state="col" :load="loadCollections" />
  </div>

  <div v-if="props.tab === 'settlement'" class="tab-panel" data-panel="settlement">
    <div class="filter-bar">
      <input v-model="settleOrder" class="filter-date" placeholder="按亚马逊订单号过滤" />
      <button class="filter-btn" :disabled="settle.loading.value" @click="loadSettlements(false)">刷新</button>
      <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
              :title="financeWriteAllowed ? '从 SP-API 拉取结算报告' : `同步结算${WRITE_LOCKED_HINT}`" @click="emit('confirm', settlementSyncConfirm)">从 SP-API 同步结算</button>
    </div>

    <div v-if="ingest" class="summary-grid">
      <div class="summary-card">
        <div class="summary-label">读到的报表行数</div>
        <div class="summary-value small">{{ ingest.dataLineCount ?? 0 }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">新入库</div>
        <div class="summary-value small">{{ ingest.inserted ?? 0 }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">重复跳过</div>
        <div class="summary-value small">{{ ingest.skipped ?? 0 }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">失败行</div>
        <div class="summary-value small" :class="(ingest.failed ?? 0) > 0 ? 'profit-negative' : ''">{{ ingest.failed ?? 0 }}</div>
      </div>
    </div>
    <p v-if="ingest" class="profit-note">
      「读到 / 入库 / 跳过」是三个不同的数：全部等于「读了 N 行」会让重复拉取和数据没拉到长得一样。
    </p>

    <div class="table-card">
      <table class="data-table">
        <thead>
          <tr>
            <th>结算单号</th><th>订单号</th><th>SKU</th><th>交易类型</th><th>金额类型</th>
            <th>金额</th><th>币种</th><th>到账日</th><th>来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in settle.rows.value" :key="d.id">
            <td class="mono">{{ d.settlementId }}</td>
            <td class="mono">{{ d.amazonOrderId || '-' }}</td>
            <td class="mono">{{ d.sku || '-' }}</td>
            <td>{{ d.transactionType }}</td>
            <td>{{ d.amountType || '-' }}</td>
            <td :class="Number(d.amount) < 0 ? 'profit-negative' : 'profit-positive'">{{ d.amount }}</td>
            <td>{{ d.currency || '-' }}</td>
            <td class="mono">{{ d.depositDate || '-' }}</td>
            <td>{{ d.source || '-' }}</td>
          </tr>
          <tr v-if="!settle.loading.value && settle.rows.value.length === 0">
            <td colspan="9" class="empty-row">
              <div class="empty-state">
                <Icon icon="mdi:file-document-outline" width="32" class="empty-icon" />
                <span>暂无结算明细：差异扫描与回款都依赖它，请先同步</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <FinanceCursorBar :state="settle" :load="loadSettlements" />
  </div>
</template>

<style scoped>
/* 从 Finance.vue 的 <style scoped> 抄来：scoped 样式不跨组件边界。
   `data-table` / `mono` / `status-tag` / `empty-row` / `empty-state` 在
   src/style.css 里是全局的，不必抄；下面这些是 Finance.vue 自己的。
   `.table-card` 只抄 margin-bottom：背景与圆角来自全局那条同名规则。 */
.table-card { margin-bottom: 1rem; }
.filter-bar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-select, .filter-date { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover { border-color: var(--color-primary); }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: 0.8125rem; color: var(--color-muted); }
.summary-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }
.summary-value.small { font-size: 1rem; font-weight: 600; }
.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }
.profit-note { padding: 0.75rem 1rem; background: var(--color-surface); color: var(--color-muted); border-radius: var(--radius-md); font-size: 0.8125rem; line-height: 1.6; }

@media (max-width: 768px) { .filter-bar { flex-wrap: wrap; } .summary-grid { grid-template-columns: 1fr; } }
</style>
