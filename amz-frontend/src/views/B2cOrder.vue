<template>
  <div class="b2c-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">自建下单（B2C）</h1>
        <p class="hero-subtitle">给当前登录账号下一张 B2C 订单，并核对它有没有真的落库</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：商品候选来自该店铺的商品主数据。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div v-if="notes.length" class="note-line" role="status">
        <Icon icon="mdi:progress-clock" width="16" />
        <span>{{ notes.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四条口径：<b>① 提交≠成单</b>——这个端点只把下单消息投进队列，落库由消费者异步完成，
        页面回「已提交」时订单还没写；要以下方「我的下单」为准，没有就是没落。
        <b>② 归属是登录用户</b>：请求体里的 userId 会被后端丢弃，任何人都不能替别人下单。
        <b>③ 幂等键由服务端每次新生成</b>，所以<b>重复点击会各下一单</b>，提交期间按钮禁用。
        <b>④ 这样下的单没有 shop_id</b>：后端不在这条链路上写店铺归属，
        因此它不会出现在「订单列表」（那个页面按店铺读 Amazon 订单），只出现在这里。
      </div>

      <div class="table-card form-card">
        <h2 class="card-title">下单</h2>
        <div class="field-grid">
          <label class="field">
            商品
            <select v-model="form.productId" :disabled="busy || productsLoading">
              <option :value="0" disabled>{{ productsLoading ? '商品加载中…' : '请选择商品' }}</option>
              <option v-for="p in products" :key="p.id" :value="p.id as number">
                {{ p.sku }}{{ p.title ? ' · ' + p.title : '' }}
              </option>
            </select>
          </label>
          <label class="field">
            单价（&gt;0）
            <input v-model="form.price" type="text" inputmode="decimal" placeholder="19.90" :disabled="busy" />
          </label>
        </div>

        <div class="attr-block">
          <div class="attr-head">
            <span class="attr-title">自定义属性（可选，落 amz_order_attribute）</span>
            <button class="page-btn" :disabled="busy" @click="addAttr">加一项</button>
          </div>
          <div v-for="(attr, i) in form.attrs" :key="i" class="attr-row">
            <input v-model="attr.label" placeholder="标签，如 颜色" :disabled="busy" />
            <input v-model="attr.value" placeholder="取值，如 黑" :disabled="busy" />
            <button class="page-btn" :disabled="busy" @click="removeAttr(i)">删除</button>
          </div>
          <p v-if="!form.attrs.length" class="muted">没有属性。每个标签后端只取第一个值。</p>
        </div>

        <div class="form-actions">
          <button class="action-btn primary" :disabled="busy || !canSubmit" @click="submit">
            {{ busy ? '提交中…' : '提交下单' }}
          </button>
          <button class="page-btn" :disabled="busy" @click="loadMine()">刷新我的下单</button>
          <span class="muted">{{ mine.rows.length }} 行</span>
        </div>
      </div>

      <div class="table-card">
        <h2 class="card-title">我的下单（按登录用户读，无分页）</h2>
        <table class="data-table">
          <thead>
            <tr><th>订单 ID</th><th>商品</th><th>金额</th><th>数量</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="o in mine.rows" :key="o.id">
              <td class="mono">{{ o.id }}</td>
              <td>{{ productLabel(o.productId) }}</td>
              <td class="mono">{{ o.finalPrice ?? '-' }}</td>
              <td class="mono">{{ o.quantity ?? '-' }}</td>
              <td><span class="status-tag">{{ statusLabel(o.status) }}</span></td>
            </tr>
            <tr v-if="!mine.rows.length">
              <td colspan="5" class="empty-row">
                {{ mine.loaded ? '没有我这个账号的订单。刚提交的不在这儿，说明消费者还没落库（或已进死信）。' : '尚未读取。' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import { listMaster, type MasterRow } from '@/api/listing'
import {
  B2C_STATUS_LABELS, getMyB2cOrders, saveB2cOrder, type B2cOrderRow
} from '@/api/order'

const { currentShopId, refreshShop } = useShopGuard()
const shop = () => String(refreshShop() ?? '')

const errors = ref<string[]>([])
const notes = ref<string[]>([])
const busy = ref(false)
const products = ref<MasterRow[]>([])
const productsLoading = ref(false)
const mine = reactive<{ rows: B2cOrderRow[]; loaded: boolean }>({ rows: [], loaded: false })

const form = reactive<{ productId: number; price: string; attrs: { label: string; value: string }[] }>({
  productId: 0,
  price: '',
  attrs: []
})

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const priceNumber = computed(() => Number(String(form.price).trim()))
const canSubmit = computed(() => form.productId > 0
  && String(form.price).trim() !== '' && Number.isFinite(priceNumber.value))

const statusLabel = (s?: number | null) =>
  s === null || s === undefined ? '-' : (B2C_STATUS_LABELS[String(s)] || `状态 ${s}`)

const productLabel = (id?: number) => {
  const hit = products.value.find((p) => p.id === id)
  return hit ? `${hit.sku}${hit.title ? ' · ' + hit.title : ''}` : `#${id ?? '-'}`
}

const loadProducts = async () => {
  const shopId = shop()
  if (!shopId) return
  productsLoading.value = true
  errors.value = []
  try {
    const res = await listMaster(shopId)
    if (res?.code !== 200) {
      pushError(`商品主数据：${res?.message || '后端返回非 200'}`)
      products.value = []
      return
    }
    products.value = (res.data || []).filter((p) => p.id !== undefined && p.id !== null)
  } catch (e) {
    pushError(`商品主数据：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    productsLoading.value = false
  }
}

// 只有整页入口（切店铺重载）才清横幅：两个读取各自可能失败，
// 谁清一次就会把另一个刚报的原因擦掉，页面看起来就像「点了没反应」。
const loadMine = async () => {
  try {
    const res = await getMyB2cOrders()
    if (res?.code !== 200) {
      pushError(`我的下单：${res?.message || '后端返回非 200'}`)
      return
    }
    mine.rows = Array.isArray(res.data) ? res.data : []
    mine.loaded = true
  } catch (e) {
    pushError(`我的下单：${e instanceof Error ? e.message : '调用失败'}`)
  }
}

const addAttr = () => form.attrs.push({ label: '', value: '' })
const removeAttr = (i: number) => form.attrs.splice(i, 1)

const submit = async () => {
  if (!canSubmit.value) return
  busy.value = true
  notes.value = []
  try {
    const selected = products.value.find((p) => p.id === form.productId)
    // 价格以页面填写为准：主数据价格只是预填值，B2C 下单允许议价，
    // 而且后端现在读的就是消息里的 price（不再是跨服务查商品）
    const attrs = form.attrs
      .filter((a) => String(a.label).trim() && String(a.value).trim())
      .map((a) => ({ label: String(a.label).trim(), value: [String(a.value).trim()] }))
    const res = await saveB2cOrder({
      productId: form.productId,
      price: priceNumber.value,
      ...(attrs.length ? { selectAttributes: attrs } : {})
    })
    if (res?.code !== 200) {
      pushError(`下单：${res?.message || '后端返回非 200'}`)
      return
    }
    // 成功只代表「消息进了队列」，绝不能显示成订单已生成
    pushNote(`已投递下单消息（商品 ${selected ? selected.sku : '#' + form.productId}），`
      + '落库是异步的——下面这张表出现新行才算成单')
    await loadMine()
  } catch (e) {
    pushError(`下单：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    busy.value = false
  }
}

/** 投递成功与报错分两条横幅：「消息进了队列」不是错误，但也绝不是成单。 */
const pushNote = (text: string) => {
  if (!notes.value.includes(text)) notes.value.push(text)
}

onMounted(async () => {
  await loadProducts()
  await loadMine()
})
</script>

<style scoped>
.b2c-page { display: flex; min-height: 100vh; }
.main-content { flex: 1; padding: 1.5rem; }
.hero-section { margin-bottom: 1rem; }
.hero-title { font-size: 1.5rem; margin: 0; }
.hero-subtitle { color: var(--color-text-secondary); font-size: 0.875rem; margin: 0.25rem 0 0; }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.note-line { display: flex; align-items: center; gap: 0.5rem; background: var(--color-success-light); color: var(--color-success); border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.table-card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 1rem; margin-bottom: 1rem; }
.card-title { font-size: 1rem; margin: 0 0 0.75rem; }
.field-grid { display: flex; gap: 1rem; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.875rem; }
.field input, .field select { padding: 0.375rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.attr-block { margin-top: 1rem; }
.attr-head { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.5rem; }
.attr-title { font-size: 0.875rem; color: var(--color-text-secondary); }
.attr-row { display: flex; gap: 0.5rem; margin-bottom: 0.5rem; }
.attr-row input { padding: 0.375rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
.form-actions { display: flex; align-items: center; gap: 0.75rem; margin-top: 1rem; }
.data-table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }
.data-table th, .data-table td { text-align: left; padding: 0.5rem; border-bottom: 1px solid var(--color-border); }
.mono { font-family: var(--font-mono, monospace); }
.muted { color: var(--color-text-secondary); font-size: 0.8125rem; }
.empty-row { text-align: center; color: var(--color-text-secondary); }
.status-tag { padding: 0.125rem 0.5rem; border-radius: var(--radius-sm); background: var(--color-light-grey); font-size: 0.8125rem; }
.action-btn, .page-btn { padding: 0.375rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); cursor: pointer; font-size: 0.875rem; }
.action-btn.primary { background: var(--color-primary); color: #fff; border-color: var(--color-primary); }
.action-btn:disabled, .page-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
