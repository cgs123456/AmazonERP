import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        {
            path: '/',
            name: 'Dashboard',
            component: () => import('../views/Dashboard.vue')
        },
        {
            path: '/orders',
            name: 'Orders',
            component: () => import('../views/OrderList.vue')
        },
        {
            path: '/order-audit',
            name: 'OrderAudit',
            component: () => import('../views/OrderAudit.vue')
        },
        {
            path: '/inventory',
            name: 'Inventory',
            component: () => import('../views/InventoryMonitor.vue')
        },
        {
            path: '/ads',
            name: 'Ads',
            component: () => import('../views/AdManager.vue')
        },
        {
            path: '/ad-search-terms',
            name: 'AdSearchTerms',
            component: () => import('../views/AdSearchTerms.vue')
        },
        {
            path: '/ad-bid-schedule',
            name: 'AdBidSchedule',
            component: () => import('../views/AdBidSchedule.vue')
        },
        {
            path: '/multiplatform',
            name: 'MultiplatformOrders',
            component: () => import('../views/MultiplatformOrders.vue')
        },
        {
            path: '/profit',
            name: 'Profit',
            component: () => import('../views/ProfitReport.vue')
        },
        {
            path: '/finance',
            name: 'Finance',
            component: () => import('../views/Finance.vue')
        },
        {
            path: '/selection',
            name: 'Selection',
            component: () => import('../views/ProductSelection.vue')
        },
        {
            path: '/search',
            name: 'ProductSearch',
            component: () => import('../views/ProductSearch.vue')
        },
        {
            path: '/warehouse',
            name: 'Warehouse',
            component: () => import('../views/Warehouse.vue')
        },
        {
            path: '/warehouse-alerts',
            name: 'WarehouseAlerts',
            component: () => import('../views/WarehouseAlerts.vue')
        },
        {
            path: '/logistics',
            name: 'Logistics',
            component: () => import('../views/LogisticsDashboard.vue')
        },
        {
            path: '/connector-queue',
            name: 'ConnectorQueue',
            component: () => import('../views/ConnectorQueue.vue')
        },
        {
            path: '/connectors',
            name: 'Connectors',
            component: () => import('../views/ConnectorCenter.vue')
        },
        {
            path: '/notifications',
            name: 'Notifications',
            component: () => import('../views/NotificationPage.vue')
        },
        {
            path: '/knowledge',
            name: 'Knowledge',
            component: () => import('../views/KnowledgeBase.vue')
        },
        {
            path: '/listings',
            name: 'Listings',
            component: () => import('../views/ListingMonitor.vue')
        },
        {
            path: '/procurement',
            name: 'Procurement',
            component: () => import('../views/Procurement.vue')
        },
        {
            path: '/reports',
            name: 'Reports',
            component: () => import('../views/ReportCenter.vue')
        },
        {
            path: '/customer',
            name: 'Customer',
            component: () => import('../views/CustomerService.vue')
        },
        {
            path: '/:pathMatch(.*)*',
            name: 'NotFound',
            component: () => import('../views/NotFound.vue')
        }
    ]
})

// 全局前置守卫：除首页外均需登录
router.beforeEach((to, _from, next) => {
    // 首页为白名单，无需登录
    if (to.path === '/') {
        next()
        return
    }
    const token = localStorage.getItem('token')
    const expiry = Number(localStorage.getItem('token_expiry') || 0)
    // token 缺失、已过期，或过期时间被污染为非数字（NaN 比较恒为 false，会穿透守卫）：
    // 清理本地凭证并跳转首页登录。无过期时间记录（0）的 token 视为有效
    //（兼容后端签发但前端未记录过期时间的场景）
    if (!token || !Number.isFinite(expiry) || (expiry > 0 && Date.now() > expiry)) {
        localStorage.removeItem('token')
        localStorage.removeItem('token_expiry')
        next('/')
    } else {
        next()
    }
})

export default router
