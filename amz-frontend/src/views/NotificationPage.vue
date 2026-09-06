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
              <p>暂无通知</p>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useWebSocket } from '@/composables/useWebSocket'

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

// 初始化通知数据（示例数据，生产环境通过 WebSocket / 接口获取）
const notifications = ref<Notification[]>([
  {
    id: '1',
    type: 'inventory_alert',
    title: 'FBA 库存不足预警',
    content: 'SKU「iPhone15-Black-128G」可售天数仅剩 6 天，建议尽快补货。',
    time: '5 分钟前',
    shopName: '美国站-主店铺',
    sku: 'iPhone15-Black-128G'
  },
  {
    id: '2',
    type: 'order_exception',
    title: '订单异常告警',
    content: '订单 114-1234567-8901234 已申请退货，退款金额 $129.99，请及时处理。',
    time: '23 分钟前',
    shopName: '美国站-主店铺'
  },
  {
    id: '3',
    type: 'replenish_suggest',
    title: '补货建议',
    content: '基于近 30 天销量预测，建议为 SKU「USB-C-HUB-7in1」补货 500 件。',
    time: '1 小时前',
    shopName: '欧洲站-德国店铺',
    sku: 'USB-C-HUB-7in1'
  },
  {
    id: '4',
    type: 'negative_review',
    title: '差评告警',
    content: '产品 ASIN B0XXXXXXXX 收到 1 星差评：「充电器使用一周后损坏」，请跟进处理。',
    time: '2 小时前',
    shopName: '美国站-主店铺'
  },
  {
    id: '5',
    type: 'price_change',
    title: '价格异动提醒',
    content: '竞品 ASIN B0YYYYYYYY 调整价格至 $89.99（下降 $10），建议关注 Buy Box 变化。',
    time: '4 小时前',
    shopName: '美国站-主店铺'
  }
])

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

onMounted(() => {
  onMessage(handleWebSocketMessage)
  requestNotificationPermission()
})

// 按标签过滤
const filteredNotifications = computed(() => {
  if (activeTab.value === 'all') return notifications.value
  return notifications.value.filter(n => n.type === activeTab.value)
})

// 操作
const handleView = (notification: Notification) => {
  notification.read = true
  // 实际项目中可跳转到对应详情页
}

const handleDismiss = (notification: Notification) => {
  notifications.value = notifications.value.filter(n => n.id !== notification.id)
}
</script>

<style scoped>
.notification-page { background: var(--color-background); }

/* 页头/主区公共样式已收敛至全局 style.css */

.notification-container { width: 100%; }

.notification-content { background: var(--color-surface); border-radius: var(--radius-md); overflow: hidden; box-shadow: var(--shadow-sm); }

.connection-status { display: flex; align-items: center; gap: 0.5rem; padding: 0.75rem 1rem; margin-bottom: 1rem; background: var(--color-primary-light); color: var(--color-primary); border-radius: var(--radius-md); font-size: 0.875rem; border: 1px solid var(--color-border); }

.content-tabs { display: flex; border-bottom: 1px solid var(--color-border); padding: 0 1rem; overflow-x: auto; }
.tab-item { position: relative; padding: 0.5rem 0.75rem; background: transparent; cursor: pointer; font-size: 0.875rem; color: var(--color-muted); border: none; transition: color 0.2s; white-space: nowrap; }
.tab-item:hover { color: var(--color-primary); }
.tab-item.active { color: var(--color-primary); font-weight: 600; }
.tab-item.active::after { content: ''; position: absolute; bottom: 0; left: 50%; transform: translateX(-50%); width: 40px; height: 3px; background: var(--color-primary); border-radius: var(--radius-sm) 0 0 var(--radius-sm); }

.notification-list { padding: 0.5rem 1rem; }

.notification-item { display: flex; gap: 0.75rem; padding: 0.75rem 0; border-bottom: 1px solid var(--color-border); }
.notification-item:last-child { border-bottom: none; }

/* 通知图标：语义色浅底 + 语义色文字，替代各色实底块（单 accent + 语义色纪律） */
.notification-icon { width: 32px; height: 32px; border-radius: var(--radius-sm); display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 1rem; }
.icon-inventory_alert { background: var(--color-light-red); color: var(--color-error); }
.icon-order_exception { background: var(--color-warning-light); color: var(--color-warning-dark); }
.icon-replenish_suggest { background: var(--color-primary-light); color: var(--color-primary); }
.icon-negative_review { background: var(--color-muted-light); color: var(--color-muted); }
.icon-price_change { background: var(--color-primary-light); color: var(--color-primary); }
.icon-system { background: var(--color-muted-light); color: var(--color-muted); }

.notification-main { flex: 1; min-width: 0; }

.notification-header { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
.notification-title { font-size: 1rem; font-weight: 600; color: var(--color-on-surface); }
.notification-tag { font-size: 0.75rem; padding: 0.125rem 0.5rem; border-radius: var(--radius-sm); font-weight: 500; white-space: nowrap; }
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
.action-btn { padding: 0.25rem 0.5rem; background: var(--color-primary-light); color: var(--color-primary); border: 1px solid var(--color-border); border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; transition: all 0.2s; }
.action-btn:hover:not(:disabled) { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } }
</style>
