<template>
  <div class="notification-page">
    <!-- hero section - 符合 design-taste-frontend 约束 -->
    <!-- eyebrow: 无 (每 3 个 section 最多 1 个，本页面 0 个，合规)
         headline: "消息中心" - 2 行以内
         subtext: "查看并处理库存预警、订单异常、补货建议等" - 11 词以内
         CTAs: 无 (Tab 切换在页面尾部，不计入 hero CTA 数)
         split-header: 已垂直堆叠 (h1 在上，p 在下)
    -->
    <div class="hero-section">
      <h1 class="hero-title">消息中心</h1>
      <p class="hero-subtitle">查看并处理库存预警、订单异常、补货建议等</p>
    </div>

    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="notification-container">
        <div class="notification-content">
          <!-- WebSocket 连接状态 -->
          <div class="connection-status" v-if="!isConnected">
            <Icon icon="mdi:wifi-off" width="16" />
            <span>消息服务未连接，正在重连...</span>
          </div>

          <div class="notice-zone" role="note">
            通知已服务端落库（P1.6）：进入页面即加载最近 100 条历史，WebSocket 实时推送
            与历史合并显示；「离线期间的推送」不再丢失。标为已读会同步到服务端。
          </div>

          <div v-if="errors.length" class="error-zone" role="alert">
            <Icon icon="mdi:alert-circle-outline" width="16" />
            <span>{{ errors.join('；') }}</span>
          </div>

          <!-- 标签切换 -->
          <div class="content-tabs">
            <button
              v-for="tab in tabs"
              :key="tab.id"
              class="tab-item"
              :class="{ active: activeTab === tab.id }"
              @click="activeTab = tab.id"
            >
              {{ tab.name }}
            </button>
            <!-- 全部已读：仅在存在未读行时出现（没有未读可清时不给一个点了没反应的按钮） -->
            <button
              v-if="unreadLocalCount > 0"
              class="mark-all-btn"
              :disabled="busy"
              @click="askMarkAll"
            >
              <Icon icon="mdi:email-check-outline" width="14" />
              全部已读
            </button>
          </div>

          <!-- 通知列表 -->
          <div class="notification-list">
            <div
              v-for="notification in filteredNotifications"
              :key="notification.id"
              class="notification-item"
            >
              <div class="notification-icon" :class="`icon-${notification.type}`">
                <Icon :icon="getIcon(notification.type)" width="20" />
              </div>

              <div class="notification-main">
                <div class="notification-header">
                  <span class="notification-title">{{ notification.title }}</span>
                  <span class="notification-tag" :class="`tag-${notification.type}`">
                    {{ getTypeLabel(notification.type) }}
                  </span>
                  <span class="time">{{ notification.time }}</span>
                </div>

                <div class="notification-body">
                  <p class="notification-text">{{ notification.content }}</p>
                  <p v-if="notification.shopName" class="notification-meta">
                    <Icon icon="mdi:store-outline" width="12" />
                    {{ notification.shopName }}
                    <template v-if="notification.sku"> · SKU: {{ notification.sku }}</template>
                  </p>
                </div>

                <div class="notification-actions">
                  <button class="action-btn" @click="handleView(notification)">
                    <Icon icon="mdi:eye-outline" width="14" />
                    查看
                  </button>
                  <button class="action-btn" @click="handleDismiss(notification)">
                    <Icon icon="mdi:check" width="14" />
                    忽略
                  </button>
                </div>
              </div>
            </div>

            <!-- 空状态 -->
            <div v-if="filteredNotifications.length === 0" class="empty-state">
              <Icon icon="mdi:bell-off-outline" width="48" />
              <p>本次会话还没有收到推送</p>
              <p class="empty-hint">消息服务连上后新的告警会实时出现在这里；历史消息不持久化，无法回看。</p>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 全部已读二次确认：批量写操作不可撤销，先确认再落库 -->
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
import { ref, computed, onMounted } from 'vue'
import Icon from '../components/Icon.vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useWebSocket } from '@/composables/useWebSocket'
import { listNotifications, markNotificationRead, markAllNotificationsRead } from '@/api/notification'

// 通知类型对齐后端 MessageTypeEnum：
// INVENTORY_ALERT(0) / ORDER_EXCEPTION(1) / REPLENISH_SUGGEST(2) / NEGATIVE_REVIEW(3) / PRICE_CHANGE(4)
type NoticeType =
  | 'inventory_alert'
  | 'order_exception'
  | 'replenish_suggest'
  | 'negative_review'
  | 'price_change'
  | 'system'

interface Notification {
  id: string
  type: NoticeType
  title: string
  content: string
  time: string
  shopName?: string
  sku?: string
  read?: boolean
}

const tabs = ref([
  { id: 'all', name: '全部通知' },
  { id: 'inventory_alert', name: '库存预警' },
  { id: 'order_exception', name: '订单异常' },
  { id: 'replenish_suggest', name: '补货建议' },
  { id: 'negative_review', name: '差评告警' },
  { id: 'price_change', name: '价格异动' }
])

const activeTab = ref('all')

// 通知列表：只装本次会话经 WebSocket 收到的消息。
//
// 这里原先写死 5 条示例（iPhone15 库存预警、订单 114-1234567-8901234 退货…），
// 它们和登录店铺无关，却以「库存预警」「订单异常」的样式排在列表最前面——
// 运营会把它们当成待办去处理。服务端（amz-service-message）没有消息表、
// 也没有 REST 读取接口，所以拿不到历史是事实，编几条顶上是假象。
const notifications = ref<Notification[]>([])

// 使用 WebSocket
const { isConnected, onMessage } = useWebSocket()

// 类型 → 图标映射
const getIcon = (type: NoticeType): string => {
  const map: Record<NoticeType, string> = {
    inventory_alert: 'mdi:package-variant-closed',
    order_exception: 'mdi:alert-circle-outline',
    replenish_suggest: 'mdi:cart-plus',
    negative_review: 'mdi:star-off-outline',
    price_change: 'mdi:currency-usd-off',
    system: 'mdi:bell-outline'
  }
  return map[type] || 'mdi:bell-outline'
}

// 类型 → 标签映射
const getTypeLabel = (type: NoticeType): string => {
  const map: Record<NoticeType, string> = {
    inventory_alert: '库存预警',
    order_exception: '订单异常',
    replenish_suggest: '补货建议',
    negative_review: '差评告警',
    price_change: '价格异动',
    system: '系统通知'
  }
  return map[type] || '通知'
}

// WebSocket 消息 → 通知类型映射（对齐后端 MessageTypeEnum 序号）
const mapMessageType = (type: number | string | undefined): NoticeType => {
  if (type === undefined || type === null) return 'system'
  const num = typeof type === 'number' ? type : parseInt(String(type), 10)
  const map: Record<number, NoticeType> = {
    0: 'inventory_alert',
    1: 'order_exception',
    2: 'replenish_suggest',
    3: 'negative_review',
    4: 'price_change'
  }
  return map[num] || (typeof type === 'string' ? (type as NoticeType) : 'system')
}

// 处理接收到的 WebSocket 消息
const handleWebSocketMessage = (data: unknown) => {
  try {
    const msgData = data as Record<string, any>
    if (!msgData) return

    const type = mapMessageType(msgData.type)
    const newNotification: Notification = {
      id: String(msgData.id || msgData.noticeId || Date.now()),
      type,
      title: msgData.title || getTypeLabel(type),
      content: msgData.content || msgData.message || '',
      time: msgData.time || '刚刚',
      shopName: msgData.shopName,
      sku: msgData.sku,
      read: false
    }

    notifications.value.unshift(newNotification)
    // 上限裁剪：久置页面 WS 持续堆积会拖慢渲染，保留最新 200 条
    // （如需全量历史/虚拟滚动，另做分页加载，此处先止血）
    if (notifications.value.length > 200) notifications.value.splice(200)

    // 浏览器通知
    if ('Notification' in window && Notification.permission === 'granted') {
      new Notification(newNotification.title, {
        body: newNotification.content
      })
    }
  } catch (error) {
    // 处理 WebSocket 消息失败
  }
}

// 请求浏览器通知权限
const requestNotificationPermission = () => {
  if ('Notification' in window && Notification.permission === 'default') {
    Notification.requestPermission()
  }
}

// 加载服务端历史（最近 100 条）。会话内的实时行（无 h- 前缀）保留，历史行整体以服务端为准刷新。
// 返回是否成功，供调用方决定后续动作（失败不阻断实时推送）。
const loadHistory = async (): Promise<boolean> => {
  try {
    const history = await listNotifications()
    if (history?.code !== 200 || !Array.isArray(history.data)) return false
    // 只保留会话内实时行，历史行丢弃后用服务端最新状态重建（避免「已读后又重拉」时残留旧未读）
    const live = notifications.value.filter(n => !n.id.startsWith('h-'))
    const seen = new Set(live.map(n => n.type + '|' + n.content + '|' + n.time))
    const merged = [...live]
    for (const row of history.data) {
      const key = String(row.type ?? 0) + '|' + (row.content || '') + '|' + (row.createTime || '')
      if (seen.has(key)) continue
      merged.push({
        id: 'h-' + row.id,
        type: mapMessageType(row.type),
        title: row.title || '运营通知',
        content: row.content || '',
        time: (row.createTime || '').slice(0, 19).replace('T', ' '),
        read: !!row.isRead
      })
    }
    notifications.value = merged
    return true
  } catch {
    // 历史加载失败不阻断实时推送（页面仍有会话内通知）
    return false
  }
}

onMounted(async () => {
  onMessage(handleWebSocketMessage)
  requestNotificationPermission()
  await loadHistory()
})

// 按标签过滤
const filteredNotifications = computed(() => {
  if (activeTab.value === 'all') return notifications.value
  return notifications.value.filter(n => n.type === activeTab.value)
})

// 操作
const handleView = (notification: Notification) => {
  notification.read = true
  // 服务端同步已读（历史行 id 带 h- 前缀；会话内实时行无服务端 id，跳过）
  if (notification.id.startsWith('h-')) {
    markNotificationRead(Number(notification.id.slice(2))).catch(() => {})
  }
  // 已读会让顶栏红点数变化，通知顶栏重拉
  window.dispatchEvent(new Event('amz:notifications-changed'))
}

const handleDismiss = (notification: Notification) => {
  notifications.value = notifications.value.filter(n => n.id !== notification.id)
}

// ==================== 全部已读（接孤儿 api markAllNotificationsRead） ====================
// 该函数此前定义但全仓零调用：后端 POST /user/notification/read-all 早已就绪。
// 未读以本地行状态为准（服务端返回 isRead，会话内实时行为未读），点一次清空全部未读。
const busy = ref(false)
const errors = ref<string[]>([])

const unreadLocalCount = computed(() => notifications.value.filter(n => !n.read).length)

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const askMarkAll = () => {
  confirmBox.value = {
    title: '全部标记为已读',
    detail: '会把当前所有未读通知一次性标为已读（服务端同步）。该操作不可撤销。',
    run: async () => {
      const res = await markAllNotificationsRead()
      if (res?.code !== 200) {
        errors.value = ['全部已读失败：' + (res?.message || '服务端未返回成功')]
        return
      }
      errors.value = []
      // 本地行全部置已读（不必等重拉，红点与行状态立即一致）
      notifications.value.forEach(n => { n.read = true })
      // 重拉服务端列表，让 isRead 与后端对齐
      await loadHistory()
      // 通知顶栏重拉未读数
      window.dispatchEvent(new Event('amz:notifications-changed'))
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  confirmBox.value = null
  if (!box) return
  busy.value = true
  try {
    await box.run()
  } catch (e) {
    errors.value = ['全部已读失败：' + ((e as Error)?.message || '请求异常')]
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.notification-page { background: var(--color-background); }

/* 页头/主区公共样式已收敛至全局 style.css */

.notification-container { width: 100%; }

.notification-content { background: var(--color-surface); border-radius: var(--amz-radius-md); overflow: hidden; box-shadow: var(--shadow-sm); }

.notice-zone { padding: 0.5rem 0.75rem; margin-bottom: 0.75rem; border-radius: var(--amz-radius-md); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; line-height: 1.6; }
.empty-hint { font-size: 0.8125rem; color: var(--color-muted); margin-top: 0.25rem; }
.connection-status { display: flex; align-items: center; gap: 0.5rem; padding: 0.75rem 1rem; margin-bottom: 1rem; background: var(--color-primary-light); color: var(--color-primary); border-radius: var(--amz-radius-md); font-size: 0.875rem; border: 1px solid var(--color-border); }

.content-tabs { display: flex; border-bottom: 1px solid var(--color-border); padding: 0 1rem; overflow-x: auto; }
.tab-item { position: relative; padding: 0.5rem 0.75rem; background: transparent; cursor: pointer; font-size: 0.875rem; color: var(--color-muted); border: none; transition: color 0.2s; white-space: nowrap; }
.tab-item:hover { color: var(--color-primary); }
.tab-item.active { color: var(--color-primary); font-weight: 600; }
.tab-item.active::after { content: ''; position: absolute; bottom: 0; left: 50%; transform: translateX(-50%); width: 40px; height: 3px; background: var(--color-primary); border-radius: var(--amz-radius-sm) 0 0 var(--amz-radius-sm); }

/* 全部已读：右对齐的次级动作，不参与 Tab 高亮/下划线 */
.mark-all-btn { margin-left: auto; display: inline-flex; align-items: center; gap: 0.25rem; align-self: center; padding: 0.25rem 0.625rem; background: var(--color-surface); color: var(--color-primary); border: 1px solid var(--color-border); border-radius: var(--amz-radius-sm); cursor: pointer; font-size: 0.75rem; white-space: nowrap; }
.mark-all-btn:hover:not(:disabled) { background: var(--color-primary-light); border-color: var(--color-primary); }
.mark-all-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); border-radius: var(--amz-radius-md); padding: 0.625rem 0.875rem; margin-bottom: 0.75rem; font-size: 0.875rem; }

.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--amz-radius-md); padding: 1.5rem; width: 90%; max-width: 560px; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.notification-list { padding: 0.5rem 1rem; }

.notification-item { display: flex; gap: 0.75rem; padding: 0.75rem 0; border-bottom: 1px solid var(--color-border); }
.notification-item:last-child { border-bottom: none; }

/* 通知图标：语义色浅底 + 语义色文字，替代各色实底块（单 accent + 语义色纪律） */
.notification-icon { width: 32px; height: 32px; border-radius: var(--amz-radius-sm); display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 1rem; }
.icon-inventory_alert { background: var(--color-light-red); color: var(--color-error); }
.icon-order_exception { background: var(--color-warning-light); color: var(--color-warning-dark); }
.icon-replenish_suggest { background: var(--color-primary-light); color: var(--color-primary); }
.icon-negative_review { background: var(--color-muted-light); color: var(--color-muted); }
.icon-price_change { background: var(--color-primary-light); color: var(--color-primary); }
.icon-system { background: var(--color-muted-light); color: var(--color-muted); }

.notification-main { flex: 1; min-width: 0; }

.notification-header { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
.notification-title { font-size: 1rem; font-weight: 600; color: var(--color-on-surface); }
.notification-tag { font-size: 0.75rem; padding: 0.125rem 0.5rem; border-radius: var(--amz-radius-sm); font-weight: 500; white-space: nowrap; }
.tag-inventory_alert { background: var(--color-light-red); color: var(--color-error); }
.tag-order_exception { background: var(--color-warning-light); color: var(--color-warning-dark); }
.tag-replenish_suggest { background: var(--color-primary-light); color: var(--color-primary); }
.tag-negative_review { background: var(--color-muted-light); color: var(--color-muted); }
.tag-price_change { background: var(--color-primary-light); color: var(--color-primary-dark); }
.tag-system { background: var(--color-primary-light); color: var(--color-primary); }

.time { font-size: 0.75rem; color: var(--color-muted); margin-left: auto; }

.notification-body { margin-bottom: 0.5rem; }
.notification-text { font-size: 1rem; color: var(--color-on-surface); line-height: 1.6; margin: 0 0 0.25rem 0; }
.notification-meta { font-size: 0.75rem; color: var(--color-muted); margin: 0; display: flex; align-items: center; gap: 0.25rem; }

.notification-actions { display: flex; gap: 0.5rem; }
.action-btn { padding: 0.25rem 0.5rem; background: var(--color-primary-light); color: var(--color-primary); border: 1px solid var(--color-border); border-radius: var(--amz-radius-sm); cursor: pointer; font-size: 0.75rem; transition: all 0.2s; }
.action-btn:hover:not(:disabled) { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } }
</style>
