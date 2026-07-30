<template>
  <div class="ad-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="page-header">
        <h1>广告管理</h1>
        <p class="subtitle">广告活动、ACoS 监控与分时调价（支持 SP / SB / SD / DSP 全广告类型）</p>
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

      <div v-if="loading" class="loading-mask">加载中...</div>

      <!-- 未选择店铺提示 -->
      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看广告数据。
      </div>

      <!-- ACoS 概览（全类型共用） -->
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
        <h3>近 14 天 ACoS 趋势</h3>
        <div class="line-chart">
          <svg viewBox="0 0 600 200" class="chart-svg">
            <polyline :points="acosTrendPoints" fill="none" stroke="#4f46e5" stroke-width="2" />
            <circle v-for="(pt, i) in acosTrendDots" :key="i" :cx="pt.x" :cy="pt.y" r="3" fill="#4f46e5" />
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
              <th>操作</th>
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
              <td><button class="action-btn">调价</button></td>
            </tr>
            <tr v-if="!loading && campaigns.length === 0">
              <td colspan="7" class="empty-row">暂无广告活动数据</td>
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
              <tr v-if="creatives.length === 0"><td colspan="9" class="empty-row">暂无素材，请输入活动ID查询</td></tr>
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
              <tr v-if="targetings.length === 0"><td colspan="9" class="empty-row">暂无定向规则，请输入活动ID查询</td></tr>
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
import { ref, computed, reactive, onMounted } from 'vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getAdReports } from '@/api/ad'
import type { AdOverview, AcosTrendItem, AdCampaign } from '@/api/ad'
import * as AdExt from '@/api/ad-ext'
import type { AdCreative, AdTargeting, AdSummary, AdType } from '@/api/ad-ext'
import { getCurrentShopId } from '@/utils/shop'

const loading = ref(false)

// 当前选中店铺（未选则为空字符串，用于阻断查询并提示用户）
const currentShopId = ref(getCurrentShopId())

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

const acosTrendPoints = computed(() => {
  if (acosTrend.value.length === 0) return ''
  const max = 40, min = 15
  const w = 600, h = 180, pad = 10
  const step = (w - pad * 2) / (acosTrend.value.length - 1)
  return acosTrend.value.map((d, i) => {
    const x = pad + i * step
    const y = h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
    return `${x},${y}`
  }).join(' ')
})
const acosTrendDots = computed(() => {
  if (acosTrend.value.length === 0) return []
  const max = 40, min = 15
  const w = 600, h = 180, pad = 10
  const step = (w - pad * 2) / (acosTrend.value.length - 1)
  return acosTrend.value.map((d, i) => ({
    x: pad + i * step,
    y: h - ((d.value - min) / (max - min)) * (h - pad * 2) + pad
  }))
})

onMounted(async () => {
  // 未选择店铺时不发请求
  const shopId = currentShopId.value
  if (!shopId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await getAdReports(shopId)
    if (res?.code === 200 && res.data) {
      if (res.data.overview) acosData.value = res.data.overview
      if (res.data.trend) acosTrend.value = res.data.trend
      if (res.data.campaigns) campaigns.value = res.data.campaigns
    } else {
      console.warn('[AdManager] 返回数据异常，使用降级数据', res)
    }
  } catch (e) {
    console.warn('[AdManager] API 调用失败，使用降级数据', e)
  } finally {
    loading.value = false
  }
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
  const shopId = currentShopId.value
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
.ad-page { min-height: 100vh; background: #f5f6fa; }
.main-content { margin-left: 220px; margin-top: 64px; padding: 24px 32px; }
.page-header h1 { font-size: 24px; font-weight: 700; color: #1a1a2e; margin: 0; }
.page-header .subtitle { color: #666; margin: 4px 0 24px; font-size: 14px; }

.loading-mask { padding: 12px 16px; margin-bottom: 16px; background: #eef2ff; color: #4f46e5; border-radius: 8px; font-size: 14px; text-align: center; }

.shop-tip { padding: 12px 16px; margin-bottom: 16px; background: #fef3c7; color: #92400e; border-radius: 8px; font-size: 14px; text-align: center; }

.acos-overview { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 24px; }
.acos-card { background: #fff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.acos-label { font-size: 13px; color: #666; }
.acos-value { font-size: 28px; font-weight: 700; color: #1a1a2e; margin: 4px 0; }
.acos-value.good { color: #10b981; }
.acos-value.warn { color: #f59e0b; }
.acos-value.bad { color: #ef4444; }
.acos-desc { font-size: 12px; color: #999; }

.chart-card { background: #fff; border-radius: 12px; padding: 20px; margin-bottom: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.chart-card h3 { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
.line-chart { width: 100%; }
.chart-svg { width: 100%; height: 200px; }
.chart-labels { display: flex; justify-content: space-between; margin-top: 8px; }
.chart-labels span { font-size: 11px; color: #999; }

.table-card { background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th { background: #f9fafb; padding: 12px 16px; text-align: left; font-size: 13px; color: #6b7280; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.data-table td { padding: 12px 16px; font-size: 14px; color: #1f2937; border-bottom: 1px solid #f3f4f6; }
.data-table tr:hover { background: #f9fafb; }
.empty-row { text-align: center; color: #999; padding: 32px 0; }

.status-tag { padding: 4px 10px; border-radius: 6px; font-size: 12px; }
.status-tag.active { background: #d1fae5; color: #065f46; }
.status-tag.paused { background: #f3f4f6; color: #6b7280; }
.status-tag.pending { background: #fef3c7; color: #92400e; }
.acos-good { color: #10b981; font-weight: 600; }
.acos-warn { color: #f59e0b; font-weight: 600; }
.acos-bad { color: #ef4444; font-weight: 600; }
.action-btn { padding: 4px 12px; background: #4f46e5; color: #fff; border: none; border-radius: 6px; cursor: pointer; font-size: 12px; }
.action-btn.cancel { background: #fee2e2; color: #991b1b; }

/* 广告类型 Tab */
.ad-tab-bar { display: flex; gap: 4px; margin-bottom: 20px; border-bottom: 1px solid #e5e7eb; }
.ad-tab-item { padding: 10px 18px; cursor: pointer; color: #6b7280; font-size: 14px; border-bottom: 2px solid transparent; transition: all 0.2s; }
.ad-tab-item:hover { color: #1a1a2e; }
.ad-tab-item.active { color: #4f46e5; border-bottom-color: #4f46e5; font-weight: 600; }

/* 扩展区块 */
.ext-section { margin-top: 20px; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.section-header h3 { font-size: 16px; font-weight: 600; margin: 0; }
.ext-toolbar { display: flex; gap: 8px; margin-top: 12px; align-items: center; }
.ext-toolbar input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; flex: 1; max-width: 320px; }

/* DSP 批量报表 */
.summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.summary-card { background: #fff; border-radius: 12px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.summary-type { font-size: 16px; font-weight: 700; color: #4f46e5; margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid #f3f4f6; }
.summary-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 13px; }
.summary-row span { color: #6b7280; }
.summary-row b { color: #1f2937; font-weight: 600; }

/* 弹窗 */
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: #fff; border-radius: 12px; padding: 24px; width: 560px; max-width: 90vw; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 16px; font-size: 18px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #374151; }
.form-grid input, .form-grid select { padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
</style>
