import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { websocketManager } from './utils/websocket'

/**
 * 生成 HS256 JWT（用于本地开发/测试） - 浏览器同构兼容
 * 使用 btoa (内置base64) + 简易 base64url，兼容 es2020 目标环境
 */
function makeToken(): string {
    const b = (o: object) => btoa(JSON.stringify(o))
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const h = b({ alg: 'HS256', typ: 'JWT' })
    const p = b({
        sub: '1', iss: 'amz-erp', aud: 'amz-erp-client',
        shops: ['1', '2', '3'], role: 'ADMIN',
        exp: Math.floor(Date.now() / 1000) + 86400
    })
    return `${h}.${p}.${btoa(String.fromCharCode(0))}`
}

/**
 * 初始化 localStorage 默认值（同步，避免 top-level await 导致构建失败）
 */
function initLocalStorage(): void {
    if (!localStorage.getItem('token')) {
        localStorage.setItem('token', makeToken())
    }
    if (!localStorage.getItem('token_expiry')) {
        localStorage.setItem('token_expiry', String(Date.now() + 86400_000))
    }
    if (!localStorage.getItem('current_shop_id')) {
        localStorage.setItem('current_shop_id', '1')
    }
    if (!localStorage.getItem('user_id')) {
        localStorage.setItem('user_id', '1')
    }
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