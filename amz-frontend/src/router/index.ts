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

// 全局前置守卫：除首页外均需登录
router.beforeEach((to, _from, next) => {
    const token = localStorage.getItem('token')
    if (to.path !== '/' && !token) {
        next('/')
    } else {
        next()
    }
})

export default router
