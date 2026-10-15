<template>
  <div class="ad-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <!-- hero section - design-taste-frontend 约束：headline ≤2 行，subtext ≤20 词，垂直堆叠 -->
      <div class="hero-section">
        <h1 class="hero-title">广告管理</h1>
        <p class="hero-subtitle">广告活动、ACoS 监控与分时调价</p>
      </div>

      <!-- 广告类型 Tab -->
      <div class="ad-tab-bar">
        <div
          v-for="tab in adTabs"
          :key="tab.key"
          class="ad-tab-item"
          :class="{ active: activeAdTab === tab.key }"
          @click="switchAdTab(tab.key)"
        >
          {{ tab.label }}
        </div>
      </div>

      <!-- 骨架屏：ACoS 概览卡片 + 表格行形状（技能 4.5 Loading） -->
      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="acos-overview">
          <div v-for="i in 4" :key="i" class="acos-card">
            <div class="skeleton sk-line sk-line-sm"></div>
            <div class="skeleton sk-line sk-line-lg sk-line-center"></div>
          </div>
        </div>
        <div class="table-card sk-table-card">
          <div v-for="i in 5" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
      </div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看广告数据。
      </div>

      <!-- 演示数据必须显式标识；生产模式无数据时展示未同步空态 -->
      <div v-if="adDemoMode" v-show="activeAdTab !== 'DSP'" class="demo-banner">
        演示模式：当前展示示例数据，不代表真实广告 API 返回结果。
      </div>
      <div v-else-if="currentShopId && !overviewIsLive" v-show="activeAdTab !== 'DSP'" class="sync-empty">
        暂无已同步的广告报表，请先配置 API 并执行日报同步。
      </div>

      <!-- ACoS 概览（全类型共用）（加载时仅显示骨架） -->
      <template v-if="!loading">
      <div v-show="activeAdTab !== 'DSP' && (adDemoMode || overviewIsLive)" class="acos-overview">
        <div class="acos-card">
          <div class="acos-label">整体 ACoS</div>
          <div class="acos-value" :class="acosLevel">{{ acosData.totalAcos }}%</div>
          <div class="acos-desc">{{ acosLevelText }}</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">广告花费</div>
          <div class="acos-value">${{ acosData.totalSpend }}</div>
          <div class="acos-desc">近 7 天</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">广告销售额</div>
          <div class="acos-value">${{ acosData.totalSales }}</div>
          <div class="acos-desc">近 7 天</div>
        </div>
        <div class="acos-card">
          <div class="acos-label">ROAS</div>
          <div class="acos-value">{{ acosData.roas }}x</div>
          <div class="acos-desc">投资回报率</div>
        </div>
      </div>

      <!-- ACoS 趋势 -->
      <div v-show="activeAdTab !== 'DSP'" class="chart-card">
        <h3>近 14 天 ACoS 趋势 <span v-if="!trendIsLive && acosTrend.length > 0" class="mock-badge">示例数据</span></h3>
        <div class="line-chart">
          <div v-if="acosTrend.length === 0" class="chart-empty">暂无趋势数据</div>
          <template v-else>
            <svg viewBox="0 0 600 200" class="chart-svg">
              <polyline :points="acosTrendPoints" class="trend-line" fill="none" stroke-width="2" />
              <circle v-for="(pt, i) in acosTrendDots" :key="i" :cx="pt.x" :cy="pt.y" r="3" class="trend-dot" />
            </svg>
            <div class="chart-labels">
              <span v-for="(d, i) in acosTrend" :key="i">{{ d.day }}</span>
            </div>
          </template>
        </div>
      </div>

      <!-- 活动列表（SP） -->
      <div v-show="activeAdTab === 'SP'" class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th v-if="canEditCampaign"><input type="checkbox" :checked="allChecked" @change="toggleAllCampaigns(($event.target as HTMLInputElement).checked)" aria-label="全选活动" /></th>
              <th>活动名称</th>
              <th>状态</th>
              <th>日预算</th>
              <th>花费</th>
              <th>销售额</th>
              <th>ACoS</th>
              <th v-if="canEditCampaign">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="camp in campaigns" :key="camp.id">
              <td v-if="canEditCampaign"><input type="checkbox" :value="camp.id" v-model="checkedCampaignIds" aria-label="选择活动" /></td>
              <td>{{ camp.name }}</td>
              <td><span class="status-tag" :class="camp.active ? 'active' : 'paused'">{{ camp.active ? '运行中' : '已暂停' }}</span></td>
              <td>${{ camp.budget }}</td>
              <td>{{ money(camp.spend) }}</td>
              <td>{{ money(camp.sales) }}</td>
              <td :class="spAcosClass(camp.acos)">{{ pct(camp.acos) }}</td>
              <td v-if="canEditCampaign">
                <button class="action-btn" @click="openCampaignEdit(camp.campaignId)">编辑</button>
              </td>
            </tr>
            <tr v-if="!loading && campaigns.length === 0">
              <td :colspan="canEditCampaign ? 8 : 6" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:chart-line" width="32" class="empty-icon" />
                  <span>暂无广告活动数据</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="col-note" data-note="sp-source">
          花费 / 销售额 / ACoS 取自本页已拉到的日报聚合行，按 campaignId 关联过来；
          活动没有日报时显示「—」。日预算仍是广告活动扩展表的真列。
        </p>

        <!-- 活动写操作：新建 / 批量新建 / 批量改状态。
             默认收起：大部分时候只是看数，四个按钮常驻会把表格挤成两行。 -->
        <div v-if="canEditCampaign" class="ops-card" data-panel="campaign-ops">
          <div class="section-header">
            <h3>活动写操作</h3>
            <button class="action-btn" @click="campaignOpsOpen = !campaignOpsOpen">
              {{ campaignOpsOpen ? '收起' : '展开' }}
            </button>
          </div>
          <template v-if="campaignOpsOpen">
            <p class="ops-note">
              这些按钮直接改<strong>真实广告平台</strong>的活动（<code>POST/PUT /ad/campaigns</code>），
              不是本地草稿：暂停后平台立刻停止投放，无法从本系统撤销。仅 OPERATOR / ADMIN 可点。
            </p>
            <div class="ops-row">
              <button class="action-btn" @click="openCampaignCreate()">新建活动</button>
              <button class="action-btn" @click="batchDialog.visible = true; batchDialog.parseError = ''">批量新建</button>
              <button
                class="action-btn cancel"
                :disabled="checkedCampaignIds.length === 0"
                @click="askBatchStatus('PAUSED')"
              >
                批量暂停（{{ checkedCampaignIds.length }}）
              </button>
              <button
                class="action-btn"
                :disabled="checkedCampaignIds.length === 0"
                @click="askBatchStatus('ENABLED')"
              >
                批量启用（{{ checkedCampaignIds.length }}）
              </button>
            </div>
            <div v-if="campaignError" class="ops-error" role="alert">{{ campaignError }}</div>
          </template>
        </div>
      </div>

      <!-- SB 广告素材管理 -->
      <div v-show="activeAdTab === 'SB'" class="ext-section">
        <div class="section-header">
          <h3>SB 广告素材管理</h3>
          <button class="action-btn" @click="openCreativeDialog()">+ 新建素材</button>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>活动ID</th><th>素材类型</th><th>Headline</th><th>品牌名</th>
                <th>视频/Logo</th><th>落地页</th><th>ASIN</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cr in creatives" :key="cr.id">
                <td>{{ cr.campaignId }}</td>
                <td>{{ cr.creativeType || '-' }}</td>
                <td>{{ cr.headline || '-' }}</td>
                <td>{{ cr.brandName || '-' }}</td>
                <td>{{ cr.videoUrl || cr.logoUrl || '-' }}</td>
                <td>{{ cr.landingPageUrl || '-' }}</td>
                <td>{{ cr.asin || '-' }}</td>
                <td><span class="status-tag" :class="creativeStatusClass(cr.status)">{{ cr.status }}</span></td>
                <td>
                  <button v-if="canEditCampaign" class="action-btn" @click="openCreativeEdit(cr)">编辑</button>
                  <button v-if="cr.status === 'PENDING'" class="action-btn" @click="reviewCreative(cr.id!, 'APPROVED')">通过</button>
                  <button v-if="cr.status === 'PENDING'" class="action-btn cancel" @click="reviewCreative(cr.id!, 'REJECTED')">拒绝</button>
                </td>
              </tr>
              <tr v-if="creatives.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:image-multiple-outline" width="32" class="empty-icon" /><span>暂无素材，请输入活动ID查询</span></div></td></tr>
            </tbody>
          </table>
        </div>
        <div v-if="creativeError" class="ops-error" role="alert">{{ creativeError }}</div>
        <div class="ext-toolbar">
          <input v-model="creativeQuery.campaignId" placeholder="输入活动ID查询素材" />
          <button class="action-btn" @click="loadCreatives">查询</button>
        </div>
      </div>

      <!-- SD 受众定向管理 -->
      <div v-show="activeAdTab === 'SD'" class="ext-section">
        <div class="section-header">
          <h3>SD 受众定向管理</h3>
          <button class="action-btn" @click="openTargetingDialog()">+ 新建定向</button>
        </div>
        <div class="table-card">
          <table class="data-table">
            <thead>
              <tr>
                <th>活动ID</th><th>定向类型</th><th>定向值</th><th>竞价</th>
                <th>曝光</th><th>点击</th><th>花费</th><th>销售额</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="tg in targetings" :key="tg.id">
                <td>{{ tg.campaignId }}</td>
                <td>{{ tg.targetingType || '-' }}</td>
                <td>{{ tg.targetingValue || '-' }}</td>
                <td>${{ tg.bid || 0 }}</td>
                <td>{{ tg.impressions || 0 }}</td>
                <td>{{ tg.clicks || 0 }}</td>
                <td>${{ tg.spend || 0 }}</td>
                <td>${{ tg.sales || 0 }}</td>
                <td>
                  <button v-if="canEditCampaign" class="action-btn" @click="openTargetingEdit(tg)">编辑</button>
                  <button class="action-btn cancel" @click="removeTargeting(tg.id!)">删除</button>
                </td>
              </tr>
              <tr v-if="targetings.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:target-variant" width="32" class="empty-icon" /><span>暂无定向规则，请输入活动ID查询</span></div></td></tr>
            </tbody>
          </table>
        </div>
        <div v-if="targetingError" class="ops-error" role="alert">{{ targetingError }}</div>
        <div class="ext-toolbar">
          <input v-model="targetingQuery.campaignId" placeholder="输入活动ID查询定向" />
          <button class="action-btn" @click="loadTargetings">查询</button>
        </div>
      </div>

      <!-- DSP 批量报表 -->
      <div v-show="activeAdTab === 'DSP'" class="ext-section">
        <div class="section-header">
          <h3>DSP 批量报表（按广告类型汇总）</h3>
          <button class="action-btn" @click="loadSummaryByType">刷新</button>
        </div>
        <div class="summary-grid">
          <div v-for="(sum, type) in summaryByType" :key="type" class="summary-card">
            <div class="summary-type">{{ type }}</div>
            <div class="summary-row"><span>曝光</span><b>{{ sum.impressions }}</b></div>
            <div class="summary-row"><span>点击</span><b>{{ sum.clicks }}</b></div>
            <div class="summary-row"><span>花费</span><b>${{ sum.spend }}</b></div>
            <div class="summary-row"><span>销售额</span><b>${{ sum.sales }}</b></div>
            <div class="summary-row"><span>订单</span><b>{{ sum.orders }}</b></div>
            <div class="summary-row"><span>ACoS</span><b :class="acosClass(sum.acos)">{{ sum.acos }}%</b></div>
            <div class="summary-row"><span>ROAS</span><b>{{ sum.roas }}x</b></div>
          </div>
        </div>
      </div>
      </template>

      <!-- 日报回补：上面那句「请先执行日报同步」以前没有对应入口，这里就是那个入口。
           刻意不带 ext-section 类：页面上既有 .ext-section:visible 这类活动区定位器，
           一个常驻可见的同类块会把它们变成多义匹配。 -->
      <div v-if="!loading" class="ops-card" data-panel="sync">
        <div class="section-header">
          <h3>日报同步回补</h3>
          <button class="action-btn" @click="opsOpen = !opsOpen">{{ opsOpen ? '收起' : '展开' }}</button>
        </div>
        <template v-if="opsOpen">
          <p class="ops-note">
            趋势与概览都来自 <code>amz_ad_daily_report</code>，此前只有后端定时任务会写这张表。
            这里可以按店铺手工回补：同步是「读广告 API + 本地覆盖写」，不改平台数据，但会消耗调用配额，
            单次窗口最多 {{ AD_SYNC_MAX_DAYS }} 天。
          </p>
          <div class="ops-row">
            <label class="ops-filter">回补窗口
              <select v-model="syncDays">
                <option v-for="d in SYNC_DAY_CHOICES" :key="d" :value="d">近 {{ d }} 天</option>
              </select>
            </label>
            <button class="action-btn" :disabled="!currentShopId || syncing" @click="runSync(false)">
              {{ syncing ? '同步中' : '同步当前店铺' }}
            </button>
            <button class="action-btn" :disabled="syncing" @click="runSync(true)">
              {{ syncing ? '同步中' : '同步全部店铺' }}
            </button>
            <span class="ops-hint">全部店铺那个重载只有 ADMIN 能过；OPERATOR 点了会得到后端拒绝，不会退化成单店同步。</span>
          </div>
          <div v-if="syncErrors.length" class="ops-error" role="alert">{{ syncErrors.join('；') }}</div>
          <div v-if="lastSync" class="ops-result">
            <span class="ops-scope">{{ lastSyncScope }}</span>
            窗口 {{ lastSync.days }} 天：尝试 {{ lastSync.attempted }} · 成功 {{ lastSync.succeeded }}
            · 失败 {{ lastSync.failed }} · 跳过 {{ lastSync.skipped }} · 落库 {{ lastSync.upserted }} 行
            <span v-if="lastSync.metadataWarnings > 0" class="ops-warn">
              另有 {{ lastSync.metadataWarnings }} 条活动元数据回填告警
            </span>
            <p v-if="lastSync.failed > 0" class="ops-sub">
              有店铺没同步成功，上面的落库行数不代表窗口已补齐——失败原因看后端日志里的 shopId。
            </p>
            <p v-else-if="lastSync.succeeded === 0" class="ops-sub">
              本次没有任何店铺成功：多为没有广告授权或该店没有活动，跳过（skipped）不等于同步完成。
            </p>
          </div>
        </template>
      </div>

      <!-- 素材弹窗（新建 / 编辑同一套表单；编辑时按 id 走 PUT） -->
      <div v-if="creativeDialog.visible" class="modal-mask" @click.self="creativeDialog.visible = false">
        <div class="modal">
          <h3>{{ creativeDialog.form.id ? '编辑 SB 广告素材' : '新建 SB 广告素材' }}</h3>
          <div class="form-grid">
            <label>活动ID<input v-model="creativeDialog.form.campaignId" /></label>
            <label>素材类型
              <select v-model="creativeDialog.form.creativeType">
                <option value="VIDEO">视频</option>
                <option value="IMAGE">图片</option>
                <option value="STORE_SPOTLIGHT">品牌旗舰店</option>
                <option value="CUSTOM_HEADLINE">自定义标题</option>
              </select>
            </label>
            <label>Headline<input v-model="creativeDialog.form.headline" /></label>
            <label>品牌名<input v-model="creativeDialog.form.brandName" /></label>
            <label>Logo URL<input v-model="creativeDialog.form.logoUrl" /></label>
            <label>视频 URL<input v-model="creativeDialog.form.videoUrl" /></label>
            <label>落地页<input v-model="creativeDialog.form.landingPageUrl" /></label>
            <label>ASIN<input v-model="creativeDialog.form.asin" /></label>
          </div>
          <p class="modal-note">
            素材的审核状态走行内的「通过 / 拒绝」，这里只改主数据；活动ID 必须属于本店，否则后端拒绝。
          </p>
          <div v-if="creativeError" class="ops-error" role="alert">{{ creativeError }}</div>
          <div class="modal-actions">
            <button class="action-btn cancel" @click="creativeDialog.visible = false">取消</button>
            <button class="action-btn" @click="submitCreative">保存</button>
          </div>
        </div>
      </div>

      <!-- 定向弹窗（新建 / 编辑同一套表单；编辑时按 id 走 PUT） -->
      <div v-if="targetingDialog.visible" class="modal-mask" @click.self="targetingDialog.visible = false">
        <div class="modal">
          <h3>{{ targetingDialog.form.id ? '编辑 SD 受众定向' : '新建 SD 受众定向' }}</h3>
          <div class="form-grid">
            <label>活动ID<input v-model="targetingDialog.form.campaignId" /></label>
            <label>定向类型
              <select v-model="targetingDialog.form.targetingType">
                <option value="CONTEXTUAL">Contextual</option>
                <option value="REMARKETING">Remarketing</option>
                <option value="AUDIENCE">Audience</option>
                <option value="LOOKALIKE">Lookalike</option>
              </select>
            </label>
            <label>定向值<input v-model="targetingDialog.form.targetingValue" placeholder="ASIN/Category/Interest" /></label>
            <label>竞价<input v-model.number="targetingDialog.form.bid" type="number" /></label>
          </div>
          <p class="modal-note">
            曝光 / 点击 / 花费 / 销售额由日报同步回填，这里改不动；删除走行内按钮。
          </p>
          <div v-if="targetingError" class="ops-error" role="alert">{{ targetingError }}</div>
          <div class="modal-actions">
            <button class="action-btn cancel" @click="targetingDialog.visible = false">取消</button>
            <button class="action-btn" @click="submitTargeting">保存</button>
          </div>
        </div>
      </div>

      <!-- 活动弹窗：新建 / 编辑（campaignId 编辑态只读——后端明确拒改） -->
      <div v-if="campaignDialog.visible" class="modal-mask" @click.self="campaignDialog.visible = false">
        <div class="modal">
          <h3>{{ campaignDialog.mode === 'edit' ? '编辑广告活动' : '新建广告活动' }}</h3>
          <div class="form-grid">
            <label>活动ID
              <input
                v-model="campaignDialog.form.campaignId"
                :readonly="campaignDialog.mode === 'edit'"
                :class="{ 'input-locked': campaignDialog.mode === 'edit' }"
              />
            </label>
            <label>活动名称<input v-model="campaignDialog.form.campaignName" /></label>
            <label>广告类型
              <select v-model="campaignDialog.form.adType">
                <option value="SP">SP 商品推广</option>
                <option value="SB">SB 品牌推广</option>
                <option value="SD">SD 展示推广</option>
                <option value="DSP">DSP 批量报表</option>
              </select>
            </label>
            <label>日预算<input v-model="campaignDialog.form.budget" type="number" placeholder="留空表示不修改" /></label>
            <label>状态
              <select v-model="campaignDialog.form.status">
                <option value="ENABLED">启用</option>
                <option value="PAUSED">暂停</option>
                <option value="ARCHIVED">归档</option>
              </select>
            </label>
          </div>
          <p class="modal-note">
            编辑态下<strong>活动ID 只读</strong>——后端拒改它（会破坏与日报行的关联）。
            日预算留空表示保持原值，填 0 是把预算设成 0 元而不是「不改」。
            暂停 / 归档是真实平台操作，改完立刻生效。
          </p>
          <div v-if="campaignError" class="ops-error" role="alert">{{ campaignError }}</div>
          <div class="modal-actions">
            <button class="action-btn cancel" @click="campaignDialog.visible = false">取消</button>
            <button class="action-btn" :disabled="!campaignFormReady" @click="submitCampaign">保存</button>
          </div>
        </div>
      </div>

      <!-- 批量新建弹窗 -->
      <div v-if="batchDialog.visible" class="modal-mask" @click.self="batchDialog.visible = false">
        <div class="modal">
          <h3>批量新建广告活动</h3>
          <p class="modal-note">
            一行一个活动，格式「活动ID, 活动名称[, 预算[, 状态]]」，例如
            <code>C-4001, 春季促销, 80, ENABLED</code>。
            所有行会在<strong>一次请求</strong>里提交到 <code>/ad/campaigns/batch</code>，
            任一行不合法后端会整批拒绝——不会留下建了一半的活动。这里只建 SP 活动。
          </p>
          <textarea v-model="batchDialog.text" class="batch-box" rows="8" placeholder="C-4001, 春季促销, 80&#10;C-4002, 夏季清仓"></textarea>
          <div v-if="batchDialog.parseError" class="ops-error" role="alert">{{ batchDialog.parseError }}</div>
          <div v-if="campaignError" class="ops-error" role="alert">{{ campaignError }}</div>
          <div class="modal-actions">
            <button class="action-btn cancel" @click="batchDialog.visible = false">取消</button>
            <button class="action-btn" :disabled="!batchDialog.text.trim()" @click="submitBatchCreate">保存</button>
          </div>
        </div>
      </div>

      <!-- 批量改状态二次确认 -->
      <div v-if="batchStatusDialog.visible" class="modal-mask" @click.self="batchStatusDialog.visible = false">
        <div class="modal">
          <h3>确认{{ batchStatusDialog.status === 'PAUSED' ? '暂停' : '启用' }} {{ checkedCampaignIds.length }} 个活动</h3>
          <p class="modal-note">
            这会<strong>立刻</strong>改动真实广告平台的投放状态，
            {{ batchStatusDialog.status === 'PAUSED' ? '暂停后这些活动马上停止消耗预算，本系统无法帮你撤销' : '启用后这些活动马上开始消耗预算' }}。
            若只想临时停投，优先用单个活动的编辑而不是批量操作。
          </p>
          <div v-if="campaignError" class="ops-error" role="alert">{{ campaignError }}</div>
          <div class="modal-actions">
            <button class="action-btn cancel" @click="batchStatusDialog.visible = false">取消</button>
            <button class="action-btn cancel" @click="submitBatchStatus">
              确认{{ batchStatusDialog.status === 'PAUSED' ? '暂停' : '启用' }}
            </button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getAdReports, getAdTrend, syncAdReports, syncAllAdReports, AD_SYNC_MAX_DAYS } from '@/api/ad'
import type { AdOverview, AcosTrendItem, AdCampaign, AdSyncSummary, AdReportRow } from '@/api/ad'
import * as AdExt from '@/api/ad-ext'
import type { AdCampaignExt, AdCreative, AdTargeting, AdSummary, AdType } from '@/api/ad-ext'
import { canUseRole } from '@/utils/identity'
import { useShopGuard } from '@/composables/useShopGuard'

const loading = ref(false)
const adDemoMode = import.meta.env.VITE_AD_DEMO_MODE === 'true'

// 当前选中店铺（B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值）
const { currentShopId, refreshShop } = useShopGuard()

// ===== 日报回补 =====
const SYNC_DAY_CHOICES = [1, 7, 14, AD_SYNC_MAX_DAYS]
const opsOpen = ref(false)
const syncing = ref(false)
const syncDays = ref<number | string>(7)
const syncErrors = ref<string[]>([])
const lastSync = ref<AdSyncSummary | null>(null)
const lastSyncScope = ref('')

const runSync = async (allShops: boolean) => {
  const shopId = refreshShop()
  if (!allShops && !shopId) return
  const raw = Number(syncDays.value)
  const days = Number.isFinite(raw) && raw > 0 ? Math.trunc(raw) : 7
  syncing.value = true
  syncErrors.value = []
  lastSync.value = null
  lastSyncScope.value = allShops ? '全部店铺' : `店铺 ${shopId}`
  try {
    // 全店那个重载靠「不带 shopId」区分，带上就退化成单店同步
    const res = allShops ? await syncAllAdReports(days) : await syncAdReports(shopId as string, days)
    if (res?.code !== 200 || !res.data) {
      syncErrors.value = [`同步未执行：${res?.message || '后端返回非 200'}`]
      return
    }
    lastSync.value = res.data
    if (res.data.failed > 0) {
      syncErrors.value = [`有 ${res.data.failed} 个店铺同步失败，落库行数不代表窗口已补齐`]
    }
    // 真的写进了日报表才重拉本页数据，否则空转一次没有意义的请求
    if (res.data.upserted > 0) await loadOverview()
  } catch (e) {
    syncErrors.value = [`同步调用失败：${e instanceof Error ? e.message : '未知错误'}`]
  } finally {
    syncing.value = false
  }
}

// 广告类型 Tab
const adTabs = [
  { key: 'SP' as AdType, label: 'SP 商品推广' },
  { key: 'SB' as AdType, label: 'SB 品牌推广' },
  { key: 'SD' as AdType, label: 'SD 展示推广' },
  { key: 'DSP' as AdType, label: 'DSP 批量报表' }
]
const activeAdTab = ref<AdType>('SP')
const switchAdTab = (tab: AdType) => {
  activeAdTab.value = tab
  if (tab === 'DSP') loadSummaryByType()
}

// 趋势是否为真实数据；只有显式 demo 模式才允许用 mock 填充空数据
const trendIsLive = ref(false)
const overviewIsLive = ref(false)

// 加载指定广告类型的趋势（非空才替换 mock）
// 请求序号守卫：快速切换 Tab 时丢弃过期响应，避免旧类型数据覆盖新 Tab 图表
let trendSeq = 0
const loadTrend = async (shopId: number | string, adType: AdType) => {
  const seq = ++trendSeq
  try {
    // 趋势来自 /ad/trend 日报表聚合（空数组说明日报表无数据，保留 mock）
    const tres = await getAdTrend(shopId, 14, adType)
    if (seq !== trendSeq) return
    if (tres?.code === 200 && Array.isArray(tres.data) && tres.data.length > 0) {
      acosTrend.value = tres.data.map((d) => ({ day: String(d.day), value: Number(d.value) || 0 }))
      trendIsLive.value = true
    } else {
      trendIsLive.value = false
      acosTrend.value = adDemoMode ? [...mockTrend] : []
      if (!tres || tres.code !== 200) console.warn('[AdManager] 趋势返回异常', tres)
    }
  } catch (e) {
    if (seq !== trendSeq) return
    trendIsLive.value = false
    acosTrend.value = adDemoMode ? [...mockTrend] : []
    console.warn('[AdManager] 趋势调用失败', e)
  }
}

// 切换 SP/SB/SD 时按类型重拉趋势（DSP 页不展示趋势图，跳过）
watch(activeAdTab, (tab) => {
  const shopId = refreshShop()
  if (!shopId || tab === 'DSP') return
  loadTrend(shopId, tab)
})

// 降级用的 mock 数据
const mockOverview: AdOverview = { totalAcos: 24.9, totalSpend: '1,098.50', totalSales: '4,412.80', roas: '4.02' }
const mockTrend: AcosTrendItem[] = [
  { day: '7/1', value: 28 }, { day: '7/2', value: 26 }, { day: '7/3', value: 30 },
  { day: '7/4', value: 25 }, { day: '7/5', value: 23 }, { day: '7/6', value: 26 },
  { day: '7/7', value: 24.9 }
]
const mockCampaigns: AdCampaign[] = [
  { id: 1, campaignId: 'MOCK-SP-1', name: '关键词-蓝牙耳机-US', active: true, budget: 50, spend: 32.50, sales: 158.00, acos: 20.6 },
  { id: 2, campaignId: 'MOCK-SP-2', name: '自动广告-全店铺', active: true, budget: 100, spend: 68.30, sales: 210.50, acos: 32.4 },
  { id: 3, campaignId: 'MOCK-SP-3', name: '品牌广告-Shop B', active: false, budget: 30, spend: 12.00, sales: 28.50, acos: 42.1 },
  { id: 4, campaignId: 'MOCK-SP-4', name: '商品推广-新品', active: true, budget: 40, spend: 15.70, sales: 89.20, acos: 17.6 }
]

const emptyOverview: AdOverview = { totalAcos: 0, totalSpend: '0.00', totalSales: '0.00', roas: '0.00' }
const acosData = ref<AdOverview>(adDemoMode ? { ...mockOverview } : { ...emptyOverview })
const acosTrend = ref<AcosTrendItem[]>(adDemoMode ? [...mockTrend] : [])
const campaigns = ref<AdCampaign[]>(adDemoMode ? [...mockCampaigns] : [])

/** 「没有数据」和「值为 0」必须长得不一样：$0.00 会被读成这个活动没花钱。 */
const money = (v: number | null): string =>
  v === null ? '—' : '$' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pct = (v: number | null): string => (v === null ? '—' : `${v}%`)
const spAcosClass = (v: number | null): string =>
  v === null ? 'acos-unknown' : v > 50 ? 'acos-bad' : v > 35 ? 'acos-warn' : 'acos-good'

const acosLevel = computed(() => {
  const a = acosData.value.totalAcos
  if (a < 25) return 'good'
  if (a < 35) return 'warn'
  return 'bad'
})
const acosLevelText = computed(() => {
  const a = acosData.value.totalAcos
  if (a < 25) return '优秀 (<25%)'
  if (a < 35) return '健康 (25-35%)'
  return '预警 (>35%)'
})

// 趋势纵轴动态刻度：真实 ACoS 可能超出 mock 的 [15,40] 区间，固定刻度会把线裁出画布；
// 单点数据居中绘制（此前 len===1 时 step 除零产生 NaN，整条线消失）
const trendBounds = computed(() => {
  let lo = Infinity, hi = -Infinity
  for (const d of acosTrend.value) {
    if (d.value < lo) lo = d.value
    if (d.value > hi) hi = d.value
  }
  if (!isFinite(lo)) return { min: 0, max: 40 }
  const pad = Math.max((hi - lo) * 0.2, 1)
  return { min: Math.max(0, lo - pad), max: hi + pad }
})
const acosTrendPoints = computed(() => {
  const n = acosTrend.value.length
  if (n === 0) return ''
  const { min, max } = trendBounds.value
  const w = 600, h = 180, pad = 10
  const step = n <= 1 ? 0 : (w - pad * 2) / (n - 1)
  return acosTrend.value.map((d, i) => {
    const x = n <= 1 ? w / 2 : pad + i * step
    const y = h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
    return `${x},${y}`
  }).join(' ')
})
const acosTrendDots = computed(() => {
  const n = acosTrend.value.length
  if (n === 0) return []
  const { min, max } = trendBounds.value
  const w = 600, h = 180, pad = 10
  const step = n <= 1 ? 0 : (w - pad * 2) / (n - 1)
  return acosTrend.value.map((d, i) => ({
    x: n <= 1 ? w / 2 : pad + i * step,
    y: h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
  }))
})

const loadOverview = async () => {
  // 未选择店铺时不发请求
  const shopId = refreshShop()
  if (!shopId) {
    loading.value = false
    return
  }
  loading.value = true
  const toNum = (v: unknown): number => {
    const n = Number(v)
    return isNaN(n) ? 0 : n
  }
  // 金额存裸数字串（模板统一加 $ 前缀；此前 live 路径自带 $ 导致渲染成 $$，mock 路径无此问题）
  const fmtMoney = (n: number): string =>
    n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  // 日报行要在前后两个 try 之间共用，所以声明在 try 外面：
  // 放进第一个 try 会让第二个 try 抛 ReferenceError，loading 永不复位，表现就是表格整块消失。
  let reportRows: AdReportRow[] = []
  try {
    // 后端 GET /ad/reports 返回 AdReport 行数组（无总览包装），前端聚合总览。
    // 行同时留给 SP 表格按 campaignId 关联：ext 表的 spend/sales/acos 三列无人写入，
    // 真实模式下恒为 0，只有日报里有数。
    const res = await getAdReports(shopId)
    if (res?.code === 200 && Array.isArray(res.data) && res.data.length > 0) {
      overviewIsLive.value = true
      const rows = res.data
      reportRows = rows
      const totalSpend = rows.reduce((s, r) => s + toNum(r.cost), 0)
      const totalSales = rows.reduce((s, r) => s + toNum(r.sales), 0)
      acosData.value = {
        totalAcos: totalSales > 0 ? Number(((totalSpend / totalSales) * 100).toFixed(1)) : 0,
        totalSpend: fmtMoney(totalSpend),
        totalSales: fmtMoney(totalSales),
        roas: totalSpend > 0 ? (totalSales / totalSpend).toFixed(2) : '0.00'
      }
    } else {
      overviewIsLive.value = false
      acosData.value = adDemoMode ? { ...mockOverview } : { ...emptyOverview }
      if (!res || res.code !== 200) {
        console.warn('[AdManager] 返回数据异常', res)
      }
    }
  } catch (e) {
    overviewIsLive.value = false
    acosData.value = adDemoMode ? { ...mockOverview } : { ...emptyOverview }
    console.warn('[AdManager] API 调用失败', e)
  }
  try {
    // 活动列表改用 /ad/campaigns/list（含名称/状态/预算，AdReport 行无这些字段）
    const cres = await AdExt.listCampaigns(shopId, 'SP')
    if (cres?.code === 200 && Array.isArray(cres.data) && cres.data.length > 0) {
      // 原始行留给编辑/批量勾选（表格里展示的是聚合后的视图模型，没有 budget 之外的原始字段）
      campaignRows.value = cres.data
      checkedCampaignIds.value = []
      // 同一活动可能有多天日报，先按 campaignId 汇总再关联
      const byCampaign = new Map<string, { cost: number; sales: number }>()
      reportRows.forEach((r) => {
        const key = String(r.campaignId ?? '')
        if (!key) return
        const acc = byCampaign.get(key) || { cost: 0, sales: 0 }
        acc.cost += toNum(r.cost)
        acc.sales += toNum(r.sales)
        byCampaign.set(key, acc)
      })
      campaigns.value = cres.data.map((c, i) => {
        const key = String(c.campaignId ?? '')
        const agg = byCampaign.get(key) || null
        return {
          id: Number(c.id ?? i + 1),
          campaignId: key,
          name: c.campaignName || key,
          active: c.status === 'ENABLED',
          budget: toNum(c.budget),
          spend: agg ? agg.cost : null,
          sales: agg ? agg.sales : null,
          // 销售额为 0 时 ACoS 无定义：显示 — 而不是 0%（0% 会被读成免费流量）
          acos: agg && agg.sales > 0 ? Number(((agg.cost / agg.sales) * 100).toFixed(1)) : null
        }
      })
    } else {
      campaigns.value = adDemoMode ? [...mockCampaigns] : []
      campaignRows.value = []
      checkedCampaignIds.value = []
      if (!cres || cres.code !== 200) {
        console.warn('[AdManager] 活动列表返回异常', cres)
      }
    }
  } catch (e) {
    campaigns.value = adDemoMode ? [...mockCampaigns] : []
    campaignRows.value = []
    checkedCampaignIds.value = []
    console.warn('[AdManager] 活动列表调用失败', e)
  }
  await loadTrend(shopId, activeAdTab.value)
  loading.value = false
}

onMounted(loadOverview)

// ===== SB 广告素材 =====
const creatives = ref<AdCreative[]>([])
const creativeQuery = reactive<{ campaignId: string }>({ campaignId: '' })
const creativeError = ref('')
const creativeDialog = reactive<{ visible: boolean; form: AdCreative }>({
  visible: false,
  form: { campaignId: '', creativeType: 'VIDEO', headline: '', brandName: '', status: 'PENDING' }
})
const openCreativeDialog = () => {
  creativeDialog.form = { campaignId: creativeQuery.campaignId, creativeType: 'VIDEO', headline: '', brandName: '', status: 'PENDING' }
  creativeDialog.visible = true
}
const loadCreatives = async () => {
  const shopId = refreshShop()
  if (!shopId || !creativeQuery.campaignId) return
  try {
    const res = await AdExt.listCreatives(shopId, creativeQuery.campaignId)
    if (res?.code === 200) creatives.value = res.data || []
  } catch (e) { console.warn('[AdManager] 加载素材失败', e) }
}
const reviewCreative = async (id: number, status: 'APPROVED' | 'REJECTED') => {
  const shopId = refreshShop()
  if (!shopId) return
  try {
    await AdExt.reviewCreative(shopId, id, status)
    await loadCreatives()
  } catch (e) { console.warn('[AdManager] 审核素材失败', e) }
}
const creativeStatusClass = (s?: string) => {
  if (s === 'APPROVED') return 'active'
  if (s === 'REJECTED') return 'paused'
  return 'pending'
}

// ===== SD 受众定向 =====
const targetings = ref<AdTargeting[]>([])
const targetingQuery = reactive<{ campaignId: string }>({ campaignId: '' })
const targetingError = ref('')
const targetingDialog = reactive<{ visible: boolean; form: AdTargeting }>({
  visible: false,
  form: { campaignId: '', targetingType: 'CONTEXTUAL', targetingValue: '', bid: 1 }
})
const openTargetingDialog = () => {
  targetingDialog.form = { campaignId: targetingQuery.campaignId, targetingType: 'CONTEXTUAL', targetingValue: '', bid: 1 }
  targetingDialog.visible = true
}
const loadTargetings = async () => {
  const shopId = refreshShop()
  if (!shopId || !targetingQuery.campaignId) return
  try {
    const res = await AdExt.listTargeting(shopId, targetingQuery.campaignId)
    if (res?.code === 200) targetings.value = res.data || []
  } catch (e) { console.warn('[AdManager] 加载定向失败', e) }
}
const removeTargeting = async (id: number) => {
  const shopId = refreshShop()
  if (!shopId) return
  try {
    await AdExt.deleteTargeting(shopId, id)
    await loadTargetings()
  } catch (e) { console.warn('[AdManager] 删除定向失败', e) }
}

// ===== DSP 批量报表 =====
const summaryByType = ref<Record<string, AdSummary>>({})
const loadSummaryByType = async () => {
  // 未选择店铺时不发请求
  const shopId = refreshShop()
  if (!shopId) return
  try {
    const res = await AdExt.getSummaryByType(shopId)
    if (res?.code === 200 && res.data) summaryByType.value = res.data
  } catch (e) { console.warn('[AdManager] 加载批量报表失败', e) }
}
const acosClass = (acos?: number) => {
  if (acos == null) return ''
  if (acos < 25) return 'acos-good'
  if (acos < 35) return 'acos-warn'
  return 'acos-bad'
}

// ===== 活动 CRUD（曾零调用：createCampaign/updateCampaign/batchCreateCampaigns/batchUpdateStatus）=====
// 这四个 api 函数定义完整但页面从未调用（只在测试里被 mock），SP 活动表长期只读——
// 换预算、暂停活动都要去卖家后台。后端四个写端点全部 @RequireRole(OPERATOR/ADMIN)，
// 入口门控沿用采购页取消付款 / 搜索词批量出建议的先例：VIEWER 隐藏，未知角色放行让后端做权威判定。
const canEditCampaign = canUseRole(['OPERATOR', 'ADMIN'])

/** 原始活动行：编辑/批量勾选都要 id 与 campaignId，而表格里展示的是聚合后的视图模型 */
const campaignRows = ref<AdCampaignExt[]>([])
const checkedCampaignIds = ref<number[]>([])
const campaignOpsOpen = ref(false)
const campaignError = ref('')
const campaignDialog = reactive<{
  visible: boolean
  mode: 'create' | 'edit'
  form: { id?: number; campaignId: string; campaignName: string; adType: AdType; status: 'ENABLED' | 'PAUSED' | 'ARCHIVED'; budget: number | string }
}>({
  visible: false,
  mode: 'create',
  form: { campaignId: '', campaignName: '', adType: 'SP', status: 'ENABLED', budget: '' }
})
const batchDialog = reactive<{ visible: boolean; text: string; parseError: string }>({
  visible: false, text: '', parseError: ''
})
const batchStatusDialog = reactive<{ visible: boolean; status: 'PAUSED' | 'ENABLED'; resultNote: string }>({
  visible: false, status: 'PAUSED', resultNote: ''
})

const allChecked = computed(
  () => campaignRows.value.length > 0 && checkedCampaignIds.value.length === campaignRows.value.length
)
const toggleAllCampaigns = (checked: boolean) => {
  checkedCampaignIds.value = checked ? campaignRows.value.map((c) => c.id!) : []
}

/** 与后端 validateCreateFields 同口径的前置校验：省一次往返，但后端仍是权威 */
const campaignFormReady = computed(() => {
  const f = campaignDialog.form
  return Boolean(String(f.campaignId).trim() && String(f.campaignName).trim() && String(f.adType).trim())
})

const openCampaignCreate = () => {
  campaignDialog.mode = 'create'
  campaignDialog.visible = true
  campaignDialog.form = { campaignId: '', campaignName: '', adType: 'SP', status: 'ENABLED', budget: '' }
}

const openCampaignEdit = (campaignId: string) => {
  // 表格行（视图模型）与 campaignRows（原始行）是两个数组，按下标关联是一条没人守的不变量：
  // 汇总、排序、分页任一改动都会让「编辑」改到别的活动上。用活动 ID 找回自己的行。
  const row = campaignRows.value.find((c) => String(c.campaignId ?? '') === campaignId)
  if (!row) {
    campaignError.value = `找不到活动 ${campaignId}，请重新查询后再编辑`
    return
  }
  campaignDialog.mode = 'edit'
  campaignDialog.visible = true
  campaignDialog.form = {
    id: row.id,
    campaignId: String(row.campaignId ?? ''),
    campaignName: String(row.campaignName ?? ''),
    adType: (row.adType as AdType) || 'SP',
    // 库里理论上可能有关联状态机之外的值（历史数据/后端扩枚举），
    // 落回后端认可的三态而不是原样带进表单——带进去会在保存时被拒且看不出原因。
    status: row.status === 'PAUSED' || row.status === 'ARCHIVED' ? row.status : 'ENABLED',
    budget: row.budget ?? ''
  }
}

const submitCampaign = async () => {
  const shopId = refreshShop()
  if (!shopId || !campaignFormReady.value) return
  campaignError.value = ''
  const f = campaignDialog.form
  const budget = String(f.budget).trim()
  // 预算留空 = 不修改（后端 NOT_NULL 策略下 null 字段不写），
  // 不能填 0：0 预算会把活动打到花不出钱，那不是「保持原值」。
  const body: AdCampaignExt = {
    campaignId: f.campaignId.trim(),
    campaignName: f.campaignName.trim(),
    adType: f.adType,
    status: f.status,
    ...(budget ? { budget: Number(budget) } : {})
  }
  if (campaignDialog.mode === 'edit') body.id = f.id
  try {
    const res = campaignDialog.mode === 'edit'
      ? await AdExt.updateCampaign(shopId, body)
      : await AdExt.createCampaign(shopId, body)
    if (res?.code !== 200) {
      campaignError.value = `保存活动：${res?.message || '后端返回非 200'}`
      return
    }
    campaignDialog.visible = false
    await loadOverview()
  } catch (e) {
    campaignError.value = `保存活动：${e instanceof Error ? e.message : '调用失败'}`
  }
}

/**
 * 批量新建：一行一个活动，`活动ID, 活动名称[, 预算[, 状态]]`。
 * 走 /ad/campaigns/batch（一个数组体的单次请求），不是逐条 create——
 * 批量建 10 个活动逐条发要么慢要么中途失败留下半个批次。
 */
const parseBatchRows = (): AdCampaignExt[] | null => {
  const lines = batchDialog.text.split('\n').map((l) => l.trim()).filter(Boolean)
  if (lines.length === 0) {
    batchDialog.parseError = '请至少输入一行'
    return null
  }
  const rows: AdCampaignExt[] = []
  for (let i = 0; i < lines.length; i++) {
    const parts = lines[i].split(',').map((p) => p.trim())
    if (parts.length < 2 || !parts[0] || !parts[1]) {
      batchDialog.parseError = `第 ${i + 1} 行格式应为「活动ID, 活动名称」：${lines[i]}`
      return null
    }
    const budget = parts[2] ? Number(parts[2]) : undefined
    if (parts[2] && !Number.isFinite(budget)) {
      batchDialog.parseError = `第 ${i + 1} 行预算不是数字：${parts[2]}`
      return null
    }
    const rawStatus = parts[3] ? parts[3].toUpperCase() : undefined
    const status = rawStatus === 'ENABLED' || rawStatus === 'PAUSED' || rawStatus === 'ARCHIVED'
      ? rawStatus
      : undefined
    if (rawStatus && !status) {
      batchDialog.parseError = `第 ${i + 1} 行状态仅支持 ENABLED / PAUSED / ARCHIVED：${parts[3]}`
      return null
    }
    rows.push({
      campaignId: parts[0],
      campaignName: parts[1],
      adType: 'SP',
      ...(budget !== undefined ? { budget } : {}),
      ...(status ? { status } : {})
    })
  }
  return rows
}

const submitBatchCreate = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  batchDialog.parseError = ''
  campaignError.value = ''
  const rows = parseBatchRows()
  if (!rows) return
  try {
    const res = await AdExt.batchCreateCampaigns(shopId, rows)
    if (res?.code !== 200) {
      campaignError.value = `批量新建：${res?.message || '后端返回非 200'}`
      return
    }
    batchDialog.visible = false
    batchDialog.text = ''
    await loadOverview()
  } catch (e) {
    campaignError.value = `批量新建：${e instanceof Error ? e.message : '调用失败'}`
  }
}

/**
 * 批量改状态：一个 PUT 按 ids 改，是不可逆的真实平台写操作，故二次确认。
 * 确认按钮文案随目标状态变（暂停/启用），不让用户对着同一个「确认」猜自己批准了什么。
 */
const askBatchStatus = (status: 'PAUSED' | 'ENABLED') => {
  if (checkedCampaignIds.value.length === 0) return
  batchStatusDialog.status = status
  batchStatusDialog.resultNote = ''
  batchStatusDialog.visible = true
}

const submitBatchStatus = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  batchStatusDialog.resultNote = ''
  campaignError.value = ''
  const ids = [...checkedCampaignIds.value]
  try {
    const res = await AdExt.batchUpdateStatus(shopId, ids, batchStatusDialog.status)
    if (res?.code !== 200) {
      campaignError.value = `批量改状态：${res?.message || '后端返回非 200'}`
      batchStatusDialog.visible = false
      return
    }
    // 后端按 ids 原序返回更新后的行；回来的比勾选的少，
    // 说明有 id 被归属校验拦下了——报「没有活动被更新」而不是「已暂停 N 个」。
    const returned = Array.isArray(res.data) ? res.data : []
    batchStatusDialog.visible = false
    checkedCampaignIds.value = []
    if (returned.length < ids.length) {
      campaignError.value = `批量改状态：勾选 ${ids.length} 个活动，只有 ${returned.length} 个被更新，` +
        '其余不在本店或已归档（归属校验在后端）'
      return
    }
    await loadOverview()
  } catch (e) {
    campaignError.value = `批量改状态：${e instanceof Error ? e.message : '调用失败'}`
    batchStatusDialog.visible = false
  }
}

// 素材/定向编辑：新建已有入口，补「编辑」走对应的 PUT（后端按 id + shopId 双重定位）
const openCreativeEdit = (row: AdCreative) => {
  creativeDialog.form = {
    id: row.id,
    campaignId: String(row.campaignId ?? ''),
    creativeType: row.creativeType || 'VIDEO',
    headline: row.headline || '',
    brandName: row.brandName || '',
    logoUrl: row.logoUrl || '',
    videoUrl: row.videoUrl || '',
    landingPageUrl: row.landingPageUrl || '',
    asin: row.asin || '',
    status: row.status || 'PENDING'
  }
  creativeDialog.visible = true
}

const submitCreative = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  creativeError.value = ''
  const isEdit = creativeDialog.form.id != null
  try {
    const res = isEdit
      ? await AdExt.updateCreative(shopId, creativeDialog.form)
      : await AdExt.createCreative(shopId, creativeDialog.form)
    if (res?.code !== 200) {
      creativeError.value = `${isEdit ? '保存素材' : '新建素材'}：${res?.message || '后端返回非 200'}`
      return
    }
    creativeDialog.visible = false
    if (creativeQuery.campaignId) await loadCreatives()
  } catch (e) {
    creativeError.value = `${isEdit ? '保存素材' : '新建素材'}：${e instanceof Error ? e.message : '调用失败'}`
  }
}

const openTargetingEdit = (row: AdTargeting) => {
  targetingDialog.form = {
    id: row.id,
    campaignId: String(row.campaignId ?? ''),
    targetingType: row.targetingType || 'CONTEXTUAL',
    targetingValue: row.targetingValue || '',
    bid: row.bid ?? 1
  }
  targetingDialog.visible = true
}

const submitTargeting = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  targetingError.value = ''
  const isEdit = targetingDialog.form.id != null
  try {
    const res = isEdit
      ? await AdExt.updateTargeting(shopId, targetingDialog.form)
      : await AdExt.createTargeting(shopId, targetingDialog.form)
    if (res?.code !== 200) {
      targetingError.value = `${isEdit ? '保存定向' : '新建定向'}：${res?.message || '后端返回非 200'}`
      return
    }
    targetingDialog.visible = false
    if (targetingQuery.campaignId) await loadTargetings()
  } catch (e) {
    targetingError.value = `${isEdit ? '保存定向' : '新建定向'}：${e instanceof Error ? e.message : '调用失败'}`
  }
}
</script>

<style scoped>
.ad-page { background: var(--color-background); }
/* 页头/主区/表格/分页等公共样式已收敛至全局 style.css */

/* 骨架屏 */
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 55%; height: 1.5rem; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }
.sk-line-center { margin: 0.5rem auto 0; }

.acos-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1rem; }
.acos-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; text-align: center; box-shadow: var(--shadow-sm); }
.acos-label { font-size: 0.8125rem; color: var(--color-muted); margin-bottom: 0.25rem; }
.acos-value { font-size: 1.75rem; font-weight: 700; color: var(--color-on-surface); margin: 0.25rem 0; font-variant-numeric: tabular-nums; }
.acos-value.good { color: var(--color-success); }
.acos-value.warn { color: var(--color-warning-dark); }
.acos-value.bad { color: var(--color-error); }
.acos-desc { font-size: 0.75rem; color: var(--color-muted); }

.chart-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; margin-bottom: 1rem; box-shadow: var(--shadow-sm); }
.chart-card h3 { font-size: 1rem; font-weight: 600; margin: 0 0 0.5rem 0; color: var(--color-on-surface); }
.demo-banner, .sync-empty { padding: 0.75rem 1rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.demo-banner { background: var(--color-warning-light); color: var(--color-warning-dark); }
.sync-empty { background: var(--color-muted-light); color: var(--color-muted); }
.chart-empty { min-height: 10rem; display: flex; align-items: center; justify-content: center; color: var(--color-muted); font-size: 0.875rem; }

/* 折线颜色走 token，双主题自动适配 */
.trend-line { stroke: var(--color-primary); }
.trend-dot { fill: var(--color-primary); }
.line-chart { width: 100%; }
.chart-svg { width: 100%; height: auto; }
.chart-labels { display: flex; justify-content: space-between; margin-top: 0.5rem; }
.chart-labels span { font-size: 0.6875rem; color: var(--color-muted); }

.status-tag.active { background: var(--color-success-light); color: var(--color-success); }
.status-tag.paused { background: var(--color-muted-light); color: var(--color-muted); }
.status-tag.pending { background: var(--color-warning-light); color: var(--color-warning-dark); }

.acos-good { color: var(--color-success); font-weight: 600; }
.acos-warn { color: var(--color-warning-dark); font-weight: 600; }
.acos-bad { color: var(--color-error); font-weight: 600; }
.acos-unknown { color: var(--color-muted); }
.col-note { padding: 0.625rem 1rem; margin: 0; font-size: 0.75rem; color: var(--color-muted); line-height: 1.5; }

.action-btn {
  padding: 0.25rem 0.75rem;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 0.75rem;
}

.action-btn.cancel {
  background: var(--color-light-red);
  color: var(--color-error);
}

/* 广告类型 Tab */
.ad-tab-bar { display: flex; gap: 0.25rem; margin-bottom: 1rem; border-bottom: 1px solid var(--color-border); }
.ad-tab-item { padding: 0.5rem 0.75rem; cursor: pointer; color: var(--color-muted); font-size: 0.875rem; border-bottom: 2px solid transparent; transition: all 0.2s; }
.ad-tab-item:hover { color: var(--color-on-surface); }
.ad-tab-item.active { color: var(--color-primary); border-bottom-color: var(--color-primary); font-weight: 600; }

/* 扩展区块 */
.ext-section { margin-top: 1rem; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.section-header h3 { font-size: 1rem; font-weight: 600; margin: 0; }
.ops-card { margin-top: 1rem; }
.ops-note { font-size: 0.8125rem; color: var(--color-muted); line-height: 1.6; margin: 0 0 0.625rem; }
.ops-note code { font-family: var(--font-mono, monospace); }
.ops-row { display: flex; align-items: center; gap: 0.625rem; flex-wrap: wrap; }
.ops-filter { display: inline-flex; align-items: center; gap: 0.375rem; font-size: 0.8125rem; color: var(--color-muted); }
.ops-filter select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); }
.ops-hint { font-size: 0.75rem; color: var(--color-muted); }
.ops-error { margin-top: 0.625rem; padding: 0.5rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-light-red); color: var(--color-error); font-size: 0.8125rem; }
.ops-result { margin-top: 0.625rem; padding: 0.5rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-surface-variant); font-size: 0.8125rem; color: var(--color-on-surface); }
.ops-scope { font-weight: 600; margin-right: 0.25rem; }
.ops-warn { color: var(--color-warning-dark); }
.ops-sub { margin: 0.375rem 0 0; font-size: 0.75rem; color: var(--color-muted); }
.ext-toolbar { display: flex; gap: 0.5rem; margin-top: 0.75rem; align-items: center; }
.ext-toolbar input { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; flex: 1; max-width: 320px; }

/* DSP 批量报表 */
.summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 1rem; }
.summary-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; box-shadow: var(--shadow-sm); }
.summary-type { font-size: 1rem; font-weight: 700; color: var(--color-primary); margin-bottom: 0.5rem; padding-bottom: 0.5rem; border-bottom: 1px solid var(--color-border); }
.summary-row { display: flex; justify-content: space-between; padding: 0.25rem 0; font-size: 0.8125rem; }
.summary-row span { color: var(--color-muted); }
.summary-row b { color: var(--color-on-surface); font-weight: 600; }

/* 弹窗 */
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; width: 90%; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 0.5rem 0; font-size: 1.125rem; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid input, .form-grid select { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.modal-note { font-size: 0.75rem; color: var(--color-muted); line-height: 1.6; margin: 0.625rem 0 0; }
.modal-note code, .ops-note code { font-family: var(--font-mono, monospace); }
.batch-box { width: 100%; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-family: var(--font-mono, monospace); font-size: 0.8125rem; resize: vertical; }
.input-locked { background: var(--color-muted-light); color: var(--color-muted); cursor: not-allowed; }
/* 勾选框列：收窄，别把活动名称挤走 */
.data-table th:first-child:has(input[type="checkbox"]), .data-table td:first-child:has(input[type="checkbox"]) { width: 2.25rem; text-align: center; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .acos-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .acos-overview { grid-template-columns: 1fr; } .ad-tab-bar { overflow-x: auto; } }
</style>
