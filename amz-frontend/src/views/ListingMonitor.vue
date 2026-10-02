<template>
  <div class="listing-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">商品与 Listing</h1>
        <p class="hero-subtitle">Listing 健康度、关键词排名、竞品与 Buybox 抢占</p>
      </div>

      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="health-grid">
          <div v-for="i in 4" :key="i" class="health-card">
            <div class="skeleton sk-count"></div>
            <div class="skeleton sk-line sk-line-sm"></div>
          </div>
        </div>
        <div class="table-card sk-table-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-row" :class="{ 'sk-row-alt': i % 2 === 0 }"></div>
        </div>
      </div>

      <div v-if="!currentShopId" class="shop-tip">
        请先在右上角选择店铺后再查看 Listing 数据。
      </div>

      <template v-if="!loading && currentShopId">
        <!-- 加载失败的区显示明确错误，不用样例数据顶替 -->
        <div v-if="errors.length" class="error-zone" role="alert">
          <Icon icon="mdi:alert-circle-outline" width="16" />
          <span>{{ errors.join('；') }}</span>
        </div>

        <div class="health-grid">
          <div class="health-card">
            <div class="health-count">{{ summary.total }}</div>
            <div class="health-label">在册 Listing</div>
          </div>
          <div class="health-card healthy">
            <div class="health-count">{{ summary.avgScore }}</div>
            <div class="health-label">平均健康分</div>
          </div>
          <div class="health-card risk">
            <div class="health-count">{{ summary.warning }}</div>
            <div class="health-label">需关注</div>
          </div>
          <div class="health-card urgent">
            <div class="health-count">{{ summary.critical }}</div>
            <div class="health-label">严重 / 被抑制</div>
          </div>
        </div>

        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="switchTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- 健康度明细 -->
        <div v-if="tab === 'health'" class="table-card">
          <div class="filter-row">
            <label class="filter">严重度
              <select v-model="severity" @change="reloadHealth">
                <option value="">全部</option>
                <option value="OK">OK</option>
                <option value="WARNING">WARNING</option>
                <option value="CRITICAL">CRITICAL</option>
              </select>
            </label>
            <span class="muted">达标率 {{ summary.healthRate }}%（后端按 binary 精确匹配 severity，'ok' 不计入 OK）</span>
          </div>
          <table class="data-table">
            <thead>
              <tr>
                <th>ASIN</th><th>SKU</th><th>状态</th><th>健康分</th><th>严重度</th><th>抑制/缺陷原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pagedRows" :key="row.asin + '|' + (row.sku || '')">
                <td class="mono">{{ row.asin }}</td>
                <td class="mono">{{ row.sku || '-' }}</td>
                <td>{{ row.status || '-' }}</td>
                <td :class="scoreClass(row.healthScore)">{{ row.healthScore ?? '-' }}</td>
                <td><span class="health-tag" :class="severityClass(row.severity)">{{ row.severity || '未知' }}</span></td>
                <td>{{ row.suppressedReason || '-' }}</td>
              </tr>
              <tr v-if="!pagedRows.length">
                <td colspan="6" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:file-document-remove-outline" width="32" class="empty-icon" />
                    <span>该店铺暂无 Listing 健康度记录</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="healthTotal > pageSize" class="table-pager">
            <span class="page-info">第 {{ currentPage }} 页 / 共 {{ totalPagesCount }} 页（{{ healthTotal }} 条）</span>
            <div class="page-actions">
              <button class="page-btn" :disabled="currentPage <= 1" @click="pager.prevPage">上一页</button>
              <button class="page-btn" :disabled="currentPage >= totalPagesCount" @click="pager.nextPage">下一页</button>
            </div>
          </div>
        </div>

        <!-- 关键词排名 -->
        <div v-if="tab === 'ranking'" class="table-card">
          <table class="data-table">
            <thead><tr><th>ASIN</th><th>关键词</th><th>自然排名</th><th>广告排名</th><th>搜索量</th><th>日期</th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in rankings" :key="r.asin + '|' + r.keyword + '|' + i">
                <td class="mono">{{ r.asin }}</td>
                <td>{{ r.keyword }}</td>
                <td :class="typeof r.organicRank === 'number' && r.organicRank <= 10 ? 'days-healthy' : ''">{{ r.organicRank ?? '-' }}</td>
                <td>{{ r.adRank ?? '-' }}</td>
                <td>{{ r.searchVolume ?? '-' }}</td>
                <td class="mono">{{ r.rankDate || '-' }}</td>
              </tr>
              <tr v-if="!rankings.length"><td colspan="6" class="empty-row"><div class="empty-state"><span>暂无关键词排名（该能力需先录入排名数据）</span></div></td></tr>
            </tbody>
          </table>
        </div>

        <!-- 竞品 -->
        <div v-if="tab === 'competitor'" class="table-card">
          <table class="data-table">
            <thead><tr><th>竞品 ASIN</th><th>标题</th><th>价格</th><th>BSR</th><th>评分</th><th>评论数</th><th>有货</th><th>快照日期</th></tr></thead>
            <tbody>
              <tr v-for="(c, i) in competitors" :key="c.competitorAsin + '|' + i">
                <td class="mono">{{ c.competitorAsin }}</td>
                <td class="cell-clip">{{ c.competitorTitle || '-' }}</td>
                <td>{{ c.price ?? '-' }}</td>
                <td>{{ c.bsRank ?? '-' }}</td>
                <td>{{ c.reviewRating ?? '-' }}</td>
                <td>{{ c.reviewCount ?? '-' }}</td>
                <td>{{ truthy(c.inStock) ? '有货' : '无货' }}</td>
                <td class="mono">{{ c.snapshotDate || '-' }}</td>
              </tr>
              <tr v-if="!competitors.length"><td colspan="8" class="empty-row"><div class="empty-state"><span>暂无竞品监控记录</span></div></td></tr>
            </tbody>
          </table>
        </div>

        <!-- Buybox -->
        <div v-if="tab === 'buybox'" class="table-card">
          <table class="data-table">
            <thead><tr><th>ASIN</th><th>Winner</th><th>是否我方</th><th>Buybox 价</th><th>我方价</th><th>价差</th><th>配送</th><th>份额</th></tr></thead>
            <tbody>
              <tr v-for="(b, i) in buybox" :key="b.asin + '|' + i">
                <td class="mono">{{ b.asin }}</td>
                <td class="mono">{{ b.sellerId || '-' }}</td>
                <td><span class="health-tag" :class="truthy(b.isSelf) ? 'healthy' : 'overstock'">{{ truthy(b.isSelf) ? '我方' : '对手' }}</span></td>
                <td>{{ b.buyboxPrice ?? '-' }}</td>
                <td>{{ b.ourPrice ?? '-' }}</td>
                <td>{{ b.priceGap ?? '-' }}</td>
                <td>{{ b.fulfillmentType || '-' }}</td>
                <td>{{ b.ownershipPct ?? '-' }}%</td>
              </tr>
              <tr v-if="!buybox.length"><td colspan="8" class="empty-row"><div class="empty-state"><span>暂无 Buybox 记录</span></div></td></tr>
            </tbody>
          </table>
        </div>

        <!-- 变更日志 -->
        <div v-if="tab === 'changelog'" class="table-card">
          <table class="data-table">
            <thead><tr><th>ASIN</th><th>字段</th><th>变更前</th><th>变更后</th><th>操作人</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="(log, i) in changeLogs" :key="i">
                <td class="mono">{{ log.asin ?? '-' }}</td>
                <td>{{ log.fieldName ?? log.field ?? '-' }}</td>
                <td class="cell-clip">{{ log.oldValue ?? '-' }}</td>
                <td class="cell-clip">{{ log.newValue ?? '-' }}</td>
                <td>{{ log.operator ?? '-' }}</td>
                <td class="mono">{{ log.changeTime ?? '-' }}</td>
              </tr>
              <tr v-if="!changeLogs.length"><td colspan="6" class="empty-row"><div class="empty-state"><span>暂无 Listing 变更记录</span></div></td></tr>
            </tbody>
          </table>
        </div>

        <!-- 商品主数据：FBA 计费与跨站复制的依赖数据 -->
        <div v-if="tab === 'master'" class="table-card">
          <div class="filter-row">
            <input v-model="masterKeyword" class="fee-input" placeholder="按标题/SKU/ASIN 搜索"
                   @keyup.enter="reloadMaster" />
            <button class="page-btn" @click="reloadMaster">搜索</button>
            <span class="muted">marketplaceId 与 title 在库上是 NOT NULL，缺失会在保存时被挡下</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>SKU</th><th>ASIN</th><th>站点</th><th>标题</th><th>品牌</th>
                  <th>尺寸档</th><th>重量(g)</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="m in masters" :key="m.id">
                <td class="mono">{{ m.sku }}</td>
                <td class="mono">{{ m.asin || '-' }}</td>
                <td class="mono">{{ m.marketplaceId || '-' }}</td>
                <td class="cell-clip">{{ m.title || '-' }}</td>
                <td><input class="cell-input" :value="m.brand" @input="patch[m.id as number] = {...(patch[m.id as number] || {}), brand: ($event.target as HTMLInputElement).value}" /></td>
                <td><input class="cell-input" :value="m.sizeTier" @input="patch[m.id as number] = {...(patch[m.id as number] || {}), sizeTier: ($event.target as HTMLInputElement).value}" /></td>
                <td><input class="cell-input" type="number" :value="m.weightG" @input="patch[m.id as number] = {...(patch[m.id as number] || {}), weightG: Number(($event.target as HTMLInputElement).value)}" /></td>
                <td>{{ m.status || '-' }}</td>
                <td><button class="page-btn" :disabled="!patch[m.id as number] || masterBusy" @click="saveMaster(m)">保存</button></td>
              </tr>
              <tr v-if="!masters.length">
                <td colspan="9" class="empty-row">
                  <div class="empty-state"><span>该店铺还没有商品主数据；FBA 试算与跨站复制都依赖它</span></div>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="master-create">
            <div class="fee-head"><span class="fee-title">新增主数据</span><span class="muted">{{ masterMsg }}</span></div>
            <div class="master-grid">
              <input v-model="masterForm.sku" class="fee-input" placeholder="SKU *" />
              <input v-model="masterForm.marketplaceId" class="fee-input" placeholder="站点 marketplaceId *" />
              <input v-model="masterForm.title" class="fee-input" placeholder="标题 *" />
              <input v-model="masterForm.asin" class="fee-input" placeholder="ASIN" />
              <input v-model="masterForm.brand" class="fee-input" placeholder="品牌" />
              <input v-model="masterForm.sizeTier" class="fee-input" placeholder="尺寸档 SIZE_TIER" />
              <input v-model="masterForm.weightG" class="fee-input" type="number" placeholder="重量(g)" />
              <button class="page-btn" :disabled="masterBusy" @click="createMasterRow">创建</button>
            </div>
          </div>
        </div>

        <!-- FBA 费用试算 -->
        <div class="fee-card">
          <div class="fee-head">
            <span class="fee-title">FBA 费用试算</span>
            <span class="muted">按 ASIN 试算（后端取商品尺寸/重量；结果带 source 标记真实与否）</span>
          </div>
          <div class="fee-form">
            <input v-model="feeAsin" class="fee-input mono" placeholder="输入 ASIN，如 B08X4ABC01" @keyup.enter="runFee" />
            <button class="page-btn" :disabled="!feeAsin.trim() || feeBusy" @click="runFee">
              {{ feeBusy ? '试算中…' : '试算' }}
            </button>
          </div>
          <dl v-if="feeResult" class="fee-result">
            <template v-for="(v, k) in feeResult" :key="k">
              <dt>{{ k }}</dt><dd>{{ typeof v === 'object' ? JSON.stringify(v) : v }}</dd>
            </template>
          </dl>
          <p v-if="feeError" class="fee-error">{{ feeError }}</p>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import { usePagination } from '@/composables/usePagination'
import {
  getListingSummary, getListingHealthList, getRankings, getCompetitors,
  getBuyBoxList, getChangeLogs, estimateFees, severityClass, emptySummary,
  listMaster, createMaster, updateMaster
} from '@/api/listing'
import type {
  ListingHealthRow, ListingSummary, KeywordRankingRow, CompetitorRow, BuyBoxRow, MasterRow
} from '@/api/listing'

type TabKey = 'health' | 'ranking' | 'competitor' | 'buybox' | 'changelog' | 'master'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'health', label: 'Listing 健康度' },
  { key: 'ranking', label: '关键词排名' },
  { key: 'competitor', label: '竞品监控' },
  { key: 'buybox', label: 'Buybox' },
  { key: 'changelog', label: '变更记录' },
  { key: 'master', label: '商品主数据' }
]

const loading = ref(false)
const { currentShopId, refreshShop } = useShopGuard()

const tab = ref<TabKey>('health')
const summary = ref<ListingSummary>(emptySummary())
const healthRows = ref<ListingHealthRow[]>([])
const rankings = ref<KeywordRankingRow[]>([])
const competitors = ref<CompetitorRow[]>([])
const buybox = ref<BuyBoxRow[]>([])
const changeLogs = ref<Array<Record<string, unknown>>>([])
const errors = ref<string[]>([])
const masters = ref<MasterRow[]>([])
const masterKeyword = ref('')
const patch = ref<Record<number, Partial<MasterRow>>>({})
const masterBusy = ref(false)
const masterMsg = ref('')
const masterForm = ref<Partial<MasterRow>>({ marketplaceId: 'ATVPDKIKX0DER' })
const severity = ref('')

const pager = usePagination<ListingHealthRow>(() => healthRows.value, 20)
const pagedRows = pager.paged
const healthTotal = pager.total
const currentPage = pager.page
const pageSize = pager.size
const totalPagesCount = pager.totalPages

const truthy = (v: unknown) => v === true || v === 1 || v === '1'

const scoreClass = (score?: number | null) => {
  if (typeof score !== 'number') return ''
  if (score < 60) return 'days-urgent'
  if (score < 80) return 'days-risk'
  return 'days-healthy'
}

const zone = async (name: string, fn: () => Promise<unknown>, apply: (v: any) => void) => {
  try {
    const res: any = await fn()
    if (res?.code !== 200) {
      errors.value.push(`${name}：${(res && res.message) || '接口返回非 200'}`)
      return
    }
    apply(res.data)
  } catch (e: any) {
    errors.value.push(`${name}：${e?.message || '调用失败'}`)
  }
}

const loadAll = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  loading.value = true
  errors.value = []
  await Promise.all([
    zone('健康度概览', () => getListingSummary(shopId), (d: ListingSummary) => { summary.value = d }),
    zone('Listing 列表', () => getListingHealthList(shopId, severity.value || undefined),
      (d: ListingHealthRow[]) => { healthRows.value = d || [] }),
    zone('关键词排名', () => getRankings(shopId), (d: KeywordRankingRow[]) => { rankings.value = d || [] }),
    zone('竞品监控', () => getCompetitors(shopId), (d: CompetitorRow[]) => { competitors.value = d || [] }),
    zone('Buybox', () => getBuyBoxList(shopId), (d: BuyBoxRow[]) => { buybox.value = d || [] }),
    zone('变更记录', () => getChangeLogs(shopId), (d: Array<Record<string, unknown>>) => { changeLogs.value = d || [] }),
    zone('商品主数据', () => listMaster(shopId), (d: MasterRow[]) => { masters.value = d || [] })
  ])
  loading.value = false
}

const reloadHealth = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  await zone('Listing 列表', () => getListingHealthList(shopId, severity.value || undefined),
    (d: ListingHealthRow[]) => { healthRows.value = d || [] })
}

const switchTab = (key: TabKey) => { tab.value = key }

const reloadMaster = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  await zone('商品主数据', () => listMaster(shopId, undefined, masterKeyword.value || undefined),
    (d: MasterRow[]) => { masters.value = d || []; patch.value = {} })
}

const saveMaster = async (row: MasterRow) => {
  const shopId = refreshShop()
  if (!shopId || !row.id) return
  masterBusy.value = true
  masterMsg.value = ''
  try {
    const res: any = await updateMaster(shopId, row.id, patch.value[row.id] || {})
    if (res?.code !== 200) { masterMsg.value = `保存失败：${res?.message || '后端拒绝'}`; return }
    const merged = res.data as MasterRow
    masters.value = masters.value.map(m => (m.id === row.id ? { ...m, ...merged } : m))
    delete patch.value[row.id]
    masterMsg.value = `已保存 ${row.sku}`
  } catch (e: any) {
    masterMsg.value = `保存失败：${e?.message || '调用异常'}`
  } finally {
    masterBusy.value = false
  }
}

const createMasterRow = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  masterBusy.value = true
  masterMsg.value = ''
  try {
    const body = { ...masterForm.value, shopId }
    const res: any = await createMaster(body)
    if (res?.code !== 200) { masterMsg.value = `创建失败：${res?.message || '后端拒绝'}`; return }
    masters.value = [...masters.value, res.data as MasterRow].sort((a, b) => a.sku.localeCompare(b.sku))
    masterMsg.value = `已创建 ${res.data?.sku || ''}`
    masterForm.value = { marketplaceId: 'ATVPDKIKX0DER' }
  } catch (e: any) {
    masterMsg.value = `创建失败：${e?.message || '调用异常'}`
  } finally {
    masterBusy.value = false
  }
}

// FBA 费用试算
const feeAsin = ref('')
const feeBusy = ref(false)
const feeResult = ref<Record<string, unknown> | null>(null)
const feeError = ref('')
const runFee = async () => {
  const asin = feeAsin.value.trim()
  if (!asin || feeBusy.value) return
  feeBusy.value = true
  feeError.value = ''
  feeResult.value = null
  try {
    const res: any = await estimateFees({ asin })
    if (res?.code === 200 && res.data) feeResult.value = res.data
    else feeError.value = res?.message || '试算失败：后端未返回数据'
  } catch (e: any) {
    feeError.value = e?.message || '试算调用失败'
  } finally {
    feeBusy.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.listing-page { background: var(--color-background); }

.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.sk-count { width: 2.5rem; height: 2rem; margin: 0 auto 0.5rem auto; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 55%; }
.sk-row { height: 2.75rem; border-radius: 0; }
.sk-row-alt { width: 96%; }
.sk-table-card { padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.625rem; }

.health-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1rem; }
.health-card {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1rem;
  text-align: center;
  box-shadow: var(--shadow-sm);
  border-top: 4px solid var(--color-primary);
}
.health-card .health-count {
  font-size: 1.875rem;
  font-weight: 700;
  color: var(--color-on-surface);
  margin-bottom: 0.25rem;
  font-variant-numeric: tabular-nums;
}
.health-card.urgent { border-top-color: var(--color-error); }
.health-card.risk { border-top-color: var(--color-warning); }
.health-card.healthy { border-top-color: var(--color-success); }
.health-label { font-size: 0.875rem; color: var(--color-muted); margin-top: 0.125rem; }

.error-zone {
  display: flex; align-items: center; gap: 0.5rem;
  background: var(--color-light-red); color: var(--color-error);
  border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem;
  font-size: 0.875rem;
}

.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab {
  background: var(--color-surface); color: var(--color-muted);
  border: 1px solid var(--color-border); border-radius: var(--radius-md);
  padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer;
}
.tab.active { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }

.filter-row { display: flex; align-items: center; gap: 1rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter select { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-on-surface); }
.muted { color: var(--color-muted); font-size: 0.75rem; }

.health-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; font-weight: 500; white-space: nowrap; }
.health-tag.ok, .health-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.health-tag.warning, .health-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.health-tag.critical, .health-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.health-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }

.days-urgent { color: var(--color-error); font-weight: 600; }
.days-risk { color: var(--color-warning-dark); font-weight: 600; }
.days-healthy { color: var(--color-success); font-weight: 600; }
.cell-clip { max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; margin-top: 1rem; }

.fee-card { background: var(--color-surface); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); padding: 1rem; margin-top: 1rem; }
.fee-head { display: flex; align-items: baseline; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
.fee-title { font-weight: 600; color: var(--color-on-surface); }
.fee-form { display: flex; gap: 0.5rem; }
.fee-input { flex: 1; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-background); color: var(--color-on-surface); }
.fee-result { display: grid; grid-template-columns: max-content 1fr; gap: 0.25rem 1rem; margin: 0.75rem 0 0 0; font-size: 0.8125rem; }
.fee-result dt { color: var(--color-muted); }
.fee-result dd { margin: 0; color: var(--color-on-surface); word-break: break-all; }
.fee-error { color: var(--color-error); font-size: 0.8125rem; margin-top: 0.5rem; }

.cell-input { width: 6.5rem; padding: 0.25rem 0.375rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.master-create { border-top: 1px solid var(--color-border); margin-top: 1rem; padding-top: 0.875rem; }
.master-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0.5rem; align-items: center; }
@media (max-width: 1024px) { .master-grid { grid-template-columns: repeat(2, 1fr); } }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } .health-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .main-content { margin-left: 0; } .health-grid { grid-template-columns: 1fr; } }
</style>
