<template>
  <div class="order-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">订单管理</h1>
        <p class="hero-subtitle">Amazon 订单列表与状态跟踪</p>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <select v-model="filterShop" class="filter-select">
          <option value="">全部店铺</option>
          <option value="1">Shop A (US)</option>
          <option value="2">Shop B (UK)</option>
          <option value="3">Shop C (DE)</option>
        </select>
        <input type="date" v-model="filterDate" class="filter-date" />
        <input type="text" v-model="filterOrderNo" placeholder="搜索订单号..." class="filter-input" />
        <button class="filter-btn" @click="handleQuery">查询</button>
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
            </tr>
            <tr v-if="!loading && displayOrders.length === 0">
              <td colspan="8" class="empty-row">
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getOrderList } from '@/api/order'
import type { OrderItem } from '@/api/order'
import { getCurrentShopId } from '@/utils/shop'

const filterShop = ref('')
const filterDate = ref('')
const filterOrderNo = ref('')

const loading = ref(false)
const orders = ref<OrderItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

// 当前选中店铺（未选则为空字符串，用于阻断查询并提示用户）
const currentShopId = ref(getCurrentShopId())

// 降级用的 mock 数据
const mockOrders: OrderItem[] = [
  { id: 1, orderNo: '114-1234567-1234567', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-001', qty: 2, amount: '$59.98', profit: 18.50, status: '已发货', statusClass: 'shipped', date: '2026-07-06 14:30' },
  { id: 2, orderNo: '114-2345678-2345678', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-002', qty: 1, amount: '$29.99', profit: 12.30, status: '已完成', statusClass: 'completed', date: '2026-07-06 12:15' },
  { id: 3, orderNo: '114-3456789-3456789', shop: 'Shop B (UK)', shopId: '2', sku: 'B08X4-003', qty: 3, amount: '£89.97', profit: 22.80, status: '待发货', statusClass: 'pending', date: '2026-07-06 10:00' },
  { id: 4, orderNo: '114-4567890-4567890', shop: 'Shop C (DE)', shopId: '3', sku: 'B08X4-004', qty: 1, amount: '€45.00', profit: -3.20, status: '已退款', statusClass: 'refunded', date: '2026-07-05 18:45' },
  { id: 5, orderNo: '114-5678901-5678901', shop: 'Shop A (US)', shopId: '1', sku: 'B08X4-005', qty: 5, amount: '$149.95', profit: 45.60, status: '已发货', statusClass: 'shipped', date: '2026-07-05 16:20' },
  { id: 6, orderNo: '114-6789012-6789012', shop: 'Shop B (UK)', shopId: '2', sku: 'B08X4-006', qty: 2, amount: '£55.98', profit: 15.40, status: '已完成', statusClass: 'completed', date: '2026-07-05 09:30' }
]

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

// 订单号走服务端查询（GET /order/list?orderNo=），分页与总数与之一致
const displayOrders = computed(() => orders.value)

const loadOrders = async () => {
  // 未选择店铺时不发请求，避免网关 MyGlobalFilter 拒绝 /order/ 路径
  const shopId = filterShop.value || currentShopId.value
  if (!shopId) {
    orders.value = []
    total.value = 0
    return
  }
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
    if (res?.code === 200 && res.data) {
      // 兼容分页对象与数组两种返回结构
      if (Array.isArray(res.data)) {
        orders.value = res.data
        total.value = res.data.length
      } else {
        orders.value = res.data.list || []
        total.value = res.data.total || 0
      }
    } else {
      console.warn('[OrderList] 返回数据异常，使用降级数据', res)
      fallbackToMock()
    }
  } catch (e) {
    console.warn('[OrderList] API 调用失败，使用降级数据', e)
    fallbackToMock()
  } finally {
    loading.value = false
  }
}

const fallbackToMock = () => {
  let filtered = [...mockOrders]
  if (filterShop.value) filtered = filtered.filter(o => o.shopId === filterShop.value)
  if (filterDate.value) filtered = filtered.filter(o => o.date.startsWith(filterDate.value))
  if (filterOrderNo.value) filtered = filtered.filter(o => o.orderNo.includes(filterOrderNo.value))
  orders.value = filtered
  total.value = filtered.length
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
</script>

<style scoped>
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

/* shop-tip / table-card / data-table / mono / empty-row / status-tag 基类 / 分页已收敛至全局 style.css */

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }

/* 状态标签：语义色各归其位（pending=等待/警告，completed=完成/成功，refunded=退款/错误） */
.status-tag.shipped { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.completed { background: var(--color-success-light); color: var(--color-success); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.refunded { background: var(--color-light-red); color: var(--color-error); }

/* 分页已收敛全局（style.css） */

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .filter-bar { flex-wrap: wrap; } }
</style>
