<template>
  <header class="header">
    <div class="header-wrapper">
      <div class="header-content">
        <div class="logo" @click="goHome">
          <div class="logo-icon">
            <Icon icon="mdi:amazon" width="28" color="#ff9900" />
          </div>
          <span class="logo-text">Amazon ERP</span>
        </div>

        <nav class="nav-menu">
          <router-link to="/" class="nav-link">仪表盘</router-link>
          <router-link to="/orders" class="nav-link">订单</router-link>
          <router-link to="/inventory" class="nav-link">库存</router-link>
          <router-link to="/ads" class="nav-link">广告</router-link>
          <router-link to="/profit" class="nav-link">利润</router-link>
          <router-link to="/notifications" class="nav-link">消息</router-link>
        </nav>

        <div class="header-actions">
          <!-- 店铺选择器：登录后从 localStorage 的 shops 数组读取，
               切换时更新 current_shop_id 并刷新当前页面数据 -->
          <select
            v-if="userInfo"
            class="shop-selector"
            :value="currentShopId"
            @change="handleShopChange"
            title="切换当前店铺"
          >
            <option value="" disabled>请选择店铺</option>
            <option v-for="shop in shops" :key="shop.id" :value="String(shop.id)">
              {{ shop.name }}
            </option>
          </select>
          <button v-if="!userInfo" class="action-btn login-btn-header" @click="showLoginModal = true">
            登录 / 注册
          </button>
          <div v-else class="user-menu">
            <img
              :src="userInfo.image || defaultAvatar"
              :alt="userInfo.nickname || '用户'"
              class="user-avatar"
              @click="toggleUserMenu"
            />
            <Transition name="dropdown">
              <div v-if="showUserMenu" class="user-dropdown" @click.stop>
                <div class="user-info-section">
                  <img
                    :src="userInfo.image || defaultAvatar"
                    class="dropdown-avatar"
                  />
                  <div class="user-details">
                    <div class="user-nickname">{{ userInfo.nickname || userInfo.username || '用户' }}</div>
                    <div class="user-id">ID: {{ userInfo.id }}</div>
                  </div>
                </div>
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
import LoginModal from './LoginModal.vue'
import { getUserInfo, type UserVo } from '../api/auth'
import { websocketManager } from '../utils/websocket'
import { getShops, getCurrentShopId, setCurrentShopId, type ShopOption } from '../utils/shop'

const router = useRouter()
const showLoginModal = ref(false)
const userInfo = ref<UserVo | null>(null)
const showUserMenu = ref(false)

// 当前选中店铺与授权店铺列表（来源：localStorage 'shops' 数组）
const shops = ref<ShopOption[]>([])
const currentShopId = ref('')

const refreshShops = () => {
  shops.value = getShops()
  currentShopId.value = getCurrentShopId()
}

// 切换店铺：更新 localStorage 并刷新当前页面数据
const handleShopChange = (e: Event) => {
  const value = (e.target as HTMLSelectElement).value
  setCurrentShopId(value)
  currentShopId.value = value
  // 切换店铺影响所有页面的数据视图，整页刷新以重新拉取
  window.location.reload()
}

// 本地 SVG 占位头像，避免依赖国外服务
const defaultAvatar = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="150" height="150" viewBox="0 0 150 150">' +
  '<rect width="150" height="150" fill="#e0e0e0"/>' +
  '<circle cx="75" cy="55" r="28" fill="#bdbdbd"/>' +
  '<path d="M30 130 Q75 95 120 130 L120 150 L30 150 Z" fill="#bdbdbd"/>' +
  '</svg>'
)

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
      refreshShops()
    } else {
      localStorage.removeItem('token')
      userInfo.value = null
    }
  } catch {
    localStorage.removeItem('token')
    userInfo.value = null
  }
}

const toggleUserMenu = () => {
  showUserMenu.value = !showUserMenu.value
}

const handleClickOutside = (event: MouseEvent) => {
  const target = event.target as HTMLElement
  if (!target.closest('.user-menu')) {
    showUserMenu.value = false
  }
}

const handleLoginSuccess = () => {
  loadUserInfo()
}

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
  max-width: 1400px;
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

.logo:hover { transform: scale(1.05); }

.logo-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, #fff4e0 0%, #ffe0b0 100%);
  border-radius: 10px;
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  color: #1a1a2e;
  letter-spacing: 0.5px;
}

.nav-menu {
  flex: 1;
  display: flex;
  gap: 4px;
}

.nav-link {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  color: #666;
  text-decoration: none;
  transition: all 0.2s;
}

.nav-link:hover {
  background: #f0f0f5;
  color: #1a1a2e;
}

.nav-link.router-link-exact-active {
  background: #4f46e5;
  color: #fff;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.shop-selector {
  padding: 8px 12px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  background: #fff;
  color: #1a1a2e;
  cursor: pointer;
  max-width: 200px;
  transition: border-color 0.2s;
}

.shop-selector:hover { border-color: #4f46e5; }
.shop-selector:focus { outline: none; border-color: #4f46e5; }

.action-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
}

.login-btn-header {
  background: #4f46e5;
  color: white;
}

.login-btn-header:hover {
  background: #4338ca;
}

.user-menu { position: relative; }

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
  border-color: #4f46e5;
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
  background: #f9fafb;
}

.dropdown-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.user-details { flex: 1; min-width: 0; }
.user-nickname { font-size: 15px; font-weight: 600; color: #333; margin-bottom: 4px; }
.user-id { font-size: 12px; color: #999; }

.menu-divider { height: 1px; background: #f0f0f0; margin: 8px 0; }

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

.menu-item:hover { background: #f7f7f7; }
.logout-item { color: #ef4444; }
.logout-item:hover { background: #fee2e2; }

.dropdown-enter-active, .dropdown-leave-active { transition: all 0.2s ease; }
.dropdown-enter-from, .dropdown-leave-to { opacity: 0; transform: translateY(-8px); }

@media (max-width: 768px) {
  .nav-menu { display: none; }
}
</style>
