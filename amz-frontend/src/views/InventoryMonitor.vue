<template>
  <div class="inventory-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>库存监控</h1>
        <p class="subtitle">FBA 库存健康度与补货建议</p>
      </div>

      <div v-if="loading" class="loading-mask">加载中...</div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看库存数据。
      </div>

      <!-- 健康度概览 -->
      <div class="health-grid">
        <div class="health-card urgent">
          <div class="health-count">{{ healthCounts.urgent }}</div>
          <div class="health-label">紧急补货</div>
        </div>
        <div class="health-card risk">
          <div class="health-count">{{ healthCounts.risk }}</div>
          <div class="health-label">风险库存</div>
        </div>
        <div class="health-card healthy">
          <div class="health-count">{{ healthCounts.healthy }}</div>
          <div class="health-label">健康库存</div>
        </div>
        <div class="health-card overstock">
          <div class="health-count">{{ healthCounts.overstock }}</div>
          <div class="health-label">滞销库存</div>
        </div>
      </div>

      <!-- 库存列表 -->
      <div class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th>SKU</th>
              <th>ASIN</th>
              <th>店铺</th>
              <th>FBA 库存</th>
              <th>日均销量</th>
              <th>可售天数</th>
              <th>健康度</th>
              <th>建议补货</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in inventory" :key="item.sku">
              <td class="mono">{{ item.sku }}</td>
              <td class="mono">{{ item.asin }}</td>
              <td>{{ item.shop }}</td>
              <td>{{ item.stock }}</td>
              <td>{{ item.dailySales }}</td>
              <td :class="item.days <= 7 ? 'days-urgent' : item.days <= 14 ? 'days-risk' : ''">{{ item.days }} 天</td>
              <td><span class="health-tag" :class="item.level">{{ item.levelText }}</span></td>
              <td>{{ item.suggestQty > 0 ? item.suggestQty + ' 件' : '-' }}</td>
            </tr>
            <tr v-if="!loading && inventory.length === 0">
              <td colspan="8" class="empty-row">暂无库存数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getInventoryList, getInventoryHealth } from '@/api/inventory'
import type { InventoryItem, InventoryHealth } from '@/api/inventory'
import { getCurrentShopId } from '@/utils/shop'

const loading = ref(false)

// 当前选中店铺（未选则为空字符串，用于阻断查询并提示用户）
const currentShopId = ref(getCurrentShopId())

// 降级用的 mock 数据
const mockInventory: InventoryItem[] = [
  { sku: 'B08X4-001', asin: 'B08X4ABC01', shop: 'Shop A (US)', stock: 32, dailySales: 8, days: 4, level: 'urgent', levelText: '紧急', suggestQty: 200 },
  { sku: 'B08X4-002', asin: 'B08X4ABC02', shop: 'Shop A (US)', stock: 56, dailySales: 6, days: 9, level: 'risk', levelText: '风险', suggestQty: 150 },
  { sku: 'B08X4-003', asin: 'B08X4ABC03', shop: 'Shop B (UK)', stock: 180, dailySales: 5, days: 36, level: 'healthy', levelText: '健康', suggestQty: 0 },
  { sku: 'B08X4-004', asin: 'B08X4ABC04', shop: 'Shop B (UK)', stock: 12, dailySales: 2, days: 6, level: 'urgent', levelText: '紧急', suggestQty: 100 },
  { sku: 'B08X4-005', asin: 'B08X4ABC05', shop: 'Shop C (DE)', stock: 450, dailySales: 3, days: 150, level: 'overstock', levelText: '滞销', suggestQty: 0 },
  { sku: 'B08X4-006', asin: 'B08X4ABC06', shop: 'Shop C (DE)', stock: 95, dailySales: 7, days: 14, level: 'risk', levelText: '风险', suggestQty: 80 },
  { sku: 'B08X4-007', asin: 'B08X4ABC07', shop: 'Shop A (US)', stock: 220, dailySales: 10, days: 22, level: 'healthy', levelText: '健康', suggestQty: 0 }
]
const mockHealth: InventoryHealth = { urgent: 2, risk: 2, healthy: 2, overstock: 1 }

const inventory = ref<InventoryItem[]>([...mockInventory])
const healthData = ref<InventoryHealth>({ ...mockHealth })

const healthCounts = computed(() => ({
  urgent: healthData.value.urgent,
  risk: healthData.value.risk,
  healthy: healthData.value.healthy,
  overstock: healthData.value.overstock
}))

onMounted(async () => {
  // 未选择店铺时不发请求，避免网关校验失败
  const shopId = currentShopId.value
  if (!shopId) {
    loading.value = false
    return
  }
  loading.value = true

  // 并行请求库存列表与健康度
  const tasks = [
    {
      fn: () => getInventoryList(shopId),
      onSuccess: (data: InventoryItem[]) => { inventory.value = data },
      mock: mockInventory,
      tag: 'getInventoryList'
    },
    {
      fn: () => getInventoryHealth(shopId),
      onSuccess: (data: InventoryHealth) => { healthData.value = data },
      mock: mockHealth,
      tag: 'getInventoryHealth'
    }
  ]

  await Promise.all(
    tasks.map(async (t) => {
      try {
        const res = await t.fn()
        if (res?.code === 200 && res.data) {
          t.onSuccess(res.data as any)
        } else {
          console.warn(`[InventoryMonitor] ${t.tag} 返回数据异常，使用降级数据`, res)
        }
      } catch (e) {
        console.warn(`[InventoryMonitor] ${t.tag} 调用失败，使用降级数据`, e)
      }
    })
  )

  loading.value = false
})
</script>

<style scoped>
.inventory-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.shop-tip { padding: 12px 16px; margin-bottom: 16px; background: #fef3c7; color: #92400e; border-radius: 8px; font-size: 14px; text-align: center; }

.health-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 24px; }
.health-card { background: #fff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.04); border-top: 4px solid; }
.health-card.urgent { border-color: #ef4444; }
.health-card.risk { border-color: #f59e0b; }
.health-card.healthy { border-color: #10b981; }
.health-card.overstock { border-color: #6366f1; }
.health-count { font-size: 32px; font-weight: 700; color: #1a1a2e; }
.health-label { font-size: 14px; color: #666; margin-top: 4px; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.mono { font-family: 'Courier New', monospace; font-size: 13px; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }
.days-urgent { color: #ef4444; font-weight: 600; }
.days-risk { color: #f59e0b; font-weight: 600; }

.health-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 500; }
.health-tag.urgent { background: #fee2e2; color: #991b1b; }
.health-tag.risk { background: #fef3c7; color: #92400e; }
.health-tag.healthy { background: #d1fae5; color: #065f46; }
.health-tag.overstock { background: #e0e7ff; color: #3730a3; }
</style>
