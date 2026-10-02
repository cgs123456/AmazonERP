<template>
  <div class="audit-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">订单审单</h1>
        <p class="hero-subtitle">规则维护 · 审单判定 · 发货路由建议 · 拆分日志</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺后再使用审单功能。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条边界：① 审单只给判定与建议，<b>不修改订单数据</b>——MERGE/SPLIT 是规则建议，系统里没有合并/拆单实现，
        拆分日志表（amz_order_split_log）全仓零插入点；② <b>shipping_address 这个条件字段永远判不出来</b>
        （Order 模型没有地址字段），预置的 PO Box / APO / 同地址三条规则只会进「未判定」并把 verdict 抬到 REVIEW；
        ③ 路由建议只给仓库<b>类型</b>，具体 warehouse_name 要物流模块确认后才回填，后端已不再拼假仓名。
      </div>

      <template v-if="currentShopId">
        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="gotoTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- ==================== 规则 ==================== -->
        <div v-if="tab === 'rule'" class="tab-panel" data-panel="rule">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">启用状态
                <select v-model="ruleEnabled" @change="loadRules()">
                  <option value="">全部</option>
                  <option value="true">仅启用</option>
                  <option value="false">仅停用</option>
                </select>
              </label>
              <button class="action-btn" :disabled="loading.rule" @click="loadRules()">刷新</button>
              <button class="action-btn" @click="openRuleModal()">新建规则</button>
              <span class="muted">动作/字段/操作符的候选值来自后端实现，不是前端编的</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>规则名</th><th>类型</th><th>条件</th><th>动作</th><th>优先级</th><th>状态</th><th>说明</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in rules" :key="r.id">
                  <td>{{ r.ruleName }}</td>
                  <td>{{ r.ruleType }}</td>
                  <td class="mono">{{ r.conditionField }} {{ r.conditionOp }} {{ r.conditionValue }}
                    <span v-if="isUnresolvedField(r.conditionField)" class="neg">（永远判不出来）</span>
                  </td>
                  <td><span class="status-tag" :class="actionClass(r.action)">{{ r.action }}</span>
                    <span v-if="isAdvisory(r.action)" class="muted">仅建议</span>
                  </td>
                  <td>{{ r.priority ?? 0 }}</td>
                  <td><span class="status-tag" :class="truthy(r.enabled) ? 'healthy' : 'unknown'">{{ truthy(r.enabled) ? '启用' : '停用' }}</span></td>
                  <td class="cell-clip" :title="r.description || ''">{{ r.description || '-' }}</td>
                  <td>
                    <button class="action-btn" :disabled="busy" @click="toggle(r)">{{ truthy(r.enabled) ? '停用' : '启用' }}</button>
                    <button class="action-btn" @click="openRuleModal(r)">编辑</button>
                    <button class="action-btn danger" :disabled="busy" @click="askDelete(r)">删除</button>
                  </td>
                </tr>
                <tr v-if="!loading.rule && !rules.length">
                  <td colspan="8" class="empty-row">该店铺还没有审单规则，审单会一律给 PASS</td>
                </tr>
              </tbody>
            </table>
            <p class="muted note-line">共 {{ rules.length }} 条规则（后端不分页，返回该店全部规则）。</p>
          </div>
        </div>

        <!-- ==================== 单订单审单 ==================== -->
        <div v-if="tab === 'audit'" class="tab-panel" data-panel="audit">
          <div class="table-card">
            <div class="block-title">送审订单（只填规则能看到的字段）</div>
            <div class="filter-row">
              <label class="filter">订单号 *<input v-model="auditForm.amazonOrderId" placeholder="114-1111111-1111111" /></label>
              <label class="filter">买家名<input v-model="auditForm.buyerName" /></label>
              <label class="filter">订单状态<input v-model="auditForm.orderStatus" placeholder="Pending" /></label>
              <label class="filter">发货渠道
                <select v-model="auditForm.fulfillmentChannel">
                  <option value="">（空）</option>
                  <option value="AFN">AFN（自发货）</option>
                  <option value="FBA">FBA</option>
                </select>
              </label>
              <label class="filter">金额<input type="number" step="0.01" v-model="auditForm.finalPrice" /></label>
              <label class="filter">Marketplace<input v-model="auditForm.marketplaceId" placeholder="ATVPDKIKX0DER" /></label>
              <button class="action-btn" :disabled="busy" @click="runAudit()">审这一单</button>
            </div>
            <p class="muted note-line">
              这里送的是「按你填的字段跑一次规则」，不是从库里取真单：后端不会替你校验订单号是否属于本店。
            </p>
          </div>

          <div v-if="result" class="table-card">
            <div class="block-title">
              判定结果
              <span class="status-tag" :class="verdictClass(result.verdict)">{{ result.verdict }}</span>
            </div>
            <div class="filter-row">
              <span class="muted">订单 {{ result.orderId || '-' }}</span>
              <span class="muted">命中 {{ result.alertCount }} 条</span>
              <span class="muted">未判定 {{ result.unevaluatedCount }} 条</span>
              <span class="muted">动作 {{ (result.actions || []).join('/') || '无' }}</span>
            </div>
            <p v-if="result.advisoryNote" class="advisory">{{ result.advisoryNote }}</p>

            <table class="data-table">
              <thead><tr><th>规则</th><th>类型</th><th>动作</th><th>说明</th></tr></thead>
              <tbody>
                <tr v-for="(a, i) in result.alerts" :key="'a' + i">
                  <td>{{ a.ruleName || a.ruleId }}</td>
                  <td>{{ a.ruleType || '-' }}</td>
                  <td><span class="status-tag" :class="actionClass(a.action)">{{ a.action }}</span></td>
                  <td class="cell-clip" :title="a.description || ''">{{ a.description || '-' }}</td>
                </tr>
                <tr v-if="!result.alerts.length"><td colspan="4" class="empty-row">没有规则命中</td></tr>
              </tbody>
            </table>

            <table class="data-table">
              <thead><tr><th>未判定的规则</th><th>条件</th><th>动作</th><th>原因</th></tr></thead>
              <tbody>
                <tr v-for="(u, i) in result.unevaluatedRules" :key="'u' + i">
                  <td>{{ u.ruleName || u.ruleId }}</td>
                  <td class="mono">{{ u.conditionField }} {{ u.conditionOp }}</td>
                  <td>{{ u.action || '-' }}</td>
                  <td class="reason">{{ u.reason }}</td>
                </tr>
                <tr v-if="!result.unevaluatedRules.length"><td colspan="4" class="empty-row">全部规则都判定成功</td></tr>
              </tbody>
            </table>
            <p class="muted note-line">
              「未判定」不等于「没风险」：verdict 会把未判定计入 REVIEW，这是 fail-closed 的结果。
            </p>
          </div>
        </div>

        <!-- ==================== 批量审单 ==================== -->
        <div v-if="tab === 'batch'" class="tab-panel" data-panel="batch">
          <div class="table-card">
            <div class="block-title">批量送审（粘贴订单 JSON 数组）</div>
            <div class="filter-row">
              <textarea class="json-box" rows="8" v-model="batchText"
                        placeholder='[{"amazonOrderId":"114-1","orderStatus":"Pending","finalPrice":120.5,"fulfillmentChannel":"AFN"}]'></textarea>
            </div>
            <div class="filter-row">
              <button class="action-btn" :disabled="busy" @click="runBatch()">执行批量审单</button>
              <span class="muted">数组每一项就是一条订单快照；空数组后端返回空结果，不会造示例数据</span>
            </div>
          </div>

          <div v-if="batchResults.length" class="table-card">
            <div class="block-title">批量结果（共 {{ batchResults.length }} 条）</div>
            <table class="data-table">
              <thead><tr><th>订单号</th><th>verdict</th><th>命中</th><th>未判定</th><th>动作</th><th>建议类动作</th></tr></thead>
              <tbody>
                <tr v-for="(b, i) in batchResults" :key="'b' + i">
                  <td class="mono">{{ b.orderId || '-' }}</td>
                  <td><span class="status-tag" :class="verdictClass(b.verdict)">{{ b.verdict }}</span></td>
                  <td>{{ b.alertCount }}</td>
                  <td>{{ b.unevaluatedCount }}</td>
                  <td>{{ (b.actions || []).join('/') || '-' }}</td>
                  <td>{{ (b.advisoryActions || []).join('/') || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 发货路由 ==================== -->
        <div v-if="tab === 'route'" class="tab-panel" data-panel="route">
          <div class="table-card">
            <div class="block-title">路由建议</div>
            <div class="filter-row">
              <label class="filter">订单号 *<input v-model="routeForm.amazonOrderId" /></label>
              <label class="filter">SKU *<input v-model="routeForm.sku" /></label>
              <label class="filter">ASIN<input v-model="routeForm.asin" /></label>
              <label class="filter">数量<input type="number" min="1" v-model="routeForm.quantity" /></label>
              <label class="filter">目的国
                <select v-model="routeForm.country">
                  <option v-for="c in ROUTE_COUNTRIES" :key="c" :value="c">{{ c }}</option>
                </select>
              </label>
              <button class="action-btn danger" :disabled="busy" @click="askRoute()">生成并入库</button>
            </div>
            <p class="muted note-line">
              这个动作会真的往 amz_shipment_routing 插一行：每点一次一条，请按需生成。
              仓库类型由目的国规则决定，具体仓库名不在订单模块解析。
            </p>
          </div>

          <div v-if="routing" class="table-card">
            <div class="block-title">最近一次路由结果（id={{ routing.id ?? '未回填' }}）</div>
            <table class="data-table">
              <thead><tr><th>订单号</th><th>SKU</th><th>数量</th><th>仓库类型</th><th>仓库名</th><th>仓库ID</th><th>原因</th><th>时间</th></tr></thead>
              <tbody>
                <tr>
                  <td class="mono">{{ routing.amazonOrderId || '-' }}</td>
                  <td class="mono">{{ routing.sku || '-' }}</td>
                  <td>{{ routing.quantity ?? '-' }}</td>
                  <td><span class="status-tag" :class="routing.warehouseType === 'FBA' ? 'healthy' : 'risk'">{{ routing.warehouseType || '-' }}</span></td>
                  <td><span v-if="routing.warehouseName" class="mono">{{ routing.warehouseName }}</span>
                    <span v-else class="neg">未解析（需物流模块确认）</span></td>
                  <td>{{ routing.warehouseId ?? '-' }}</td>
                  <td class="cell-clip" :title="routing.selectedReason || ''">{{ routing.selectedReason || '-' }}</td>
                  <td class="mono">{{ routing.routeTime || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 拆分日志 ==================== -->
        <div v-if="tab === 'split'" class="tab-panel" data-panel="split">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">原订单号<input v-model="splitOrderId" @keyup.enter="loadSplitLogs()" /></label>
              <button class="action-btn" :disabled="loading.split" @click="loadSplitLogs()">刷新</button>
              <span class="muted">只读：这张表在系统里没有写入方</span>
            </div>
            <table class="data-table">
              <thead><tr><th>原订单号</th><th>子订单号</th><th>原因</th><th>明细</th><th>操作人</th><th>时间</th></tr></thead>
              <tbody>
                <tr v-for="s in splitLogs" :key="s.id">
                  <td class="mono">{{ s.originalOrderId }}</td>
                  <td class="mono">{{ s.splitOrderId }}</td>
                  <td>{{ s.splitReason || '-' }}</td>
                  <td class="cell-clip" :title="s.splitItems || ''">{{ s.splitItems || '-' }}</td>
                  <td>{{ s.operator || '-' }}</td>
                  <td class="mono">{{ s.splitTime || '-' }}</td>
                </tr>
                <tr v-if="!loading.split && !splitLogs.length">
                  <td colspan="6" class="empty-row">
                    拆分日志为空是真实状态：全仓没有 amz_order_split_log 的插入点，MERGE/SPLIT 规则不会写这张表
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </main>

    <!-- 规则弹窗 -->
    <div v-if="ruleModal" class="modal-mask" @click.self="ruleModal = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ editingId ? '编辑审单规则' : '新建审单规则' }}</h3>
        <p class="modal-note">
          条件字段、操作符、动作的候选值来自后端实现；选了 shipping_address 的规则不会命中，只会进「未判定」。
        </p>
        <div class="form-grid">
          <label>规则名 *<input v-model="ruleForm.ruleName" /></label>
          <label>规则类型 *<input v-model="ruleForm.ruleType" placeholder="如 ADDRESS_CHECK / AMOUNT_CHECK" /></label>
          <label>条件字段 *
            <select v-model="ruleForm.conditionField">
              <option v-for="f in RULE_FIELDS" :key="f" :value="f">{{ f }}</option>
            </select>
          </label>
          <label>操作符 *
            <select v-model="ruleForm.conditionOp">
              <option v-for="o in RULE_OPS" :key="o" :value="o">{{ o }}</option>
            </select>
          </label>
          <label>条件值 *<input v-model="ruleForm.conditionValue" /></label>
          <label>动作 *
            <select v-model="ruleForm.action">
              <option v-for="a in RULE_ACTIONS" :key="a" :value="a">{{ a }}</option>
            </select>
          </label>
          <label>优先级<input type="number" v-model="ruleForm.priority" /></label>
          <label>启用
            <select v-model="ruleForm.enabled"><option :value="1">启用</option><option :value="0">停用</option></select>
          </label>
          <label class="span2">说明<input v-model="ruleForm.description" /></label>
          <label class="span2">动作参数（JSON，可选）<textarea v-model="ruleForm.actionParams" rows="2" /></label>
        </div>
        <div v-if="isUnresolvedField(ruleForm.conditionField)" class="advisory">
          这条规则的字段取不到值，保存后每次审单都会记为「未判定」，不会真的拦截订单。
        </div>
        <div v-else-if="isAdvisory(ruleForm.action)" class="advisory">
          {{ ruleForm.action }} 只给建议：本系统没有合并/拆单实现，保存后不会改变订单数据。
        </div>
        <div class="modal-actions">
          <button class="page-btn" @click="ruleModal = false">取消</button>
          <button class="page-btn" :disabled="busy" @click="submitRule()">提交</button>
        </div>
      </div>
    </div>

    <!-- 二次确认 -->
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
import { reactive, ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import * as audit from '@/api/orderAudit'
import {
  RULE_ACTIONS, RULE_FIELDS, RULE_OPS, UNRESOLVED_FIELDS, ADVISORY_ACTIONS
} from '@/api/orderAudit'
import type {
  OrderAuditRule, AuditResult, AuditOrderInput, ShipmentRouting, OrderSplitLog
} from '@/api/orderAudit'

type TabKey = 'rule' | 'audit' | 'batch' | 'route' | 'split'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'rule', label: '审单规则' },
  { key: 'audit', label: '单订单审单' },
  { key: 'batch', label: '批量审单' },
  { key: 'route', label: '发货路由' },
  { key: 'split', label: '拆分日志' }
]
/** 后端 routeOrder 的 FBA 覆盖国家表 + 一个非覆盖示例，值来自实现里的常量 */
const ROUTE_COUNTRIES = ['US', 'CA', 'MX', 'GB', 'DE', 'FR', 'IT', 'ES', 'JP', 'AU', 'SG']

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('rule')
const errors = ref<string[]>([])
const busy = ref(false)
const loading = reactive<Record<'rule' | 'split', boolean>>({ rule: false, split: false })

const rules = ref<OrderAuditRule[]>([])
const ruleEnabled = ref('')
const ruleModal = ref(false)
const editingId = ref<number | null>(null)
const ruleForm = reactive<Record<string, any>>({})

const auditForm = reactive<AuditOrderInput>({})
const result = ref<AuditResult | null>(null)
const batchText = ref('')
const batchResults = ref<AuditResult[]>([])

const routeForm = reactive<{ amazonOrderId: string; sku: string; asin: string; quantity: number | string; country: string }>({
  amazonOrderId: '', sku: '', asin: '', quantity: 1, country: 'US'
})
const routing = ref<ShipmentRouting | null>(null)

const splitLogs = ref<OrderSplitLog[]>([])
const splitOrderId = ref('')

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

const truthy = (v: unknown) => v === true || v === 1 || v === '1'
const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const isUnresolvedField = (f?: string) => !!f && UNRESOLVED_FIELDS.includes(f)
const isAdvisory = (a?: string) => !!a && ADVISORY_ACTIONS.includes(a)

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

/* ---------- 加载 ---------- */
const loadRules = async () => {
  const shopId = shop()
  if (!shopId) return
  loading.rule = true
  errors.value = []
  try {
    const enabled = ruleEnabled.value === '' ? undefined : ruleEnabled.value === 'true'
    const res = await audit.listRules(shopId, enabled)
    if (res?.code !== 200) {
      pushError(`规则列表：${res?.message || '后端返回非 200'}`)
      rules.value = []
      return
    }
    rules.value = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    pushError(`规则列表：${e instanceof Error ? e.message : '调用失败'}`)
    rules.value = []
  } finally {
    loading.rule = false
  }
}

const loadSplitLogs = async () => {
  const shopId = shop()
  if (!shopId) return
  loading.split = true
  errors.value = []
  try {
    const res = await audit.listSplitLogs(shopId, splitOrderId.value || undefined)
    if (res?.code !== 200) {
      pushError(`拆分日志：${res?.message || '后端返回非 200'}`)
      splitLogs.value = []
      return
    }
    splitLogs.value = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    pushError(`拆分日志：${e instanceof Error ? e.message : '调用失败'}`)
    splitLogs.value = []
  } finally {
    loading.split = false
  }
}

/* ---------- 规则动作 ---------- */
const openRuleModal = (r?: OrderAuditRule) => {
  editingId.value = r?.id ?? null
  Object.keys(ruleForm).forEach((k) => delete ruleForm[k])
  Object.assign(ruleForm, {
    ruleName: r?.ruleName || '',
    ruleType: r?.ruleType || '',
    conditionField: r?.conditionField || RULE_FIELDS[0],
    conditionOp: r?.conditionOp || 'EQ',
    conditionValue: r?.conditionValue || '',
    action: r?.action || 'FLAG',
    priority: r?.priority ?? 0,
    enabled: r ? (truthy(r.enabled) ? 1 : 0) : 1,
    description: r?.description || '',
    actionParams: r?.actionParams || ''
  })
  ruleModal.value = true
}

const submitRule = async () => {
  const required = ['ruleName', 'ruleType', 'conditionField', 'conditionValue', 'action'] as const
  const missing = required.filter((k) => !String(ruleForm[k] ?? '').trim())
  if (missing.length) {
    pushError(`规则保存：${missing.join('、')} 必填`)
    return
  }
  const shopId = shop()
  const body: Record<string, unknown> = {
    ...ruleForm,
    shopId,
    priority: Number(ruleForm.priority ?? 0),
    enabled: truthy(ruleForm.enabled),
    actionParams: String(ruleForm.actionParams || '').trim() || null
  }
  const id = editingId.value
  const ok = await call(id ? '更新规则' : '新建规则', () =>
    (id ? audit.updateRule(id, body as Partial<OrderAuditRule>) : audit.createRule(body as Partial<OrderAuditRule>)),
    () => undefined)
  if (ok) {
    ruleModal.value = false
    await loadRules()
  }
}

const toggle = async (r: OrderAuditRule) => {
  const next = !truthy(r.enabled)
  // toggleRule 对不存在的规则静默 no-op 并仍返回 true，所以结果只以重新加载后的列表为准
  const ok = await call(next ? '启用规则' : '停用规则', () => audit.toggleRule(r.id as number, next), () => undefined)
  if (ok) await loadRules()
}

const askDelete = (r: OrderAuditRule) => {
  confirmBox.value = {
    title: `删除规则「${r.ruleName}」`,
    detail: '删除不可恢复。后端按规则 id 删除且不校验该规则属于当前店铺，'
      + '请确认 id 正确；已被删除的规则不会再参与后续审单。',
    run: async () => {
      const ok = await call('删除规则', () => audit.deleteRule(r.id as number), () => undefined)
      if (ok) await loadRules()
    }
  }
}

/* ---------- 审单 ---------- */
const buildAuditPayload = (): AuditOrderInput | null => {
  const f = { ...auditForm }
  if (!String(f.amazonOrderId || '').trim()) {
    pushError('审单：订单号必填')
    return null
  }
  const out: AuditOrderInput = { amazonOrderId: String(f.amazonOrderId).trim() }
  if (String(f.buyerName || '').trim()) out.buyerName = String(f.buyerName).trim()
  if (String(f.orderStatus || '').trim()) out.orderStatus = String(f.orderStatus).trim()
  if (String(f.fulfillmentChannel || '').trim()) out.fulfillmentChannel = String(f.fulfillmentChannel).trim()
  if (String(f.marketplaceId || '').trim()) out.marketplaceId = String(f.marketplaceId).trim()
  if (f.finalPrice !== undefined && f.finalPrice !== null && String(f.finalPrice) !== '') {
    const n = Number(f.finalPrice)
    if (!Number.isFinite(n)) {
      pushError('审单：金额不是数字，数值类规则会判为「未判定」')
      return null
    }
    out.finalPrice = n
  }
  return out
}

const runAudit = async () => {
  const payload = buildAuditPayload()
  if (!payload) return
  const ok = await call('审单', () => audit.auditOrder(shop(), payload), (r) => { result.value = r })
  return ok
}

const runBatch = async () => {
  const text = batchText.value.trim()
  if (!text) {
    pushError('批量审单：请先粘贴订单 JSON 数组')
    return
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    pushError(`批量审单：JSON 解析失败（${e instanceof Error ? e.message : '未知错误'}）`)
    return
  }
  if (!Array.isArray(parsed)) {
    pushError('批量审单：顶层必须是数组')
    return
  }
  await call('批量审单', () => audit.batchAudit(shop(), parsed as AuditOrderInput[]),
    (rows) => { batchResults.value = Array.isArray(rows) ? rows : [] })
}

/* ---------- 路由 ---------- */
const askRoute = () => {
  if (!String(routeForm.amazonOrderId || '').trim() || !String(routeForm.sku || '').trim()) {
    pushError('路由建议：订单号与 SKU 必填')
    return
  }
  confirmBox.value = {
    title: '生成发货路由并入库',
    detail: '会调用 GET /order/audit/route 并往 amz_shipment_routing 插入一行记录（每点一次一条）。'
      + '返回只含仓库类型建议，warehouse_name/warehouse_id 保持为空，需物流模块确认后才回填。',
    run: async () => {
      await call('路由建议', () => audit.routeOrder(shop(), {
        amazonOrderId: String(routeForm.amazonOrderId).trim(),
        sku: String(routeForm.sku).trim(),
        asin: String(routeForm.asin || '').trim() || undefined,
        quantity: Number(routeForm.quantity || 1),
        country: routeForm.country
      }), (r) => { routing.value = r })
    }
  }
}

/* ---------- 展示归类 ---------- */
const verdictClass = (v?: string) => (v === 'PASS' ? 'healthy' : v === 'BLOCKED' ? 'urgent' : 'risk')
const actionClass = (a?: string) => {
  if (a === 'BLOCK') return 'urgent'
  if (a === 'FLAG' || a === 'ALERT') return 'risk'
  return 'unknown'
}

const TAB_LOADERS: Record<TabKey, () => Promise<unknown>> = {
  rule: () => loadRules(),
  audit: async () => undefined,
  batch: async () => undefined,
  route: async () => undefined,
  split: () => loadSplitLogs()
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
  await gotoTab('rule')
})
</script>

<style scoped>
.audit-page { background: var(--color-background); }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.neg { color: var(--color-error); font-weight: 600; font-size: 0.75rem; }
.reason { font-size: 0.75rem; color: var(--color-error); max-width: 18rem; }
.cell-clip { max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.block-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); padding: 0.75rem 1rem 0.25rem; display: flex; align-items: center; gap: 0.5rem; }
.table-card { margin-bottom: 1rem; }
.note-line { padding: 0.5rem 1rem 0.75rem; margin: 0; }
.advisory { margin: 0.5rem 1rem; padding: 0.5rem 0.75rem; background: var(--color-warning-light); color: var(--color-warning-dark); border-radius: var(--radius-sm); font-size: 0.8125rem; line-height: 1.5; }
.json-box { width: 100%; padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-family: var(--font-mono, monospace); font-size: 0.8125rem; background: var(--color-background); color: var(--color-on-surface); }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 620px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-note { font-size: 0.75rem; color: var(--color-muted); margin: 0 0 1rem; line-height: 1.5; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid .span2 { grid-column: 1 / -1; }
.form-grid input, .form-grid select, .form-grid textarea { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-background); color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
@media (max-width: 768px) { .form-grid { grid-template-columns: 1fr; } }
</style>
