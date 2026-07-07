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
        <button class="filter-btn">查询</button>
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
            <tr v-for="order in orders" :key="order.id">
              <td class="mono">{{ order.orderNo }}</td>
              <td>{{ order.shop }}</td>
              <td class="mono">{{ order.sku }}</td>
              <td>{{ order.qty }}</td>
              <td>{{ order.amount }}</td>
              <td :class="order.profit > 0 ? 'profit-positive' : 'profit-negative'">{{ order.profit > 0 ? '+' : '' }}{{ order.profit }}</td>
              <td><span class="status-tag" :class="order.statusClass">{{ order.status }}</span></td>
              <td class="mono">{{ order.date }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'

const filterShop = ref('')
const filterDate = ref('')
const filterOrderNo = ref('')

const orders = ref([
  { id: 1, orderNo: '114-1234567-1234567', shop: 'Shop A (US)', sku: 'B08X4-001', qty: 2, amount: '$59.98', profit: 18.50, status: '已发货', statusClass: 'shipped', date: '2026-07-06 14:30' },
  { id: 2, orderNo: '114-2345678-2345678', shop: 'Shop A (US)', sku: 'B08X4-002', qty: 1, amount: '$29.99', profit: 12.30, status: '已完成', statusClass: 'completed', date: '2026-07-06 12:15' },
  { id: 3, orderNo: '114-3456789-3456789', shop: 'Shop B (UK)', sku: 'B08X4-003', qty: 3, amount: '£89.97', profit: 22.80, status: '待发货', statusClass: 'pending', date: '2026-07-06 10:00' },
  { id: 4, orderNo: '114-4567890-4567890', shop: 'Shop C (DE)', sku: 'B08X4-004', qty: 1, amount: '€45.00', profit: -3.20, status: '已退款', statusClass: 'refunded', date: '2026-07-05 18:45' },
  { id: 5, orderNo: '114-5678901-5678901', shop: 'Shop A (US)', sku: 'B08X4-005', qty: 5, amount: '$149.95', profit: 45.60, status: '已发货', statusClass: 'shipped', date: '2026-07-05 16:20' },
  { id: 6, orderNo: '114-6789012-6789012', shop: 'Shop B (UK)', sku: 'B08X4-006', qty: 2, amount: '£55.98', profit: 15.40, status: '已完成', statusClass: 'completed', date: '2026-07-05 09:30' }
])
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

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.mono { font-family: 'Courier New', monospace; font-size: 13px; }
.profit-positive { color: #10b981; font-weight: 600; }
.profit-negative { color: #ef4444; font-weight: 600; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 500; }
.status-tag.shipped { background: #dbeafe; color: #1e40af; }
.status-tag.completed { background: #d1fae5; color: #065f46; }
.status-tag.pending { background: #fef3c7; color: #92400e; }
.status-tag.refunded { background: #fee2e2; color: #991b1b; }
</style>
