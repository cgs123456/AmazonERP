<template>
  <div class="order-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>订单管理</h1>
        <p class="subtitle">Amazon 订单列表与状态跟踪</p>
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

      <div v-if="loading" class="loading-mask">加载中...</div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查询订单数据。
      </div>

      <!-- 订单表格 -->
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
              <td colspan="8" class="empty-row">暂无订单数据</td>
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
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
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

// 订单号搜索为前端筛选（API 未提供该参数）
const displayOrders = computed(() => {
  if (!filterOrderNo.value) return orders.value
  return orders.value.filter(o => o.orderNo.includes(filterOrderNo.value))
})

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
.order-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.filter-bar { display: flex; gap: 12px; margin-bottom: 20px; }
.filter-select, .filter-date, .filter-input { padding: 8px 12px; border: 1px solid #e0e0e0; border-radius: 8px; font-size: 14px; background: #fff; }
.filter-input { flex: 1; max-width: 300px; }
.filter-btn { padding: 8px 20px; background: #4f46e5; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 14px; }
.filter-btn:hover { background: #4338ca; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.shop-tip { padding: 12px 16px; margin-bottom: 16px; background: #fef3c7; color: #92400e; border-radius: 8px; font-size: 14px; text-align: center; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.mono { font-family: 'Courier New', monospace; font-size: 13px; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }
.profit-positive { color: #10b981; font-weight: 600; }
.profit-negative { color: #ef4444; font-weight: 600; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 500; }
.status-tag.shipped { background: #dbeafe; color: #1e40af; }
.status-tag.completed { background: #d1fae5; color: #065f46; }
.status-tag.pending { background: #fef3c7; color: #92400e; }
.status-tag.refunded { background: #fee2e2; color: #991b1b; }

.pagination { display: flex; justify-content: space-between; align-items: center; margin-top: 16px; }
.page-info { font-size: 13px; color: #666; }
.page-actions { display: flex; gap: 8px; }
.page-btn { padding: 6px 16px; border: 1px solid #e0e0e0; border-radius: 8px; background: #fff; cursor: pointer; font-size: 13px; color: #333; }
.page-btn:hover:not(:disabled) { background: #f9fafb; border-color: #4f46e5; color: #4f46e5; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
