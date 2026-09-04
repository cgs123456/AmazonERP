import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { websocketManager } from './utils/websocket'

/**
 * 初始化 localStorage 默认值（同步，避免 top-level await 导致构建失败）
 * 注意：token 只能由登录流程写入，此处绝不提供占位 token——
 * 占位 token 会绕过路由守卫（表现为“永远登录”），掩盖未登录态 UI，
 * 并导致登出后 reload 自动“复活”，同时让 E2E 守卫测试无法构造无 token 状态
 */
function initLocalStorage(): void {
    // 默认选中店铺 ID
    if (!localStorage.getItem('current_shop_id')) {
        localStorage.setItem('current_shop_id', '1')
    }
    // 默认用户 ID
    if (!localStorage.getItem('user_id')) {
        localStorage.setItem('user_id', '1')
    }
    // 默认可用店铺列表
    if (!localStorage.getItem('shops')) {
        localStorage.setItem('shops', JSON.stringify([
            { id: '1', name: 'Shop A (US)' },
            { id: '2', name: 'Shop B (UK)' },
            { id: '3', name: 'Shop C (DE)' }
        ]))
    }
}

const app = createApp(App)

app.use(router)

// 初始化 localStorage 默认值（同步，避免构建 top-level await 错误）
initLocalStorage()

// 路由准备就绪后初始化 WebSocket
router.isReady().then(() => {
    const token = localStorage.getItem('token')
    if (token) {
        websocketManager.connect()
    }
})

app.mount('#app')