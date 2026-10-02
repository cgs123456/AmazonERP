<template>
  <div class="procurement-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">采购供应链</h1>
        <p class="hero-subtitle">供应商比价 · 采购计划审批 · 1688 采购单 · 质检 · FBA 货件与批次成本</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再进入采购流程（供应商、计划、采购单、货件都按店铺隔离）。
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <template v-if="currentShopId">
        <div class="stat-grid">
          <div class="stat-card">
            <div class="stat-count">{{ pendingApproval.length }}</div>
            <div class="stat-label">本页待审批计划</div>
          </div>
          <div class="stat-card warn">
            <div class="stat-count">{{ qcPending.length }}</div>
            <div class="stat-label">本页待质检采购单</div>
          </div>
            <div class="stat-card">
            <div class="stat-count">{{ inTransit.length }}</div>
            <div class="stat-label">本页在途货件</div>
          </div>
          <div class="stat-card">
            <div class="stat-count">{{ pageAmountTotal }}</div>
            <div class="stat-label">本页采购单金额合计</div>
          </div>
        </div>
        <p class="stat-note">
          以上只统计「已加载的页」：列表按 keyset 游标分页，
          {{ anyTruncated ? '当前存在未翻完的列表，合计不是全量。' : '本页后端未报告截断。' }}
        </p>

        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="tab = t.key">{{ t.label }}</button>
        </div>

        <!-- ==================== 供应商 ==================== -->
        <div v-if="tab === 'supplier'" class="table-card">
          <div class="filter-row">
            <label class="filter">状态
              <select v-model="supplierStatus" @change="loadSuppliers()">
                <option value="">全部</option>
                <option value="ACTIVE">ACTIVE</option>
                <option value="DISABLED">DISABLED</option>
                <option value="BLACKLISTED">BLACKLISTED</option>
              </select>
            </label>
            <label class="filter">关键词
              <input class="filter-input" v-model="supplierKeyword" placeholder="名称/编码/联系人" @keyup.enter="loadSuppliers()" />
            </label>
            <button class="action-btn" :disabled="suppliers.loading.value" @click="loadSuppliers()">查询</button>
            <button class="action-btn" @click="openModal('supplier')">新增供应商</button>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>供应商</th><th>编码</th><th>联系人</th><th>评分</th><th>准时率</th>
                <th>合格率</th><th>累计单数</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in suppliers.rows.value" :key="s.id">
                <td>{{ s.supplierName }}</td>
                <td class="mono">{{ s.supplierCode || '-' }}</td>
                <td>{{ s.contactName || '-' }}<span class="muted" v-if="s.contactPhone"> / {{ s.contactPhone }}</span></td>
                <td>{{ s.rating ?? '-' }}</td>
                <td>{{ s.onTimeDeliveryRate ?? '-' }}</td>
                <td>{{ s.qualityPassRate ?? '-' }}</td>
                <td>{{ s.totalOrders ?? 0 }}</td>
                <td><span class="status-tag" :class="supplierClass(s.status)">{{ s.status }}</span></td>
                <td class="row-actions">
                  <button class="action-btn" @click="showKpi(s)">KPI</button>
                  <button class="action-btn" @click="openModal('offer', { supplierId: s.id, supplierName: s.supplierName })">关联 SKU</button>
                  <button class="action-btn" :disabled="busy" @click="setSupplierStatus(s, 'DISABLED')">停用</button>
                  <button class="action-btn" :disabled="busy" @click="setSupplierStatus(s, 'ACTIVE')">启用</button>
                  <button class="action-btn cancel" :disabled="busy" @click="confirmAction({
                      title: '拉黑供应商',
                      detail: `将 ${s.supplierName} 标记为 BLACKLISTED。后端只改状态字段，历史采购单不受影响。`,
                      run: () => setSupplierStatus(s, 'BLACKLISTED')
                    })">拉黑</button>
                </td>
              </tr>
              <tr v-if="!suppliers.loading.value && !suppliers.rows.value.length">
                <td colspan="9" class="empty-row">该店铺暂无供应商记录</td>
              </tr>
            </tbody>
          </table>
          <div class="table-pager">
            <span class="page-info">{{ pagerText(suppliers) }}</span>
            <div class="page-actions">
              <button v-if="suppliers.truncated.value" class="page-btn" :disabled="suppliers.loading.value"
                      @click="loadSuppliers(true)">加载下一页</button>
            </div>
          </div>

          <div class="sub-block">
            <div class="sub-title">SKU 供应商关联与比价</div>
            <div class="inline-row">
              <input v-model="offerSku" placeholder="输入 SKU 查该 SKU 的供货关系与比价" @keyup.enter="loadOffers()" />
              <button class="action-btn" :disabled="!offerSku.trim() || offerBusy" @click="loadOffers()">查询</button>
            </div>
            <table v-if="offers.length" class="data-table">
              <thead>
                <tr><th>供应商 ID</th><th>SKU</th><th>offer</th><th>供货价</th><th>MOQ</th><th>交期(天)</th><th>首选</th></tr>
              </thead>
              <tbody>
                <tr v-for="o in offers" :key="o.id">
                  <td>{{ o.supplierId }}</td>
                  <td class="mono">{{ o.sku }}</td>
                  <td class="mono">{{ o.supplierOfferId || '-' }}</td>
                  <td>{{ o.supplyPrice ?? '-' }}</td>
                  <td>{{ o.moq ?? '-' }}</td>
                  <td>{{ o.leadTimeDays ?? '-' }}</td>
                  <td>{{ truthy(o.isPreferred) ? '首选' : '-' }}</td>
                </tr>
              </tbody>
            </table>
            <table v-if="compareRows.length" class="data-table compare-table">
              <thead>
                <tr><th>性价比排名</th><th>供应商</th><th>评分</th><th>供货价</th><th>MOQ</th><th>交期(天)</th><th>综合分</th></tr>
              </thead>
              <tbody>
                <tr v-for="(c, i) in compareRows" :key="c.supplierId">
                  <td>{{ i + 1 }}</td>
                  <td>{{ c.supplierName }}</td>
                  <td>{{ c.supplierRating ?? '-' }}</td>
                  <td>{{ c.supplyPrice ?? '-' }}</td>
                  <td>{{ c.moq ?? '-' }}</td>
                  <td>{{ c.leadTimeDays ?? '-' }}</td>
                  <td>{{ c.overallScore ?? '-' }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="offerSku && !offers.length && !compareRows.length" class="muted">
              该 SKU 没有供货关系：比价表为空是后端真实结果，不是加载失败。
            </p>
          </div>

          <div v-if="kpi" class="panel">
            <div class="panel-head">
              <span class="panel-title">{{ kpi.supplierName }} · 供应商 KPI</span>
              <button class="action-btn" @click="kpi = null">关闭</button>
            </div>
            <dl class="kv">
              <dt>综合评级</dt><dd>{{ kpi.grade || '-' }}（compositeScore {{ kpi.compositeScore ?? '-' }}）</dd>
              <dt>评分</dt><dd>{{ kpi.rating ?? '-' }}</dd>
              <dt>准时交货率</dt><dd>{{ kpi.onTimeDeliveryRate ?? '-' }}</dd>
              <dt>质量合格率</dt><dd>{{ kpi.qualityPassRate ?? '-' }}</dd>
              <dt>价格竞争力</dt><dd>{{ kpi.priceCompetitiveness ?? '-' }}</dd>
              <dt>响应速度</dt><dd>{{ kpi.responseSpeed ?? '-' }}</dd>
              <dt>累计采购</dt><dd>{{ kpi.totalOrders ?? 0 }} 单 / {{ kpi.totalAmount ?? 0 }}</dd>
            </dl>
          </div>
        </div>

        <!-- ==================== 采购计划 ==================== -->
        <div v-if="tab === 'plan'" class="table-card">
          <div class="filter-row">
            <label class="filter">状态
              <select v-model="planStatus" @change="loadPlans()">
                <option value="">全部</option>
                <option v-for="s in PLAN_STATUS" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <button class="action-btn" :disabled="plans.loading.value" @click="loadPlans()">刷新</button>
            <button class="action-btn" @click="openModal('plan')">新建计划</button>
            <span class="muted">状态机：DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED（生成采购单）</span>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>计划号</th><th>SKU</th><th>建议量</th><th>计划量</th><th>单价</th><th>金额</th>
                <th>紧急度</th><th>来源</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in plans.rows.value" :key="p.id">
                <td class="mono">{{ p.planNo || p.id }}</td>
                <td class="mono">{{ p.sku }}</td>
                <td>{{ p.suggestedQty ?? '-' }}</td>
                <td>{{ p.plannedQty }}</td>
                <td>{{ p.unitPrice ?? '-' }}</td>
                <td>{{ p.totalAmount ?? '-' }}</td>
                <td><span class="status-tag" :class="urgencyClass(p.urgency)">{{ p.urgency || '-' }}</span></td>
                <td>{{ p.source || '-' }}</td>
                <td><span class="status-tag" :class="planClass(p.status)">{{ p.status }}</span></td>
                <td class="row-actions">
                  <button v-if="p.status === 'DRAFT'" class="action-btn" :disabled="busy" @click="runPlan(p, 'submit')">提交审批</button>
                  <template v-if="p.status === 'PENDING_APPROVAL'">
                    <label class="approver">审批人<input class="cell-input" v-model="operator" /></label>
                    <button class="action-btn" :disabled="busy || !operator.trim()" @click="runPlan(p, true)">通过</button>
                    <button class="action-btn cancel" :disabled="busy || !operator.trim()" @click="runPlan(p, false)">驳回</button>
                  </template>
                  <button v-if="p.status === 'APPROVED'" class="action-btn" :disabled="busy" @click="runPlan(p, 'convert')">转采购单</button>
                  <button v-if="canCancelPlan(p.status)" class="action-btn cancel" :disabled="busy" @click="runPlan(p, 'cancel')">取消</button>
                  <button v-if="p.replenishmentData" class="action-btn" @click="showBasis(p)">补货依据</button>
                </td>
              </tr>
              <tr v-if="!plans.loading.value && !plans.rows.value.length">
                <td colspan="10" class="empty-row">该店铺暂无采购计划</td>
              </tr>
            </tbody>
          </table>
          <div v-if="planBasis" class="panel">
            <div class="panel-head">
              <span class="panel-title">{{ planBasis.planNo }} 的补货依据</span>
              <button class="action-btn" @click="planBasis = null">关闭</button>
            </div>
            <table class="data-table">
              <tbody>
                <tr v-for="row in planBasis.rows" :key="row[0]">
                  <th class="mono">{{ row[0] }}</th><td class="mono">{{ row[1] }}</td>
                </tr>
              </tbody>
            </table>
            <p class="panel-note">{{ planBasis.note }}</p>
          </div>

          <div class="table-pager">
            <span class="page-info">{{ pagerText(plans) }}</span>
            <div class="page-actions">
              <button v-if="plans.truncated.value" class="page-btn" :disabled="plans.loading.value"
                      @click="loadPlans(true)">加载下一页</button>
            </div>
          </div>
        </div>

        <!-- ==================== 采购单 ==================== -->
        <div v-if="tab === 'order'" class="table-card">
          <div class="filter-row">
            <button class="action-btn" :disabled="orders.loading.value" @click="loadOrders()">刷新</button>
            <button class="action-btn" @click="openModal('order')">新建采购单</button>
            <span class="muted">提交/取消会真的调用 1688 开放平台下单与关单，均需二次确认</span>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>单号</th><th>SKU</th><th>供应商</th><th>数量</th><th>单价</th><th>金额</th>
                <th>状态</th><th>1688 单号</th><th>运单</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in orders.rows.value" :key="o.id">
                <td class="mono">{{ o.orderNo || o.id }}</td>
                <td class="mono">{{ o.sku }}</td>
                <td>{{ o.supplierName || '-' }}</td>
                <td>{{ o.quantity }}</td>
                <td>{{ o.unitPrice }}</td>
                <td>{{ o.totalAmount ?? '-' }}</td>
                <td><span class="status-tag" :class="orderClass(o.status)">{{ o.status }}</span></td>
                <td class="mono">{{ o.alibabaOrderNo || '-' }}</td>
                <td class="mono">{{ o.trackingNo || '-' }}</td>
                <td class="row-actions">
                  <button v-if="o.status === 'DRAFT'" class="action-btn" :disabled="busy"
                          @click="confirmAction(realOrderConfirm(o))">提交 1688</button>
                  <button v-if="o.alibabaOrderNo" class="action-btn" :disabled="busy" @click="runOrder(o, 'sync')">同步状态</button>
                  <button v-if="o.status === 'QC_PENDING'" class="action-btn" :disabled="busy"
                          @click="openModal('qc', { order: o })">提交质检</button>
                  <button v-if="canCancelOrder(o.status)" class="action-btn cancel" :disabled="busy"
                          @click="confirmAction(cancelOrderConfirm(o))">取消</button>
                </td>
              </tr>
              <tr v-if="!orders.loading.value && !orders.rows.value.length">
                <td colspan="10" class="empty-row">该店铺暂无采购单</td>
              </tr>
            </tbody>
          </table>
          <div class="table-pager">
            <span class="page-info">{{ pagerText(orders) }}</span>
            <div class="page-actions">
              <button v-if="orders.truncated.value" class="page-btn" :disabled="orders.loading.value"
                      @click="loadOrders(true)">加载下一页</button>
            </div>
          </div>
        </div>

        <!-- ==================== FBA 货件 ==================== -->
        <div v-if="tab === 'shipment'" class="table-card">
          <div class="filter-row">
            <label class="filter">状态
              <select v-model="shipmentStatus" @change="loadShipments()">
                <option value="">全部</option>
                <option v-for="s in SHIPMENT_STATUS" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <button class="action-btn" :disabled="shipments.loading.value" @click="loadShipments()">刷新</button>
            <button class="action-btn" @click="openModal('shipment')">新建货件</button>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>货件号</th><th>FBA ShipmentId</th><th>目的仓</th><th>承运</th><th>主运单</th>
                <th>箱数</th><th>总成本</th><th>状态</th><th>ETA</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in shipments.rows.value" :key="s.id">
                <td class="mono">{{ s.shipmentNo || s.id }}</td>
                <td class="mono">{{ s.fbaShipmentId || '-' }}</td>
                <td>{{ s.destinationFbaCode || '-' }}</td>
                <td>{{ s.carrier || '-' }}</td>
                <td class="mono">{{ s.masterTrackingNo || '-' }}</td>
                <td>{{ s.boxCount ?? '-' }}</td>
                <td>{{ s.totalCost ?? '-' }}</td>
                <td><span class="status-tag" :class="shipmentClass(s.status)">{{ s.status }}</span></td>
                <td>{{ s.eta || '-' }}</td>
                <td class="row-actions">
                  <button class="action-btn" :disabled="busy" @click="selectShipment(s)">明细</button>
                  <button v-if="s.status === 'CREATED' || s.status === 'READY_TO_SHIP'" class="action-btn"
                          :disabled="busy" @click="openModal('ship', { shipment: s })">确认发货</button>
                  <button class="action-btn" :disabled="busy" @click="allocate(s)">费用分摊</button>
                  <button class="action-btn" :disabled="busy" @click="confirmAction(markArrivalConfirm(s))">标记到货</button>
                </td>
              </tr>
              <tr v-if="!shipments.loading.value && !shipments.rows.value.length">
                <td colspan="10" class="empty-row">该店铺暂无 FBA 货件</td>
              </tr>
            </tbody>
          </table>
          <div class="table-pager">
            <span class="page-info">{{ pagerText(shipments) }}</span>
            <div class="page-actions">
              <button v-if="shipments.truncated.value" class="page-btn" :disabled="shipments.loading.value"
                      @click="loadShipments(true)">加载下一页</button>
            </div>
          </div>

          <div v-if="activeShipment" class="sub-block">
            <div class="sub-title">货件 {{ activeShipment.shipmentNo || activeShipment.id }} 明细</div>
            <table class="data-table">
              <thead>
                <tr><th>SKU</th><th>ASIN</th><th>发货量</th><th>已收</th><th>批次</th><th>单位成本</th><th>运费分摊</th><th>总成本</th><th>签收数量</th></tr>
              </thead>
              <tbody>
                <tr v-for="it in items" :key="it.id">
                  <td class="mono">{{ it.sku }}</td>
                  <td class="mono">{{ it.asin || '-' }}</td>
                  <td>{{ it.quantity }}</td>
                  <td>{{ it.receivedQuantity ?? 0 }}</td>
                  <td class="mono">{{ it.batchNo || '-' }}</td>
                  <td>{{ it.unitCost ?? '-' }}</td>
                  <td>{{ it.freightAllocation ?? '-' }}</td>
                  <td>{{ it.totalCost ?? '-' }}</td>
                  <td>
                    <input class="cell-input" type="number" min="0" v-model="receiveQty[it.id as number]"
                           :disabled="!!it.receivedQuantity" />
                  </td>
                </tr>
                <tr v-if="!items.length"><td colspan="9" class="empty-row">该货件还没有明细，先添加明细才能分摊与签收</td></tr>
              </tbody>
            </table>
            <div class="inline-row">
              <button class="action-btn" @click="openModal('item')">添加明细</button>
              <button class="action-btn" :disabled="busy || !items.length" @click="receive()">提交签收</button>
              <span class="muted">签收按明细逐行入库，已签收的行不可重复签收</span>
            </div>
            <p v-if="shipMsg" class="panel-note">{{ shipMsg }}</p>

            <div v-if="allocation" class="panel">
              <div class="panel-head">
                <span class="panel-title">头程费用分摊结果</span>
                <button class="action-btn" @click="allocation = null">关闭</button>
              </div>
              <dl class="kv">
                <dt>总数量</dt><dd>{{ allocation.totalQuantity ?? '-' }}</dd>
                <dt>运费合计</dt><dd>{{ allocation.totalFreight ?? '-' }}</dd>
                <dt>关税+税费合计</dt><dd>{{ allocation.totalCustoms ?? '-' }}</dd>
                <dt>其他费用合计</dt><dd>{{ allocation.totalOther ?? '-' }}</dd>
                <dt>成本合计</dt><dd>{{ allocation.totalCost ?? '-' }}</dd>
              </dl>
              <table class="data-table">
                <thead><tr><th>SKU</th><th>数量</th><th>运费分摊</th><th>关税分摊</th><th>其他</th><th>总成本</th><th>单位成本</th></tr></thead>
                <tbody>
                  <tr v-for="(d, i) in allocation.allocationDetails || []" :key="`${d.sku}-${i}`">
                    <td class="mono">{{ d.sku }}</td>
                    <td>{{ d.quantity }}</td>
                    <td>{{ d.freightAllocation ?? '-' }}</td>
                    <td>{{ d.customsAllocation ?? '-' }}</td>
                    <td>{{ d.otherAllocation ?? '-' }}</td>
                    <td>{{ d.totalCost ?? '-' }}</td>
                    <td>{{ d.unitCost ?? '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-if="receipt" class="panel">
              <div class="panel-head">
                <span class="panel-title">签收结果</span>
                <button class="action-btn" @click="receipt = null">关闭</button>
              </div>
                <p :class="receipt.hasDiscrepancy ? 'diff-warn' : 'diff-ok'">
                {{ receipt.hasDiscrepancy
                  ? '存在签收差异（溢装/短装），下列明细差异不为 0；差异目前不会自动生成费用差异单，需人工跟进。'
                  : '本次签收与发货量一致。' }}
              </p>
              <table class="data-table">
                <thead><tr><th>SKU</th><th>应收</th><th>实收</th><th>差异</th><th>判定</th></tr></thead>
                <tbody>
                  <tr v-for="(r, i) in receipt.itemResults || []" :key="`${r.sku}-${i}`">
                    <td class="mono">{{ r.sku }}</td>
                    <td>{{ r.expectedQty ?? '-' }}</td>
                    <td>{{ r.receivedQty ?? '-' }}</td>
                    <td :class="r.discrepancy ? 'diff-warn' : ''">{{ r.discrepancy ?? '-' }}</td>
                    <td>{{ r.status || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- ==================== 库存批次 ==================== -->
        <div v-if="tab === 'batch'" class="table-card">
          <div class="filter-row">
            <label class="filter">SKU
              <input class="filter-input" v-model="batchSku" placeholder="批次按 SKU 查询" @keyup.enter="loadBatches()" />
            </label>
            <button class="action-btn" :disabled="!batchSku.trim() || batches.loading.value" @click="loadBatches()">查询批次</button>
            <span class="muted">FIFO 出库会直接扣减批次可用量，需二次确认</span>
          </div>

          <div v-if="costSummary" class="inline-row cost-summary">
            <span>ACTIVE 批次数 {{ costSummary.batchCount ?? 0 }}</span>
            <span>总量 {{ costSummary.totalQuantity ?? 0 }}</span>
            <span>批次成本合计 {{ costSummary.totalBatchCost ?? 0 }}</span>
          </div>

          <table class="data-table">
            <thead>
              <tr>
                <th>批次号</th><th>SKU</th><th>入库日</th><th>数量</th><th>可用</th>
                <th>单位成本</th><th>总成本</th><th>到期日</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in batches.rows.value" :key="b.id">
                <td class="mono">{{ b.batchNo }}</td>
                <td class="mono">{{ b.sku }}</td>
                <td>{{ b.inboundDate || '-' }}</td>
                <td>{{ b.quantity ?? 0 }}</td>
                <td>{{ b.availableQuantity ?? 0 }}</td>
                <td>{{ b.unitCost ?? '-' }}</td>
                <td>{{ b.totalCost ?? '-' }}</td>
                <td>{{ b.expireDate || '-' }}</td>
                <td><span class="status-tag" :class="batchClass(b.status)">{{ b.status }}</span></td>
              </tr>
              <tr v-if="!batches.loading.value && batchSku && !batches.rows.value.length">
                <td colspan="9" class="empty-row">该 SKU 没有批次记录（成本将退化为采购单价，而不是有一个更早批次被漏掉）</td>
              </tr>
              <tr v-if="!batchSku">
                <td colspan="9" class="empty-row">输入 SKU 后查询：批次表按 SKU+FIFO 顺序读取，无法整店列出</td>
              </tr>
            </tbody>
          </table>
          <div class="table-pager">
            <span class="page-info">{{ pagerText(batches) }}</span>
            <div class="page-actions">
              <button v-if="batches.truncated.value" class="page-btn" :disabled="batches.loading.value"
                      @click="loadBatches(true)">加载下一页</button>
            </div>
          </div>

          <div class="sub-block">
            <div class="sub-title">FIFO 出库</div>
            <div class="inline-row">
              <input class="cell-input" type="number" min="1" v-model="fifoQty" placeholder="数量" />
              <button class="action-btn" :disabled="busy || !batchSku.trim() || !Number(fifoQty)" @click="confirmAction(fifoConfirm())">
                按 FIFO 扣减
              </button>
            </div>
            <table v-if="fifoRows.length" class="data-table">
              <thead><tr><th>批次号</th><th>SKU</th><th>扣减量</th><th>单位成本</th><th>小计</th><th>入库日</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in fifoRows" :key="`${String(r.batchNo)}-${i}`">
                  <td class="mono">{{ r.batchNo }}</td>
                  <td class="mono">{{ r.sku }}</td>
                  <td>{{ r.quantity }}</td>
                  <td>{{ r.unitCost }}</td>
                  <td>{{ r.subtotal }}</td>
                  <td>{{ r.inboundDate }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </main>

    <!-- ==================== 弹窗 ==================== -->
    <div v-if="modal" class="modal-mask" @click.self="modal = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ MODAL_TITLE[modal] }}</h3>

        <div v-if="modal === 'supplier'" class="form-grid">
          <label>供应商名称 *<input v-model="form.supplierName" /></label>
          <label>编码<input v-model="form.supplierCode" /></label>
          <label>联系人<input v-model="form.contactName" /></label>
          <label>联系电话<input v-model="form.contactPhone" /></label>
          <label>联系邮箱<input v-model="form.contactEmail" /></label>
          <label>1688 offer 页<input v-model="form.alibabaShopUrl" /></label>
          <label>结算方式<input v-model="form.paymentTerms" /></label>
          <label>评分(0-5)<input type="number" step="0.1" min="0" max="5" v-model="form.rating" /></label>
        </div>

        <div v-else-if="modal === 'offer'" class="form-grid">
          <label>供应商<input :value="form.supplierName" disabled /></label>
          <label>SKU *<input v-model="form.sku" /></label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>1688 offerId<input v-model="form.supplierOfferId" /></label>
          <label>供货价 *<input type="number" step="0.01" min="0" v-model="form.supplyPrice" /></label>
          <label>MOQ<input type="number" min="1" v-model="form.moq" /></label>
          <label>交期(天)<input type="number" min="0" v-model="form.leadTimeDays" /></label>
          <label>首选
            <select v-model="form.isPreferred"><option :value="1">是</option><option :value="0">否</option></select>
          </label>
        </div>

        <div v-else-if="modal === 'plan'" class="form-grid">
          <label>SKU *<input v-model="form.sku" /></label>
          <label>计划数量 *<input type="number" min="1" v-model="form.plannedQty" /></label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>建议量<input type="number" min="0" v-model="form.suggestedQty" /></label>
          <label>单价<input type="number" step="0.01" min="0" v-model="form.unitPrice" /></label>
          <label>供应商 ID<input type="number" min="1" v-model="form.supplierId" /></label>
          <label>紧急度
            <select v-model="form.urgency"><option v-for="u in URGENCY" :key="u" :value="u">{{ u }}</option></select>
          </label>
          <label>备注<input v-model="form.remark" /></label>
        </div>

        <div v-else-if="modal === 'order'" class="form-grid">
          <label>SKU *<input v-model="form.sku" /></label>
          <label>供应商名称<input v-model="form.supplierName" /></label>
          <label>数量 *<input type="number" min="1" v-model="form.quantity" /></label>
          <label>单价 *<input type="number" step="0.01" min="0" v-model="form.unitPrice" /></label>
          <label>1688 offerId<input v-model="form.supplierOfferId" /></label>
          <label>预计到货<input v-model="form.expectedDeliveryDate" placeholder="2026-10-20" /></label>
          <label>备注<input v-model="form.remark" /></label>
        </div>

        <div v-else-if="modal === 'qc'" class="form-grid">
          <label>采购单<input :value="form.order?.orderNo || form.order?.id" disabled /></label>
          <label>当前状态<input :value="form.order?.status" disabled /></label>
          <label>抽检数 *(≥1)<input type="number" min="1" v-model="form.sampleCount" /></label>
          <label>不良数 *(≤抽检数)<input type="number" min="0" v-model="form.failedCount" /></label>
          <label>质检人 *<input v-model="form.inspector" /></label>
          <label>缺陷描述<input v-model="form.defectDescription" /></label>
        </div>

        <div v-else-if="modal === 'shipment'" class="form-grid">
          <label>FBA ShipmentId<input v-model="form.fbaShipmentId" /></label>
          <label>目的仓代码<input v-model="form.destinationFbaCode" placeholder="MDW2" /></label>
          <label>发货方式
            <select v-model="form.shippingMethod"><option v-for="m in SHIP_METHODS" :key="m" :value="m">{{ m }}</option></select>
          </label>
          <label>箱数<input type="number" min="1" v-model="form.boxCount" /></label>
          <label>总重(kg)<input type="number" step="0.01" min="0" v-model="form.totalWeight" /></label>
          <label>总体积(m³)<input type="number" step="0.001" min="0" v-model="form.totalVolume" /></label>
          <label>运费<input type="number" step="0.01" min="0" v-model="form.freightCost" /></label>
          <label>关税<input type="number" step="0.01" min="0" v-model="form.customsCost" /></label>
          <label>税费<input type="number" step="0.01" min="0" v-model="form.taxCost" /></label>
          <label>其他费用<input type="number" step="0.01" min="0" v-model="form.otherCost" /></label>
          <label>ETA<input v-model="form.eta" placeholder="2026-10-30" /></label>
          <label>状态
            <select v-model="form.status"><option v-for="s in SHIPMENT_STATUS" :key="s" :value="s">{{ s }}</option></select>
          </label>
        </div>

        <div v-else-if="modal === 'item'" class="form-grid">
          <label>SKU *<input v-model="form.sku" /></label>
          <label>数量 *<input type="number" min="1" v-model="form.quantity" /></label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>单位成本<input type="number" step="0.01" min="0" v-model="form.unitCost" /></label>
        </div>

        <div v-else-if="modal === 'ship'" class="form-grid">
          <label>承运商 *<input v-model="form.carrier" placeholder="UPS / 中外运" /></label>
          <label>主运单号 *<input v-model="form.trackingNo" /></label>
        </div>

        <p class="modal-note">{{ modalNote }}</p>
        <div class="modal-actions">
          <button class="action-btn cancel" @click="modal = null">关闭</button>
          <button class="action-btn" :disabled="busy" @click="submitModal()">提交</button>
        </div>
      </div>
    </div>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p class="confirm-detail">{{ confirmBox.detail }}</p>
        <div class="modal-actions">
          <button class="action-btn cancel" @click="confirmBox = null">再想想</button>
          <button class="action-btn" :disabled="busy" @click="runConfirm()">确认执行</button>
        </div>
      </div>
    </div>

    <p v-if="msg" class="toast" role="status">{{ msg }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, type Ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import { asNumber } from '@/utils/format'
import type { ApiResponse } from '@/api/types'
import * as proc from '@/api/procurement'
import type {
  Supplier, SupplierProduct, PurchasePlan, PurchaseOrder, FbaShipment, FbaShipmentItem,
  InventoryBatch, BatchCostSummary, PriceCompareRow, SupplierKpi, AllocationResult, ReceiptResult
} from '@/api/procurement'

type TabKey = 'supplier' | 'plan' | 'order' | 'shipment' | 'batch'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'supplier', label: '供应商与比价' },
  { key: 'plan', label: '采购计划' },
  { key: 'order', label: '采购单（1688）' },
  { key: 'shipment', label: 'FBA 货件' },
  { key: 'batch', label: '库存批次与 FIFO' }
]

const PLAN_STATUS = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CONVERTED', 'CANCELED']
const SHIPMENT_STATUS = ['CREATED', 'READY_TO_SHIP', 'SHIPPED', 'DELIVERED', 'CLOSED']
const URGENCY = ['URGENT', 'HIGH', 'NORMAL', 'LOW']
const SHIP_METHODS = ['SEA', 'AIR', 'TRAIN', 'EXPRESS']
const MODAL_TITLE: Record<string, string> = {
  supplier: '新增供应商',
  offer: '关联供应商 SKU（报价）',
  plan: '新建采购计划',
  order: '新建采购单',
  qc: '提交质检结果',
  shipment: '新建 FBA 货件',
  item: '添加货件明细',
  ship: '确认发货'
}

type ModalKey = keyof typeof MODAL_TITLE

const loading = ref(false)
const busy = ref(false)
const errors = ref<string[]>([])
const msg = ref('')
const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('supplier')

const truthy = (v: unknown) => v === true || v === 1 || v === '1'

const planBasis = ref<null | { planNo: string; rows: Array<[string, string]>; note: string }>(null)

/**
 * replenishmentData 是建计划当时留档的补货输入（由库存页「生成采购计划」写入）。
 * 解析不了就原样显示而不是丢弃：这段文本是「这条计划凭什么定这个量」的唯一凭据。
 */
const showBasis = (p: PurchasePlan) => {
  const raw = p.replenishmentData || ''
  const label = p.planNo || `计划 #${p.id}`
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    planBasis.value = {
      planNo: label,
      rows: Object.entries(parsed).map(([k, v]) => [k, String(v ?? '-')] as [string, string]),
      note: '这是生成该草稿计划时的补货输入快照（含建议统计日期），不是当前库存的实时值。'
    }
  } catch {
    planBasis.value = { planNo: label, rows: [['原文', raw]], note: '依据文本不是 JSON，原样显示（可能是更早版本或人工填写）。' }
  }
}

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

/* ---------- 游标分页列表：累积已加载页 + 显式记录截断状态 ---------- */
interface CursorList<T> {
  rows: Ref<T[]>
  cursor: Ref<string | null>
  truncated: Ref<boolean>
  total: Ref<number | null>
  loading: Ref<boolean>
}
const makeList = <T>(): CursorList<T> => ({
  rows: ref<T[]>([]) as Ref<T[]>,
  cursor: ref<string | null>(null),
  truncated: ref(false),
  total: ref<number | null>(null),
  loading: ref(false)
})

const fetchPage = async <T>(
  list: CursorList<T>,
  call: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  name: string,
  append: boolean
) => {
  if (list.loading.value) return
  list.loading.value = true
  try {
    const res = await call(append ? list.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${name}：${res?.message || '接口返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    list.rows.value = append ? [...list.rows.value, ...batch] : batch
    const meta = res._page
    list.truncated.value = meta?.truncated ?? false
    list.cursor.value = meta?.nextCursor ?? null
    list.total.value = meta?.total ?? null
    if (!meta) pushError(`${name}：响应没有分页元数据，无法判断是否还有下一页，本页可能不完整`)
  } catch (e) {
    pushError(`${name}：${(e as Error)?.message || '调用失败'}`)
  } finally {
    list.loading.value = false
  }
}

const pagerText = (list: CursorList<unknown>): string => {
  const loaded = list.rows.value.length
  const total = list.total.value
  const scope = total === null ? `已加载 ${loaded} 条（后端未给全量数）` : `已加载 ${loaded} / ${total} 条`
  return list.truncated.value ? `${scope} · 后端标记仍有下一页` : scope
}

/* ---------- 供应商 ---------- */
const suppliers = makeList<Supplier>()
const supplierStatus = ref('')
const supplierKeyword = ref('')
const offers = ref<SupplierProduct[]>([])
const compareRows = ref<PriceCompareRow[]>([])
const offerSku = ref('')
const offerBusy = ref(false)
const kpi = ref<SupplierKpi | null>(null)

const loadSuppliers = (append = false) =>
  fetchPage(suppliers, cursor => proc.listSuppliers(shop(), {
    status: supplierStatus.value || undefined,
    keyword: supplierKeyword.value || undefined,
    cursor
  }), '供应商列表', append)

const loadOffers = async () => {
  const sku = offerSku.value.trim()
  if (!sku) return
  offerBusy.value = true
  try {
    const [bySku, cmp] = await Promise.all([
      callApi('SKU 供货关系', () => proc.suppliersBySku(shop(), sku)),
      callApi('SKU 比价', () => proc.compareSuppliers(shop(), sku))
    ])
    offers.value = bySku ?? []
    compareRows.value = cmp ?? []
  } finally {
    offerBusy.value = false
  }
}

const showKpi = async (s: Supplier) => {
  if (!s.id) return
  const data = await callApi('供应商 KPI', () => proc.supplierKpi(s.id as number))
  if (data) kpi.value = data
}

const setSupplierStatus = async (s: Supplier, status: string) => {
  if (!s.id) return
  const okRun = await callApi(`${s.supplierName} → ${status}`, () => proc.updateSupplierStatus(s.id as number, status))
  if (okRun !== null) await loadSuppliers()
}

const supplierClass = (status?: string) =>
  status === 'ACTIVE' ? 'healthy' : status === 'BLACKLISTED' ? 'urgent' : 'risk'

/* ---------- 采购计划 ---------- */
const plans = makeList<PurchasePlan>()
const planStatus = ref('')
const operator = ref('')

const loadPlans = (append = false) =>
  fetchPage(plans, cursor => proc.listPlans(shop(), { status: planStatus.value || undefined, cursor }),
    '采购计划', append)

const runPlan = async (p: PurchasePlan, action: 'submit' | 'convert' | 'cancel' | boolean) => {
  if (!p.id) return
  const id = p.id
  const label = action === true ? '审批通过' : action === false ? '审批驳回' : `计划${action}`
  const data = action === 'submit' ? await callApi(label, () => proc.submitPlan(id))
    : action === 'convert' ? await callApi(label, () => proc.convertPlan(id))
      : action === 'cancel' ? await callApi(label, () => proc.cancelPlan(id))
        : await callApi(label, () => proc.approvePlan(id, operator.value.trim(), action === true))
  if (data === null) return
  if (action === 'convert') {
    const orderNo = (data as Record<string, unknown>)?.orderNo
    msg.value = `已生成采购单：${orderNo ?? '（后端未回传单号，请到采购单页确认）'}`
  }
  await loadPlans()
}

const canCancelPlan = (status?: string) => !!status && !['CONVERTED', 'CANCELED'].includes(status)
const planClass = (status?: string) =>
  status === 'APPROVED' || status === 'CONVERTED' ? 'healthy'
    : status === 'PENDING_APPROVAL' ? 'risk'
      : status === 'REJECTED' || status === 'CANCELED' ? 'urgent' : ''
const urgencyClass = (urgency?: string) =>
  urgency === 'URGENT' ? 'urgent' : urgency === 'HIGH' ? 'risk' : ''

/* ---------- 采购单 ---------- */
const orders = makeList<PurchaseOrder>()

const loadOrders = (append = false) =>
  fetchPage(orders, cursor => proc.listOrders(shop(), { cursor }), '采购单列表', append)

const runOrder = async (o: PurchaseOrder, action: 'submit' | 'sync' | 'cancel') => {
  if (!o.id) return
  const id = o.id
  if (action === 'submit') {
    if (await callApi('提交 1688', () => proc.submitOrderTo1688(id)) !== null) await loadOrders()
  } else if (action === 'sync') {
    if (await callApi('同步 1688 状态', () => proc.syncOrderStatus(id)) !== null) await loadOrders()
  } else {
    const closed = await callApi('取消采购单', () => proc.cancelOrder(id))
    if (closed === null) return
    // 远程关单失败时后端返回 false 且保持本地状态不变，这里必须说出来，否则看起来像已取消
    if (closed !== true) msg.value = '取消未完成：1688 远程关单失败，本地状态保持不变'
    await loadOrders()
  }
}

const canCancelOrder = (status?: string) => !!status && !['CANCELED', 'QC_PASSED', 'QC_FAILED', 'RECEIVED'].includes(status)
const orderClass = (status?: string) =>
  status === 'QC_PASSED' || status === 'COMPLETED' || status === 'RECEIVED' ? 'healthy'
    : status === 'QC_FAILED' || status === 'CANCELED' ? 'urgent'
      : status === 'SUBMITTING' || status === 'QC_PENDING' ? 'risk' : ''
const qcPending = computed(() => orders.rows.value.filter(o => o.status === 'QC_PENDING'))
const pendingApproval = computed(() => plans.rows.value.filter(p => p.status === 'PENDING_APPROVAL'))

const realOrderConfirm = (o: PurchaseOrder) => ({
  title: '提交到 1688（真实下单）',
  detail: `将按 offerId=${o.supplierOfferId || '未填'}、数量 ${o.quantity}、单价 ${o.unitPrice} 在 1688 生成真实采购订单。`
    + '下单成功后本地状态变 SUBMITTED；远程失败会回滚为 DRAFT。若响应丢失但 1688 已成交，本地仍是 DRAFT，重试会产生重复单——需与 1688 后台对账。',
  run: () => runOrder(o, 'submit')
})

const cancelOrderConfirm = (o: PurchaseOrder) => ({
  title: '取消采购单',
  detail: o.alibabaOrderNo
    ? `采购单 ${o.orderNo || o.id} 已绑定 1688 订单 ${o.alibabaOrderNo}，取消会先关闭远程订单；`
      + '远程关单失败时本地取消会被中止（不会留下"ERP 已取消、1688 还在"的状态）。'
    : `采购单 ${o.orderNo || o.id} 尚未提交 1688，仅本地置为 CANCELED。`,
  run: () => runOrder(o, 'cancel')
})

/* ---------- FBA 货件 ---------- */
const shipments = makeList<FbaShipment>()
const shipmentStatus = ref('')
const activeShipment = ref<FbaShipment | null>(null)
const items = ref<FbaShipmentItem[]>([])
const receiveQty = ref<Record<number, number | string>>({})
const allocation = ref<AllocationResult | null>(null)
const receipt = ref<ReceiptResult | null>(null)
const shipMsg = ref('')

const loadShipments = (append = false) =>
  fetchPage(shipments, cursor => proc.listShipments(shop(), { status: shipmentStatus.value || undefined, cursor }),
    'FBA 货件', append)

const inTransit = computed(() => shipments.rows.value.filter(s => s.status === 'SHIPPED'))

const loadItems = async (shipmentId: number) => {
  const rows = await callApi('货件明细', () => proc.listShipmentItems(shipmentId))
  items.value = rows ?? []
  const defaults: Record<number, number | string> = {}
  items.value.forEach(it => {
    if (!it.id) return
    // 未签收的行默认按发货量全额填（receivedQuantity=0 也算未收），已签收的行由提交时排除
    defaults[it.id] = asNumber(it.receivedQuantity) > 0 ? (it.receivedQuantity as number) : it.quantity
  })
  receiveQty.value = defaults
}

/** 切到货件时清空上一次的结果面板；刷新明细但保留结果面板请直接用 loadItems。 */
const selectShipment = async (s: FbaShipment) => {
  if (!s.id) return
  activeShipment.value = s
  allocation.value = null
  receipt.value = null
  shipMsg.value = ''
  await loadItems(s.id)
}

const allocate = async (s: FbaShipment) => {
  if (!s.id) return
  const data = await callApi('头程费用分摊', () => proc.allocateShipmentCosts(s.id as number))
  if (data) {
    allocation.value = data
    if (activeShipment.value?.id === s.id) await loadItems(s.id as number)
  }
}

const receive = async () => {
  const s = activeShipment.value
  if (!s?.id) return
  const lines = items.value
    .filter(it => it.id && !it.receivedQuantity)
    .map(it => ({ itemId: it.id as number, receivedQty: asNumber(receiveQty.value[it.id as number]) }))
  if (!lines.length) { shipMsg.value = '没有可签收的明细行（已签收的行不可重复签收）'; return }
  const data = await callApi('提交签收', () => proc.receiveShipment(s.id as number, lines))
  if (data) {
    receipt.value = data
    shipMsg.value = data.allReceived ? '全部明细已签收' : `仍有 ${data.pendingItemCount ?? '?'} 行未签收`
    await loadItems(s.id as number)
  }
}

const markArrivalConfirm = (s: FbaShipment) => ({
  title: '标记货件到货',
  detail: '仅把货件状态改为 DELIVERED 并写入实际到货日，不做签收、不生成批次入库。'
    + '签收请用上方的「提交签收」，那一步才会写库存批次。',
  run: async () => {
    const res = await callApi('标记到货', () => proc.updateShipmentStatus(s.id as number, 'DELIVERED'))
    if (res) await loadShipments()
  }
})

const batchClass = (status?: string) =>
  status === 'ACTIVE' ? 'healthy' : status === 'DEPLETED' ? '' : 'risk'

const shipmentClass = (status?: string) =>
  status === 'DELIVERED' || status === 'CLOSED' ? 'healthy'
    : status === 'SHIPPED' ? 'risk' : ''

/* ---------- 库存批次 ---------- */
const batches = makeList<InventoryBatch>()
const batchSku = ref('')
const costSummary = ref<BatchCostSummary | null>(null)
const fifoQty = ref<number | string>('')
const fifoRows = ref<Array<Record<string, unknown>>>([])

const loadBatches = async (append = false) => {
  const sku = batchSku.value.trim()
  if (!sku) return
  if (!append) {
    costSummary.value = null
    fifoRows.value = []
  }
  await Promise.all([
    fetchPage(batches, cursor => proc.listBatches(shop(), sku, { cursor }), '库存批次', append),
    (async () => {
      const data = await callApi('批次成本聚合', () => proc.batchCostSummary(shop(), sku))
      if (data) costSummary.value = data
    })()
  ])
}

const fifoConfirm = () => ({
  title: 'FIFO 出库',
  detail: `将按入库顺序从 ${batchSku.value.trim()} 的 ACTIVE 批次中扣减可用量 ${fifoQty.value} 件，`
    + '并写出库批次明细。该操作直接改库存，不可撤销。',
  run: async () => {
    const rows = await callApi('FIFO 出库', () =>
      proc.fifoOutbound(shop(), batchSku.value.trim(), asNumber(fifoQty.value)))
    if (rows) {
      fifoRows.value = rows
      await loadBatches()
    }
  }
})

/* ---------- 通用调用 ---------- */
const shop = (): string => {
  const id = refreshShop()
  return id
}

const callApi = async <T>(label: string, fn: () => Promise<ApiResponse<T>>): Promise<T | null> => {
  busy.value = true
  msg.value = ''
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '接口返回非 200'}`)
      return null
    }
    msg.value = `${label}：成功`
    return res.data ?? null
  } catch (e) {
    pushError(`${label}：${(e as Error)?.message || '调用失败'}`)
    return null
  } finally {
    busy.value = false
  }
}

/* ---------- 弹窗与确认 ---------- */
const modal = ref<ModalKey | null>(null)
const form = ref<Record<string, any>>({})

const MODAL_DEFAULTS: Record<ModalKey, () => Record<string, any>> = {
  supplier: () => ({ rating: 4.0 }),
  offer: () => ({ isPreferred: 0, moq: 100, leadTimeDays: 7 }),
  plan: () => ({ urgency: 'NORMAL', plannedQty: 100 }),
  order: () => ({ quantity: 100 }),
  qc: () => ({ sampleCount: 10, failedCount: 0 }),
  shipment: () => ({ status: 'CREATED', shippingMethod: 'SEA' }),
  item: () => ({ quantity: 100 }),
  ship: () => ({})
}

const modalNote = computed(() => {
  switch (modal.value) {
    case 'order': return '采购单在本系统先落成 DRAFT，只有点「提交 1688」才会真的下单。'
    case 'offer': return '供货价是比价与计划金额的依据；shopId 由页面自动带上，无需手填。'
    case 'shipment': return '成本字段决定后续「费用分摊」按数量分摊的基数。'
    case 'qc': return '后端按合格率判定：≥95% PASS，<90% FAIL，其间 CONDITIONAL（让步接收，状态仍会置 QC_PASSED）。'
    default: return ''
  }
})

const openModal = (key: ModalKey, seed: Record<string, any> = {}) => {
  form.value = { ...MODAL_DEFAULTS[key](), ...seed }
  modal.value = key
}

const submitModal = async () => {
  const key = modal.value
  if (!key) return
  const shopId = shop()
  const body = { ...form.value }
  let done = false

  if (key === 'supplier') {
    if (!body.supplierName?.trim()) { pushError('新增供应商：名称必填'); return }
    done = (await callApi('新增供应商', () => proc.createSupplier({ ...body, shopId }))) !== null
  } else if (key === 'offer') {
    if (!body.sku?.trim()) { pushError('关联 SKU：SKU 必填'); return }
    done = (await callApi('关联供应商 SKU', () => proc.addSupplierProduct({ ...body, shopId }))) !== null
  } else if (key === 'plan') {
    if (!body.sku?.trim()) { pushError('新建计划：SKU 必填'); return }
    done = (await callApi('新建采购计划', () => proc.createPlan({ ...body, shopId, source: 'MANUAL' }))) !== null
  } else if (key === 'order') {
    if (!body.sku?.trim()) { pushError('新建采购单：SKU 必填'); return }
    done = (await callApi('新建采购单', () => proc.createOrder({ ...body, shopId }))) !== null
  } else if (key === 'qc') {
    const order = body.order as PurchaseOrder
    done = (await callApi('提交质检', () => proc.submitQualityCheck(order.id as number, {
      sampleCount: asNumber(body.sampleCount), failedCount: asNumber(body.failedCount),
      inspector: String(body.inspector || '').trim(), defectDescription: body.defectDescription
    }))) !== null
  } else if (key === 'shipment') {
    done = (await callApi('新建 FBA 货件', () => proc.createShipment({ ...body, shopId }))) !== null
  } else if (key === 'item') {
    const shipment = activeShipment.value
    if (!shipment?.id) { pushError('添加货件明细：请先在货件列表点「明细」选中货件'); return }
    const shipmentId = shipment.id
    done = (await callApi('添加货件明细', () => proc.addShipmentItem(shipmentId, body))) !== null
  } else if (key === 'ship') {
    const shipment = body.shipment as FbaShipment
    if (!String(body.carrier || '').trim() || !String(body.trackingNo || '').trim()) {
      pushError('确认发货：承运商与主运单号必填'); return
    }
    done = (await callApi('确认发货', () => proc.confirmShipment(shipment.id as number,
      String(body.carrier).trim(), String(body.trackingNo).trim()))) !== null
  }

  if (!done) return
  modal.value = null
  if (key === 'supplier') await loadSuppliers()
  else if (key === 'offer') await loadOffers()
  else if (key === 'plan') await loadPlans()
  else if (key === 'order' || key === 'qc') await loadOrders()
  else if (key === 'shipment') await loadShipments()
  else if (key === 'item' && activeShipment.value?.id) await loadItems(activeShipment.value.id)
  else if (key === 'ship') await loadShipments()
}

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<unknown> }>(null)
const confirmAction = (box: { title: string; detail: string; run: () => Promise<unknown> }) => { confirmBox.value = box }
const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

/* ---------- 本页统计 ---------- */
const pageAmountTotal = computed(() =>
  orders.rows.value.reduce((sum, o) => sum + asNumber(o.totalAmount), 0).toFixed(2))
const anyTruncated = computed(() =>
  [suppliers, plans, orders, shipments, batches].some(l => l.truncated.value))

const loadTabData = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  loading.value = true
  errors.value = []
  await Promise.all([loadSuppliers(), loadPlans(), loadOrders(), loadShipments()])
  loading.value = false
}

onMounted(loadTabData)
</script>

<style scoped>
.procurement-page { background: var(--color-background); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 0.5rem; }
.stat-card {
  background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem;
  text-align: center; box-shadow: var(--shadow-sm); border-top: 4px solid var(--color-primary);
}
.stat-card.warn { border-top-color: var(--color-warning); }
.stat-count { font-size: 1.5rem; font-weight: 700; color: var(--color-on-surface); font-variant-numeric: tabular-nums; }
.stat-label { font-size: 0.8125rem; color: var(--color-muted); margin-top: 0.125rem; }
.stat-note { font-size: 0.75rem; color: var(--color-muted); margin: 0 0 1rem; }

.error-zone {
  display: flex; align-items: center; gap: 0.5rem;
  background: var(--color-light-red); color: var(--color-error);
  border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem;
}

.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab {
  background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer;
}
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }

.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter-input, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.approver { font-size: 0.75rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.25rem; }

.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; font-weight: 500; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }

.row-actions { white-space: nowrap; }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; transition: all 0.2s; }
.action-btn:hover:not(:disabled) { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.cancel { background: var(--color-light-red); color: var(--color-error); }
.action-btn.cancel:hover:not(:disabled) { background: var(--color-error); color: var(--color-on-primary); }

.compare-table { margin-top: 0.75rem; }
.sub-block { border-top: 1px solid var(--color-border); margin-top: 1rem; padding-top: 0.875rem; }
.sub-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); margin-bottom: 0.5rem; }
.inline-row { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; align-items: center; flex-wrap: wrap; }
.inline-row input { flex: 1; min-width: 12rem; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; background: var(--color-surface); color: var(--color-on-surface); }
.cost-summary { gap: 1.5rem; font-size: 0.8125rem; color: var(--color-muted); }

.panel { background: var(--color-surface); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 1rem; margin-top: 1rem; }
.panel-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 0.5rem; }
.panel-title { font-weight: 600; color: var(--color-on-surface); font-size: 0.9375rem; }
.panel-note { font-size: 0.8125rem; color: var(--color-muted); }
.kv { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0; font-size: 0.8125rem; }
.kv dt { color: var(--color-muted); }
.kv dd { margin: 0; color: var(--color-on-surface); word-break: break-all; }
.diff-warn { color: var(--color-error); font-size: 0.8125rem; font-weight: 600; }
.diff-ok { color: var(--color-success); font-size: 0.8125rem; }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; }
.modal-note { font-size: 0.75rem; color: var(--color-muted); margin: 0.75rem 0 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }

.toast { position: fixed; right: 1.5rem; bottom: 1.5rem; background: var(--color-surface); color: var(--color-on-surface); border-left: 4px solid var(--color-primary); border-radius: var(--radius-md); box-shadow: var(--shadow-md); padding: 0.625rem 0.875rem; font-size: 0.8125rem; z-index: 2100; }

.skeleton { background: linear-gradient(90deg, var(--color-muted-light) 25%, var(--color-border) 37%, var(--color-muted-light) 63%); background-size: 400% 100%; animation: sk 1.4s ease infinite; }
@keyframes sk { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }

@media (max-width: 1024px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .stat-grid { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } }
</style>
