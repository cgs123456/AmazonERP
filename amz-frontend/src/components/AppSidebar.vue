<template>
  <aside class="sidebar">
    <div class="sidebar-nav">
      <div
        class="nav-item"
        :class="{ active: isActive('/') }"
        @click="navigateTo('/')"
        role="button"
        aria-label="仪表盘"
        tabindex="0"
      >
        <Icon icon="mdi:view-dashboard" class="nav-icon" width="24" />
        <span class="nav-text">仪表盘</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/orders') }"
        @click="navigateTo('/orders')"
        role="button"
        aria-label="订单管理"
        tabindex="0"
      >
        <Icon icon="mdi:cart-outline" class="nav-icon" width="24" />
        <span class="nav-text">订单管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/inventory') }"
        @click="navigateTo('/inventory')"
        role="button"
        aria-label="库存监控"
        tabindex="0"
      >
        <Icon icon="mdi:package-variant-closed" class="nav-icon" width="24" />
        <span class="nav-text">库存监控</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/warehouse') }"
        @click="navigateTo('/warehouse')"
        role="button"
        aria-label="海外仓"
        tabindex="0"
      >
        <Icon icon="mdi:warehouse" class="nav-icon" width="24" />
        <span class="nav-text">海外仓</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/ads') }"
        @click="navigateTo('/ads')"
        role="button"
        aria-label="广告管理"
        tabindex="0"
      >
        <Icon icon="mdi:chart-line" class="nav-icon" width="24" />
        <span class="nav-text">广告管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/profit') }"
        @click="navigateTo('/profit')"
        role="button"
        aria-label="利润报表"
        tabindex="0"
      >
        <Icon icon="mdi:currency-usd" class="nav-icon" width="24" />
        <span class="nav-text">利润报表</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/finance') }"
        @click="navigateTo('/finance')"
        role="button"
        aria-label="财务管理"
        tabindex="0"
      >
        <Icon icon="mdi:finance" class="nav-icon" width="24" />
        <span class="nav-text">财务管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/selection') }"
        @click="navigateTo('/selection')"
        role="button"
        aria-label="选品分析"
        tabindex="0"
      >
        <Icon icon="mdi:compass-outline" class="nav-icon" width="24" />
        <span class="nav-text">选品分析</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/notifications') }"
        @click="navigateTo('/notifications')"
        role="button"
        aria-label="消息中心"
        tabindex="0"
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
/* sidebar: fixed positioning, height constraint */
.sidebar {
  position: fixed;
  left: 0;
  top: 64px; /* 与 header height 保持一致 */
  width: 220px;
  height: calc(100dvh - 64px); /* 使用 dvh 单位，避免移动端地址栏导致 layout-jumping */
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  padding: 1rem 0; /* 16px */
  z-index: 100;
  /* 确保焦点状态可见 */
  -webkit-tap-highlight-color: transparent;
}

/* nav-container */
.sidebar-nav {
  flex: 1;
  padding: 0 1rem; /* 16px */
  overflow-y: auto;
}

/* nav-item: 语义化交互，带 ARIA 和 focus-visible */
.nav-item {
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  padding: 0.75rem 1rem; /* 12px 16px */
  margin-bottom: 0.25rem; /* 4px */
  cursor: pointer;
  font-size: 0.875rem; /* 14px */
  color: var(--color-on-surface);
  border-radius: var(--radius-md); /* 12px */
  transition: all 0.2s;
  position: relative;
  /* focus-visible: WCAG AA 可见焦点 */
  outline: 2px solid transparent;
  outline-offset: 2px;
}

/* hover 状态 */
.nav-item:hover {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

/* active 状态 */
.nav-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 600;
}

/* focus-visible 状态 - 必须可见 (WCAG AA) */
.nav-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

/* 左侧激活指示器 */
.nav-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 24px;
  background: var(--color-primary);
  border-radius: 0 4px 4px 0;
}

/* 图标 */
.nav-icon {
  flex-shrink: 0;
  width: 1.5rem; /* 24px */
  height: 1.5rem;
}

/* 文本 */
.nav-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 响应式断点 */
/* 桌面: 220px 侧边栏 */
@media (min-width: 1025px) {
  .sidebar { width: 220px; }
  .nav-text { display: inline; }
  .nav-item { padding: 0.75rem 1rem; }
}

/* 平板: 80px 精简侧边栏 (文字隐藏) */
@media (min-width: 769px) and (max-width: 1024px) {
  .sidebar { width: 80px; }
  .nav-text { display: none; }
  .nav-item { justify-content: center; padding: 0.75rem; }
  .nav-item.active::before { display: none; }
}

/* 移动端: 完全折叠，需点击按钮展开 (需在父组件实现) */
@media (max-width: 768px) {
  .sidebar { 
    transform: translateX(0);
    width: 220px;
    /* 移动端必须明确：默认可见或通过按钮切换 */
  }
}
</style>
