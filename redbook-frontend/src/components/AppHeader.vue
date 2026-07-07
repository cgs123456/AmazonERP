<template>
  <header class="header">
    <div class="header-wrapper">
      <div class="header-content">
        <div class="logo" @click="goHome">
          <div class="logo-icon">
            <Icon icon="mdi:book-open-page-variant" width="28" color="#ff2442" />
          </div>
          <span class="logo-text">小红书</span>
        </div>
        
        <div class="search-bar">
          <Icon icon="mdi:magnify" width="20" class="search-icon" />
          <input 
            v-model="searchKeyword" 
            type="text" 
            placeholder="搜索你感兴趣的内容" 
            @keyup.enter="handleSearch"
          />
        </div>
        
        <div class="header-actions">
          <button class="action-btn create-btn" @click="showCreateModal = true">
            <Icon icon="mdi:pencil" width="18" />
            <span>创作中心</span>
          </button>
          
          <!-- 未登录状态 -->
          <button v-if="!userInfo" class="action-btn login-btn-header" @click="showLoginModal = true">
            登录/注册
          </button>
          
          <!-- 已登录状态 -->
          <div v-else class="user-menu">
            <img 
              :src="userInfo.image || 'https://i.pravatar.cc/150?img=1'" 
              :alt="userInfo.nickname || '用户'"
              class="user-avatar"
              @click="toggleUserMenu"
            />
            
            <!-- 用户下拉菜单 -->
            <Transition name="dropdown">
              <div v-if="showUserMenu" class="user-dropdown" @click.stop>
                <div class="user-info-section">
                  <img 
                    :src="userInfo.image || 'https://i.pravatar.cc/150?img=1'" 
                    :alt="userInfo.nickname || '用户'"
                    class="dropdown-avatar"
                  />
                  <div class="user-details">
                    <div class="user-nickname">{{ userInfo.nickname || userInfo.username || '用户' }}</div>
                    <div class="user-id">ID: {{ userInfo.number || userInfo.id }}</div>
                  </div>
                </div>
                <div class="menu-divider"></div>
                <button class="menu-item" @click="goToProfile">
                  <Icon icon="mdi:account" width="18" />
                  <span>个人主页</span>
                </button>
                <button class="menu-item" @click="goToSettings">
                  <Icon icon="mdi:cog" width="18" />
                  <span>设置</span>
                </button>
                <div class="menu-divider"></div>
                <button class="menu-item logout-item" @click="handleLogout">
                  <Icon icon="mdi:logout" width="18" />
                  <span>退出登录</span>
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </div>
    </div>

    <!-- 创作中心弹窗 -->
    <CreateNoteModal
      :visible="showCreateModal"
      @update:visible="showCreateModal = $event"
      @success="handleCreateSuccess"
    />

    <!-- 登录弹窗 -->
    <LoginModal
      :visible="showLoginModal"
      @update:visible="showLoginModal = $event"
      @login-success="handleLoginSuccess"
    />
  </header>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { useRouter } from 'vue-router'
import CreateNoteModal from './CreateNoteModal.vue'
import LoginModal from './LoginModal.vue'
import { getUserInfo, type UserVo } from '../api/auth'
import { websocketManager } from '../utils/websocket'

const router = useRouter()
const showCreateModal = ref(false)
const showLoginModal = ref(false)
const userInfo = ref<UserVo | null>(null)
const showUserMenu = ref(false)
const searchKeyword = ref('')

// 加载用户信息
const loadUserInfo = async () => {
  const token = localStorage.getItem('token')
  if (!token) {
    userInfo.value = null
    return
  }
  
  try {
    const response = await getUserInfo()
    if (response.code === 200 && response.data && response.data.user) {
      userInfo.value = response.data.user
    } else {
      // token 无效，清除
      localStorage.removeItem('token')
      userInfo.value = null
    }
  } catch (error) {
    // 获取用户信息失败
    localStorage.removeItem('token')
    userInfo.value = null
  }
}

// 切换用户菜单
const toggleUserMenu = () => {
  showUserMenu.value = !showUserMenu.value
}

// 点击外部关闭菜单
const handleClickOutside = (event: MouseEvent) => {
  const target = event.target as HTMLElement
  if (!target.closest('.user-menu')) {
    showUserMenu.value = false
  }
}

// 登录成功回调
const handleLoginSuccess = () => {
  // 重新加载用户信息
  loadUserInfo()
}

// 跳转到个人主页
const goToProfile = () => {
  showUserMenu.value = false
  router.push('/profile')
}

// 跳转到设置
const goToSettings = () => {
  showUserMenu.value = false
  router.push('/settings')
}

// 退出登录
const handleLogout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('token_expiry')
  userInfo.value = null
  showUserMenu.value = false
  websocketManager.close()
  router.push('/')
}

const goHome = () => {
  router.push('/')
}

// 搜索功能
const handleSearch = () => {
  const keyword = searchKeyword.value.trim()
  if (keyword) {
    router.push({ path: '/search', query: { keyword } })
  }
}

const handleCreateSuccess = () => {
  // 发布成功后刷新页面
  window.location.reload()
}

onMounted(() => {
  loadUserInfo()
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 64px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 1000;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
}

.header-wrapper {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
}

.header-content {
  width: 100%;
  max-width: 1200px;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  gap: 32px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  transition: transform 0.2s;
  flex-shrink: 0;
}

.logo:hover {
  transform: scale(1.05);
}

.logo-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, #fff0f0 0%, #ffe0e0 100%);
  border-radius: 10px;
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  color: #ff2442;
  letter-spacing: 0.5px;
}

.search-bar {
  flex: 1;
  max-width: 500px;
  display: flex;
  align-items: center;
  background: #f7f7f7;
  border-radius: 24px;
  padding: 10px 20px;
  gap: 10px;
  transition: all 0.3s;
  border: 2px solid transparent;
}

.search-bar:focus-within {
  background: white;
  border-color: #ff2442;
  box-shadow: 0 4px 12px rgba(255, 36, 66, 0.1);
}

.search-icon {
  color: #999;
  flex-shrink: 0;
}

.search-bar input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: #333;
}

.search-bar input::placeholder {
  color: #999;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border: none;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
  white-space: nowrap;
}

.create-btn {
  background: linear-gradient(135deg, #ff2442 0%, #ff4d6d 100%);
  color: white;
  box-shadow: 0 4px 12px rgba(255, 36, 66, 0.2);
}

.create-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(255, 36, 66, 0.3);
}

.login-btn-header {
  background: white;
  color: #333;
  border: 1px solid #e5e5e5;
}

.login-btn-header:hover {
  border-color: #ff2442;
  color: #ff2442;
}

/* 用户菜单 */
.user-menu {
  position: relative;
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.3s;
}

.user-avatar:hover {
  border-color: #ff2442;
  transform: scale(1.05);
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 12px);
  right: 0;
  width: 240px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  z-index: 100;
}

.user-info-section {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: linear-gradient(135deg, #fff0f0 0%, #ffe0e0 100%);
}

.dropdown-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.user-details {
  flex: 1;
  min-width: 0;
}

.user-nickname {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-id {
  font-size: 12px;
  color: #999;
}

.menu-divider {
  height: 1px;
  background: #f0f0f0;
  margin: 8px 0;
}

.menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: none;
  background: none;
  color: #333;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
  text-align: left;
}

.menu-item:hover {
  background: #f7f7f7;
}

.logout-item {
  color: #ff2442;
}

.logout-item:hover {
  background: #fff0f0;
}

/* 下拉菜单动画 */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: all 0.2s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 响应式 */
@media (max-width: 900px) {
  .header-content {
    padding: 0 20px;
    gap: 16px;
  }
  
  .logo-text {
    display: none;
  }
  
  .create-btn span {
    display: none;
  }
}

@media (max-width: 768px) {
  .header-content {
    padding: 0 16px;
  }
  
  .action-btn {
    padding: 8px 16px;
  }
}

@media (max-width: 600px) {
  .header {
    height: 56px;
  }
  
  .login-btn-header {
    display: none;
  }
}
</style>
