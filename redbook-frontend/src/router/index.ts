import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'
import NotificationPage from '../views/NotificationPage.vue'
import ShoppingPage from '../views/ShoppingPage.vue'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        {
            path: '/',
            name: 'Home',
            component: HomePage
        },
        {
            path: '/notifications',
            name: 'Notifications',
            component: NotificationPage
        },
        {
            path: '/shopping',
            name: 'Shopping',
            component: ShoppingPage
        },
        {
            path: '/:pathMatch(.*)*',
            name: 'NotFound',
            component: () => import('../views/NotFound.vue')
        }
    ]
})

// 全局前置守卫
router.beforeEach((to, _from, next) => {
    const token = localStorage.getItem('token')
    // 需要登录的页面
    const authPages = ['/notifications', '/shopping']
    if (authPages.includes(to.path) && !token) {
        next('/')
    } else {
        next()
    }
})

export default router
