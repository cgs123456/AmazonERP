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

      <div v-if="listErrors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ listErrors.join('；') }}</span>
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
          <button v-if="warehouseWriteAllowed" class="primary-btn" @click="openWarehouseDialog()">+ 新建仓库</button>
          <span v-else class="write-locked" role="note">新建/编辑仓库需 OPERATOR 或 ADMIN 角色</span>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>仓库名称</th><th>编码</th><th>类型</th><th>国家</th>
                <th>城市</th><th>联系人</th><th>容量(m³)</th><th>已用(m³)</th><th>状态</th><th>操作</th>
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
                <td>
                  <button v-if="warehouseWriteAllowed" class="ghost-btn" :disabled="w.id == null" @click="openWarehouseDialog(w)">编辑</button>
                  <span v-else class="write-locked" role="note">需 OPERATOR/ADMIN</span>
                </td>
              </tr>
              <tr v-if="!loading && warehouses.length === 0"><td colspan="10" class="empty-row"><div class="empty-state"><Icon icon="mdi:warehouse" width="32" class="empty-icon" /><span>暂无仓库数据</span></div></td></tr>
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
            <button class="primary-btn" @click="loadInventory()">查询</button>
          </div>
        </div>
        <!-- CSV 批量维护库存元数据（A6）：只更新已存在行的 asin/库位/过期日期，数量列被拒。
             刻意用常驻可见的小节而非 <details> 折叠：折叠会让内容 display 隐藏，
             E2E 里 innerText 取空、元素判不可见，徒增脆弱（与 MasterCsvImport 内联同形）。 -->
        <div class="inv-import" data-panel="inventory-import">
          <div class="inv-import-title">批量维护库存元数据（CSV）</div>
          <!-- manageLoading=false：整个内容区包在 v-if="!loading" 里，打骨架屏会把面板连同报告一起卸载 -->
          <!-- :role-allowed 由父页面决定：后端这个端点是 @RequireRole({"OPERATOR","ADMIN"})，
               角色判定放在父页统一做（同一页其它写入口共用同一 ref），子组件只管展示 -->
          <InventoryCsvImport :role-allowed="warehouseWriteAllowed" @imported="loadInventory(false, false)" />
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
              <tr v-for="inv in inventoryList" :key="inv.id ?? `${inv.sku}|${inv.warehouseId}|${inv.batchNo || ''}`">
                <td>{{ inv.sku }}</td>
                <td>{{ inv.asin || '-' }}</td>
                <td>{{ inv.warehouseId }}</td>
                <td class="num-good">{{ inv.availableQuantity }}</td>
                <td>{{ inv.quantity }}</td>
                <td>{{ inv.reservedQuantity }}</td>
                <td>{{ inv.inboundQuantity }}</td>
                <td>
                  <div v-if="editingInventoryId === inv.id" class="loc-edit">
                    <input
                      v-model="editLocationCode"
                      class="loc-input"
                      :maxlength="LOCATION_MAX"
                      aria-label="库位码"
                      @keyup.enter="saveLocation(inv)"
                    />
                    <button class="page-btn" :disabled="locating" @click="saveLocation(inv)">保存</button>
                    <button class="page-btn" :disabled="locating" @click="cancelLocationEdit">取消</button>
                    <span v-if="locationError" class="loc-error" role="alert">{{ locationError }}</span>
                  </div>
                  <div v-else class="loc-view">
                    <span>{{ inv.locationCode || '-' }}</span>
                    <button
                      v-if="locationRoleAllowed"
                      class="page-btn loc-btn"
                      :disabled="!inv.id"
                      :title="inv.id ? '修改库位码（需 OPERATOR 或 ADMIN 角色）' : '该行没有 id，无法定位记录'"
                      @click="startLocationEdit(inv)"
                    >改库位</button>
                    <span v-else class="loc-locked" role="note">需 OPERATOR/ADMIN</span>
                    <span v-if="savedLocationId === inv.id" class="loc-saved" role="status">已保存</span>
                  </div>
                </td>
                <td>{{ inv.batchNo || '-' }}</td>
              </tr>
              <tr v-if="!loading && inventoryList.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:package-variant-closed" width="32" class="empty-icon" /><span>暂无库存数据</span></div></td></tr>
            </tbody>
          </table>
          <div class="pagination">
            <span class="page-info">
              已加载 {{ inventoryList.length }} 条<template v-if="inventoryTruncated">，仍有更多未加载</template><template v-else-if="inventoryPageMetaMissing">，完整性未知</template>
            </span>
            <div class="page-actions">
              <button v-if="inventoryTruncated" class="page-btn" :disabled="inventoryLoadingMore || !inventoryNextCursor" @click="loadMoreInventory">
                {{ inventoryLoadingMore ? '加载中...' : '加载更多' }}
              </button>
            </div>
          </div>
          <div v-if="inventoryTruncated" class="truncated-tip" role="status">
            结果已被服务端截断：当前列表不是全量。请继续加载后再用于盘点、导出或库存汇总。
          </div>
          <div v-else-if="inventoryPageMetaMissing" class="truncated-tip" role="status">
            服务端未返回分页元数据，列表完整性无法确认：当前列表不能视为全量。
          </div>
        </div>

        <!-- 库龄分析：这条端点接前端前从没被浏览器调过，可空快照列的兜底口径见 api/warehouse.ts -->
        <div class="table-card aging-card" data-panel="aging">
          <div class="panel-header">
            <h3>库龄分析</h3>
            <div class="filter-bar">
              <button class="primary-btn" :disabled="agingLoading" @click="loadAging(true)">
                {{ agingLoading ? '加载中...' : '重新计算' }}
              </button>
              <span v-if="aging" class="page-info">有货 SKU {{ aging.totalSkus ?? 0 }} 个</span>
            </div>
          </div>

          <div v-if="agingError" class="truncated-tip" role="alert">{{ agingError }}</div>
          <template v-else-if="aging">
            <div class="aging-grid">
              <div v-for="b in AGING_BUCKETS" :key="b.key" class="aging-cell">
                <div class="aging-label">{{ b.label }}</div>
                <div class="aging-count">{{ aging.aging[b.key]?.count ?? 0 }} 个 SKU</div>
                <div class="aging-value">
                  货值 {{ fmtMoney(aging.aging[b.key]?.value) }} · {{ fmtPct(aging.aging[b.key]?.pct) }}
                </div>
              </div>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>SKU</th><th>仓库</th><th>在库天数</th><th>可用</th><th>货值</th></tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in aging.oldestTop10" :key="`${r.sku}-${r.warehouse}-${i}`">
                  <td>{{ r.sku }}</td>
                  <td>{{ r.warehouse || '—（快照未记仓库名）' }}</td>
                  <td>{{ r.days }}</td>
                  <td>{{ r.qty }}</td>
                  <td>{{ fmtMoney(r.value) }}</td>
                </tr>
                <tr v-if="!aging.oldestTop10.length">
                  <td colspan="5" class="page-info">
                    没有「可用库存 &gt; 0」的快照行。这不代表仓里没货，代表这条店铺的库存快照是空的。
                    <br />
                    快照只能由 <code>POST /logistics/warehouse/stock</code> 写入，
                    目前没有自动同步方（入/出库单写的是各自的表，不回写快照）——
                    要补数据需由海外仓回传后调用该端点，前端刻意不提供手填入口，
                    避免任何人往快照表造数并覆盖同一 sku+仓 的历史口径。
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="aging-note">
              天数取自快照列 days_in_stock：快照滞后时该值会偏高，这里是「按快照算的库龄」，不是实时在库天数。
              <template v-if="aging.stocksTruncated">
                本次扫描触顶（已扫 {{ aging.scannedStockCount }} 行），分段与 Top10 只覆盖已扫描部分。
              </template>
            </p>
          </template>
          <div v-else-if="!agingLoading" class="aging-note">尚未加载：切到本标签会自动算一次。</div>
        </div>
      </div>
      <!-- 入库单 -->
      <div v-show="activeTab === 'inbound'" class="panel">
        <div class="panel-header">
          <h3>入库单列表</h3>
          <div class="header-actions">
            <button v-if="wmsWriteAllowed" class="primary-btn" :disabled="!batchSelected.length || batchReceiving"
                    @click="openBatchReceive()">批量到货（{{ batchSelected.length }}）</button>
            <button v-if="wmsWriteAllowed" class="primary-btn" @click="openInboundDialog()">+ 创建入库单</button>
            <span v-else class="write-locked" role="note">入库单操作{{ WRITE_LOCKED_HINT }}</span>
          </div>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th class="col-check"><input type="checkbox" :checked="allReceivableChecked"
                                             @change="toggleAllReceivable(($event.target as HTMLInputElement).checked)" /></th>
                <th>入库单号</th><th>仓库</th><th>来源</th><th>关联单号</th>
                <th>状态</th><th>总数</th><th>已收</th><th>预计到货</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in inboundOrders" :key="o.id">
                <td class="col-check">
                  <input v-if="canReceiveInbound(o.status) && wmsWriteAllowed" type="checkbox" :checked="batchSelected.includes(o.id!)"
                         @change="toggleBatchSelect(o.id!, ($event.target as HTMLInputElement).checked)" />
                  <span v-else class="muted">—</span>
                </td>
                <td>{{ o.inboundNo }}</td>
                <td>{{ warehouseName(o.warehouseId) }}</td>
                <td>{{ o.source || '-' }}</td>
                <td>{{ o.referenceNo || '-' }}</td>
                <td><span class="status-tag" :class="inboundStatusClass(o.status)">{{ o.status }}</span></td>
                <td>{{ o.totalItems }}</td>
                <td>{{ o.receivedItems }}</td>
                <td>{{ o.expectedArrival || '-' }}</td>
                <td>
                  <span v-if="!wmsWriteAllowed" class="write-locked" role="note">{{ WRITE_LOCKED_HINT }}</span>
                  <template v-else>
                    <button v-if="o.status === 'PENDING'" class="action-btn" @click="doTransit(o.id!)">运输中</button>
                    <button v-if="o.status === 'IN_TRANSIT' || o.status === 'PARTIAL'" class="action-btn" @click="openReceive(o.id!, o.warehouseId)">到货验收</button>
                    <button v-if="canCancelInbound(o.status)" class="action-btn cancel" @click="doCancelInbound(o.id!)">取消</button>
                  </template>
                </td>
              </tr>
              <tr v-if="!loading && inboundOrders.length === 0"><td colspan="10" class="empty-row"><div class="empty-state"><Icon icon="mdi:truck-in" width="32" class="empty-icon" /><span>暂无入库单</span></div></td></tr>
            </tbody>
          </table>
          <div class="pagination">
            <span class="page-info">
              已加载 {{ inboundOrders.length }} 条<template v-if="inboundTruncated">，仍有更多未加载</template><template v-else-if="inboundPageMetaMissing">，完整性未知</template>
            </span>
            <div class="page-actions">
              <button v-if="inboundTruncated" class="page-btn" :disabled="inboundLoadingMore || !inboundNextCursor" @click="loadMoreInbound">
                {{ inboundLoadingMore ? '加载中...' : '加载更多' }}
              </button>
            </div>
          </div>
          <div v-if="inboundTruncated" class="truncated-tip" role="status">
            结果已被服务端截断：当前列表不是全量。请继续加载后再用于收货对账或导出。
          </div>
          <div v-else-if="inboundPageMetaMissing" class="truncated-tip" role="status">
            服务端未返回分页元数据，列表完整性无法确认：当前列表不能视为全量。
          </div>
        </div>
      </div>

      <!-- 出库单 -->
      <div v-show="activeTab === 'outbound'" class="panel">
        <div class="panel-header">
          <h3>出库单列表</h3>
          <button v-if="wmsWriteAllowed" class="primary-btn" @click="openOutboundDialog()">+ 创建出库单</button>
          <span v-else class="write-locked" role="note">出库单操作{{ WRITE_LOCKED_HINT }}</span>
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
                  <span v-if="!wmsWriteAllowed" class="write-locked" role="note">{{ WRITE_LOCKED_HINT }}</span>
                  <template v-else>
                    <button v-if="o.status === 'PENDING'" class="action-btn" @click="doPick(o.id!)">拣货</button>
                    <button v-if="o.status === 'PICKING'" class="action-btn" @click="doPack(o.id!)">打包</button>
                    <button v-if="o.status === 'PACKED'" class="action-btn" @click="doShip(o.id!, o.warehouseId)">发货</button>
                    <button v-if="canCancelOutbound(o.status)" class="action-btn cancel" @click="doCancelOutbound(o.id!)">取消</button>
                  </template>
                </td>
              </tr>
              <tr v-if="!loading && outboundOrders.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:truck-out" width="32" class="empty-icon" /><span>暂无出库单</span></div></td></tr>
            </tbody>
          </table>
          <div class="pagination">
            <span class="page-info">
              已加载 {{ outboundOrders.length }} 条<template v-if="outboundTruncated">，仍有更多未加载</template><template v-else-if="outboundPageMetaMissing">，完整性未知</template>
            </span>
            <div class="page-actions">
              <button v-if="outboundTruncated" class="page-btn" :disabled="outboundLoadingMore || !outboundNextCursor" @click="loadMoreOutbound">
                {{ outboundLoadingMore ? '加载中...' : '加载更多' }}
              </button>
            </div>
          </div>
          <div v-if="outboundTruncated" class="truncated-tip" role="status">
            结果已被服务端截断：当前列表不是全量。请继续加载后再用于发货对账或导出。
          </div>
          <div v-else-if="outboundPageMetaMissing" class="truncated-tip" role="status">
            服务端未返回分页元数据，列表完整性无法确认：当前列表不能视为全量。
          </div>
        </div>
      </div>

      <!-- 库存盘点（P1.2）：§S32 拆为独立组件（自带列表/弹窗/落账等 6 个状态） -->
      <StocktakePanel
        :active="activeTab === 'stocktake'"
        :wms-write-allowed="wmsWriteAllowed"
        :warehouses="warehouses"
      />
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

      <!-- 批量到货弹窗：每张入库单各自填明细，提交后逐单报告 -->
      <div v-if="batchDialog.visible" class="modal-mask" @click.self="batchDialog.visible = false">
        <div class="modal modal-wide" role="dialog" aria-modal="true" data-panel="batch-receive">
          <h3>批量到货验收（{{ batchDialog.lines.length }} 张入库单）</h3>
          <p class="helper">
            每张单各自填明细：后端对每单独立校验与落账，一单失败不会挡住其余单。
            明细留空的单只会变更单据状态、不增加库存——请不要对需要收货的单留空。
          </p>
          <div v-for="line in batchDialog.lines" :key="line.inboundId" class="batch-group">
            <div class="batch-group-head">
              <strong>{{ line.inboundNo }}</strong>
              <span class="muted">仓库 {{ warehouseName(line.warehouseId) }} · 总数 {{ line.totalItems }} · 已收 {{ line.receivedItems }}</span>
            </div>
            <div v-for="(item, ii) in line.items" :key="ii" class="inline-row">
              <input v-model="item.sku" placeholder="SKU" />
              <input v-model.number="item.quantity" type="number" placeholder="数量" />
              <input v-model="item.batchNo" placeholder="批次号（选填）" />
              <button class="action-btn cancel" @click="line.items.splice(ii, 1)">删除</button>
            </div>
            <button class="ghost-btn" @click="line.items.push({ sku: '', quantity: 1, batchNo: '' })">+ 添加明细</button>
          </div>
          <div v-if="batchReport.length" class="batch-report" data-panel="batch-report">
            <h4>到货报告（逐单）</h4>
            <div v-for="r in batchReport" :key="r.inboundId" class="batch-report-row"
                 :class="r.ok ? 'ok' : 'fail'" role="status">
              <template v-if="r.ok">入库单 {{ r.inboundId }}：到货成功，状态 {{ r.status }}</template>
              <template v-else>入库单 {{ r.inboundId }}：失败——{{ r.error || '未给出原因' }}</template>
            </div>
          </div>
          <div class="modal-actions">
            <button class="ghost-btn" @click="batchDialog.visible = false">关闭</button>
            <button class="primary-btn" :disabled="batchReceiving" @click="confirmBatchReceive">
              {{ batchReceiving ? '提交中…' : '确认到货' }}
            </button>
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
import { ref, onMounted, onUnmounted, reactive, watch, computed } from 'vue'
import Icon from '../components/Icon.vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import InventoryCsvImport from '../components/InventoryCsvImport.vue'
import StocktakePanel from '../components/StocktakePanel.vue'
import * as WH from '@/api/warehouse'
import { canUseRole, LOCATION_EDIT_ROLES, WAREHOUSE_WRITE_ROLES, WMS_WRITE_ROLES, WRITE_LOCKED_HINT, ROLE_CHANGED_EVENT } from '@/utils/identity'
import { AGING_BUCKETS } from '@/api/warehouse'
import type { Warehouse, WarehouseInventory, InboundOrder, OutboundOrder, StockAging, BatchReceiveReportRow } from '@/api/warehouse'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'

// 店铺上下文统一走 current_shop_id（曾硬编码 shopIdNum()=1 导致多店数据污染；B4 公共守卫）
const { currentShopId, refreshShop } = useShopGuard()
const shopIdNum = () => Number(currentShopId.value) || 0
const { showToast } = useToast()
const loading = ref(false)
const activeTab = ref<'warehouse' | 'inventory' | 'inbound' | 'outbound' | 'stocktake'>('warehouse')
const tabs = [
  { key: 'warehouse', label: '仓库列表', icon: 'mdi:warehouse' },
  { key: 'inventory', label: '库存查询', icon: 'mdi:package-variant-closed' },
  { key: 'inbound', label: '入库单', icon: 'mdi:truck-in' },
  { key: 'outbound', label: '出库单', icon: 'mdi:truck-out' },
  { key: 'stocktake', label: '库存盘点', icon: 'mdi:clipboard-check' }
] as const

const PAGE_SIZE = 50
const warehouses = ref<Warehouse[]>([])
const inventoryList = ref<WarehouseInventory[]>([])
const inventoryNextCursor = ref<string | null>(null)
const inventoryTruncated = ref(false)
const inventoryPageMetaMissing = ref(false)
const inventoryLoadingMore = ref(false)

// ===== 库位码就地编辑（PUT /logistics/warehouse/inventory/{id}/location）=====
const editingInventoryId = ref<number | null>(null)
const editLocationCode = ref('')
const locating = ref(false)
const locationError = ref('')
const savedLocationId = ref<number | null>(null)

// 命名空间对象不能直接出现在模板绑定里（Vue 会对 setup 绑定做 ref 解包，
// 解包时读到的会是 mock 的代理键），所以在脚本里取成普通常量再交给模板。
const LOCATION_MAX = WH.LOCATION_CODE_MAX_LENGTH

/**
 * 后端该端点是 @RequireRole({"OPERATOR","ADMIN"})。角色未知（AppHeader 的 getInfo 还没回来）
 * 时不隐藏按钮：权限的权威判定在后端，前端只做「点了必然 403」的提前告知。
 */
const locationRoleAllowed = ref(canUseRole(LOCATION_EDIT_ROLES))

const syncLocationRole = () => {
  locationRoleAllowed.value = canUseRole(LOCATION_EDIT_ROLES)
}

/**
 * 仓库新建/编辑同样是 @RequireRole({"OPERATOR","ADMIN"})，此前两个按钮没有任何门控——
 * VIEWER 点「+ 新建仓库」只会拿到 403，看起来像功能坏了。
 * 与库位码门禁同源（都是 canUseRole：未知角色放行，后端才是权威判定），
 * 但常量分开（见 WAREHOUSE_WRITE_ROLES 注释）。
 */
const warehouseWriteAllowed = ref(canUseRole(WAREHOUSE_WRITE_ROLES))

const syncWarehouseWriteRole = () => {
  warehouseWriteAllowed.value = canUseRole(WAREHOUSE_WRITE_ROLES)
}

/**
 * WMS 单据写入（入库单 / 出库单 / 盘点单）的角色门禁。
 *
 * 这三个域此前**完全没有门控**：页面上 15 个写入口对 VIEWER 全绿可点，
 * 而同一个文件里的「+ 新建仓库」早就规规矩矩换成了灰字说明。
 * 两套口径出现在同一屏，用户无从判断哪个是真坏了。
 */
const wmsWriteAllowed = ref(canUseRole(WMS_WRITE_ROLES))

const syncWmsWriteRole = () => {
  wmsWriteAllowed.value = canUseRole(WMS_WRITE_ROLES)
}
// ==================== 库龄分析 ====================
// 库龄分析：这条端点接前端前只有 curl 能碰，且 DDL 里 available_qty / days_in_stock /
// warehouse_name 三列都可空 —— 后端已改成按 0 与空串兜底（见 WarehouseAgingAnalysisTest）
const aging = ref<StockAging | null>(null)
const agingLoading = ref(false)
const agingError = ref('')

const fmtMoney = (v?: number | string) => {
  const n = Number(v ?? 0)
  return Number.isFinite(n) ? n.toFixed(2) : String(v ?? '-')
}
const fmtPct = (v?: number | string) => {
  const n = Number(v ?? 0)
  return Number.isFinite(n) ? `${(n * 100).toFixed(2)}%` : '-'
}

/**
 * 写操作的统一守卫：角色不够时不发请求，并把原因说出来。
 *
 * 为什么每个写入口都要调用它（而不是只在按钮上做 v-if）：
 * 入口隐藏只是体验优化，不是边界。弹窗可能在角色变更前就已打开，或者角色信息
 * （AppHeader 的 getInfo）比弹窗晚到——那种情况下请求照样会发出去并 403。
 *
 * 返回 true 表示可以继续发请求。
 */
const wmsWriteBlocked = (action: string): boolean => {
  if (wmsWriteAllowed.value) return false
  showToast(`${action}需要 OPERATOR 或 ADMIN 角色`, 'error')
  return true
}

/**
 * 后端业务拒绝的统一出口。
 *
 * <b>为什么必须有这个函数</b>：{@code RequireRoleAspect} 与所有业务校验失败都返回
 * {@code Result.failure()}，即 **HTTP 200 + code=400**（见 Result#failure，以及
 * GlobalExceptionHandler 上「业务拒绝刻意保持 200」的注释）。所以 axios 的 promise
 * 是 <b>resolve</b> 而不是 reject——`catch` 块根本不会执行。
 *
 * 本页此前有一批 `try { await x(); onSuccess() } catch { showToast('请稍后重试') }`，
 * 把「角色不够」当成成功：弹窗关闭、列表刷新、提示操作成功。用户看到的是
 * 「点了、有反应、但什么都没发生」，而 OPERATOR 遇到任何 code=400 的业务拒绝
 * （状态不对、仓库无效…）也是同一套假成功。
 *
 * 用法：`const res = await x(); if (!ok(res)) return; doSuccess()`
 * 返回 true 表示 res.code === 200，调用方可以放心走成功分支。
 */
const ok = (res: { code?: number; message?: string } | null | undefined, fallback: string): boolean => {
  if (res?.code === 200) return true
  // 后端已经把原因写好了（「无权限执行该操作（需要角色：OPERATOR/ADMIN）」），
  // 必须原样透出，不能换成「请稍后重试」——那句话让重试变成无效动作。
  showToast(res?.message || fallback, 'error')
  return false
}

const loadAging = async (force = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    aging.value = null
    agingError.value = '未选择店铺：库龄按店铺读取，不能猜一家。'
    return
  }
  if (aging.value && !force) return
  agingLoading.value = true
  agingError.value = ''
  try {
    const res = await WH.getStockAging(shopId)
    if (res?.code !== 200 || !res.data) {
      aging.value = null
      agingError.value = `库龄分析失败：${res?.message || '后端没有返回数据'}`
      return
    }
    aging.value = res.data
  } catch (e) {
    aging.value = null
    agingError.value = `库龄分析失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    agingLoading.value = false
  }
}

// 切到「库存查询」才读，且只在第一次进入时自动读；之后靠「重新计算」按钮。

watch(activeTab, (t) => {
  if (t === 'inventory') void loadAging()
})

onMounted(() => {
  window.addEventListener(ROLE_CHANGED_EVENT, syncLocationRole)
  window.addEventListener(ROLE_CHANGED_EVENT, syncWarehouseWriteRole)
  window.addEventListener(ROLE_CHANGED_EVENT, syncWmsWriteRole)
})
onUnmounted(() => {
  window.removeEventListener(ROLE_CHANGED_EVENT, syncLocationRole)
  window.removeEventListener(ROLE_CHANGED_EVENT, syncWarehouseWriteRole)
  window.removeEventListener(ROLE_CHANGED_EVENT, syncWmsWriteRole)
})

const startLocationEdit = (inv: WarehouseInventory) => {
  if (!inv.id) return
  editingInventoryId.value = inv.id
  editLocationCode.value = inv.locationCode ?? ''
  locationError.value = ''
  savedLocationId.value = null
}

const cancelLocationEdit = () => {
  editingInventoryId.value = null
  locationError.value = ''
}

/**
 * 保存库位码。空值与超长在前端先挡一次，后端同规则：
 * ?locationCode= 这种「参数存在但为空」过去会把库位清空，现在一律拒绝。
 */
const saveLocation = async (inv: WarehouseInventory) => {
  if (!inv.id) return
  const code = editLocationCode.value.trim()
  if (!code) {
    locationError.value = '库位码不能为空：本接口不做静默清空'
    return
  }
  if (code.length > LOCATION_MAX) {
    locationError.value = `库位码最长 ${LOCATION_MAX} 字符，当前 ${code.length}`
    return
  }
  locating.value = true
  locationError.value = ''
  try {
    const res = await WH.updateInventoryLocation(inv.id, code)
    if (res?.code === 200 && res.data) {
      const at = inventoryList.value.findIndex((r) => r.id === inv.id)
      if (at !== -1) inventoryList.value.splice(at, 1, { ...inventoryList.value[at], ...res.data })
      savedLocationId.value = inv.id
      editingInventoryId.value = null
    } else {
      locationError.value = `保存失败：${res?.message || '后端未返回成功'}`
    }
  } catch (e) {
    // 403（角色不够）与无权访问该店铺都要在格子上看得见，而不是只进 console
    locationError.value = e instanceof Error && e.message ? `保存失败：${e.message}` : '保存失败'
  } finally {
    locating.value = false
  }
}
const inboundOrders = ref<InboundOrder[]>([])
const inboundNextCursor = ref<string | null>(null)
const inboundTruncated = ref(false)
const inboundPageMetaMissing = ref(false)
const inboundLoadingMore = ref(false)
const outboundOrders = ref<OutboundOrder[]>([])
const outboundNextCursor = ref<string | null>(null)
const outboundTruncated = ref(false)
const outboundPageMetaMissing = ref(false)
const outboundLoadingMore = ref(false)
// 列表加载失败必须可见：不能把失败渲染成「暂无数据」空态（假成功）。
// 错误只在整页装载时清一次，各 loader 只追加，避免并发加载互相擦横幅（踩过的坑）。
const listErrors = ref<string[]>([])
const pushListError = (tag: string, e?: unknown) => {
  const detail = e instanceof Error ? e.message : (e as string | undefined) || '后端返回异常'
  if (!listErrors.value.some((x) => x.startsWith(`${tag}：`))) {
    listErrors.value.push(`${tag}：${detail}`)
  }
}

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
    else pushListError('仓库列表', res?.message)
  } catch (e) { console.warn('[Warehouse] 加载仓库失败', e); pushListError('仓库列表', e) }
}

// 库存/出库都使用服务端游标分页：前端只负责保留已加载行，不能再假装当前页就是全量。
const loadInventory = async (append = false, manageLoading = true) => {
  const shopId = refreshShop()
  if (!shopId) {
    if (!append) {
      inventoryList.value = []
      inventoryNextCursor.value = null
      inventoryTruncated.value = false
      inventoryPageMetaMissing.value = false
    }
    return
  }
  if (!append) {
    inventoryNextCursor.value = null
    inventoryTruncated.value = false
    inventoryPageMetaMissing.value = false
  }
  if (append) inventoryLoadingMore.value = true
  else if (manageLoading) loading.value = true
  try {
    const cursor = append ? (inventoryNextCursor.value ?? undefined) : undefined
    const res = await WH.listInventory({
      shopId: shopIdNum(),
      ...invFilter,
      size: PAGE_SIZE,
      cursor
    })
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      inventoryList.value = append ? inventoryList.value.concat(rows) : rows
      const page = res._page
      inventoryPageMetaMissing.value = !page
      inventoryTruncated.value = page ? page.truncated : false
      inventoryNextCursor.value = page?.nextCursor ?? null
    } else {
      console.warn('[Warehouse] 库存列表返回异常', res)
      pushListError('库存列表', res?.message)
      if (!append) inventoryList.value = []
    }
  } catch (e) {
    console.warn('[Warehouse] 加载库存失败', e)
    pushListError('库存列表', e)
    if (!append) inventoryList.value = []
  } finally {
    if (append) inventoryLoadingMore.value = false
    if (manageLoading && !append) loading.value = false
  }
}

const loadMoreInventory = () => {
  if (!inventoryTruncated.value || !inventoryNextCursor.value || inventoryLoadingMore.value) return
  void loadInventory(true)
}

const loadInbound = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    if (!append) {
      inboundOrders.value = []
      inboundNextCursor.value = null
      inboundTruncated.value = false
      inboundPageMetaMissing.value = false
    }
    return
  }
  if (!append) {
    inboundNextCursor.value = null
    inboundTruncated.value = false
    inboundPageMetaMissing.value = false
  }
  if (append) inboundLoadingMore.value = true
  try {
    const cursor = append ? (inboundNextCursor.value ?? undefined) : undefined
    const res = await WH.listInboundOrders(shopIdNum(), undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      inboundOrders.value = append ? inboundOrders.value.concat(rows) : rows
      const page = res._page
      inboundPageMetaMissing.value = !page
      inboundTruncated.value = page ? page.truncated : false
      inboundNextCursor.value = page?.nextCursor ?? null
    } else {
      console.warn('[Warehouse] 入库单列表返回异常', res)
      pushListError('入库单', res?.message)
      if (!append) inboundOrders.value = []
    }
  } catch (e) {
    console.warn('[Warehouse] 加载入库单失败', e)
    pushListError('入库单', e)
    if (!append) inboundOrders.value = []
  } finally {
    if (append) inboundLoadingMore.value = false
  }
}

const loadMoreInbound = () => {
  if (!inboundTruncated.value || !inboundNextCursor.value || inboundLoadingMore.value) return
  void loadInbound(true)
}

const loadOutbound = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    if (!append) {
      outboundOrders.value = []
      outboundNextCursor.value = null
      outboundTruncated.value = false
      outboundPageMetaMissing.value = false
    }
    return
  }
  if (!append) {
    outboundNextCursor.value = null
    outboundTruncated.value = false
    outboundPageMetaMissing.value = false
  }
  if (append) outboundLoadingMore.value = true
  try {
    const cursor = append ? (outboundNextCursor.value ?? undefined) : undefined
    const res = await WH.listOutboundOrders(shopIdNum(), undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && Array.isArray(res.data)) {
      const rows = res.data
      outboundOrders.value = append ? outboundOrders.value.concat(rows) : rows
      const page = res._page
      outboundPageMetaMissing.value = !page
      outboundTruncated.value = page ? page.truncated : false
      outboundNextCursor.value = page?.nextCursor ?? null
    } else {
      console.warn('[Warehouse] 出库单列表返回异常', res)
      pushListError('出库单', res?.message)
      if (!append) outboundOrders.value = []
    }
  } catch (e) {
    console.warn('[Warehouse] 加载出库单失败', e)
    pushListError('出库单', e)
    if (!append) outboundOrders.value = []
  } finally {
    if (append) outboundLoadingMore.value = false
  }
}

const loadMoreOutbound = () => {
  if (!outboundTruncated.value || !outboundNextCursor.value || outboundLoadingMore.value) return
  void loadOutbound(true)
}

// ===== 仓库表单 =====
const warehouseDialog = reactive<{ visible: boolean; form: Warehouse }>({
  visible: false,
  form: { shopId: shopIdNum(), warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
})
/**
 * 打开仓库弹窗：不传参＝新建，传行＝编辑（回填该行）。
 * <p>
 * 弹窗标题早就写了 `form.id ? '编辑仓库' : '新建仓库'` 这个分支，但此前没有任何入口能
 * 把 id 带进来、submitWarehouse 也无条件调 createWarehouse——编辑仓库根本不可达，
 * 误点只会「新建一个同名仓库」。这里补上编辑入口。
 */
const openWarehouseDialog = (w?: Warehouse) => {
  warehouseDialog.form = w
    ? { ...w }
    : { shopId: shopIdNum(), warehouseName: '', warehouseCode: '', warehouseType: 'THIRD_PARTY', country: 'US' }
  warehouseDialog.visible = true
}
const submitWarehouse = async () => {
  // 入口隐藏只是体验优化，不是边界：弹窗可能在角色变更前就已经打开，
  // 或者角色信息比弹窗晚到。这里再挡一次，别把注定 403 的请求发出去。
  if (!warehouseWriteAllowed.value) {
    showToast('当前角色不能新建或编辑仓库（需 OPERATOR 或 ADMIN）', 'error')
    return
  }
  try {
    // 有 id 走更新（PUT /logistics/warehouse），否则新建（POST）——两者后端都在，
    // 路径同为 /logistics/warehouse，靠请求体带 id 区分。
    if (warehouseDialog.form.id != null) {
      await WH.updateWarehouse(warehouseDialog.form)
    } else {
      await WH.createWarehouse(warehouseDialog.form)
    }
    warehouseDialog.visible = false
    await loadWarehouses()
  } catch (e) {
    // §S33.29：原实现只 console.warn。弹窗不关、也没有任何提示，
    // 用户看到的是「点了保存没反应」，会反复点——而每次都真的发了一次写请求。
    console.warn('[Warehouse] 保存仓库失败', e)
    showToast(`保存仓库失败：${e instanceof Error ? e.message : '请稍后重试'}`, 'error')
  }
}

// ===== 入库单表单 =====
const inboundDialog = reactive<{ visible: boolean; form: InboundOrder }>({
  visible: false,
  form: { shopId: shopIdNum(), warehouseId: 0, source: '1688_PURCHASE', totalItems: 0 }
})
const openInboundDialog = () => {
  // 没有可用仓库时不打开弹窗：仓库下拉会空着，提交值退化成 warehouseId=0，
  // 后端按无效仓库拒绝，而这里的 catch 又只写 console——OPERATOR 会看到
  // 「弹窗一开一关、什么都没发生」。零反馈比一个明确的报错更难排查。
  if (!warehouses.value.length) {
    showToast('还没有可用仓库：请先在「仓库列表」里新建仓库', 'error')
    return
  }
  inboundDialog.form = { shopId: shopIdNum(), warehouseId: warehouses.value[0]!.id!, source: '1688_PURCHASE', totalItems: 0 }
  inboundDialog.visible = true
}
const submitInbound = async () => {
  if (wmsWriteBlocked('创建入库单')) return
  try {
    const res = await WH.createInboundOrder(inboundDialog.form)
    if (!ok(res, '创建入库单失败')) return
    inboundDialog.visible = false
    await loadInbound()
  } catch (e) { console.warn('[Warehouse] 创建入库单失败', e); showToast('创建入库单失败', 'error') }
}

const doTransit = async (id: number) => {
  if (wmsWriteBlocked('标记在途')) return
  try {
    const res = await WH.transitInbound(id)
    if (!ok(res, '标记在途失败')) return
    await loadInbound()
  } catch (e) {
    console.warn('[Warehouse] 入库单发运失败', e)
    showToast('标记在途失败', 'error')
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
/* ---------- 批量到货 ---------- */

/** 后端 receiveInbound 只接 IN_TRANSIT / PARTIAL；与单条「到货验收」按钮的显示条件一致。 */
const canReceiveInbound = (status?: string) => status === 'IN_TRANSIT' || status === 'PARTIAL'

const receivableInbounds = computed<InboundOrder[]>(() => inboundOrders.value.filter((o) => canReceiveInbound(o.status)))
const allReceivableChecked = computed(() =>
  receivableInbounds.value.length > 0 && batchSelected.value.length === receivableInbounds.value.length)

const batchSelected = ref<number[]>([])
const batchReceiving = ref(false)
const batchReport = ref<BatchReceiveReportRow[]>([])
const batchDialog = reactive<{
  visible: boolean
  lines: Array<{ inboundId: number; inboundNo: string; warehouseId: number; totalItems?: number; receivedItems?: number; items: Array<{ sku: string; quantity: number; batchNo: string }> }>
}>({ visible: false, lines: [] })

const toggleBatchSelect = (id: number, checked: boolean) => {
  batchSelected.value = checked ? [...batchSelected.value, id] : batchSelected.value.filter((x) => x !== id)
}

const toggleAllReceivable = (checked: boolean) => {
  batchSelected.value = checked ? receivableInbounds.value.map((o) => o.id!) : []
}
const openBatchReceive = () => {
  batchReport.value = []
  batchDialog.lines = batchSelected.value
    .map((id) => inboundOrders.value.find((o) => o.id === id))
    .filter((o): o is InboundOrder => !!o)
    .map((o) => ({
      inboundId: o.id!,
      inboundNo: o.inboundNo || String(o.id),
      warehouseId: o.warehouseId,
      totalItems: o.totalItems,
      receivedItems: o.receivedItems,
      items: [{ sku: '', quantity: 1, batchNo: '' }]
    }))
  batchDialog.visible = true
}

const confirmBatchReceive = async () => {
  if (wmsWriteBlocked('批量到货验收')) return
  // 逐组校验：只要有单没填明细就整批拒绝，并点名未填的单号。
  // 不按「过滤掉空组」放行——那会静默少收几单，比明确报错危险得多。
  const blankLines = batchDialog.lines
    .filter((line) => line.items.every((x) => !x.sku || x.quantity <= 0))
    .map((line) => line.inboundNo)
  if (blankLines.length) {
    showToast(`以下入库单还没填明细：${blankLines.join('、')}。不留空的单才会增加库存`, 'error')
    return
  }

  const lines = batchDialog.lines.map((line) => ({
    inboundId: line.inboundId,
    items: line.items
      .filter((x) => x.sku && x.quantity > 0)
      .map((x) => ({
        warehouseId: line.warehouseId,
        shopId: shopIdNum(),
        sku: x.sku,
        quantity: x.quantity,
        ...(x.batchNo ? { batchNo: x.batchNo } : {})
      } as WarehouseInventory))
  }))

  if (!lines.length) {
    showToast('每张入库单都要至少填一条 SKU 明细，否则不会增加库存', 'error')
    return
  }

  batchReceiving.value = true
  batchReport.value = []
  try {
    const res = await WH.batchReceiveInbound(lines)
    if (res?.code !== 200) {
      showToast(`批量到货失败：${res?.message || '接口返回非 200'}`, 'error')
      return
    }
    batchReport.value = res.data ?? []
    const failed = batchReport.value.filter((r) => !r.ok).length
    // 逐单报告，不汇总成「全部成功」：失败行必须看得见，否则仓库会漏收货
    if (failed > 0) {
      showToast(`${batchReport.value.length} 张单：${batchReport.value.length - failed} 成功、${failed} 失败（详见下方报告）`, 'error')
    } else {
      showToast(`${batchReport.value.length} 张入库单全部到货成功`, 'success')
    }
    await Promise.all([loadInbound(), loadInventory()])
  } catch (e) {
    console.warn('[Warehouse] 批量到货失败', e)
    showToast('批量到货失败，请稍后重试', 'error')
  } finally {
    batchReceiving.value = false
  }
}

const confirmReceive = async () => {
  if (wmsWriteBlocked('到货验收')) return
  try {
    const items: WarehouseInventory[] = receiveDialog.items
      .filter((x) => x.sku && x.quantity > 0)
      .map((x) => ({ warehouseId: receiveDialog.warehouseId, shopId: shopIdNum(), sku: x.sku, quantity: x.quantity } as WarehouseInventory))
    const res = await WH.receiveInbound(receiveDialog.id, items)
    if (!ok(res, '到货验收失败')) return
    receiveDialog.visible = false
    showToast('到货验收成功', 'success')
    await loadInbound()
    await loadInventory()
  } catch (e) {
    console.warn('[Warehouse] 到货验收失败', e)
    showToast('到货验收失败', 'error')
  }
}
const doCancelInbound = async (id: number) => {
  if (wmsWriteBlocked('取消入库单')) return
  try {
    const res = await WH.cancelInbound(id)
    if (!ok(res, '取消入库单失败')) return
    await loadInbound()
  } catch (e) {
    console.warn('[Warehouse] 取消入库单失败', e)
    showToast('取消入库单失败', 'error')
  }
}
const canCancelInbound = (s?: string) => s === 'PENDING' || s === 'IN_TRANSIT' || s === 'PARTIAL'

// ===== 出库单表单 =====
const outboundDialog = reactive<{ visible: boolean; form: OutboundOrder }>({
  visible: false,
  form: { shopId: shopIdNum(), warehouseId: 0, orderType: 'ORDER', totalItems: 0 }
})
const openOutboundDialog = () => {
  // 同 openInboundDialog：无仓库时打开只会拿到一次零反馈的假成功
  if (!warehouses.value.length) {
    showToast('还没有可用仓库：请先在「仓库列表」里新建仓库', 'error')
    return
  }
  outboundDialog.form = { shopId: shopIdNum(), warehouseId: warehouses.value[0]!.id!, orderType: 'ORDER', totalItems: 0 }
  outboundDialog.visible = true
}
const submitOutbound = async () => {
  if (wmsWriteBlocked('创建出库单')) return
  try {
    const res = await WH.createOutboundOrder(outboundDialog.form)
    if (!ok(res, '创建出库单失败')) return
    outboundDialog.visible = false
    await loadOutbound()
  } catch (e) { console.warn('[Warehouse] 创建出库单失败', e); showToast('创建出库单失败', 'error') }
}

const doPick = async (id: number) => {
  if (wmsWriteBlocked('拣货')) return
  try {
    const res = await WH.pickOutbound(id)
    if (!ok(res, '拣货失败')) return
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 拣货失败', e)
    showToast('拣货失败', 'error')
  }
}
const doPack = async (id: number) => {
  if (wmsWriteBlocked('打包')) return
  try {
    const res = await WH.packOutbound(id)
    if (!ok(res, '打包失败')) return
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 打包失败', e)
    showToast('打包失败', 'error')
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
  if (wmsWriteBlocked('发货')) return
  try {
    const items: WarehouseInventory[] = shipDialog.items
      .filter((x) => x.sku && x.quantity > 0)
      .map((x) => ({ warehouseId: shipDialog.warehouseId, shopId: shopIdNum(), sku: x.sku, quantity: x.quantity } as WarehouseInventory))
    const res = await WH.shipOutbound(shipDialog.id, { carrier: shipDialog.carrier, trackingNo: shipDialog.trackingNo, items })
    if (!ok(res, '发货失败')) return
    shipDialog.visible = false
    showToast('发货成功', 'success')
    await loadOutbound()
    await loadInventory()
  } catch (e) {
    console.warn('[Warehouse] 发货失败', e)
    showToast('发货失败', 'error')
  }
}
const doCancelOutbound = async (id: number) => {
  if (wmsWriteBlocked('取消出库单')) return
  try {
    const res = await WH.cancelOutbound(id)
    if (!ok(res, '取消出库单失败')) return
    await loadOutbound()
  } catch (e) {
    console.warn('[Warehouse] 取消出库单失败', e)
    showToast('取消出库单失败', 'error')
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
  listErrors.value = []
  try {
    await loadWarehouses()
    await Promise.all([loadInventory(false, false), loadInbound(), loadOutbound()])
  } finally { loading.value = false }
})
</script>

<style scoped>
.error-zone {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border-radius: var(--amz-radius-md);
  padding: 0.625rem 0.875rem;
  margin-bottom: 1rem;
  font-size: 0.875rem;
  background: var(--color-light-red);
  color: var(--color-error);
}
/* 页面基础 */
.warehouse-page { background: var(--color-background); }
.truncated-tip { margin-top: 0.75rem; padding: 0.625rem 0.875rem; border-radius: var(--amz-radius-md); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; line-height: 1.6; }
/* 库龄分析卡片 */
.aging-card { margin-top: 1rem; }
.aging-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0.75rem; margin-bottom: 1rem; }
.aging-cell { border: 1px solid var(--color-border); border-radius: 8px; padding: 0.75rem; }
.aging-label { font-size: 0.8125rem; color: var(--color-muted); }
.aging-count { font-size: 1.125rem; font-weight: 600; margin: 0.25rem 0; }
.aging-value { font-size: 0.8125rem; color: var(--color-muted); }
.aging-note { font-size: 0.8125rem; color: var(--color-muted); margin: 0.5rem 0 0; line-height: 1.5; }

/* 库位码就地编辑 */
.loc-view { display: flex; align-items: center; gap: 0.375rem; flex-wrap: wrap; }
.loc-btn { padding: 0.1875rem 0.5rem; font-size: 0.75rem; }
.loc-edit { display: flex; align-items: center; gap: 0.375rem; flex-wrap: wrap; }
.loc-input { width: 7.5rem; padding: 0.25rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.loc-input:focus { border-color: var(--color-primary); outline: none; }
.loc-error { color: var(--color-error); font-size: 0.75rem; max-width: 14rem; }
.loc-saved { color: var(--color-success); font-size: 0.75rem; }
.loc-locked { color: var(--color-muted); font-size: 0.75rem; }
.write-locked { color: var(--color-muted); font-size: 0.75rem; }

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
.panel-header .header-actions { display: flex; gap: 0.5rem; }
/* 批量到货：勾选列窄，不可勾的格子给破折号而不是空（空会被读成「漏渲染」） */
.col-check { width: 2.5rem; text-align: center; }
.batch-group { border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); padding: 0.75rem; margin: 0.5rem 0; }
.batch-group-head { display: flex; justify-content: space-between; align-items: baseline; gap: 0.5rem; margin-bottom: 0.5rem; }
.batch-report { border-top: 1px solid var(--color-border); margin-top: 0.75rem; padding-top: 0.5rem; }
.batch-report h4 { font-size: 0.875rem; margin: 0 0 0.375rem; }
.batch-report-row { font-size: 0.8125rem; padding: 0.25rem 0; }
.batch-report-row.ok { color: var(--color-primary); }
.batch-report-row.fail { color: var(--color-error, #d32f2f); }
.filter-bar { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.filter-bar select, .filter-bar input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); font-size: 0.8125rem; background: var(--color-surface); color: var(--color-on-surface); }

.num-good { color: var(--color-primary); font-weight: 600; }

/* 状态语义色：active=运行/成功，pending=进行中/警告，inactive=停用/中性灰而非错误红 */
.status-tag.active { background: var(--color-success-light); color: var(--color-success); }
.status-tag.inactive { background: var(--color-muted-light); color: var(--color-muted); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }

.primary-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--amz-radius-md); cursor: pointer; font-size: 0.8125rem; font-weight: 500; transition: background 0.2s; }
.primary-btn:hover { background: var(--color-primary-dark); }
.ghost-btn { padding: 0.5rem 1rem; background: transparent; color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); cursor: pointer; font-size: 0.8125rem; transition: all 0.2s; }
.ghost-btn:hover { background: var(--color-primary-light); color: var(--color-primary); border-color: var(--color-primary); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--amz-radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; transition: all 0.2s; }
.action-btn:hover:not(:disabled) { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn.cancel { background: var(--color-light-red); color: var(--color-error); }
.action-btn.cancel:hover:not(:disabled) { background: var(--color-error); color: var(--color-on-primary); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--amz-radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); font-size: 0.875rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.helper { font-size: 0.8125rem; color: var(--color-muted); margin: 0.75rem 0 0.5rem; }
.inline-row { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; }
.inline-row input { flex: 1; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); font-size: 0.8125rem; }

/* 分页条 */
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; margin-top: 1rem; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } .tab-bar { overflow-x: auto; } }
.inv-import { margin-bottom: 0.5rem; }
.inv-import-title { font-size: 0.8125rem; font-weight: 600; color: var(--color-on-surface); margin-bottom: 0.375rem; }
</style>
