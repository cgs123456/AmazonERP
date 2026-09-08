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

      <!-- ACoS 概览（全类型共用）（加载时仅显示骨架） -->
      <template v-if="!loading">
      <div v-show="activeAdTab !== 'DSP'" class="acos-overview">
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
        <h3>近 14 天 ACoS 趋势 <span v-if="!trendIsLive" class="mock-badge">示例数据</span></h3>
        <div class="line-chart">
          <svg viewBox="0 0 600 200" class="chart-svg">
            <polyline :points="acosTrendPoints" class="trend-line" fill="none" stroke-width="2" />
            <circle v-for="(pt, i) in acosTrendDots" :key="i" :cx="pt.x" :cy="pt.y" r="3" class="trend-dot" />
          </svg>
          <div class="chart-labels">
            <span v-for="(d, i) in acosTrend" :key="i">{{ d.day }}</span>
          </div>
        </div>
      </div>

      <!-- 活动列表（SP） -->
      <div v-show="activeAdTab === 'SP'" class="table-card">
        <table class="data-table">
          <thead>
            <tr>
              <th>活动名称</th>
              <th>状态</th>
              <th>日预算</th>
              <th>花费</th>
              <th>销售额</th>
              <th>ACoS</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="camp in campaigns" :key="camp.id">
              <td>{{ camp.name }}</td>
              <td><span class="status-tag" :class="camp.active ? 'active' : 'paused'">{{ camp.active ? '运行中' : '已暂停' }}</span></td>
              <td>${{ camp.budget }}</td>
              <td>${{ camp.spend }}</td>
              <td>${{ camp.sales }}</td>
              <td :class="camp.acos > 50 ? 'acos-bad' : camp.acos > 35 ? 'acos-warn' : 'acos-good'">{{ camp.acos }}%</td>
            </tr>
            <tr v-if="!loading && campaigns.length === 0">
              <td colspan="6" class="empty-row">
                <div class="empty-state">
                  <Icon icon="mdi:chart-line" width="32" class="empty-icon" />
                  <span>暂无广告活动数据</span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
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
                  <button v-if="cr.status === 'PENDING'" class="action-btn" @click="reviewCreative(cr.id!, 'APPROVED')">通过</button>
                  <button v-if="cr.status === 'PENDING'" class="action-btn cancel" @click="reviewCreative(cr.id!, 'REJECTED')">拒绝</button>
                </td>
              </tr>
              <tr v-if="creatives.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:image-multiple-outline" width="32" class="empty-icon" /><span>暂无素材，请输入活动ID查询</span></div></td></tr>
            </tbody>
          </table>
        </div>
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
                <td><button class="action-btn cancel" @click="removeTargeting(tg.id!)">删除</button></td>
              </tr>
              <tr v-if="targetings.length === 0"><td colspan="9" class="empty-row"><div class="empty-state"><Icon icon="mdi:target-variant" width="32" class="empty-icon" /><span>暂无定向规则，请输入活动ID查询</span></div></td></tr>
            </tbody>
          </table>
        </div>
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

      <!-- 素材弹窗 -->
      <div v-if="creativeDialog.visible" class="modal-mask" @click.self="creativeDialog.visible = false">
        <div class="modal">
          <h3>新建 SB 广告素材</h3>
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
          <div class="modal-actions">
            <button class="action-btn cancel" @click="creativeDialog.visible = false">取消</button>
            <button class="action-btn" @click="submitCreative">保存</button>
          </div>
        </div>
      </div>

      <!-- 定向弹窗 -->
      <div v-if="targetingDialog.visible" class="modal-mask" @click.self="targetingDialog.visible = false">
        <div class="modal">
          <h3>新建 SD 受众定向</h3>
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
          <div class="modal-actions">
            <button class="action-btn cancel" @click="targetingDialog.visible = false">取消</button>
            <button class="action-btn" @click="submitTargeting">保存</button>
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
import { getAdReports, getAdTrend } from '@/api/ad'
import type { AdOverview, AcosTrendItem, AdCampaign } from '@/api/ad'
import * as AdExt from '@/api/ad-ext'
import type { AdCreative, AdTargeting, AdSummary, AdType } from '@/api/ad-ext'
import { useShopGuard } from '@/composables/useShopGuard'

const loading = ref(false)

// 当前选中店铺（B4 公共守卫：快照用于模板提示，发请求前 refreshShop 同步最新值）
const { currentShopId, refreshShop } = useShopGuard()

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

// 趋势是否为真实数据（false 时图表为降级 mock，标题旁展示“示例数据”标识）
const trendIsLive = ref(false)

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
      if (!tres || tres.code !== 200) console.warn('[AdManager] 趋势返回异常，使用降级数据', tres)
    }
  } catch (e) {
    if (seq !== trendSeq) return
    trendIsLive.value = false
    console.warn('[AdManager] 趋势调用失败，使用降级数据', e)
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
  { id: 1, name: '关键词-蓝牙耳机-US', active: true, budget: 50, spend: 32.50, sales: 158.00, acos: 20.6 },
  { id: 2, name: '自动广告-全店铺', active: true, budget: 100, spend: 68.30, sales: 210.50, acos: 32.4 },
  { id: 3, name: '品牌广告-Shop B', active: false, budget: 30, spend: 12.00, sales: 28.50, acos: 42.1 },
  { id: 4, name: '商品推广-新品', active: true, budget: 40, spend: 15.70, sales: 89.20, acos: 17.6 }
]

const acosData = ref<AdOverview>({ ...mockOverview })
const acosTrend = ref<AcosTrendItem[]>([...mockTrend])
const campaigns = ref<AdCampaign[]>([...mockCampaigns])

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

onMounted(async () => {
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
  try {
    // 后端 GET /ad/reports 返回 AdReport 行数组（无总览包装），前端聚合总览
    const res = await getAdReports(shopId)
    if (res?.code === 200 && Array.isArray(res.data) && res.data.length > 0) {
      const rows = res.data
      const totalSpend = rows.reduce((s, r) => s + toNum(r.cost), 0)
      const totalSales = rows.reduce((s, r) => s + toNum(r.sales), 0)
      acosData.value = {
        totalAcos: totalSales > 0 ? Number(((totalSpend / totalSales) * 100).toFixed(1)) : 0,
        totalSpend: fmtMoney(totalSpend),
        totalSales: fmtMoney(totalSales),
        roas: totalSpend > 0 ? (totalSales / totalSpend).toFixed(2) : '0.00'
      }
      // 趋势无后端数据源，保留降级 mock
    } else if (!res || res.code !== 200) {
      console.warn('[AdManager] 返回数据异常，使用降级数据', res)
    }
  } catch (e) {
    console.warn('[AdManager] API 调用失败，使用降级数据', e)
  }
  try {
    // 活动列表改用 /ad/campaigns/list（含名称/状态/预算，AdReport 行无这些字段）
    const cres = await AdExt.listCampaigns(shopId, 'SP')
    if (cres?.code === 200 && Array.isArray(cres.data) && cres.data.length > 0) {
      campaigns.value = cres.data.map((c, i) => ({
        id: Number(c.id ?? i + 1),
        name: c.campaignName || c.campaignId,
        active: c.status === 'ENABLED',
        budget: toNum(c.budget),
        spend: toNum(c.spend),
        sales: toNum(c.sales),
        acos: toNum(c.acos)
      }))
    } else if (!cres || cres.code !== 200) {
      console.warn('[AdManager] 活动列表返回异常，使用降级数据', cres)
    }
  } catch (e) {
    console.warn('[AdManager] 活动列表调用失败，使用降级数据', e)
  }
  await loadTrend(shopId, activeAdTab.value)
  loading.value = false
})

// ===== SB 广告素材 =====
const creatives = ref<AdCreative[]>([])
const creativeQuery = reactive<{ campaignId: string }>({ campaignId: '' })
const creativeDialog = reactive<{ visible: boolean; form: AdCreative }>({
  visible: false,
  form: { campaignId: '', creativeType: 'VIDEO', headline: '', brandName: '', status: 'PENDING' }
})
const openCreativeDialog = () => {
  creativeDialog.form = { campaignId: creativeQuery.campaignId, creativeType: 'VIDEO', headline: '', brandName: '', status: 'PENDING' }
  creativeDialog.visible = true
}
const loadCreatives = async () => {
  if (!creativeQuery.campaignId) return
  try {
    const res = await AdExt.listCreatives(creativeQuery.campaignId)
    if (res?.code === 200) creatives.value = res.data || []
  } catch (e) { console.warn('[AdManager] 加载素材失败', e) }
}
const submitCreative = async () => {
  try {
    await AdExt.createCreative(creativeDialog.form)
    creativeDialog.visible = false
    if (creativeQuery.campaignId) await loadCreatives()
  } catch (e) { console.warn('[AdManager] 创建素材失败', e) }
}
const reviewCreative = async (id: number, status: 'APPROVED' | 'REJECTED') => {
  try {
    await AdExt.reviewCreative(id, status)
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
const targetingDialog = reactive<{ visible: boolean; form: AdTargeting }>({
  visible: false,
  form: { campaignId: '', targetingType: 'CONTEXTUAL', targetingValue: '', bid: 1 }
})
const openTargetingDialog = () => {
  targetingDialog.form = { campaignId: targetingQuery.campaignId, targetingType: 'CONTEXTUAL', targetingValue: '', bid: 1 }
  targetingDialog.visible = true
}
const loadTargetings = async () => {
  if (!targetingQuery.campaignId) return
  try {
    const res = await AdExt.listTargeting(targetingQuery.campaignId)
    if (res?.code === 200) targetings.value = res.data || []
  } catch (e) { console.warn('[AdManager] 加载定向失败', e) }
}
const submitTargeting = async () => {
  try {
    await AdExt.createTargeting(targetingDialog.form)
    targetingDialog.visible = false
    if (targetingQuery.campaignId) await loadTargetings()
  } catch (e) { console.warn('[AdManager] 创建定向失败', e) }
}
const removeTargeting = async (id: number) => {
  try {
    await AdExt.deleteTargeting(id)
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

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .acos-overview { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .acos-overview { grid-template-columns: 1fr; } .ad-tab-bar { overflow-x: auto; } }
</style>
