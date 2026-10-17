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
          <section v-else-if="tab === 'alerts'">
            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>级别</th>
                    <th>类型</th>
                    <th>货件号</th>
                    <th>承运商</th>
                    <th>运单号</th>
                    <th>状态</th>
                    <th>ETA</th>
                    <th>超期</th>
                    <th>说明与建议</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!alerts.length">
                    <td colspan="10" class="empty-row">当前没有需要人工处理的货件</td>
                  </tr>
                  <tr v-for="a in alerts" :key="a.type + '-' + a.shipmentId">
                    <td>
                      <span class="status-tag" :class="severityTone(a.severity)">
                        {{ severityLabel(a.severity) }}
                      </span>
                    </td>
                    <td>{{ alertTypeLabel(a.type) }}</td>
                    <td class="mono">{{ a.shipmentNo }}</td>
                    <td>{{ a.carrier || '—' }}</td>
                    <td class="mono">{{ a.masterTrackingNo || '—' }}</td>
                    <td>{{ statusLabel(a.status) }}</td>
                    <td class="mono">{{ a.eta || '—' }}</td>
                    <td>{{ a.daysOverdue !== null && a.daysOverdue > 0 ? a.daysOverdue + ' 天' : '—' }}</td>
                    <td class="hint-cell">
                      <div class="hint-msg">{{ a.message }}</div>
                      <div class="hint-action">建议：{{ a.actionHint }}</div>
                    </td>
                    <td class="ops">
                      <button class="link-btn" :disabled="busy" @click="doSync(a.shipmentId)">同步</button>
                      <button class="link-btn" :disabled="busy" @click="openTracking(a.shipmentId)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="doClose(a.shipmentId)">关单</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <!-- ================= 在途货件 ================= -->
          <section v-else-if="tab === 'shipments'">
            <div class="filter-bar">
              <label for="lg-status">状态</label>
              <select id="lg-status" v-model="shipmentStatus" @change="loadShipments()">
                <option value="">全部</option>
                <option v-for="s in statusOptions" :key="s" :value="s">
                  {{ statusLabel(s) }}
                </option>
              </select>
              <span class="filter-count">
              已加载 {{ shipments.length }} 条<template v-if="shipmentsTruncated">，仍有更多未加载</template><template v-else-if="shipmentsPageMetaMissing">，完整性未知</template>
            </span>
            </div>
            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>货件号</th>
                    <th>承运商</th>
                    <th>运单号</th>
                    <th>状态</th>
                    <th>方式</th>
                    <th>ETA</th>
                    <th>最近取数</th>
                    <th>来源</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!shipments.length">
                    <td colspan="9" class="empty-row">暂无货件</td>
                  </tr>
                  <tr v-for="s in shipments" :key="s.id">
                    <td class="mono">{{ s.shipmentNo }}</td>
                    <td>{{ s.carrier || '—' }}</td>
                    <td class="mono">{{ s.masterTrackingNo || '—' }}</td>
                    <td>
                      <span class="status-tag" :class="statusTone(s.status)">
                        {{ statusLabel(s.status) }}
                      </span>
                    </td>
                    <td>{{ methodLabel(s.shippingMethod) }}</td>
                    <td class="mono">{{ s.eta || '—' }}</td>
                    <td class="mono">{{ fmtTime(s.lastTrackTime) }}</td>
                    <td>{{ s.dataSource || 'AUTO' }}</td>
                    <td class="ops">
                      <button class="link-btn" :disabled="busy" @click="doSync(s.id!)">同步</button>
                      <button class="link-btn" @click="openTracking(s.id!)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="doClose(s.id!)">关单</button>
                    </td>
                  </tr>
                </tbody>
              </table>
              <LogisticsCursorBar
                :loaded="shipments.length"
                :truncated="shipmentsTruncated"
                :page-meta-missing="shipmentsPageMetaMissing"
                :loading-more="shipmentsLoadingMore"
                :has-next="!!shipmentsNextCursor"
                @load-more="loadMoreShipments"
              />
            </div>
          </section>

          <!-- ================= 承运商 ================= -->
          <section v-else-if="tab === 'carrier'">
            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>承运商</th>
                    <th>总货件</th>
                    <th>在跟踪</th>
                    <th>已送达</th>
                    <th>延误</th>
                    <th>异常</th>
                    <th>平均时效</th>
                    <th>延误率</th>
                    <th>异常率</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!carriers.length">
                    <td colspan="9" class="empty-row">暂无数据</td>
                  </tr>
                  <tr v-for="c in carriers" :key="c.carrier">
                    <td>{{ c.carrier }}</td>
                    <td>{{ c.total }}</td>
                    <td>{{ c.active }}</td>
                    <td>{{ c.delivered }}</td>
                    <td :class="{ 'cell-alert': c.delayed > 0 }">{{ c.delayed }}</td>
                    <td :class="{ 'cell-alert': c.exception > 0 }">{{ c.exception }}</td>
                    <td>{{ c.avgTransitDays === null ? '暂无样本' : c.avgTransitDays + ' 天' }}</td>
                    <td>{{ fmtRate(c.delayedRate) }}</td>
                    <td>{{ fmtRate(c.exceptionRate) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="foot-note">
              平均时效以「承运商确认送达」的轨迹事件时间为终点计算，样本不足时显示「暂无样本」而非 0，
              避免把小样本误读为高效。
            </p>
          </section>

          <!-- ================= 数据导入 ================= -->
          <section v-else-if="tab === 'import'">
            <div class="import-grid">
              <div class="chart-card">
                <h3>① 导入货件主单</h3>
                <p class="card-desc">
                  轨迹要挂在货件上，因此<strong>必须先有货件</strong>。按货件编号 upsert，
                  同一份文件可安全重复导入（已存在则只补齐本次给出的字段）。
                </p>
                <textarea
                  v-model="shipmentJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="shipmentPlaceholder"
                  aria-label="货件主单 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !shipmentJson.trim()" @click="doImportShipments">
                    导入货件主单
                  </button>
                  <button class="link-btn" @click="shipmentJson = shipmentExample">填入示例</button>
                </div>
              </div>

              <div class="chart-card">
                <h3>② 导入运单轨迹</h3>
                <p class="card-desc">
                  可提交<strong>全量历史轨迹</strong>：已存在的轨迹点会计入「跳过」而非重复插入，
                  因此不必自行维护增量。状态填承运商原文即可，服务端统一映射。
                </p>
                <textarea
                  v-model="trackingJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="trackingPlaceholder"
                  aria-label="运单轨迹 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !trackingJson.trim()" @click="doImportTracking">
                    导入运单轨迹
                  </button>
                  <button class="link-btn" @click="trackingJson = trackingExample">填入示例</button>
                </div>
              </div>
            </div>

            <div v-if="importReport" class="chart-card report-card">
              <div class="card-head">
                <h3>最近一次导入结果</h3>
                <button class="link-btn" @click="importReport = null">清除</button>
              </div>
              <div class="report-grid">
                <div class="report-item"><b>{{ importReport.totalRows }}</b><span>总行数</span></div>
                <div class="report-item"><b>{{ importReport.succeededRows }}</b><span>成功</span></div>
                <div class="report-item" :class="{ 'cell-alert': importReport.failedRows > 0 }">
                  <b>{{ importReport.failedRows }}</b><span>失败</span>
                </div>
                <div class="report-item"><b>{{ importReport.createdShipments }}</b><span>新建货件</span></div>
                <div class="report-item"><b>{{ importReport.updatedShipments }}</b><span>补齐货件</span></div>
                <div class="report-item"><b>{{ importReport.acceptedEvents }}</b><span>写入轨迹点</span></div>
                <div class="report-item"><b>{{ importReport.skippedEvents }}</b><span>跳过（已存在）</span></div>
                <div class="report-item"><b>{{ importReport.statusChangedShipments }}</b><span>状态变更</span></div>
              </div>

              <div v-if="importReport.unmatched.length" class="report-block">
                <div class="report-title">以下运单在系统内没有对应货件，请先导入货件主单：</div>
                <div class="chip-wrap">
                  <span v-for="u in importReport.unmatched" :key="u" class="chip">{{ u }}</span>
                </div>
              </div>
              <div v-if="importReport.errors.length" class="report-block">
                <div class="report-title">行级错误：</div>
                <ul class="err-list">
                  <li v-for="(e, i) in importReport.errors" :key="i">{{ e }}</li>
                </ul>
                <div v-if="importReport.errorsTruncated" class="report-title">
                  错误过多，仅展示前若干条
                </div>
              </div>
              <p
                v-if="!importReport.unmatched.length && !importReport.errors.length && importReport.failedRows === 0"
                class="report-ok"
              >
                全部处理成功。若「写入轨迹点」为 0 而「跳过」大于 0，说明这批数据此前已导入过（幂等生效）。
              </p>
            </div>
          </section>

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
import LogisticsCursorBar from '../components/LogisticsCursorBar.vue'
import LogisticsTrackingModal from '../components/LogisticsTrackingModal.vue'
import LogisticsReceiptPanel from '../components/LogisticsReceiptPanel.vue'
import LogisticsFreightPanel from '../components/LogisticsFreightPanel.vue'
import LogisticsTransferPanel from '../components/LogisticsTransferPanel.vue'
import LogisticsQuoteComparePanel from '../components/LogisticsQuoteComparePanel.vue'
import LogisticsQuoteListPanel from '../components/LogisticsQuoteListPanel.vue'
import LogisticsOverviewPanel from '../components/LogisticsOverviewPanel.vue'
import { statusLabel, statusTone } from '../components/logisticsStatus'
import LogisticsQuoteBoardPanel from '../components/LogisticsQuoteBoardPanel.vue'
import { fmtRate } from '../components/logisticsFormat'
// 物流子域面板共用的 KPI / 小指标 / 警告横幅 / 图表卡样式（scoped 不跨组件，故走全局样式表）
import '../styles/logistics-panels.css'
import { useToast } from '../composables/useToast'
import { useShopGuard } from '../composables/useShopGuard'
import {
  getLogisticsOverview,
  getLogisticsTrend,
  getCarrierPerformance,
  getLogisticsAlerts,
  listShipments,
  syncShipment,
  closeShipment,
  getShipmentTracking,
  importShipments,
  importTracking,
  getQuoteBoard,
  getFreightCostBoard,
  getReceiptBoard,
  investigateDiscrepancy,
  resolveDiscrepancy,
  type DashboardOverview,
  type TrendPoint,
  type CarrierPerformance,
  type ShipmentAlert,
  type Shipment,
  type TrackingEvent,
  type ImportReport,
  type ImportShipmentRow,
  type ImportTrackingRow,
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
const PAGE_SIZE = 50
const shipments = ref<Shipment[]>([])
const shipmentsNextCursor = ref<string | null>(null)
const shipmentsTruncated = ref(false)
const shipmentsPageMetaMissing = ref(false)
const shipmentsLoadingMore = ref(false)

// 运营子域看板
const quoteBoard = ref<QuoteBoard | null>(null)
/** 逐条有效报价（与看板/比价互补的后端读侧；只含有效期内且 ACTIVE 的报价） */
const transferPanel = ref<InstanceType<typeof LogisticsTransferPanel> | null>(null)
const quoteListPanel = ref<InstanceType<typeof LogisticsQuoteListPanel> | null>(null)
const freightBoard = ref<FreightCostBoard | null>(null)
const receiptBoard = ref<ReceiptBoard | null>(null)

/** 比价入参。重量与体积至少填一个，两者都填时服务端按计费价取高 */

type TabKey = 'overview' | 'alerts' | 'shipments' | 'carrier' | 'quotes' | 'transfers' | 'freight' | 'receipts' | 'import'

const tab = ref<TabKey>('overview')
const shipmentStatus = ref('')

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

// 导入
const shipmentJson = ref('')
const trackingJson = ref('')
const importReport = ref<ImportReport | null>(null)

const shipmentExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU1234567',
      shippingMethod: 'SEA',
      originPort: 'Shenzhen',
      destinationPort: 'LAX9',
      boxCount: 120,
      weight: 2400,
      eta: '2026-09-20'
    }
  ],
  null,
  2
)

const trackingExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      trackingNo: 'COSU1234567',
      events: [
        { status: '已开船', location: '深圳港', eventTime: '2026-08-05T09:00:00+08:00' },
        { status: 'IN_TRANSIT', location: '太平洋', eventTime: '2026-08-12T09:00:00+08:00' },
        { status: '清关中', location: '洛杉矶港', eventTime: '2026-08-16T21:30:00-07:00' }
      ]
    }
  ],
  null,
  2
)

const shipmentPlaceholder = `[\n  { "shipmentNo": "SHP20260818001", "carrier": "COSCO", "masterTrackingNo": "COSU1234567", "eta": "2026-09-20" }\n]`
const trackingPlaceholder = `[\n  { "shipmentNo": "SHP20260818001", "events": [ { "status": "已开船", "eventTime": "2026-08-05T09:00:00+08:00" } ] }\n]`

// ============================================================
// 展示映射（状态 / 类型 / 级别）
// ============================================================


const statusOptions = ['CREATED', 'IN_TRANSIT', 'CUSTOMS', 'DELIVERED', 'RECEIVED', 'DELAYED', 'EXCEPTION', 'CLOSED']

const METHOD_LABELS: Record<string, string> = {
  SEA: '海运',
  AIR: '空运',
  EXPRESS: '快递',
  TRUCK: '卡车'
}

const ALERT_TYPE_LABELS: Record<string, string> = {
  DELAYED: '延误',
  EXCEPTION: '异常',
  ETA_OVERDUE: '超期未标',
  ETA_APPROACHING: '临近到港',
  STALE_DATA: '取数过期',
  MISSING_TRACKING_NO: '缺运单号'
}






const methodLabel = (m?: string) => (m ? METHOD_LABELS[m] || m : '—')
const alertTypeLabel = (t: string) => ALERT_TYPE_LABELS[t] || t




const severityLabel = (s: string) => (s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : '低')
const severityTone = (s: string) => (s === 'HIGH' ? 'tone-danger' : s === 'MEDIUM' ? 'tone-warn' : 'tone-muted')

const fmtTime = (v?: string | null) => {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}

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

const loadShipments = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    if (!append) {
      shipments.value = []
      shipmentsNextCursor.value = null
      shipmentsTruncated.value = false
      shipmentsPageMetaMissing.value = false
    }
    return
  }
  if (!append) {
    shipmentsNextCursor.value = null
    shipmentsTruncated.value = false
    shipmentsPageMetaMissing.value = false
  }
  if (append) shipmentsLoadingMore.value = true
  try {
    const cursor = append ? (shipmentsNextCursor.value ?? undefined) : undefined
    const res = await listShipments(shopId, shipmentStatus.value || undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      shipments.value = append ? shipments.value.concat(rows) : rows
      const page = res._page
      const hasMore = page ? (page.truncated || page.hasMore) : false
      const metaMissing = !page || (hasMore && !page.nextCursor)
      shipmentsPageMetaMissing.value = metaMissing
      shipmentsTruncated.value = !metaMissing && hasMore
      shipmentsNextCursor.value = metaMissing ? null : (page?.nextCursor ?? null)
    } else {
      pushBlockError('货件列表', res?.message)
      if (!append) shipments.value = []
    }
  } catch (e) {
    console.warn('[Logistics] 加载货件列表失败', e)
    pushBlockError('货件列表', e)
    if (!append) shipments.value = []
  } finally {
    if (append) shipmentsLoadingMore.value = false
  }
}

const loadMoreShipments = () => {
  if (!shipmentsTruncated.value || !shipmentsNextCursor.value || shipmentsLoadingMore.value) return
  void loadShipments(true)
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
      await loadShipments()
    } else if (tab.value === 'quotes') {
      await safeLoad('quotes', () => loadQuoteBoard(shopId))
    } else if (tab.value === 'transfers') {
      // 列表与看板都在子组件里，刷新整块交给它（错误经 report-error 回流页级错误区）
      await transferPanel.value?.load()
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
    if (!shipments.value.length) await safeLoad('shipments', loadShipments)
  } else if (key === 'quotes') {
    if (!quoteBoard.value) await safeLoad('quotes', () => loadQuoteBoard(shopId))
    // 逐条报价与看板同源但不同端点：也按「首次打开才拉」。
    // 交给面板自己的 load()——「已加载」这个判据归面板，页壳再存一份就是两套真相。
    // 切店铺时由 onAuthChanged 走 reload 分支重拉。
    await quoteListPanel.value?.load()
  } else if (key === 'transfers') {
    // 首次进入才拉。把关留在这里而不下沉到子组件：两处把关就是两套
    // 「已加载」集合，改一处漏一处的症状是重复请求或永不请求。
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
      await Promise.all([loadShipments(), loadOverview(refreshShop())])
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
      await Promise.all([loadShipments(), reload()])
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

/** 解析粘贴的 JSON。解析失败时给出具体原因，而不是笼统的「导入失败」 */
const parseRows = <T,>(text: string): T[] | null => {
  try {
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      showToast('内容必须是 JSON 数组', 'error')
      return null
    }
    return parsed as T[]
  } catch (e) {
    showToast('JSON 解析失败：' + (e instanceof Error ? e.message : '格式错误'), 'error')
    return null
  }
}

const doImportShipments = async () => {
  const rows = parseRows<ImportShipmentRow>(shipmentJson.value)
  if (!rows) return
  await runImport(() => importShipments(refreshShop(), rows), '货件主单')
}

const doImportTracking = async () => {
  const rows = parseRows<ImportTrackingRow>(trackingJson.value)
  if (!rows) return
  await runImport(() => importTracking(refreshShop(), rows), '运单轨迹')
}

const runImport = async (
  fn: () => Promise<{ code: number; message: string; data: ImportReport }>,
  label: string
) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await fn()
    if (res?.code === 200 && res.data) {
      importReport.value = res.data
      const r = res.data
      showToast(
        `${label}导入完成：成功 ${r.succeededRows} / 失败 ${r.failedRows}`,
        r.failedRows > 0 ? 'error' : 'success'
      )
      // 导入会改变看板数字，直接刷新让使用者立刻看到结果，省去手动再点一次刷新
      await reload()
      if (tab.value === 'shipments') await loadShipments()
    } else {
      showToast(res?.message || `${label}导入失败`, 'error')
    }
  } catch (e) {
    showToast(`${label}导入失败，请稍后重试`, 'error')
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
    if (tab.value === 'shipments') loadShipments()
    // 子域看板同样要跟着换店铺重拉，否则会残留上一个店铺的数字
    const shopId = refreshShop()
    if (tab.value === 'quotes') {
      safeLoad('quotes', () => loadQuoteBoard(shopId))
      void quoteListPanel.value?.reload()
    }
    if (tab.value === 'transfers') void transferPanel.value?.load()
    if (tab.value === 'freight') safeLoad('freight', () => loadFreightBoard(shopId))
    if (tab.value === 'receipts') safeLoad('receipts', () => loadReceiptBoard(shopId))
  } else {
    overview.value = null
    trend.value = []
    carriers.value = []
    alerts.value = []
    shipments.value = []
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


/* 过滤条 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
  font-size: var(--font-size-2);
  color: var(--color-muted);
}
.filter-bar select {
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  font-size: var(--font-size-2);
}
.filter-count { margin-left: auto; }

/* 数据导入 */
.import-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}
.json-input {
  width: 100%;
  min-height: 12rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-background);
  color: var(--color-on-surface);
  font-family: var(--font-mono);
  font-size: var(--font-size-1);
  line-height: var(--line-height-normal);
  resize: vertical;
  box-sizing: border-box;
}
.import-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 0.75rem;
}

/* 导入报告 */
.report-card { margin-top: 0.5rem; }
.report-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.report-item {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  padding: 0.75rem;
  border-radius: var(--radius-sm);
  background: var(--color-muted-light);
}
.report-item b { font-size: var(--font-size-5); color: var(--color-on-surface); }
.report-item span { font-size: var(--font-size-1); color: var(--color-muted); }
.report-title {
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
  margin-bottom: 0.5rem;
}
.chip-wrap { display: flex; flex-wrap: wrap; gap: 0.375rem; }
.chip {
  padding: 0.125rem 0.5rem;
  border-radius: var(--radius-sm);
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
  font-family: var(--font-mono);
  font-size: var(--font-size-1);
}
.report-ok {
  margin: 0.75rem 0 0 0;
  font-size: var(--font-size-2);
  color: var(--color-success);
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

@media (max-width: 1024px) {
  .chart-row { grid-template-columns: 1fr; }
  .import-grid { grid-template-columns: 1fr; }
  .report-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
}
</style>



