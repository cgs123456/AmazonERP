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
        :class="{ active: isActive('/order-audit') }"
        @click="navigateTo('/order-audit')"
        role="button"
        aria-label="订单审单"
        tabindex="0"
      >
        <Icon icon="mdi:clipboard-check-outline" class="nav-icon" width="24" />
        <span class="nav-text">订单审单</span>
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
        :class="{ active: isActive('/warehouse-alerts') }"
        @click="navigateTo('/warehouse-alerts')"
        role="button"
        aria-label="海外仓预警"
        tabindex="0"
      >
        <Icon icon="mdi:bell-alert-outline" class="nav-icon" width="24" />
        <span class="nav-text">海外仓预警</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/logistics') }"
        @click="navigateTo('/logistics')"
        role="button"
        aria-label="物流看板"
        tabindex="0"
      >
        <Icon icon="mdi:truck-fast-outline" class="nav-icon" width="24" />
        <span class="nav-text">物流看板</span>
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
        :class="{ active: isActive('/ad-search-terms') }"
        @click="navigateTo('/ad-search-terms')"
        role="button"
        aria-label="搜索词与规则"
        tabindex="0"
      >
        <Icon icon="mdi:magnify-plus-outline" class="nav-icon" width="24" />
        <span class="nav-text">搜索词与规则</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/ad-bid-schedule') }"
        @click="navigateTo('/ad-bid-schedule')"
        role="button"
        aria-label="分时调价"
        tabindex="0"
      >
        <Icon icon="mdi:clock-outline" class="nav-icon" width="24" />
        <span class="nav-text">分时调价</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/multiplatform') }"
        @click="navigateTo('/multiplatform')"
        role="button"
        aria-label="多平台订单"
        tabindex="0"
      >
        <Icon icon="mdi:storefront-outline" class="nav-icon" width="24" />
        <span class="nav-text">多平台订单</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/multiplatform-ops') }"
        @click="navigateTo('/multiplatform-ops')"
        role="button"
        aria-label="多平台运营台"
        tabindex="0"
      >
        <Icon icon="mdi:book-open-page-variant-outline" class="nav-icon" width="24" />
        <span class="nav-text">多平台运营台</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/ops-alerts') }"
        @click="navigateTo('/ops-alerts')"
        role="button"
        aria-label="运营预警台"
        tabindex="0"
      >
        <Icon icon="mdi:shield-alert-outline" class="nav-icon" width="24" />
        <span class="nav-text">运营预警台</span>
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
        :class="{ active: isActive('/listings') }"
        @click="navigateTo('/listings')"
        role="button"
        aria-label="商品与Listing"
        tabindex="0"
      >
        <Icon icon="mdi:tag-text-outline" class="nav-icon" width="24" />
        <span class="nav-text">商品与Listing</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/procurement') }"
        @click="navigateTo('/procurement')"
        role="button"
        aria-label="采购供应链"
        tabindex="0"
      >
        <Icon icon="mdi:cart-arrow-right" class="nav-icon" width="24" />
        <span class="nav-text">采购供应链</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/reports') }"
        @click="navigateTo('/reports')"
        role="button"
        aria-label="经营报表"
        tabindex="0"
      >
        <Icon icon="mdi:file-chart-outline" class="nav-icon" width="24" />
        <span class="nav-text">经营报表</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/customer') }"
        @click="navigateTo('/customer')"
        role="button"
        aria-label="客服中心"
        tabindex="0"
      >
        <Icon icon="mdi:headset-outline" class="nav-icon" width="24" />
        <span class="nav-text">客服中心</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/search') }"
        @click="navigateTo('/search')"
        role="button"
        aria-label="商品搜索"
        tabindex="0"
      >
        <Icon icon="mdi:magnify" class="nav-icon" width="24" />
        <span class="nav-text">商品搜索</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/connectors') }"
        @click="navigateTo('/connectors')"
        role="button"
        aria-label="连接器状态"
        tabindex="0"
      >
        <Icon icon="mdi:connection" class="nav-icon" width="24" />
        <span class="nav-text">连接器状态</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/connector-queue') }"
        @click="navigateTo('/connector-queue')"
        role="button"
        aria-label="调用队列"
        tabindex="0"
      >
        <Icon icon="mdi:queue-right" class="nav-icon" width="24" />
        <span class="nav-text">调用队列</span>
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
      <div
        class="nav-item"
        :class="{ active: isActive('/knowledge') }"
        @click="navigateTo('/knowledge')"
        role="button"
        aria-label="知识库管理"
        tabindex="0"
      >
        <Icon icon="mdi:database-search-outline" class="nav-icon" width="24" />
        <span class="nav-text">知识库管理</span>
      </div>
      <div
        class="nav-item"
        :class="{ active: isActive('/agent-memory') }"
        @click="navigateTo('/agent-memory')"
        role="button"
        aria-label="助手记忆"
        tabindex="0"
      >
        <Icon icon="mdi:brain" class="nav-icon" width="24" />
        <span class="nav-text">助手记忆</span>
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
