<template>
  <div class="report-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">经营报表</h1>
        <p class="hero-subtitle">利润明细 / 库存周转与滞销 / 日销趋势 / 实时利润快照 / 成本分摊</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺后再查看报表。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        快照口径缺口：后端把 VAT 成本、退款成本、其他成本写成常量 0（退款字段在 ProfitDetail 里根本不存在），
        所以「实时快照」的 netProfit 与 margin 会偏高。这里的数字用于趋势观察，不能当结算依据。
      </div>

      <template v-if="currentShopId">
        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="gotoTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- ==================== 经营概览 ==================== -->
        <div v-if="tab === 'overview'" class="tab-panel" data-panel="overview">
          <div v-if="dash" class="kpi-grid">
            <div class="kpi-card">
              <div class="kpi-value">{{ dash.kpi?.totalSales7d ?? '-' }}</div>
              <div class="kpi-label">近 7 日销售额</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ dash.kpi?.totalProfit7d ?? '-' }}</div>
              <div class="kpi-label">近 7 日利润</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ dash.kpi?.totalOrders7d ?? '-' }}</div>
              <div class="kpi-label">近 7 日订单</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ dash.kpi?.profitMargin7d ?? '-' }}</div>
              <div class="kpi-label">近 7 日毛利率</div>
            </div>
          </div>
          <p v-if="dash" class="muted">
            看板日期 {{ dash.reportDate || '-' }}；近 30 日滞销
            {{ dash.deadStockAnalysis?.deadStockCount ?? 0 }} 个 SKU /
            {{ dash.deadStockAnalysis?.totalDeadStockValue ?? 0 }}。
            30 日利润合计 {{ dash.profitSummary30d?.totalNetProfit ?? '-' }}。
          </p>

          <div class="table-card">
            <div class="block-title">每日经营概览</div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>日期</th><th>销售额</th><th>订单</th><th>件数</th><th>客单价</th><th>成本</th>
                  <th>广告</th><th>平台费</th><th>净利</th><th>毛利率</th><th>退款率</th><th>差评</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="o in overview" :key="`${o.reportDate}`">
                  <td class="mono">{{ o.reportDate }}</td>
                  <td>{{ o.totalSales }}</td>
                  <td>{{ o.totalOrders }}</td>
                  <td>{{ o.totalUnits }}</td>
                  <td>{{ o.avgOrderValue }}</td>
                  <td>{{ o.totalCost }}</td>
                  <td>{{ o.totalAdSpend }}</td>
                  <td>{{ o.totalFees }}</td>
                  <td :class="Number(o.netProfit) < 0 ? 'neg' : ''">{{ o.netProfit }}</td>
                  <td>{{ o.profitMargin }}</td>
                  <td>{{ o.refundRate }}</td>
                  <td>{{ o.negativeReviews }}</td>
                </tr>
                <tr v-if="!overview.length"><td colspan="12" class="empty-row">该店铺没有每日概览数据</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 利润明细 ==================== -->
        <div v-if="tab === 'profit'" class="tab-panel" data-panel="profit">
          <div class="filter-row">
            <label class="filter">ASIN<input v-model="pdAsin" @keyup.enter="loadProfitDetails()" /></label>
            <label class="filter">起<input type="date" v-model="pdStart" /></label>
            <label class="filter">止<input type="date" v-model="pdEnd" /></label>
            <button class="action-btn" @click="loadAllProfit">查询</button>
            <span class="muted">明细与汇总分别来自两个端点，汇总不是对明细的前端求和</span>
          </div>

          <div v-if="pSummary" class="kpi-grid">
            <div class="kpi-card">
              <div class="kpi-value">{{ pSummary.totalOrders ?? 0 }}</div>
              <div class="kpi-label">订单数（后端 COUNT）</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ pSummary.totalSales ?? '-' }}</div>
              <div class="kpi-label">销售额</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ pSummary.totalCost ?? '-' }}</div>
              <div class="kpi-label">成本合计</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ pSummary.totalNetProfit ?? '-' }}</div>
              <div class="kpi-label">净利 / 毛利率 {{ pSummary.overallMargin ?? '-' }}</div>
            </div>
          </div>

          <div class="table-card">
            <div class="block-title">按 ASIN 汇总</div>
            <table class="data-table">
              <thead>
                <tr><th>ASIN</th><th>订单</th><th>销售</th><th>成本</th><th>广告</th><th>平台费</th><th>毛利</th><th>净利</th><th>毛利率</th></tr>
              </thead>
              <tbody>
                <tr v-for="a in pSummary?.asinSummaries || []" :key="a.asin">
                  <td class="mono">{{ a.asin }}</td>
                  <td>{{ a.orderCount }}</td>
                  <td>{{ a.totalSales }}</td>
                  <td>{{ a.totalCost }}</td>
                  <td>{{ a.totalAdSpend }}</td>
                  <td>{{ a.totalFees }}</td>
                  <td>{{ a.grossProfit }}</td>
                  <td :class="a.netProfit < 0 ? 'neg' : ''">{{ a.netProfit }}</td>
                  <td>{{ a.margin }}</td>
                </tr>
                <tr v-if="pSummary && !(pSummary.asinSummaries || []).length">
                  <td colspan="9" class="empty-row">该区间没有利润明细，汇总为 0 是后端的真实统计</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="table-card">
            <div class="block-title">利润明细行</div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>订单</th><th>ASIN</th><th>SKU</th><th>日期</th><th>销售</th><th>商品成本</th>
                  <th>FBA</th><th>佣金</th><th>仓储</th><th>广告</th><th>VAT</th><th>头程</th>
                  <th>毛利</th><th>净利</th><th>毛利率</th><th>币种/汇率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in profitRows" :key="p.id">
                  <td class="mono">{{ p.amazonOrderId || '-' }}</td>
                  <td class="mono">{{ p.asin }}</td>
                  <td class="mono">{{ p.sku || '-' }}</td>
                  <td class="mono">{{ p.reportDate }}</td>
                  <td>{{ p.productSales }}</td>
                  <td>{{ p.productCost }}</td>
                  <td>{{ p.fbaFees }}</td>
                  <td>{{ p.referralFee }}</td>
                  <td>{{ p.storageFee }}</td>
                  <td>{{ p.advertisingCost }}</td>
                  <td>{{ p.vatTax }}</td>
                  <td>{{ p.inboundFreight }}</td>
                  <td>{{ p.grossProfit }}</td>
                  <td :class="Number(p.netProfit) < 0 ? 'neg' : ''">{{ p.netProfit }}</td>
                  <td>{{ p.margin }}</td>
                  <td>{{ p.currency || '-' }} / {{ p.exchangeRate ?? '-' }}</td>
                </tr>
                <tr v-if="!profitRows.length"><td colspan="16" class="empty-row">没有利润明细行（明细表为空说明还没跑过利润聚合）</td></tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(profitList) }}</span>
              <div class="page-actions">
                <button v-if="profitList.truncated.value" class="page-btn" :disabled="profitList.loading.value"
                        @click="loadProfitDetails(true)">加载下一页</button>
              </div>
            </div>
          </div>
        </div>

        <!-- ==================== 库存周转与滞销 ==================== -->
        <div v-if="tab === 'turnover'" class="tab-panel" data-panel="turnover">
          <div class="filter-row">
            <label class="filter">ASIN<input v-model="toAsin" @keyup.enter="loadTurnover" /></label>
            <button class="action-btn" @click="loadTurnover">查询</button>
          </div>

          <div v-if="dead" class="kpi-grid">
            <div class="kpi-card warn">
              <div class="kpi-value">{{ dead.deadStockCount ?? 0 }}</div>
              <div class="kpi-label">滞销 SKU 数</div>
            </div>
            <div class="kpi-card warn">
              <div class="kpi-value">{{ dead.totalDeadStockValue ?? 0 }}</div>
              <div class="kpi-label">滞销库存价值</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ turnover.length }}</div>
              <div class="kpi-label">本页周转记录</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ stockoutTotal }}</div>
              <div class="kpi-label">本页断货次数合计</div>
            </div>
          </div>

          <div class="table-card">
            <div class="block-title">库存周转</div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>ASIN</th><th>SKU</th><th>日期</th><th>平均库存价值</th><th>COGS</th><th>周转率</th>
                  <th>供应天数</th><th>断货次数</th><th>滞销天数</th><th>滞销价值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in turnover" :key="t.id">
                  <td class="mono">{{ t.asin }}</td>
                  <td class="mono">{{ t.sku || '-' }}</td>
                  <td class="mono">{{ t.reportDate }}</td>
                  <td>{{ t.avgInventoryValue }}</td>
                  <td>{{ t.cogs }}</td>
                  <td>{{ t.turnoverRate }}</td>
                  <td :class="(t.daysOfSupply ?? 0) > 90 ? 'neg' : ''">{{ t.daysOfSupply }}</td>
                  <td>{{ t.stockoutCount ?? 0 }}</td>
                  <td>{{ t.overstockDays ?? 0 }}</td>
                  <td>{{ t.deadStockValue ?? 0 }}</td>
                </tr>
                <tr v-if="!turnover.length"><td colspan="10" class="empty-row">没有周转数据（周转由报表聚合任务写入）</td></tr>
              </tbody>
            </table>
          </div>

          <div class="table-card">
            <div class="block-title">滞销明细（后端 dead-stock 端点）</div>
            <table class="data-table">
              <thead><tr><th>ASIN</th><th>SKU</th><th>日期</th><th>滞销价值</th><th>供应天数</th><th>滞销天数</th></tr></thead>
              <tbody>
                <tr v-for="d in dead?.details || []" :key="d.id">
                  <td class="mono">{{ d.asin }}</td>
                  <td class="mono">{{ d.sku || '-' }}</td>
                  <td class="mono">{{ d.reportDate }}</td>
                  <td>{{ d.deadStockValue }}</td>
                  <td>{{ d.daysOfSupply }}</td>
                  <td>{{ d.overstockDays }}</td>
                </tr>
                <tr v-if="dead && !(dead.details || []).length"><td colspan="6" class="empty-row">没有滞销 SKU</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 日销与销售对比 ==================== -->
        <div v-if="tab === 'sales'" class="tab-panel" data-panel="sales">
          <div class="filter-row">
            <label class="filter">ASIN<input v-model="sdAsin" @keyup.enter="loadSales" /></label>
            <label class="filter">起<input type="date" v-model="sdStart" /></label>
            <label class="filter">止<input type="date" v-model="sdEnd" /></label>
            <button class="action-btn" @click="loadSales">查询日销</button>
            <label class="filter">对比天数<input class="cell-input" type="number" min="1" max="365" v-model="cmpDays" /></label>
            <button class="action-btn" @click="runComparison">同期对比</button>
          </div>

          <div v-if="cmp" class="table-card">
            <div class="block-title">本期 vs 上期</div>
            <div class="cmp-grid">
              <div><span class="muted">本期</span> {{ cmp.currentPeriod }}：{{ cmp.currentUnits }} 件 / {{ cmp.currentSales }}</div>
              <div><span class="muted">上期</span> {{ cmp.previousPeriod }}：{{ cmp.previousUnits }} 件 / {{ cmp.previousSales }}</div>
              <div>件数增长 <b :class="Number(cmp.unitGrowth) < 0 ? 'neg' : 'pos'">{{ cmp.unitGrowth }}</b></div>
              <div>销售增长 <b :class="Number(cmp.salesGrowth) < 0 ? 'neg' : 'pos'">{{ cmp.salesGrowth }}</b></div>
            </div>
            <p class="muted">对比对象 ASIN：{{ cmp.asin || '整店' }}（asin 留空时后端按整店聚合）</p>
          </div>

          <div class="table-card">
            <div class="block-title">日销明细</div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>日期</th><th>ASIN</th><th>SKU</th><th>下单件</th><th>退款件</th><th>净件</th>
                  <th>毛销售额</th><th>退款额</th><th>净销售额</th><th>会话</th><th>转化率</th><th>Buybox</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in sales" :key="s.id">
                  <td class="mono">{{ s.reportDate }}</td>
                  <td class="mono">{{ s.asin }}</td>
                  <td class="mono">{{ s.sku || '-' }}</td>
                  <td>{{ s.unitsOrdered }}</td>
                  <td>{{ s.unitsRefunded }}</td>
                  <td>{{ s.netUnits }}</td>
                  <td>{{ s.grossSales }}</td>
                  <td>{{ s.refundAmount }}</td>
                  <td>{{ s.netSales }}</td>
                  <td>{{ s.sessions ?? '-' }}</td>
                  <td>{{ s.conversionRate ?? '-' }}</td>
                  <td>{{ s.buyBoxPercentage ?? '-' }}</td>
                </tr>
                <tr v-if="!sales.length"><td colspan="12" class="empty-row">该区间没有日销数据</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 实时快照与成本分摊 ==================== -->
        <div v-if="tab === 'snapshot'" class="tab-panel" data-panel="snapshot">
          <div class="filter-row">
            <label class="filter">SKU<input v-model="snapSku" @keyup.enter="loadSnapshots()" /></label>
            <label class="filter">起<input type="date" v-model="snapStart" /></label>
            <label class="filter">止<input type="date" v-model="snapEnd" /></label>
            <button class="action-btn" @click="loadAllSnapshot">查询快照</button>
            <button class="action-btn" @click="confirm(buildSnapshotConfirm)">按 SKU 重算快照</button>
            <label class="filter">趋势小时<input class="cell-input" type="number" min="1" max="168" v-model="trendHours" /></label>
            <button class="action-btn" :disabled="!snapSku.trim()" @click="runTrend">看趋势</button>
          </div>

          <div v-if="summary" class="kpi-grid">
            <div class="kpi-card">
              <div class="kpi-value">{{ summary.totalSales ?? '-' }}</div>
              <div class="kpi-label">区间销售额</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ summary.totalNetProfit ?? '-' }}</div>
              <div class="kpi-label">区间净利（含口径缺口）</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ summary.skuCount ?? 0 }}</div>
              <div class="kpi-label">涉及 SKU 数</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-value">{{ summary.overallMargin ?? '-' }}</div>
              <div class="kpi-label">整体毛利率</div>
            </div>
          </div>

          <div v-if="trend" class="table-card">
            <div class="block-title">{{ trend.sku }} 利润趋势（近 {{ trend.hours }} 小时，{{ trend.snapshotCount }} 个快照）</div>
            <table class="data-table">
              <thead><tr><th>时间</th><th>销售</th><th>毛利</th><th>净利</th><th>毛利率</th></tr></thead>
              <tbody>
                <tr v-for="(pt, i) in trend.trendData || []" :key="`${pt.time}-${i}`">
                  <td class="mono">{{ pt.time }}</td>
                  <td>{{ pt.sales }}</td>
                  <td>{{ pt.grossProfit }}</td>
                  <td :class="Number(pt.netProfit) < 0 ? 'neg' : ''">{{ pt.netProfit }}</td>
                  <td>{{ pt.margin }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="trend.trendTruncated" class="muted">趋势被截断：后端最多取 168 小时内的快照。</p>
          </div>

          <div class="table-card">
            <div class="block-title">快照列表</div>
            <table class="data-table">
              <thead>
                <tr>
                  <th>时间</th><th>SKU</th><th>ASIN</th><th>销售额</th><th>销量</th><th>商品成本</th><th>FBA</th>
                  <th>佣金</th><th>广告</th><th>仓储</th><th>头程</th><th>VAT</th><th>退款</th><th>其他</th>
                  <th>毛利</th><th>净利</th><th>毛利率</th><th>来源</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in snaps.rows.value" :key="s.id">
                  <td class="mono">{{ s.statTime }}</td>
                  <td class="mono">{{ s.sku }}</td>
                  <td class="mono">{{ s.asin || '-' }}</td>
                  <td>{{ s.salesAmount }}</td>
                  <td>{{ s.salesQuantity }}</td>
                  <td>{{ s.productCost }}</td>
                  <td>{{ s.fbaFees }}</td>
                  <td>{{ s.referralFee }}</td>
                  <td>{{ s.advertisingCost }}</td>
                  <td>{{ s.storageFee }}</td>
                  <td>{{ s.headhaulCost }}</td>
                  <td class="zero-flag">{{ s.vatCost }}</td>
                  <td class="zero-flag">{{ s.refundCost }}</td>
                  <td class="zero-flag">{{ s.otherCost }}</td>
                  <td>{{ s.grossProfit }}</td>
                  <td :class="Number(s.netProfit) < 0 ? 'neg' : ''">{{ s.netProfit }}</td>
                  <td>{{ s.margin }}</td>
                  <td>{{ s.dataSource }}</td>
                </tr>
                <tr v-if="!snaps.rows.value.length"><td colspan="18" class="empty-row">该区间没有快照：点「按 SKU 重算快照」生成</td></tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(snaps) }}</span>
              <div class="page-actions">
                <button v-if="snaps.truncated.value" class="page-btn" :disabled="snaps.loading.value"
                        @click="loadSnapshots(true)">加载下一页</button>
              </div>
            </div>
          </div>

          <div class="table-card">
            <div class="block-title">成本分摊</div>
            <div class="filter-row">
              <label class="filter">费用类型
                <select v-model="allocType" class="cell-input">
                  <option value="">全部</option>
                  <option value="HEADHAUL">头程</option>
                  <option value="ADVERTISING">广告</option>
                  <option value="STORAGE">仓储</option>
                  <option value="OTHER">其他</option>
                </select>
              </label>
              <button class="action-btn" @click="loadAllocations()">查询分摊</button>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>日期</th><th>类型</th><th>来源单号</th><th>来源描述</th><th>总额</th><th>币种</th><th>分摊方式</th><th>分摊明细</th></tr>
              </thead>
              <tbody>
                <tr v-for="a in allocs.rows.value" :key="a.id">
                  <td class="mono">{{ a.allocDate }}</td>
                  <td>{{ a.costType }}</td>
                  <td class="mono">{{ a.sourceRef || '-' }}</td>
                  <td>{{ a.sourceDesc || '-' }}</td>
                  <td>{{ a.totalAmount }}</td>
                  <td>{{ a.currency || '-' }}</td>
                  <td>{{ a.allocMethod || '-' }}</td>
                  <td class="mono cell-clip">{{ allocDetailText(a) }}</td>
                </tr>
                <tr v-if="!allocs.rows.value.length"><td colspan="8" class="empty-row">没有分摊记录</td></tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(allocs) }}</span>
              <div class="page-actions">
                <button v-if="allocs.truncated.value" class="page-btn" :disabled="allocs.loading.value"
                        @click="loadAllocations(true)">加载下一页</button>
              </div>
            </div>

            <div class="filter-row">
              <label class="filter">新分摊类型
                <select v-model="newAllocType" class="cell-input">
                  <option value="HEADHAUL">头程</option>
                  <option value="ADVERTISING">广告</option>
                  <option value="STORAGE">仓储</option>
                  <option value="OTHER">其他</option>
                </select>
              </label>
              <label class="filter">总额<input class="cell-input" type="number" min="0" step="0.01" v-model="newAllocAmount" /></label>
              <label class="filter">币种 *<input class="cell-input" v-model="newAllocCurrency" placeholder="CNY / USD" /></label>
              <label class="filter">来源标识<input v-model="newAllocSource" placeholder="如货件号，留空则不做幂等" /></label>
              <label class="filter">分摊项（逗号分隔，写 SKU 或 SKU:金额）<input v-model="newAllocSkus" /></label>
              <button class="action-btn" @click="confirm(allocateConfirm)">执行分摊</button>
            </div>
            <p class="muted alloc-note">
              写 SKU:金额时按给定金额入账，后端会校验合计等于总额；只写 SKU 时均摊。
              实时快照只消费 HEADHAUL 这一类（headhaulCost），其余类型入账也不会进利润，别指望它自动生效。
            </p>

            <div class="filter-row">
              <label class="filter">到货件分摊导入
                <select class="cell-input" v-model="pickShipmentId" @change="loadShipmentForImport">
                  <option value="">选择货件</option>
                  <option v-for="sh in importableShipments" :key="sh.id" :value="sh.id">
                    {{ sh.shipmentNo || sh.id }} · {{ sh.status }} · 总成本 {{ sh.totalCost ?? '-' }}
                  </option>
                </select>
              </label>
              <label class="filter">赔付/结算币种 *<input class="cell-input" v-model="importCurrency" placeholder="USD" /></label>
              <button class="action-btn" :disabled="!pickedShipment || busy" @click="confirm(shipmentImportConfirm)">
                按货件运费入账 HEADHAUL
              </button>
            </div>
            <p v-if="pickedShipment" class="muted">
              货件 {{ pickedShipment.shipmentNo }}：运费合计 {{ importPreview.freight }}（取每行 freightAllocation），
              关税 {{ importPreview.customs }}、其他 {{ importPreview.other }} 不会进这一笔——
              快照的 headhaulCost 只读运费口径，货件成本字段本身没有币种，所以币种要人确认。
            </p>

            <table v-if="allocResult.length" class="data-table">
              <thead><tr><th>SKU</th><th>分摊金额</th></tr></thead>
              <tbody>
                <tr v-for="r in allocResult" :key="r.sku"><td class="mono">{{ r.sku }}</td><td>{{ r.amount }}</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </main>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p class="confirm-detail">{{ confirmBox.detail }}</p>
        <div class="modal-actions">
          <button class="page-btn" @click="confirmBox = null">取消</button>
          <button class="page-btn" :disabled="busy" @click="runConfirm">确认执行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, type Ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import { asNumber } from '@/utils/format'
import type { ApiResponse, PageMeta } from '@/api/types'
import * as rpt from '@/api/report'
import { listShipments as listProcurementShipments, listShipmentItems as listProcurementShipmentItems } from '@/api/procurement'
import type { FbaShipment, FbaShipmentItem } from '@/api/procurement'
import type {
  ProfitDetail, ProfitSummaryReport, InventoryTurnover, DeadStockReport, SalesDaily,
  SalesComparison, BusinessOverview, ShopDashboard, ProfitSnapshot, ProfitTrend,
  RealtimeProfitSummary, CostAllocation
} from '@/api/report'

type TabKey = 'overview' | 'profit' | 'turnover' | 'sales' | 'snapshot'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'overview', label: '经营概览' },
  { key: 'profit', label: '利润明细' },
  { key: 'turnover', label: '库存周转与滞销' },
  { key: 'sales', label: '日销与同期对比' },
  { key: 'snapshot', label: '实时快照与成本分摊' }
]

const currentShop = ref('')
const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('overview')
const errors = ref<string[]>([])
const busy = ref(false)

const dash = ref<ShopDashboard | null>(null)
const overview = ref<BusinessOverview[]>([])

const profitList = makeList<ProfitDetail>()
const pSummary = ref<ProfitSummaryReport | null>(null)
const pdAsin = ref('')
const pdStart = ref('')
const pdEnd = ref('')

const turnover = ref<InventoryTurnover[]>([])
const dead = ref<DeadStockReport | null>(null)
const toAsin = ref('')

const sales = ref<SalesDaily[]>([])
const cmp = ref<SalesComparison | null>(null)
const sdAsin = ref('')
const sdStart = ref('')
const sdEnd = ref('')
const cmpDays = ref<number | string>(30)

const snaps = makeList<ProfitSnapshot>()
const summary = ref<RealtimeProfitSummary | null>(null)
const trend = ref<ProfitTrend | null>(null)
const snapSku = ref('')
const snapStart = ref('')
const snapEnd = ref('')
const trendHours = ref<number | string>(24)

const allocs = makeList<CostAllocation>()
const allocType = ref('')
const newAllocType = ref('HEADHAUL')
const newAllocAmount = ref<number | string>('')
const newAllocSkus = ref('')
const allocResult = ref<Array<{ sku: string; amount: number | string }>>([])
const newAllocCurrency = ref('')
const newAllocSource = ref('')
const importableShipments = ref<FbaShipment[]>([])
const importItems = ref<FbaShipmentItem[]>([])
const pickShipmentId = ref<number | string>('')
const importCurrency = ref('')

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

interface CursorList<T> {
  rows: Ref<T[]>
  cursor: Ref<string | null>
  truncated: Ref<boolean>
  loading: Ref<boolean>
}

/**
 * 报表端点都是游标分页：这里累积已加载页并保留后端的截断事实。
 * 报表场景最容易被「拿第一页当全量」坑掉（导出、汇总、对账都基于「这就是全部」的错觉）。
 */
function makeList<T>(): CursorList<T> {
  const rows = ref<T[]>([]) as unknown as Ref<T[]>
  const cursor = ref<string | null>(null)
  const truncated = ref(false)
  const loading = ref(false)
  return { rows, cursor, truncated, loading }
}

const pagerText = (list: CursorList<unknown>): string => {
  const loaded = list.rows.value.length
  return list.truncated.value ? `已加载 ${loaded} 条 · 后端标记仍有下一页` : `已加载 ${loaded} 条`
}

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const call = async <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void): Promise<boolean> => {
  busy.value = true
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return false
    }
    apply(res.data as T)
    return true
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
    return false
  } finally {
    busy.value = false
  }
}

const loadList = async <T>(
  list: CursorList<T>,
  label: string,
  fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  append: boolean
) => {
  if (!append) {
    list.rows.value = []
    list.cursor.value = null
  }
  list.loading.value = true
  try {
    const res = await fetcher(append ? list.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    list.rows.value = append ? list.rows.value.concat(batch) : batch
    const meta: PageMeta | undefined = res._page
    list.truncated.value = meta ? meta.truncated : false
    list.cursor.value = meta ? meta.nextCursor : null
    if (!meta && batch.length) {
      pushError(`${label}：响应没有分页元数据，无法判断是否还有下一页，本页可能不完整`)
    }
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    list.loading.value = false
  }
}

const shop = () => {
  const id = refreshShop()
  currentShop.value = id
  return id
}

/* ---------- 概览 ---------- */
const loadOverview = async () => {
  await Promise.all([
    call('经营看板', () => rpt.shopDashboard(shop()), (d) => { dash.value = d }),
    call('每日概览', () => rpt.listBusinessOverview(shop()), (d) => { overview.value = d || [] })
  ])
}

/* ---------- 利润明细 ---------- */
const loadProfitDetails = (append = false) =>
  // 后端 /report/v2/profit/list 无分页参数（全量返回），不做 cursor 续读
  loadList(profitList, '利润明细', () => rpt.listProfitDetails(shop(), {
    asin: pdAsin.value || undefined, startDate: pdStart.value || undefined, endDate: pdEnd.value || undefined
  }), append)

const loadAllProfit = async () => {
  await Promise.all([
    loadProfitDetails(),
    call('利润汇总', () => rpt.profitSummary(shop(), {
      startDate: pdStart.value || undefined, endDate: pdEnd.value || undefined
    }), (d) => { pSummary.value = d })
  ])
}

const profitRows = computed(() => profitList.rows.value)

/* ---------- 周转 ---------- */
const loadTurnover = async () => {
  await Promise.all([
    call('库存周转', () => rpt.listTurnover(shop(), toAsin.value || undefined), (d) => { turnover.value = d || [] }),
    call('滞销分析', () => rpt.deadStock(shop()), (d) => { dead.value = d })
  ])
}
const stockoutTotal = computed(() => turnover.value.reduce((sum, t) => sum + asNumber(t.stockoutCount), 0))

/* ---------- 日销 ---------- */
const loadSales = async () => {
  await call('日销明细', () => rpt.listSalesDaily(shop(), {
    asin: sdAsin.value || undefined, startDate: sdStart.value || undefined, endDate: sdEnd.value || undefined
  }), (d) => { sales.value = d || [] })
}

const runComparison = async () => {
  await call('同期对比', () => rpt.salesComparison(shop(), {
    asin: sdAsin.value || undefined, currentDate: sdEnd.value || undefined, compareDays: asNumber(cmpDays.value, 30)
  }), (d) => { cmp.value = d })
}

/* ---------- 快照 ---------- */
const loadSnapshots = (append = false) =>
  loadList(snaps, '利润快照', cursor => rpt.listSnapshots(shop(), {
    sku: snapSku.value || undefined, startTime: snapStart.value || undefined, endTime: snapEnd.value || undefined, cursor
  }), append)

const loadAllSnapshot = async () => {
  await Promise.all([
    loadSnapshots(),
    loadAllocations(),
    call('实时利润汇总', () => rpt.realtimeSummary(shop(), {
      startTime: snapStart.value || undefined, endTime: snapEnd.value || undefined
    }), (d) => { summary.value = d })
  ])
}

const buildSnapshotConfirm = {
  title: '按 SKU 重算实时快照',
  detail: `会用当前数据为该 SKU 生成一条新快照（后端 CALC 来源）。`
    + '注意后端把 VAT/退款/其他成本写死为 0，所以新快照的净利仍会偏高。',
  run: async () => {
    const sku = snapSku.value.trim()
    if (!sku) { pushError('重算快照：请先填 SKU'); return }
    const ok = await call('重算快照', () => rpt.buildSnapshot(shop(), sku), () => undefined)
    if (ok) await loadAllSnapshot()
  }
}

const runTrend = async () => {
  const sku = snapSku.value.trim()
  if (!sku) { pushError('看趋势：请先填 SKU'); return }
  await call('利润趋势', () => rpt.profitTrend(shop(), sku, asNumber(trendHours.value, 24)), (d) => { trend.value = d })
}

/* ---------- 分摊 ---------- */
const loadAllocations = (append = false) =>
  loadList(allocs, '成本分摊', cursor => rpt.listAllocations(shop(), { costType: allocType.value || undefined, cursor }), append)

const allocDetailText = (a: CostAllocation): string => {
  if (!a.allocDetails) return '-'
  // allocDetails 后端存的是 JSON 文本，不是对象：解析失败就原样显示，不假装是空的
  try {
    const parsed = JSON.parse(a.allocDetails) as Record<string, unknown>
    return Object.entries(parsed).map(([k, v]) => `${k}:${String(v)}`).join(' ')
  } catch {
    return a.allocDetails
  }
}

const pickedShipment = computed(() =>
  importableShipments.value.find(s => String(s.id) === String(pickShipmentId.value)) || null)

const sumBy = (pick: (i: FbaShipmentItem) => number | string | null | undefined) =>
  importItems.value.reduce((acc, i) => acc + asNumber(pick(i)), 0)

/** 只把运费摊成 HEADHAUL：实时快照的 headhaulCost 只认这一类，其余口径不在此消费。 */
const importPreview = computed(() => ({
  freight: sumBy(i => i.freightAllocation).toFixed(2),
  customs: sumBy(i => i.customsAllocation).toFixed(2),
  other: (() => {
    const total = sumBy(i => i.totalCost)
    const goods = sumBy(i => asNumber(i.unitCost) * asNumber(i.quantity))
    return Math.max(0, total - goods - sumBy(i => i.freightAllocation) - sumBy(i => i.customsAllocation)).toFixed(2)
  })()
}))

const loadImportShipments = async () => {
  await call('可导入货件', () => listProcurementShipments(shop(), { size: 50 }),
    (rows) => { importableShipments.value = (rows || []).filter(r => r.id) })
}

const loadShipmentForImport = async () => {
  const id = pickShipmentId.value
  importItems.value = []
  if (!id) return
  await call('货件明细分摊', () => listProcurementShipmentItems(Number(id), { size: 200 }),
    (rows) => { importItems.value = rows || [] })
}

const allocateConfirm = {
  title: '执行成本分摊',
  detail: `按 ${newAllocAmount.value} 的 ${newAllocType.value} 成本写一条 CostAllocation 记录。`
    + '来源标识留空时后端不做幂等：重复点一次就多记一条，实时利润会被重复扣。',
  run: async () => {
    const entries = newAllocSkus.value.split(/[,，\s]+/).filter(Boolean)
    const amount = asNumber(newAllocAmount.value)
    if (!entries.length) { pushError('分摊：分摊项不能为空'); return }
    if (amount <= 0) { pushError('分摊：总额必须大于 0'); return }
    if (!newAllocCurrency.value.trim()) { pushError('分摊：币种必填，后端不替调用方猜'); return }
    const ok = await call('成本分摊', () => rpt.allocateCost(shop(), newAllocType.value, amount, entries, {
      sourceRef: newAllocSource.value.trim() || undefined, currency: newAllocCurrency.value.trim()
    }), (map) => {
      allocResult.value = Object.entries(map || {}).map(([sku, v]) => ({ sku, amount: v }))
    })
    if (ok) await loadAllocations()
  }
}

const shipmentImportConfirm = {
  title: '按货件运费入账 HEADHAUL',
  detail: `把货件 ${pickedShipment.value?.shipmentNo || ''} 的运费合计 `
    + `${importPreview.value.freight} 按每行 freightAllocation 分摊到 SKU 并写入 HEADHAUL 成本，`
    + '来源标识用货件号，所以同一货件重复导入不会二次入账。关税与其他费用不在这一笔里。',
  run: async () => {
    const s = pickedShipment.value
    const currency = importCurrency.value.trim()
    if (!s?.id) { pushError('导入：请先选择货件'); return }
    if (!importItems.value.length) { pushError('导入：该货件还没有分摊明细，先在采购页执行费用分摊'); return }
    if (!currency) { pushError('导入：币种必填，货件成本字段没有记币种'); return }
    const entries = importItems.value
      .filter(i => asNumber(i.freightAllocation) > 0)
      .map(i => `${i.sku}:${asNumber(i.freightAllocation).toFixed(2)}`)
    const freight = asNumber(importPreview.value.freight)
    if (!entries.length || freight <= 0) { pushError('导入：运费合计为 0，没有可入账的金额'); return }
    const ok = await call('货件运费入账', () => rpt.allocateCost(shop(), 'HEADHAUL', freight, entries, {
      sourceRef: s.shipmentNo || `SHIP-${s.id}`, currency
    }), (map) => {
      allocResult.value = Object.entries(map || {}).map(([sku, v]) => ({ sku, amount: v }))
    })
    if (ok) await loadAllocations()
  }
}

const confirm = (box: { title: string; detail: string; run: () => Promise<void> }) => { confirmBox.value = box }
const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const TAB_LOADERS: Record<TabKey, () => Promise<unknown>> = {
  overview: loadOverview,
  profit: loadAllProfit,
  turnover: loadTurnover,
  sales: loadSales,
  snapshot: async () => { await Promise.all([loadAllSnapshot(), loadImportShipments()]) }
}

const loaded = new Set<TabKey>()
const gotoTab = async (key: TabKey) => {
  tab.value = key
  if (loaded.has(key)) return
  loaded.add(key)
  await TAB_LOADERS[key]()
}

onMounted(async () => {
  if (!shop()) return
  await gotoTab('overview')
})
</script>

<style scoped>
.report-page { background: var(--color-background); }

.error-zone, .notice-zone {
  display: flex; align-items: center; gap: 0.5rem; border-radius: var(--radius-md);
  padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem;
}
.error-zone { background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); display: block; }

.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab {
  background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer;
}
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 0.5rem; }
.kpi-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; text-align: center; box-shadow: var(--shadow-sm); border-top: 4px solid var(--color-primary); }
.kpi-card.warn { border-top-color: var(--color-warning); }
.kpi-value { font-size: 1.5rem; font-weight: 700; color: var(--color-on-surface); font-variant-numeric: tabular-nums; }
.kpi-label { font-size: 0.8125rem; color: var(--color-muted); margin-top: 0.125rem; }

.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.neg { color: var(--color-error); font-weight: 600; }
.pos { color: var(--color-success); font-weight: 600; }
/* 后端常量 0 的三个字段：显示成"口径占位"而不是真数字 */
.zero-flag { color: var(--color-muted); font-style: italic; }

.block-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); padding: 0.75rem 1rem 0.25rem; }
.table-card { margin-bottom: 1rem; }
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.5rem 1rem; }
.cell-clip { max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.action-btn { padding: 0.3rem 0.7rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.8125rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.cmp-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem 1rem; padding: 0 1rem 0.5rem; font-size: 0.875rem; color: var(--color-on-surface); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 520px; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }

@media (max-width: 1024px) { .kpi-grid { grid-template-columns: repeat(2, 1fr); } .cmp-grid { grid-template-columns: 1fr; } }
@media (max-width: 768px) { .kpi-grid { grid-template-columns: 1fr; } }
</style>
