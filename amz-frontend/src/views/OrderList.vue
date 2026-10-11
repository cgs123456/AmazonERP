<template>
  <div class="order-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
    <div v-if="loadErrors.length" class="error-zone" role="alert">
      <span>{{ loadErrors.join('；') }}</span>
    </div>
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">订单管理</h1>
        <p class="hero-subtitle">Amazon 订单列表与状态跟踪</p>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <select v-model="filterShop" class="filter-select">
          <option value="">全部店铺</option>
          <option v-for="shop in shopOptions" :key="shop.id" :value="String(shop.id)">
            {{ shop.name }}
          </option>
        </select>
        <input type="date" v-model="filterDate" class="filter-date" />
        <input type="text" v-model="filterOrderNo" placeholder="搜索订单号..." class="filter-input" />
        <button class="filter-btn" @click="handleQuery">查询</button>
        <button class="filter-btn export" :disabled="exporting" @click="exportOrders">
          {{ exporting ? '导出中…' : '导出 CSV' }}
        </button>
      </div>

      <!-- 骨架屏：表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
      </div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查询订单数据。
      </div>

      <!-- 订单表格（加载时仅显示骨架，避免布局跳动） -->
      <template v-if="!loading">
      <div class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th>Amazon 订单号</th>
              <th>店铺</th>
              <th>SKU</th>
              <th>数量</th>
              <th>金额</th>
              <th>利润</th>
              <th>状态</th>
              <th>下单时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in displayOrders" :key="order.id">
              <td class="mono">{{ order.orderNo }}</td>
              <td>{{ order.shop }}</td>
              <td class="mono">{{ order.sku }}</td>
              <td>{{ order.qty }}</td>
              <td>{{ order.amount }}</td>
              <td :class="order.profit > 0 ? 'profit-positive' : 'profit-negative'">{{ order.profit > 0 ? '+' : '' }}{{ order.profit }}</td>
              <td><span class="status-tag" :class="order.statusClass">{{ order.status }}</span></td>
              <td class="mono">{{ order.date }}</td>
              <td>
                <div class="row-actions">
                  <button class="row-btn" @click="printSlip(order)">发货单</button>
                  <button
                    class="row-btn primary"
                    :disabled="!canConfirm(order)"
                    :title="confirmTitle(order)"
                    @click="openConfirm(order)"
                  >{{ confirmLabel(order) }}</button>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && displayOrders.length === 0">
              <td colspan="9" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:clipboard-text-off-outline" width="32" class="empty-icon" />
                  <span>暂无订单数据</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页 -->
      <div class="pagination">
        <span class="page-info">第 {{ page }} 页 / 共 {{ totalPages }} 页（{{ total }} 条）</span>
        <div class="page-actions">
          <button class="page-btn" :disabled="page <= 1" @click="prevPage">上一页</button>
          <button class="page-btn" :disabled="page >= totalPages" @click="nextPage">下一页</button>
        </div>
      </div>
      </template>
    </main>

    <!-- 发货回传对话框（FBM 确认发货） -->
    <div v-if="confirmTarget" class="dialog-mask" @click.self="closeConfirm">
      <div class="dialog" role="dialog" aria-modal="true" aria-label="确认发货">
        <h3 class="dialog-title">确认发货</h3>
        <p class="dialog-sub">订单 {{ confirmTarget.orderNo }}：向 Amazon 回传承运商与运单号。
          <strong>只有 Amazon 受理后</strong>，本地才会标记为已回传。</p>
        <div class="dialog-field">
          <label>承运商代码 *</label>
          <input v-model="confirmForm.carrierCode" class="dialog-input" placeholder="如 USPS / UPS / FedEx；其它填 Other" />
        </div>
        <div class="dialog-field">
          <label>承运商名称{{ isOtherCarrier ? ' *' : '（选填）' }}</label>
          <input v-model="confirmForm.carrierName" class="dialog-input" placeholder="carrierCode=Other 时必填" />
        </div>
        <div class="dialog-field">
          <label>运单号 *</label>
          <input v-model="confirmForm.trackingNumber" class="dialog-input" placeholder="承运商运单号" />
        </div>
        <div class="dialog-field">
          <label>发货时间（选填）</label>
          <input v-model="confirmForm.shipDate" class="dialog-input" placeholder="留空则用当前时间，格式 2026-10-11T09:30:00Z" />
        </div>
        <p v-if="confirmError" class="dialog-error">{{ confirmError }}</p>
        <div class="dialog-actions">
          <button class="row-btn" :disabled="confirming" @click="closeConfirm">取消</button>
          <button class="row-btn primary" :disabled="confirming || !confirmValid" @click="submitConfirm">
            {{ confirming ? '回传中…' : '确认发货' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getOrderList, confirmShipment } from '@/api/order'
import type { OrderItem } from '@/api/order'
import { getShops, type ShopOption } from '@/utils/shop'
import { openPackingSlip } from '@/utils/packingSlip'
import { downloadCsv } from '@/utils/csvDownload'
import { useShopGuard } from '@/composables/useShopGuard'

const filterShop = ref('')
const filterDate = ref('')
const filterOrderNo = ref('')

// 店铺下拉来自真实来源（后端 getInfo 写入 localStorage），不再写死 Shop A/B/C
const shopOptions = ref<ShopOption[]>(getShops())

const exporting = ref(false)
const exportOrders = async () => {
  const shopId = filterShop.value || refreshShop()
  if (!shopId) return
  exporting.value = true
  try {
    await downloadCsv('/order/list/export', {
      shopId,
      days: filterDate.value ? undefined : 30,
      startDate: filterDate.value || undefined,
      endDate: filterDate.value || undefined,
      orderNo: filterOrderNo.value || undefined
    }, `orders-${shopId}.csv`)
  } catch (e) {
    loadErrors.value = [e instanceof Error ? e.message : '导出失败']
  } finally {
    exporting.value = false
  }
}

const loading = ref(false)
const orders = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

// 当前选中店铺（B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值）
const { currentShopId, refreshShop } = useShopGuard()

const loadErrors = ref<string[]>([])

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

// 订单号走服务端查询（GET /order/list?orderNo=），分页与总数与之一致
const displayOrders = computed(() => orders.value)

// 请求序号守卫：快速查询/翻页时丢弃过期响应（与 AdManager 趋势序号同模式）
let loadSeq = 0
const loadOrders = async () => {
  // 未选择店铺时不发请求，避免网关 MyGlobalFilter 拒绝 /order/ 路径
  const shopId = filterShop.value || refreshShop()
  if (!shopId) {
    orders.value = []
    total.value = 0
    return
  }
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await getOrderList({
      shopId,
      startDate: filterDate.value || undefined,
      endDate: filterDate.value || undefined,
      orderNo: filterOrderNo.value || undefined,
      page: page.value,
      size: size.value
    })
    if (seq !== loadSeq) return
    if (res?.code === 200 && res.data) {
      // 兼容分页对象与数组两种返回结构
      if (Array.isArray(res.data)) {
        orders.value = res.data
        total.value = res.data.length
      } else {
        orders.value = res.data.list || []
        total.value = res.data.total || 0
      }
      loadErrors.value = []
    } else {
      // 失败必须可见：按筛选条件过滤后的 mock 数据会被当成真实查询结果（假成功）
      loadErrors.value = [res?.message || '后端返回非 200']
      orders.value = []
      total.value = 0
    }
  } catch (e: any) {
    loadErrors.value = [e?.message || '网络异常']
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const handleQuery = () => {
  page.value = 1
  loadOrders()
}

const prevPage = () => {
  if (page.value > 1) {
    page.value--
    loadOrders()
  }
}

const nextPage = () => {
  if (page.value < totalPages.value) {
    page.value++
    loadOrders()
  }
}

onMounted(() => {
  loadOrders()
})

/* ==================== 发货回传（FBM 确认发货） ==================== */

const confirmTarget = ref<OrderItem | null>(null)
const confirmError = ref('')
const confirming = ref(false)
const confirmForm = ref({ carrierCode: '', carrierName: '', trackingNumber: '', shipDate: '' })

const isOtherCarrier = computed(() => confirmForm.value.carrierCode.trim().toLowerCase() === 'other')
const confirmValid = computed(() => {
  const f = confirmForm.value
  if (!f.carrierCode.trim() || !f.trackingNumber.trim()) return false
  if (isOtherCarrier.value && !f.carrierName.trim()) return false
  return true
})

/** 已回传的订单不允许再次回传（后端也会拒绝，这里提前把按钮禁掉，避免无谓请求） */
const canConfirm = (order: OrderItem): boolean => !order.shipmentConfirmed
const confirmLabel = (order: OrderItem): string => (order.shipmentConfirmed ? '已回传' : '确认发货')
const confirmTitle = (order: OrderItem): string =>
  order.shipmentConfirmed ? `已于 ${order.shipConfirmedAt || ''} 回传 Amazon` : '向 Amazon 回传承运商与运单号'

const openConfirm = (order: OrderItem) => {
  if (!canConfirm(order)) return
  confirmTarget.value = order
  confirmError.value = ''
  confirmForm.value = { carrierCode: '', carrierName: '', trackingNumber: '', shipDate: '' }
}

const closeConfirm = () => {
  confirmTarget.value = null
  confirmError.value = ''
}

const submitConfirm = async () => {
  const target = confirmTarget.value
  if (!target || !confirmValid.value) return
  confirming.value = true
  confirmError.value = ''
  try {
    const f = confirmForm.value
    const res = await confirmShipment(
      target.id,
      f.carrierCode.trim(),
      f.trackingNumber.trim(),
      f.carrierName.trim() || undefined,
      f.shipDate.trim() || undefined
    )
    if (res?.code === 200) {
      closeConfirm()
      // 重新拉取而不是本地改状态：以后端为准，避免前端「看着成功了其实没回传」
      await loadOrders()
    } else {
      confirmError.value = res?.message || '回传失败'
    }
  } catch (e: any) {
    confirmError.value = e?.message || '网络异常'
  } finally {
    confirming.value = false
  }
}

/**
 * 打印本地发货单（拣货单）。
 * <p>
 * 边界：这不是 Amazon 面单——面单属独立 Shipping API，本仓库无其模型与凭证。
 * 弹窗被拦截时给出明确提示，而不是「点了没反应」。
 */
const printSlip = (order: OrderItem) => {
  const ok = openPackingSlip({
    orderNo: order.orderNo,
    shopName: order.shop,
    buyerName: order.buyerName,
    carrierCode: order.carrierCode,
    carrierName: order.carrierName,
    trackingNumber: order.trackingNumber,
    shipServiceLevel: order.shipServiceLevel,
    fulfillmentChannel: order.fulfillmentChannel,
    purchaseDate: order.date,
    lines: [
      { sku: order.sku, title: order.title, quantity: order.qty, asin: order.asin }
    ]
  })
  if (!ok) {
    loadErrors.value = ['浏览器拦截了打印窗口，请允许本站弹出窗口后重试']
  }
}
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
.order-page { background: var(--color-background); }
/* .main-content / hero / shop-tip / 表格 / 分页样式已收敛至全局 style.css */

/* 骨架屏（表格行形状） */
.skeleton-zone { display: flex; flex-direction: column; gap: 0.625rem; margin-bottom: 1rem; }
.sk-row { height: 2.75rem; }
.sk-row-alt { width: 96%; }

.filter-bar { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; align-items: center; }
.filter-select, .filter-date, .filter-input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover, .filter-input:hover { border-color: var(--color-primary); }
.filter-input { flex: 1; max-width: 300px; }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.filter-btn.export { background: var(--color-surface); color: var(--color-primary); border: 1px solid var(--color-primary); }

/* shop-tip / table-card / data-table / mono / empty-row / status-tag 基类 / 分页已收敛至全局 style.css */

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }

/* 行内操作按钮（发货单 / 确认发货） */
.row-actions { display: flex; gap: 0.375rem; white-space: nowrap; }
.row-btn { padding: 0.25rem 0.625rem; font-size: 0.8125rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; transition: border-color 0.2s, background 0.2s; }
.row-btn:hover:not(:disabled) { border-color: var(--color-primary); }
.row-btn.primary { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.row-btn.primary:hover:not(:disabled) { background: var(--color-primary-dark); }
.row-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 发货回传对话框 */
.dialog-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.dialog { background: var(--color-surface); border-radius: var(--radius-lg); padding: 1.25rem; width: min(440px, 92vw); box-shadow: var(--shadow-lg); }
.dialog-title { margin: 0 0 0.375rem; font-size: 1.0625rem; }
.dialog-sub { margin: 0 0 1rem; font-size: 0.8125rem; color: var(--color-on-surface-muted); line-height: 1.5; }
.dialog-field { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 0.75rem; }
.dialog-field label { font-size: 0.8125rem; color: var(--color-on-surface-muted); }
.dialog-input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); }
.dialog-error { margin: 0 0 0.75rem; font-size: 0.8125rem; color: var(--color-error); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 0.5rem; }

/* 状态标签：语义色各归其位（pending=等待/警告，completed=完成/成功，refunded=退款/错误） */
.status-tag.shipped { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.completed { background: var(--color-success-light); color: var(--color-success); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.refunded { background: var(--color-light-red); color: var(--color-error); }

/* 分页已收敛全局（style.css） */

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .filter-bar { flex-wrap: wrap; } }
</style>
