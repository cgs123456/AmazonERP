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
import { useShopGuard } from '@/composables/useShopGuard'

const filterShop = ref('')
const filterDate = ref('')
const filterOrderNo = ref('')

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
