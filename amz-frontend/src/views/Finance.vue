<template>
  <div class="finance-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext 精简，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">财务管理</h1>
        <p class="hero-subtitle">业财一体化：凭证与金蝶同步 / 回款对账 / 结算原表 / 费用差异 / 亚马逊索赔 / 单品利润 / VAT</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看财务数据。
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <!-- 维度切换 -->
      <div class="dim-tabs">
        <button v-for="t in TABS" :key="t.key" :class="['dim-tab', { active: tab === t.key }]"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <!-- 凭证列表 -->
      <div v-show="tab === 'voucher'">
        <div class="filter-bar">
          <select v-model="filterSourceType" class="filter-select">
            <option value="">全部类型</option>
            <option value="ORDER">订单收入</option>
            <option value="PROCUREMENT">采购成本</option>
            <option value="PLATFORM_FEE">平台费用</option>
            <option value="REFUND">退款</option>
          </select>
          <button class="filter-btn" @click="loadVouchers(false)">查询</button>
        </div>

        <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="table-card sk-table-card">
            <div v-for="i in 5" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
          </div>
        </div>

        <template v-if="!loading">
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>凭证号</th>
                <th>类型</th>
                <th>原币金额</th>
                <th>CNY 金额</th>
                <th>店铺</th>
                <th>金蝶状态</th>
                <th>业务日期</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in vouchers" :key="v.id">
                <td class="mono">{{ v.voucherNo }}</td>
                <td><span class="status-tag" :class="sourceTypeClass(v.sourceType)">{{ sourceTypeText(v.sourceType) }}</span></td>
                <td>{{ v.originalAmount }} {{ v.currency }}</td>
                <td class="mono">¥{{ v.cnyAmount }}</td>
                <td>{{ v.shopId }}</td>
                <td><span class="status-tag" :class="syncStatusClass(v.kingdeeSyncStatus)">{{ syncStatusText(v.kingdeeSyncStatus) }}</span></td>
                <td class="mono">{{ v.bizDate }}</td>
                <td><button class="sync-btn" :disabled="syncing === v.id" @click="handleSync(v)">{{ syncing === v.id ? '同步中...' : '同步金蝶' }}</button></td>
              </tr>
              <tr v-if="!loading && vouchers.length === 0">
                <td colspan="8" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:book-open-outline" width="32" class="empty-icon" />
                    <span>暂无凭证数据</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="pagination">
          <span class="page-info">
            已加载 {{ vouchers.length }} 条<template v-if="truncated">，仍有更多未加载</template>
          </span>
          <div class="page-actions">
            <button v-if="truncated" class="page-btn" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中...' : '加载更多' }}
            </button>
          </div>
        </div>

        <div v-if="truncated" class="truncated-tip" role="status">
          结果已被服务端截断：当前列表不是全量。对账、导出或金额汇总请继续翻页取完，
          不要直接拿这一页当完整数据。
        </div>
        </template>
      </div>

      <!-- 利润查询 -->
      <div v-show="tab === 'profit'">
        <div class="filter-bar">
          <input type="date" v-model="profitStart" class="filter-date" />
          <span class="range-sep">至</span>
          <input type="date" v-model="profitEnd" class="filter-date" />
          <button class="filter-btn" @click="loadProfit">查询利润</button>
        </div>

        <div v-if="profitLoading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="summary-grid">
            <div v-for="i in 2" :key="i" class="summary-card">
              <div class="skeleton sk-line sk-line-sm"></div>
              <div class="skeleton sk-line sk-line-lg"></div>
            </div>
          </div>
        </div>

        <div v-if="!profitLoading" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">店铺利润（CNY）</div>
            <div class="summary-value" :class="profitError || profitNum === null ? 'profit-flat' : profitNum > 0 ? 'profit-positive' : profitNum < 0 ? 'profit-negative' : 'profit-flat'">{{ profitError ? '—' : '¥' + profitDisplay }}<span v-if="profitError">（{{ profitError }}）</span></div>
          </div>
          <div class="summary-card">
            <div class="summary-label">统计区间</div>
            <div class="summary-value small">{{ profitStart || '不限' }} ~ {{ profitEnd || '不限' }}</div>
          </div>
        </div>

        <div class="profit-note">
          利润 = 订单收入(ORDER) - 采购成本(PROCUREMENT) - 平台费用(PLATFORM_FEE) - 退款(REFUND)，按借贷方向汇总，单位 CNY。
        </div>
      </div>
      <!-- 回款对账 -->
      <div v-if="tab === 'collection'" class="tab-panel" data-panel="collection">
        <div class="filter-bar">
          <select v-model="colStatus" class="filter-select">
            <option value="">全部状态</option>
            <option v-for="s in COLLECTION_STATUS" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="filter-btn" :disabled="col.loading.value" @click="loadCollections(false)">刷新</button>
          <button class="filter-btn" :disabled="busy" @click="confirm(rebuildConfirm)">重算回款</button>
        </div>

        <div class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">应回合计</div>
            <div class="summary-value small">¥{{ colSummary?.receivableTotal ?? '-' }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">实收合计</div>
            <div class="summary-value small">¥{{ colSummary?.netReceivedTotal ?? '-' }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">在途金额</div>
            <div class="summary-value small">¥{{ colSummary?.inTransitAmount ?? '-' }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">短款（需追）</div>
            <div class="summary-value small">{{ colSummary?.shortfallOrders ?? 0 }} 单 / ¥{{ colSummary?.shortfallAmount ?? '-' }}</div>
          </div>
        </div>
        <p v-if="colSummary?.warnings?.length" class="profit-note">口径提示：{{ colSummary.warnings.join('；') }}</p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>亚马逊订单号</th><th>币种</th><th>应回</th><th>平台扣费</th><th>退款</th>
                <th>赔付</th><th>实收</th><th>短款</th><th>到账日</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in col.rows.value" :key="c.id">
                <td class="mono">{{ c.amazonOrderId }}</td>
                <td>{{ c.currency || '-' }}</td>
                <td>{{ c.receivable }}</td>
                <td>{{ c.feeDeducted }}</td>
                <td>{{ c.refunded }}</td>
                <td>{{ c.reimbursed }}</td>
                <td>{{ c.netReceived }}</td>
                <td :class="(Number(c.shortfall) || 0) > 0 ? 'profit-negative' : ''">{{ c.shortfall ?? 0 }}</td>
                <td class="mono">{{ c.depositDate || '未到账' }}</td>
                <td><span class="status-tag">{{ c.status }}</span></td>
              </tr>
              <tr v-if="!col.loading.value && col.rows.value.length === 0">
                <td colspan="10" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:bank-outline" width="32" class="empty-icon" />
                    <span>暂无回款记录：需先同步结算并「重算回款」</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <cursor-bar :state="col" :load="loadCollections" />
      </div>

      <!-- 结算原表 -->
      <div v-if="tab === 'settlement'" class="tab-panel" data-panel="settlement">
        <div class="filter-bar">
          <input v-model="settleOrder" class="filter-date" placeholder="按亚马逊订单号过滤" />
          <button class="filter-btn" :disabled="settle.loading.value" @click="loadSettlements(false)">刷新</button>
          <button class="filter-btn" :disabled="busy" @click="confirm(settlementSyncConfirm)">从 SP-API 同步结算</button>
        </div>

        <div v-if="ingest" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">读到的报表行数</div>
            <div class="summary-value small">{{ ingest.dataLineCount ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">新入库</div>
            <div class="summary-value small">{{ ingest.inserted ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">重复跳过</div>
            <div class="summary-value small">{{ ingest.skipped ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">失败行</div>
            <div class="summary-value small" :class="(ingest.failed ?? 0) > 0 ? 'profit-negative' : ''">{{ ingest.failed ?? 0 }}</div>
          </div>
        </div>
        <p v-if="ingest" class="profit-note">
          「读到 / 入库 / 跳过」是三个不同的数：全部等于「读了 N 行」会让重复拉取和数据没拉到长得一样。
        </p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>结算单号</th><th>订单号</th><th>SKU</th><th>交易类型</th><th>金额类型</th>
                <th>金额</th><th>币种</th><th>到账日</th><th>来源</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in settle.rows.value" :key="d.id">
                <td class="mono">{{ d.settlementId }}</td>
                <td class="mono">{{ d.amazonOrderId || '-' }}</td>
                <td class="mono">{{ d.sku || '-' }}</td>
                <td>{{ d.transactionType }}</td>
                <td>{{ d.amountType || '-' }}</td>
                <td :class="Number(d.amount) < 0 ? 'profit-negative' : 'profit-positive'">{{ d.amount }}</td>
                <td>{{ d.currency || '-' }}</td>
                <td class="mono">{{ d.depositDate || '-' }}</td>
                <td>{{ d.source || '-' }}</td>
              </tr>
              <tr v-if="!settle.loading.value && settle.rows.value.length === 0">
                <td colspan="9" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:file-document-outline" width="32" class="empty-icon" />
                    <span>暂无结算明细：差异扫描与回款都依赖它，请先同步</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <cursor-bar :state="settle" :load="loadSettlements" />
      </div>

      <!-- 费用差异 -->
      <div v-if="tab === 'discrepancy'" class="tab-panel" data-panel="discrepancy">
        <div class="filter-bar">
          <select v-model="discStatus" class="filter-select">
            <option value="">全部状态</option>
            <option v-for="s in DISCREPANCY_STATUS" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="filter-btn" :disabled="disc.loading.value" @click="loadDiscrepancies(false)">刷新</button>
          <button class="filter-btn" :disabled="busy" @click="runScan">扫描差异</button>
          <button class="filter-btn" :disabled="busy" @click="openShortage()">登记入库短收</button>
        </div>

        <div v-if="scan" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">本次新建</div>
            <div class="summary-value small">{{ scan.created ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">已存在跳过</div>
            <div class="summary-value small">{{ scan.skippedExisting ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">无预估费用可对照</div>
            <div class="summary-value small">{{ scan.skippedNoEstimate ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">可索赔金额</div>
            <div class="summary-value small profit-positive">{{ scan.claimableAmount ?? 0 }}</div>
          </div>
        </div>
        <p v-if="scan?.warnings?.length" class="profit-note">扫描提示：{{ scan.warnings.join('；') }}</p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr><th>SKU</th><th>货件</th><th>差异类型</th><th>应收</th><th>实收</th><th>差额</th><th>币种</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="d in disc.rows.value" :key="d.id">
                <td class="mono">{{ d.sku || '-' }}</td>
                <td class="mono">{{ d.shipmentId || '-' }}</td>
                <td>{{ d.discrepancyType }}</td>
                <td>{{ d.expectedAmount }}</td>
                <td>{{ d.actualAmount }}</td>
                <td class="profit-negative">{{ d.difference }}</td>
                <td>{{ d.currency || '-' }}</td>
                <td><span class="status-tag">{{ d.status }}</span></td>
                <td>
                  <button class="sync-btn" :disabled="busy || d.status !== 'OPEN'" @click="confirm(claimFrom(d))">生成索赔单</button>
                  <button class="sync-btn" :disabled="busy || d.status !== 'OPEN'" @click="confirm(dismissConfirm(d))">忽略</button>
                </td>
              </tr>
              <tr v-if="!disc.loading.value && disc.rows.value.length === 0">
                <td colspan="9" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:scale-balance" width="32" class="empty-icon" />
                    <span>暂无费用差异记录：点「扫描差异」从结算明细里比对</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <cursor-bar :state="disc" :load="loadDiscrepancies" />

        <div class="table-card">
          <div class="panel-title">入库短收待登记（来自采购域签收事实）</div>
          <div class="filter-bar">
            <button class="filter-btn" :disabled="shortagesLoading" @click="loadShortages">刷新待登记清单</button>
            <span class="muted">
              单位成本是我方口径且没有记币种，亚马逊赔付按站点币种结算：
              金额与币种请在登记时确认，不要直接采信带入的成本数。
            </span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>亚马逊货件号</th><th>内部货件号</th><th>SKU</th><th>应发</th><th>实收</th><th>短收</th><th>我方单位成本口径</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in shortages" :key="s.itemId">
                <td class="mono">{{ s.fbaShipmentId || '-' }}</td>
                <td class="mono">{{ s.shipmentNo || s.shipmentId }}</td>
                <td class="mono">{{ s.sku || '-' }}</td>
                <td>{{ s.expectedQty }}</td>
                <td>{{ s.receivedQty }}</td>
                <td class="profit-negative">{{ s.shortUnits }}</td>
                <td>{{ s.unitCost ?? '-' }}</td>
                <td><button class="sync-btn" :disabled="busy" @click="prefillShortage(s)">按此行登记</button></td>
              </tr>
              <tr v-if="!shortages.length"><td colspan="8" class="empty-row">没有待登记的入库短收</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 亚马逊索赔 -->
      <div v-if="tab === 'claim'" class="tab-panel" data-panel="claim">
        <div class="filter-bar">
          <select v-model="claimStatus" class="filter-select">
            <option value="">全部状态</option>
            <option v-for="s in CLAIM_STATUS" :key="s" :value="s">{{ s }}</option>
          </select>
          <button class="filter-btn" :disabled="claimList.loading.value" @click="loadClaims(false)">刷新</button>
          <button class="filter-btn" :disabled="busy" @click="runReconcile">与平台对账</button>
        </div>

        <div v-if="claimSum" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">索赔单总数</div>
            <div class="summary-value small">{{ claimSum.total ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">待提交 / 已提交</div>
            <div class="summary-value small">{{ claimSum.candidate ?? 0 }} / {{ claimSum.submitted ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">已获赔付</div>
            <div class="summary-value small profit-positive">{{ claimSum.reimbursedAmountTotal ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">成功率 / 平均结案天数</div>
            <div class="summary-value small">{{ claimSum.successRate ?? '-' }}% / {{ claimSum.avgSettlementDays ?? '-' }}</div>
          </div>
        </div>

        <div v-if="recon" class="reconcile">
          <div class="recon-head">平台 Adjustment 与系统索赔单对账</div>
          <div class="recon-grid">
            <span>matched {{ recon.matched ?? 0 }}</span>
            <span>平台侧 {{ recon.platformAdjustmentCount ?? 0 }} 笔 / {{ recon.platformTotal ?? 0 }}</span>
            <span>系统侧合计 {{ recon.systemTotal ?? 0 }}</span>
            <span :class="Number(recon.difference) ? 'profit-negative' : ''">差额 {{ recon.difference ?? 0 }}</span>
          </div>
          <div class="recon-cols">
            <div>
              <div class="recon-sub">只有平台有（系统未登记 → 可能漏提索赔）</div>
              <div v-for="(p, i) in recon.platformOnly || []" :key="`p${i}`" class="recon-line">
                {{ p.reference || '-' }} / {{ p.sku || '-' }} / {{ p.amount }} {{ p.currency || '' }}
              </div>
              <div v-if="!(recon.platformOnly || []).length" class="muted">无</div>
            </div>
            <div>
              <div class="recon-sub">只有系统有（平台未赔付或口径不同）</div>
              <div v-for="(s, i) in recon.systemOnly || []" :key="`s${i}`" class="recon-line">
                {{ s.reference || '-' }} / {{ s.sku || '-' }} / {{ s.amount }} {{ s.currency || '' }}
              </div>
              <div v-if="!(recon.systemOnly || []).length" class="muted">无</div>
            </div>
          </div>
          <p v-if="recon.warnings?.length" class="profit-note">对账提示：{{ recon.warnings.join('；') }}</p>
        </div>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr><th>索赔单号</th><th>SKU</th><th>货件</th><th>差异类型</th><th>索赔额</th><th>已赔付</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="c in claimList.rows.value" :key="c.id">
                <td class="mono">{{ c.claimNo || c.id }}</td>
                <td class="mono">{{ c.sku || '-' }}</td>
                <td class="mono">{{ c.shipmentId || '-' }}</td>
                <td>{{ c.discrepancyType || '-' }}</td>
                <td>{{ c.claimAmount }}</td>
                <td>{{ c.reimbursedAmount ?? 0 }}</td>
                <td><span class="status-tag">{{ c.status }}</span></td>
                <td>
                  <button v-if="c.status === 'CANDIDATE'" class="sync-btn" :disabled="busy" @click="runClaim(c, 'submit')">提交平台</button>
                  <button v-if="c.status === 'SUBMITTED'" class="sync-btn" :disabled="busy" @click="runClaim(c, 'accept')">登记受理</button>
                  <button v-if="c.status === 'ACCEPTED'" class="sync-btn" :disabled="busy" @click="openReimburse(c)">登记赔付</button>
                  <button v-if="c.status !== 'REIMBURSED' && c.status !== 'REJECTED'" class="sync-btn" :disabled="busy" @click="confirm(rejectConfirm(c))">驳回</button>
                </td>
              </tr>
              <tr v-if="!claimList.loading.value && claimList.rows.value.length === 0">
                <td colspan="8" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:shield-check-outline" width="32" class="empty-icon" />
                    <span>暂无索赔单：可从费用差异页「生成索赔单」</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <cursor-bar :state="claimList" :load="loadClaims" />
      </div>

      <!-- 单品利润 -->
      <div v-if="tab === 'skuprofit'" class="tab-panel" data-panel="skuprofit">
        <div class="filter-bar">
          <input type="date" v-model="spAfter" class="filter-date" />
          <span class="range-sep">至</span>
          <input type="date" v-model="spBefore" class="filter-date" />
          <input v-model="spSku" class="filter-date" placeholder="指定 SKU（可空）" />
          <button class="filter-btn" :disabled="spLoading" @click="runSkuProfit">计算单品利润</button>
        </div>

        <div v-if="sp" class="summary-grid">
          <div class="summary-card">
            <div class="summary-label">SKU 数</div>
            <div class="summary-value small">{{ sp.skuCount ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">合计收入</div>
            <div class="summary-value small">{{ sp.totals?.revenue ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">合计利润</div>
            <div class="summary-value small" :class="Number(sp.totals?.profit) < 0 ? 'profit-negative' : 'profit-positive'">{{ sp.totals?.profit ?? 0 }}</div>
          </div>
          <div class="summary-card">
            <div class="summary-label">成本不全的 SKU</div>
            <div class="summary-value small" :class="sp.costDataComplete ? '' : 'profit-negative'">{{ sp.incompleteSkuCount ?? 0 }}</div>
          </div>
        </div>
        <p v-if="sp && !sp.costDataComplete" class="profit-note">
          有 {{ sp.incompleteSkuCount ?? 0 }} 个 SKU 的采购成本取不到（批次缺失或采购域降级），
          它们的 profit 不是确定值；下方按后端 profitIsComplete 标记显示，不补数。
        </p>

        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>SKU</th><th>销量</th><th>收入</th><th>佣金</th><th>配送费</th><th>仓储费</th>
                <th>退款</th><th>赔付</th><th>成本</th><th>利润</th><th>单件利润</th><th>毛利率</th><th>数据完整</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in sp?.entries || []" :key="e.sku">
                <td class="mono">{{ e.sku }}</td>
                <td>{{ e.unitsSold ?? 0 }}</td>
                <td>{{ e.revenue }}</td>
                <td>{{ e.commission }}</td>
                <td>{{ e.fulfillmentFee }}</td>
                <td>{{ e.storageFee }}</td>
                <td>{{ e.refunded }}</td>
                <td>{{ e.reimbursed }}</td>
                <td>{{ e.costMissing ? '缺' : e.cogs }}</td>
                <td :class="Number(e.profit) < 0 ? 'profit-negative' : ''">{{ e.profit }}</td>
                <td>{{ e.profitPerUnit ?? '-' }}</td>
                <td>{{ e.marginRate ?? '-' }}</td>
                <td>
                  <span class="status-tag" :class="e.profitIsComplete ? 'sync-synced' : 'sync-failed'">
                    {{ e.profitIsComplete ? '完整' : '不完整' }}
                  </span>
                </td>
              </tr>
              <tr v-if="sp && (sp.entries || []).length === 0">
                <td colspan="13" class="empty-row">该区间没有销量记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- VAT 与凭证补数 -->
      <div v-if="tab === 'tax'" class="tab-panel" data-panel="tax">
        <div class="table-card">
          <div class="panel-title">VAT 试算</div>
          <div class="filter-bar">
            <input v-model="vatCountry" class="filter-date" placeholder="国家码 US/DE/UK" />
            <input v-model="vatAmount" type="number" class="filter-date" placeholder="金额" />
            <button class="filter-btn" :disabled="busy" @click="runVat('calc')">算 VAT</button>
            <button class="filter-btn" :disabled="busy" @click="runVat('rate')">查税率</button>
            <button class="filter-btn" :disabled="busy" @click="runVat('threshold')">超门槛检查</button>
          </div>
          <p class="vat-line">{{ vatResult || '后端返回的是给人看的字符串，这里原样显示，不在前端拆成数字。' }}</p>
        </div>

        <div class="table-card">
          <div class="panel-title">月结与凭证补数</div>
          <div class="filter-bar">
            <input v-model="closeYear" type="number" class="filter-date" placeholder="年" />
            <input v-model="closeMonth" type="number" class="filter-date" placeholder="月" />
            <button class="filter-btn" :disabled="busy" @click="confirm(monthlyCloseConfirm)">执行 VAT 月结</button>
          </div>
          <div class="filter-bar">
            <button class="filter-btn" :disabled="busy" @click="runSettlementVouchers">补结算扣费/退款凭证</button>
            <button class="filter-btn" :disabled="busy" @click="runProcurementVouchers">补采购成本凭证</button>
            <button class="filter-btn" :disabled="busy" @click="reloadVouchers">回读凭证列表</button>
          </div>
          <dl v-if="svReport" class="kv-grid">
            <dt>扫描结算行</dt><dd>{{ svReport.scanned ?? 0 }}</dd>
            <dt>费用凭证</dt><dd>{{ svReport.feeVouchers ?? 0 }}</dd>
            <dt>退款凭证</dt><dd>{{ svReport.refundVouchers ?? 0 }}</dd>
            <dt>幂等命中</dt><dd>{{ svReport.existing ?? 0 }}</dd>
            <dt>跳过（类型/无币种/零金额）</dt><dd>{{ svReport.skippedByKind ?? 0 }} / {{ svReport.skippedNoCurrency ?? 0 }} / {{ svReport.skippedZeroAmount ?? 0 }}</dd>
            <dt>是否还有未处理</dt><dd :class="svReport.capped ? 'profit-negative' : ''">{{ svReport.capped ? '是，需再跑一次' : '否' }}</dd>
          </dl>
          <dl v-if="pvReport" class="kv-grid">
            <dt>读到的采购单</dt><dd>{{ pvReport.scanned ?? 0 }}（{{ pvReport.pagesRead ?? 0 }} 页）</dd>
            <dt>新凭证</dt><dd>{{ pvReport.generated ?? 0 }}</dd>
            <dt>幂等命中</dt><dd>{{ pvReport.existing ?? 0 }}</dd>
            <dt>跳过（无单号/零金额）</dt><dd>{{ pvReport.skippedNoOrderNo ?? 0 }} / {{ pvReport.skippedZeroAmount ?? 0 }}</dd>
            <dt>采购域状态</dt><dd :class="pvReport.remoteDegraded ? 'profit-negative' : ''">
              {{ pvReport.remoteDegraded ? `降级：${pvReport.remoteMessage || '读不到'}（结果不完整，别当成没有成本）` : '读取正常' }}
            </dd>
          </dl>
          <div v-if="closeResult" class="muted">{{ JSON.stringify(closeResult) }}</div>
        </div>
      </div>

      <!-- 确认弹窗 -->
      <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
        <div class="modal" role="dialog" aria-modal="true">
          <h3>{{ confirmBox.title }}</h3>
          <p class="confirm-detail">{{ confirmBox.detail }}</p>
          <div class="modal-actions">
            <button class="filter-btn" @click="confirmBox = null">取消</button>
            <button class="filter-btn" :disabled="busy" @click="runConfirm">确认执行</button>
          </div>
        </div>
      </div>

      <!-- 短收登记 / 赔付登记 -->
      <div v-if="formKind" class="modal-mask" @click.self="formKind = null">
        <div class="modal" role="dialog" aria-modal="true">
          <h3>{{ formKind === 'shortage' ? '登记入库短收差异' : '登记平台赔付金额' }}</h3>
          <div v-if="formKind === 'shortage'" class="form-grid">
            <label>SKU *<input v-model="shortage.sku" /></label>
            <label>货件号 *<input v-model="shortage.shipmentId" /></label>
            <label>短收件数 *<input type="number" min="1" v-model="shortage.shortageUnits" /></label>
            <label>单件金额<input type="number" step="0.01" min="0" v-model="shortage.unitAmount" /></label>
            <label>币种 *<input v-model="shortage.currency" placeholder="亚马逊赔付币种，如 USD" /></label>
            <label>备注<input v-model="shortage.note" /></label>
          </div>
          <div v-else class="form-grid">
            <label>索赔单<input :value="formClaim?.claimNo || formClaim?.id" disabled /></label>
            <label>索赔额<input :value="formClaim?.claimAmount" disabled /></label>
            <label>实际赔付 *<input type="number" step="0.01" min="0" v-model="reimburseAmount" /></label>
          </div>
          <div class="modal-actions">
            <button class="filter-btn" @click="formKind = null">取消</button>
            <button class="filter-btn" :disabled="busy" @click="submitForm">提交</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, defineComponent, h, type PropType, type Ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { listVouchers, syncToKingdee, calculateProfit } from '@/api/finance'
import type { AccountingVoucher } from '@/api/finance'
import { useShopGuard } from '@/composables/useShopGuard'
import { useToast } from '@/composables/useToast'
import type { ApiResponse } from '@/api/types'
import * as ext from '@/api/finance-ext'
import { listReceiptShortages, type ReceiptShortage } from '@/api/procurement'
import {
  COLLECTION_STATUS, CLAIM_STATUS, DISCREPANCY_STATUS
} from '@/api/finance-ext'
import type {
  PaymentCollection, PaymentCollectionSummary, SettlementDetail, SettlementIngestReport,
  FeeDiscrepancy, FeeDiscrepancyScanReport, InboundShortageRequest, ReimbursementClaim,
  ReimbursementClaimSummary, ReimbursementReconcileReport, SkuProfitReport,
  SettlementVoucherReport, ProcurementVoucherReport
} from '@/api/finance-ext'

const tab = ref<TabKey>('voucher')
// B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值
const { currentShopId, refreshShop } = useShopGuard()

// 凭证列表
const loading = ref(false)
const vouchers = ref<AccountingVoucher[]>([])
const filterSourceType = ref('')
const syncing = ref<number | null>(null)
const { showToast } = useToast()

// 服务端游标分页状态。
// 之前是「后端最多给 500 条 + 前端本地分页」：前端会显示「共 500 条」并让用户翻页，
// 但那 500 条本身就是被截断的结果 —— 用户拿到的是一份看起来完整、实际漏单的列表。
// 现在本地不再分页：只展示已加载的行，还有没有下一页由服务端 _page 说了算。
const PAGE_SIZE = 50
const nextCursor = ref<string | null>(null)
const truncated = ref(false)
const loadingMore = ref(false)

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'voucher', label: '凭证列表' },
  { key: 'profit', label: '利润查询' },
  { key: 'collection', label: '回款对账' },
  { key: 'settlement', label: '结算原表' },
  { key: 'discrepancy', label: '费用差异' },
  { key: 'claim', label: '亚马逊索赔' },
  { key: 'skuprofit', label: '单品利润' },
  { key: 'tax', label: 'VAT 与凭证补数' }
]

/**
 * 拉取凭证。
 *
 * @param append true 表示按当前 cursor 追加下一页；false 表示重新从首页加载。
 */
const loadVouchers = async (append = false) => {
  const shopId = refreshShop()
  if (!shopId) {
    vouchers.value = []
    return
  }
  if (append) {
    loadingMore.value = true
  } else {
    loading.value = true
  }
  try {
    const cursor = append ? (nextCursor.value ?? undefined) : undefined
    const res = await listVouchers(shopId, filterSourceType.value || undefined, PAGE_SIZE, cursor)
    if (res?.code === 200 && res.data) {
      const rows = Array.isArray(res.data) ? res.data : []
      vouchers.value = append ? vouchers.value.concat(rows) : rows
      // _page 缺失时按「未知」处理：宁可让用户再点一次加载更多，
      // 也不要默认当成「已经全了」——那正是漏单的来源。
      const page = res._page
      truncated.value = page ? page.truncated : false
      nextCursor.value = page ? page.nextCursor : null
    } else {
      console.warn('[Finance] 凭证列表返回异常', res)
      pushFinanceError('凭证列表', res?.message)
      if (!append) vouchers.value = []
    }
  } catch (e) {
    console.warn('[Finance] 凭证列表调用失败', e)
    pushFinanceError('凭证列表', e)
    if (!append) vouchers.value = []
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

const loadMore = () => {
  if (!truncated.value || loadingMore.value) return
  void loadVouchers(true)
}

const handleSync = async (v: AccountingVoucher) => {
  syncing.value = v.id
  try {
    const res = await syncToKingdee(v.id)
    const result = res?.data
    if (!result?.status) {
      showToast(res?.message || '金蝶同步返回异常', 'error')
      return
    }

    if (result.status === 'SYNCED') {
      showToast(result.message || '金蝶已确认凭证入账', 'success')
    } else if (result.status === 'MOCK') {
      // 模拟客户端只验证调用链，绝不把结果描述为真实入账。
      showToast(result.message || '模拟同步完成，未真实入账', 'info')
    } else if (result.status === 'SKIPPED') {
      showToast(result.message || '同步正在处理中或状态已变化，本次已跳过', 'info')
    } else {
      showToast(result.message || res?.message || '金蝶同步失败', 'error')
    }

    // 无论真实、模拟还是失败，都回读服务端状态，避免前端状态与数据库漂移。
    await loadVouchers()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '金蝶同步调用失败', 'error')
  } finally {
    syncing.value = null
  }
}

// 利润查询
const profitLoading = ref(false)
const profitStart = ref('')
const profitEnd = ref('')
const profitNum = ref<number | null>(null)
const profitError = ref('')
const profitDisplay = computed(() => {
  const n = profitNum.value
  // null = 查询失败：显示 '—'（未知）而不是 0.00（假成功）
  return typeof n === 'number' && !isNaN(n) ? n.toFixed(2) : '—'
})

const loadProfit = async () => {
  const shopId = refreshShop()
  if (!shopId) {
    profitNum.value = 0
    return
  }
  profitLoading.value = true
  try {
    const res = await calculateProfit(shopId, profitStart.value || undefined, profitEnd.value || undefined)
    if (res?.code === 200 && res.data !== null && res.data !== undefined) {
      const raw = res.data
      const num = typeof raw === 'number' ? raw : parseFloat(String(raw))
      profitNum.value = isNaN(num) ? 0 : num
      profitError.value = ''
    } else {
      // 失败显示 '—' 并报错：置 0 会把「查询失败」伪装成「真实利润为 0」
      console.warn('[Finance] 利润查询返回异常', res)
      profitNum.value = null
      profitError.value = res?.message || '后端返回非 200'
    }
  } catch (e: any) {
    console.warn('[Finance] 利润查询调用失败', e)
    profitNum.value = null
    profitError.value = e?.message || '网络异常'
  } finally {
    profitLoading.value = false
  }
}

const sourceTypeText = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: '订单收入',
    PROCUREMENT: '采购成本',
    PLATFORM_FEE: '平台费用',
    REFUND: '退款'
  }
  return map[t] || t
}

const sourceTypeClass = (t: string): string => {
  const map: Record<string, string> = {
    ORDER: 'src-order',
    PROCUREMENT: 'src-proc',
    PLATFORM_FEE: 'src-fee',
    REFUND: 'src-refund'
  }
  return map[t] || 'src-other'
}

const syncStatusText = (s: string): string => {
  const map: Record<string, string> = {
    PENDING: '待同步',
    SYNCING: '同步中',
    SYNCED: '已入账',
    MOCK: '模拟未入账',
    FAILED: '失败'
  }
  return map[s] || s || '未知'
}

const syncStatusClass = (s: string): string => {
  const map: Record<string, string> = {
    PENDING: 'sync-pending',
    SYNCED: 'sync-synced',
    SYNCING: 'sync-syncing',
    MOCK: 'sync-mock',
    FAILED: 'sync-failed'
  }
  return map[s] || 'sync-pending'
}

/* ==================== 财务运营：回款 / 结算 / 差异 / 索赔 / 单品利润 / VAT 与凭证补数 ==================== */

type TabKey = 'voucher' | 'profit' | 'collection' | 'settlement' | 'discrepancy' | 'claim' | 'skuprofit' | 'tax'

interface CursorList<T> {
  rows: Ref<T[]>
  cursor: Ref<string | null>
  truncated: Ref<boolean>
  loading: Ref<boolean>
}

const makeCursor = <T>(): CursorList<T> => ({
  rows: ref<T[]>([]) as Ref<T[]>,
  cursor: ref<string | null>(null),
  truncated: ref(false),
  loading: ref(false)
})

/**
 * 游标分页条：与凭证列表同规则——是否还有下一页由服务端 _page 说了算。
 * 走 props 回调而不是 emit，是为了让「加载更多」始终调用该页自己的 loader，
 * 避免多个列表共用一个全局游标。
 */
const CursorBar = defineComponent({
  name: 'CursorBar',
  props: {
    state: { type: Object as PropType<CursorList<unknown>>, required: true },
    load: { type: Function as PropType<(append: boolean) => void>, required: true }
  },
  setup: (props) => () => h('div', [
    h('div', { class: 'pagination' }, [
      h('span', { class: 'page-info' }, `已加载 ${props.state.rows.value.length} 条${props.state.truncated.value ? '，仍有更多未加载' : ''}`),
      h('div', { class: 'page-actions' }, props.state.truncated.value
        ? [h('button', {
            class: 'page-btn',
            disabled: props.state.loading.value,
            onClick: () => props.load(true)
          }, props.state.loading.value ? '加载中...' : '加载更多')]
        : [])
    ]),
    props.state.truncated.value
      ? h('div', { class: 'truncated-tip', role: 'status' },
          '结果已被服务端截断：当前列表不是全量。对账、导出或金额汇总请继续翻页取完。')
      : null
  ])
})

const busy = ref(false)
const errors = ref<string[]>([])
const pushFinanceError = (tag: string, detail: unknown) => {
  const msg = detail instanceof Error ? detail.message : (detail as string | undefined) || '后端返回异常'
  if (!errors.value.some((x) => x.startsWith(`${tag}：`))) errors.value.push(`${tag}：${msg}`)
}
const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const col = makeCursor<PaymentCollection>()
const colStatus = ref('')
const colSummary = ref<PaymentCollectionSummary | null>(null)

const settle = makeCursor<SettlementDetail>()
const settleOrder = ref('')
const ingest = ref<SettlementIngestReport | null>(null)

const disc = makeCursor<FeeDiscrepancy>()
const discStatus = ref('')
const scan = ref<FeeDiscrepancyScanReport | null>(null)

const claimList = makeCursor<ReimbursementClaim>()
const claimStatus = ref('')
const claimSum = ref<ReimbursementClaimSummary | null>(null)
const recon = ref<ReimbursementReconcileReport | null>(null)

const sp = ref<SkuProfitReport | null>(null)
const spAfter = ref('')
const spBefore = ref('')
const spSku = ref('')
const spLoading = ref(false)

const vatCountry = ref('US')
const vatAmount = ref<number | string>(100)
const vatResult = ref('')
const closeYear = ref(new Date().getFullYear())
const closeMonth = ref(new Date().getMonth() + 1)
const closeResult = ref<Record<string, unknown> | null>(null)
const svReport = ref<SettlementVoucherReport | null>(null)
const pvReport = ref<ProcurementVoucherReport | null>(null)

const formKind = ref<null | 'shortage' | 'reimburse'>(null)
const shortage = ref<InboundShortageRequest>({ sku: '', shipmentId: '', shortageUnits: 1, unitAmount: undefined, currency: 'USD', note: '' })
const formClaim = ref<ReimbursementClaim | null>(null)
const reimburseAmount = ref<number | string>('')

const PAGE = 50

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

/** 一次调用：失败进错误条并返回 false，绝不把失败伪装成「空列表」。 */
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

const loadCursor = async <T>(
  state: CursorList<T>,
  name: string,
  fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  append: boolean
) => {
  const shopId = refreshShop()
  if (!shopId) return
  if (!append) {
    state.rows.value = []
    state.cursor.value = null
    errors.value = []
  }
  state.loading.value = true
  try {
    const res = await fetcher(append ? state.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${name}：${res?.message || '后端返回非 200'}`)
      return
    }
    const rows = Array.isArray(res.data) ? res.data : []
    state.rows.value = append ? state.rows.value.concat(rows) : rows
    // 是否还有下一页只认服务端 _page；缺失时按「未知」处理，与凭证列表同口径
    state.cursor.value = res._page ? res._page.nextCursor : null
    state.truncated.value = res._page ? res._page.truncated : false
  } catch (e) {
    pushError(`${name}：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    state.loading.value = false
  }
}

const shop = () => refreshShop()

/** 一次性列表加载（不累积游标）：清单类端点数有限，用不着分页状态 */
const loadCursor2 = async <T>(target: { value: T[] }, name: string,
  fetcher: () => Promise<ApiResponse<T[]>>) => {
  try {
    const res = await fetcher()
    if (res?.code !== 200) { pushError(`${name}：${res?.message || '后端返回非 200'}`); return }
    target.value = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    pushError(`${name}：${e instanceof Error ? e.message : '调用失败'}`)
  }
}

/* ---------- 回款 ---------- */
const loadCollections = (append = false) => {
  void loadCursor(col, '回款列表', cursor => ext.listCollections(shop(), { status: colStatus.value || undefined, size: PAGE, cursor }), append)
}
const loadCollectionSummary = () =>
  call('回款汇总', () => ext.collectionSummary(shop()), (d) => { colSummary.value = d })
const rebuildConfirm = {
  title: '重算回款',
  detail: '从结算原表重新聚合每个订单的应回 / 扣费 / 退款 / 实收。结算原表若还没同步，重算只会得到空结果。',
  run: async () => {
    const ok = await call('重算回款', () => ext.rebuildCollections(shop()), (n) => showToast(`重算完成，影响 ${n} 个订单`, 'success'))
    if (ok) {
      await Promise.all([loadCollections(), loadCollectionSummary()])
    }
  }
}

/* ---------- 结算 ---------- */
const loadSettlements = (append = false) => {
  void loadCursor(settle, '结算明细', cursor =>
    ext.listSettlements(shop(), { amazonOrderId: settleOrder.value || undefined, size: PAGE, cursor }), append)
}
const settlementSyncConfirm = {
  title: '从 SP-API 同步结算报表',
  detail: '会向亚马逊发起真实报表请求（创建报告 + 拉取 + 入库），数据量大时耗时较长；重复行按业务指纹跳过，不会重复入账。',
  run: async () => {
    const ok = await call('结算同步', () => ext.syncSettlement(shop()), (d) => { ingest.value = d })
    if (ok) await loadSettlements()
  }
}

/* ---------- 入库短收待登记（采购域 → 财务差异） ---------- */
const shortages = ref<ReceiptShortage[]>([])
const shortagesLoading = ref(false)

const loadShortages = async () => {
  shortagesLoading.value = true
  try {
    await loadCursor2(shortages, '入库短收清单', () => listReceiptShortages(shop(), { size: 50 }))
  } finally {
    shortagesLoading.value = false
  }
}

/** 用签收事实预填登记表单：件数与 SKU 是事实，金额与币种留给人确认。 */
const prefillShortage = (s: ReceiptShortage) => {
  shortage.value = {
    sku: s.sku || '',
    shipmentId: s.fbaShipmentId || s.shipmentNo || '',
    shortageUnits: s.shortUnits ?? 1,
    unitAmount: s.unitCost === null || s.unitCost === undefined ? undefined : Number(s.unitCost),
    currency: '',
    note: `系统短收事实：应发 ${s.expectedQty} / 实收 ${s.receivedQty}（明细 #${s.itemId}）`
  }
  formKind.value = 'shortage'
}

/* ---------- 费用差异 ---------- */
const loadDiscrepancies = (append = false) => {
  void loadCursor(disc, '费用差异', cursor =>
    ext.listDiscrepancies(shop(), { status: discStatus.value || undefined, size: PAGE, cursor }), append)
}
const runScan = async () => {
  const ok = await call('差异扫描', () => ext.scanDiscrepancies(shop()), (d) => { scan.value = d })
  if (ok) await loadDiscrepancies()
}
const openShortage = () => {
  shortage.value = { sku: '', shipmentId: '', shortageUnits: 1, unitAmount: undefined, currency: 'USD', note: '' }
  formKind.value = 'shortage'
}
const dismissConfirm = (d: FeeDiscrepancy) => ({
  title: '忽略这条费用差异',
  detail: `标记为 DISMISSED 后不再进入索赔流程（${d.sku || '-'} / ${d.discrepancyType}，差额 ${d.difference}）。若之后发现判错，需要人工重新登记。`,
  run: async () => {
    const ok = await call('忽略差异', () => ext.dismissDiscrepancy(d.id as number, shop()), () => undefined)
    if (ok) await loadDiscrepancies()
  }
})
const claimFrom = (d: FeeDiscrepancy) => ({
  title: '由差异生成索赔单',
  detail: `按 ${d.sku || '-'} 的 ${d.discrepancyType} 差额 ${d.difference} ${d.currency || ''} 建一张 CANDIDATE 索赔单，后续仍需「提交平台」。`,
  run: async () => {
    const ok = await call('生成索赔单', () => ext.createClaimFromDiscrepancy(shop(), d.id as number), () => undefined)
    if (ok) {
      await Promise.all([loadDiscrepancies(), loadClaims()])
    }
  }
})

/* ---------- 索赔 ---------- */
const loadClaims = (append = false) => {
  void loadCursor(claimList, '索赔单', cursor =>
    ext.listClaims(shop(), { status: claimStatus.value || undefined, size: PAGE, cursor }), append)
}
const loadClaimSummary = () => call('索赔汇总', () => ext.claimSummary(shop()), (d) => { claimSum.value = d })
const runReconcile = async () => {
  const ok = await call('索赔对账', () => ext.reconcileClaims(shop()), (d) => { recon.value = d })
  if (ok) showToast(`对账完成：平台侧 ${d0(recon.value?.platformAdjustmentCount)} 笔 / 系统侧 ${d0(recon.value?.matched)} 笔匹配`, 'info')
}
const d0 = (v: unknown) => Number(v ?? 0)
const runClaim = async (c: ReimbursementClaim, action: 'submit' | 'accept') => {
  const label = action === 'submit' ? '提交平台' : '登记受理'
  const fn = action === 'submit' ? () => ext.submitClaim(c.id as number, shop()) : () => ext.acceptClaim(c.id as number, shop())
  const ok = await call(label, fn, (res) => { replaceClaim(res) })
  if (ok) {
    await Promise.all([loadClaims(), loadClaimSummary()])
  }
}
const replaceClaim = (c: ReimbursementClaim) => {
  claimList.rows.value = claimList.rows.value.map(x => (x.id === c.id ? { ...x, ...c } : x))
}
const openReimburse = (c: ReimbursementClaim) => {
  formClaim.value = c
  reimburseAmount.value = c.claimAmount ?? ''
  formKind.value = 'reimburse'
}
const rejectConfirm = (c: ReimbursementClaim) => ({
  title: '驳回索赔单',
  detail: `${c.claimNo || c.id} 将被标记 REJECTED，并生成红字凭证冲回此前确认的赔付收入（后端 reimburse 链路决定）。`,
  run: async () => {
    const ok = await call('驳回索赔', () => ext.rejectClaim(c.id as number, shop()), () => undefined)
    if (ok) {
      await Promise.all([loadClaims(), loadClaimSummary()])
    }
  }
})

/* ---------- 单品利润 ---------- */
const runSkuProfit = async () => {
  spLoading.value = true
  try {
    await call('单品利润', () => ext.skuProfit(shop(), {
      depositAfter: spAfter.value || undefined,
      depositBefore: spBefore.value || undefined,
      sku: spSku.value || undefined
    }), (d) => { sp.value = d })
  } finally {
    spLoading.value = false
  }
}

/* ---------- VAT 与凭证补数 ---------- */
const runVat = async (mode: 'calc' | 'rate' | 'threshold') => {
  const country = vatCountry.value.trim()
  if (!country) { pushError('VAT：请先填国家码'); return }
  const amount = Number(vatAmount.value)
  if (mode !== 'rate' && !Number.isFinite(amount)) { pushError('VAT：金额必须是数字'); return }
  const label = mode === 'calc' ? 'VAT 试算' : mode === 'rate' ? 'VAT 税率' : 'VAT 门槛检查'
  const fn = mode === 'calc' ? () => ext.calculateVat(amount, country)
    : mode === 'rate' ? () => ext.vatRate(country)
      : () => ext.vatThreshold(country, amount)
  await call(label, fn, (text) => { vatResult.value = String(text ?? '') })
}

const monthlyCloseConfirm = {
  title: '执行 VAT 月结',
  detail: '为该店铺该月生成 VAT 计提凭证（后端幂等：同月已有凭证则跳过）。年份/月份请确认无误。',
  run: async () => {
    const y = Number(closeYear.value)
    const m = Number(closeMonth.value)
    if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) {
      pushError('VAT 月结：年份或月份非法'); return
    }
    const ok = await call('VAT 月结', () => ext.vatMonthlyClose(shop(), y, m), (d) => { closeResult.value = d as Record<string, unknown> })
    if (ok) await reloadVouchers()
  }
}

const runSettlementVouchers = async () => {
  const ok = await call('补结算凭证', () => ext.generateSettlementVouchers(shop()), (d) => { svReport.value = d })
  if (ok) {
    if (svReport.value?.capped) showToast('已命中扫描上限，仍有结算行未处理，请再跑一次', 'info')
    else showToast(`结算凭证补数：费用 ${d0(svReport.value?.feeVouchers)} 张 / 退款 ${d0(svReport.value?.refundVouchers)} 张`, 'success')
    await reloadVouchers()
  }
}

const runProcurementVouchers = async () => {
  const ok = await call('补采购凭证', () => ext.generateProcurementVouchers(shop()), (d) => { pvReport.value = d })
  if (ok) {
    if (pvReport.value?.remoteDegraded) showToast(`采购域未读到数据：${pvReport.value.remoteMessage || '原因未知'}（结果不完整）`, 'error')
    else showToast(`采购凭证补数：新增 ${d0(pvReport.value?.generated)} 张 / 幂等命中 ${d0(pvReport.value?.existing)} 张`, 'success')
    await reloadVouchers()
  }
}

/** 凭证列表由本页既有逻辑负责，补数完必须回读，避免前端显示与库内状态漂移 */
const reloadVouchers = async () => { await loadVouchers(false) }

const confirm = (box: { title: string; detail: string; run: () => Promise<void> }) => { confirmBox.value = box }
const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const submitForm = async () => {
  if (formKind.value === 'shortage') {
    const s = shortage.value
    if (!s.sku.trim() || !s.shipmentId.trim() || Number(s.shortageUnits) < 1) {
      pushError('入库短收：SKU、货件号必填，短收件数至少 1')
      return
    }
    if (!String(s.currency || '').trim()) {
      pushError('入库短收：必须显式选择赔付币种（我方成本口径没有记币种，不能默认）')
      return
    }
    const ok = await call('登记短收差异', () => ext.intakeInboundShortage(shop(), { ...s, shortageUnits: Number(s.shortageUnits) }),
      (id) => showToast(`已登记差异 #${id}`, 'success'))
    if (ok) {
      formKind.value = null
      await loadDiscrepancies()
    }
    return
  }
  const c = formClaim.value
  if (!c?.id) return
  const amount = Number(reimburseAmount.value)
  if (!Number.isFinite(amount) || amount < 0) { pushError('赔付金额必须是非负数字'); return }
  const ok = await call('登记赔付', () => ext.reimburseClaim(c.id as number, shop(), amount), (res) => { replaceClaim(res) })
  if (ok) {
    formKind.value = null
    await Promise.all([loadClaims(), loadClaimSummary()])
  }
}

const TAB_LOADERS: Record<TabKey, () => Promise<unknown> | void> = {
  voucher: () => undefined,
  profit: () => undefined,
  collection: async () => { await Promise.all([loadCollections(), loadCollectionSummary()]) },
  settlement: () => loadSettlements(),
  discrepancy: async () => { await Promise.all([loadDiscrepancies(), loadShortages()]) },
  claim: async () => { await Promise.all([loadClaims(), loadClaimSummary()]) },
  skuprofit: () => undefined,
  tax: () => undefined
}

/** 每个分区首次进入才拉数据：财务端点重，全压在挂载上会拖慢首屏 */
const loadedTabs = new Set<TabKey>(['voucher'])
const gotoTab = async (t: TabKey) => {
  tab.value = t
  if (loadedTabs.has(t)) return
  loadedTabs.add(t)
  await TAB_LOADERS[t]()
}

onMounted(() => {
  void loadVouchers(false)
})
</script>

<style scoped>
.finance-page { background: var(--color-background); }
/* ---- 财务运营分区 ---- */
.panel-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); margin-bottom: 0.5rem; }
.kv-grid { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0.75rem 0 0; font-size: 0.8125rem; }
.kv-grid dt { color: var(--color-muted); }
.kv-grid dd { margin: 0; color: var(--color-on-surface); word-break: break-all; }
.vat-line { font-size: 0.8125rem; color: var(--color-on-surface); background: var(--color-background); border-radius: var(--radius-sm); padding: 0.5rem 0.75rem; margin: 0.5rem 0 0; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.reconcile { background: var(--color-surface); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 1rem; margin-bottom: 1rem; }
.recon-head { font-weight: 600; margin-bottom: 0.5rem; color: var(--color-on-surface); }
.recon-grid { display: flex; gap: 1.5rem; flex-wrap: wrap; font-size: 0.8125rem; color: var(--color-muted); }
.recon-cols { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-top: 0.75rem; }
.recon-sub { font-size: 0.8125rem; color: var(--color-on-surface); margin-bottom: 0.25rem; font-weight: 600; }
.recon-line { font-size: 0.8125rem; color: var(--color-muted); font-family: var(--font-mono); }
.table-card { margin-bottom: 1rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; color: var(--color-on-surface); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-background); color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; margin: 0; }

@media (max-width: 768px) { .recon-cols { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } }

/* 页头/主区/表格等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 60%; height: 1.5rem; margin-top: 0.5rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.dim-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.dim-tab { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); cursor: pointer; font-size: 0.875rem; color: var(--color-muted); transition: all 0.2s; }
.dim-tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }

.filter-bar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; }
.filter-select, .filter-date { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-surface); color: var(--color-on-surface); transition: border-color 0.2s; }
.filter-select:hover, .filter-date:hover { border-color: var(--color-primary); }
.filter-btn { padding: 0.5rem 1rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.2s; }
.filter-btn:hover { background: var(--color-primary-dark); }
.range-sep { color: var(--color-muted); font-size: 0.875rem; margin: 0 0.5rem; }

.src-order { background: var(--color-primary-light); color: var(--color-primary); }
.src-proc { background: var(--color-muted-light); color: var(--color-muted); }
.src-fee { background: var(--color-muted-light); color: var(--color-muted); }
.src-refund { background: var(--color-light-red); color: var(--color-error); }
.src-other { background: var(--color-surface); color: var(--color-muted); }

/* 同步状态语义色：SYNCED=成功 / PENDING=待处理 / FAILED=失败 */
.sync-pending { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-synced { background: var(--color-success-light); color: var(--color-success); }
.sync-syncing { background: var(--color-primary-light); color: var(--color-primary); }
.sync-mock { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-failed { background: var(--color-light-red); color: var(--color-error); }

.sync-btn { padding: 0.25rem 0.75rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; }
.sync-btn:hover:not(:disabled) { background: var(--color-primary-dark); }
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.summary-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1rem; margin-bottom: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-label { font-size: 0.8125rem; color: var(--color-muted); }
.summary-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin-top: 0.25rem; font-variant-numeric: tabular-nums; }
.summary-value.small { font-size: 1rem; font-weight: 600; }

.profit-positive { color: var(--color-success); font-weight: 600; }
.profit-negative { color: var(--color-error); font-weight: 600; }
/* 零利润中性展示，避免误读为亏损 */
.profit-flat { color: var(--color-muted); font-weight: 600; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.truncated-tip { margin-top: 0.75rem; padding: 0.625rem 0.875rem; border-radius: var(--radius-md); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; line-height: 1.6; }
.profit-note { padding: 0.75rem 1rem; background: var(--color-surface); color: var(--color-muted); border-radius: var(--radius-md); font-size: 0.8125rem; line-height: 1.6; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .summary-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .filter-bar { flex-wrap: wrap; } .summary-grid { grid-template-columns: 1fr; } }
</style>