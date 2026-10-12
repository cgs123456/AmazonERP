<template>
  <div class="price-rules-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">Listing 调价规则</h1>
        <p class="hero-subtitle">目标价 / 调整幅度 · 手动执行下发 Amazon</p>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条边界：① <b>目标价与调整幅度二选一</b>——目标价直接定价，调整幅度相对当前价
        （正数上调、负数下调，-0.05 = 降价 5%），两者都填时后端只认目标价语义；
        ② 同一「店铺 + marketplace + SKU」再保存是<b>更新</b>而不是新增；
        ③ <b>执行会真的改 Amazon 上的售价</b>（经 listingsItems patch），
        无真实凭证时后端显式失败并把原因写进规则的「上次结果」——不会静默什么都不做。
      </div>

      <div class="table-card" data-panel="rules">
        <div class="filter-row">
          <button class="action-btn" :disabled="loading" @click="load">刷新</button>
          <button class="action-btn" @click="openDialog()">+ 新建规则</button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th>SKU</th><th>Marketplace</th><th>类型</th><th>目标价</th><th>调整幅度</th>
              <th>状态</th><th>上次执行</th><th>上次结果</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rules" :key="r.id">
              <td class="mono">{{ r.sku }}</td>
              <td class="mono">{{ r.marketplaceId }}</td>
              <td>{{ r.ruleType || '—' }}</td>
              <td>{{ r.targetPrice ?? '—' }}</td>
              <td>{{ r.adjustPct == null ? '—' : `${(Number(r.adjustPct) * 100).toFixed(1)}%` }}</td>
              <td>
                <span class="status-tag" :class="r.enabled === 1 ? 'healthy' : 'unknown'">
                  {{ r.enabled === 1 ? '启用' : '停用' }}
                </span>
              </td>
              <td>{{ r.lastExecutedAt || '—' }}</td>
              <td class="cell-clip" :title="r.lastResult || ''">{{ r.lastResult || '—' }}</td>
              <td>
                <button class="action-btn" :disabled="busy" @click="openDialog(r)">编辑</button>
                <button class="action-btn primary" :disabled="busy || r.enabled !== 1"
                        :title="r.enabled !== 1 ? '规则已停用，请先启用' : ''"
                        @click="confirmAction(executeConfirm(r))">执行</button>
                <button class="action-btn cancel" :disabled="busy"
                        @click="confirmAction(deleteConfirm(r))">删除</button>
              </td>
            </tr>
            <tr v-if="!loading && !rules.length">
              <td colspan="9" class="empty-row">该店铺暂无调价规则</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>

    <!-- 新建 / 编辑弹窗 -->
    <div v-if="dialog" class="modal-mask" @click.self="dialog = null">
      <div class="modal" role="dialog" aria-modal="true" data-panel="rule-save">
        <h3>{{ dialog.id ? '编辑调价规则' : '新建调价规则' }}</h3>
        <div class="form-grid">
          <label>SKU *<input v-model="dialog.sku" /></label>
          <label>Marketplace *<input v-model="dialog.marketplaceId" placeholder="如 ATVPDKIKX0DER" /></label>
          <label>规则类型<input v-model="dialog.ruleType" placeholder="MANUAL_SCHEDULE" /></label>
          <label>目标价<input v-model="dialog.targetPrice" type="number" step="0.01" min="0"
                          placeholder="直接定价，与幅度二选一" /></label>
          <label>调整幅度<input v-model="dialog.adjustPct" type="number" step="0.01"
                          placeholder="如 -0.05 表示降价 5%" /></label>
          <label>启用
            <select v-model="dialog.enabled">
              <option :value="1">启用</option>
              <option :value="0">停用</option>
            </select>
          </label>
          <label>备注<input v-model="dialog.remark" /></label>
        </div>
        <p v-if="dialogError" class="error-zone" role="alert">{{ dialogError }}</p>
        <div class="modal-actions">
          <button class="action-btn cancel" @click="dialog = null">取消</button>
          <button class="action-btn" :disabled="busy || !canSubmit" @click="submitDialog()">保存</button>
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
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import {
  listPriceRules, savePriceRule, deletePriceRule, executePriceRule,
  type ListingPriceRuleRow
} from '@/api/listingPrice'

const { refreshShop } = useShopGuard()
const shop = () => refreshShop()

const rules = ref<ListingPriceRuleRow[]>([])
const loading = ref(false)
const busy = ref(false)
const errors = ref<string[]>([])
const msg = ref('')

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const load = async () => {
  const shopId = shop()
  if (!shopId) return
  loading.value = true
  errors.value = []
  try {
    const res = await listPriceRules(shopId)
    if (res?.code === 200 && Array.isArray(res.data)) {
      rules.value = res.data
    } else {
      pushError(`调价规则列表：${res?.message || '后端返回非 200'}`)
      rules.value = []
    }
  } catch (e) {
    // 「拉不到」与「真的没有规则」必须可区分
    pushError(`调价规则列表：${e instanceof Error ? e.message : '调用失败'}`)
    rules.value = []
  } finally {
    loading.value = false
  }
}

/* ---------- 新建 / 编辑 ---------- */

interface RuleDialog {
  id?: number
  sku: string
  marketplaceId: string
  ruleType: string
  targetPrice: string
  adjustPct: string
  enabled: number
  remark: string
}

const dialog = ref<RuleDialog | null>(null)
const dialogError = ref('')

const emptyDialog = (): RuleDialog => ({
  sku: '', marketplaceId: '', ruleType: 'MANUAL_SCHEDULE',
  targetPrice: '', adjustPct: '', enabled: 1, remark: ''
})

const openDialog = (r?: ListingPriceRuleRow) => {
  dialogError.value = ''
  dialog.value = r ? {
    id: r.id,
    sku: r.sku || '',
    marketplaceId: r.marketplaceId || '',
    ruleType: r.ruleType || 'MANUAL_SCHEDULE',
    targetPrice: r.targetPrice == null ? '' : String(r.targetPrice),
    adjustPct: r.adjustPct == null ? '' : String(r.adjustPct),
    enabled: r.enabled === 1 ? 1 : 0,
    remark: r.remark || ''
  } : emptyDialog()
}

/**
 * 与后端同口径的前置校验：少发无效请求，也把规则讲在页面上。
 * <p>
 * 价格两个字段是 <input type="number">，v-model 会被 Vue 自动转成 number
 * （空值时则是空串），所以这里一律走 String() 兜——直接 .trim() 会在数字上抛
 * TypeError，让 computed 求值失败、保存按钮永远 disabled。
 */
const canSubmit = computed(() => {
  const d = dialog.value
  if (!d) return false
  if (!d.sku.trim() || !d.marketplaceId.trim()) return false
  return String(d.targetPrice ?? '').trim() !== '' || String(d.adjustPct ?? '').trim() !== ''
})

const submitDialog = async () => {
  const d = dialog.value
  if (!d) return
  const target = String(d.targetPrice ?? '').trim() === '' ? null : Number(d.targetPrice)
  const pct = String(d.adjustPct ?? '').trim() === '' ? null : Number(d.adjustPct)

  if (target !== null && pct !== null) {
    dialogError.value = '目标价与调整幅度二选一：都填时后端只按目标价处理，请只填一个'
    return
  }
  if (target !== null && !(target > 0)) {
    dialogError.value = '目标价必须大于 0'
    return
  }
  if (pct !== null && pct <= -1) {
    dialogError.value = '调整幅度不能低于 -100%（价格不可为负）'
    return
  }

  busy.value = true
  dialogError.value = ''
  try {
    const res = await savePriceRule({
      id: d.id,
      shopId: shop(),
      sku: d.sku.trim(),
      marketplaceId: d.marketplaceId.trim(),
      ruleType: d.ruleType.trim() || 'MANUAL_SCHEDULE',
      targetPrice: target,
      adjustPct: pct,
      enabled: d.enabled,
      remark: d.remark.trim() || undefined
    })
    if (res?.code !== 200) {
      dialogError.value = `保存失败：${res?.message || '后端返回非 200'}`
      return
    }
    dialog.value = null
    msg.value = '规则已保存'
    await load()
  } catch (e) {
    dialogError.value = `保存失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    busy.value = false
  }
}

/* ---------- 执行 / 删除（都要确认） ---------- */

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)
const confirmAction = (box: { title: string; detail: string; run: () => Promise<void> }) => { confirmBox.value = box }

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const executeConfirm = (r: ListingPriceRuleRow) => ({
  title: '执行调价规则',
  detail: `将按 ${r.targetPrice != null ? `目标价 ${r.targetPrice}` : `幅度 ${(Number(r.adjustPct) * 100).toFixed(1)}%`} `
    + `经 listingsItems patch 修改 Amazon 上 SKU ${r.sku} 的售价。这是<b>真实改价</b>，不可撤销；`
    + '无真实凭证时后端会显式失败，并把原因写进「上次结果」。',
  run: async () => {
    if (!r.id) return
    busy.value = true
    try {
      const res = await executePriceRule(r.id, shop())
      if (res?.code !== 200) {
        pushError(`执行失败：${res?.message || '后端返回非 200'}`)
        return
      }
      msg.value = '执行完成，结果已写进「上次结果」列'
      await load()
    } catch (e) {
      pushError(`执行失败：${e instanceof Error ? e.message : '调用失败'}`)
    } finally {
      busy.value = false
    }
  }
})

const deleteConfirm = (r: ListingPriceRuleRow) => ({
  title: '删除调价规则',
  detail: `删除 SKU ${r.sku} 的调价规则。已改出去的价格不会因此回滚——回滚需要另建一条反向规则。`,
  run: async () => {
    if (!r.id) return
    busy.value = true
    try {
      const res = await deletePriceRule(r.id, shop())
      if (res?.code !== 200) {
        pushError(`删除失败：${res?.message || '后端返回非 200'}`)
        return
      }
      msg.value = '规则已删除'
      await load()
    } catch (e) {
      pushError(`删除失败：${e instanceof Error ? e.message : '调用失败'}`)
    } finally {
      busy.value = false
    }
  }
})

onMounted(() => { void load() })
</script>

<style scoped>
.price-rules-page { min-height: 100vh; }
.main-content { padding: 1.5rem; }
.hero-section { margin-bottom: 1rem; }
.hero-title { font-size: 1.5rem; font-weight: 700; margin: 0; }
.hero-subtitle { font-size: 0.875rem; color: var(--color-muted); margin: 0.25rem 0 0; }
.notice-zone, .error-zone { padding: 0.75rem 1rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.8125rem; line-height: 1.6; }
.error-zone { background: var(--color-error-light, #fdecea); color: var(--color-error, #c62828); display: flex; gap: 0.5rem; align-items: center; }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); }
.table-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; }
.filter-row { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.data-table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; }
.data-table th, .data-table td { padding: 0.5rem; border-bottom: 1px solid var(--color-border); text-align: left; }
.data-table th { color: var(--color-muted); font-weight: 600; }
.empty-row { text-align: center; color: var(--color-muted); padding: 1.5rem; }
.mono { font-family: ui-monospace, monospace; }
.cell-clip { max-width: 12rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-tag { padding: 0.125rem 0.5rem; border-radius: 999px; font-size: 0.75rem; }
.status-tag.healthy { background: var(--color-primary-light, #e8f5e9); color: var(--color-primary); }
.status-tag.unknown { background: var(--color-muted-light, #eee); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); font-size: 0.75rem; cursor: pointer; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.action-btn.cancel { color: var(--color-error, #c62828); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.modal-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-muted); }
.form-grid input, .form-grid select { padding: 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.8125rem; line-height: 1.6; color: var(--color-on-surface); }
.toast { position: fixed; bottom: 1.5rem; left: 50%; transform: translateX(-50%); background: var(--color-primary); color: var(--color-on-primary); padding: 0.5rem 1rem; border-radius: var(--radius-md); font-size: 0.8125rem; }
</style>
