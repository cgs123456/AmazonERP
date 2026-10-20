<script setup lang="ts">
/**
 * 调拨面板（从 LogisticsDashboard.vue 拆出，§S33）。
 *
 * ## 与 receipts / freight 的关键区别：列表状态搬进来了
 * 前两个面板没有本地列表，只收一个 board prop。本面板有一个游标分页列表
 * （`transfers` + 4 个分页标志）+ 一个状态筛选。若按前两个的做法
 * 「状态留在页壳、渲染搬进来」，子组件要收 6 个 prop 才能画完这张表，
 * 而其中 4 个只服务于 `<LogisticsCursorBar>`——那是把父组件当成
 * 子组件的状态仓库，比复制代码更难维护。
 *
 * 所以这里把**列表这一整件事**（状态 + loader + 分页 + 筛选）搬进来。
 * 判据是「是否只有一个使用者」：实测 transfers* 五个 ref 与
 * transferStatusFilter 在全文件无第二处引用。
 *
 * ## 但错误区仍在页壳
 * loader 失败要把原因写进页级错误区（`blockErrors`），那属于页壳。
 * 因此本组件不自己持有错误数组，而是通过 `report-error` 事件把
 * （标签, 详情）交回页壳，由页壳的 `pushBlockError` 落账。
 * 这样只多一条显式事件通道，不会有隐式共享状态。
 *
 * ## 三个动作共用的刷新
 * 审批 / 发出 / 收货都是写操作，成功后看板与列表的数字都已过期，
 * 必须一起重拉。合起来一个 `refreshViews`，避免三处各写一遍
 * 「拉 board 又拉 list」而漏掉其中一个。
 */
import { ref } from 'vue'
import LogisticsCursorBar from './LogisticsCursorBar.vue'
import LogisticsTransferRiskPanel from './LogisticsTransferRiskPanel.vue'
import { TRANSFER_STATUS_LABELS, transferStatusLabel, transferStatusTone } from './logisticsTransferStatus'
import {
  getTransferBoard,
  listTransfers,
  approveTransfer,
  shipTransfer,
  receiveTransfer,
  type TransferBoard,
  type InventoryTransfer
} from '../api/logistics'
import { useShopGuard } from '../composables/useShopGuard'
import { useToast } from '../composables/useToast'

const props = defineProps<{
  /**
   * 当前是否激活（页壳的 tab 是不是本面板）。
   *
   * **本组件常驻挂载**，不像其他面板那样由页壳用 v-else-if 挂卸。
   * 原因：页壳的 switchTab 要通过 ref 调 `load()` 完成懒加载，
   * 而 v-else-if 下的组件在切到该 tab 之前不存在，ref 取到 null，
   * 懒加载永不触发——症状是列表一直空、接口一次都没调。
   *
   * 所以渲染由本组件自己按 active 决定（内部 v-if）。
   * DOM 只在该 tab 激活时存在，按选择器取元素的测试不受影响。
   *
   * 数据也因此活过「切走再切回」：切走时 DOM 卸载但 ref 里的列表还在，
   * 切回来直接显示上次的结果，而不是空表 + 「暂无调拨单」——那是误导。
   */
  active: boolean
  /** 页级操作互斥标记 */
  busy: boolean
}>()

const emit = defineEmits<{
  /** loader 失败：把（标签, 详情）交回页壳落进页级错误区 */
  (e: 'report-error', tag: string, detail: unknown): void
}>()

const { refreshShop } = useShopGuard()
const { showToast } = useToast()

const PAGE_SIZE = 50
/**
 * 本面板自己的操作互斥标记。
 *
 * 不能直接用页壳的 `props.busy`：拆出来后，页壳不再为这三个写操作置位
 * （它只管自己的动作），于是请求期间按钮不会禁用，连点会重复审批/发货。
 * 但 props.busy 仍要参与判断——用户在别的面板触发的页级操作进行时，
 * 本面板的按钮同样不该可点。
 */
const panelBusy = ref(false)
const transferBoard = ref<TransferBoard | null>(null)
const transfers = ref<InventoryTransfer[]>([])
const transfersNextCursor = ref<string | null>(null)
const transfersTruncated = ref(false)
const transfersPageMetaMissing = ref(false)
const transfersLoadingMore = ref(false)
const transferStatusFilter = ref('')

const loadTransferBoard = async (shopId: string) => {
  const res = await getTransferBoard(shopId)
  if (res?.code === 200 && res.data) {
    transferBoard.value = res.data
  }
}

const loadTransfers = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    if (!append) {
      transfers.value = []
      transfersNextCursor.value = null
      transfersTruncated.value = false
      transfersPageMetaMissing.value = false
    }
    return
  }
  if (!append) {
    transfersNextCursor.value = null
    transfersTruncated.value = false
    transfersPageMetaMissing.value = false
  }
  if (append) transfersLoadingMore.value = true
  try {
    const cursor = append ? (transfersNextCursor.value ?? undefined) : undefined
    const res = await listTransfers(shopId, transferStatusFilter.value || undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      transfers.value = append ? transfers.value.concat(rows) : rows
      const page = res._page
      const hasMore = page ? (page.truncated || page.hasMore) : false
      const metaMissing = !page || (hasMore && !page.nextCursor)
      transfersPageMetaMissing.value = metaMissing
      transfersTruncated.value = !metaMissing && hasMore
      transfersNextCursor.value = metaMissing ? null : (page?.nextCursor ?? null)
    } else {
      emit('report-error', '调拨列表', res?.message)
      if (!append) transfers.value = []
    }
  } catch (e) {
    console.warn('[Logistics] 加载调拨列表失败', e)
    emit('report-error', '调拨列表', e)
    if (!append) transfers.value = []
  } finally {
    if (append) transfersLoadingMore.value = false
  }
}

/**
 * 写操作成功后：看板与列表的数字都已过期，一起重拉。
 *
 * 顺序与原版一致——**先拉列表再看板**：列表是用户正在看的那份，
 * 先刷新它能让操作反馈第一时间可见；看板刷新失败也不该挡住列表更新。
 * 原版把看板那次包在 safeLoad 里（失败只进页级错误区），
 * 这里等价地 try/catch 后 emit('report-error')。
 */
const refreshTransferViews = async () => {
  const shopId = refreshShop()
  await loadTransfers()
  try {
    await loadTransferBoard(shopId)
  } catch (e) {
    emit('report-error', '调拨看板', e)
  }
}

const loadMoreTransfers = () => {
  if (!transfersTruncated.value || !transfersNextCursor.value || transfersLoadingMore.value) return
  void loadTransfers(true)
}

/**
 * 「已加载」判据归本组件，且按**店铺**记。
 *
 * 此前判据是 `!transferBoard` / `!transfers.length`——切到新店铺时两者都
 * 还是旧店的值，于是永远不再拉；而同一店铺内点回标签时若列表恰好为空
 * （该店确实没有调拨单），又会反复重拉。记店铺 id 同时解决这两头。
 */
const loadedShopId = ref<string | null>(null)

/** 首次进入才拉看板与列表（由页壳的 switchTab 调用）。 */
const load = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    loadedShopId.value = null
    return
  }
  if (loadedShopId.value === shopId) return
  if (!transferBoard.value) {
    try {
      await loadTransferBoard(shopId)
    } catch (e) {
      emit('report-error', '调拨看板', e)
    }
  }
  await loadTransfers()
  loadedShopId.value = shopId
}

/** 强制重拉：刷新按钮 / 切店铺 / 登录态变化走这条（`load` 只负责首次）。 */
const reload = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    loadedShopId.value = null
    return
  }
  await refreshTransferViews()
  loadedShopId.value = shopId
}

/** 只在草稿与待审批状态下给出通过/驳回；已进入后续状态的按钮没有意义。 */
const canApprove = (status?: string) => status === 'DRAFT' || status === 'PENDING_APPROVAL'
const isTransferDone = (status?: string) => status === 'RECEIVED' || status === 'CANCELLED'

const doApproveTransfer = async (t: InventoryTransfer, approved: boolean) => {
  if (props.busy || panelBusy.value || t.id === undefined) return
  const action = approved ? '审批通过' : '驳回'
  if (!window.confirm(`确认${action}调拨单 ${t.transferNo}？`)) return
  panelBusy.value = true
  try {
    const res = await approveTransfer(t.id, approved)
    if (res?.code === 200) {
      showToast(`调拨单已${action}`, 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || `${action}失败`, 'error')
    }
  } catch (e) {
    showToast(`${action}失败，请稍后重试`, 'error')
  } finally {
    panelBusy.value = false
  }
}

const doShipTransfer = async (t: InventoryTransfer) => {
  if (props.busy || panelBusy.value || t.id === undefined) return
  const carrier = window.prompt('承运商名称', t.carrier || '')
  if (carrier === null) return
  if (!carrier.trim()) {
    showToast('承运商不能为空', 'error')
    return
  }
  const trackingNo = window.prompt('物流单号', t.trackingNo || '')
  if (trackingNo === null) return
  // 单号决定了后续能否自动跟单，因此这里只做非空校验而不静默跳过
  if (!trackingNo.trim()) {
    showToast('物流单号不能为空', 'error')
    return
  }
  panelBusy.value = true
  try {
    const res = await shipTransfer(t.id, carrier.trim(), trackingNo.trim())
    if (res?.code === 200) {
      showToast('调拨单已标记发出', 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || '操作失败', 'error')
    }
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  } finally {
    panelBusy.value = false
  }
}

const doReceiveTransfer = async (t: InventoryTransfer) => {
  if (props.busy || panelBusy.value || t.id === undefined) return
  if (!window.confirm(`确认调拨单 ${t.transferNo} 已到货？确认后单据结束。`)) return
  panelBusy.value = true
  try {
    const res = await receiveTransfer(t.id)
    if (res?.code === 200) {
      showToast('调拨单已确认收货', 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || '确认收货失败', 'error')
    }
  } catch (e) {
    showToast('确认收货失败，请稍后重试', 'error')
  } finally {
    panelBusy.value = false
  }
}

/**
 * 供页壳取两样东西：
 * - `load`：首次进入本 tab 时才拉数据（把关在页壳，见 Finance 面板同一先例）；
 * - `riskCount`：tab 角标要显示「需要人动手的调拨单数」。
 *
 * 角标为什么必须在页壳算：切走本 tab 后角标仍在，
 * 而子组件的数据是它自己的——让页壳读子组件的 ref 比让页壳再持有一份
 * board 数据好，后者会出现两个真相来源。
 */
defineExpose({ load, reload, riskCount: () => transferBoard.value?.risks.length ?? 0 })

/* ---------- 展示用格式化与文案（从页壳原样搬来，未改一字） ---------- */



const fmtAmount = (v?: number | null) =>
  v === null || v === undefined ? '—' : v.toFixed(2)

const fmtTime = (v?: string | null) => {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <div v-if="props.active" data-panel="transfers">
    <LogisticsTransferRiskPanel :board="transferBoard" />

    <div class="chart-card mt">
      <div class="card-head">
        <h3>调拨单</h3>
        <div class="filter-inline">
          <label for="trf-status">状态</label>
          <select id="trf-status" v-model="transferStatusFilter" @change="loadTransfers()">
            <option value="">全部</option>
            <option v-for="(label, key) in TRANSFER_STATUS_LABELS" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </div>
      </div>
      <p class="card-desc">
        状态只能沿既定路径前进：草稿 → 待审批 → 已审批 → 运输中 → 已收货。
        因此「发出」只对已审批的单据可用，「收货」只对运输中的单据可用——
        跳过前置状态会被服务端拒绝，避免出现「没发货就收货」导致库存账凭空多出来。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>调拨单号</th>
            <th>货</th>
            <th>数量</th>
            <th>源 → 目标仓</th>
            <th>状态</th>
            <th>承运商 / 单号</th>
            <th>运费</th>
            <th>建单时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!transfers.length">
            <td colspan="9" class="empty-row">暂无调拨单</td>
          </tr>
          <tr v-for="t in transfers" :key="t.id">
            <td class="mono">{{ t.transferNo }}</td>
            <td>
              <div>{{ t.asin }}</div>
              <div class="sub-line">{{ t.sku }}</div>
            </td>
            <td>{{ t.quantity }}</td>
            <td>{{ t.fromWarehouseId }} → {{ t.toWarehouseId }}</td>
            <td>
              <span class="status-tag" :class="transferStatusTone(t.status)">
                {{ transferStatusLabel(t.status) }}
              </span>
            </td>
            <td class="mono">
              {{ t.carrier || '—' }}
              <div class="sub-line">{{ t.trackingNo || '未登记' }}</div>
            </td>
            <td class="mono">{{ fmtAmount(t.shippingCost) }}</td>
            <td class="mono">{{ fmtTime(t.createTime) }}</td>
            <td class="ops">
              <template v-if="canApprove(t.status)">
                <button class="link-btn" :disabled="props.busy || panelBusy" @click="doApproveTransfer(t, true)">通过</button>
                <button class="link-btn" :disabled="props.busy || panelBusy" @click="doApproveTransfer(t, false)">驳回</button>
              </template>
              <button
                v-else-if="t.status === 'APPROVED'"
                class="link-btn"
                :disabled="props.busy || panelBusy"
                @click="doShipTransfer(t)"
              >
                确认发出
              </button>
              <button
                v-else-if="t.status === 'IN_TRANSIT'"
                class="link-btn"
                :disabled="props.busy || panelBusy"
                @click="doShipTransfer(t)"
              >
                更正单号
              </button>
              <button
                v-if="t.status === 'IN_TRANSIT'"
                class="link-btn"
                :disabled="props.busy || panelBusy"
                @click="doReceiveTransfer(t)"
              >
                确认收货
              </button>
              <span v-if="isTransferDone(t.status)" class="sub-line">已结束</span>
            </td>
          </tr>
        </tbody>
      </table>
      <LogisticsCursorBar
        :loaded="transfers.length"
        :truncated="transfersTruncated"
        :page-meta-missing="transfersPageMetaMissing"
        :loading-more="transfersLoadingMore"
        :has-next="!!transfersNextCursor"
        @load-more="loadMoreTransfers"
      />
    </div>

  </div>
</template>

<style scoped>
/* 只有 .filter-inline 是本组件自己的：它随调拨单列表的状态筛选器一起搬进来，
 * 拆完后**全仓库只此一处使用**（原先在页壳 scoped 块里），所以放进共享表
 * 反而会让它脱离唯一的使用者。其余类全部来自
 * amz-frontend/src/styles/logistics-panels.css 与 src/style.css。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
/* 卡片标题行内的小筛选器 */
.filter-inline {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: var(--font-size-2);
  color: var(--color-muted);
}
.filter-inline select {
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--amz-radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  font-size: var(--font-size-2);
}
</style>
