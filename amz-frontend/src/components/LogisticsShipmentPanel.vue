<script setup lang="ts">
/**
 * 在途货件列表（从 LogisticsDashboard.vue 拆出，§S33.14）。
 *
 * ## 为什么整个搬进来（含 loader 与分页状态）
 * 与调拨列表、有效报价明细同一处理方式：`shipments` + 4 个分页标志 + 状态筛选
 * 在全文件只有这一个使用者。切出去之后页壳只剩 loader 供 onMounted 首次加载用，
 * 不必为别人的一张表持有 6 个 ref。
 *
 * ## 三个按钮仍由页壳执行
 * 同步 / 轨迹 / 关单同样服务待处理 tab（那边也有同样三个按钮），
 * 且成功路径要动页壳的 overview / shipments / trend，所以留页壳、本组件只发事件。
 * 判据不是「谁是写操作」，而是「这个 handler 有几个使用者」。
 *
 * ## 一条不能省的边界
 * 无店铺时必须清空列表。切走店铺后 `refreshShop()` 返回空串，
 * 若不清空，**上一个店铺的货件会继续显示在新店铺下**——多租户隔离缺口。
 * 这一条在调拨列表上已经栽过一次，那边补了回归测试，这里同样要有。
 */
import { ref } from 'vue'
import { listShipments, type Shipment } from '../api/logistics'
import LogisticsCursorBar from './LogisticsCursorBar.vue'
import { statusLabel, statusTone } from './logisticsStatus'
import { fmtTime } from './logisticsFormat'
import { methodLabel } from './logisticsMethodLabels'
import { useShopGuard } from '../composables/useShopGuard'

const props = defineProps<{
  /** 当前是否激活（页壳的 tab 是不是本面板） */
  active: boolean
  /** 页级操作互斥标记 */
  busy: boolean
}>()

const emit = defineEmits<{
  (e: 'report-error', tag: string, detail: unknown): void
  (e: 'sync', shipmentId: number): void
  (e: 'track', shipmentId: number): void
  (e: 'close', shipmentId: number): void
}>()

const { refreshShop } = useShopGuard()

const PAGE_SIZE = 50

const shipments = ref<Shipment[]>([])
const shipmentsNextCursor = ref<string | null>(null)
const shipmentsTruncated = ref(false)
const shipmentsPageMetaMissing = ref(false)
const shipmentsLoadingMore = ref(false)
const shipmentStatus = ref('')

/** 状态筛选项。与 STATUS_LABELS 同一套键，顺序即货态流转顺序 */
const statusOptions = ['CREATED', 'IN_TRANSIT', 'CUSTOMS', 'DELIVERED', 'RECEIVED', 'DELAYED', 'EXCEPTION', 'CLOSED']

/* ---------- 展示用格式化 ---------- */


const loadShipments = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    // 见文件头「一条不能省的边界」
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
      emit('report-error', '货件列表', res?.message)
      if (!append) shipments.value = []
    }
  } catch (e) {
    console.warn('[Logistics] 加载货件列表失败', e)
    emit('report-error', '货件列表', e)
    if (!append) shipments.value = []
  } finally {
    if (append) shipmentsLoadingMore.value = false
  }
}

const loadMoreShipments = () => {
  if (!shipmentsTruncated.value || !shipmentsNextCursor.value || shipmentsLoadingMore.value) return
  void loadShipments(true)
}

/**
 * 供页壳调用的加载入口。
 *
 * 把关留在页壳（switchTab 判断「已加载」），本组件只管把这一次加载做完。
 * `append` 参数保留：同步 / 关单成功后页壳会再调一次，那两次走的是
 * 「按当前筛选重新加载」而不是追加，语义不同。
 */
defineExpose({ load: loadShipments })
</script>

<template>
  <div v-if="props.active">

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
                      <button class="link-btn" :disabled="busy" @click="emit('sync', s.id!)">同步</button>
                      <button class="link-btn" @click="emit('track', s.id!)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="emit('close', s.id!)">关单</button>
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
          
  </div>
</template>

<style scoped>
/* 不定义任何样式：filter-bar / filter-count / table-card / data-table /
 * status-tag / mono / empty-row / ops / link-btn / table-pager / truncated-tip
 * 全部自共享样式表与 src/style.css。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
