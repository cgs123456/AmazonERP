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
            path: '/warehouse',
            name: 'Warehouse',
            component: () => import('../views/Warehouse.vue')
        },
        {
            path: '/notifications',
            name: 'Notifications',
            component: () => import('../views/NotificationPage.vue')
        },
        {
            path: '/:pathMatch(.*)*',
            name: 'NotFound',
            component: () => import('../views/NotFound.vue')
        }
    ]
})

// 全局前置守卫：除首页外均需登录，并校验 token 是否过期
router.beforeEach((to, _from, next) => {
    // 首页为白名单，无需登录
    if (to.path === '/') {
        next()
        return
    }
    const token = localStorage.getItem('token')
    const tokenExpiry = localStorage.getItem('token_expiry')
    // token 缺失或已过期：清理本地凭证并跳转首页登录
    if (!token || (tokenExpiry && Date.now() > parseInt(tokenExpiry))) {
        localStorage.removeItem('token')
        localStorage.removeItem('token_expiry')
        next('/')
    } else {
        next()
    }
})

export default router