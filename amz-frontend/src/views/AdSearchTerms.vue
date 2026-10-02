<template>
  <div class="st-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">搜索词分析与广告规则</h1>
        <p class="hero-subtitle">搜索词报表 · 综合分析 · 词根聚类 · 出单词库 · ASIN 反查 · 规则判定</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：本页所有查询都以选中店铺为范围。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四件事先看，否则这张表会读错：① <b>搜索词报表没有自动来源</b>——SP-API 客户端只有活动、
        关键词、日报表三个通道，没有搜索词报表，唯一写入口就是本页「录入搜索词」，
        没录数据时分析与规则命中必然是空的；② <b>规则执行只产出建议</b>，
        暂停/否词/改价都不会下发到广告账号（全系统唯一的真实改价通道是后端按小时跑的分时调价）；
        ③ <b>没有调度器自动跑规则</b>，「自动」= 人工点「出建议」；
        ④ 规则类型只是分类标签，判定只看条件字段/比较/阈值与动作。
      </div>

      <div class="tabs">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <template v-if="currentShopId">
        <!-- ==================== 规则 ==================== -->
        <div v-if="tab === 'rules'" class="tab-panel" data-panel="rules">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">规则类型
                <select v-model="ruleType" @change="loadRules()">
                  <option value="">全部</option>
                  <option v-for="t in RULE_TYPE_PRESETS" :key="t" :value="t">{{ t }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="rules.loading.value" @click="loadRules()">刷新</button>
              <button v-if="rules.truncated.value" class="action-btn"
                      :disabled="rules.loading.value" @click="loadRules(true)">下一页</button>
              <button class="action-btn primary" @click="resetRuleForm()">新建规则</button>
              <span class="muted">{{ pagerText(rules) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>规则</th><th>类型</th><th>判定条件</th><th>动作</th><th>范围</th>
                  <th>窗口</th><th>优先级</th><th>状态</th><th>上次执行</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in rules.rows.value" :key="r.id">
                  <td>{{ r.ruleName }}</td>
                  <td class="mono">{{ r.ruleType }}</td>
                  <td class="mono">{{ conditionText(r) }}</td>
                  <td class="mono">{{ actionText(r) }}</td>
                  <td class="mono">{{ scopeText(r) }}</td>
                  <td>{{ r.timeWindow ?? '-' }} 天</td>
                  <td>{{ r.priority ?? '-' }}</td>
                  <td>
                    <span class="status-tag" :class="truthy(r.enabled) ? 'healthy' : 'unknown'">
                      {{ truthy(r.enabled) ? '启用' : '停用' }}
                    </span>
                  </td>
                  <td class="mono">{{ r.lastExecuted || '从未' }}</td>
                  <td class="row-actions">
                    <button class="action-btn" :disabled="busy" @click="askExecute(r)">出建议</button>
                    <button class="action-btn" :disabled="busy" @click="editRule(r)">编辑</button>
                    <button class="action-btn" :disabled="busy" @click="askToggle(r)">
                      {{ truthy(r.enabled) ? '停用' : '启用' }}
                    </button>
                    <button class="action-btn danger" :disabled="busy" @click="askDeleteRule(r)">删除</button>
                  </td>
                </tr>
                <tr v-if="!rules.loading.value && !rules.rows.value.length">
                  <td colspan="10" class="empty-row">
                    这家店还没有规则。注意：规则判的是搜索词报表，报表没有数据时任何规则都命中不了。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="ruleModal" class="table-card form-card">
            <h3 class="card-title">{{ ruleForm.id ? '编辑规则 #' + ruleForm.id : '新建规则' }}</h3>
            <div class="form-grid">
              <label class="field">规则名称 *<input v-model="ruleForm.ruleName" placeholder="高ACoS暂停" /></label>
              <label class="field">规则类型
                <input v-model="ruleForm.ruleType" :placeholder="RULE_TYPE_PRESETS.join(' / ')" />
              </label>
              <label class="field">条件字段
                <select v-model="ruleForm.conditionField">
                  <option v-for="f in RULE_CONDITION_FIELDS" :key="f" :value="f">{{ f }}</option>
                </select>
              </label>
              <label class="field">比较方式
                <select v-model="ruleForm.conditionOp">
                  <option v-for="o in RULE_CONDITION_OPS" :key="o" :value="o">{{ o }}</option>
                </select>
              </label>
              <label class="field">阈值 <span v-if="ruleForm.conditionOp === 'BETWEEN'">下界</span> *
                <input v-model="ruleForm.conditionValue" type="number" step="0.01" />
              </label>
              <label v-if="ruleForm.conditionOp === 'BETWEEN'" class="field">上界 *
                <input v-model="ruleForm.conditionValue2" type="number" step="0.01" />
              </label>
              <label class="field">动作
                <select v-model="ruleForm.action">
                  <option v-for="a in RULE_ACTIONS" :key="a" :value="a">{{ a }}</option>
                </select>
              </label>
              <label v-if="isPercentAction" class="field">动作参数（百分数）
                <input v-model="ruleForm.actionValue" type="number" step="0.01" />
              </label>
              <label class="field">作用范围
                <select v-model="ruleForm.scope">
                  <option value="">全店搜索词</option>
                  <option v-for="s in RULE_SCOPES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <label v-if="ruleForm.scope" class="field">
                {{ ruleForm.scope === 'CAMPAIGN' ? '活动 ID' : '关键词 ID' }} *
                <input v-model="ruleForm.scopeValue" :type="ruleForm.scope === 'KEYWORD' ? 'number' : 'text'" />
              </label>
              <label class="field">统计窗口（天）
                <input v-model="ruleForm.timeWindow" type="number" min="1" max="365" />
              </label>
              <label class="field">优先级
                <input v-model="ruleForm.priority" type="number" />
              </label>
              <label class="field checkbox">启用
                <input v-model="ruleForm.enabled" type="checkbox" />
              </label>
            </div>
            <p class="muted form-hint">
              后端会按「库里现值 + 本次填写」合并后校验：BETWEEN 必须给上界、限定范围必须给 ID，
              否则这条规则永远判不出来，所以直接被拒。
            </p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !ruleFormReady" @click="submitRule()">
                {{ ruleForm.id ? '保存修改' : '创建规则' }}
              </button>
              <button class="page-btn" @click="ruleModal = false">取消</button>
              <span v-if="!ruleFormReady" class="muted">按当前填写，后端会拒绝保存（见上面标 * 的字段）</span>
            </div>
          </div>

          <div v-if="execution" class="table-card result-card">
            <h3 class="card-title">
              {{ execution.ruleName || execution.ruleId }} 的执行结果
              <span class="status-tag" :class="execution.appliedToAdAccount ? 'urgent' : 'unknown'">
                {{ execution.appliedToAdAccount ? '已下发广告账号' : '仅建议，未下发广告账号' }}
              </span>
            </h3>
            <p v-if="execution.skipped" class="muted">{{ execution.skipped }}</p>
            <p v-else class="muted">命中 {{ execution.actionCount }} 条建议</p>
            <table v-if="execution.matchedActions && execution.matchedActions.length" class="data-table">
              <thead><tr><th>搜索词</th><th>命中值</th><th>动作</th><th>建议</th><th>已下发</th></tr></thead>
              <tbody>
                <tr v-for="(a, i) in execution.matchedActions" :key="'a' + i">
                  <td>{{ a.searchTerm }}</td>
                  <td>{{ asText(a.matchedValue) }}</td>
                  <td class="mono">{{ asText(a.action) }}</td>
                  <td>{{ asText(a.suggestion) }}</td>
                  <td>{{ a.applied === true ? '是' : '否' }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else-if="!execution.skipped" class="muted">
              没有任何搜索词命中——先确认报表里有数据，以及窗口天数是否覆盖到那些日期。
            </p>
            <p v-if="execution.note" class="muted note-line">{{ execution.note }}</p>
          </div>
        </div>

        <!-- ==================== 搜索词报表 ==================== -->
        <div v-if="tab === 'terms'" class="tab-panel" data-panel="terms">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">活动 ID
                <input v-model="termCampaign" @keyup.enter="loadTerms()" />
              </label>
              <label class="filter">搜索词包含
                <input v-model="termSearch" @keyup.enter="loadTerms()" />
              </label>
              <button class="action-btn" :disabled="terms.loading.value" @click="loadTerms()">查询</button>
              <button v-if="terms.truncated.value" class="action-btn"
                      :disabled="terms.loading.value" @click="loadTerms(true)">下一页</button>
              <button class="action-btn primary" @click="resetTermForm()">录入搜索词</button>
              <span class="muted">{{ pagerText(terms) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>日期</th><th>搜索词</th><th>活动</th><th>匹配</th><th>曝光</th>
                  <th>点击</th><th>花费</th><th>销售额</th><th>订单</th><th>ACoS%</th><th>CR%</th></tr>
              </thead>
              <tbody>
                <tr v-for="t in terms.rows.value" :key="t.id">
                  <td class="mono">{{ asText(t.reportDate) }}</td>
                  <td>{{ t.searchTerm }}</td>
                  <td class="mono">{{ asText(t.campaignId) }}</td>
                  <td class="mono">{{ asText(t.matchType) }}</td>
                  <td>{{ asText(t.impressions) }}</td>
                  <td>{{ asText(t.clicks) }}</td>
                  <td>{{ num(t.cost) }}</td>
                  <td>{{ num(t.sales) }}</td>
                  <td>{{ asText(t.orders) }}</td>
                  <td>{{ num(t.acos) }}</td>
                  <td>{{ num(t.cr) }}</td>
                </tr>
                <tr v-if="!terms.loading.value && !terms.rows.value.length">
                  <td colspan="11" class="empty-row">
                    没有搜索词报表数据。这不是「投放没有搜索词」——系统里没有 SP-API 搜索词报表通道，
                    需要用上面的「录入搜索词」把 Amazon 报表里的行录进来。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="termModal" class="table-card form-card">
            <h3 class="card-title">录入一行搜索词报表（店铺 {{ currentShopId }}）</h3>
            <div class="form-grid">
              <label class="field">活动 ID *<input v-model="termForm.campaignId" /></label>
              <label class="field">搜索词 *<input v-model="termForm.searchTerm" /></label>
              <label class="field">匹配方式
                <select v-model="termForm.matchType">
                  <option value="">未填</option>
                  <option v-for="m in MATCH_TYPES" :key="m" :value="m">{{ m }}</option>
                </select>
              </label>
              <label class="field">报表日期<input v-model="termForm.reportDate" type="date" /></label>
              <label class="field">曝光<input v-model="termForm.impressions" type="number" /></label>
              <label class="field">点击<input v-model="termForm.clicks" type="number" /></label>
              <label class="field">花费<input v-model="termForm.cost" type="number" step="0.01" /></label>
              <label class="field">销售额<input v-model="termForm.sales" type="number" step="0.01" /></label>
              <label class="field">订单<input v-model="termForm.orders" type="number" /></label>
            </div>
            <p class="muted form-hint">
              ACoS / CR / CTR / CPC 由后端按这一行的原始量重算，页面上的比值不是照着报表抄来的第二个口径。
            </p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !termFormReady" @click="submitTerm()">保存这一行</button>
              <button class="page-btn" @click="termModal = false">取消</button>
            </div>
          </div>
        </div>

        <!-- ==================== 综合分析 ==================== -->
        <div v-if="tab === 'analyze'" class="tab-panel" data-panel="analyze">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">窗口
                <select v-model="analyzeDays">
                  <option v-for="d in DAY_CHOICES" :key="d" :value="d">近 {{ d }} 天</option>
                </select>
              </label>
              <label class="filter">活动 ID（留空为全店）<input v-model="analyzeCampaign" /></label>
              <button class="action-btn" :disabled="busy" @click="loadAnalyze()">重新分析</button>
              <span class="muted">分析只读，不改任何数据</span>
            </div>

            <div v-if="analyze" class="metric-grid">
              <div class="metric"><span class="metric-label">扫描行数</span>
                <span class="metric-value">{{ analyze.scannedRows }}</span></div>
              <div class="metric"><span class="metric-label">总花费</span>
                <span class="metric-value">{{ num(analyze.totalCost) }}</span></div>
              <div class="metric"><span class="metric-label">总销售额</span>
                <span class="metric-value">{{ num(analyze.totalSales) }}</span></div>
              <div class="metric"><span class="metric-label">整体 ACoS%</span>
                <span class="metric-value">{{ num(analyze.overallAcos) }}</span></div>
              <div class="metric"><span class="metric-label">出单词</span>
                <span class="metric-value">{{ analyze.convertingTerms }}</span></div>
              <div class="metric"><span class="metric-label">高 ACoS 词（≥40%）</span>
                <span class="metric-value">{{ analyze.highAcosTerms }}</span></div>
              <div class="metric"><span class="metric-label">浪费词（有曝光无点击）</span>
                <span class="metric-value">{{ analyze.wasteTerms }}</span></div>
              <div class="metric"><span class="metric-label">浪费花费</span>
                <span class="metric-value">{{ num(analyze.wasteCost) }}</span></div>
            </div>
            <p v-if="analyze && analyze.scannedRows === 0" class="empty-inline">
              窗口内扫描到 0 行报表：上面这些 0 是「没有数据」，不是「表现良好」。
              先去「搜索词报表」录入，或把窗口拉到覆盖报表日期。
            </p>

            <table v-if="analyze && analyze.topConvertingTerms.length" class="data-table">
              <thead><tr><th>Top 出单词</th><th>订单</th><th>销售额</th><th>花费</th><th>ACoS%</th></tr></thead>
              <tbody>
                <tr v-for="(t, i) in analyze.topConvertingTerms" :key="'c' + i">
                  <td>{{ t.searchTerm }}</td>
                  <td>{{ asText(t.orders) }}</td>
                  <td>{{ num(t.sales) }}</td>
                  <td>{{ num(t.cost) }}</td>
                  <td>{{ num(t.acos) }}</td>
                </tr>
              </tbody>
            </table>
            <table v-if="analyze && analyze.topWasteTerms.length" class="data-table">
              <thead><tr><th>Top 浪费词</th><th>花费</th><th>曝光</th><th>点击</th></tr></thead>
              <tbody>
                <tr v-for="(t, i) in analyze.topWasteTerms" :key="'w' + i">
                  <td>{{ t.searchTerm }}</td>
                  <td>{{ num(t.cost) }}</td>
                  <td>{{ asText(t.impressions) }}</td>
                  <td>{{ asText(t.clicks) }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="!analyze" class="empty-inline">点「重新分析」开始。</p>
          </div>
        </div>

        <!-- ==================== 词根聚类 ==================== -->
        <div v-if="tab === 'cluster'" class="tab-panel" data-panel="cluster">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">窗口
                <select v-model="clusterDays">
                  <option v-for="d in DAY_CHOICES" :key="d" :value="d">近 {{ d }} 天</option>
                </select>
              </label>
              <label class="filter">活动 ID（留空为全店）<input v-model="clusterCampaign" /></label>
              <button class="action-btn" :disabled="busy" @click="loadCluster()">重新聚类</button>
              <span class="muted">词根规则：搜索词里长度 ≥3 的连续英文字母，只读</span>
            </div>
            <div v-if="cluster" class="metric-grid">
              <div class="metric"><span class="metric-label">扫描行数</span>
                <span class="metric-value">{{ cluster.scannedRows }}</span></div>
              <div class="metric"><span class="metric-label">词根数</span>
                <span class="metric-value">{{ cluster.totalClusters }}</span></div>
            </div>
            <table v-if="cluster && cluster.topClusters.length" class="data-table">
              <thead>
                <tr><th>词根</th><th>覆盖搜索词数</th><th>曝光</th><th>点击</th>
                  <th>花费</th><th>销售额</th><th>订单</th></tr>
              </thead>
              <tbody>
                <tr v-for="(c, i) in cluster.topClusters" :key="'k' + i">
                  <td class="mono">{{ c.root }}</td>
                  <td>{{ c.termCount }}</td>
                  <td>{{ c.totalImpressions }}</td>
                  <td>{{ c.totalClicks }}</td>
                  <td>{{ num(c.totalCost) }}</td>
                  <td>{{ num(c.totalSales) }}</td>
                  <td>{{ c.totalOrders }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else-if="cluster" class="empty-inline">
              没有聚类结果：窗口内没有可分词的搜索词（中文词根不会被这段规则切出来）。
            </p>
            <p v-else class="empty-inline">点「重新聚类」开始。</p>
          </div>
        </div>

        <!-- ==================== 出单词库 ==================== -->
        <div v-if="tab === 'converting'" class="tab-panel" data-panel="converting">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">提取窗口
                <select v-model="extractDays">
                  <option v-for="d in DAY_CHOICES" :key="d" :value="d">近 {{ d }} 天</option>
                </select>
              </label>
              <button class="action-btn" :disabled="busy" @click="askExtract()">按窗口重算词库</button>
              <label class="filter">按 ASIN 过滤<input v-model="convAsin" @keyup.enter="loadConverting()" /></label>
              <button class="action-btn" :disabled="converting.loading.value" @click="loadConverting()">查询</button>
              <button v-if="converting.truncated.value" class="action-btn"
                      :disabled="converting.loading.value" @click="loadConverting(true)">下一页</button>
              <span class="muted">{{ pagerText(converting) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>出单词</th><th>ASIN</th><th>活动</th><th>累计订单</th>
                  <th>累计销售额</th><th>累计花费</th><th>平均 ACoS%</th><th>首次/最近</th></tr>
              </thead>
              <tbody>
                <tr v-for="c in converting.rows.value" :key="c.id">
                  <td>{{ c.searchTerm }}</td>
                  <td class="mono">{{ asText(c.asin) }}</td>
                  <td class="mono">{{ asText(c.campaignId) }}</td>
                  <td>{{ asText(c.totalOrders) }}</td>
                  <td>{{ num(c.totalSales) }}</td>
                  <td>{{ num(c.totalCost) }}</td>
                  <td>{{ num(c.avgAcos) }}</td>
                  <td class="mono">{{ asText(c.firstSeen) }} / {{ asText(c.lastSeen) }}</td>
                </tr>
                <tr v-if="!converting.loading.value && !converting.rows.value.length">
                  <td colspan="8" class="empty-row">
                    词库为空。自动提取的行不带 ASIN（报表里没有 ASIN 维度），
                    「按 ASIN 过滤」只对导入过的数据有效。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== ASIN 反查 ==================== -->
        <div v-if="tab === 'asin'" class="tab-panel" data-panel="asin">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">ASIN *<input v-model="asinInput" @keyup.enter="loadAsin()" /></label>
              <button class="action-btn" :disabled="busy || !asinInput.trim()" @click="loadAsin()">反查</button>
              <button v-if="asinRows.truncated.value" class="action-btn"
                      :disabled="asinRows.loading.value" @click="loadAsin(true)">下一页</button>
              <button class="action-btn primary" @click="importModal = !importModal">批量导入</button>
              <span class="muted">{{ asinRows.rows.value.length ? pagerText(asinRows) : '按自然排名升序' }}</span>
            </div>

            <div v-if="importModal" class="form-card-inner">
              <p class="muted">
                这个接口是给外部抓取/导入脚本整批写快照用的：粘贴 JSON 数组，
                每行至少要有 asin 与 keyword；店铺会统一按当前选中的 {{ currentShopId }} 打标，
                同 (店铺, ASIN, 关键词) 已存在时覆盖为新快照。
              </p>
              <textarea v-model="asinJson" class="json-box" rows="6"
                        placeholder='[{"asin":"B0ABC12345","keyword":"yoga mat","organicRank":12,"searchVolume":40000}]'></textarea>
              <div class="form-actions">
                <button class="page-btn" :disabled="busy || !asinJson.trim()" @click="askImport()">导入</button>
                <button class="page-btn" @click="importModal = false">收起</button>
              </div>
            </div>

            <table class="data-table">
              <thead>
                <tr><th>ASIN</th><th>关键词</th><th>自然排名</th><th>广告排名</th>
                  <th>搜索量</th><th>相关性</th><th>已收录</th><th>检查日期</th></tr>
              </thead>
              <tbody>
                <tr v-for="k in asinRows.rows.value" :key="k.id">
                  <td class="mono">{{ k.asin }}</td>
                  <td>{{ k.keyword }}</td>
                  <td>{{ asText(k.organicRank) }}</td>
                  <td>{{ asText(k.adRank) }}</td>
                  <td>{{ asText(k.searchVolume) }}</td>
                  <td>{{ num(k.relevanceScore, 1) }}</td>
                  <td>{{ truthy(k.isIndexed) ? '是' : '否' }}</td>
                  <td class="mono">{{ asText(k.lastChecked) }}</td>
                </tr>
                <tr v-if="!asinRows.loading.value && !asinRows.rows.value.length">
                  <td colspan="8" class="empty-row">
                    {{ asinInput ? '这个 ASIN 没有反查记录：该表只由批量导入写入，系统不会自动抓排名。'
                                : '填 ASIN 后点「反查」。' }}
                  </td>
                </tr>
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
          <button class="page-btn" :disabled="busy" @click="runConfirm()">确认执行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import * as api from '@/api/adSearchTerms'
import {
  PERCENT_ACTIONS, RULE_ACTIONS, RULE_CONDITION_FIELDS, RULE_CONDITION_OPS,
  RULE_SCOPES, RULE_TYPE_PRESETS, asNumber, asText
} from '@/api/adSearchTerms'
import type {
  AdAsinKeyword, AdAutoRule, AdSearchTerm, AnalyzeResult, ClusterResult,
  ConvertingTerm, RuleExecutionResult
} from '@/api/adSearchTerms'

type TabKey = 'rules' | 'terms' | 'analyze' | 'cluster' | 'converting' | 'asin'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'rules', label: '规则' },
  { key: 'terms', label: '搜索词报表' },
  { key: 'analyze', label: '综合分析' },
  { key: 'cluster', label: '词根聚类' },
  { key: 'converting', label: '出单词库' },
  { key: 'asin', label: 'ASIN 反查' }
]

const DAY_CHOICES = [7, 14, 30]
const MATCH_TYPES = ['EXACT', 'PHRASE', 'BROAD']

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('rules')
const errors = ref<string[]>([])
const busy = ref(false)

const rules = mkList<AdAutoRule>()
const terms = mkList<AdSearchTerm>()
const converting = mkList<ConvertingTerm>()
const asinRows = mkList<AdAsinKeyword>()

const ruleType = ref('')
const termCampaign = ref('')
const termSearch = ref('')
const convAsin = ref('')
const asinInput = ref('')
const analyzeDays = ref<number | string>(7)
const analyzeCampaign = ref('')
const clusterDays = ref<number | string>(7)
const clusterCampaign = ref('')
const extractDays = ref<number | string>(7)

const analyze = ref<AnalyzeResult | null>(null)
const cluster = ref<ClusterResult | null>(null)
const execution = ref<RuleExecutionResult | null>(null)

const ruleModal = ref(false)
const termModal = ref(false)
const importModal = ref(false)
const asinJson = ref('')
const ruleForm = reactive<Record<string, any>>({})
const termForm = reactive<Record<string, any>>({})

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

function mkList<T>() {
  return {
    rows: ref<T[]>([]) as unknown as import('vue').Ref<T[]>,
    cursor: ref<string | null>(null),
    truncated: ref(false),
    loading: ref(false)
  }
}

interface ListState<T> {
  rows: { value: T[] }
  cursor: { value: string | null }
  truncated: { value: boolean }
  loading: { value: boolean }
}

const pagerText = (list: ListState<unknown>): string =>
  list.truncated.value
    ? `已加载 ${list.rows.value.length} 条 · 后端标记仍有下一页，本页不是全量`
    : `已加载 ${list.rows.value.length} 条`

const shop = () => refreshShop()
const truthy = (v: unknown) => v === true || v === 1 || v === '1'
const num = (v: unknown, digits = 2) => asNumber(v, digits) ?? '-'
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const isPercentAction = computed(() => PERCENT_ACTIONS.includes(ruleForm.action))

/** 与后端同一套跨字段规则：缺了就标出按钮不可用，省一次往返，但后端仍然是权威 */
const ruleFormReady = computed(() => {
  if (!String(ruleForm.ruleName ?? '').trim()) return false
  if (!String(ruleForm.ruleType ?? '').trim()) return false
  if (ruleForm.conditionValue === '' || ruleForm.conditionValue === null) return false
  if (ruleForm.conditionOp === 'BETWEEN'
    && (ruleForm.conditionValue2 === '' || ruleForm.conditionValue2 === null)) return false
  if (ruleForm.scope && !String(ruleForm.scopeValue ?? '').trim()) return false
  return true
})
const termFormReady = computed(() =>
  Boolean(String(termForm.campaignId ?? '').trim() && String(termForm.searchTerm ?? '').trim()))

const conditionText = (r: AdAutoRule) =>
  `${r.conditionField} ${r.conditionOp} ${r.conditionValue}`
  + (r.conditionOp === 'BETWEEN' ? ` ~ ${r.conditionValue2 ?? '缺上界'}` : '')
const actionText = (r: AdAutoRule) =>
  `${r.action}${r.actionValue !== null && r.actionValue !== undefined ? ` ${r.actionValue}%` : ''}`
const scopeText = (r: AdAutoRule) =>
  r.scope && r.scopeValue ? `${r.scope}=${r.scopeValue}` : '全店搜索词'

/** 数值字段：空串按未填处理，不能把 '' 发给后端变成 0 */
const numeric = (v: unknown): number | undefined => {
  if (v === '' || v === null || v === undefined) return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

const loadList = async <T>(
  list: ListState<T>,
  label: string,
  fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  append: boolean,
  clearErrors = true
) => {
  const shopId = shop()
  if (!shopId) return
  if (!append) {
    list.rows.value = []
    list.cursor.value = null
    if (clearErrors) errors.value = []
  }
  list.loading.value = true
  try {
    const res = await fetcher(append ? list.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    list.rows.value = append ? [...list.rows.value, ...batch] : batch
    list.truncated.value = res._page ? res._page.truncated : false
    list.cursor.value = res._page ? res._page.nextCursor : null
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    list.loading.value = false
  }
}

const loadRules = (append = false, clearErrors = true) => loadList(rules, '规则列表', (cursor) =>
  api.listRules(shop(), {
    ruleType: ruleType.value || undefined,
    size: 20,
    cursor
  }), append, clearErrors)

const loadTerms = (append = false) => loadList(terms, '搜索词报表', (cursor) =>
  api.listSearchTerms(shop(), {
    campaignId: termCampaign.value || undefined,
    searchTerm: termSearch.value || undefined,
    size: 20,
    cursor
  }), append)

const loadConverting = (append = false) => loadList(converting, '出单词库', (cursor) =>
  api.listConvertingTerms(shop(), { asin: convAsin.value || undefined, size: 20, cursor }), append)

const loadAsin = (append = false) => {
  const asin = asinInput.value.trim()
  if (!asin) return Promise.resolve()
  return loadList(asinRows, 'ASIN 反查', (cursor) =>
    api.reverseLookupAsin(shop(), asin, { size: 20, cursor }), append)
}

const run = async <T>(label: string, fn: () => Promise<ApiResponse<T>>): Promise<T | null> => {
  busy.value = true
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return null
    }
    return res.data as T
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
    return null
  } finally {
    busy.value = false
  }
}

const loadAnalyze = async () => {
  const data = await run('综合分析', () => api.analyzeSearchTerms(shop(), {
    campaignId: analyzeCampaign.value || undefined,
    days: numeric(analyzeDays.value)
  }))
  analyze.value = data
}

const loadCluster = async () => {
  const data = await run('词根聚类', () => api.clusterSearchTerms(shop(), {
    campaignId: clusterCampaign.value || undefined,
    days: numeric(clusterDays.value)
  }))
  cluster.value = data
}

const resetRuleForm = () => {
  Object.assign(ruleForm, {
    id: undefined,
    ruleName: '',
    ruleType: 'KEYWORD_PAUSE',
    conditionField: 'ACOS',
    conditionOp: 'GT',
    conditionValue: '',
    conditionValue2: '',
    action: 'PAUSE',
    actionValue: '',
    scope: '',
    scopeValue: '',
    timeWindow: 7,
    priority: 0,
    enabled: true
  })
  ruleModal.value = true
}

const editRule = (r: AdAutoRule) => {
  resetRuleForm()
  Object.assign(ruleForm, {
    id: r.id,
    ruleName: r.ruleName,
    ruleType: r.ruleType,
    conditionField: r.conditionField,
    conditionOp: r.conditionOp,
    conditionValue: r.conditionValue,
    conditionValue2: r.conditionValue2 ?? '',
    action: r.action,
    actionValue: r.actionValue ?? '',
    scope: r.scope ?? '',
    scopeValue: r.scopeValue ?? '',
    timeWindow: r.timeWindow ?? 7,
    priority: r.priority ?? 0,
    enabled: truthy(r.enabled)
  })
}

const submitRule = async () => {
  const body: Record<string, any> = {
    ruleName: String(ruleForm.ruleName).trim(),
    ruleType: String(ruleForm.ruleType).trim(),
    conditionField: ruleForm.conditionField,
    conditionOp: ruleForm.conditionOp,
    conditionValue: numeric(ruleForm.conditionValue),
    action: ruleForm.action,
    actionValue: numeric(ruleForm.actionValue),
    timeWindow: numeric(ruleForm.timeWindow),
    priority: numeric(ruleForm.priority),
    enabled: ruleForm.enabled ? 1 : 0
  }
  if (ruleForm.conditionOp === 'BETWEEN') body.conditionValue2 = numeric(ruleForm.conditionValue2)
  if (ruleForm.scope) {
    body.scope = ruleForm.scope
    body.scopeValue = String(ruleForm.scopeValue).trim()
  }
  const id = ruleForm.id as number | undefined
  const saved = await run(id ? '保存规则' : '创建规则', () => {
    if (id) return api.updateRule(id, body)
    return api.createRule({ ...body, shopId: Number(shop()) } as AdAutoRule)
  })
  if (!saved) return
  ruleModal.value = false
  execution.value = null
  await loadRules()
}

const askToggle = (r: AdAutoRule) => {
  const next = !truthy(r.enabled)
  confirmBox.value = {
    title: `${next ? '启用' : '停用'}规则「${r.ruleName}」`,
    detail: next
      ? '启用后这条规则会参与「批量出建议」：命中哪些搜索词、建议做什么都会出现在结果里，'
        + '但动作不会下发到广告账号。'
      : '停用后批量执行会跳过它；已经给出的建议不会因为停用而撤销。',
    run: async () => {
      const ok = await run('启停规则', () => api.toggleRule(r.id as number, next))
      if (ok === null) return
      await loadRules()
    }
  }
}

const askDeleteRule = (r: AdAutoRule) => {
  confirmBox.value = {
    title: `删除规则「${r.ruleName}」`,
    detail: '删除是物理删除，没有回收站，也不会保留历史建议。确认前请核对规则 ID '
      + `${r.id}（${r.ruleType}）。`,
    run: async () => {
      const ok = await run('删除规则', () => api.deleteRule(r.id as number))
      if (ok === null) return
      if (execution.value && execution.value.ruleId === r.id) execution.value = null
      await loadRules()
    }
  }
}

const askExecute = (r: AdAutoRule) => {
  confirmBox.value = {
    title: `按「${r.ruleName}」出建议`,
    detail: `将扫描 ${scopeText(r)} 在最近 ${r.timeWindow ?? '-'} 天窗口内的搜索词报表，`
      + '命中后只产出建议清单：不会暂停投放、不会改价、不会加否词。'
      + '这一行的「上次执行」时间会随之更新。',
    run: async () => {
      const data = await run('规则建议', () => api.executeRule(r.id as number))
      if (!data) return
      execution.value = data
      if (data.appliedToAdAccount === true) {
        pushError('后端把 appliedToAdAccount 置为真：本页「仅建议」的说明已不成立，请核对执行链路')
      }
      // 刷新「上次执行」时不能顺手清空错误条，否则上面那条越界警告会跟着列表一起消失
      await loadRules(false, false)
    }
  }
}

const askExtract = () => {
  confirmBox.value = {
    title: `按近 ${extractDays.value} 天重算出单词库`,
    detail: '提取会按这个窗口重算并覆盖写词库：库里已有同 (活动, 搜索词) 的行会被更新，'
      + '不会重复累加订单。窗口选小了会把更早的出单词留在库里。',
    run: async () => {
      const data = await run('出单词提取', () =>
        api.extractConvertingTerms(shop(), numeric(extractDays.value) ?? 7))
      if (data === null) return
      await loadConverting()
    }
  }
}

const askImport = () => {
  confirmBox.value = {
    title: '批量导入 ASIN 反查快照',
    detail: '按 (店铺, ASIN, 关键词) 覆盖写入，已有行会被这次提交的值替换。'
      + '整批里任何一行缺 ASIN 或关键词，后端会拒绝整批（不会只写一半）。',
    run: async () => {
      const shopId = Number(shop())
      let parsed: unknown
      try {
        parsed = JSON.parse(asinJson.value)
      } catch (e) {
        pushError(`ASIN 导入：JSON 解析失败（${e instanceof Error ? e.message : '格式错误'}）`)
        return
      }
      if (!Array.isArray(parsed)) {
        pushError('ASIN 导入：需要的是 JSON 数组，例如 [{...},{...}]')
        return
      }
      const rows = (parsed as AdAsinKeyword[]).map((row) => ({ ...row, shopId }))
      const saved = await run('ASIN 导入', () => api.saveAsinKeywords(rows))
      if (saved === null) return
      asinJson.value = ''
      importModal.value = false
      await loadAsin()
    }
  }
}

const resetTermForm = () => {
  Object.assign(termForm, {
    campaignId: termCampaign.value || '',
    searchTerm: '',
    matchType: '',
    reportDate: '',
    impressions: '',
    clicks: '',
    cost: '',
    sales: '',
    orders: ''
  })
  termModal.value = true
}

const submitTerm = async () => {
  const payload = {
    shopId: Number(shop()),
    campaignId: String(termForm.campaignId).trim(),
    searchTerm: String(termForm.searchTerm).trim(),
    matchType: termForm.matchType || undefined,
    reportDate: termForm.reportDate || undefined,
    impressions: numeric(termForm.impressions),
    clicks: numeric(termForm.clicks),
    cost: numeric(termForm.cost),
    sales: numeric(termForm.sales),
    orders: numeric(termForm.orders)
  } as AdSearchTerm
  const saved = await run('录入搜索词', () => api.saveSearchTerm(payload))
  if (!saved) return
  termModal.value = false
  await loadTerms()
}

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const loaded = new Set<TabKey>()
const gotoTab = async (key: TabKey) => {
  tab.value = key
  if (!currentShopId || loaded.has(key)) return
  loaded.add(key)
  if (key === 'rules') await loadRules()
  if (key === 'terms') await loadTerms()
  if (key === 'converting') await loadConverting()
}

onMounted(() => {
  void gotoTab('rules')
})
</script>

<style scoped>
.st-page { background: var(--color-background); }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .field input, .field select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.cell-clip { max-width: 18rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-card { margin-bottom: 1rem; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0 0 0.5rem; display: flex; align-items: center; gap: 0.5rem; }
.form-card, .result-card { padding: 0.875rem 1rem 1rem; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.field.checkbox { flex-direction: row; align-items: center; }
.form-hint { margin: 0.625rem 0 0; line-height: 1.5; }
.form-actions { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.75rem; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr)); gap: 0.625rem; margin: 0.75rem 1rem; }
.metric { background: var(--color-surface-variant); border-radius: var(--radius-md); padding: 0.5rem 0.625rem; display: flex; flex-direction: column; gap: 0.25rem; }
.metric-label { font-size: 0.75rem; color: var(--color-muted); }
.metric-value { font-size: 1.125rem; color: var(--color-on-surface); }
.empty-inline { color: var(--color-muted); font-size: 0.8125rem; padding: 0 1rem 0.75rem; margin: 0; }
.row-actions { white-space: nowrap; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.json-box { width: 100%; box-sizing: border-box; font-family: var(--font-mono, monospace); font-size: 0.75rem; padding: 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); }
.form-card-inner { border-top: 1px dashed var(--color-border); margin: 0 1rem 0.75rem; padding-top: 0.75rem; }
.note-line { padding: 0.5rem 0 0; margin: 0; line-height: 1.5; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.empty-row { color: var(--color-muted); font-size: 0.8125rem; padding: 1rem; }
</style>
