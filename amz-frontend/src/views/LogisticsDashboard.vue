<template>
  <div class="logistics-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
    <div v-if="blockErrors.length" class="error-zone" role="alert">
      <span>部分区块加载失败：{{ blockErrors.join('；') }}</span>
    </div>
      <div class="hero-section">
        <h1 class="hero-title">物流看板</h1>
        <p class="hero-subtitle">头程在途与异常一览</p>
      </div>

      <!-- 未登录：不发任何业务请求，避免 401 拦截器跳登录页造成重载循环 -->
      <div v-if="!hasToken" class="login-hint">
        <Icon icon="mdi:account-lock-outline" width="20" />
        <span>请先登录后查看物流数据</span>
      </div>

      <div v-else-if="!currentShopId" class="shop-tip">请先在顶部选择店铺</div>

      <template v-else>
        <!-- 取数状态条：让「数据为什么没动」有明确答案，而不是让人反复点刷新 -->
        <div v-if="overview" class="sync-bar" :class="overview.autoSyncAvailable ? 'on' : 'off'">
          <Icon
            :icon="overview.autoSyncAvailable ? 'mdi:sync' : 'mdi:sync-off'"
            width="18"
            class="sync-icon"
          />
          <span class="sync-text">
            <template v-if="overview.autoSyncAvailable">
              第三方轨迹自动同步已启用 · 最近取数 {{ fmtTime(overview.lastTrackTime) }}
            </template>
            <template v-else>
              第三方轨迹自动同步未启用（未配置凭证）· 请在下方「数据导入」维护轨迹
            </template>
          </span>
          <button class="sync-btn" :disabled="busy || loading" @click="refreshCurrent">
            <Icon icon="mdi:refresh" width="16" />
            刷新
          </button>
        </div>

        <!-- 骨架屏：形状与最终布局一致，避免加载完成时页面跳动 -->
        <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="kpi-grid">
            <div v-for="i in 4" :key="i" class="kpi-card">
              <div class="skeleton sk-icon"></div>
              <div class="sk-lines">
                <div class="skeleton sk-line sk-line-lg"></div>
                <div class="skeleton sk-line sk-line-sm"></div>
              </div>
            </div>
          </div>
          <div class="chart-card"><div class="skeleton sk-chart"></div></div>
        </div>

        <template v-else>
          <!-- 标签页 -->
          <div class="tabs" role="tablist">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="tab"
              :class="{ active: tab === t.key }"
              role="tab"
              :aria-selected="tab === t.key"
              @click="switchTab(t.key)"
            >
              {{ t.label }}
              <span v-if="t.badge" class="tab-badge" :class="t.badgeTone">{{ t.badge }}</span>
            </button>
          </div>

          <!-- 调拨面板：常驻挂载，内部按 active prop 决定渲染（见组件注释）。
               不能放进下面的 v-else-if——页壳要用 ref 调它的 load()，
               而 v-else-if 下的组件在切到该 tab 之前不存在，ref 是 null，
               懒加载就永远不会触发（症状：列表一直空、接口一次都没调）。
               与 Finance.vue 常驻挂载 FinanceSettlementOps 是同一先例。 -->
          <LogisticsTransferPanel
            ref="transferPanel"
            :active="tab === 'transfers'"
            :busy="busy"
            @report-error="pushBlockError"
          />

          <!-- ================= 在途货件 ================= -->
          <!-- 常驻挂载而不是 v-else-if：switchTab 要通过 ref 调它的 load()，
               而 v-else-if 下的组件在切到该 tab 之前不存在，ref 取到 null，
               懒加载就永远不会触发（症状：列表一直空、listShipments 一次都没调）。
               与调拨面板同一先例。 -->
          <LogisticsShipmentPanel
            ref="shipmentPanel"
            :active="tab === 'shipments'"
            :busy="busy"
            @report-error="(tag, detail) => pushBlockError(tag, detail)"
            @sync="doSync"
            @track="openTracking"
            @close="doClose"
          />

          <!-- ================= 概览 ================= -->
          <!-- 概览是这条 v-if/v-else-if 链的**头**：后面每个 tab 都是 v-else-if，
               所以头必须是 v-if。组件内部再按 active 决定渲染（与调拨面板同一先例）——
               常驻挂载而不是 v-else-if，是为了让 overview / trend 两个 ref 的
               加载把关留在页壳，不出现两套「已加载」集合。 -->
          <LogisticsOverviewPanel
            v-if="tab === 'overview'"
            :active="tab === 'overview'"
            :overview="overview"
            :trend="trend"
          />

          <!-- ================= 待处理 ================= -->
            <LogisticsAlertPanel
              v-else-if="tab === 'alerts'"
              :active="tab === 'alerts'"
              :alerts="alerts"
              :busy="busy"
              @sync="doSync"
              @track="openTracking"
              @close="doClose"
            />

          <!-- ================= 在途货件 ================= -->

          <!-- ================= 承运商 ================= -->
            <LogisticsCarrierPanel
              v-else-if="tab === 'carrier'"
              :active="tab === 'carrier'"
              :carriers="carriers"
            />

          <!-- ================= 数据导入 ================= -->

          <!-- ================= 报价与比价 ================= -->
          <section v-else-if="tab === 'quotes'">
            <LogisticsQuoteBoardPanel
              :board="quoteBoard"
              :busy="busy"
              @reload-board="safeLoad('quotes', () => loadQuoteBoard(refreshShop()))"
            />

            <LogisticsQuoteListPanel
              ref="quoteListPanel"
              :active="tab === 'quotes'"
              @report-error="(tag, detail) => pushBlockError(tag, detail)"
            />

            <LogisticsQuoteComparePanel />
          </section>

          <!-- ================= 头程成本 ================= -->
          <section v-else-if="tab === 'freight'">
            <LogisticsFreightPanel
              :board="freightBoard"
              :busy="busy"
              @reload-board="safeLoad('freight', () => loadFreightBoard(refreshShop()))"
            />
          </section>

          <!-- ================= 签收差异 ================= -->
          <section v-else-if="tab === 'receipts'">
            <LogisticsReceiptPanel
              :board="receiptBoard"
              :busy="busy"
              @investigate="doInvestigate"
              @resolve="doResolve"
            />
          </section>
        </template>

        <!-- 数据导入放在 tab 区**外面**、v-if="loading" 那对之外：
             导入成功后 onImportDone 会把 loading 置 true，tab 区随即被骨架屏整块替换，
             放在里面会让本组件卸载、importReport 丢失（症状：toast 说成功但结果面板不出现）。
             这不是假想——拆这件时实测过，改前 card=true、改后 card=false。
             常驻挂载 + 内部按 active 渲染，与调拨/在途货件同一先例。 -->
        <LogisticsImportPanel
          :active="tab === 'import'"
          :busy="busy"
          @reload-board="onImportDone"
        />
      </template>
    </main>

    <!-- 轨迹时间线浮层（渲染与样式都在子组件，loader 留在本页） -->
    <LogisticsTrackingModal
      :open="trackingOpen"
      :loading="trackingLoading"
      :timeline="timeline"
      :truncated="trackingTruncated"
      :page-meta-missing="trackingPageMetaMissing"
      :loading-more="trackingLoadingMore"
      :has-next="!!trackingNextCursor"
      @close="trackingOpen = false"
      @load-more="loadMoreTracking"
    />

    <!-- 全局 toast -->
    <Transition name="fade">
      <div v-if="toastVisible" class="toast" :class="toastType">{{ toastMessage }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import LogisticsTrackingModal from '../components/LogisticsTrackingModal.vue'
import LogisticsReceiptPanel from '../components/LogisticsReceiptPanel.vue'
import LogisticsFreightPanel from '../components/LogisticsFreightPanel.vue'
import LogisticsTransferPanel from '../components/LogisticsTransferPanel.vue'
import LogisticsQuoteComparePanel from '../components/LogisticsQuoteComparePanel.vue'
import LogisticsQuoteListPanel from '../components/LogisticsQuoteListPanel.vue'
import LogisticsOverviewPanel from '../components/LogisticsOverviewPanel.vue'
import LogisticsAlertPanel from '../components/LogisticsAlertPanel.vue'
import LogisticsShipmentPanel from '../components/LogisticsShipmentPanel.vue'
import LogisticsCarrierPanel from '../components/LogisticsCarrierPanel.vue'
import LogisticsImportPanel from '../components/LogisticsImportPanel.vue'
import LogisticsQuoteBoardPanel from '../components/LogisticsQuoteBoardPanel.vue'
import { fmtTime } from '../components/logisticsFormat'
// 物流子域面板共用的 KPI / 小指标 / 警告横幅 / 图表卡样式（scoped 不跨组件，故走全局样式表）
import '../styles/logistics-panels.css'
import { useToast } from '../composables/useToast'
import { useShopGuard } from '../composables/useShopGuard'
import {
  getLogisticsOverview,
  getLogisticsTrend,
  getCarrierPerformance,
  getLogisticsAlerts,
  syncShipment,
  closeShipment,
  getShipmentTracking,
  getQuoteBoard,
  getFreightCostBoard,
  getReceiptBoard,
  investigateDiscrepancy,
  resolveDiscrepancy,
  type DashboardOverview,
  type TrendPoint,
  type CarrierPerformance,
  type ShipmentAlert,
  type TrackingEvent,
  type QuoteBoard,
  type FreightCostBoard,
  type ReceiptBoard,
} from '../api/logistics'

const { toastVisible, toastMessage, toastType, showToast } = useToast()
const { currentShopId, refreshShop } = useShopGuard()

const hasToken = ref(!!localStorage.getItem('token'))
const loading = ref(false)
const busy = ref(false)

const overview = ref<DashboardOverview | null>(null)
const trend = ref<TrendPoint[]>([])
const carriers = ref<CarrierPerformance[]>([])
const alerts = ref<ShipmentAlert[]>([])

// 运营子域看板
const quoteBoard = ref<QuoteBoard | null>(null)
/** 逐条有效报价（与看板/比价互补的后端读侧；只含有效期内且 ACTIVE 的报价） */
const transferPanel = ref<InstanceType<typeof LogisticsTransferPanel> | null>(null)
const quoteListPanel = ref<InstanceType<typeof LogisticsQuoteListPanel> | null>(null)
const shipmentPanel = ref<InstanceType<typeof LogisticsShipmentPanel> | null>(null)
const freightBoard = ref<FreightCostBoard | null>(null)
const receiptBoard = ref<ReceiptBoard | null>(null)

/** 比价入参。重量与体积至少填一个，两者都填时服务端按计费价取高 */

type TabKey = 'overview' | 'alerts' | 'shipments' | 'carrier' | 'quotes' | 'transfers' | 'freight' | 'receipts' | 'import'

const tab = ref<TabKey>('overview')

// 轨迹浮层
const TRACKING_PAGE_SIZE = 200
const trackingOpen = ref(false)
const trackingLoading = ref(false)
const trackingShipmentId = ref<number | null>(null)
const trackingNextCursor = ref<string | null>(null)
const trackingTruncated = ref(false)
const trackingPageMetaMissing = ref(false)
const trackingLoadingMore = ref(false)
const timeline = ref<TrackingEvent[]>([])



// ============================================================
// 展示映射（状态 / 类型 / 级别）
// ============================================================














/** 金额/数量：null 与 undefined 都显示占位，避免出现 NaN 或误读成 0 */



/** 报价区间：两端相同只显示一个值，避免出现「3.50 ~ 3.50」这种噪音 */

// ============================================================
// 派生数据
// ============================================================

const tabs = computed(() => [
  { key: 'overview' as const, label: '概览', badge: '', badgeTone: '' },
  {
    key: 'alerts' as const,
    label: '待处理',
    badge: alerts.value.length ? String(alerts.value.length) : '',
    badgeTone: alerts.value.length ? 'badge-danger' : ''
  },
  { key: 'shipments' as const, label: '在途货件', badge: '', badgeTone: '' },
  { key: 'carrier' as const, label: '承运商表现', badge: '', badgeTone: '' },
  // 子域标签的角标只挂「需要有人动手」的数量，纯统计量不挂，否则角标到处都是就失去提示作用
  {
    key: 'quotes' as const,
    label: '比价',
    badge: quoteBoard.value?.staleByDate ? String(quoteBoard.value.staleByDate) : '',
    badgeTone: quoteBoard.value?.staleByDate ? 'badge-warn' : ''
  },
  {
    key: 'transfers' as const,
    label: '调拨',
    badge: transferPanel.value?.riskCount() ? String(transferPanel.value.riskCount()) : '',
    badgeTone: transferPanel.value?.riskCount() ? 'badge-danger' : ''
  },
  {
    key: 'freight' as const,
    label: '头程成本',
    badge: freightBoard.value?.uncoveredShipments ? String(freightBoard.value.uncoveredShipments) : '',
    badgeTone: freightBoard.value?.uncoveredShipments ? 'badge-warn' : ''
  },
  {
    key: 'receipts' as const,
    label: '签收差异',
    badge: receiptBoard.value?.pending ? String(receiptBoard.value.pending) : '',
    badgeTone: receiptBoard.value?.pending ? 'badge-danger' : ''
  },
  { key: 'import' as const, label: '数据导入', badge: '', badgeTone: '' }
])




// ============================================================
// 数据加载
// ============================================================

/**
 * 统一包一层加载容错。
 * 单个接口异常只记 console，不影响其余区块渲染——
 * 看板是概览视图，因一个接口报错就整页空白会让人误判为「系统挂了」。
 * 但失败必须可见：catch 里推页面级错误条，空态/旧数据不许冒充真实看板。
 */
const blockErrors = ref<string[]>([])
const pushBlockError = (tag: string, detail: unknown) => {
  const msg = detail instanceof Error ? detail.message : (detail as string | undefined) || '后端返回异常'
  const hit = blockErrors.value.find((x) => x.startsWith(`${tag}：`))
  if (hit) {
    blockErrors.value = blockErrors.value.map((x) => (x === hit ? `${tag}：${msg}` : x))
  } else {
    blockErrors.value.push(`${tag}：${msg}`)
  }
}

const safeLoad = async (tag: string, fn: () => Promise<void>) => {
  try {
    await fn()
  } catch (e) {
    console.warn(`[Logistics] ${tag} 加载失败`, e)
    pushBlockError(tag, e)
  }
}

const loadOverview = async (shopId: string) => {
  const res = await getLogisticsOverview(shopId)
  if (res?.code === 200 && res.data) {
    overview.value = res.data
  } else {
    throw new Error(res?.message || '后端返回非 200')
  }
}

/** 趋势拉取天数。与概览面板标题里的 trendDays 是两个数，见该组件注释 */
const trendDays = 30

const loadTrend = async (shopId: string) => {
  const res = await getLogisticsTrend(shopId, trendDays)
  if (res?.code === 200) {
    trend.value = res.data || []
  } else {
    throw new Error(res?.message || '后端返回非 200')
  }
}

const loadCarriers = async (shopId: string) => {
  const res = await getCarrierPerformance(shopId)
  if (res?.code === 200) {
    carriers.value = res.data || []
  } else {
    throw new Error(res?.message || '后端返回非 200')
  }
}

const loadAlerts = async (shopId: string) => {
  const res = await getLogisticsAlerts(shopId)
  if (res?.code === 200) {
    alerts.value = res.data || []
  } else {
    throw new Error(res?.message || '后端返回非 200')
  }
}


// ---- 运营子域看板 ----

const loadQuoteBoard = async (shopId: string) => {
  const res = await getQuoteBoard(shopId)
  if (res?.code === 200 && res.data) {
    quoteBoard.value = res.data
  }
}




const loadFreightBoard = async (shopId: string) => {
  const res = await getFreightCostBoard(shopId)
  if (res?.code === 200 && res.data) {
    freightBoard.value = res.data
  }
}

const loadReceiptBoard = async (shopId: string) => {
  const res = await getReceiptBoard(shopId)
  if (res?.code === 200 && res.data) {
    receiptBoard.value = res.data
  }
}



/** 加载首屏四组核心数据（概览 / 趋势 / 承运商 / 告警） */
/**
 * 导入成功后的收尾。
 *
 * 由 LogisticsImportPanel 的 reload-board 事件触发。刷新范围比页壳自己的
 * refreshCurrent 大：导入既改货件也改轨迹，概览四个接口和列表都得重拉。
 * 在途货件列表要显式再拉一次——它不是 reload() 的一部分，不拉就会停在
 * 「导入前的旧列表」，用户刚导进去的货件看不到。
 */
const onImportDone = async () => {
  await reload()
  // 强制重拉：刚导进去的货件必须出现，load() 的「已加载就跳过」会把它挡掉
  if (tab.value === 'shipments') await shipmentPanel.value?.reload()
}

const reload = async () => {
  if (!hasToken.value) return
  const shopId = refreshShop()
  if (!shopId) return

  loading.value = true
  blockErrors.value = []
  await Promise.all([
    safeLoad('overview', () => loadOverview(shopId)),
    safeLoad('trend', () => loadTrend(shopId)),
    safeLoad('carriers', () => loadCarriers(shopId)),
    safeLoad('alerts', () => loadAlerts(shopId))
  ])
  loading.value = false
}

/**
 * 刷新「当前正在看的标签页」。
 * <p>
 * 首屏四个接口走骨架屏；子域看板若也走骨架屏，切换标签时整页会闪一下。
 * 因此刷新按钮按需只拉当前标签的数据，并用 busy 只禁用操作按钮。
 */
const refreshCurrent = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  busy.value = true
  try {
    if (tab.value === 'overview') {
      await reload()
    } else if (tab.value === 'shipments') {
      // 刷新按钮要**强制**重拉：面板的 load() 是「首次才拉」，用它等于按钮没反应。
      await shipmentPanel.value?.reload()
    } else if (tab.value === 'quotes') {
      await safeLoad('quotes', () => loadQuoteBoard(shopId))
      // 有效报价明细与看板是不同端点：只刷看板会让用户在看的列表停在旧数据
      await quoteListPanel.value?.reload()
    } else if (tab.value === 'transfers') {
      // 列表与看板都在子组件里，刷新整块交给它（错误经 report-error 回流页级错误区）
      await transferPanel.value?.reload()
    } else if (tab.value === 'freight') {
      await safeLoad('freight', () => loadFreightBoard(shopId))
    } else if (tab.value === 'receipts') {
      await safeLoad('receipts', () => loadReceiptBoard(shopId))
    } else {
      await reload()
    }
    // 子域的角标依赖看板数据，顺带刷新概览让同步状态条与角标保持一致
    await safeLoad('overview', () => loadOverview(shopId))
  } finally {
    busy.value = false
  }
}

const switchTab = async (key: TabKey) => {
  tab.value = key
  const shopId = refreshShop()
  if (!shopId) return
  // 按标签懒加载：子域看板只在第一次打开时拉取，避免进来就把六七个接口全打一遍
  if (key === 'shipments') {
    // 「已加载」判据归面板（按店铺记），页壳再存一份就是两套真相
    await shipmentPanel.value?.load()
  } else if (key === 'quotes') {
    if (!quoteBoard.value) await safeLoad('quotes', () => loadQuoteBoard(shopId))
    // 逐条报价与看板同源但不同端点：也按「首次打开才拉」。
    // 交给面板自己的 load()——判据归面板，页壳再存一份就是两套真相。
    await quoteListPanel.value?.load()
  } else if (key === 'transfers') {
    // 看板与列表都由面板自己判「本店铺是否已加载」
    await transferPanel.value?.load()
  } else if (key === 'freight') {
    if (!freightBoard.value) await safeLoad('freight', () => loadFreightBoard(shopId))
  } else if (key === 'receipts') {
    if (!receiptBoard.value) await safeLoad('receipts', () => loadReceiptBoard(shopId))
  }
}

// ============================================================
// 操作
// ============================================================

const doSync = async (shipmentId: number) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await syncShipment(shipmentId)
    if (res?.code === 200) {
      showToast('同步完成', 'success')
      await Promise.all([shipmentPanel.value?.reload(), loadOverview(refreshShop())])
    } else {
      showToast(res?.message || '同步失败', 'error')
    }
  } catch (e) {
    showToast('同步失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const doClose = async (shipmentId: number) => {
  if (busy.value) return
  if (!window.confirm('确认关闭该货件？关闭后不再参与自动跟踪与延误统计。')) return
  busy.value = true
  try {
    const res = await closeShipment(shipmentId, refreshShop())
    if (res?.code === 200) {
      showToast('货件已关闭', 'success')
      // 强制重拉：刚关掉的那条必须从列表里消失
      await Promise.all([shipmentPanel.value?.reload(), reload()])
    } else {
      showToast(res?.message || '关闭失败', 'error')
    }
  } catch (e) {
    showToast('关闭失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const loadTracking = async (shipmentId: number, append = false) => {
  if (!append) {
    timeline.value = []
    trackingNextCursor.value = null
    trackingTruncated.value = false
    trackingPageMetaMissing.value = false
  }
  if (append) trackingLoadingMore.value = true
  else trackingLoading.value = true
  try {
    const cursor = append ? (trackingNextCursor.value ?? undefined) : undefined
    const res = await getShipmentTracking(shipmentId, TRACKING_PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      timeline.value = append ? timeline.value.concat(rows) : rows
      const page = res._page
      const hasMore = page ? (page.truncated || page.hasMore) : false
      const metaMissing = !page || (hasMore && !page.nextCursor)
      trackingPageMetaMissing.value = metaMissing
      trackingTruncated.value = !metaMissing && hasMore
      trackingNextCursor.value = metaMissing ? null : (page?.nextCursor ?? null)
    } else {
      pushBlockError('物流轨迹', res?.message)
      if (!append) timeline.value = []
      trackingPageMetaMissing.value = true
      trackingTruncated.value = false
      trackingNextCursor.value = null
    }
  } catch (e) {
    console.warn('[Logistics] 轨迹加载失败', e)
    pushBlockError('物流轨迹', e)
    if (!append) timeline.value = []
    trackingPageMetaMissing.value = true
    trackingTruncated.value = false
    trackingNextCursor.value = null
  } finally {
    if (append) trackingLoadingMore.value = false
    else trackingLoading.value = false
  }
}

const openTracking = async (shipmentId: number) => {
  trackingOpen.value = true
  trackingShipmentId.value = shipmentId
  await loadTracking(shipmentId)
}

const loadMoreTracking = () => {
  const shipmentId = trackingShipmentId.value
  if (!shipmentId || !trackingTruncated.value || !trackingNextCursor.value || trackingLoadingMore.value) return
  void loadTracking(shipmentId, true)
}

// ---- 比价 ----




// ---- 调拨 ----





// ---- 签收差异 ----

const doInvestigate = async (id: number) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await investigateDiscrepancy(id)
    if (res?.code === 200) {
      showToast('已转入核查中', 'success')
      await safeLoad('receipts', () => loadReceiptBoard(refreshShop()))
    } else {
      showToast(res?.message || '操作失败', 'error')
    }
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const doResolve = async (id: number) => {
  if (busy.value) return
  const resolution = window.prompt('请输入处理结果（结案必填，例如：已向亚马逊提交索赔 / 确认不补发）')
  if (resolution === null) return
  if (!resolution.trim()) {
    showToast('结案必须填写处理结果', 'error')
    return
  }
  busy.value = true
  try {
    const res = await resolveDiscrepancy(id, resolution.trim())
    if (res?.code === 200) {
      showToast('差异已结案', 'success')
      await safeLoad('receipts', () => loadReceiptBoard(refreshShop()))
    } else {
      showToast(res?.message || '结案失败', 'error')
    }
  } catch (e) {
    showToast('结案失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}


// ============================================================
// 生命周期
// ============================================================

// 登录态变化（顶部登录/登出）后重评估：登录后补拉，登出后清空回提示态
const onAuthChanged = () => {
  hasToken.value = !!localStorage.getItem('token')
  if (hasToken.value) {
    reload()
    // 换店铺后必须**强制**重拉：面板的 load() 按店铺 id 判「已加载」，
    // 店铺没变就跳过——而这里恰恰是店铺变了的那条路径。用 load() 会
    // 让上一个店铺的列表留在屏上（多租户隔离缺口）。
    const shopId = refreshShop()
    if (tab.value === 'shipments') void shipmentPanel.value?.reload()
    if (tab.value === 'quotes') {
      safeLoad('quotes', () => loadQuoteBoard(shopId))
      void quoteListPanel.value?.reload()
    }
    if (tab.value === 'transfers') void transferPanel.value?.reload()
    if (tab.value === 'freight') safeLoad('freight', () => loadFreightBoard(shopId))
    if (tab.value === 'receipts') safeLoad('receipts', () => loadReceiptBoard(shopId))
  } else {
    overview.value = null
    trend.value = []
    carriers.value = []
    alerts.value = []
    quoteBoard.value = null
    freightBoard.value = null
    receiptBoard.value = null
    loading.value = false
  }
}

onMounted(() => {
  reload()
  window.addEventListener('amz:auth-changed', onAuthChanged)
})

onUnmounted(() => {
  window.removeEventListener('amz:auth-changed', onAuthChanged)
})
</script>

<style scoped>
.error-zone {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border-radius: var(--radius-md);
  padding: 0.625rem 0.875rem;
  margin-bottom: 1rem;
  font-size: 0.875rem;
  background: var(--color-light-red);
  color: var(--color-error);
}
.logistics-page {
  background: var(--color-background);
}

.login-hint {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  margin-bottom: 1rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-muted);
  font-size: var(--font-size-2);
}

/* 取数状态条：本看板最关键的一条信息——数据到底还在不在更新 */
.sync-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1rem;
  margin-bottom: 1rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
}
.sync-bar.on {
  background: var(--color-success-light);
  color: var(--color-success);
}
.sync-bar.off {
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
}
.sync-icon { flex-shrink: 0; }
.sync-text { flex: 1; }
.sync-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  font-size: var(--font-size-2);
  cursor: pointer;
}
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 骨架屏 */
.skeleton-zone { min-height: 20rem; }
.sk-icon { width: 3rem; height: 3rem; flex-shrink: 0; }
.sk-lines { flex: 1; display: flex; flex-direction: column; gap: 0.5rem; justify-content: center; }
.sk-line { height: 0.875rem; }
.sk-line-lg { width: 55%; }
.sk-line-sm { width: 35%; }
.sk-chart { height: 12rem; margin-top: 1rem; }

/* 标签页 */
.tabs {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--color-border);
  overflow-x: auto;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.625rem 1rem;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--font-size-3);
  cursor: pointer;
  white-space: nowrap;
}
.tab:hover { color: var(--color-on-surface); }
.tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.tab-badge {
  min-width: 1.25rem;
  padding: 0 0.375rem;
  border-radius: var(--radius-full);
  font-size: var(--font-size-1);
  text-align: center;
  background: var(--color-muted-light);
  color: var(--color-muted);
}
.tab-badge.badge-danger {
  background: var(--color-light-red);
  color: var(--color-error);
}
.tab-badge.badge-warn {
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
}








/* toast */
.toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.625rem 1.25rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
  color: #fff;
  z-index: 1100;
  box-shadow: var(--shadow-md);
}
.toast.success { background: var(--color-success); }
.toast.error { background: var(--color-error); }
.toast.info { background: var(--color-primary); }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>



