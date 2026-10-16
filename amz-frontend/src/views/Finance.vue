<template>
  <div class="finance-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">财务管理</h1>
        <p class="hero-subtitle">业财一体化：凭证与金蝶同步 / 回款对账 / 结算原表 / 费用差异 / 亚马逊索赔 / 单品利润 / VAT</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看财务数据。
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <!-- 维度切换 -->
      <div class="dim-tabs">
        <button v-for="t in TABS" :key="t.key" :class="['dim-tab', { active: tab === t.key }]"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <!-- 凭证列表 -->
      <div v-show="tab === 'voucher'">
        <div class="filter-bar">
          <select v-model="filterSourceType" class="filter-select">
            <option value="">全部类型</option>
            <option value="ORDER">订单收入</option>
            <option value="PROCUREMENT">采购成本</option>
            <option value="PLATFORM_FEE">平台费用</option>
            <option value="REFUND">退款</option>
          </select>
          <button class="filter-btn" @click="loadVouchers(false)">查询</button>
        </div>

        <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="table-card sk-table-card">
            <div v-for="i in 5" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
          </div>
        </div>

        <template v-if="!loading">
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>凭证号</th>
                <th>类型</th>
                <th>原币金额</th>
                <th>CNY 金额</th>
                <th>店铺</th>
                <th>金蝶状态</th>
                <th>业务日期</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in vouchers" :key="v.id">
                <td class="mono">{{ v.voucherNo }}</td>
                <td><span class="status-tag" :class="sourceTypeClass(v.sourceType)">{{ sourceTypeText(v.sourceType) }}</span></td>
                <td>{{ v.originalAmount }} {{ v.currency }}</td>
                <td class="mono">¥{{ v.cnyAmount }}</td>
                <td>{{ v.shopId }}</td>
                <td><span class="status-tag" :class="syncStatusClass(v.kingdeeSyncStatus)">{{ syncStatusText(v.kingdeeSyncStatus) }}</span></td>
                <td class="mono">{{ v.bizDate }}</td>
                <td><button class="sync-btn" :disabled="syncing === v.id || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '把这张凭证同步到金蝶' : `同步金蝶${WRITE_LOCKED_HINT}`"
                  @click="handleSync(v)">{{ syncing === v.id ? '同步中...' : '同步金蝶' }}</button></td>
              </tr>
              <tr v-if="!loading && vouchers.length === 0">
                <td colspan="8" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:book-open-outline" width="32" class="empty-icon" />
                    <span>{{ voucherEmptyText }}</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pagination">
          <span class="page-info">
            已加载 {{ vouchers.length }} 条<template v-if="truncated">，仍有更多未加载</template>
          </span>
          <div class="page-actions">
            <button v-if="truncated" class="page-btn" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中...' : '加载更多' }}
            </button>
          </div>
        </div>

        <div v-if="truncated" class="truncated-tip" role="status">
          结果已被服务端截断：当前列表不是全量。对账、导出或金额汇总请继续翻页取完，
          不要直接拿这一页当完整数据。
        </div>
        </template>
      </div>

      <!-- 利润查询 -->
      <div v-show="tab === 'profit'">
        <div class="filter-bar">
          <input type="date" v-model="profitStart" class="filter-date" />
          <span class="range-sep">至</span>
          <input type="date" v-model="profitEnd" class="filter-date" />
          <button class="filter-btn" @click="loadProfit">查询利润</button>
        </div>

        <div v-if="profitLoading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="summary-grid">
            <div v-for="i in 2" :key="i" class="summary-card">
              <div class="skeleton sk-line sk-line-sm"></div>
              <div class="skeleton sk-line sk-line-lg"></div>
            </div>
          </div>
        </div>

        <div v-if="!profitLoading" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">店铺利润（CNY）</div>
            <div class="summary-value" :class="profitError || profitNum === null ? 'profit-flat' : profitNum > 0 ? 'profit-positive' : profitNum < 0 ? 'profit-negative' : 'profit-flat'">{{ profitError ? '—' : '¥' + profitDisplay }}<span v-if="profitError">（{{ profitError }}）</span></div>
          </div>
          <div class="summary-card">
            <div class="summary-label">统计区间</div>
            <div class="summary-value small">{{ profitStart || '不限' }} ~ {{ profitEnd || '不限' }}</div>
          </div>
        </div>

        <div class="profit-note">
          利润 = 订单收入(ORDER) - 采购成本(PROCUREMENT) - 平台费用(PLATFORM_FEE) - 退款(REFUND)，按借贷方向汇总，单位 CNY。
        </div>
      </div>
      <!-- 回款对账 + 结算原表：数据与 loader 在子组件内，靠 tab prop 决定渲染哪个 -->
      <FinanceSettlementOps
        ref="settlementOps"
        :tab="tab === 'collection' || tab === 'settlement' ? tab : null"
        @confirm="confirm"
      />

      <!-- 费用差异 + 亚马逊索赔：数据、loader 与登记表单都在子组件内 -->
      <FinanceClaimFlow
        ref="claimFlow"
        :tab="tab === 'discrepancy' || tab === 'claim' ? tab : null"
        @confirm="confirm"
      />

      <!-- 单品利润 -->
      <div v-if="tab === 'skuprofit'" class="tab-panel" data-panel="skuprofit">
        <div class="filter-bar">
          <input type="date" v-model="spAfter" class="filter-date" />
          <span class="range-sep">至</span>
          <input type="date" v-model="spBefore" class="filter-date" />
          <input v-model="spSku" class="filter-date" placeholder="指定 SKU（可空）" />
          <button class="filter-btn" :disabled="spLoading" @click="runSkuProfit">计算单品利润</button>
        </div>

        <div v-if="sp" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">SKU 数</div>
            <div class="summary-value small">{{ sp.skuCount ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">合计收入</div>
            <div class="summary-value small">{{ sp.totals?.revenue ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">合计利润</div>
            <div class="summary-value small" :class="Number(sp.totals?.profit) < 0 ? 'profit-negative' : 'profit-positive'">{{ sp.totals?.profit ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">成本不全的 SKU</div>
            <div class="summary-value small" :class="sp.costDataComplete ? '' : 'profit-negative'">{{ sp.incompleteSkuCount ?? 0 }}</div>
          </div>
        </div>
        <p v-if="sp && !sp.costDataComplete" class="profit-note">
          有 {{ sp.incompleteSkuCount ?? 0 }} 个 SKU 的采购成本取不到（批次缺失或采购域降级），
          它们的 profit 不是确定值；下方按后端 profitIsComplete 标记显示，不补数。
        </p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>SKU</th><th>销量</th><th>收入</th><th>佣金</th><th>配送费</th><th>仓储费</th>
                <th>退款</th><th>赔付</th><th>成本</th><th>利润</th><th>单件利润</th><th>毛利率</th><th>数据完整</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in sp?.entries || []" :key="e.sku">
                <td class="mono">{{ e.sku }}</td>
                <td>{{ e.unitsSold ?? 0 }}</td>
                <td>{{ e.revenue }}</td>
                <td>{{ e.commission }}</td>
                <td>{{ e.fulfillmentFee }}</td>
                <td>{{ e.storageFee }}</td>
                <td>{{ e.refunded }}</td>
                <td>{{ e.reimbursed }}</td>
                <td>{{ e.costMissing ? '缺' : e.cogs }}</td>
                <td :class="Number(e.profit) < 0 ? 'profit-negative' : ''">{{ e.profit }}</td>
                <td>{{ e.profitPerUnit ?? '-' }}</td>
                <td>{{ e.marginRate ?? '-' }}</td>
                <td>
                  <span class="status-tag" :class="e.profitIsComplete ? 'sync-synced' : 'sync-failed'">
                    {{ e.profitIsComplete ? '完整' : '不完整' }}
                  </span>
                </td>
              </tr>
              <tr v-if="sp && (sp.entries || []).length === 0">
                <td colspan="13" class="empty-row">该区间没有销量记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- VAT 与凭证补数 -->
      <div v-if="tab === 'tax'" class="tab-panel" data-panel="tax">
        <div class="table-card">
          <div class="panel-title">VAT 试算</div>
          <div class="filter-bar">
            <input v-model="vatCountry" class="filter-date" placeholder="国家码 US/DE/UK" />
            <input v-model="vatAmount" type="number" class="filter-date" placeholder="金额" />
            <button class="filter-btn" :disabled="busy" @click="runVat('calc')">算 VAT</button>
            <button class="filter-btn" :disabled="busy" @click="runVat('rate')">查税率</button>
            <button class="filter-btn" :disabled="busy" @click="runVat('threshold')">超门槛检查</button>
          </div>
          <p class="vat-line">{{ vatResult || '后端返回的是给人看的字符串，这里原样显示，不在前端拆成数字。' }}</p>
        </div>

        <div class="table-card">
          <div class="panel-title">月结与凭证补数</div>
          <div class="filter-bar">
            <input v-model="closeYear" type="number" class="filter-date" placeholder="年" />
            <input v-model="closeMonth" type="number" class="filter-date" placeholder="月" />
            <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '执行指定月份的 VAT 月结' : `VAT 月结${WRITE_LOCKED_HINT}`" @click="confirm(monthlyCloseConfirm)">执行 VAT 月结</button>
          </div>
          <div class="filter-bar">
            <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '按结算明细补生成凭证' : `补结算凭证${WRITE_LOCKED_HINT}`" @click="runSettlementVouchers">补结算扣费/退款凭证</button>
            <button class="filter-btn" :disabled="busy || !financeWriteAllowed"
                  :title="financeWriteAllowed ? '按采购单补生成凭证' : `补采购凭证${WRITE_LOCKED_HINT}`" @click="runProcurementVouchers">补采购成本凭证</button>
            <button class="filter-btn" :disabled="busy" @click="reloadVouchers">回读凭证列表</button>
          </div>
          <dl v-if="svReport" class="kv-grid">
            <dt>扫描结算行</dt><dd>{{ svReport.scanned ?? 0 }}</dd>
            <dt>费用凭证</dt><dd>{{ svReport.feeVouchers ?? 0 }}</dd>
            <dt>退款凭证</dt><dd>{{ svReport.refundVouchers ?? 0 }}</dd>
            <dt>幂等命中</dt><dd>{{ svReport.existing ?? 0 }}</dd>
            <dt>跳过（类型/无币种/零金额）</dt><dd>{{ svReport.skippedByKind ?? 0 }} / {{ svReport.skippedNoCurrency ?? 0 }} / {{ svReport.skippedZeroAmount ?? 0 }}</dd>
            <dt>是否还有未处理</dt><dd :class="svReport.capped ? 'profit-negative' : ''">{{ svReport.capped ? '是，需再跑一次' : '否' }}</dd>
          </dl>
          <dl v-if="pvReport" class="kv-grid">
            <dt>读到的采购单</dt><dd>{{ pvReport.scanned ?? 0 }}（{{ pvReport.pagesRead ?? 0 }} 页）</dd>
            <dt>新凭证</dt><dd>{{ pvReport.generated ?? 0 }}</dd>
            <dt>幂等命中</dt><dd>{{ pvReport.existing ?? 0 }}</dd>
            <dt>跳过（无单号/零金额）</dt><dd>{{ pvReport.skippedNoOrderNo ?? 0 }} / {{ pvReport.skippedZeroAmount ?? 0 }}</dd>
            <dt>采购域状态</dt><dd :class="pvReport.remoteDegraded ? 'profit-negative' : ''">
              {{ pvReport.remoteDegraded ? `降级：${pvReport.remoteMessage || '读不到'}（结果不完整，别当成没有成本）` : '读取正常' }}
            </dd>
          </dl>
          <div v-if="closeResult" class="muted">{{ JSON.stringify(closeResult) }}</div>
        </div>
      </div>

      <!-- 确认弹窗：状态（confirmBox / runConfirm）留在本组件，弹窗只渲染并回传事件 -->
      <FinanceConfirmBox
        :box="confirmBox"
        :busy="busy"
        :write-allowed="financeWriteAllowed"
        @cancel="confirmBox = null"
        @confirm="runConfirm"
      />

    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import FinanceConfirmBox from '../components/FinanceConfirmBox.vue'
import FinanceClaimFlow from '../components/FinanceClaimFlow.vue'
import FinanceSettlementOps from '../components/FinanceSettlementOps.vue'
import { useFinanceErrors } from '@/composables/useFinanceErrors'
import { useFinanceApi } from '@/composables/useFinanceApi'
import { listVouchers, syncToKingdee, calculateProfit } from '@/api/finance'
import type { AccountingVoucher } from '@/api/finance'
import { useShopGuard } from '@/composables/useShopGuard'
import { WRITE_LOCKED_HINT } from '@/utils/identity'
import { useToast } from '@/composables/useToast'
import * as ext from '@/api/finance-ext'
import type {
  SkuProfitReport, SettlementVoucherReport, ProcurementVoucherReport
} from '@/api/finance-ext'

const tab = ref<TabKey>('voucher')
// B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值
const { currentShopId, refreshShop } = useShopGuard()

// 凭证列表
const loading = ref(false)
const vouchers = ref<AccountingVoucher[]>([])
const filterSourceType = ref('')
const syncing = ref<number | null>(null)
const { showToast } = useToast()

// 服务端游标分页状态。
// 之前是「后端最多给 500 条 + 前端本地分页」：前端会显示「共 500 条」并让用户翻页，
// 但那 500 条本身就是被截断的结果 —— 用户拿到的是一份看起来完整、实际漏单的列表。
// 现在本地不再分页：只展示已加载的行，还有没有下一页由服务端 _page 说了算。
const PAGE_SIZE = 50
const nextCursor = ref<string | null>(null)
const truncated = ref(false)
const loadingMore = ref(false)
/** 最近一次凭证列表请求是否失败（用于空表格区分「没数据」与「没取到」） */
const voucherLoadedFailed = ref(false)
/** 最近一次凭证列表返回是否不是数组（契约问题，与业务空数据是两回事） */
const voucherBadShape = ref(false)

/**
 * 凭证空表格的说明文案。
 * <p>
 * 四种「列表是空的」在界面上必须能区分开，因为它们要用户做的动作完全不同：
 * <ul>
 *   <li>没选店铺 → 去右上角选店（否则发了请求也是空）；</li>
 *   <li>请求失败 / 返回结构异常 → 看顶部错误区，重试；</li>
 *   <li>真的一张凭证都没有 → 去「VAT 与凭证补数」页生成；</li>
 * </ul>
 * 此前只显示「暂无凭证数据」，而失败分支同样会清空 vouchers，于是请求失败
 * 与「店铺确实没凭证」在界面上完全一样——用户会以为没数据，其实是被吞掉的错误。
 */
const voucherEmptyText = computed(() => {
  if (!shop()) return '尚未选择店铺：请先在右上角选择店铺，否则请求不到该店凭证。'
  if (voucherLoadedFailed.value) return '凭证列表未取到（原因见顶部错误区）：这不是「没有凭证」，请重试或换筛选条件。'
  if (voucherBadShape.value) return '凭证列表返回的结构不是数组：这是接口契约问题，请把顶部错误信息反馈给开发。'
  return '该店铺暂无凭证：可到「VAT 与凭证补数」页补结算扣费/退款凭证或采购成本凭证。'
})

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'voucher', label: '凭证列表' },
  { key: 'profit', label: '利润查询' },
  { key: 'collection', label: '回款对账' },
  { key: 'settlement', label: '结算原表' },
  { key: 'discrepancy', label: '费用差异' },
  { key: 'claim', label: '亚马逊索赔' },
  { key: 'skuprofit', label: '单品利润' },
  { key: 'tax', label: 'VAT 与凭证补数' }
]

/**
 * 拉取凭证。
 *
 * @param append true 表示按当前 cursor 追加下一页；false 表示重新从首页加载。
 */
const loadVouchers = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    vouchers.value = []
    // 没选店铺不是失败：错误区不该为此亮起，但空表格必须说明原因
    voucherLoadedFailed.value = false
    voucherBadShape.value = false
    return
  }
  if (append) {
    loadingMore.value = true
  } else {
    loading.value = true
  }
  try {
    const cursor = append ? (nextCursor.value ?? undefined) : undefined
    const res = await listVouchers(shopId, filterSourceType.value || undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && res.data) {
      const rows = Array.isArray(res.data) ? res.data : []
      vouchers.value = append ? vouchers.value.concat(rows) : rows
      // _page 缺失时按「未知」处理：宁可让用户再点一次加载更多，
      // 也不要默认当成「已经全了」——那正是漏单的来源。
      const page = res._page
      truncated.value = page ? page.truncated : false
      nextCursor.value = page ? page.nextCursor : null
      voucherLoadedFailed.value = false
      // 200 但 data 不是数组：契约问题。此时若把它当空数组渲染，用户看到的是
      // 「没凭证」而不是「接口坏了」——这两件事的处理方式完全不同。
      voucherBadShape.value = !Array.isArray(res.data)
    } else {
      console.warn('[Finance] 凭证列表返回异常', res)
      pushFinanceError('凭证列表', res?.message)
      voucherLoadedFailed.value = true
      voucherBadShape.value = false
      if (!append) vouchers.value = []
    }
  } catch (e) {
    console.warn('[Finance] 凭证列表调用失败', e)
    pushFinanceError('凭证列表', e)
    voucherLoadedFailed.value = true
    voucherBadShape.value = false
    if (!append) vouchers.value = []
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

const loadMore = () => {
  if (!truncated.value || loadingMore.value) return
  void loadVouchers(true)
}

const handleSync = async (v: AccountingVoucher) => {
  // POST /finance/voucher/{id}/sync 也是 OPERATOR/ADMIN，与 call() 覆盖的其余写操作同一口径
  if (financeWriteBlocked('同步金蝶')) return
  syncing.value = v.id
  try {
    const res = await syncToKingdee(v.id)
    const result = res?.data
    if (!result?.status) {
      showToast(res?.message || '金蝶同步返回异常', 'error')
      return
    }

    if (result.status === 'SYNCED') {
      showToast(result.message || '金蝶已确认凭证入账', 'success')
    } else if (result.status === 'MOCK') {
      // 模拟客户端只验证调用链，绝不把结果描述为真实入账。
      showToast(result.message || '模拟同步完成，未真实入账', 'info')
    } else if (result.status === 'SKIPPED') {
      showToast(result.message || '同步正在处理中或状态已变化，本次已跳过', 'info')
    } else {
      showToast(result.message || res?.message || '金蝶同步失败', 'error')
    }

    // 无论真实、模拟还是失败，都回读服务端状态，避免前端状态与数据库漂移。
    await loadVouchers()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '金蝶同步调用失败', 'error')
  } finally {
    syncing.value = null
  }
}

// 利润查询
const profitLoading = ref(false)
const profitStart = ref('')
const profitEnd = ref('')
const profitNum = ref<number | null>(null)
const profitError = ref('')
const profitDisplay = computed(() => {
  const n = profitNum.value
  // null = 查询失败：显示 '—'（未知）而不是 0.00（假成功）
  return typeof n === 'number' && !isNaN(n) ? n.toFixed(2) : '—'
})

const loadProfit = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    profitNum.value = 0
    return
  }
  profitLoading.value = true
  try {
    const res = await calculateProfit(shopId, profitStart.value || undefined, profitEnd.value || undefined)
    if (res?.code === 200 && res.data !== null && res.data !== undefined) {
      const raw = res.data
      const num = typeof raw === 'number' ? raw : parseFloat(String(raw))
      profitNum.value = isNaN(num) ? 0 : num
      profitError.value = ''
    } else {
      // 失败显示 '—' 并报错：置 0 会把「查询失败」伪装成「真实利润为 0」
      console.warn('[Finance] 利润查询返回异常', res)
      profitNum.value = null
      profitError.value = res?.message || '后端返回非 200'
    }
  } catch (e: any) {
    console.warn('[Finance] 利润查询调用失败', e)
    profitNum.value = null
    profitError.value = e?.message || '网络异常'
  } finally {
    profitLoading.value = false
  }
}

const sourceTypeText = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: '订单收入',
    PROCUREMENT: '采购成本',
    PLATFORM_FEE: '平台费用',
    REFUND: '退款'
  }
  return map[t] || t
}

const sourceTypeClass = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: 'src-order',
    PROCUREMENT: 'src-proc',
    PLATFORM_FEE: 'src-fee',
    REFUND: 'src-refund'
  }
  return map[t] || 'src-other'
}

const syncStatusText = (s: string): string => {
  const map: Record<string, string> = {
    PENDING: '待同步',
    SYNCING: '同步中',
    SYNCED: '已入账',
    MOCK: '模拟未入账',
    FAILED: '失败'
  }
  return map[s] || s || '未知'
}

const syncStatusClass = (s: string): string => {
  const map: Record<string, string> = {
    PENDING: 'sync-pending',
    SYNCED: 'sync-synced',
    SYNCING: 'sync-syncing',
    MOCK: 'sync-mock',
    FAILED: 'sync-failed'
  }
  return map[s] || 'sync-pending'
}

/* ==================== 财务运营：回款 / 结算 / 差异 / 索赔 / 单品利润 / VAT 与凭证补数 ==================== */

type TabKey = 'voucher' | 'profit' | 'collection' | 'settlement' | 'discrepancy' | 'claim' | 'skuprofit' | 'tax'

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

/**
 * 回款 / 结算两个分区的 loader 在子组件里（数据和游标也在那），
 * 这里只拿它的方法调（见 TAB_LOADERS 与 onMounted 的注释）。
 */
const settlementOps = ref<{
  loadCollections: (append?: boolean) => void
  loadCollectionSummary: () => Promise<boolean>
  loadSettlements: (append?: boolean) => void
} | null>(null)

/** 差异 / 索赔两个分区的 loader 同理在 `FinanceClaimFlow` 里。 */
const claimFlow = ref<{
  loadDiscrepancies: (append?: boolean) => void
  loadShortages: () => Promise<void>
  loadClaims: (append?: boolean) => void
  loadClaimSummary: () => Promise<boolean>
} | null>(null)

const sp = ref<SkuProfitReport | null>(null)
const spAfter = ref('')
const spBefore = ref('')
const spSku = ref('')
const spLoading = ref(false)

const vatCountry = ref('US')
const vatAmount = ref<number | string>(100)
const vatResult = ref('')
const closeYear = ref(new Date().getFullYear())
const closeMonth = ref(new Date().getMonth() + 1)
const closeResult = ref<Record<string, unknown> | null>(null)
const svReport = ref<SettlementVoucherReport | null>(null)
const pvReport = ref<ProcurementVoucherReport | null>(null)

const { errors, pushError, pushFinanceError } = useFinanceErrors()
const { busy, financeWriteAllowed, financeWriteBlocked, read, call, installRoleListener, removeRoleListener } = useFinanceApi()
const shop = () => refreshShop()

/**
 * 提示串里的数值兜底（本组件只剩 VAT 补数两处 toast 在用）。
 * 刻意不换成 `utils/format.ts` 的 `asNumber`：后者对非法值返回 fallback，
 * 这里保留原值原样显示，语义不同；改共享 util 不该混在一次拆分里。
 */
const d0 = (v: unknown) => Number(v ?? 0)

/* ---------- 单品利润 ---------- */
const runSkuProfit = async () => {
  spLoading.value = true
  try {
    // 走 read 不是笔误：GET /finance/profit/sku/{shopId} 后端没有 @RequireRole
    // （SkuProfitController 注释明写「VIEWER 亦可查看」）——VIEWER 查得到。
    await read('单品利润', () => ext.skuProfit(shop(), {
      depositAfter: spAfter.value || undefined,
      depositBefore: spBefore.value || undefined,
      sku: spSku.value || undefined
    }), (d) => { sp.value = d })
  } finally {
    spLoading.value = false
  }
}

/* ---------- VAT 与凭证补数 ---------- */
const runVat = async (mode: 'calc' | 'rate' | 'threshold') => {
  const country = vatCountry.value.trim()
  if (!country) { pushError('VAT：请先填国家码'); return }
  const amount = Number(vatAmount.value)
  if (mode !== 'rate' && !Number.isFinite(amount)) { pushError('VAT：金额必须是数字'); return }
  const label = mode === 'calc' ? 'VAT 试算' : mode === 'rate' ? 'VAT 税率' : 'VAT 门槛检查'
  const fn = mode === 'calc' ? () => ext.calculateVat(amount, country)
    : mode === 'rate' ? () => ext.vatRate(country)
      : () => ext.vatThreshold(country, amount)
  // 这三个 VAT 端点是试算/查税率/查门槛，后端只加 @ShopScoped 没有 @RequireRole
  // （VatController:26/33/39），VIEWER 算得动，所以走 read。
  // 同 tab 的「执行 VAT 月结」才是 POST + @RequireRole，那个走 call 照旧拦。
  await read(label, fn, (text) => { vatResult.value = String(text ?? '') })
}

const monthlyCloseConfirm = {
  title: '执行 VAT 月结',
  detail: '为该店铺该月生成 VAT 计提凭证（后端幂等：同月已有凭证则跳过）。年份/月份请确认无误。',
  run: async () => {
    const y = Number(closeYear.value)
    const m = Number(closeMonth.value)
    if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) {
      pushError('VAT 月结：年份或月份非法'); return
    }
    const ok = await call('VAT 月结', () => ext.vatMonthlyClose(shop(), y, m), (d) => { closeResult.value = d as Record<string, unknown> })
    if (ok) await reloadVouchers()
  }
}

const runSettlementVouchers = async () => {
  const ok = await call('补结算凭证', () => ext.generateSettlementVouchers(shop()), (d) => { svReport.value = d })
  if (ok) {
    if (svReport.value?.capped) showToast('已命中扫描上限，仍有结算行未处理，请再跑一次', 'info')
    else showToast(`结算凭证补数：费用 ${d0(svReport.value?.feeVouchers)} 张 / 退款 ${d0(svReport.value?.refundVouchers)} 张`, 'success')
    await reloadVouchers()
  }
}

const runProcurementVouchers = async () => {
  const ok = await call('补采购凭证', () => ext.generateProcurementVouchers(shop()), (d) => { pvReport.value = d })
  if (ok) {
    if (pvReport.value?.remoteDegraded) showToast(`采购域未读到数据：${pvReport.value.remoteMessage || '原因未知'}（结果不完整）`, 'error')
    else showToast(`采购凭证补数：新增 ${d0(pvReport.value?.generated)} 张 / 幂等命中 ${d0(pvReport.value?.existing)} 张`, 'success')
    await reloadVouchers()
  }
}

/** 凭证列表由本页既有逻辑负责，补数完必须回读，避免前端显示与库内状态漂移 */
const reloadVouchers = async () => { await loadVouchers(false) }

const confirm = (box: { title: string; detail: string; run: () => Promise<void> }) => { confirmBox.value = box }
const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

/**
 * 子组件持有各自分区数据与 loader 的，这里调它 expose 出来的方法
 * （`FinanceSettlementOps` 管回款/结算，`FinanceClaimFlow` 管差异/索赔）；
 * 其余分区仍在本组件。**「首次进入才拉」的把关只在这里**，子组件不重复把关。
 */
const TAB_LOADERS: Record<TabKey, () => Promise<unknown> | void> = {
  voucher: () => undefined,
  profit: () => undefined,
  collection: async () => {
    await Promise.all([settlementOps.value?.loadCollections(), settlementOps.value?.loadCollectionSummary()])
  },
  settlement: () => settlementOps.value?.loadSettlements(),
  discrepancy: async () => {
    await Promise.all([claimFlow.value?.loadDiscrepancies(), claimFlow.value?.loadShortages()])
  },
  claim: async () => {
    await Promise.all([claimFlow.value?.loadClaims(), claimFlow.value?.loadClaimSummary()])
  },
  skuprofit: () => undefined,
  tax: () => undefined
}

/** 每个分区首次进入才拉数据：财务端点重，全压在挂载上会拖慢首屏 */
const loadedTabs = new Set<TabKey>(['voucher'])
const gotoTab = async (t: TabKey) => {
  tab.value = t
  if (loadedTabs.has(t)) return
  loadedTabs.add(t)
  await TAB_LOADERS[t]()
}

onMounted(() => {
  errors.value = []          // 整页入口清一次；loader 只按各自前缀追加/移除
  installRoleListener()      // 角色变更监听只由页壳装一次，子组件不要各自装（见 useFinanceApi 注释）
  void loadVouchers(false)
})
onUnmounted(() => {
  removeRoleListener()
})
</script>

<style scoped>
.finance-page { background: var(--color-background); }
/* ---- 财务运营分区 ---- */
.panel-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); margin-bottom: 0.5rem; }
.kv-grid { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0.75rem 0 0; font-size: 0.8125rem; }
.kv-grid dt { color: var(--color-muted); }
.kv-grid dd { margin: 0; color: var(--color-on-surface); word-break: break-all; }
.vat-line { font-size: 0.8125rem; color: var(--color-on-surface); background: var(--color-background); border-radius: var(--radius-sm); padding: 0.5rem 0.75rem; margin: 0.5rem 0 0; }
.muted { color: var(--color-muted); font-size: 0.75rem; }

/* 页头/主区/表格等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 60%; height: 1.5rem; margin-top: 0.5rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.dim-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.dim-tab { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; font-size: 0.875rem; color: var(--color-muted); transition: all 0.2s; }
.dim-tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }

.filter-bar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-select, .filter-date { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover { border-color: var(--color-primary); }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.range-sep { color: var(--color-muted); font-size: 0.875rem; margin: 0 0.5rem; }

.src-order { background: var(--color-primary-light); color: var(--color-primary); }
.src-proc { background: var(--color-muted-light); color: var(--color-muted); }
.src-fee { background: var(--color-muted-light); color: var(--color-muted); }
.src-refund { background: var(--color-light-red); color: var(--color-error); }
.src-other { background: var(--color-surface); color: var(--color-muted); }

/* 同步状态语义色：SYNCED=成功 / PENDING=待处理 / FAILED=失败 */
.sync-pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-synced { background: var(--color-success-light); color: var(--color-success); }
.sync-syncing { background: var(--color-primary-light); color: var(--color-primary); }
.sync-mock { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-failed { background: var(--color-light-red); color: var(--color-error); }

.sync-btn { padding: 0.25rem 0.75rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; }
.sync-btn:hover:not(:disabled) { background: var(--color-primary-dark); }
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: 0.8125rem; color: var(--color-muted); }
.summary-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }
.panel-title { display: flex; align-items: center; gap: 0.5rem; font-size: 0.9375rem; font-weight: 600; color: var(--color-on-surface); margin-bottom: 0.5rem; }
.summary-value.small { font-size: 1rem; font-weight: 600; }

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }
/* 零利润中性展示，避免误读为亏损 */
.profit-flat { color: var(--color-muted); font-weight: 600; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.truncated-tip { margin-top: 0.75rem; padding: 0.625rem 0.875rem; border-radius: var(--radius-md); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; line-height: 1.6; }
.profit-note { padding: 0.75rem 1rem; background: var(--color-surface); color: var(--color-muted); border-radius: var(--radius-md); font-size: 0.8125rem; line-height: 1.6; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .summary-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .filter-bar { flex-wrap: wrap; } .summary-grid { grid-template-columns: 1fr; } }
</style>