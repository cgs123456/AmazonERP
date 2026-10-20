<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  listStocktakes,
  createStocktake,
  getStocktakeDetail,
  countStocktake,
  applyStocktake,
  cancelStocktake,
  STOCKTAKE_STATUS_LABELS,
  type StocktakeRow,
  type StocktakeItemRow
} from '@/api/stocktake'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'
import { WRITE_LOCKED_HINT } from '@/utils/identity'
import type { Warehouse } from '@/api/warehouse'

/**
 * 库存盘点面板（从 Warehouse.vue 拆出，§S32）。
 *
 * 为什么要单独成组件：这一块自带 6 个状态（列表/加载/创建中/保存中/新建弹窗/明细弹窗）
 * 与 6 个操作函数，与仓库列表、库龄、出入库单几乎不互相引用——把它移出去，
 * Warehouse.vue 的 script 立刻少掉约 160 行。
 *
 * 两个刻意的接口决定：
 * 1. **角色（wmsWriteAllowed）由父组件传入**，不在本组件监听 ROLE_CHANGED_EVENT。
 *    角色变更监听是页级关注点（三个角色 ref 一起同步），组件自己监听会变成
 *    「每个面板各注册一个监听器」，而它们的同步时机必须一致。
 * 2. **首次激活才拉数据**用 props.active 驱动（原来在父组件的 watch(activeTab) 里），
 *    语义不变：切到盘点页才请求，避免进来就打所有接口。
 */
const props = defineProps<{
  /** 该 tab 是否激活 */
  active: boolean
  /** WMS 写入口是否放行（角色由父组件同步后传入） */
  wmsWriteAllowed: boolean
  /** 仓库列表：建单时校验「至少有一个仓」，明细里按 id 回显仓库名 */
  warehouses: Warehouse[]
}>()

const { refreshShop } = useShopGuard()
const { showToast } = useToast()

/**
 * 写入口的角色守卫。
 *
 * 为什么每个写入口都要调用它（而不是只在按钮上做 v-if）：
 * 入口隐藏只是体验优化，不是边界。弹窗可能在角色变更前就已打开，或者角色信息
 * （AppHeader 的 getInfo）比弹窗晚到——那种情况下请求照样会发出去并 403。
 *
 * 返回 true 表示可以继续发请求。
 */
const warehouseName = (id?: number) => {
  const w = props.warehouses.find((x) => x.id === id)
  return w ? w.warehouseName : id
}

const wmsWriteBlocked = (action: string): boolean => {
  if (props.wmsWriteAllowed) return false
  showToast(`${action}需要 OPERATOR 或 ADMIN 角色`, 'error')
  return true
}

// ==================== 库存盘点（P1.2） ====================
const stocktakes = ref<StocktakeRow[]>([])
const stocktakeLoading = ref(false)
const stocktakeCreating = ref(false)
const stocktakeSaving = ref(false)
const stocktakeDialog = ref({ visible: false, warehouseId: '', skusText: '', remark: '' })
/** 可编辑明细行：countedQtyInput 绑定输入框（undefined=未盘，落账跳过） */
interface EditableStocktakeItem extends StocktakeItemRow {
  countedQtyInput?: number
}
interface EditableStocktakeDetail {
  stocktake: StocktakeRow
  items: EditableStocktakeItem[]
}
const stocktakeDetail = ref<EditableStocktakeDetail | null>(null)

const loadStocktakes = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  stocktakeLoading.value = true
  try {
    const res = await listStocktakes(shopId)
    if (res?.code === 200 && Array.isArray(res.data)) {
      stocktakes.value = res.data
    } else {
      showToast(res?.message || '加载盘点单失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载盘点单失败', 'error')
  } finally {
    stocktakeLoading.value = false
  }
}

const openStocktakeDialog = () => {
  if (!refreshShop()) return
  if (!props.warehouses.length) {
    showToast('还没有可用仓库：盘点按仓库快照账面数，请先建仓', 'error')
    return
  }
  stocktakeDialog.value = { visible: true, warehouseId: '', skusText: '', remark: '' }
}

const submitStocktake = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  if (wmsWriteBlocked('创建盘点单')) return
  if (!stocktakeDialog.value.warehouseId) {
    showToast('请选择盘点仓库', 'error')
    return
  }
  const skus = stocktakeDialog.value.skusText
    .split(/\r?\n|,/)
    .map(x => x.trim())
    .filter(Boolean)
  if (!skus.length) {
    showToast('请填写至少一个 SKU', 'error')
    return
  }
  stocktakeCreating.value = true
  try {
    const res = await createStocktake({
      shopId,
      warehouseId: stocktakeDialog.value.warehouseId,
      remark: stocktakeDialog.value.remark || undefined,
      skus
    })
    if (res?.code === 200) {
      stocktakeDialog.value.visible = false
      showToast(`盘点单 ${res.data?.stocktakeNo || ''} 已创建`, 'success')
      await loadStocktakes()
    } else {
      showToast(res?.message || '创建盘点单失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '创建盘点单失败', 'error')
  } finally {
    stocktakeCreating.value = false
  }
}

const openStocktakeDetail = async (st: StocktakeRow) => {
  if (!st.id) return
  try {
    const res = await getStocktakeDetail(st.id)
    if (res?.code === 200 && res.data) {
      stocktakeDetail.value = {
        stocktake: res.data.stocktake,
        items: (res.data.items || []).map(item => ({
          ...item,
          countedQtyInput: item.countedQty ?? undefined
        }))
      }
    } else {
      showToast(res?.message || '加载盘点详情失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载盘点详情失败', 'error')
  }
}

const saveCounted = async () => {
  const detail = stocktakeDetail.value
  if (!detail?.stocktake.id) return
  if (wmsWriteBlocked('录入实盘')) return
  const counts = detail.items
    .filter(item => item.countedQtyInput != null)
    .map(item => ({ sku: item.sku || '', countedQty: Number(item.countedQtyInput) }))
  if (!counts.length) {
    showToast('至少为一个 SKU 录入实盘数（留空 = 未盘）', 'error')
    return
  }
  stocktakeSaving.value = true
  try {
    const res = await countStocktake(detail.stocktake.id, counts)
    if (res?.code === 200) {
      showToast('实盘已保存', 'success')
      stocktakeDetail.value = null
      await loadStocktakes()
    } else {
      showToast(res?.message || '保存实盘失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存实盘失败', 'error')
  } finally {
    stocktakeSaving.value = false
  }
}

const applyStocktakeRow = async (st: StocktakeRow) => {
  if (!st.id) return
  if (wmsWriteBlocked('盘点落账')) return
  try {
    const res = await applyStocktake(st.id)
    if (res?.code === 200) {
      const d = res.data
      showToast(`落账完成：修正 ${d?.adjusted ?? 0} 行，未盘跳过 ${d?.skippedUncounted ?? 0}，零差异 ${d?.noDiff ?? 0}`, 'success')
      await loadStocktakes()
    } else {
      showToast(res?.message || '落账失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '落账失败', 'error')
  }
}

const cancelStocktakeRow = async (st: StocktakeRow) => {
  if (!st.id) return
  if (wmsWriteBlocked('取消盘点单')) return
  try {
    const res = await cancelStocktake(st.id)
    if (res?.code === 200) {
      await loadStocktakes()
    } else {
      showToast(res?.message || '取消失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '取消失败', 'error')
  }
}

// 首次切到本页才拉数据（与父组件原来的 watch(activeTab) 语义一致）
watch(
  () => props.active,
  (v) => {
    if (v) void loadStocktakes()
  },
  { immediate: true }
)
</script>

<template>
      <!-- 库存盘点（P1.2） -->
      <div v-show="props.active" class="panel">
        <div class="panel-header">
          <h3>库存盘点</h3>
          <button v-if="wmsWriteAllowed" class="primary-btn" @click="openStocktakeDialog()">+ 新建盘点单</button>
          <span v-else class="write-locked" role="note">盘点操作{{ WRITE_LOCKED_HINT }}</span>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr><th>盘点单号</th><th>仓库</th><th>状态</th><th>创建时间</th><th>落账时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="st in stocktakes" :key="st.id">
                <td class="mono">{{ st.stocktakeNo }}</td>
                <td>{{ warehouseName(st.warehouseId) }}</td>
                <td>{{ STOCKTAKE_STATUS_LABELS[st.status || ''] || st.status }}</td>
                <td class="mono">{{ (st.createTime || '').slice(0, 19).replace('T', ' ') }}</td>
                <td class="mono">{{ (st.appliedAt || '').slice(0, 19).replace('T', ' ') || '—' }}</td>
                <td>
                  <div class="row-actions">
                    <button class="row-btn" @click="openStocktakeDetail(st)">明细</button>
                    <template v-if="wmsWriteAllowed">
                      <button v-if="st.status === 'COUNTED'" class="row-btn primary" @click="applyStocktakeRow(st)">落账</button>
                      <button v-if="st.status === 'DRAFT' || st.status === 'COUNTED'" class="row-btn" @click="cancelStocktakeRow(st)">取消</button>
                      </template>
                                </div>
                </td>
              </tr>
              <tr v-if="!stocktakeLoading && !stocktakes.length">
                <td colspan="6" class="empty-row">暂无盘点单：建单会按仓库+SKU 快照账面数，落账按实盘修正并写库存流水</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 新建盘点弹窗 -->
      <div v-if="stocktakeDialog.visible" class="modal-mask" @click.self="stocktakeDialog.visible = false">
        <div class="modal">
          <h3>新建盘点单</h3>
          <div class="form-row">
            <label>仓库
              <select v-model="stocktakeDialog.warehouseId">
                <option value="">请选择</option>
                <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
              </select>
            </label>
          </div>
          <div class="form-row">
            <label>SKU 清单（每行一个）</label>
            <textarea v-model="stocktakeDialog.skusText" rows="6" placeholder="SKU-001&#10;SKU-002" />
          </div>
          <div class="form-row">
            <label>备注<input v-model="stocktakeDialog.remark" /></label>
          </div>
          <p class="muted">建单会快照各 SKU 当前账面数；未盘的行落账时跳过（不按 0 处理）。</p>
          <div class="modal-actions">
            <button class="row-btn" @click="stocktakeDialog.visible = false">取消</button>
            <button class="primary-btn" :disabled="stocktakeCreating" @click="submitStocktake">
              {{ stocktakeCreating ? '创建中…' : '创建' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 盘点明细弹窗 -->
      <div v-if="stocktakeDetail" class="modal-mask" @click.self="stocktakeDetail = null">
        <div class="modal modal-wide">
          <h3>盘点明细 {{ stocktakeDetail.stocktake.stocktakeNo }}</h3>
          <table class="data-table">
            <thead>
              <tr><th>SKU</th><th>账面快照</th><th>实盘数量</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in stocktakeDetail.items" :key="item.id">
                <td class="mono">{{ item.sku }}</td>
                <td>{{ item.expectedQty }}</td>
                <td>
                  <input
                    v-model.number="item.countedQtyInput"
                    type="number"
                    min="0"
                    :disabled="stocktakeDetail.stocktake.status !== 'DRAFT' && stocktakeDetail.stocktake.status !== 'COUNTED'"
                    style="width: 6rem"
                  />
                </td>
              </tr>
            </tbody>
          </table>
          <p class="muted">留空 = 未盘，落账时跳过；「快照差额」仅供报告，落账以「apply 时真实账面 → 实盘」为准。</p>
          <div class="modal-actions">
            <button class="row-btn" @click="stocktakeDetail = null">关闭</button>
            <button
              v-if="stocktakeDetail.stocktake.status === 'DRAFT' || stocktakeDetail.stocktake.status === 'COUNTED'"
              class="primary-btn"
              :disabled="stocktakeSaving"
              @click="saveCounted"
            >
              {{ stocktakeSaving ? '保存中…' : '保存实盘' }}
            </button>
          </div>
        </div>
      </div>
</template>

<style scoped>
/*
 * 从 Warehouse.vue 搬来的本面板专用样式（scoped CSS 不跨组件）。
 *
 * 只搬 Warehouse 里定义的类：table-card / data-table / empty-row / row-btn 等
 * 是 src/style.css 的全局类，对所有组件生效，不需要重复声明。
 * 这与 MasterCsvImport.vue 的做法一致（它自己重新声明 .error-zone）。
 */
.write-locked { color: var(--color-muted); font-size: 0.75rem; }

.panel { margin-bottom: 1rem; }

.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }

.panel-header h3 { font-size: 1rem; font-weight: 600; margin: 0; color: var(--color-on-surface); }

.panel-header .header-actions { display: flex; gap: 0.5rem; }

.status-tag.active { background: var(--color-success-light); color: var(--color-success); }

.status-tag.inactive { background: var(--color-muted-light); color: var(--color-muted); }

.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }

.primary-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--amz-radius-md); cursor: pointer; font-size: 0.8125rem; font-weight: 500; transition: background 0.2s; }

.primary-btn:hover { background: var(--color-primary-dark); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }

.modal { background: var(--color-surface); border-radius: var(--amz-radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }

.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }

.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }

.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); font-size: 0.875rem; }

.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }

.helper { font-size: 0.8125rem; color: var(--color-muted); margin: 0.75rem 0 0.5rem; }
</style>
