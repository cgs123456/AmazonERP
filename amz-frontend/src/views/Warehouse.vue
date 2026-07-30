<template>
  <div class="warehouse-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>海外仓 / WMS</h1>
        <p class="subtitle">仓库管理、入库单、出库单与库存查询</p>
      </div>

      <!-- Tab 切换 -->
      <div class="tab-bar">
        <div
          v-for="tab in tabs"
          :key="tab.key"
          class="tab-item"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          <Icon :icon="tab.icon" width="18" />
          <span>{{ tab.label }}</span>
        </div>
      </div>

      <div v-if="loading" class="loading-mask">加载中...</div>

      <!-- 仓库列表 -->
      <div v-show="activeTab === 'warehouse'" class="panel">
        <div class="panel-header">
          <h3>仓库列表</h3>
          <button class="primary-btn" @click="openWarehouseDialog()">+ 新建仓库</button>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>仓库名称</th><th>编码</th><th>类型</th><th>国家</th>
                <th>城市</th><th>联系人</th><th>容量(m³)</th><th>已用(m³)</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="w in warehouses" :key="w.id">
                <td>{{ w.warehouseName }}</td>
                <td>{{ w.warehouseCode }}</td>
                <td>{{ w.warehouseType }}</td>
                <td>{{ w.country }}</td>
                <td>{{ w.city || '-' }}</td>
                <td>{{ w.contactName || '-' }}</td>
                <td>{{ w.capacityCbm || '-' }}</td>
                <td>{{ w.usedCbm || 0 }}</td>
                <td><span class="status-tag" :class="w.status === 'ACTIVE' ? 'active' : 'inactive'">{{ w.status }}</span></td>
              </tr>
              <tr v-if="!loading && warehouses.length === 0"><td colspan="9" class="empty-row">暂无仓库数据</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 仓库库存 -->
      <div v-show="activeTab === 'inventory'" class="panel">
        <div class="panel-header">
          <h3>仓库库存查询</h3>
          <div class="filter-bar">
            <select v-model="invFilter.warehouseId">
              <option :value="undefined">全部仓库</option>
              <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
            </select>
            <input v-model="invFilter.sku" placeholder="按 SKU 筛选" />
            <button class="primary-btn" @click="loadInventory">查询</button>
          </div>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>SKU</th><th>ASIN</th><th>仓库ID</th><th>可用库存</th>
                <th>总库存</th><th>预留</th><th>在途</th><th>库位码</th><th>批次号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="inv in inventoryList" :key="inv.id">
                <td>{{ inv.sku }}</td>
                <td>{{ inv.asin || '-' }}</td>
                <td>{{ inv.warehouseId }}</td>
                <td class="num-good">{{ inv.availableQuantity }}</td>
                <td>{{ inv.quantity }}</td>
                <td>{{ inv.reservedQuantity }}</td>
                <td>{{ inv.inboundQuantity }}</td>
                <td>{{ inv.locationCode || '-' }}</td>
                <td>{{ inv.batchNo || '-' }}</td>
              </tr>
              <tr v-if="!loading && inventoryList.length === 0"><td colspan="9" class="empty-row">暂无库存数据</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 入库单 -->
      <div v-show="activeTab === 'inbound'" class="panel">
        <div class="panel-header">
          <h3>入库单列表</h3>
          <button class="primary-btn" @click="openInboundDialog()">+ 创建入库单</button>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>入库单号</th><th>仓库</th><th>来源</th><th>关联单号</th>
                <th>状态</th><th>总数</th><th>已收</th><th>预计到货</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in inboundOrders" :key="o.id">
                <td>{{ o.inboundNo }}</td>
                <td>{{ warehouseName(o.warehouseId) }}</td>
                <td>{{ o.source || '-' }}</td>
                <td>{{ o.referenceNo || '-' }}</td>
                <td><span class="status-tag" :class="inboundStatusClass(o.status)">{{ o.status }}</span></td>
                <td>{{ o.totalItems }}</td>
                <td>{{ o.receivedItems }}</td>
                <td>{{ o.expectedArrival || '-' }}</td>
                <td>
                  <button v-if="o.status === 'PENDING'" class="action-btn" @click="doTransit(o.id!)">运输中</button>
                  <button v-if="o.status === 'IN_TRANSIT' || o.status === 'PARTIAL'" class="action-btn" @click="doReceive(o.id!)">到货验收</button>
                  <button v-if="canCancelInbound(o.status)" class="action-btn cancel" @click="doCancelInbound(o.id!)">取消</button>
                </td>
              </tr>
              <tr v-if="!loading && inboundOrders.length === 0"><td colspan="9" class="empty-row">暂无入库单</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 出库单 -->
      <div v-show="activeTab === 'outbound'" class="panel">
        <div class="panel-header">
          <h3>出库单列表</h3>
          <button class="primary-btn" @click="openOutboundDialog()">+ 创建出库单</button>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>出库单号</th><th>仓库</th><th>类型</th><th>关联单号</th>
                <th>状态</th><th>承运商</th><th>追踪号</th><th>发货数</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in outboundOrders" :key="o.id">
                <td>{{ o.outboundNo }}</td>
                <td>{{ warehouseName(o.warehouseId) }}</td>
                <td>{{ o.orderType || '-' }}</td>
                <td>{{ o.referenceNo || '-' }}</td>
                <td><span class="status-tag" :class="outboundStatusClass(o.status)">{{ o.status }}</span></td>
                <td>{{ o.carrier || '-' }}</td>
                <td>{{ o.trackingNo || '-' }}</td>
                <td>{{ o.shippedItems }}</td>
                <td>
                  <button v-if="o.status === 'PENDING'" class="action-btn" @click="doPick(o.id!)">拣货</button>
                  <button v-if="o.status === 'PICKING'" class="action-btn" @click="doPack(o.id!)">打包</button>
                  <button v-if="o.status === 'PACKED'" class="action-btn" @click="doShip(o.id!)">发货</button>
                  <button v-if="canCancelOutbound(o.status)" class="action-btn cancel" @click="doCancelOutbound(o.id!)">取消</button>
                </td>
              </tr>
              <tr v-if="!loading && outboundOrders.length === 0"><td colspan="9" class="empty-row">暂无出库单</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 仓库弹窗 -->
      <div v-if="warehouseDialog.visible" class="modal-mask" @click.self="warehouseDialog.visible = false">
        <div class="modal">
          <h3>{{ warehouseDialog.form.id ? '编辑仓库' : '新建仓库' }}</h3>
          <div class="form-grid">
            <label>仓库名称<input v-model="warehouseDialog.form.warehouseName" /></label>
            <label>仓库编码<input v-model="warehouseDialog.form.warehouseCode" /></label>
            <label>类型
              <select v-model="warehouseDialog.form.warehouseType">
                <option value="THIRD_PARTY">第三方</option>
                <option value="FBA">FBA</option>
                <option value="AWD">AWD</option>
              </select>
            </label>
            <label>国家<input v-model="warehouseDialog.form.country" /></label>
            <label>城市<input v-model="warehouseDialog.form.city" /></label>
            <label>地址<input v-model="warehouseDialog.form.address" /></label>
            <label>联系人<input v-model="warehouseDialog.form.contactName" /></label>
            <label>联系电话<input v-model="warehouseDialog.form.contactPhone" /></label>
            <label>容量(m³)<input v-model.number="warehouseDialog.form.capacityCbm" type="number" /></label>
          </div>
          <div class="modal-actions">
            <button class="ghost-btn" @click="warehouseDialog.visible = false">取消</button>
            <button class="primary-btn" @click="submitWarehouse">保存</button>
          </div>
        </div>
      </div>

      <!-- 入库单弹窗 -->
      <div v-if="inboundDialog.visible" class="modal-mask" @click.self="inboundDialog.visible = false">
        <div class="modal">
          <h3>创建入库单</h3>
          <div class="form-grid">
            <label>仓库
              <select v-model="inboundDialog.form.warehouseId">
                <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
              </select>
            </label>
            <label>来源
              <select v-model="inboundDialog.form.source">
                <option value="FBA_TRANSFER">FBA 调拨</option>
                <option value="1688_PURCHASE">1688 采购</option>
                <option value="OTHER">其他</option>
              </select>
            </label>
            <label>关联单号<input v-model="inboundDialog.form.referenceNo" /></label>
            <label>预计到货<input v-model="inboundDialog.form.expectedArrival" type="date" /></label>
            <label>总件数<input v-model.number="inboundDialog.form.totalItems" type="number" /></label>
          </div>
          <div class="modal-actions">
            <button class="ghost-btn" @click="inboundDialog.visible = false">取消</button>
            <button class="primary-btn" @click="submitInbound">创建</button>
          </div>
        </div>
      </div>

      <!-- 出库单弹窗 -->
      <div v-if="outboundDialog.visible" class="modal-mask" @click.self="outboundDialog.visible = false">
        <div class="modal">
          <h3>创建出库单</h3>
          <div class="form-grid">
            <label>仓库
              <select v-model="outboundDialog.form.warehouseId">
                <option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.warehouseName }}</option>
              </select>
            </label>
            <label>出库类型
              <select v-model="outboundDialog.form.orderType">
                <option value="ORDER">订单出库</option>
                <option value="TRANSFER">调拨</option>
                <option value="RETURN">退货</option>
                <option value="SCRAP">报废</option>
              </select>
            </label>
            <label>关联单号<input v-model="outboundDialog.form.referenceNo" /></label>
            <label>承运商<input v-model="outboundDialog.form.carrier" /></label>
            <label>追踪号<input v-model="outboundDialog.form.trackingNo" /></label>
            <label>总件数<input v-model.number="outboundDialog.form.totalItems" type="number" /></label>
          </div>
          <div class="modal-actions">
            <button class="ghost-btn" @click="outboundDialog.visible = false">取消</button>
            <button class="primary-btn" @click="submitOutbound">创建</button>
          </div>
        </div>
      </div>

      <!-- 发货弹窗 -->
      <div v-if="shipDialog.visible" class="modal-mask" @click.self="shipDialog.visible = false">
        <div class="modal">
          <h3>发货 - 库存扣减</h3>
          <div class="form-grid">
            <label>承运商<input v-model="shipDialog.carrier" /></label>
            <label>追踪号<input v-model="shipDialog.trackingNo" /></label>
          </div>
          <p class="helper">请填写本次发货明细（用于扣减库存）：</p>
          <div v-for="(item, idx) in shipDialog.items" :key="idx" class="inline-row">
            <input v-model="item.sku" placeholder="SKU" />
            <input v-model.number="item.quantity" type="number" placeholder="数量" />
            <button class="action-btn cancel" @click="shipDialog.items.splice(idx, 1)">删除</button>
          </div>
          <button class="ghost-btn" @click="shipDialog.items.push({ sku: '', quantity: 1 })">+ 添加明细</button>
          <div class="modal-actions">
            <button class="ghost-btn" @click="shipDialog.visible = false">取消</button>
            <button class="primary-btn" @click="confirmShip">确认发货</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import * as WH from '@/api/warehouse'
import type { Warehouse, WarehouseInventory, InboundOrder, OutboundOrder } from '@/api/warehouse'

const SHOP_ID = 1
const loading = ref(false)
const activeTab = ref<'warehouse' | 'inventory' | 'inbound' | 'outbound'>('warehouse')
const tabs = [
  { key: 'warehouse', label: '仓库列表', icon: 'mdi:warehouse' },
  { key: 'inventory', label: '库存查询', icon: 'mdi:package-variant-closed' },
  { key: 'inbound', label: '入库单', icon: 'mdi:truck-in' },
  { key: 'outbound', label: '出库单', icon: 'mdi:truck-out' }
] as const

const warehouses = ref<Warehouse[]>([])
const inventoryList = ref<WarehouseInventory[]>([])
const inboundOrders = ref<InboundOrder[]>([])
const outboundOrders = ref<OutboundOrder[]>([])
const invFilter = reactive<{ warehouseId?: number; sku?: string }>({})

const warehouseName = (id?: number) => {
  const w = warehouses.value.find((x) => x.id === id)
  return w ? w.warehouseName : id
}

const loadWarehouses = async () => {
  try {
    const res = await WH.listWarehouses(SHOP_ID)
    if (res?.code === 200) warehouses.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载仓库失败', e) }
}

const loadInventory = async () => {
  loading.value = true
  try {
    const res = await WH.listInventory({ shopId: SHOP_ID, ...invFilter })
    if (res?.code === 200) inventoryList.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载库存失败', e) } finally { loading.value = false }
}

const loadInbound = async () => {
  try {
    const res = await WH.listInboundOrders(SHOP_ID)
    if (res?.code === 200) inboundOrders.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载入库单失败', e) }
}

const loadOutbound = async () => {
  try {
    const res = await WH.listOutboundOrders(SHOP_ID)
    if (res?.code === 200) outboundOrders.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载出库单失败', e) }
}

// ===== 仓库表单 =====
const warehouseDialog = reactive<{ visible: boolean; form: Warehouse }>({
  visible: false,
  form: { shopId: SHOP_ID, warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
})
const openWarehouseDialog = () => {
  warehouseDialog.form = { shopId: SHOP_ID, warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
  warehouseDialog.visible = true
}
const submitWarehouse = async () => {
  try {
    await WH.createWarehouse(warehouseDialog.form)
    warehouseDialog.visible = false
    await loadWarehouses()
  } catch (e) { console.warn('[Warehouse] 创建仓库失败', e) }
}

// ===== 入库单表单 =====
const inboundDialog = reactive<{ visible: boolean; form: InboundOrder }>({
  visible: false,
  form: { shopId: SHOP_ID, warehouseId: 0, source: '1688_PURCHASE', totalItems: 0 }
})
const openInboundDialog = () => {
  inboundDialog.form = { shopId: SHOP_ID, warehouseId: warehouses.value[0]?.id || 0, source: '1688_PURCHASE', totalItems: 0 }
  inboundDialog.visible = true
}
const submitInbound = async () => {
  try {
    await WH.createInboundOrder(inboundDialog.form)
    inboundDialog.visible = false
    await loadInbound()
  } catch (e) { console.warn('[Warehouse] 创建入库单失败', e) }
}

const doTransit = async (id: number) => { await WH.transitInbound(id); await loadInbound() }
const doReceive = async (id: number) => {
  // 简化：到货验收无明细，直接完成。实际场景应填写收货明细。
  const items: WarehouseInventory[] = []
  const sku = window.prompt('收货 SKU（留空则不增加库存）')
  if (sku) {
    const qty = Number(window.prompt('收货数量', '1') || '0')
    if (qty > 0) items.push({ warehouseId: 0, shopId: SHOP_ID, sku, quantity: qty } as WarehouseInventory)
  }
  await WH.receiveInbound(id, items)
  await loadInbound()
  await loadInventory()
}
const doCancelInbound = async (id: number) => { await WH.cancelInbound(id); await loadInbound() }
const canCancelInbound = (s?: string) => s === 'PENDING' || s === 'IN_TRANSIT' || s === 'PARTIAL'

// ===== 出库单表单 =====
const outboundDialog = reactive<{ visible: boolean; form: OutboundOrder }>({
  visible: false,
  form: { shopId: SHOP_ID, warehouseId: 0, orderType: 'ORDER', totalItems: 0 }
})
const openOutboundDialog = () => {
  outboundDialog.form = { shopId: SHOP_ID, warehouseId: warehouses.value[0]?.id || 0, orderType: 'ORDER', totalItems: 0 }
  outboundDialog.visible = true
}
const submitOutbound = async () => {
  try {
    await WH.createOutboundOrder(outboundDialog.form)
    outboundDialog.visible = false
    await loadOutbound()
  } catch (e) { console.warn('[Warehouse] 创建出库单失败', e) }
}

const doPick = async (id: number) => { await WH.pickOutbound(id); await loadOutbound() }
const doPack = async (id: number) => { await WH.packOutbound(id); await loadOutbound() }
const shipDialog = reactive<{ visible: boolean; id: number; carrier: string; trackingNo: string; items: { sku: string; quantity: number }[] }>({
  visible: false, id: 0, carrier: '', trackingNo: '', items: [{ sku: '', quantity: 1 }]
})
const doShip = (id: number) => {
  shipDialog.id = id
  shipDialog.carrier = ''
  shipDialog.trackingNo = ''
  shipDialog.items = [{ sku: '', quantity: 1 }]
  shipDialog.visible = true
}
const confirmShip = async () => {
  const items: WarehouseInventory[] = shipDialog.items
    .filter((x) => x.sku && x.quantity > 0)
    .map((x) => ({ warehouseId: 0, shopId: SHOP_ID, sku: x.sku, quantity: x.quantity } as WarehouseInventory))
  await WH.shipOutbound(shipDialog.id, { carrier: shipDialog.carrier, trackingNo: shipDialog.trackingNo, items })
  shipDialog.visible = false
  await loadOutbound()
  await loadInventory()
}
const doCancelOutbound = async (id: number) => { await WH.cancelOutbound(id); await loadOutbound() }
const canCancelOutbound = (s?: string) => s === 'PENDING' || s === 'PICKING'

const inboundStatusClass = (s?: string) => {
  if (s === 'RECEIVED') return 'active'
  if (s === 'CANCELLED') return 'inactive'
  return 'pending'
}
const outboundStatusClass = (s?: string) => {
  if (s === 'SHIPPED') return 'active'
  if (s === 'CANCELLED') return 'inactive'
  return 'pending'
}

onMounted(async () => {
  loading.value = true
  try {
    await loadWarehouses()
    await Promise.all([loadInventory(), loadInbound(), loadOutbound()])
  } finally { loading.value = false }
})
</script>

<style scoped>
.warehouse-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.tab-bar { display: flex; gap: 8px; margin-bottom: 20px; border-bottom: 1px solid #e5e7eb; }
.tab-item { display: flex; align-items: center; gap: 6px; padding: 10px 16px; cursor: pointer; color: #6b7280; font-size: 14px; border-bottom: 2px solid transparent; transition: all 0.2s; }
.tab-item:hover { color: #1a1a2e; }
.tab-item.active { color: #4f46e5; border-bottom-color: #4f46e5; font-weight: 600; }

.panel { margin-bottom: 24px; }
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.panel-header h3 { font-size: 16px; font-weight: 600; margin: 0; }
.filter-bar { display: flex; gap: 8px; align-items: center; }
.filter-bar select, .filter-bar input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }
.num-good { color: #10b981; font-weight: 600; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; }
.status-tag.active { background: #d1fae5; color: #065f46; }
.status-tag.inactive { background: #fee2e2; color: #991b1b; }
.status-tag.pending { background: #fef3c7; color: #92400e; }

.primary-btn { padding: 8px 16px; background: #4f46e5; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 13px; }
.primary-btn:hover { background: #4338ca; }
.ghost-btn { padding: 8px 16px; background: transparent; color: #6b7280; border: 1px solid #d1d5db; border-radius: 8px; cursor: pointer; font-size: 13px; }
.action-btn { padding: 4px 10px; background: #eef2ff; color: #4f46e5; border: none; border-radius: 6px; cursor: pointer; font-size: 12px; margin-right: 4px; }
.action-btn.cancel { background: #fee2e2; color: #991b1b; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: #fff; border-radius: 12px; padding: 24px; width: 560px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 16px; font-size: 18px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #374151; }
.form-grid input, .form-grid select { padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.helper { font-size: 13px; color: #6b7280; margin: 12px 0 8px; }
.inline-row { display: flex; gap: 8px; margin-bottom: 8px; }
.inline-row input { flex: 1; padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
</style>
