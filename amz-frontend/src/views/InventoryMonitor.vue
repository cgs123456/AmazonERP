<template>
  <div class="inventory-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">库存监控</h1>
        <p class="hero-subtitle">FBA 库存健康度与补货建议</p>
      </div>

      <!-- 骨架屏：健康度卡片 + 表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" aria-hidden="true">
        <div class="health-grid">
          <div v-for="i in 4" :key="i" class="health-card">
            <div class="skeleton sk-count"></div>
            <div class="skeleton sk-line sk-line-sm"></div>
          </div>
        </div>
        <div class="table-card sk-table-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
      </div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看库存数据。
      </div>

      <!-- 健康度概览 - bento grid: 4 个 card, 无空单元格 -->
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

      <!-- 库存列表（客户端分页，避免大店铺全量渲染卡顿） -->
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
            <tr v-for="item in pagedInventory" :key="item.sku">
              <td class="mono">{{ item.sku }}</td>
              <td class="mono">{{ item.asin }}</td>
              <td>{{ item.shop }}</td>
              <td>{{ item.stock }}</td>
              <td>{{ item.dailySales }}</td>
              <td :class="item.days <= 7 ? 'days-urgent' : item.days <= 14 ? 'days-risk' : ''">{{ item.days }} 天</td>
              <td><span class="health-tag" :class="item.level">{{ item.levelText }}</span></td>
              <td>{{ item.suggestQty > 0 ? item.suggestQty + ' 件' : '-' }}</td>
            </tr>
            <tr v-if="!loading && pagedInventory.length === 0">
              <td colspan="8" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:package-variant-closed-remove" width="32" class="empty-icon" />
                  <span>暂无库存数据</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="totalInventory > pageSize" class="table-pager">
          <span class="page-info">第 {{ currentPage }} 页 / 共 {{ totalPagesCount }} 页（{{ totalInventory }} 条）</span>
          <div class="page-actions">
            <button class="page-btn" :disabled="currentPage <= 1" @click="invPage.prevPage">上一页</button>
            <button class="page-btn" :disabled="currentPage >= totalPagesCount" @click="invPage.nextPage">下一页</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getInventoryList, getInventoryHealth } from '@/api/inventory'
import type { InventoryItem, InventoryHealth } from '@/api/inventory'
import { getCurrentShopId } from '@/utils/shop'
import { usePagination } from '@/composables/usePagination'

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

// 客户端分页：每页 20 条
const invPage = usePagination<InventoryItem>(() => inventory.value, 20)
const pagedInventory = invPage.paged
const totalInventory = invPage.total
const currentPage = invPage.page
const pageSize = invPage.size
const totalPagesCount = invPage.totalPages

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
/* 页面基础 */
.inventory-page { background: var(--color-background); }
.main-content { margin-left: 220px; margin-top: 64px; padding: 1rem; }

/* 页头：左对齐 + muted 副标题 */
.hero-section { padding-top: env(safe-area-inset-top); padding-bottom: 1.5rem; }
.hero-title { font-size: var(--font-size-7); font-weight: 700; color: var(--color-on-surface); margin: 0 0 0.25rem 0; line-height: var(--line-height-tight); }
.hero-subtitle { font-size: var(--font-size-2); color: var(--color-muted); margin: 0; line-height: var(--line-height-snug); }

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-count { width: 2.5rem; height: 2rem; margin: 0 auto 0.5rem auto; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 55%; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

/* shop-tip */
.shop-tip {
  padding: 0.75rem 1rem; margin-bottom: 1rem;
  background: var(--color-primary-light); color: var(--color-primary);
  border-radius: var(--radius-md); font-size: 0.875rem; text-align: center;
}

/* health-grid - bento grid: 4 个 card */
.health-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1rem; }

.health-card {
  background: var(--color-surface);
  border-radius: var(--radius-md); /* 12px */
  padding: 1rem; /* 20px */
  text-align: center;
  box-shadow: var(--shadow-sm);
  /* 边框颜色使用语义色标示紧急程度 */
  border-top: 4px solid var(--color-primary);
}

.health-card .health-count {
  font-size: 1.875rem; /* 32px */
  font-weight: 700;
  color: var(--color-on-surface);
  margin-bottom: 0.25rem; /* 4px */
  font-variant-numeric: tabular-nums;
}

/* 顶部边线语义色：与卡片代表的库存状态一致 */
.health-card.urgent { border-top-color: var(--color-error); }
.health-card.risk { border-top-color: var(--color-warning); }
.health-card.healthy { border-top-color: var(--color-success); }
.health-card.overstock { border-top-color: var(--color-muted); }

.health-label {
  font-size: 0.875rem; /* 14px */
  color: var(--color-muted);
  margin-top: 0.125rem; /* 2px */
}

/* table-card */
.table-card { background: var(--color-surface); border-radius: var(--radius-md); overflow-x: auto; box-shadow: var(--shadow-sm); }

/* data-table */
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: var(--color-surface); padding: 0.75rem 1rem; text-align: left; font-size: 0.8125rem; color: var(--color-muted); font-weight: 600; border-bottom: 1px solid var(--color-border); }
.data-table td { padding: 0.75rem 1rem; font-size: 0.875rem; color: var(--color-on-surface); border-bottom: 1px solid var(--color-border); }
.data-table tr:hover { background: var(--color-primary-light); }

.mono { font-family: var(--font-mono); font-size: 0.8125rem; }
.empty-row { text-align: center; color: var(--color-muted); padding: 2rem 0; }

/* days-urgent / days-risk */
.days-urgent { color: var(--color-error); font-weight: 600; }
.days-risk { color: var(--color-warning-dark); font-weight: 600; }

/* health-tag */
.health-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; font-weight: 500; white-space: nowrap; }
.health-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.health-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.health-tag.healthy { background: var(--color-primary-light); color: var(--color-primary); }
.health-tag.overstock { background: var(--color-muted-light); color: var(--color-muted); }

/* table-pager */
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; margin-top: 1rem; }
.page-info { font-size: 0.8125rem; color: var(--color-muted); }
.page-actions { display: flex; gap: 0.5rem; }
.page-btn { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; font-size: 0.8125rem; color: var(--color-on-surface); transition: all 0.2s; }
.page-btn:hover:not(:disabled) { background: var(--color-primary-light); border-color: var(--color-primary); color: var(--color-primary); }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .health-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .health-grid { grid-template-columns: 1fr; } }
</style>
