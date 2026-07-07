<template>
  <div class="notification-page">
    <!-- 顶部导航栏 -->
    <AppHeader />

    <!-- 侧边栏 -->
    <AppSidebar />

    <!-- 主内容区 -->
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
  console.log('查看通知:', notification.id)
}

const handleDismiss = (notification: Notification) => {
  notifications.value = notifications.value.filter(n => n.id !== notification.id)
}
</script>

<style scoped>
.notification-page {
  min-width: 1000px;
  min-height: 100vh;
  background: #fafafa;
}

.main-content {
  margin-left: 220px;
  padding-top: 64px;
  min-height: 100vh;
  width: calc(100% - 220px);
}

.notification-container {
  width: 100%;
  padding: 24px;
}

.notification-content {
  background: white;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.connection-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  background: #fff7e6;
  border-bottom: 1px solid #ffe7ba;
  color: #d46b08;
  font-size: 13px;
}

.content-tabs {
  display: flex;
  border-bottom: 1px solid #f0f0f0;
  padding: 0 24px;
  overflow-x: auto;
}

.tab-item {
  padding: 16px 20px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  position: relative;
  transition: all 0.2s;
  white-space: nowrap;
}

.tab-item:hover {
  color: #4f46e5;
}

.tab-item.active {
  color: #4f46e5;
  font-weight: 600;
}

.tab-item.active::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 40px;
  height: 3px;
  background: #4f46e5;
  border-radius: 2px 2px 0 0;
}

.notification-list {
  padding: 8px 24px;
}

.notification-item {
  display: flex;
  gap: 16px;
  padding: 18px 0;
  border-bottom: 1px solid #f7f7f7;
}

.notification-item:last-child {
  border-bottom: none;
}

.notification-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: white;
}

.icon-inventory_alert { background: #f56c6c; }
.icon-order_exception { background: #e6a23c; }
.icon-replenish_suggest { background: #409eff; }
.icon-negative_review { background: #909399; }
.icon-price_change { background: #9c27b0; }
.icon-system { background: #4f46e5; }

.notification-main {
  flex: 1;
  min-width: 0;
}

.notification-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.notification-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.notification-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}

.tag-inventory_alert { background: #fef0f0; color: #f56c6c; }
.tag-order_exception { background: #fdf6ec; color: #e6a23c; }
.tag-replenish_suggest { background: #ecf5ff; color: #409eff; }
.tag-negative_review { background: #f4f4f5; color: #909399; }
.tag-price_change { background: #f3e5f5; color: #9c27b0; }
.tag-system { background: #eef2ff; color: #4f46e5; }

.time {
  font-size: 12px;
  color: #999;
  margin-left: auto;
}

.notification-body {
  margin-bottom: 12px;
}

.notification-text {
  font-size: 14px;
  color: #555;
  line-height: 1.6;
  margin: 0 0 6px 0;
}

.notification-meta {
  font-size: 12px;
  color: #999;
  margin: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.notification-actions {
  display: flex;
  gap: 12px;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  background: white;
  cursor: pointer;
  font-size: 13px;
  color: #666;
  transition: all 0.2s;
}

.action-btn:hover {
  border-color: #4f46e5;
  color: #4f46e5;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
  color: #bbb;
}

.empty-state p {
  margin-top: 12px;
  font-size: 14px;
}

@media (max-width: 1024px) {
  .main-content {
    margin-left: 80px;
  }
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
  }

  .notification-container {
    padding: 16px;
  }
}
</style>
