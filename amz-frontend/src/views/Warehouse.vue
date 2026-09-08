<template>
  <div class="warehouse-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - 符合 design-taste-frontend 约束 -->
      <!-- eyebrow: 无 (每 3 个 section 最多 1 个，本页面 0 个，合规)
           headline: "海外仓 / WMS" - 2 行以内
           subtext: "仓库管理、入库单、出库单与库存查询" - 6 词以内
           CTAs: 无 (Tab 切换在页面尾部，不计入 hero CTA 数)
           top padding: 由 AppHeader + main-content margin 处理
           split-header: 已垂直堆叠 (h1 在上，p 在下)
      -->
      <div class="hero-section">
        <h1 class="hero-title">海外仓 / WMS</h1>
        <p class="hero-subtitle">仓库管理、入库单、出库单与库存查询</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看仓库数据。
      </div>

      <!-- 骨架屏：表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="table-card sk-table-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
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

      <!-- 各 Tab 面板（加载时仅显示骨架） -->
      <template v-if="!loading">
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
              <tr v-if="!loading && warehouses.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:warehouse" width="32" class="empty-icon" /><span>暂无仓库数据</span></div></td></tr>
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
            <button class="primary-btn" @click="() => { invPage.resetPage(); loadInventory() }">查询</button>
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
              <tr v-for="inv in pagedInventoryList" :key="inv.id ?? `${inv.sku}|${inv.warehouseId}|${inv.batchNo || ''}`">
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
              <tr v-if="!loading && inventoryList.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:package-variant-closed" width="32" class="empty-icon" /><span>暂无库存数据</span></div></td></tr>
            </tbody>
          </table>
          <div v-if="invPage.total.value > invPage.size.value" class="table-pager">
            <span class="page-info">第 {{ invPage.page.value }} 页 / 共 {{ invPage.totalPages.value }} 页（{{ invPage.total.value }} 条）</span>
            <div class="page-actions">
              <button class="page-btn" :disabled="invPage.page.value <= 1" @click="() => invPage.prevPage()">上一页</button>
              <button class="page-btn" :disabled="invPage.page.value >= invPage.totalPages.value" @click="() => invPage.nextPage()">下一页</button>
            </div>
          </div>
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
                  <button v-if="o.status === 'IN_TRANSIT' || o.status === 'PARTIAL'" class="action-btn" @click="openReceive(o.id!, o.warehouseId)">到货验收</button>
                  <button v-if="canCancelInbound(o.status)" class="action-btn cancel" @click="doCancelInbound(o.id!)">取消</button>
                </td>
              </tr>
              <tr v-if="!loading && inboundOrders.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:truck-in" width="32" class="empty-icon" /><span>暂无入库单</span></div></td></tr>
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
                  <button v-if="o.status === 'PACKED'" class="action-btn" @click="doShip(o.id!, o.warehouseId)">发货</button>
                  <button v-if="canCancelOutbound(o.status)" class="action-btn cancel" @click="doCancelOutbound(o.id!)">取消</button>
                </td>
              </tr>
              <tr v-if="!loading && outboundOrders.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:truck-out" width="32" class="empty-icon" /><span>暂无出库单</span></div></td></tr>
            </tbody>
          </table>
        </div>
      </div>
      </template>

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

      <!-- 到货验收弹窗 -->
      <div v-if="receiveDialog.visible" class="modal-mask" @click.self="receiveDialog.visible = false">
        <div class="modal">
          <h3>到货验收 - 库存增加</h3>
          <p class="helper">请填写本次到货明细（用于增加库存，留空则仅变更单据状态）：</p>
          <div v-for="(item, idx) in receiveDialog.items" :key="idx" class="inline-row">
            <input v-model="item.sku" placeholder="SKU" />
            <input v-model.number="item.quantity" type="number" placeholder="数量" />
            <button class="action-btn cancel" @click="receiveDialog.items.splice(idx, 1)">删除</button>
          </div>
          <button class="ghost-btn" @click="receiveDialog.items.push({ sku: '', quantity: 1 })">+ 添加明细</button>
          <div class="modal-actions">
            <button class="ghost-btn" @click="receiveDialog.visible = false">取消</button>
            <button class="primary-btn" @click="confirmReceive">确认收货</button>
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
import { usePagination } from '@/composables/usePagination'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'

// 店铺上下文统一走 current_shop_id（曾硬编码 shopIdNum()=1 导致多店数据污染；B4 公共守卫）
const { currentShopId, refreshShop } = useShopGuard()
const shopIdNum = () => Number(currentShopId.value) || 0
const { showToast } = useToast()
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

// 仓库库存客户端分页（每页 20 条）
const invPage = usePagination<WarehouseInventory>(() => inventoryList.value, 20)
const pagedInventoryList = invPage.paged
const inboundOrders = ref<InboundOrder[]>([])
const outboundOrders = ref<OutboundOrder[]>([])
const invFilter = reactive<{ warehouseId?: number; sku?: string }>({})

const warehouseName = (id?: number) => {
  const w = warehouses.value.find((x) => x.id === id)
  return w ? w.warehouseName : id
}

const loadWarehouses = async () => {
  if (!refreshShop()) return
  try {
    const res = await WH.listWarehouses(currentShopId.value)
    if (res?.code === 200) warehouses.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载仓库失败', e) }
}

// manageLoading：onMounted 外层已统一控制骨架屏时传 false，避免内层提前复位导致闪烁
const loadInventory = async (manageLoading = true) => {
  if (!refreshShop()) return
  if (manageLoading) loading.value = true
  try {
    const res = await WH.listInventory({ shopId: shopIdNum(), ...invFilter })
    if (res?.code === 200) inventoryList.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载库存失败', e) } finally {
    if (manageLoading) loading.value = false
  }
}

const loadInbound = async () => {
  if (!refreshShop()) return
  try {
    const res = await WH.listInboundOrders(currentShopId.value)
    if (res?.code === 200) inboundOrders.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载入库单失败', e) }
}

const loadOutbound = async () => {
  if (!refreshShop()) return
  try {
    const res = await WH.listOutboundOrders(currentShopId.value)
    if (res?.code === 200) outboundOrders.value = res.data || []
  } catch (e) { console.warn('[Warehouse] 加载出库单失败', e) }
}

// ===== 仓库表单 =====
const warehouseDialog = reactive<{ visible: boolean; form: Warehouse }>({
  visible: false,
  form: { shopId: shopIdNum(), warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
})
const openWarehouseDialog = () => {
  warehouseDialog.form = { shopId: shopIdNum(), warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
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
  form: { shopId: shopIdNum(), warehouseId: 0, source: '1688_PURCHASE', totalItems: 0 }
})
const openInboundDialog = () => {
  inboundDialog.form = { shopId: shopIdNum(), warehouseId: warehouses.value[0]?.id || 0, source: '1688_PURCHASE', totalItems: 0 }
  inboundDialog.visible = true
}
const submitInbound = async () => {
  try {
    await WH.createInboundOrder(inboundDialog.form)
    inboundDialog.visible = false
    await loadInbound()
  } catch (e) { console.warn('[Warehouse] 创建入库单失败', e) }
}

const doTransit = async (id: number) => {
  try {
    await WH.transitInbound(id)
    await loadInbound()
  } catch (e) {
    console.warn('[Warehouse] 入库单发运失败', e)
    showToast('操作失败，请稍后重试', 'error')
  }
}
// 到货验收明细弹窗（替代阻塞式 window.prompt，支持多行 SKU/数量校验）
const receiveDialog = reactive<{ visible: boolean; id: number; warehouseId: number; items: { sku: string; quantity: number }[] }>({
  visible: false, id: 0, warehouseId: 0, items: [{ sku: '', quantity: 1 }]
})
const openReceive = (id: number, warehouseId: number) => {
  receiveDialog.id = id
  // 明细必须归属入库单所属仓库：硬编码 0 会导致后端按无效仓库落库存
  receiveDialog.warehouseId = warehouseId
  receiveDialog.items = [{ sku: '', quantity: 1 }]
  receiveDialog.visible = true
}
const confirmReceive = async () => {
  try {
    const items: WarehouseInventory[] = receiveDialog.items
      .filter((x) => x.sku && x.quantity > 0)
      .map((x) => ({ warehouseId: receiveDialog.warehouseId, shopId: shopIdNum(), sku: x.sku, quantity: x.quantity } as WarehouseInventory))
    await WH.receiveInbound(receiveDialog.id, items)
    receiveDialog.visible = false
    showToast('到货验收成功', 'success')
    await loadInbound()
    await loadInventory()
  } catch (e) {
    console.warn('[Warehouse] 到货验收失败', e)
    showToast('到货验收失败，请稍后重试', 'error')
  }
}
const doCancelInbound = async (id: number) => {
  try {
    await WH.cancelInbound(id)
    await loadInbound()
  } catch (e) {
    console.warn('[Warehouse] 取消入库单失败', e)
    showToast('取消失败，请稍后重试', 'error')
  }
}
const canCancelInbound = (s?: string) => s === 'PENDING' || s === 'IN_TRANSIT' || s === 'PARTIAL'

// ===== 出库单表单 =====
const outboundDialog = reactive<{ visible: boolean; form: OutboundOrder }>({
  visible: false,
  form: { shopId: shopIdNum(), warehouseId: 0, orderType: 'ORDER', totalItems: 0 }
})
const openOutboundDialog = () => {
  outboundDialog.form = { shopId: shopIdNum(), warehouseId: warehouses.value[0]?.id || 0, orderType: 'ORDER', totalItems: 0 }
  outboundDialog.visible = true
}
const submitOutbound = async () => {
  try {
    await WH.createOutboundOrder(outboundDialog.form)
    outboundDialog.visible = false
    await loadOutbound()
  } catch (e) { console.warn('[Warehouse] 创建出库单失败', e) }
}

const doPick = async (id: number) => {
  try {
    await WH.pickOutbound(id)
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 拣货失败', e)
    showToast('操作失败，请稍后重试', 'error')
  }
}
const doPack = async (id: number) => {
  try {
    await WH.packOutbound(id)
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 打包失败', e)
    showToast('操作失败，请稍后重试', 'error')
  }
}
const shipDialog = reactive<{ visible: boolean; id: number; warehouseId: number; carrier: string; trackingNo: string; items: { sku: string; quantity: number }[] }>({
  visible: false, id: 0, warehouseId: 0, carrier: '', trackingNo: '', items: [{ sku: '', quantity: 1 }]
})
const doShip = (id: number, warehouseId: number) => {
  shipDialog.id = id
  // 同上：发货明细归属出库单所属仓库
  shipDialog.warehouseId = warehouseId
  shipDialog.carrier = ''
  shipDialog.trackingNo = ''
  shipDialog.items = [{ sku: '', quantity: 1 }]
  shipDialog.visible = true
}
const confirmShip = async () => {
  try {
    const items: WarehouseInventory[] = shipDialog.items
      .filter((x) => x.sku && x.quantity > 0)
      .map((x) => ({ warehouseId: shipDialog.warehouseId, shopId: shopIdNum(), sku: x.sku, quantity: x.quantity } as WarehouseInventory))
    await WH.shipOutbound(shipDialog.id, { carrier: shipDialog.carrier, trackingNo: shipDialog.trackingNo, items })
    shipDialog.visible = false
    showToast('发货成功', 'success')
    await loadOutbound()
    await loadInventory()
  } catch (e) {
    console.warn('[Warehouse] 发货失败', e)
    showToast('发货失败，请稍后重试', 'error')
  }
}
const doCancelOutbound = async (id: number) => {
  try {
    await WH.cancelOutbound(id)
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 取消出库单失败', e)
    showToast('取消失败，请稍后重试', 'error')
  }
}
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
  // 未选择店铺时不发请求，各 load 函数内部同样守卫
  if (!refreshShop()) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    await loadWarehouses()
    await Promise.all([loadInventory(false), loadInbound(), loadOutbound()])
  } finally { loading.value = false }
})
</script>

<style scoped>
/* 页面基础 */
.warehouse-page { background: var(--color-background); }

/* 页头/主区/表格/分页等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.tab-bar { display: flex; gap: 0.5rem; margin-bottom: 1rem; border-bottom: 1px solid var(--color-border); }
.tab-item { display: flex; align-items: center; gap: 0.375rem; padding: 0.5rem 1rem; cursor: pointer; color: var(--color-muted); font-size: 0.875rem; border-bottom: 2px solid transparent; transition: all 0.2s; }
.tab-item:hover { color: var(--color-on-surface); }
.tab-item.active { color: var(--color-primary); border-bottom-color: var(--color-primary); font-weight: 600; }

.panel { margin-bottom: 1rem; }
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.panel-header h3 { font-size: 1rem; font-weight: 600; margin: 0; color: var(--color-on-surface); }
.filter-bar { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.filter-bar select, .filter-bar input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; background: var(--color-surface); color: var(--color-on-surface); }

.num-good { color: var(--color-primary); font-weight: 600; }

/* 状态语义色：active=运行/成功，pending=进行中/警告，inactive=停用/中性灰而非错误红 */
.status-tag.active { background: var(--color-success-light); color: var(--color-success); }
.status-tag.inactive { background: var(--color-muted-light); color: var(--color-muted); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }

.primary-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); cursor: pointer; font-size: 0.8125rem; font-weight: 500; transition: background 0.2s; }
.primary-btn:hover { background: var(--color-primary-dark); }
.ghost-btn { padding: 0.5rem 1rem; background: transparent; color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); cursor: pointer; font-size: 0.8125rem; transition: all 0.2s; }
.ghost-btn:hover { background: var(--color-primary-light); color: var(--color-primary); border-color: var(--color-primary); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; transition: all 0.2s; }
.action-btn:hover:not(:disabled) { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn.cancel { background: var(--color-light-red); color: var(--color-error); }
.action-btn.cancel:hover:not(:disabled) { background: var(--color-error); color: var(--color-on-primary); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.helper { font-size: 0.8125rem; color: var(--color-muted); margin: 0.75rem 0 0.5rem; }
.inline-row { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; }
.inline-row input { flex: 1; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; }

/* 分页条 */
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; margin-top: 1rem; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } .tab-bar { overflow-x: auto; } }
</style>
