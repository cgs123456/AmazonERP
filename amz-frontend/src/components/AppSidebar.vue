<template>
  <aside class="sidebar">
    <div class="sidebar-nav">
      <div
        class="nav-item"
        :class="{ active: isActive('/') }"
        @click="navigateTo('/')"
      >
        <Icon icon="mdi:view-dashboard" class="nav-icon" width="24" />
        <span class="nav-text">仪表盘</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/orders') }"
        @click="navigateTo('/orders')"
      >
        <Icon icon="mdi:cart-outline" class="nav-icon" width="24" />
        <span class="nav-text">订单管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/inventory') }"
        @click="navigateTo('/inventory')"
      >
        <Icon icon="mdi:package-variant-closed" class="nav-icon" width="24" />
        <span class="nav-text">库存监控</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/warehouse') }"
        @click="navigateTo('/warehouse')"
      >
        <Icon icon="mdi:warehouse" class="nav-icon" width="24" />
        <span class="nav-text">海外仓</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/ads') }"
        @click="navigateTo('/ads')"
      >
        <Icon icon="mdi:chart-line" class="nav-icon" width="24" />
        <span class="nav-text">广告管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/profit') }"
        @click="navigateTo('/profit')"
      >
        <Icon icon="mdi:currency-usd" class="nav-icon" width="24" />
        <span class="nav-text">利润报表</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/finance') }"
        @click="navigateTo('/finance')"
      >
        <Icon icon="mdi:finance" class="nav-icon" width="24" />
        <span class="nav-text">财务管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/selection') }"
        @click="navigateTo('/selection')"
      >
        <Icon icon="mdi:compass-outline" class="nav-icon" width="24" />
        <span class="nav-text">选品分析</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/notifications') }"
        @click="navigateTo('/notifications')"
      >
        <Icon icon="mdi:bell-outline" class="nav-icon" width="24" />
        <span class="nav-text">消息中心</span>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Icon } from '@iconify/vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

// 使用 startsWith 匹配子路由，例如 /orders/123 也高亮"订单管理"
// 首页 '/' 需精确匹配，避免所有路由都被命中
const isActive = (path: string) => {
  if (path === '/') return route.path === '/'
  return route.path === path || route.path.startsWith(path + '/')
}

const navigateTo = (path: string) => {
  router.push(path)
}
</script>

<style scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 64px;
  width: 220px;
  height: calc(100vh - 64px);
  background: white;
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  padding: 24px 0;
  z-index: 100;
}

.sidebar-nav {
  flex: 1;
  padding: 0 16px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 4px;
  cursor: pointer;
  font-size: 15px;
  color: #666;
  border-radius: 12px;
  transition: all 0.2s;
  position: relative;
}

.nav-item:hover {
  background: #f5f6fa;
  color: #1a1a2e;
}

.nav-item.active {
  background: linear-gradient(135deg, #eef2ff 0%, #e0e7ff 100%);
  color: #4f46e5;
  font-weight: 600;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 24px;
  background: #4f46e5;
  border-radius: 0 4px 4px 0;
}

.nav-icon {
  flex-shrink: 0;
}

.nav-text {
  flex: 1;
}

@media (max-width: 1024px) {
  .sidebar { width: 80px; }
  .nav-text { display: none; }
  .nav-item { justify-content: center; padding: 14px; }
  .nav-item.active::before { display: none; }
}

@media (max-width: 768px) {
  .sidebar { transform: translateX(-100%); }
}
</style>
