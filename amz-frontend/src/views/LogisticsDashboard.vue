<template>
  <div class="logistics-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">物流看板</h1>
        <p class="hero-subtitle">头程在途与异常一览</p>
      </div>

      <!-- 未登录：不发任何业务请求，避免 401 拦截器跳登录页造成重载循环 -->
      <div v-if="!hasToken" class="login-hint">
        <Icon icon="mdi:account-lock-outline" width="20" />
        <span>请先登录后查看物流数据</span>
      </div>

      <div v-else-if="!currentShopId" class="shop-tip">请先在顶部选择店铺</div>

      <template v-else>
        <!-- 取数状态条：让「数据为什么没动」有明确答案，而不是让人反复点刷新 -->
        <div v-if="overview" class="sync-bar" :class="overview.autoSyncAvailable ? 'on' : 'off'">
          <Icon
            :icon="overview.autoSyncAvailable ? 'mdi:sync' : 'mdi:sync-off'"
            width="18"
            class="sync-icon"
          />
          <span class="sync-text">
            <template v-if="overview.autoSyncAvailable">
              第三方轨迹自动同步已启用 · 最近取数 {{ fmtTime(overview.lastTrackTime) }}
            </template>
            <template v-else>
              第三方轨迹自动同步未启用（未配置凭证）· 请在下方「数据导入」维护轨迹
            </template>
          </span>
          <button class="sync-btn" :disabled="busy || loading" @click="refreshCurrent">
            <Icon icon="mdi:refresh" width="16" />
            刷新
          </button>
        </div>

        <!-- 骨架屏：形状与最终布局一致，避免加载完成时页面跳动 -->
        <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
          <div class="kpi-grid">
            <div v-for="i in 4" :key="i" class="kpi-card">
              <div class="skeleton sk-icon"></div>
              <div class="sk-lines">
                <div class="skeleton sk-line sk-line-lg"></div>
                <div class="skeleton sk-line sk-line-sm"></div>
              </div>
            </div>
          </div>
          <div class="chart-card"><div class="skeleton sk-chart"></div></div>
        </div>

        <template v-else>
          <!-- 标签页 -->
          <div class="tabs" role="tablist">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="tab"
              :class="{ active: tab === t.key }"
              role="tab"
              :aria-selected="tab === t.key"
              @click="switchTab(t.key)"
            >
              {{ t.label }}
              <span v-if="t.badge" class="tab-badge" :class="t.badgeTone">{{ t.badge }}</span>
            </button>
          </div>

          <!-- ================= 概览 ================= -->
          <section v-if="tab === 'overview'">
            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:ship-wheel" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.activeShipments ?? 0 }}</div>
                  <div class="kpi-label">在跟踪货件</div>
                  <div class="kpi-sub">共 {{ overview?.totalShipments ?? 0 }} 个货件</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:clock-alert-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.delayed ?? 0 }}</div>
                  <div class="kpi-label">已判定延误</div>
                  <div class="kpi-sub">按 ETA 自动判定</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:alert-octagon-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.exception ?? 0 }}</div>
                  <div class="kpi-label">异常货件</div>
                  <div class="kpi-sub">扣关 / 查验 / 退件</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:calendar-clock" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ overview?.arrivingIn7Days ?? 0 }}</div>
                  <div class="kpi-label">7 天内到港</div>
                  <div class="kpi-sub">需提前准备入库</div>
                </div>
              </div>
            </div>

            <!-- 次级指标：都是「不作为就会被忽略」的那几类 -->
            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">
                  {{ overview?.avgTransitDays === null ? '暂无样本' : overview?.avgTransitDays + ' 天' }}
                </div>
                <div class="mini-label">平均头程时效</div>
                <div class="mini-hint">近 {{ overview?.transitStatWindowDays ?? 90 }} 天内送达样本</div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.etaOverdue ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.etaOverdue ?? 0 }}</div>
                <div class="mini-label">ETA 已过未标延误</div>
                <div class="mini-hint">延误重判任务可能尚未执行</div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.staleShipments ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.staleShipments ?? 0 }}</div>
                <div class="mini-label">取数过期</div>
                <div class="mini-hint">
                  超过 {{ overview?.staleThresholdHours ?? 48 }} 小时未取数
                </div>
              </div>
              <div class="mini-card" :class="{ alert: (overview?.missingTrackingNo ?? 0) > 0 }">
                <div class="mini-value">{{ overview?.missingTrackingNo ?? 0 }}</div>
                <div class="mini-label">缺运单号</div>
                <div class="mini-hint">补录后才能自动跟踪</div>
              </div>
            </div>

            <div class="chart-row">
              <div class="chart-card">
                <div class="card-head">
                  <h3>近 {{ trendDays }} 天建单 / 送达</h3>
                  <div class="legend">
                    <span class="lg"><i class="dot dot-created"></i>建单 {{ trendTotal.created }}</span>
                    <span class="lg"><i class="dot dot-delivered"></i>送达 {{ trendTotal.delivered }}</span>
                  </div>
                </div>
                <!-- 折线图用手写 SVG：项目未引入图表库，两条线的需求不值得为此加依赖 -->
                <svg
                  v-if="chart"
                  class="chart"
                  :viewBox="`0 0 ${chart.W} ${chart.H}`"
                  role="img"
                  aria-label="建单量与送达量趋势"
                >
                  <line
                    v-for="g in chart.grid"
                    :key="'g' + g.y"
                    :x1="chart.PAD"
                    :x2="chart.W - chart.PAD"
                    :y1="g.y"
                    :y2="g.y"
                    class="grid-line"
                  />
                  <text
                    v-for="g in chart.grid"
                    :key="'t' + g.y"
                    :x="chart.PAD - 6"
                    :y="g.y + 3"
                    class="axis-text"
                    text-anchor="end"
                  >
                    {{ g.value }}
                  </text>
                  <polyline :points="chart.createdPath" class="line line-created" />
                  <polyline :points="chart.deliveredPath" class="line line-delivered" />
                  <text
                    v-for="l in chart.xLabels"
                    :key="l.date"
                    :x="l.x"
                    :y="chart.H - 6"
                    class="axis-text"
                    text-anchor="middle"
                  >
                    {{ l.date.slice(5) }}
                  </text>
                </svg>
                <div v-else class="empty-row">该时间窗内暂无数据</div>
              </div>

              <div class="chart-card">
                <h3>状态分布</h3>
                <div class="dist-list">
                  <div v-for="s in statusDist" :key="s.key" class="dist-item">
                    <span class="status-tag" :class="s.tone">{{ s.label }}</span>
                    <div class="dist-bar">
                      <div class="dist-fill" :style="{ width: s.percent + '%' }"></div>
                    </div>
                    <span class="dist-value">{{ s.count }}</span>
                  </div>
                </div>
                <h3 class="mt">取数来源</h3>
                <div class="source-list">
                  <span v-for="d in sourceDist" :key="d.key" class="source-chip">
                    {{ d.label }} <b>{{ d.count }}</b>
                  </span>
                </div>
              </div>
            </div>
          </section>

          <!-- ================= 待处理 ================= -->
          <section v-else-if="tab === 'alerts'">
            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>级别</th>
                    <th>类型</th>
                    <th>货件号</th>
                    <th>承运商</th>
                    <th>运单号</th>
                    <th>状态</th>
                    <th>ETA</th>
                    <th>超期</th>
                    <th>说明与建议</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!alerts.length">
                    <td colspan="10" class="empty-row">当前没有需要人工处理的货件</td>
                  </tr>
                  <tr v-for="a in alerts" :key="a.type + '-' + a.shipmentId">
                    <td>
                      <span class="status-tag" :class="severityTone(a.severity)">
                        {{ severityLabel(a.severity) }}
                      </span>
                    </td>
                    <td>{{ alertTypeLabel(a.type) }}</td>
                    <td class="mono">{{ a.shipmentNo }}</td>
                    <td>{{ a.carrier || '—' }}</td>
                    <td class="mono">{{ a.masterTrackingNo || '—' }}</td>
                    <td>{{ statusLabel(a.status) }}</td>
                    <td class="mono">{{ a.eta || '—' }}</td>
                    <td>{{ a.daysOverdue !== null && a.daysOverdue > 0 ? a.daysOverdue + ' 天' : '—' }}</td>
                    <td class="hint-cell">
                      <div class="hint-msg">{{ a.message }}</div>
                      <div class="hint-action">建议：{{ a.actionHint }}</div>
                    </td>
                    <td class="ops">
                      <button class="link-btn" :disabled="busy" @click="doSync(a.shipmentId)">同步</button>
                      <button class="link-btn" :disabled="busy" @click="openTracking(a.shipmentId)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="doClose(a.shipmentId)">关单</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <!-- ================= 在途货件 ================= -->
          <section v-else-if="tab === 'shipments'">
            <div class="filter-bar">
              <label for="lg-status">状态</label>
              <select id="lg-status" v-model="shipmentStatus" @change="loadShipments">
                <option value="">全部</option>
                <option v-for="s in statusOptions" :key="s" :value="s">
                  {{ statusLabel(s) }}
                </option>
              </select>
              <span class="filter-count">共 {{ shipments.length }} 条</span>
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
                      <button class="link-btn" :disabled="busy" @click="doSync(s.id!)">同步</button>
                      <button class="link-btn" @click="openTracking(s.id!)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="doClose(s.id!)">关单</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <!-- ================= 承运商 ================= -->
          <section v-else-if="tab === 'carrier'">
            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>承运商</th>
                    <th>总货件</th>
                    <th>在跟踪</th>
                    <th>已送达</th>
                    <th>延误</th>
                    <th>异常</th>
                    <th>平均时效</th>
                    <th>延误率</th>
                    <th>异常率</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!carriers.length">
                    <td colspan="9" class="empty-row">暂无数据</td>
                  </tr>
                  <tr v-for="c in carriers" :key="c.carrier">
                    <td>{{ c.carrier }}</td>
                    <td>{{ c.total }}</td>
                    <td>{{ c.active }}</td>
                    <td>{{ c.delivered }}</td>
                    <td :class="{ 'cell-alert': c.delayed > 0 }">{{ c.delayed }}</td>
                    <td :class="{ 'cell-alert': c.exception > 0 }">{{ c.exception }}</td>
                    <td>{{ c.avgTransitDays === null ? '暂无样本' : c.avgTransitDays + ' 天' }}</td>
                    <td>{{ fmtRate(c.delayedRate) }}</td>
                    <td>{{ fmtRate(c.exceptionRate) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="foot-note">
              平均时效以「承运商确认送达」的轨迹事件时间为终点计算，样本不足时显示「暂无样本」而非 0，
              避免把小样本误读为高效。
            </p>
          </section>

          <!-- ================= 数据导入 ================= -->
          <section v-else-if="tab === 'import'">
            <div class="import-grid">
              <div class="chart-card">
                <h3>① 导入货件主单</h3>
                <p class="card-desc">
                  轨迹要挂在货件上，因此<strong>必须先有货件</strong>。按货件编号 upsert，
                  同一份文件可安全重复导入（已存在则只补齐本次给出的字段）。
                </p>
                <textarea
                  v-model="shipmentJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="shipmentPlaceholder"
                  aria-label="货件主单 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !shipmentJson.trim()" @click="doImportShipments">
                    导入货件主单
                  </button>
                  <button class="link-btn" @click="shipmentJson = shipmentExample">填入示例</button>
                </div>
              </div>

              <div class="chart-card">
                <h3>② 导入运单轨迹</h3>
                <p class="card-desc">
                  可提交<strong>全量历史轨迹</strong>：已存在的轨迹点会计入「跳过」而非重复插入，
                  因此不必自行维护增量。状态填承运商原文即可，服务端统一映射。
                </p>
                <textarea
                  v-model="trackingJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="trackingPlaceholder"
                  aria-label="运单轨迹 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !trackingJson.trim()" @click="doImportTracking">
                    导入运单轨迹
                  </button>
                  <button class="link-btn" @click="trackingJson = trackingExample">填入示例</button>
                </div>
              </div>
            </div>

            <div v-if="importReport" class="chart-card report-card">
              <div class="card-head">
                <h3>最近一次导入结果</h3>
                <button class="link-btn" @click="importReport = null">清除</button>
              </div>
              <div class="report-grid">
                <div class="report-item"><b>{{ importReport.totalRows }}</b><span>总行数</span></div>
                <div class="report-item"><b>{{ importReport.succeededRows }}</b><span>成功</span></div>
                <div class="report-item" :class="{ 'cell-alert': importReport.failedRows > 0 }">
                  <b>{{ importReport.failedRows }}</b><span>失败</span>
                </div>
                <div class="report-item"><b>{{ importReport.createdShipments }}</b><span>新建货件</span></div>
                <div class="report-item"><b>{{ importReport.updatedShipments }}</b><span>补齐货件</span></div>
                <div class="report-item"><b>{{ importReport.acceptedEvents }}</b><span>写入轨迹点</span></div>
                <div class="report-item"><b>{{ importReport.skippedEvents }}</b><span>跳过（已存在）</span></div>
                <div class="report-item"><b>{{ importReport.statusChangedShipments }}</b><span>状态变更</span></div>
              </div>

              <div v-if="importReport.unmatched.length" class="report-block">
                <div class="report-title">以下运单在系统内没有对应货件，请先导入货件主单：</div>
                <div class="chip-wrap">
                  <span v-for="u in importReport.unmatched" :key="u" class="chip">{{ u }}</span>
                </div>
              </div>
              <div v-if="importReport.errors.length" class="report-block">
                <div class="report-title">行级错误：</div>
                <ul class="err-list">
                  <li v-for="(e, i) in importReport.errors" :key="i">{{ e }}</li>
                </ul>
                <div v-if="importReport.errorsTruncated" class="report-title">
                  错误过多，仅展示前若干条
                </div>
              </div>
              <p
                v-if="!importReport.unmatched.length && !importReport.errors.length && importReport.failedRows === 0"
                class="report-ok"
              >
                全部处理成功。若「写入轨迹点」为 0 而「跳过」大于 0，说明这批数据此前已导入过（幂等生效）。
              </p>
            </div>
          </section>

          <!-- ================= 报价与比价 ================= -->
          <section v-else-if="tab === 'quotes'">
            <div v-if="quoteBoard?.warnings?.length" class="warn-banner">
              <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
              <div class="wb-body">
                <div v-for="(w, i) in quoteBoard.warnings" :key="i">{{ w }}</div>
              </div>
            </div>

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:file-document-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ quoteBoard?.validQuotes ?? 0 }}</div>
                  <div class="kpi-label">有效报价</div>
                  <div class="kpi-sub">共登记 {{ quoteBoard?.totalQuotes ?? 0 }} 条</div>
                </div>
              </div>
              <div
                class="kpi-card"
                :class="{ 'kpi-alert': (quoteBoard?.staleByDate ?? 0) > 0 }"
              >
                <div class="kpi-icon tone-warn"><Icon icon="mdi:calendar-remove-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ quoteBoard?.staleByDate ?? 0 }}</div>
                  <div class="kpi-label">已失效未收口</div>
                  <div class="kpi-sub">比价时已自动排除</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:timer-sand" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ quoteBoard?.expiringIn30Days ?? 0 }}</div>
                  <div class="kpi-label">30 天内失效</div>
                  <div class="kpi-sub">需提前重新询价</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (quoteBoard?.unpricedQuotes ?? 0) > 0 }">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:currency-usd-off" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ quoteBoard?.unpricedQuotes ?? 0 }}</div>
                  <div class="kpi-label">未填单价</div>
                  <div class="kpi-sub">无法参与比价</div>
                </div>
              </div>
            </div>

            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">{{ quoteBoard?.carrierCount ?? 0 }}</div>
                <div class="mini-label">覆盖承运商</div>
                <div class="mini-hint">有有效报价的承运商数</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ quoteBoard?.routeCount ?? 0 }}</div>
                <div class="mini-label">覆盖航线</div>
                <div class="mini-hint">起运港 → 目的港</div>
              </div>
              <div
                class="mini-card"
                :class="{ alert: (singleSourceRoutes ?? 0) > 0 }"
              >
                <div class="mini-value">{{ singleSourceRoutes ?? 0 }}</div>
                <div class="mini-label">单一来源航线</div>
                <div class="mini-hint">只有一家承运商，无议价空间</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ currencyCount }}</div>
                <div class="mini-label">涉及币种</div>
                <div class="mini-hint">多币种报价不可直接比价</div>
              </div>
            </div>

            <div class="chart-card">
              <div class="card-head">
                <h3>航线报价覆盖</h3>
                <button
                  class="link-btn"
                  :disabled="busy || !(quoteBoard?.staleByDate ?? 0)"
                  @click="doExpireQuotes"
                >
                  标记过期报价
                </button>
              </div>
              <p class="card-desc">
                按承运商数量升序排列——<strong>排最前面的航线最需要补供应商</strong>。
                报价生效靠人工或定时任务置位，因此存在一段「已失效但仍是 ACTIVE」的窗口，比价时会自动排除。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>航线</th>
                    <th>承运商</th>
                    <th>时效</th>
                    <th>每公斤价</th>
                    <th>每立方价</th>
                    <th>币种</th>
                    <th>价差</th>
                    <th>提示</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!quoteBoard?.routes?.length">
                    <td colspan="8" class="empty-row">暂无有效报价</td>
                  </tr>
                  <tr v-for="r in quoteBoard?.routes || []" :key="r.originPort + '→' + r.destinationPort">
                    <td>{{ r.originPort }} → {{ r.destinationPort }}</td>
                    <td>
                      <div>{{ r.carrierCount }} 家</div>
                      <div class="sub-line">{{ r.carriers.join('、') }}</div>
                    </td>
                    <td>{{ transitRange(r) }}</td>
                    <td class="mono">{{ priceRange(r.minPricePerKg, r.maxPricePerKg) }}</td>
                    <td class="mono">{{ priceRange(r.minPricePerCbm, r.maxPricePerCbm) }}</td>
                    <td>{{ r.currency }}</td>
                    <td>{{ fmtRate(r.priceSpreadRate) }}</td>
                    <td>
                      <span v-if="r.singleSource" class="status-tag tone-warn">单一来源</span>
                      <span v-else class="status-tag tone-muted">正常</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="chart-card mt">
              <h3>运费比价</h3>
              <p class="card-desc">
                填写货量即可比较该航线上所有有效报价。
                计费口径为<strong>重量价与体积价取高</strong>，再叠加最低收费与燃油附加费——
                这也是承运商的实际计费规则，只按重量算会低估运费。
                不同币种的金额不可直接比较，因此按币种分别给出推荐。
              </p>
              <div class="cmp-form">
                <label>
                  起运港
                  <input v-model="cmpForm.originPort" placeholder="如 Shenzhen" />
                </label>
                <label>
                  目的港
                  <input v-model="cmpForm.destinationPort" placeholder="如 Los Angeles" />
                </label>
                <label>
                  重量 (kg)
                  <input v-model.number="cmpForm.weightKg" type="number" min="0" placeholder="可留空" />
                </label>
                <label>
                  体积 (m³)
                  <input v-model.number="cmpForm.volumeCbm" type="number" min="0" step="0.01" placeholder="可留空" />
                </label>
                <button
                  class="primary-btn"
                  :disabled="cmpLoading || !cmpForm.originPort || !cmpForm.destinationPort
                    || (!cmpForm.weightKg && !cmpForm.volumeCbm)"
                  @click="doCompare"
                >
                  计算比价
                </button>
              </div>

              <div v-if="cmpResult" class="cmp-result">
                <div v-if="cmpResult.warnings.length" class="report-block">
                  <ul class="err-list note">
                    <li v-for="(w, i) in cmpResult.warnings" :key="i">{{ w }}</li>
                  </ul>
                </div>
                <div v-if="cmpResult.recommended" class="cmp-best">
                  推荐承运商：<b>{{ cmpResult.recommended }}</b>
                </div>
                <div v-else class="cmp-best multi">
                  本航线存在多种币种，无法给出唯一的「最便宜」结论，请按下方币种分组分别判断。
                </div>
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>承运商</th>
                      <th>方式</th>
                      <th>时效</th>
                      <th>计费口径</th>
                      <th>运费</th>
                      <th>燃油附加</th>
                      <th>合计</th>
                      <th>币种</th>
                      <th>失效日期</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!cmpResult.quotes.length">
                      <td colspan="9" class="empty-row">该航线没有有效报价</td>
                    </tr>
                    <tr v-for="q in cmpResult.quotes" :key="q.quoteId">
                      <td>
                        {{ q.carrierName }}
                        <span
                          v-if="cmpResult.recommendedByCurrency[q.currency] === q.carrierName"
                          class="status-tag tone-success"
                        >该币种最低</span>
                      </td>
                      <td>{{ methodLabel(q.serviceType) }}</td>
                      <td>{{ q.transitDays != null ? q.transitDays + ' 天' : '—' }}</td>
                      <td>
                        {{ basisLabel(q.chargeableBasis) }}
                        <span v-if="q.minChargeApplied" class="status-tag tone-muted">已套最低收费</span>
                      </td>
                      <td class="mono">{{ fmtAmount(q.freightCost) }}</td>
                      <td class="mono">{{ fmtAmount(q.fuelSurcharge) }}</td>
                      <td class="mono"><b>{{ fmtAmount(q.totalCost) }}</b></td>
                      <td>{{ q.currency }}</td>
                      <td class="mono">{{ q.expiryDate || '不限' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <!-- ================= 调拨 ================= -->
          <section v-else-if="tab === 'transfers'">
            <div v-if="transferBoard?.warnings?.length" class="warn-banner">
              <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
              <div class="wb-body">
                <div v-for="(w, i) in transferBoard.warnings" :key="i">{{ w }}</div>
              </div>
            </div>

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:swap-horizontal" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ transferBoard?.total ?? 0 }}</div>
                  <div class="kpi-label">调拨单总数</div>
                  <div class="kpi-sub">仓间库存移动</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (transferBoard?.pendingApproval ?? 0) > 0 }">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:account-clock-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ transferBoard?.pendingApproval ?? 0 }}</div>
                  <div class="kpi-label">待审批</div>
                  <div class="kpi-sub">草稿 + 待审批</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (transferBoard?.approvedNotShipped ?? 0) > 0 }">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:package-variant-closed" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ transferBoard?.approvedNotShipped ?? 0 }}</div>
                  <div class="kpi-label">已批待发出</div>
                  <div class="kpi-sub">货还在源仓</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (transferBoard?.staleInTransit ?? 0) > 0 }">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:truck-alert-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ transferBoard?.staleInTransit ?? 0 }}</div>
                  <div class="kpi-label">在途卡单</div>
                  <div class="kpi-sub">
                    超过 {{ transferBoard?.staleThresholdDays ?? 7 }} 天未收货
                  </div>
                </div>
              </div>
            </div>

            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">{{ transferBoard?.inTransit ?? 0 }}</div>
                <div class="mini-label">运输中</div>
                <div class="mini-hint">正常在途的单据</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ transferBoard?.received ?? 0 }}</div>
                <div class="mini-label">已收货</div>
                <div class="mini-hint">已完成的调拨</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ transferBoard?.cancelled ?? 0 }}</div>
                <div class="mini-label">已取消</div>
                <div class="mini-hint">驳回或作废</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ fmtAmount(transferBoard?.totalShippingCost) }}</div>
                <div class="mini-label">调拨运费合计</div>
                <div class="mini-hint">全部调拨单累计</div>
              </div>
            </div>

            <div class="chart-card">
              <h3>待跟进调拨单</h3>
              <p class="card-desc">
                状态字段本身只说明「在途」，看不出已经卡了多久——因此这里按在途天数挑出卡单，
                并区分「审批卡住」与「货卡在路上」两类，处理动作完全不同。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>级别</th>
                    <th>类型</th>
                    <th>调拨单号</th>
                    <th>货</th>
                    <th>数量</th>
                    <th>状态</th>
                    <th>已停留</th>
                    <th>承运商 / 单号</th>
                    <th>说明与建议</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!transferBoard?.risks?.length">
                    <td colspan="9" class="empty-row">当前没有需要跟进的调拨单</td>
                  </tr>
                  <tr v-for="r in transferBoard?.risks || []" :key="r.type + '-' + r.id">
                    <td>
                      <span class="status-tag" :class="severityTone(r.severity)">
                        {{ severityLabel(r.severity) }}
                      </span>
                    </td>
                    <td>{{ transferRiskLabel(r.type) }}</td>
                    <td class="mono">{{ r.transferNo }}</td>
                    <td>
                      <div>{{ r.asin }}</div>
                      <div class="sub-line">{{ r.sku }}</div>
                    </td>
                    <td>{{ r.quantity }}</td>
                    <td>{{ transferStatusLabel(r.status) }}</td>
                    <td>{{ r.daysSinceCreated != null ? r.daysSinceCreated + ' 天' : '—' }}</td>
                    <td class="mono">
                      {{ r.carrier || '—' }}
                      <div class="sub-line">{{ r.trackingNo || '未登记' }}</div>
                    </td>
                    <td class="hint-cell">
                      <div class="hint-msg">{{ r.message }}</div>
                      <div class="hint-action">建议：{{ r.actionHint }}</div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="chart-card mt">
              <div class="card-head">
                <h3>调拨单</h3>
                <div class="filter-inline">
                  <label for="trf-status">状态</label>
                  <select id="trf-status" v-model="transferStatusFilter" @change="loadTransfers">
                    <option value="">全部</option>
                    <option v-for="(label, key) in TRANSFER_STATUS_LABELS" :key="key" :value="key">
                      {{ label }}
                    </option>
                  </select>
                </div>
              </div>
              <p class="card-desc">
                状态只能沿既定路径前进：草稿 → 待审批 → 已审批 → 运输中 → 已收货。
                因此「发出」只对已审批的单据可用，「收货」只对运输中的单据可用——
                跳过前置状态会被服务端拒绝，避免出现「没发货就收货」导致库存账凭空多出来。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>调拨单号</th>
                    <th>货</th>
                    <th>数量</th>
                    <th>源 → 目标仓</th>
                    <th>状态</th>
                    <th>承运商 / 单号</th>
                    <th>运费</th>
                    <th>建单时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!transfers.length">
                    <td colspan="9" class="empty-row">暂无调拨单</td>
                  </tr>
                  <tr v-for="t in transfers" :key="t.id">
                    <td class="mono">{{ t.transferNo }}</td>
                    <td>
                      <div>{{ t.asin }}</div>
                      <div class="sub-line">{{ t.sku }}</div>
                    </td>
                    <td>{{ t.quantity }}</td>
                    <td>{{ t.fromWarehouseId }} → {{ t.toWarehouseId }}</td>
                    <td>
                      <span class="status-tag" :class="transferStatusTone(t.status)">
                        {{ transferStatusLabel(t.status) }}
                      </span>
                    </td>
                    <td class="mono">
                      {{ t.carrier || '—' }}
                      <div class="sub-line">{{ t.trackingNo || '未登记' }}</div>
                    </td>
                    <td class="mono">{{ fmtAmount(t.shippingCost) }}</td>
                    <td class="mono">{{ fmtTime(t.createTime) }}</td>
                    <td class="ops">
                      <template v-if="canApprove(t.status)">
                        <button class="link-btn" :disabled="busy" @click="doApproveTransfer(t, true)">通过</button>
                        <button class="link-btn" :disabled="busy" @click="doApproveTransfer(t, false)">驳回</button>
                      </template>
                      <button
                        v-else-if="t.status === 'APPROVED'"
                        class="link-btn"
                        :disabled="busy"
                        @click="doShipTransfer(t)"
                      >
                        确认发出
                      </button>
                      <button
                        v-else-if="t.status === 'IN_TRANSIT'"
                        class="link-btn"
                        :disabled="busy"
                        @click="doShipTransfer(t)"
                      >
                        更正单号
                      </button>
                      <button
                        v-if="t.status === 'IN_TRANSIT'"
                        class="link-btn"
                        :disabled="busy"
                        @click="doReceiveTransfer(t)"
                      >
                        确认收货
                      </button>
                      <span v-if="isTransferDone(t.status)" class="sub-line">已结束</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <!-- ================= 头程成本 ================= -->
          <section v-else-if="tab === 'freight'">
            <div v-if="freightBoard?.warnings?.length" class="warn-banner">
              <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
              <div class="wb-body">
                <div v-for="(w, i) in freightBoard.warnings" :key="i">{{ w }}</div>
              </div>
            </div>

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:cash-multiple" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ fmtAmount(freightBoard?.totalCost) }}</div>
                  <div class="kpi-label">头程成本合计</div>
                  <div class="kpi-sub">{{ freightBoard?.totalAllocationRows ?? 0 }} 条分摊明细</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:calculator-variant-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">
                    {{ freightBoard?.avgUnitCost == null ? '暂无样本' : fmtAmount(freightBoard.avgUnitCost) }}
                  </div>
                  <div class="kpi-label">加权单件头程成本</div>
                  <div class="kpi-sub">总成本 / {{ freightBoard?.totalQuantity ?? 0 }} 件</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:check-decagram-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ freightBoard?.coveredShipments ?? 0 }}</div>
                  <div class="kpi-label">已核算货件</div>
                  <div class="kpi-sub">覆盖率 {{ fmtRate(freightBoard?.coverageRate) }}</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (freightBoard?.uncoveredShipments ?? 0) > 0 }">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:alert-circle-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ freightBoard?.uncoveredShipments ?? 0 }}</div>
                  <div class="kpi-label">未核算货件</div>
                  <div class="kpi-sub">成本尚未进入利润计算</div>
                </div>
              </div>
            </div>

            <div class="mini-grid">
              <div class="mini-card">
                <div class="mini-value">{{ fmtAmount(freightBoard?.totalFreight) }}</div>
                <div class="mini-label">运费分摊</div>
                <div class="mini-hint">占比 {{ costShare('freight') }}</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ fmtAmount(freightBoard?.totalDuty) }}</div>
                <div class="mini-label">关税分摊</div>
                <div class="mini-hint">占比 {{ costShare('duty') }}</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ fmtAmount(freightBoard?.totalInsurance) }}</div>
                <div class="mini-label">保险分摊</div>
                <div class="mini-hint">占比 {{ costShare('insurance') }}</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ fmtAmount(freightBoard?.totalOther) }}</div>
                <div class="mini-label">其他费用</div>
                <div class="mini-hint">占比 {{ costShare('other') }}</div>
              </div>
            </div>

            <div class="chart-card">
              <h3>未核算头程成本的货件</h3>
              <p class="card-desc">
                没有分摊明细的货件，运费不会摊到商品上，表现为<strong>利润被高估</strong>。
                其中「主单已登记运费却没做分摊」的一类最要紧：钱已经花了，只是没进成本。
                未核算的货件需要先在货件下登记 ASIN / SKU / 重量或体积，再执行费用分摊。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>货件号</th>
                    <th>承运商</th>
                    <th>状态</th>
                    <th>ETA</th>
                    <th>主单已登记运费</th>
                    <th>提示</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!freightBoard?.uncoveredList?.length">
                    <td colspan="6" class="empty-row">所有货件都已做头程分摊</td>
                  </tr>
                  <tr v-for="u in freightBoard?.uncoveredList || []" :key="u.shipmentId">
                    <td class="mono">{{ u.shipmentNo || u.shipmentId }}</td>
                    <td>{{ u.carrier || '—' }}</td>
                    <td>{{ statusLabel(u.status) }}</td>
                    <td class="mono">{{ u.eta || '—' }}</td>
                    <td class="mono" :class="{ 'cell-alert': u.hasDeclaredFreight }">
                      {{ u.freightCost == null ? '未登记' : fmtAmount(u.freightCost) }}
                    </td>
                    <td>
                      <span v-if="u.hasDeclaredFreight" class="status-tag tone-danger">
                        有费用未摊，直接抬高利润
                      </span>
                      <span v-else class="status-tag tone-muted">无费用信息</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="chart-card mt">
              <h3>单件头程成本 Top</h3>
              <p class="card-desc">
                按单件成本降序。若同一条航线上某个 SKU 的单件成本明显高于其他，
                通常意味着装箱方案不经济（体积重占比高）或分摊口径与其他货件不同。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>货件号</th>
                    <th>ASIN</th>
                    <th>SKU</th>
                    <th>数量</th>
                    <th>分摊总成本</th>
                    <th>单件成本</th>
                    <th>分摊方法</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!freightBoard?.topUnitCostItems?.length">
                    <td colspan="7" class="empty-row">暂无分摊明细</td>
                  </tr>
                  <tr v-for="(c, i) in freightBoard?.topUnitCostItems || []" :key="i">
                    <td class="mono">{{ c.shipmentNo || c.shipmentId || '—' }}</td>
                    <td>{{ c.asin || '—' }}</td>
                    <td class="mono">{{ c.sku || '—' }}</td>
                    <td>{{ c.quantity ?? '—' }}</td>
                    <td class="mono">{{ fmtAmount(c.totalCost) }}</td>
                    <td class="mono"><b>{{ fmtAmount(c.unitCost) }}</b></td>
                    <td>{{ allocationMethodLabel(c.allocationMethod) }}</td>
                  </tr>
                </tbody>
              </table>
              <p class="foot-note">
                分摊方法说明：按重量 / 按体积 / 按数量。同一批货件混用不同方法时，
                单件成本不可直接横向比较——看板会在上方提示。
              </p>
            </div>
          </section>

          <!-- ================= 签收差异 ================= -->
          <section v-else-if="tab === 'receipts'">
            <div v-if="receiptBoard?.warnings?.length" class="warn-banner">
              <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
              <div class="wb-body">
                <div v-for="(w, i) in receiptBoard.warnings" :key="i">{{ w }}</div>
              </div>
            </div>

            <div class="kpi-grid">
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:clipboard-check-outline" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ receiptBoard?.total ?? 0 }}</div>
                  <div class="kpi-label">差异记录</div>
                  <div class="kpi-sub">含 {{ receiptBoard?.matched ?? 0 }} 条对账无差异</div>
                </div>
              </div>
              <div class="kpi-card" :class="{ 'kpi-alert': (receiptBoard?.openShortageUnits ?? 0) > 0 }">
                <div class="kpi-icon tone-danger"><Icon icon="mdi:package-variant-minus" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ receiptBoard?.openShortageUnits ?? 0 }}</div>
                  <div class="kpi-label">未结案少收 (件)</div>
                  <div class="kpi-sub">还在等处理，可直接索赔</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon tone-warn"><Icon icon="mdi:trending-down" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ receiptBoard?.shortageUnits ?? 0 }}</div>
                  <div class="kpi-label">少收合计 (件)</div>
                  <div class="kpi-sub">{{ receiptBoard?.shortageRows ?? 0 }} 条记录</div>
                </div>
              </div>
              <div class="kpi-card">
                <div class="kpi-icon"><Icon icon="mdi:trending-up" width="24" /></div>
                <div class="kpi-info">
                  <div class="kpi-value">{{ receiptBoard?.overreceivedUnits ?? 0 }}</div>
                  <div class="kpi-label">多收合计 (件)</div>
                  <div class="kpi-sub">{{ receiptBoard?.overreceivedRows ?? 0 }} 条记录</div>
                </div>
              </div>
            </div>

            <div class="mini-grid">
              <div class="mini-card" :class="{ alert: (receiptBoard?.pending ?? 0) > 0 }">
                <div class="mini-value">{{ receiptBoard?.pending ?? 0 }}</div>
                <div class="mini-label">待处理</div>
                <div class="mini-hint">尚未开始核查</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ receiptBoard?.investigating ?? 0 }}</div>
                <div class="mini-label">核查中</div>
                <div class="mini-hint">已有人跟进</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ receiptBoard?.resolved ?? 0 }}</div>
                <div class="mini-label">已结案</div>
                <div class="mini-hint">已有处理结论</div>
              </div>
              <div class="mini-card">
                <div class="mini-value">{{ fmtRate(receiptBoard?.discrepancyRate) }}</div>
                <div class="mini-label">差异率</div>
                <div class="mini-hint">
                  应收 {{ receiptBoard?.totalExpected ?? 0 }} / 实收 {{ receiptBoard?.totalReceived ?? 0 }}
                </div>
              </div>
            </div>

            <div v-if="receiptBoard?.overreceivedUnits && receiptBoard?.shortageUnits" class="warn-banner strong">
              <Icon icon="mdi:swap-horizontal-bold" width="16" class="wb-icon" />
              <div class="wb-body">
                同时存在少收与多收：净差异（{{ receiptBoard?.totalDifference ?? 0 }} 件）
                会把两个方向互相抵消。差异率因此按「绝对差异之和 / 应收合计」计算，
                避免量级很大的问题被净额掩盖。
              </div>
            </div>

            <div class="chart-card">
              <h3>少收最多的 ASIN</h3>
              <p class="card-desc">
                按少收件数降序。集中少收通常指向一个具体环节：某个 SKU 的装箱数量申报不准、
                或某个 FBA 仓对该品类的收货流程有系统性问题。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>ASIN</th>
                    <th>SKU</th>
                    <th>涉及货件</th>
                    <th>应收</th>
                    <th>实收</th>
                    <th>少收</th>
                    <th>多收</th>
                    <th>净差异</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!receiptBoard?.topShortageAsins?.length">
                    <td colspan="8" class="empty-row">没有差异记录</td>
                  </tr>
                  <tr v-for="a in receiptBoard?.topShortageAsins || []" :key="a.asin">
                    <td>{{ a.asin }}</td>
                    <td class="mono">{{ a.sku || '—' }}</td>
                    <td>{{ a.shipmentCount }}</td>
                    <td>{{ a.expected }}</td>
                    <td>{{ a.received }}</td>
                    <td :class="{ 'cell-alert': a.shortageUnits > 0 }">{{ a.shortageUnits }}</td>
                    <td>{{ a.overreceivedUnits }}</td>
                    <td>{{ a.netDifference }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="chart-card mt">
              <h3>待处理签收差异</h3>
              <p class="card-desc">
                差异数量由「实收 − 应收」自动算出，不接受手工填写，
                因此不会出现「差异值与两个数量互相矛盾」的记录。
                结案必须填写处理结果——否则这条差异会变成一条没有下文的数据，后续无法复盘。
              </p>
              <table class="data-table">
                <thead>
                  <tr>
                    <th>货件号</th>
                    <th>ASIN</th>
                    <th>SKU</th>
                    <th>应收</th>
                    <th>实收</th>
                    <th>差异</th>
                    <th>类型</th>
                    <th>状态</th>
                    <th>登记时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!receiptBoard?.pendingItems?.length">
                    <td colspan="10" class="empty-row">没有待处理的签收差异</td>
                  </tr>
                  <tr v-for="d in receiptBoard?.pendingItems || []" :key="d.id">
                    <td class="mono">{{ d.shipmentNo || d.shipmentId || '—' }}</td>
                    <td>{{ d.asin || '—' }}</td>
                    <td class="mono">{{ d.sku || '—' }}</td>
                    <td>{{ d.expectedQuantity ?? '—' }}</td>
                    <td>{{ d.receivedQuantity ?? '—' }}</td>
                    <td :class="{ 'cell-alert': (d.difference ?? 0) < 0 }">
                      {{ d.difference ?? 0 }}
                    </td>
                    <td>
                      <span class="status-tag" :class="discTypeTone(d.discrepancyType)">
                        {{ discTypeLabel(d.discrepancyType) }}
                      </span>
                    </td>
                    <td>{{ discStatusLabel(d.status) }}</td>
                    <td class="mono">{{ fmtTime(d.createTime) }}</td>
                    <td class="ops">
                      <button
                        v-if="d.status === 'PENDING'"
                        class="link-btn"
                        :disabled="busy"
                        @click="doInvestigate(d.id)"
                      >
                        开始核查
                      </button>
                      <button class="link-btn" :disabled="busy" @click="doResolve(d.id)">结案</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </template>
    </main>

    <!-- 轨迹时间线浮层 -->
    <div v-if="trackingOpen" class="modal-mask" @click.self="trackingOpen = false">
      <div class="modal" role="dialog" aria-label="轨迹时间线">
        <div class="modal-head">
          <h3>轨迹时间线</h3>
          <button class="link-btn" @click="trackingOpen = false">关闭</button>
        </div>
        <div v-if="trackingLoading" class="empty-row">加载中…</div>
        <div v-else-if="!timeline.length" class="empty-row">
          暂无轨迹数据。可点「同步」拉取，或在「数据导入」中导入轨迹。
        </div>
        <ul v-else class="timeline">
          <li v-for="(e, i) in timeline" :key="i" class="timeline-item">
            <span class="tl-dot" :class="statusTone(e.eventStatus)"></span>
            <div class="tl-body">
              <div class="tl-head">
                <span class="tl-status">{{ statusLabel(e.eventStatus) }}</span>
                <span class="tl-time mono">{{ fmtTime(e.eventTime) }}</span>
              </div>
              <div class="tl-loc">{{ e.location || '—' }}</div>
              <div v-if="e.description" class="tl-desc">{{ e.description }}</div>
              <div class="tl-meta">
                <span v-if="e.rawStatus" class="tl-raw">原文：{{ e.rawStatus }}</span>
                <span v-if="e.source" class="tl-src">{{ e.source }}</span>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <!-- 全局 toast -->
    <Transition name="fade">
      <div v-if="toastVisible" class="toast" :class="toastType">{{ toastMessage }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useToast } from '../composables/useToast'
import { useShopGuard } from '../composables/useShopGuard'
import {
  getLogisticsOverview,
  getLogisticsTrend,
  getCarrierPerformance,
  getLogisticsAlerts,
  listShipments,
  syncShipment,
  closeShipment,
  getShipmentTracking,
  importShipments,
  importTracking,
  getQuoteBoard,
  getTransferBoard,
  getFreightCostBoard,
  getReceiptBoard,
  compareQuotes,
  expireOutdatedQuotes,
  listTransfers,
  approveTransfer,
  shipTransfer,
  receiveTransfer,
  investigateDiscrepancy,
  resolveDiscrepancy,
  type DashboardOverview,
  type TrendPoint,
  type CarrierPerformance,
  type ShipmentAlert,
  type Shipment,
  type TrackingEvent,
  type ImportReport,
  type ImportShipmentRow,
  type ImportTrackingRow,
  type QuoteBoard,
  type QuoteCompareResult,
  type TransferBoard,
  type FreightCostBoard,
  type ReceiptBoard,
  type InventoryTransfer
} from '../api/logistics'

const { toastVisible, toastMessage, toastType, showToast } = useToast()
const { currentShopId, refreshShop } = useShopGuard()

const hasToken = ref(!!localStorage.getItem('token'))
const loading = ref(false)
const busy = ref(false)

const overview = ref<DashboardOverview | null>(null)
const trend = ref<TrendPoint[]>([])
const carriers = ref<CarrierPerformance[]>([])
const alerts = ref<ShipmentAlert[]>([])
const shipments = ref<Shipment[]>([])

// 运营子域看板
const quoteBoard = ref<QuoteBoard | null>(null)
const transferBoard = ref<TransferBoard | null>(null)
const freightBoard = ref<FreightCostBoard | null>(null)
const receiptBoard = ref<ReceiptBoard | null>(null)
const transfers = ref<InventoryTransfer[]>([])
const transferStatusFilter = ref('')

/** 比价入参。重量与体积至少填一个，两者都填时服务端按计费价取高 */
const cmpForm = ref<{ originPort: string; destinationPort: string; weightKg?: number; volumeCbm?: number }>({
  originPort: '',
  destinationPort: ''
})
const cmpResult = ref<QuoteCompareResult | null>(null)
const cmpLoading = ref(false)

type TabKey = 'overview' | 'alerts' | 'shipments' | 'carrier' | 'quotes' | 'transfers' | 'freight' | 'receipts' | 'import'

const tab = ref<TabKey>('overview')
const trendDays = 30
const shipmentStatus = ref('')

// 轨迹浮层
const trackingOpen = ref(false)
const trackingLoading = ref(false)
const timeline = ref<TrackingEvent[]>([])

// 导入
const shipmentJson = ref('')
const trackingJson = ref('')
const importReport = ref<ImportReport | null>(null)

const shipmentExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU1234567',
      shippingMethod: 'SEA',
      originPort: 'Shenzhen',
      destinationPort: 'LAX9',
      boxCount: 120,
      weight: 2400,
      eta: '2026-09-20'
    }
  ],
  null,
  2
)

const trackingExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      trackingNo: 'COSU1234567',
      events: [
        { status: '已开船', location: '深圳港', eventTime: '2026-08-05T09:00:00+08:00' },
        { status: 'IN_TRANSIT', location: '太平洋', eventTime: '2026-08-12T09:00:00+08:00' },
        { status: '清关中', location: '洛杉矶港', eventTime: '2026-08-16T21:30:00-07:00' }
      ]
    }
  ],
  null,
  2
)

const shipmentPlaceholder = `[\n  { "shipmentNo": "SHP20260818001", "carrier": "COSCO", "masterTrackingNo": "COSU1234567", "eta": "2026-09-20" }\n]`
const trackingPlaceholder = `[\n  { "shipmentNo": "SHP20260818001", "events": [ { "status": "已开船", "eventTime": "2026-08-05T09:00:00+08:00" } ] }\n]`

// ============================================================
// 展示映射（状态 / 类型 / 级别）
// ============================================================

const STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  IN_TRANSIT: '运输中',
  CUSTOMS: '清关中',
  DELIVERED: '已送达',
  RECEIVED: 'FBA已入库',
  CLOSED: '已关闭',
  DELAYED: '延误',
  EXCEPTION: '异常',
  // 轨迹点状态（与货件状态命名不同，这里一并兜底展示）
  DEPARTED: '已起运',
  CUSTOMS_CLEARANCE: '清关中',
  ARRIVED: '已到港',
  OUT_FOR_DELIVERY: '派送中'
}

const statusOptions = ['CREATED', 'IN_TRANSIT', 'CUSTOMS', 'DELIVERED', 'RECEIVED', 'DELAYED', 'EXCEPTION', 'CLOSED']

const METHOD_LABELS: Record<string, string> = {
  SEA: '海运',
  AIR: '空运',
  EXPRESS: '快递',
  TRUCK: '卡车'
}

const ALERT_TYPE_LABELS: Record<string, string> = {
  DELAYED: '延误',
  EXCEPTION: '异常',
  ETA_OVERDUE: '超期未标',
  ETA_APPROACHING: '临近到港',
  STALE_DATA: '取数过期',
  MISSING_TRACKING_NO: '缺运单号'
}

/** 调拨单状态。流转只能单向推进，前端据此决定显示哪些操作 */
const TRANSFER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批待发出',
  IN_TRANSIT: '运输中',
  RECEIVED: '已收货',
  CANCELLED: '已取消'
}

const TRANSFER_RISK_LABELS: Record<string, string> = {
  STALE_IN_TRANSIT: '在途超期',
  PENDING_APPROVAL_TOO_LONG: '审批超期',
  MISSING_TRACKING_NO: '缺物流单号'
}

/** 比价实际采用的计费口径，必须展示：否则看不懂为什么金额和「重量 × 单价」对不上 */
const BASIS_LABELS: Record<string, string> = {
  WEIGHT: '按重量',
  VOLUME: '按体积',
  NO_PRICING_DATA: '无可用单价'
}

const ALLOCATION_METHOD_LABELS: Record<string, string> = {
  WEIGHT: '按重量',
  VOLUME: '按体积',
  QUANTITY: '按数量'
}

const DISC_TYPE_LABELS: Record<string, string> = {
  OVERRECEIVED: '多收',
  UNDERRECEIVED: '少收',
  MATCHED: '无差异',
  DAMAGED: '破损',
  NEGATIVE: '负数签收'
}

const DISC_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  INVESTIGATING: '核查中',
  RESOLVED: '已结案'
}

const statusLabel = (s?: string) => (s ? STATUS_LABELS[s] || s : '—')
const methodLabel = (m?: string) => (m ? METHOD_LABELS[m] || m : '—')
const alertTypeLabel = (t: string) => ALERT_TYPE_LABELS[t] || t
const transferStatusLabel = (s?: string) => (s ? TRANSFER_STATUS_LABELS[s] || s : '—')
const transferRiskLabel = (t?: string) => (t ? TRANSFER_RISK_LABELS[t] || t : '—')
const basisLabel = (b?: string) => (b ? BASIS_LABELS[b] || b : '—')
const allocationMethodLabel = (m?: string) => (m ? ALLOCATION_METHOD_LABELS[m] || m : '—')
const discTypeLabel = (t?: string) => (t ? DISC_TYPE_LABELS[t] || t : '—')
const discStatusLabel = (s?: string) => (s ? DISC_STATUS_LABELS[s] || s : '—')

const transferStatusTone = (s?: string) => {
  switch (s) {
    case 'CANCELLED':
      return 'tone-muted'
    case 'RECEIVED':
      return 'tone-success'
    case 'IN_TRANSIT':
      return 'tone-info'
    case 'APPROVED':
    case 'PENDING_APPROVAL':
      return 'tone-warn'
    default:
      return 'tone-muted'
  }
}

const discTypeTone = (t?: string) => {
  if (t === 'UNDERRECEIVED') return 'tone-danger'
  if (t === 'OVERRECEIVED') return 'tone-warn'
  if (t === 'MATCHED') return 'tone-success'
  return 'tone-muted'
}

/** 只有草稿与待审批可以改判；已发出 / 已收货 / 已取消不再显示审批按钮 */
const canApprove = (status?: string) => status === 'DRAFT' || status === 'PENDING_APPROVAL'
const isTransferDone = (status?: string) => status === 'RECEIVED' || status === 'CANCELLED'

const severityLabel = (s: string) => (s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : '低')
const severityTone = (s: string) => (s === 'HIGH' ? 'tone-danger' : s === 'MEDIUM' ? 'tone-warn' : 'tone-muted')

/** 状态 → 语义配色。延误/异常为红，正常推进为绿/中性，避免全表一个颜色看不出重点 */
const statusTone = (s?: string) => {
  switch (s) {
    case 'DELAYED':
    case 'EXCEPTION':
      return 'tone-danger'
    case 'DELIVERED':
    case 'RECEIVED':
      return 'tone-success'
    case 'IN_TRANSIT':
    case 'CUSTOMS':
    case 'DEPARTED':
    case 'CUSTOMS_CLEARANCE':
    case 'ARRIVED':
    case 'OUT_FOR_DELIVERY':
      return 'tone-info'
    default:
      return 'tone-muted'
  }
}

const fmtTime = (v?: string | null) => {
  if (!v) return '—'
  return v.replace('T', ' ').slice(0, 19)
}

/** 金额/数量：null 与 undefined 都显示占位，避免出现 NaN 或误读成 0 */
const fmtAmount = (v?: number | null) => (v === null || v === undefined ? '—' : v.toFixed(2))

/** 比率（0~1）→ 百分比；null / undefined 表示无样本，显示占位而非 0% */
const fmtRate = (v?: number | null) =>
  v === null || v === undefined ? '—' : (v * 100).toFixed(1) + '%'

/** 报价区间：两端相同只显示一个值，避免出现「3.50 ~ 3.50」这种噪音 */
const priceRange = (min?: number | null, max?: number | null) => {
  if (min === null || min === undefined) {
    return max === null || max === undefined ? '—' : String(max)
  }
  if (max === null || max === undefined || min === max) {
    return String(min)
  }
  return `${min} ~ ${max}`
}

const transitRange = (r: { minTransitDays: number; maxTransitDays: number }) => {
  if (r.minTransitDays < 0) return '未填写'
  return r.minTransitDays === r.maxTransitDays
    ? `${r.minTransitDays} 天`
    : `${r.minTransitDays} ~ ${r.maxTransitDays} 天`
}

// ============================================================
// 派生数据
// ============================================================

const tabs = computed(() => [
  { key: 'overview' as const, label: '概览', badge: '', badgeTone: '' },
  {
    key: 'alerts' as const,
    label: '待处理',
    badge: alerts.value.length ? String(alerts.value.length) : '',
    badgeTone: alerts.value.length ? 'badge-danger' : ''
  },
  { key: 'shipments' as const, label: '在途货件', badge: '', badgeTone: '' },
  { key: 'carrier' as const, label: '承运商表现', badge: '', badgeTone: '' },
  // 子域标签的角标只挂「需要有人动手」的数量，纯统计量不挂，否则角标到处都是就失去提示作用
  {
    key: 'quotes' as const,
    label: '比价',
    badge: quoteBoard.value?.staleByDate ? String(quoteBoard.value.staleByDate) : '',
    badgeTone: quoteBoard.value?.staleByDate ? 'badge-warn' : ''
  },
  {
    key: 'transfers' as const,
    label: '调拨',
    badge: transferBoard.value?.risks.length ? String(transferBoard.value.risks.length) : '',
    badgeTone: transferBoard.value?.risks.length ? 'badge-danger' : ''
  },
  {
    key: 'freight' as const,
    label: '头程成本',
    badge: freightBoard.value?.uncoveredShipments ? String(freightBoard.value.uncoveredShipments) : '',
    badgeTone: freightBoard.value?.uncoveredShipments ? 'badge-warn' : ''
  },
  {
    key: 'receipts' as const,
    label: '签收差异',
    badge: receiptBoard.value?.pending ? String(receiptBoard.value.pending) : '',
    badgeTone: receiptBoard.value?.pending ? 'badge-danger' : ''
  },
  { key: 'import' as const, label: '数据导入', badge: '', badgeTone: '' }
])

const trendTotal = computed(() => {
  let created = 0
  let delivered = 0
  for (const p of trend.value) {
    created += p.created
    delivered += p.delivered
  }
  return { created, delivered }
})

const statusDist = computed(() => {
  const counts = overview.value?.statusCounts || {}
  const total = overview.value?.totalShipments || 0
  const order = ['IN_TRANSIT', 'CUSTOMS', 'CREATED', 'DELAYED', 'EXCEPTION', 'DELIVERED', 'RECEIVED', 'CLOSED']
  const rows = order
    .filter((k) => (counts[k] || 0) > 0)
    .map((k) => ({
      key: k,
      label: statusLabel(k),
      count: counts[k] || 0,
      tone: statusTone(k),
      percent: total > 0 ? ((counts[k] || 0) / total) * 100 : 0
    }))
  return rows
})

const sourceDist = computed(() => {
  const counts = overview.value?.dataSourceCounts || {}
  const labels: Record<string, string> = { IMPORT: '外部导入', API: '第三方API', AUTO: '自动' }
  return Object.keys(labels)
    .filter((k) => (counts[k] || 0) > 0)
    .map((k) => ({ key: k, label: labels[k], count: counts[k] || 0 }))
})

/** 单一来源航线数：无议价空间，是选商环节最该先补的缺口 */
const singleSourceRoutes = computed(
  () => (quoteBoard.value?.routes || []).filter((r) => r.singleSource).length
)

const currencyCount = computed(() => Object.keys(quoteBoard.value?.byCurrency || {}).length)

/** 成本构成占比。总额为 0 时返回占位而不是 0%，否则会被读成「这项没花钱」 */
const costShare = (part: 'freight' | 'duty' | 'insurance' | 'other') => {
  const board = freightBoard.value
  if (!board || !board.totalCost) return '—'
  const value = {
    freight: board.totalFreight,
    duty: board.totalDuty,
    insurance: board.totalInsurance,
    other: board.totalOther
  }[part]
  if (value === null || value === undefined) return '—'
  return ((value / board.totalCost) * 100).toFixed(1) + '%'
}

/**
 * 手写 SVG 折线图。
 * viewBox 固定宽度：随容器自适应缩放，避免监听 resize 做重算。
 */
const chart = computed(() => {
  const points = trend.value
  if (!points.length) return null
  const W = 640
  const H = 200
  const PAD = 34
  const innerH = H - PAD - 18

  let maxV = 1
  for (const p of points) {
    if (p.created > maxV) maxV = p.created
    if (p.delivered > maxV) maxV = p.delivered
  }
  const stepX = points.length > 1 ? (W - PAD * 2) / (points.length - 1) : 0
  const xOf = (i: number) => PAD + i * stepX
  const yOf = (v: number) => H - 18 - (v / maxV) * innerH

  const toPoly = (key: 'created' | 'delivered') =>
    points.map((p, i) => `${xOf(i).toFixed(1)},${yOf(p[key]).toFixed(1)}`).join(' ')

  const grid = [0, 0.5, 1].map((r) => ({
    y: Number(yOf(maxV * r).toFixed(1)),
    value: Math.round(maxV * r)
  }))

  // X 轴标签按间隔抽稀，避免 30 条标签重叠成一团
  const every = Math.max(1, Math.ceil(points.length / 6))
  const xLabels = points
    .map((p, i) => ({ x: Number(xOf(i).toFixed(1)), date: p.date }))
    .filter((_, i) => i % every === 0)

  return {
    W,
    H,
    PAD,
    grid,
    xLabels,
    createdPath: toPoly('created'),
    deliveredPath: toPoly('delivered')
  }
})

// ============================================================
// 数据加载
// ============================================================

/**
 * 统一包一层加载容错。
 * 单个接口异常只记 console，不影响其余区块渲染——
 * 看板是概览视图，因一个接口报错就整页空白会让人误判为「系统挂了」。
 */
const safeLoad = async (tag: string, fn: () => Promise<void>) => {
  try {
    await fn()
  } catch (e) {
    console.warn(`[Logistics] ${tag} 加载失败`, e)
  }
}

const loadOverview = async (shopId: string) => {
  const res = await getLogisticsOverview(shopId)
  if (res?.code === 200 && res.data) {
    overview.value = res.data
  }
}

const loadTrend = async (shopId: string) => {
  const res = await getLogisticsTrend(shopId, trendDays)
  if (res?.code === 200) {
    trend.value = res.data || []
  }
}

const loadCarriers = async (shopId: string) => {
  const res = await getCarrierPerformance(shopId)
  if (res?.code === 200) {
    carriers.value = res.data || []
  }
}

const loadAlerts = async (shopId: string) => {
  const res = await getLogisticsAlerts(shopId)
  if (res?.code === 200) {
    alerts.value = res.data || []
  }
}

const loadShipments = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  const res = await listShipments(shopId, shipmentStatus.value || undefined)
  if (res?.code === 200) {
    shipments.value = res.data || []
  }
}

// ---- 运营子域看板 ----

const loadQuoteBoard = async (shopId: string) => {
  const res = await getQuoteBoard(shopId)
  if (res?.code === 200 && res.data) {
    quoteBoard.value = res.data
  }
}

const loadTransferBoard = async (shopId: string) => {
  const res = await getTransferBoard(shopId)
  if (res?.code === 200 && res.data) {
    transferBoard.value = res.data
  }
}

const loadFreightBoard = async (shopId: string) => {
  const res = await getFreightCostBoard(shopId)
  if (res?.code === 200 && res.data) {
    freightBoard.value = res.data
  }
}

const loadReceiptBoard = async (shopId: string) => {
  const res = await getReceiptBoard(shopId)
  if (res?.code === 200 && res.data) {
    receiptBoard.value = res.data
  }
}

const loadTransfers = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  const res = await listTransfers(shopId, transferStatusFilter.value || undefined)
  if (res?.code === 200) {
    transfers.value = res.data || []
  }
}

/** 加载首屏四组核心数据（概览 / 趋势 / 承运商 / 告警） */
const reload = async () => {
  if (!hasToken.value) return
  const shopId = refreshShop()
  if (!shopId) return

  loading.value = true
  await Promise.all([
    safeLoad('overview', () => loadOverview(shopId)),
    safeLoad('trend', () => loadTrend(shopId)),
    safeLoad('carriers', () => loadCarriers(shopId)),
    safeLoad('alerts', () => loadAlerts(shopId))
  ])
  loading.value = false
}

/**
 * 刷新「当前正在看的标签页」。
 * <p>
 * 首屏四个接口走骨架屏；子域看板若也走骨架屏，切换标签时整页会闪一下。
 * 因此刷新按钮按需只拉当前标签的数据，并用 busy 只禁用操作按钮。
 */
const refreshCurrent = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  busy.value = true
  try {
    if (tab.value === 'overview') {
      await reload()
    } else if (tab.value === 'shipments') {
      await loadShipments()
    } else if (tab.value === 'quotes') {
      await safeLoad('quotes', () => loadQuoteBoard(shopId))
    } else if (tab.value === 'transfers') {
      await safeLoad('transfers', () => loadTransferBoard(shopId))
      await loadTransfers()
    } else if (tab.value === 'freight') {
      await safeLoad('freight', () => loadFreightBoard(shopId))
    } else if (tab.value === 'receipts') {
      await safeLoad('receipts', () => loadReceiptBoard(shopId))
    } else {
      await reload()
    }
    // 子域的角标依赖看板数据，顺带刷新概览让同步状态条与角标保持一致
    await safeLoad('overview', () => loadOverview(shopId))
  } finally {
    busy.value = false
  }
}

const switchTab = async (key: TabKey) => {
  tab.value = key
  const shopId = refreshShop()
  if (!shopId) return
  // 按标签懒加载：子域看板只在第一次打开时拉取，避免进来就把六七个接口全打一遍
  if (key === 'shipments') {
    if (!shipments.value.length) await safeLoad('shipments', loadShipments)
  } else if (key === 'quotes') {
    if (!quoteBoard.value) await safeLoad('quotes', () => loadQuoteBoard(shopId))
  } else if (key === 'transfers') {
    if (!transferBoard.value) await safeLoad('transfers', () => loadTransferBoard(shopId))
    if (!transfers.value.length) await loadTransfers()
  } else if (key === 'freight') {
    if (!freightBoard.value) await safeLoad('freight', () => loadFreightBoard(shopId))
  } else if (key === 'receipts') {
    if (!receiptBoard.value) await safeLoad('receipts', () => loadReceiptBoard(shopId))
  }
}

// ============================================================
// 操作
// ============================================================

const doSync = async (shipmentId: number) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await syncShipment(shipmentId)
    if (res?.code === 200) {
      showToast('同步完成', 'success')
      await Promise.all([loadShipments(), loadOverview(refreshShop())])
    } else {
      showToast(res?.message || '同步失败', 'error')
    }
  } catch (e) {
    showToast('同步失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const doClose = async (shipmentId: number) => {
  if (busy.value) return
  if (!window.confirm('确认关闭该货件？关闭后不再参与自动跟踪与延误统计。')) return
  busy.value = true
  try {
    const res = await closeShipment(shipmentId, refreshShop())
    if (res?.code === 200) {
      showToast('货件已关闭', 'success')
      await Promise.all([loadShipments(), reload()])
    } else {
      showToast(res?.message || '关闭失败', 'error')
    }
  } catch (e) {
    showToast('关闭失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const openTracking = async (shipmentId: number) => {
  trackingOpen.value = true
  trackingLoading.value = true
  timeline.value = []
  try {
    const res = await getShipmentTracking(shipmentId)
    if (res?.code === 200) {
      timeline.value = res.data || []
    }
  } catch (e) {
    console.warn('[Logistics] 轨迹加载失败', e)
  } finally {
    trackingLoading.value = false
  }
}

// ---- 比价 ----

const doCompare = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  cmpLoading.value = true
  try {
    const res = await compareQuotes(shopId, {
      originPort: cmpForm.value.originPort.trim(),
      destinationPort: cmpForm.value.destinationPort.trim(),
      weightKg: cmpForm.value.weightKg || undefined,
      volumeCbm: cmpForm.value.volumeCbm || undefined
    })
    if (res?.code === 200 && res.data) {
      cmpResult.value = res.data
    } else {
      // 后端会说明具体缺什么（例如「请至少提供重量或体积」），直接透出比笼统提示有用
      showToast(res?.message || '比价失败', 'error')
    }
  } catch (e) {
    showToast('比价失败，请稍后重试', 'error')
  } finally {
    cmpLoading.value = false
  }
}

const doExpireQuotes = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  const count = quoteBoard.value?.staleByDate ?? 0
  if (!count) return
  if (!window.confirm(`确认把 ${count} 条已失效的报价状态置为 EXPIRED？`)) return
  busy.value = true
  try {
    const res = await expireOutdatedQuotes(shopId)
    if (res?.code === 200) {
      showToast(`已标记 ${res.data ?? 0} 条过期报价`, 'success')
      await safeLoad('quotes', () => loadQuoteBoard(shopId))
    } else {
      showToast(res?.message || '标记失败', 'error')
    }
  } catch (e) {
    showToast('标记失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

// ---- 调拨 ----

/** 审批后会改变状态分布，同时刷新看板与列表，避免两处数字对不上 */
const refreshTransferViews = async () => {
  const shopId = refreshShop()
  await loadTransfers()
  await safeLoad('transfers', () => loadTransferBoard(shopId))
}

const doApproveTransfer = async (t: InventoryTransfer, approved: boolean) => {
  if (busy.value || t.id === undefined) return
  const action = approved ? '审批通过' : '驳回'
  if (!window.confirm(`确认${action}调拨单 ${t.transferNo}？`)) return
  busy.value = true
  try {
    const res = await approveTransfer(t.id, approved)
    if (res?.code === 200) {
      showToast(`调拨单已${action}`, 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || `${action}失败`, 'error')
    }
  } catch (e) {
    showToast(`${action}失败，请稍后重试`, 'error')
  } finally {
    busy.value = false
  }
}

const doShipTransfer = async (t: InventoryTransfer) => {
  if (busy.value || t.id === undefined) return
  const carrier = window.prompt('承运商名称', t.carrier || '')
  if (carrier === null) return
  if (!carrier.trim()) {
    showToast('承运商不能为空', 'error')
    return
  }
  const trackingNo = window.prompt('物流单号', t.trackingNo || '')
  if (trackingNo === null) return
  // 单号决定了后续能否自动跟单，因此这里只做非空校验而不静默跳过
  if (!trackingNo.trim()) {
    showToast('物流单号不能为空', 'error')
    return
  }
  busy.value = true
  try {
    const res = await shipTransfer(t.id, carrier.trim(), trackingNo.trim())
    if (res?.code === 200) {
      showToast('调拨单已标记发出', 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || '操作失败', 'error')
    }
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const doReceiveTransfer = async (t: InventoryTransfer) => {
  if (busy.value || t.id === undefined) return
  if (!window.confirm(`确认调拨单 ${t.transferNo} 已到货？确认后单据结束。`)) return
  busy.value = true
  try {
    const res = await receiveTransfer(t.id)
    if (res?.code === 200) {
      showToast('调拨单已确认收货', 'success')
      await refreshTransferViews()
    } else {
      showToast(res?.message || '确认收货失败', 'error')
    }
  } catch (e) {
    showToast('确认收货失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

// ---- 签收差异 ----

const doInvestigate = async (id: number) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await investigateDiscrepancy(id)
    if (res?.code === 200) {
      showToast('已转入核查中', 'success')
      await safeLoad('receipts', () => loadReceiptBoard(refreshShop()))
    } else {
      showToast(res?.message || '操作失败', 'error')
    }
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

const doResolve = async (id: number) => {
  if (busy.value) return
  const resolution = window.prompt('请输入处理结果（结案必填，例如：已向亚马逊提交索赔 / 确认不补发）')
  if (resolution === null) return
  if (!resolution.trim()) {
    showToast('结案必须填写处理结果', 'error')
    return
  }
  busy.value = true
  try {
    const res = await resolveDiscrepancy(id, resolution.trim())
    if (res?.code === 200) {
      showToast('差异已结案', 'success')
      await safeLoad('receipts', () => loadReceiptBoard(refreshShop()))
    } else {
      showToast(res?.message || '结案失败', 'error')
    }
  } catch (e) {
    showToast('结案失败，请稍后重试', 'error')
  } finally {
    busy.value = false
  }
}

/** 解析粘贴的 JSON。解析失败时给出具体原因，而不是笼统的「导入失败」 */
const parseRows = <T,>(text: string): T[] | null => {
  try {
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      showToast('内容必须是 JSON 数组', 'error')
      return null
    }
    return parsed as T[]
  } catch (e) {
    showToast('JSON 解析失败：' + (e instanceof Error ? e.message : '格式错误'), 'error')
    return null
  }
}

const doImportShipments = async () => {
  const rows = parseRows<ImportShipmentRow>(shipmentJson.value)
  if (!rows) return
  await runImport(() => importShipments(refreshShop(), rows), '货件主单')
}

const doImportTracking = async () => {
  const rows = parseRows<ImportTrackingRow>(trackingJson.value)
  if (!rows) return
  await runImport(() => importTracking(refreshShop(), rows), '运单轨迹')
}

const runImport = async (
  fn: () => Promise<{ code: number; message: string; data: ImportReport }>,
  label: string
) => {
  if (busy.value) return
  busy.value = true
  try {
    const res = await fn()
    if (res?.code === 200 && res.data) {
      importReport.value = res.data
      const r = res.data
      showToast(
        `${label}导入完成：成功 ${r.succeededRows} / 失败 ${r.failedRows}`,
        r.failedRows > 0 ? 'error' : 'success'
      )
      // 导入会改变看板数字，直接刷新让使用者立刻看到结果，省去手动再点一次刷新
      await reload()
      if (tab.value === 'shipments') await loadShipments()
    } else {
      showToast(res?.message || `${label}导入失败`, 'error')
    }
  } catch (e) {
    showToast(`${label}导入失败，请稍后重试`, 'error')
  } finally {
    busy.value = false
  }
}

// ============================================================
// 生命周期
// ============================================================

// 登录态变化（顶部登录/登出）后重评估：登录后补拉，登出后清空回提示态
const onAuthChanged = () => {
  hasToken.value = !!localStorage.getItem('token')
  if (hasToken.value) {
    reload()
    if (tab.value === 'shipments') loadShipments()
    // 子域看板同样要跟着换店铺重拉，否则会残留上一个店铺的数字
    const shopId = refreshShop()
    if (tab.value === 'quotes') safeLoad('quotes', () => loadQuoteBoard(shopId))
    if (tab.value === 'transfers') {
      safeLoad('transfers', () => loadTransferBoard(shopId))
      loadTransfers()
    }
    if (tab.value === 'freight') safeLoad('freight', () => loadFreightBoard(shopId))
    if (tab.value === 'receipts') safeLoad('receipts', () => loadReceiptBoard(shopId))
  } else {
    overview.value = null
    trend.value = []
    carriers.value = []
    alerts.value = []
    shipments.value = []
    quoteBoard.value = null
    transferBoard.value = null
    freightBoard.value = null
    receiptBoard.value = null
    transfers.value = []
    cmpResult.value = null
    loading.value = false
  }
}

onMounted(() => {
  reload()
  window.addEventListener('amz:auth-changed', onAuthChanged)
})

onUnmounted(() => {
  window.removeEventListener('amz:auth-changed', onAuthChanged)
})
</script>

<style scoped>
.logistics-page {
  background: var(--color-background);
}

.login-hint {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  margin-bottom: 1rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-muted);
  font-size: var(--font-size-2);
}

/* 取数状态条：本看板最关键的一条信息——数据到底还在不在更新 */
.sync-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1rem;
  margin-bottom: 1rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
}
.sync-bar.on {
  background: var(--color-success-light);
  color: var(--color-success);
}
.sync-bar.off {
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
}
.sync-icon { flex-shrink: 0; }
.sync-text { flex: 1; }
.sync-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  font-size: var(--font-size-2);
  cursor: pointer;
}
.sync-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 骨架屏 */
.skeleton-zone { min-height: 20rem; }
.sk-icon { width: 3rem; height: 3rem; flex-shrink: 0; }
.sk-lines { flex: 1; display: flex; flex-direction: column; gap: 0.5rem; justify-content: center; }
.sk-line { height: 0.875rem; }
.sk-line-lg { width: 55%; }
.sk-line-sm { width: 35%; }
.sk-chart { height: 12rem; margin-top: 1rem; }

/* 标签页 */
.tabs {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--color-border);
  overflow-x: auto;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.625rem 1rem;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--font-size-3);
  cursor: pointer;
  white-space: nowrap;
}
.tab:hover { color: var(--color-on-surface); }
.tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.tab-badge {
  min-width: 1.25rem;
  padding: 0 0.375rem;
  border-radius: var(--radius-full);
  font-size: var(--font-size-1);
  text-align: center;
  background: var(--color-muted-light);
  color: var(--color-muted);
}
.tab-badge.badge-danger {
  background: var(--color-light-red);
  color: var(--color-error);
}
.tab-badge.badge-warn {
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
}

/* 看板提示条：口径说明与数据质量提醒。
   单独做一个条而不是塞进正文，是为了让「有口径需要注意」这件事无法被忽略 */
.warn-banner {
  display: flex;
  gap: 0.5rem;
  padding: 0.625rem 1rem;
  margin-bottom: 1rem;
  border-radius: var(--radius-md);
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
  font-size: var(--font-size-2);
  line-height: var(--line-height-relaxed);
}
.warn-banner.strong {
  background: var(--color-light-red);
  color: var(--color-error);
}
.wb-icon { flex-shrink: 0; margin-top: 0.125rem; }
.wb-body { flex: 1; }

/* KPI */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}
.kpi-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  display: flex;
  gap: 1rem;
  box-shadow: var(--shadow-sm);
}
.kpi-icon {
  width: 3rem;
  height: 3rem;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--color-primary);
  color: var(--color-on-primary);
}
.kpi-icon.tone-danger { background: var(--color-error); color: #fff; }
.kpi-icon.tone-warn { background: var(--color-warning); color: #fff; }
.kpi-info { flex: 1; }
.kpi-value {
  font-size: var(--font-size-6);
  font-weight: 700;
  color: var(--color-on-surface);
  line-height: var(--line-height-tight);
}
.kpi-label {
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
  margin-top: 0.25rem;
}
.kpi-sub {
  font-size: var(--font-size-1);
  color: var(--color-muted);
  margin-top: 0.125rem;
}
/* KPI 卡在「这项不该有值却有值」时描红边：否则一眼扫过去数字大小看不出异常 */
.kpi-card.kpi-alert {
  box-shadow: inset 0 0 0 1px var(--color-error);
}

/* 次级指标 */
.mini-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
  margin-bottom: 1.5rem;
}
.mini-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 1rem;
}
/* 需要处理时描红边：这些指标为 0 才是正常，非 0 就要有人去看 */
.mini-card.alert {
  border-color: var(--color-error);
  background: var(--color-light-red);
}
.mini-value {
  font-size: var(--font-size-5);
  font-weight: 700;
  color: var(--color-on-surface);
}
.mini-label {
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
  margin-top: 0.25rem;
}
.mini-hint {
  font-size: var(--font-size-1);
  color: var(--color-muted);
  margin-top: 0.125rem;
}

/* 图表区 */
.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1.5rem;
}
.chart-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  box-shadow: var(--shadow-sm);
}
.chart-card h3 {
  font-size: var(--font-size-4);
  font-weight: 600;
  margin: 0 0 1rem 0;
  color: var(--color-on-surface);
}
.chart-card h3.mt { margin-top: 1.5rem; }
/* 卡片之间的间距：同层级卡片紧跟，跨层级的卡片用 .mt 拉开 */
.chart-card.mt { margin-top: 1.5rem; }

/* 表格里的次要补充行（SKU、运单号等），不与主值抢注意力 */
.sub-line {
  font-size: var(--font-size-1);
  color: var(--color-muted);
  margin-top: 0.125rem;
}

/* 比价表单 */
.cmp-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.cmp-form label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
}
.cmp-form input {
  width: 9rem;
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  font-size: var(--font-size-2);
}
.cmp-result { margin-top: 0.5rem; }
.cmp-best {
  padding: 0.5rem 0.75rem;
  margin-bottom: 0.75rem;
  border-radius: var(--radius-sm);
  background: var(--color-success-light);
  color: var(--color-success);
  font-size: var(--font-size-2);
}
.cmp-best.multi {
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
}
.err-list.note { color: var(--color-warning-dark); }

/* 卡片标题行内的小筛选器 */
.filter-inline {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: var(--font-size-2);
  color: var(--color-muted);
}
.filter-inline select {
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  font-size: var(--font-size-2);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}
.card-head h3 { margin: 0; }
.card-desc {
  font-size: var(--font-size-2);
  color: var(--color-muted);
  margin: 0 0 0.75rem 0;
  line-height: var(--line-height-relaxed);
}
.legend {
  display: flex;
  gap: 0.75rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
}
.lg { display: inline-flex; align-items: center; gap: 0.25rem; }
.dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: var(--radius-full);
  display: inline-block;
}
.dot-created { background: var(--color-primary); }
.dot-delivered { background: var(--color-success); }

.chart { width: 100%; height: auto; display: block; }
.grid-line { stroke: var(--color-border); stroke-width: 1; }
.axis-text {
  font-size: 10px;
  fill: var(--color-muted);
}
.line {
  fill: none;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.line-created { stroke: var(--color-primary); }
.line-delivered { stroke: var(--color-success); }

/* 状态分布 */
.dist-list { display: flex; flex-direction: column; gap: 0.625rem; }
.dist-item { display: flex; align-items: center; gap: 0.5rem; }
.dist-bar {
  flex: 1;
  height: 0.5rem;
  border-radius: var(--radius-full);
  background: var(--color-muted-light);
  overflow: hidden;
}
.dist-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: var(--radius-full);
}
.dist-value {
  width: 2.5rem;
  text-align: right;
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
}
.source-list { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.source-chip {
  padding: 0.25rem 0.625rem;
  border-radius: var(--radius-sm);
  background: var(--color-muted-light);
  color: var(--color-muted);
  font-size: var(--font-size-1);
}

/* 状态标签语义配色 */
.status-tag.tone-danger { background: var(--color-light-red); color: var(--color-error); }
.status-tag.tone-warn { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.tone-success { background: var(--color-success-light); color: var(--color-success); }
.status-tag.tone-info { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.tone-muted { background: var(--color-muted-light); color: var(--color-muted); }

/* 表格补充 */
.hint-cell { max-width: 22rem; }
.hint-msg { font-size: var(--font-size-2); color: var(--color-on-surface); }
.hint-action {
  font-size: var(--font-size-1);
  color: var(--color-muted);
  margin-top: 0.125rem;
}
.ops { white-space: nowrap; }
.link-btn {
  padding: 0.125rem 0.375rem;
  border: none;
  background: transparent;
  color: var(--color-primary);
  font-size: var(--font-size-2);
  cursor: pointer;
}
.link-btn:hover { text-decoration: underline; }
.link-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.cell-alert { color: var(--color-error); font-weight: 600; }
.foot-note {
  margin-top: 0.75rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
  line-height: var(--line-height-relaxed);
}

/* 过滤条 */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
  font-size: var(--font-size-2);
  color: var(--color-muted);
}
.filter-bar select {
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-on-surface);
  font-size: var(--font-size-2);
}
.filter-count { margin-left: auto; }

/* 数据导入 */
.import-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}
.json-input {
  width: 100%;
  min-height: 12rem;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-background);
  color: var(--color-on-surface);
  font-family: var(--font-mono);
  font-size: var(--font-size-1);
  line-height: var(--line-height-normal);
  resize: vertical;
  box-sizing: border-box;
}
.import-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-top: 0.75rem;
}
.primary-btn {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--font-size-2);
  cursor: pointer;
}
.primary-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 导入报告 */
.report-card { margin-top: 0.5rem; }
.report-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.report-item {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  padding: 0.75rem;
  border-radius: var(--radius-sm);
  background: var(--color-muted-light);
}
.report-item b { font-size: var(--font-size-5); color: var(--color-on-surface); }
.report-item span { font-size: var(--font-size-1); color: var(--color-muted); }
.report-block { margin-top: 1rem; }
.report-title {
  font-size: var(--font-size-2);
  color: var(--color-on-surface);
  margin-bottom: 0.5rem;
}
.chip-wrap { display: flex; flex-wrap: wrap; gap: 0.375rem; }
.chip {
  padding: 0.125rem 0.5rem;
  border-radius: var(--radius-sm);
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
  font-family: var(--font-mono);
  font-size: var(--font-size-1);
}
.err-list {
  margin: 0;
  padding-left: 1.25rem;
  font-size: var(--font-size-2);
  color: var(--color-error);
  line-height: var(--line-height-relaxed);
}
.report-ok {
  margin: 0.75rem 0 0 0;
  font-size: var(--font-size-2);
  color: var(--color-success);
}

/* 轨迹浮层 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}
.modal {
  width: min(44rem, 100%);
  max-height: 80vh;
  overflow-y: auto;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.25rem;
  box-shadow: var(--shadow-md);
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.modal-head h3 {
  margin: 0;
  font-size: var(--font-size-4);
  color: var(--color-on-surface);
}
.timeline { list-style: none; margin: 0; padding: 0; }
.timeline-item {
  display: flex;
  gap: 0.75rem;
  padding-bottom: 1rem;
  position: relative;
}
.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 0.3125rem;
  top: 1rem;
  bottom: 0;
  width: 1px;
  background: var(--color-border);
}
.tl-dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: var(--radius-full);
  flex-shrink: 0;
  margin-top: 0.25rem;
  z-index: 1;
  background: var(--color-muted);
}
.tl-dot.tone-danger { background: var(--color-error); }
.tl-dot.tone-success { background: var(--color-success); }
.tl-dot.tone-info { background: var(--color-primary); }
.tl-body { flex: 1; }
.tl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
}
.tl-status { font-size: var(--font-size-2); font-weight: 600; color: var(--color-on-surface); }
.tl-time { color: var(--color-muted); }
.tl-loc { font-size: var(--font-size-2); color: var(--color-on-surface); margin-top: 0.125rem; }
.tl-desc { font-size: var(--font-size-2); color: var(--color-muted); margin-top: 0.125rem; }
.tl-meta {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.25rem;
  font-size: var(--font-size-1);
  color: var(--color-muted);
}
.tl-raw { font-family: var(--font-mono); }
.tl-src {
  padding: 0 0.375rem;
  border-radius: var(--radius-xs);
  background: var(--color-muted-light);
}

/* toast */
.toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.625rem 1.25rem;
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
  color: #fff;
  z-index: 1100;
  box-shadow: var(--shadow-md);
}
.toast.success { background: var(--color-success); }
.toast.error { background: var(--color-error); }
.toast.info { background: var(--color-primary); }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

@media (max-width: 1024px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
  .mini-grid { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: 1fr; }
  .import-grid { grid-template-columns: 1fr; }
  .report-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .kpi-grid { grid-template-columns: 1fr; }
  .mini-grid { grid-template-columns: 1fr; }
}
</style>
