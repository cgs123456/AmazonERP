<template>
  <div class="ops-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">多平台运营台</h1>
        <p class="hero-subtitle">平台账号 · 商品映射 · 消息分配 · 库存视图 · Webhook 事件 · ISV 应用</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺：这些列表都按选中店铺读取。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        四条边界：<b>① 凭证只写不回显</b>——账号列表里 apiKey 与三个 *Encrypted 列由后端抹掉，
        页面没有「查看密钥」，编辑时留空表示不改；apiKey 目前按原文入库（后端没有加密步骤），
        所以不要贴生产密钥，其余凭证列页面不提供写入。<b>②「测试连接」是真探测</b>：
        后端拿这家店的凭证向平台发一次已鉴权订单读，平台回话才写 ACTIVE，凭证缺失/签名被拒/网络不通写 ERROR；
        探测不算同步，所以不会刷新「最后同步」；亚马逊账号会被点名拒绝（本模块不探测亚马逊），
        那是「没有这个能力」而不是「平台坏了」。<b>③ 消息回复不放按钮</b>：后端已改成「先向平台真发、
        拿到平台消息 ID 才写本地记账」，而三家都还没接发送 method，生产档一律点名拒绝、一行都不写。
        <b>④ 商品/库存/消息的「同步」按钮不做</b>：三家的真实客户端对这些动作一律抛「未接入」。
      </div>

      <div class="tabs">
        <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                @click="gotoTab(t.key)">{{ t.label }}</button>
      </div>

      <template v-if="currentShopId">
        <!-- ==================== 平台账号 ==================== -->
        <div v-if="tab === 'accounts'" class="tab-panel" data-panel="accounts">
          <div class="table-card">
            <div class="filter-row">
              <button class="action-btn" :disabled="accounts.loading.value" @click="loadAccounts()">刷新</button>
              <button v-if="accounts.truncated.value" class="action-btn"
                      :disabled="accounts.loading.value" @click="loadAccounts(true)">下一页</button>
              <button class="action-btn primary" @click="resetAccountForm()">新增平台账号</button>
              <span class="muted">{{ pagerText(accounts) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>平台</th><th>店铺名</th><th>API 端点</th><th>状态</th>
                  <th>token 到期</th><th>最后同步</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="a in accounts.rows.value" :key="a.id">
                  <td><span class="status-tag plat">{{ a.platform }}</span></td>
                  <td>{{ a.storeName || '-' }}</td>
                  <td class="mono cell-clip" :title="a.apiEndpoint || ''">{{ a.apiEndpoint || '-' }}</td>
                  <td><span class="status-tag" :class="accountClass(a.status)">{{ a.status || '-' }}</span></td>
                  <td class="mono">{{ a.tokenExpiresAt || '-' }}</td>
                  <td class="mono">{{ a.lastSyncTime || '从未' }}</td>
                  <td class="row-actions">
                    <button class="action-btn" :disabled="busy" @click="testAccount(a)">测试连接</button>
                    <button class="action-btn" :disabled="busy" @click="editAccount(a)">编辑</button>
                    <button class="action-btn danger" :disabled="busy" @click="askDeleteAccount(a)">删除</button>
                  </td>
                </tr>
                <tr v-if="!accounts.loading.value && !accounts.rows.value.length">
                  <td colspan="7" class="empty-row">
                    这家店还没有平台账号。没有账号时同步会失败或跳过——凭证缺失不等于平台没有数据。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="accountFormOpen" class="table-card form-card">
            <h3 class="card-title">{{ accountForm.id ? '编辑账号 #' + accountForm.id : '新增平台账号' }}</h3>
            <div class="form-grid">
              <label class="field">平台 *
                <select v-model="accountForm.platform">
                  <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
                </select>
              </label>
              <label class="field">店铺名称<input v-model="accountForm.storeName" /></label>
              <label class="field">API 端点<input v-model="accountForm.apiEndpoint" placeholder="https://open-api.temu.com" /></label>
              <label class="field">apiKey（留空＝不改）<input v-model="accountForm.apiKey" /></label>
              <label v-if="accountForm.id" class="field">状态
                <select v-model="accountForm.status">
                  <option v-for="s in ACCOUNT_STATUSES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
            </div>
            <p class="muted form-hint">
              新建时状态由后端固定为 ACTIVE；凭证列不回显，编辑时看不到旧值，留空即保持原值。
              apiKey 会被原样写入（后端目前不做加密），要维护 apiSecret / access / refresh 请走后端凭证流程。
            </p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !accountFormReady" @click="askSubmitAccount()">
                {{ accountForm.id ? '保存修改' : '创建账号' }}
              </button>
              <button class="page-btn" @click="accountFormOpen = false">取消</button>
            </div>
          </div>
        </div>

        <!-- ==================== 商品映射 ==================== -->
        <div v-if="tab === 'products'" class="tab-panel" data-panel="products">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">平台
                <select v-model="productPlatform" @change="loadProducts()">
                  <option value="">全部平台</option>
                  <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="products.loading.value" @click="loadProducts()">刷新</button>
              <button v-if="products.truncated.value" class="action-btn"
                      :disabled="products.loading.value" @click="loadProducts(true)">下一页</button>
              <span class="muted">{{ pagerText(products) }}；映射是覆盖写，同一个商品再次提交会替换旧映射</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>平台</th><th>平台商品 ID</th><th>标题</th><th>价</th><th>平台库存</th><th>状态</th>
                  <th>ASIN</th><th>Amazon SKU</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in products.rows.value" :key="p.id">
                  <td><span class="status-tag plat">{{ p.platform }}</span></td>
                  <td class="mono">{{ p.platformProductId }}</td>
                  <td class="cell-clip" :title="p.title || ''">{{ p.title || '-' }}</td>
                  <td>{{ num(p.price) }} {{ p.currency || '' }}</td>
                  <td>{{ p.stockQty ?? '-' }}</td>
                  <td><span class="status-tag" :class="productClass(p.status)">{{ p.status || '-' }}</span></td>
                  <td class="mono">{{ p.amazonAsin || '未映射' }}</td>
                  <td class="mono">{{ p.amazonSku || '-' }}</td>
                  <td><button class="action-btn" :disabled="busy" @click="openMapForm(p)">映射</button></td>
                </tr>
                <tr v-if="!products.loading.value && !products.rows.value.length">
                  <td colspan="9" class="empty-row">
                    本地没有该平台商品记录。这些行只由平台同步写入，而商品同步目前未接入，
                    所以这里为空是「从没导入过」，不是「没有在售商品」。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="mapFormOpen" class="table-card form-card">
            <h3 class="card-title">映射到 Amazon：{{ mapForm.platformProductId }}（{{ mapForm.platform }}）</h3>
            <div class="form-grid">
              <label class="field">ASIN *<input v-model="mapForm.amazonAsin" placeholder="B0ABC12345" /></label>
              <label class="field">Amazon SKU<input v-model="mapForm.amazonSku" /></label>
            </div>
            <p class="muted form-hint">后端会把 ASIN 去空白并转大写；SKU 留空表示清除。</p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !mapFormReady" @click="askSubmitMap()">提交映射</button>
              <button class="page-btn" @click="mapFormOpen = false">取消</button>
            </div>
          </div>
        </div>

        <!-- ==================== 消息 ==================== -->
        <div v-if="tab === 'messages'" class="tab-panel" data-panel="messages">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">平台
                <select v-model="messagePlatform" @change="loadMessages()">
                  <option value="">全部</option>
                  <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
                </select>
              </label>
              <label class="filter">状态
                <select v-model="messageStatus" @change="loadMessages()">
                  <option value="">全部</option>
                  <option v-for="s in MESSAGE_STATUSES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="messages.loading.value" @click="loadMessages()">刷新</button>
              <button v-if="messages.truncated.value" class="action-btn"
                      :disabled="messages.loading.value" @click="loadMessages(true)">下一页</button>
              <span class="muted">{{ pagerText(messages) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>时间</th><th>方向</th><th>平台</th><th>买家</th><th>主题</th>
                  <th>状态</th><th>处理人</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="m in messages.rows.value" :key="m.id">
                  <td class="mono">{{ m.receiveTime || '-' }}</td>
                  <td>{{ m.direction === 'OUT' ? '我方发出' : '买家来信' }}</td>
                  <td><span class="status-tag plat">{{ m.platform }}</span></td>
                  <td>{{ m.buyerName || '-' }}</td>
                  <td class="cell-clip" :title="m.subject || ''">{{ m.subject || '-' }}</td>
                  <td><span class="status-tag" :class="messageClass(m.status)">{{ m.status || '-' }}</span></td>
                  <td>{{ m.assignedTo || '未分配' }}</td>
                  <td><button class="action-btn" :disabled="busy" @click="askAssign(m)">分配处理人</button></td>
                </tr>
                <tr v-if="!messages.loading.value && !messages.rows.value.length">
                  <td colspan="8" class="empty-row">
                    当前筛选下没有消息。回复功能本页不提供：后端只把回复文本写进本地库，
                    不会发到平台或买家，那样做会让人误以为买家收到了。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== 库存 ==================== -->
        <div v-if="tab === 'inventory'" class="tab-panel" data-panel="inventory">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">平台
                <select v-model="inventoryPlatform" @change="loadInventory()">
                  <option value="">全部</option>
                  <option v-for="p in PLATFORMS" :key="p" :value="p">{{ p }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="inventory.loading.value" @click="loadInventory()">刷新</button>
              <button v-if="inventory.truncated.value" class="action-btn"
                      :disabled="inventory.loading.value" @click="loadInventory(true)">下一页</button>
              <button class="action-btn primary" :disabled="busy" @click="loadAggregate()">按平台+SKU 汇总</button>
              <span class="muted">{{ pagerText(inventory) }}</span>
            </div>
            <div v-if="aggregate" class="metric-grid">
              <div class="metric"><span class="metric-label">可售总量</span>
                <span class="metric-value">{{ aggregate.grandTotalAvailable }}</span></div>
              <div class="metric"><span class="metric-label">SKU 数</span>
                <span class="metric-value">{{ Object.keys(aggregate.bySku).length }}</span></div>
              <div class="metric"><span class="metric-label">本次聚合时刻</span>
                <span class="metric-value mono small">{{ aggregate.computedAt }}</span></div>
            </div>
            <p v-if="aggregate" class="muted aggregate-note">
              汇总读的是本地库存表的行数求和；上面那个时间是<b>本次计算的时刻</b>，不是平台侧库存快照时间
              （每行的 snapshotTime 才是各自的快照时间）。库存同步未接入，所以这里可能一直是旧数据。
            </p>
            <table class="data-table">
              <thead>
                <tr><th>快照时间</th><th>平台</th><th>SKU</th><th>仓库</th>
                  <th>可售</th><th>预留</th><th>在途</th><th>平台商品 ID</th></tr>
              </thead>
              <tbody>
                <tr v-for="i in inventory.rows.value" :key="i.id">
                  <td class="mono">{{ i.snapshotTime || '-' }}</td>
                  <td><span class="status-tag plat">{{ i.platform }}</span></td>
                  <td class="mono">{{ i.sku }}</td>
                  <td>{{ i.warehouse || '-' }}</td>
                  <td :class="qtyClass(i.availableQty)">{{ i.availableQty ?? '-' }}</td>
                  <td>{{ i.reservedQty ?? '-' }}</td>
                  <td>{{ i.inboundQty ?? '-' }}</td>
                  <td class="mono cell-clip" :title="i.platformProductId || ''">{{ i.platformProductId || '-' }}</td>
                </tr>
                <tr v-if="!inventory.loading.value && !inventory.rows.value.length">
                  <td colspan="8" class="empty-row">本地没有该店铺的库存快照行（库存同步尚未接入）。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== Webhook ==================== -->
        <div v-if="tab === 'webhook'" class="tab-panel" data-panel="webhook">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="webhookStatus" @change="loadWebhooks()">
                  <option value="">全部</option>
                  <option v-for="s in WEBHOOK_STATUSES" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="webhooks.loading.value" @click="loadWebhooks()">刷新</button>
              <button v-if="webhooks.truncated.value" class="action-btn"
                      :disabled="webhooks.loading.value" @click="loadWebhooks(true)">下一页</button>
              <span class="muted">{{ pagerText(webhooks) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>时间</th><th>平台</th><th>事件类型</th><th>eventId</th>
                  <th>状态</th><th>处理结果</th><th>处理时间</th></tr>
              </thead>
              <tbody>
                <tr v-for="e in webhooks.rows.value" :key="e.id">
                  <td class="mono">{{ e.createTime || '-' }}</td>
                  <td><span class="status-tag plat">{{ e.platform }}</span></td>
                  <td class="mono">{{ e.eventType || '-' }}</td>
                  <td class="mono cell-clip" :title="e.eventId || ''">{{ e.eventId || '-' }}</td>
                  <td><span class="status-tag" :class="webhookClass(e.status)">{{ e.status || '-' }}</span></td>
                  <td class="reason">{{ e.processResult || '-' }}</td>
                  <td class="mono">{{ e.processTime || '-' }}</td>
                </tr>
                <tr v-if="!webhooks.loading.value && !webhooks.rows.value.length">
                  <td colspan="7" class="empty-row">
                    没有 webhook 事件记录。PROCESSED 只代表「事件已入库且处理未抛异常」，
                    当前事件分发只写日志，不会改订单/库存/消息。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== ISV 应用 ==================== -->
        <div v-if="tab === 'apps'" class="tab-panel" data-panel="apps">
          <div class="table-card">
            <div class="filter-row">
              <button class="action-btn" :disabled="apps.loading.value" @click="loadApps()">刷新</button>
              <button v-if="apps.truncated.value" class="action-btn"
                      :disabled="apps.loading.value" @click="loadApps(true)">下一页</button>
              <button class="action-btn primary" @click="resetAppForm()">注册新应用</button>
              <span class="muted">{{ pagerText(apps) }}</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>应用名</th><th>App Key</th><th>Scopes</th><th>回调地址</th>
                  <th>限流/分钟</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="a in apps.rows.value" :key="a.id">
                  <td>{{ a.appName }}</td>
                  <td class="mono">{{ a.appKey || '-' }}</td>
                  <td class="mono cell-clip" :title="a.scopes || ''">{{ a.scopes || '-' }}</td>
                  <td class="mono cell-clip" :title="a.redirectUris || ''">{{ a.redirectUris || '-' }}</td>
                  <td>{{ a.rateLimitRpm ?? '-' }}</td>
                  <td><span class="status-tag" :class="appClass(a.status)">{{ a.status || '-' }}</span></td>
                  <td><button class="action-btn danger" :disabled="busy" @click="askRotate(a)">轮换密钥</button></td>
                </tr>
                <tr v-if="!apps.loading.value && !apps.rows.value.length">
                  <td colspan="7" class="empty-row">这家店还没有 ISV 应用。</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="issued" class="table-card secret-card">
            <h3 class="card-title">一次性密钥（关闭后无法再查看）</h3>
            <p class="muted">appKey <span class="mono">{{ issued.appKey }}</span></p>
            <p class="muted">appSecret <span class="mono secret-value">{{ issued.appSecret }}</span></p>
            <p class="muted">
              后端只存密钥的 SHA-256，明文只在这一次响应里出现；页面不会把它写进任何状态里，
              刷新或切换标签后就再也取不到。请现在复制保存。
            </p>
            <div class="form-actions">
              <button class="page-btn" @click="issued = null">我已保存</button>
            </div>
          </div>

          <div v-if="appFormOpen" class="table-card form-card">
            <h3 class="card-title">注册 ISV 应用</h3>
            <div class="form-grid">
              <label class="field">应用名 *<input v-model="appForm.appName" /></label>
              <label class="field">Scopes（逗号分隔）<input v-model="appForm.scopes" placeholder="order.read,inventory.read" /></label>
              <label class="field">回调地址<input v-model="appForm.redirectUris" /></label>
              <label class="field">限流/分钟<input v-model="appForm.rateLimitRpm" type="number" min="1" /></label>
            </div>
            <p class="muted form-hint">应用必须归属当前店铺（后端把 owner_shop_id 设为 NOT NULL，缺归属会被直接拒）。</p>
            <div class="form-actions">
              <button class="page-btn" :disabled="busy || !appFormReady" @click="askRegisterApp()">注册</button>
              <button class="page-btn" @click="appFormOpen = false">取消</button>
            </div>
          </div>
        </div>
      </template>
    </main>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p v-if="confirmBox.inputLabel" class="field-line">
          {{ confirmBox.inputLabel }} <input v-model="confirmInput" class="cell-input" />
        </p>
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
import * as api from '@/api/multiplatform'
import {
  ACCOUNT_STATUSES, MESSAGE_STATUSES, PLATFORMS, WEBHOOK_STATUSES
} from '@/api/multiplatform'
import type {
  InventoryAggregate, OauthApp, OauthSecretIssue, PlatformAccount,
  PlatformInventory, PlatformMessage, PlatformProduct, WebhookEvent
} from '@/api/multiplatform'

type TabKey = 'accounts' | 'products' | 'messages' | 'inventory' | 'webhook' | 'apps'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'accounts', label: '平台账号' },
  { key: 'products', label: '商品映射' },
  { key: 'messages', label: '消息' },
  { key: 'inventory', label: '库存' },
  { key: 'webhook', label: 'Webhook 事件' },
  { key: 'apps', label: 'ISV 应用' }
]

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('accounts')
const errors = ref<string[]>([])
const busy = ref(false)

const accounts = mkList<PlatformAccount>()
const products = mkList<PlatformProduct>()
const messages = mkList<PlatformMessage>()
const inventory = mkList<PlatformInventory>()
const webhooks = mkList<WebhookEvent>()
const apps = mkList<OauthApp>()

const productPlatform = ref('')
const messagePlatform = ref('')
const messageStatus = ref('')
const inventoryPlatform = ref('')
const webhookStatus = ref('')
const aggregate = ref<InventoryAggregate | null>(null)
const issued = ref<OauthSecretIssue | null>(null)

const accountFormOpen = ref(false)
const accountForm = reactive<Record<string, any>>({})
const mapFormOpen = ref(false)
const mapForm = reactive<Record<string, any>>({})
const appFormOpen = ref(false)
const appForm = reactive<Record<string, any>>({})

const confirmInput = ref('')
const confirmBox = ref<null | {
  title: string; detail: string; inputLabel?: string; run: () => Promise<void>
}>(null)

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

const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const num = (v: unknown) => (v === null || v === undefined || v === '' ? '-' : String(v))
const pagerText = (list: ListState<unknown>): string =>
  list.truncated.value
    ? `已加载 ${list.rows.value.length} 条 · 后端标记仍有下一页，本页不是全量`
    : `已加载 ${list.rows.value.length} 条`
const accountClass = (s?: string | null) =>
  s === 'ACTIVE' ? 'healthy' : s === 'ERROR' ? 'urgent' : 'unknown'
const productClass = (s?: string | null) =>
  s === 'ACTIVE' ? 'healthy' : s === 'OUT_OF_STOCK' ? 'urgent' : 'unknown'
const messageClass = (s?: string | null) =>
  s === 'UNREAD' ? 'risk' : s === 'ARCHIVED' ? 'unknown' : 'healthy'
const webhookClass = (s?: string | null) =>
  s === 'PROCESSED' ? 'healthy' : s === 'FAILED' ? 'urgent' : 'unknown'
const appClass = (s?: string | null) =>
  s === 'ACTIVE' ? 'healthy' : s === 'REVOKED' ? 'urgent' : 'unknown'
const qtyClass = (v?: number | null) => (v === 0 ? 'neg' : '')

const accountFormReady = computed(() =>
  Boolean(String(accountForm.platform ?? '').trim() && String(accountForm.storeName ?? '').trim()))
const mapFormReady = computed(() => Boolean(String(mapForm.amazonAsin ?? '').trim()))
const appFormReady = computed(() => Boolean(String(appForm.appName ?? '').trim()))

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

const loadAccounts = (append = false, clearErrors = true) => loadList(accounts, '平台账号',
  (cursor) => api.listAccounts(shop(), { size: 20, cursor }), append, clearErrors)
const loadProducts = (append = false, clearErrors = true) => loadList(products, '商品列表',
  (cursor) => api.listProducts(shop(), { platform: productPlatform.value || undefined, size: 20, cursor }),
  append, clearErrors)
const loadMessages = (append = false, clearErrors = true) => loadList(messages, '消息列表',
  (cursor) => api.listMessages(shop(), {
    platform: messagePlatform.value || undefined,
    status: messageStatus.value || undefined,
    size: 20, cursor
  }), append, clearErrors)
const loadInventory = (append = false, clearErrors = true) => loadList(inventory, '库存列表',
  (cursor) => api.listInventory(shop(), { platform: inventoryPlatform.value || undefined, size: 20, cursor }),
  append, clearErrors)
const loadWebhooks = (append = false, clearErrors = true) => loadList(webhooks, 'Webhook 事件',
  (cursor) => api.listWebhookEvents(shop(), { status: webhookStatus.value || undefined, size: 20, cursor }),
  append, clearErrors)
const loadApps = (append = false, clearErrors = true) => loadList(apps, 'ISV 应用',
  (cursor) => api.listApps(shop(), { size: 20, cursor }), append, clearErrors)

const loadAggregate = async () => {
  const data = await run('库存汇总', () => api.aggregatedInventory(shop()))
  aggregate.value = data
}

// ==================== 账号 ====================

const resetAccountForm = () => {
  Object.assign(accountForm, {
    id: undefined, platform: 'TEMU', storeName: '', apiEndpoint: '', apiKey: '', status: 'ACTIVE'
  })
  accountFormOpen.value = true
}

const editAccount = (a: PlatformAccount) => {
  resetAccountForm()
  Object.assign(accountForm, {
    id: a.id, platform: a.platform, storeName: a.storeName || '',
    apiEndpoint: a.apiEndpoint || '', apiKey: '', status: a.status || 'ACTIVE'
  })
}

const askSubmitAccount = () => {
  const creating = !accountForm.id
  confirmBox.value = {
    title: creating ? '新增平台账号' : `保存账号 #${accountForm.id}`,
    detail: creating
      ? `会为当前店铺登记一个 ${accountForm.platform} 账号；状态由后端固定为 ACTIVE。`
      : '保存只提交有变化的列：凭证列留空表示保持原值（不回显，也无法确认原值是什么）。',
    run: async () => {
      const key = String(accountForm.apiKey || '').trim()
      const body: Record<string, any> = {
        platform: accountForm.platform,
        storeName: String(accountForm.storeName || '').trim(),
        apiEndpoint: String(accountForm.apiEndpoint || '').trim() || null
      }
      // 凭证列只有填了新值才提交：留空表示保持原值（后端读取不回显，页面无从回填）
      if (key) body.apiKey = key
      if (accountForm.id) {
        body.status = accountForm.status
      } else {
        body.shopId = Number(shop())
      }
      const id = accountForm.id as number | undefined
      const saved = await run(creating ? '新增账号' : '保存账号', () =>
        creating ? api.createAccount(body as PlatformAccount) : api.updateAccount(id as number, body))
      if (!saved) return
      accountFormOpen.value = false
      await loadAccounts()
    }
  }
}

const askDeleteAccount = (a: PlatformAccount) => {
  confirmBox.value = {
    title: `删除 ${a.platform} 账号「${a.storeName || a.id}」`,
    detail: '物理删除，没有回收站。删掉之后这家店的该平台同步会因为没有凭证而失败，'
      + '已经落在本地表里的订单/消息/库存不会因此消失。',
    run: async () => {
      const ok = await run('删除账号', () => api.deleteAccount(a.id as number))
      if (ok === null) return
      // 后端返回 false 表示一行都没删掉（账号不存在或已被别人删除），
      // 这时列表刷新后那行还在——不说明原因就会看起来像「删除失败但没报错」
      if (ok === false) pushError(`删除账号：后端没有删掉任何行（账号 #${a.id} 可能已不存在），本地状态未变`)
      if (accountForm.id === a.id) accountFormOpen.value = false
      await loadAccounts(false, false)
    }
  }
}

// 探测不弹确认框：它对平台只发一次已鉴权的订单读，本地只改账号状态列，点错也能再点。
const testAccount = async (a: PlatformAccount) => {
  const ok = await run(`${a.platform} 连接探测`, () => api.testAccountConnection(a.id as number))
  if (ok === null) return
  // 结论写在状态列上，所以要重拉列表；clearErrors=false 否则这次刷新会把紧随其后
  // push 的那条探测说明擦干净，页面看起来像「点了没反应」。
  await loadAccounts(false, false)
  if (ok === false) {
    pushError(`连接探测：${a.platform} 账号「${a.storeName || a.id}」没有回话，状态已标为 ERROR`
      + '（原因在后端日志：凭证未配置 / 签名被拒 / 网络不通）')
  }
}

// ==================== 商品映射 ====================

const openMapForm = (p: PlatformProduct) => {
  Object.assign(mapForm, {
    productId: p.id, platform: p.platform, platformProductId: p.platformProductId,
    amazonAsin: p.amazonAsin || '', amazonSku: p.amazonSku || ''
  })
  mapFormOpen.value = true
}

const askSubmitMap = () => {
  confirmBox.value = {
    title: '提交商品映射',
    detail: `会把 ${mapForm.platform} 商品 ${mapForm.platformProductId} 的映射覆盖为 `
      + `ASIN ${String(mapForm.amazonAsin).trim().toUpperCase()}`
      + (String(mapForm.amazonSku || '').trim() ? ` / SKU ${String(mapForm.amazonSku).trim()}` : '（SKU 清空）')
      + '。映射只改本地对照表，不会把商品推送到任何平台。',
    run: async () => {
      const ok = await run('商品映射', () => api.mapProduct(
        mapForm.productId as number,
        String(mapForm.amazonAsin).trim(),
        String(mapForm.amazonSku || '').trim()))
      if (ok === null) return
      mapFormOpen.value = false
      await loadProducts()
    }
  }
}

// ==================== 消息 ====================

const askAssign = (m: PlatformMessage) => {
  confirmInput.value = m.assignedTo || ''
  confirmBox.value = {
    title: `分配消息 #${m.id}`,
    inputLabel: '处理人',
    detail: '只更新这条消息在本地的处理人，不会通知买家或平台。',
    run: async () => {
      const name = confirmInput.value.trim()
      if (!name) {
        pushError('分配处理人：处理人不能为空')
        return
      }
      const ok = await run('分配处理人', () => api.assignMessage(m.id as number, name))
      if (ok === null) return
      // 后端总是返回 true（失败会抛业务错误），所以刷新后新处理人就在行里
      await loadMessages()
    }
  }
}

// ==================== 应用 ====================

const resetAppForm = () => {
  Object.assign(appForm, {
    appName: '', scopes: 'order.read,inventory.read', redirectUris: '', rateLimitRpm: 60
  })
  appFormOpen.value = true
}

const askRegisterApp = () => {
  confirmBox.value = {
    title: '注册 ISV 应用',
    detail: '会为当前店铺生成一对 appKey/appSecret。明文密钥只在这次响应里出现一次，'
      + '后端只保存其 SHA-256，之后无法再查看，只能轮换。',
    run: async () => {
      const body = {
        appName: String(appForm.appName).trim(),
        scopes: String(appForm.scopes || '').trim() || null,
        redirectUris: String(appForm.redirectUris || '').trim() || null,
        rateLimitRpm: Number(appForm.rateLimitRpm) || null,
        ownerShopId: Number(shop())
      } as OauthApp
      const data = await run('注册应用', () => api.registerApp(body))
      if (!data) return
      issued.value = data
      appFormOpen.value = false
      await loadApps()
    }
  }
}

const askRotate = (a: OauthApp) => {
  confirmBox.value = {
    title: `轮换「${a.appName}」的密钥`,
    detail: '旧密钥立刻失效：还在用旧 appSecret 换 token 的对接方会开始鉴权失败。'
      + '新明文密钥只在本次响应里出现一次。',
    run: async () => {
      const data = await run('轮换密钥', () => api.rotateAppSecret(a.id as number))
      if (!data) return
      issued.value = data
      await loadApps()
    }
  }
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
  if (!currentShopId.value || loaded.has(key)) return
  loaded.add(key)
  if (key === 'accounts') await loadAccounts()
  if (key === 'products') await loadProducts()
  if (key === 'messages') await loadMessages()
  if (key === 'inventory') await loadInventory()
  if (key === 'webhook') await loadWebhooks()
  if (key === 'apps') await loadApps()
}

onMounted(() => {
  void gotoTab('accounts')
})
</script>

<style scoped>
.ops-page { background: var(--color-background); }
.shop-tip { background: var(--color-warning-light); color: var(--color-warning-dark); padding: 0.625rem 0.875rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .field input, .field select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.mono { font-family: var(--font-mono, monospace); }
.small { font-size: 0.75rem; }
.cell-clip { max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.reason { font-size: 0.75rem; color: var(--color-muted); max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.neg { color: var(--color-error); }
.table-card { margin-bottom: 1rem; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0 0 0.5rem; }
.form-card, .secret-card { padding: 0.875rem 1rem 1rem; }
.secret-card { border-left: 3px solid var(--color-warning); }
.secret-value { font-size: 0.875rem; color: var(--color-on-surface); word-break: break-all; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.form-hint { margin: 0.625rem 0 0; line-height: 1.5; }
.form-actions { display: flex; align-items: center; gap: 0.5rem; margin-top: 0.75rem; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr)); gap: 0.625rem; padding: 0.75rem 1rem 0; }
.metric { background: var(--color-surface-variant); border-radius: var(--radius-md); padding: 0.5rem 0.625rem; display: flex; flex-direction: column; gap: 0.25rem; }
.metric-label { font-size: 0.75rem; color: var(--color-muted); }
.metric-value { font-size: 1.125rem; color: var(--color-on-surface); }
.aggregate-note { padding: 0.5rem 1rem 0; margin: 0; line-height: 1.5; }
.row-actions { white-space: nowrap; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.plat { background: var(--color-muted-light); color: var(--color-muted); }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.field-line { font-size: 0.8125rem; color: var(--color-muted); display: flex; align-items: center; gap: 0.5rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.empty-row { color: var(--color-muted); font-size: 0.8125rem; padding: 1rem; }
</style>
