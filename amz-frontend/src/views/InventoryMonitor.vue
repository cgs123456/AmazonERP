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
      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
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

      <!-- 健康度概览 - bento grid: 4 个 card, 无空单元格（加载时仅显示骨架） -->
      <template v-if="!loading">
      <div class="health-grid">
        <div class="health-card urgent">
          <div class="health-count">{{ hv(healthCounts.urgent) }}</div>
          <div class="health-label">紧急补货</div>
        </div>
        <div class="health-card risk">
          <div class="health-count">{{ hv(healthCounts.risk) }}</div>
          <div class="health-label">风险库存</div>
        </div>
        <div class="health-card healthy">
          <div class="health-count">{{ hv(healthCounts.healthy) }}</div>
          <div class="health-label">健康库存</div>
        </div>
        <div class="health-card overstock">
          <div class="health-count">{{ hv(healthCounts.overstock) }}</div>
          <div class="health-label">滞销库存</div>
        </div>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>
      <p v-if="planMsg" class="plan-msg" role="status">{{ planMsg }}</p>

      <!-- 补货重算：建议量是后端算出来的，页面上没有"手填建议量"的入口 -->
      <div class="recalc-row">
        <button
          class="recalc-btn"
          :disabled="recalcBusy || !currentShopId"
          :title="currentShopId ? '按当前 FBA 库存与销售历史重算本店补货建议' : '先在右上角选择店铺'"
          @click="onRecalc"
        >{{ recalcBusy ? '重算中...' : '重算补货建议' }}</button>
        <span v-if="recalcMsg" class="recalc-msg" role="status">{{ recalcMsg }}</span>
        <span v-else class="recalc-hint">重算按当前库存与销售历史逐条 upsert 本店的补货建议；已生成的采购计划草稿不受影响</span>
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
              <th>补货动作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in pagedInventory" :key="item.sku + '|' + item.shop">
              <td class="mono">{{ item.sku }}</td>
              <td class="mono">{{ item.asin }}</td>
              <td>{{ item.shop }}</td>
              <td>{{ item.stock }}</td>
              <td>{{ item.dailySales }}</td>
              <td :class="item.days <= 7 ? 'days-urgent' : item.days <= 14 ? 'days-risk' : ''">{{ item.days }} 天</td>
              <td><span class="health-tag" :class="item.level">{{ item.levelText }}</span></td>
              <td>
                <template v-if="item.suggestQty > 0">
                  {{ item.suggestQty }} 件
                  <button class="plan-btn" :disabled="!dataLive || planBusy === item.sku"
                          :title="dataLive ? '按这条补货建议生成草稿采购计划' : '当前显示的不是本店铺真实数据，不能据此建计划'"
                          @click="makePlan(item)">
                    {{ planBusy === item.sku ? '生成中...' : '生成采购计划' }}
                  </button>
                </template>
                <template v-else>-</template>
              </td>
            </tr>
            <tr v-if="!loading && pagedInventory.length === 0">
              <td colspan="9" class="empty-row">
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
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getInventoryList, getInventoryHealth, recalcReplenishment } from '@/api/inventory'
import type { InventoryItem, InventoryHealth } from '@/api/inventory'
import { createPlan } from '@/api/procurement'
import { useShopGuard } from '@/composables/useShopGuard'
import { usePagination } from '@/composables/usePagination'

const loading = ref(false)
const planBusy = ref('')
const planMsg = ref('')
const errors = ref<string[]>([])
/**
 * 列表与健康度都拿到真实数据才算 live。
 * 旧实现在接口失败时直接展示页面里写死的 7 个 SKU——那既不是这家店的库存，
 * 又长得很像库存。这里改成空列表 + 错误条，并且建计划按钮只在 live 时可点：
 * 补货建议会落成采购计划草稿，拿假数据建出来的计划没人会去撤销它。
 */
const dataLive = ref(false)
const healthKnown = ref(false)

const { currentShopId, refreshShop } = useShopGuard()
const inventory = ref<InventoryItem[]>([])
const healthData = ref<InventoryHealth>({ urgent: 0, risk: 0, healthy: 0, overstock: 0 })

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

/** 健康度未知时显示「—」而不是 0：0 会被读成「测出来没有紧急 SKU」 */
const hv = (n: number) => (healthKnown.value ? n : '—')

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

/** 由补货建议生成草稿采购计划；数量、可售天数、建议量与统计日期一起留档到 replenishmentData。 */
const makePlan = async (item: InventoryItem) => {
  const shopId = refreshShop()
  if (!shopId || !dataLive.value) return
  planBusy.value = item.sku
  planMsg.value = ''
  try {
    const res = await createPlan({
      shopId,
      sku: item.sku,
      asin: item.asin,
      suggestedQty: item.suggestQty,
      plannedQty: item.suggestQty,
      urgency: item.days <= 7 ? 'URGENT' : item.days <= 14 ? 'HIGH' : 'NORMAL',
      source: 'AUTO',
      remark: '由库存补货建议生成，待审批',
      replenishmentData: JSON.stringify({
        basis: 'inventory-replenishment-suggestion',
        suggestStatDate: item.suggestStatDate ?? null,
        suggestUrgency: item.suggestUrgency ?? null,
        availableQuantity: item.stock,
        dailySales: item.dailySales,
        daysOfSupply: item.days,
        suggestedReplenishQty: item.suggestQty,
        generatedAt: new Date().toISOString()
      })
    })
    if (res?.code !== 200) {
      pushError(`生成采购计划：${res?.message || '后端拒绝'}`)
      return
    }
    planMsg.value = `已为 ${item.sku} 生成草稿采购计划 ${res.data?.planNo || res.data?.id}，请到采购供应链页审批`
  } catch (e) {
    pushError(`生成采购计划：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    planBusy.value = ''
  }
}

/** 拉一次本店库存与健康度。onMounted 与「重算补货建议」成功后共用同一条路径。 */
const reloadData = async () => {
  // 未选择店铺时不发请求，避免网关校验失败
  const shopId = refreshShop()
  if (!shopId) {
    loading.value = false
    return
  }
  loading.value = true
  errors.value = []

  const jobs: Array<[string, () => Promise<unknown>, (d: any) => void, (ok: boolean) => void]> = [
    ['库存列表', () => getInventoryList(shopId), (d) => { inventory.value = d }, (ok) => { if (ok) dataLive.value = true }],
    ['库存健康度', () => getInventoryHealth(shopId), (d) => { healthData.value = d }, (ok) => { if (ok) healthKnown.value = true }]
  ]

  await Promise.all(jobs.map(async ([name, fn, apply, mark]) => {
    try {
      const res: any = await fn()
      if (res?.code === 200 && res.data) {
        apply(res.data)
        mark(true)
      } else {
        mark(false)
        pushError(`${name}：${(res && res.message) || '接口未返回数据'}`)
      }
    } catch (e) {
      mark(false)
      pushError(`${name}：${e instanceof Error ? e.message : '调用失败'}`)
    }
  }))

  loading.value = false
}

const recalcBusy = ref(false)
const recalcMsg = ref('')

/**
 * 让后端按当前 FBA 库存与销售历史重算补货建议。
 * 返回的是「生成了几条」，列表里的建议量要重新拉才更新，所以成功后必须 reloadData。
 */
const onRecalc = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    pushError('重算补货建议：未选择店铺')
    return
  }
  recalcBusy.value = true
  recalcMsg.value = ''
  try {
    const res = await recalcReplenishment(shopId)
    if (res?.code === 200) {
      recalcMsg.value = `已重算，本次生成 ${res.data ?? 0} 条建议`
      await reloadData()
    } else {
      pushError(`重算补货建议：${res?.message || '接口未返回成功'}`)
    }
  } catch (e) {
    pushError(`重算补货建议：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    recalcBusy.value = false
  }
}

onMounted(reloadData)
</script>

<style scoped>
/* 页面基础 */
.plan-btn { margin-left: 0.5rem; padding: 0.2rem 0.5rem; border: none; border-radius: var(--radius-sm); background: var(--color-primary-light); color: var(--color-primary); font-size: 0.75rem; cursor: pointer; }
.plan-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.plan-msg { font-size: 0.8125rem; color: var(--color-success); margin: 0 0 0.75rem; }
.recalc-row { display: flex; align-items: center; gap: 0.625rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
.recalc-btn { padding: 0.3rem 0.75rem; border: 1px solid var(--color-primary); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-primary); font-size: 0.8125rem; cursor: pointer; }
.recalc-btn:hover:not(:disabled) { background: var(--color-primary-light); }
.recalc-btn:disabled { opacity: 0.5; cursor: not-allowed; border-color: var(--color-border); color: var(--color-muted); }
.recalc-msg { font-size: 0.8125rem; color: var(--color-success); }
.recalc-hint { font-size: 0.75rem; color: var(--color-muted); }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }

.inventory-page { background: var(--color-background); }

/* 页头/主区/表格/分页等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-count { width: 2.5rem; height: 2rem; margin: 0 auto 0.5rem auto; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 55%; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

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

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .health-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .health-grid { grid-template-columns: 1fr; } }
</style>
