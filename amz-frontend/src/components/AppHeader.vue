<template>
  <header class="header">
    <div class="header-wrapper">
      <div class="header-content">
        <div class="logo" @click="goHome">
          <div class="logo-icon">
            <Icon icon="mdi:amazon" width="28" />
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
          <!-- 店铺选择器 -->
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
    <LoginModal :visible="showLoginModal" @update:visible="showLoginModal = $event" @login-success="handleLoginSuccess" />
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
    } else if (response.code === 401) {
      // 明确的鉴权失败才清除凭证
      clearCredentials()
    } else {
      // 业务异常（非鉴权）：保留登录态，避免瞬时故障误清 token
      console.warn('[AppHeader] getUserInfo 业务异常，保留登录态', response)
    }
  } catch (e: unknown) {
    // 仅 HTTP 401 清除凭证；网络抖动/超时等瞬时错误保持登录态
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 401) {
      clearCredentials()
    } else {
      console.warn('[AppHeader] getUserInfo 请求失败（非鉴权），保留登录态', e)
    }
  }
}

const clearCredentials = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('token_expiry')
  userInfo.value = null
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
/* design tokens 引用全局 style.css（单 accent #4f46e5）；此处不再重复定义 */

/* header - navigation height ≤ 80px */
.header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 64px; /* ≤ 80px ✅ */
  background: var(--color-surface);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--color-border);
  z-index: 1000;
  box-shadow: var(--shadow-sm);
}

/* header-content: single line nav, centered layout */
.header-wrapper {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-content {
  width: 100%;
  max-width: 1400px;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 1rem;
  gap: 1rem;
}

/* logo: 使用 primary color, 移除第二种 accent color (#ff9900) */
.logo {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  transition: transform 0.2s;
  flex-shrink: 0;
}

.logo:hover { transform: scale(1.05); }

.logo-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--color-primary-light);
  border-radius: var(--radius-md);
  color: var(--color-primary);
}

.logo-text {
  font-size: 1rem; /* 16px - 使用设计令牌 */
  font-weight: 700;
  color: var(--color-primary);
  letter-spacing: 0.5px;
}

/* nav-menu: single line on desktop */
.nav-menu {
  flex: 1;
  display: flex;
  gap: 0.25rem; /* 4px - 4px 网格系统 */
  align-items: center;
}

/* nav-link: 统一样式，hover/active 状态 */
.nav-link {
  padding: 0.5rem 0.75rem; /* 8px 12px */
  border-radius: var(--radius-md); /* 12px */
  font-size: 0.875rem; /* 14px */
  font-weight: 500;
  color: var(--color-on-surface);
  text-decoration: none;
  transition: all 0.2s;
  /* focus-visible: 必须可见的焦点状态 (WCAG AA) */
  outline: 2px solid transparent;
  outline-offset: 2px;
}

.nav-link:hover {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.nav-link:focus-visible {
  /* WCAG AA: 对比度 ≥ 3:1 for focus ring */
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.nav-link.router-link-exact-active {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

/* header-actions: 右侧排列 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
  /* 移动端 collapse: 每个 section 必须明确 */
}

/* shop-selector */
.shop-selector {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  background: var(--color-surface);
  color: var(--color-on-surface);
  cursor: pointer;
  max-width: 120px;
  transition: border-color 0.2s;
}

.shop-selector:hover { border-color: var(--color-primary); }
.shop-selector:focus { outline: none; border-color: var(--color-primary); }

/* action-buttons */
.action-btn {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.login-btn-header {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.login-btn-header:hover {
  background: var(--color-primary-dark);
}

.user-menu { position: relative; }

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-full);
  object-fit: cover;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.3s;
}

.user-avatar:hover {
  border-color: var(--color-primary);
  transform: scale(1.05);
}

.user-dropdown {
  position: absolute;
  top: calc(100% + 0.75rem);
  right: 0;
  width: 200px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  overflow: hidden;
  z-index: 100;
}

.user-info-section {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  background: var(--color-surface);
}

.dropdown-avatar {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-full);
  object-fit: cover;
}

.user-details { flex: 1; min-width: 0; }
.user-nickname { font-size: 0.875rem; font-weight: 600; color: var(--color-on-surface); margin-bottom: 0.25rem; }
.user-id { font-size: 0.75rem; color: var(--color-muted); }

.menu-divider { height: 1px; background: var(--color-border); margin: 0.5rem 0; }

.menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border: none;
  background: none;
  color: var(--color-on-surface);
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.2s;
  text-align: left;
}

.menu-item:hover { background: var(--color-primary-light); }
.menu-item:focus-visible { background: var(--color-primary-light); outline: 2px solid var(--color-primary); outline-offset: 1px; }
.logout-item { color: var(--color-error); }
.logout-item:hover { background: var(--color-primary-light); }

/* 移动端响应式: <768px 时导航折叠 */
@media (max-width: 768px) {
  .nav-menu { display: none; } /* 移动端必须明确 collapse 行为 ✅ */
  .header-actions { gap: 0.25rem; }
  
  /* 移动端下，user-menu 保持可见 */
  .user-menu { order: 3; } /* 移动端将操作移至最后 */
}
</style>
