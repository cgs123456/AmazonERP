<template>
  <div class="notification-page">
    <!-- 顶部导航栏 -->
    <AppHeader />

    <!-- 侧边栏 -->
    <AppSidebar />

    <!-- 主内容区 -->
    <main class="main-content">
      <div class="notification-container">
        <!-- 通知内容 -->
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
              :class="{ 'like-collect-item': notification.type === 'like' || notification.type === 'collect' }"
            >
              <img :src="notification.avatar" :alt="notification.username" class="user-avatar" />
              
              <div class="notification-main">
                <!-- 点赞和收藏类型的布局 -->
                <template v-if="notification.type === 'like' || notification.type === 'collect'">
                  <div class="like-collect-content">
                    <div class="user-action">
                      <span class="username">{{ notification.username }}</span>
                      <Icon v-if="notification.type === 'like'" icon="mdi:heart" width="14" class="action-icon like-icon" />
                      <Icon v-else icon="mdi:star" width="14" class="action-icon collect-icon" />
                    </div>
                    <div class="action-with-time">
                      <span class="action-text">{{ notification.content }}</span>
                      <span class="time">{{ notification.time }}</span>
                    </div>
                    <p v-if="notification.noteTitle" class="note-reference">
                      {{ notification.noteTitle }}
                    </p>
                  </div>
                </template>
                
                <!-- 其他类型的布局 -->
                <template v-else>
                  <div class="notification-header">
                    <span class="username">{{ notification.username }}</span>
                    <span class="time">{{ notification.time }}</span>
                  </div>
                  
                  <div class="notification-body">
                    <p class="notification-text">{{ notification.content }}</p>
                    <p v-if="notification.noteTitle" class="note-reference">
                      {{ notification.noteTitle }}
                    </p>
                  </div>
                  
                  <div class="notification-actions">
                    <button class="action-btn-reply">
                      <Icon icon="mdi:reply" width="16" />
                      回复
                    </button>
                    <button class="action-btn-like">
                      <Icon icon="mdi:heart-outline" width="16" />
                    </button>
                  </div>
                </template>
              </div>
              
              <!-- 点赞和收藏类型显示笔记图片和更多按钮 -->
              <div v-if="notification.type === 'like' || notification.type === 'collect'" class="notification-right">
                <img 
                  v-if="notification.noteImage" 
                  :src="notification.noteImage" 
                  :alt="notification.noteTitle" 
                  class="note-thumbnail" 
                />
                <button class="more-btn">
                  <Icon icon="mdi:dots-horizontal" width="20" />
                </button>
              </div>
              
              <!-- 其他类型显示原有的右侧内容 -->
              <div v-else class="notification-right">
                <span class="time">{{ notification.time }}</span>
                <button class="more-btn">
                  <Icon icon="mdi:dots-horizontal" width="20" />
                </button>
              </div>
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
import { getLikeList, type LikeVo } from '@/api/note'

interface Notification {
  id: string
  type: string
  avatar: string
  username: string
  time: string
  content: string
  noteTitle?: string
  noteImage?: string
}

const tabs = ref([
  { id: 'system', name: '赞和收藏' },
  { id: 'mention', name: '新增关注' },
  { id: 'all', name: '评论和@' }
])

const activeTab = ref('system')

// 初始化通知数据
const notifications = ref<Notification[]>([])

// 使用 WebSocket
const { isConnected, onMessage } = useWebSocket()

// 格式化时间
const formatTime = (timestamp?: number | string | Date): string => {
  if (!timestamp) {
    return '刚刚'
  }
  
  let date: Date
  if (typeof timestamp === 'string') {
    date = new Date(timestamp)
  } else if (typeof timestamp === 'number') {
    date = new Date(timestamp)
  } else {
    date = timestamp
  }
  
  const now = Date.now()
  const diff = now - date.getTime()
  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  
  if (seconds < 60) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`
  
  return `${date.getMonth() + 1}-${date.getDate()}`
}

// 获取点赞列表
const fetchLikeList = async () => {
  try {
    const response = await getLikeList()
    if (response.code === 200 && response.data) {
      // 将后端数据转换为前端通知格式
      const likeNotifications: Notification[] = response.data.map((item: LikeVo) => ({
        id: String(item.like.id),
        type: 'like',
        avatar: item.user?.image || 'https://i.pravatar.cc/150?img=1',
        username: item.user?.nickname || item.user?.username || '未知用户',
        time: formatTime(item.like.createTime),
        content: '赞了你的笔记',
        noteTitle: item.note?.title || item.note?.content?.substring(0, 20) || '无标题',
        noteImage: item.note?.image?.split(',')[0] || ''
      }))
      
      // 合并到通知列表
      notifications.value = [...likeNotifications, ...notifications.value]
    }
  } catch (error) {
    // 获取点赞列表失败
  }
}

// 格式化时间（原有的，保留用于WebSocket消息）
const formatTimeFromTimestamp = (timestamp?: number): string => {
  if (!timestamp) {
    return '刚刚'
  }
  
  const now = Date.now()
  const diff = now - timestamp
  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  
  if (seconds < 60) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`
  
  const date = new Date(timestamp)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

// 处理接收到的 WebSocket 消息
const handleWebSocketMessage = (data: unknown) => {
  try {
    // 后端格式：{ type: 0, noticeId: 3, obj: { like, note, user } }
    const msgData = data as Record<string, any>
    if (msgData.type !== undefined && msgData.noticeId && msgData.obj) {
      const obj = msgData.obj as Record<string, any>
      let notificationType = ''
      let content = ''
      let avatar = ''
      let username = ''
      let noteTitle = ''
      let noteImage = ''
      let notificationId = String(Date.now())
      let createTime: string | undefined
      
      // type: 0 表示点赞
      if (msgData.type === 0 && obj.like && obj.note && obj.user) {
        notificationType = 'like'
        content = '赞了你的笔记'
        avatar = obj.user.image || 'https://i.pravatar.cc/150?img=10'
        username = obj.user.nickname || obj.user.phone || '未知用户'
        noteTitle = obj.note.title || obj.note.content?.substring(0, 20) || '无标题'
        noteImage = obj.note.image?.split(',')[0] || ''
        notificationId = String(obj.like.id)
        createTime = obj.like.createTime
      }
      // type: 1 表示收藏
      else if (msgData.type === 1 && obj.collection && obj.note && obj.user) {
        notificationType = 'collect'
        content = '收藏了你的笔记'
        avatar = obj.user.image || 'https://i.pravatar.cc/150?img=10'
        username = obj.user.nickname || obj.user.phone || '未知用户'
        noteTitle = obj.note.title || obj.note.content?.substring(0, 20) || '无标题'
        noteImage = obj.note.image?.split(',')[0] || ''
        notificationId = String(obj.collection.id)
        createTime = obj.collection.createTime
      }
      // type: 2 表示关注
      else if (msgData.type === 2 && obj.attention && obj.user) {
        notificationType = 'follow'
        content = '关注了你'
        avatar = obj.user.image || 'https://i.pravatar.cc/150?img=10'
        username = obj.user.nickname || obj.user.phone || '未知用户'
        notificationId = String(obj.attention.id)
        createTime = obj.attention.createTime
      }
      
      if (notificationType) {
        const newNotification: Notification = {
          id: notificationId,
          type: notificationType,
          avatar: avatar,
          username: username,
          time: formatTime(createTime),
          content: content,
          noteTitle: noteTitle,
          noteImage: noteImage
        }
        
        // 添加到通知列表最前方
        notifications.value.unshift(newNotification)
        
        // 可选：显示浏览器通知
        if ('Notification' in window && Notification.permission === 'granted') {
          new Notification('新消息', {
            body: `${newNotification.username}: ${newNotification.content}`,
            icon: newNotification.avatar
          })
        }
      }
    }
    // 新格式：NotificationMessageDTO
    else if (msgData.type && msgData.actionUserId && msgData.noticeUserId) {
      let content = ''
      if (msgData.type === 'like') {
        content = '赞了你的笔记'
      } else if (msgData.type === 'collect') {
        content = '收藏了你的笔记'
      } else if (msgData.type === 'follow') {
        content = '关注了你'
      } else if (msgData.type === 'comment') {
        content = msgData.commentContent || '评论了你的笔记'
      }
      
      const newNotification: Notification = {
        id: String(msgData.timestamp || Date.now()),
        type: msgData.type,
        avatar: msgData.actionUserAvatar || 'https://i.pravatar.cc/150?img=10',
        username: msgData.actionUserNickname || '未知用户',
        time: formatTimeFromTimestamp(msgData.timestamp || Date.now()),
        content: content,
        noteTitle: msgData.noteTitle || msgData.noteContent || '无标题',
        noteImage: msgData.noteImage || ''
      }
      
      notifications.value.unshift(newNotification)
      
      if ('Notification' in window && Notification.permission === 'granted') {
        new Notification('新消息', {
          body: `${newNotification.username}: ${newNotification.content}`,
          icon: newNotification.avatar
        })
      }
    }
    // 兼容旧格式
    else if (msgData.type === 'notification' || msgData.type === 'comment' || msgData.type === 'like' || msgData.type === 'follow' || msgData.type === 'collect') {
      let content = msgData.content || msgData.message || ''
      if (msgData.type === 'like') {
        content = '赞了你的笔记'
      } else if (msgData.type === 'collect') {
        content = '收藏了你的笔记'
      } else if (msgData.type === 'follow') {
        content = '关注了你'
      } else if (msgData.type === 'comment') {
        content = msgData.content || msgData.message || '评论了你的笔记'
      }
      
      const newNotification: Notification = {
        id: msgData.id || String(Date.now()),
        type: msgData.type || 'comment',
        avatar: msgData.avatar || msgData.userAvatar || msgData.userImage || 'https://i.pravatar.cc/150?img=10',
        username: msgData.username || msgData.userName || msgData.nickname || '未知用户',
        time: formatTimeFromTimestamp(msgData.timestamp || msgData.time || Date.now()),
        content: content,
        noteTitle: msgData.noteTitle || msgData.title || msgData.noteContent,
        noteImage: msgData.noteImage || msgData.image || (msgData.noteImages ? msgData.noteImages.split(',')[0] : '')
      }
      
      notifications.value.unshift(newNotification)
      
      if ('Notification' in window && Notification.permission === 'granted') {
        new Notification('新消息', {
          body: `${newNotification.username}: ${newNotification.content}`,
          icon: newNotification.avatar
        })
      }
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
  // 获取点赞列表
  fetchLikeList()
  
  // 监听 WebSocket 消息
  onMessage(handleWebSocketMessage)
  
  // 请求通知权限
  requestNotificationPermission()
})

const filteredNotifications = computed(() => {
  // 可以根据 activeTab 来过滤通知
  if (activeTab.value === 'system') {
    return notifications.value.filter(n => n.type === 'like' || n.type === 'collect')
  } else if (activeTab.value === 'mention') {
    return notifications.value.filter(n => n.type === 'follow')
  } else if (activeTab.value === 'all') {
    return notifications.value.filter(n => n.type === 'comment')
  }
  return notifications.value
})
</script>

<style scoped>
.notification-page {
  min-width: 1000px;
  min-height: 100vh;
  background: #fafafa;
}

/* 主内容区 */
.main-content {
  margin-left: 220px;
  padding-top: 64px;
  min-height: 100vh;
  height: 100%;
  width: calc(100% - 220px);
}

.notification-container {
  width: 100%;
  padding: 24px;
}

/* 通知内容 */
.notification-content {
  background: white;
  border-radius: 12px;
  overflow: hidden;
}

/* WebSocket 连接状态 */
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
}

.tab-item {
  padding: 16px 24px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  position: relative;
  transition: all 0.2s;
}

.tab-item:hover {
  color: #333;
}

.tab-item.active {
  color: #ff2442;
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
  background: #ff2442;
  border-radius: 2px 2px 0 0;
}

/* 通知列表 */
.notification-list {
  padding: 16px 24px;
}

.notification-item {
  display: flex;
  gap: 16px;
  padding: 20px 0;
  border-bottom: 1px solid #f7f7f7;
}

.notification-item:last-child {
  border-bottom: none;
}

.user-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.notification-main {
  flex: 1;
  min-width: 0;
}

.notification-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.username {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.time {
  font-size: 12px;
  color: #999;
}

.notification-body {
  margin-bottom: 12px;
}

.notification-text {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  margin: 0 0 8px 0;
}

.note-reference {
  font-size: 13px;
  color: #999;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-align: left;
}

/* 点赞和收藏通知样式 */
.like-collect-item {
  align-items: flex-start;
}

.like-collect-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.user-action {
  display: flex;
  align-items: center;
  gap: 6px;
  justify-content: flex-start;
}

.action-icon {
  flex-shrink: 0;
}

.like-icon {
  color: #ff2442;
}

.collect-icon {
  color: #ffa500;
}

.action-text {
  font-size: 13px;
  color: #666;
  margin: 0;
  text-align: left;
}

.action-with-time {
  display: flex;
  align-items: center;
  gap: 8px;
}

.action-with-time .action-text {
  margin: 0;
}

.action-with-time .time {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}

.notification-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  margin-left: auto;
}

.like-collect-item .notification-right {
  flex-direction: row;
  align-items: center;
  gap: 12px;
}

.notification-right .time {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}

.like-collect-item .notification-right .note-thumbnail {
  order: -1;
}

.more-btn {
  padding: 4px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: #999;
  transition: color 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.more-btn:hover {
  color: #333;
}

.notification-actions {
  display: flex;
  gap: 16px;
}

.action-btn-reply,
.action-btn-like {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid #e5e5e5;
  border-radius: 16px;
  background: white;
  cursor: pointer;
  font-size: 13px;
  color: #666;
  transition: all 0.2s;
}

.action-btn-reply:hover {
  border-color: #ff2442;
  color: #ff2442;
}

.action-btn-like:hover {
  border-color: #ff2442;
  color: #ff2442;
}

.note-thumbnail {
  width: 80px;
  height: 80px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
}

/* 响应式 */
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

@media (max-width: 600px) {
  .main-content {
    padding-top: 56px;
  }
  
  .notification-container {
    padding: 12px;
  }
}
</style>
