import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { websocketManager } from './utils/websocket'

const JWT_SECRET = 'local-e2e-secret-key-0123456789abcdef'

/**
 * 生成 HS256 JWT（用于本地开发/测试） - 浏览器兼容版
 * 使用 Web Crypto API (window.crypto.subtle)
 */
async function makeToken(): Promise<string> {
    const b = (o: object) => btoa(JSON.stringify(o))
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    const h = b({ alg: 'HS256', typ: 'JWT' })
    const p = b({
        sub: '1', iss: 'amz-erp', aud: 'amz-erp-client',
        shops: ['1', '2', '3'], role: 'ADMIN',
        exp: Math.floor(Date.now() / 1000) + 86400
    })
    const data = `${h}.${p}`
    const encoder = new TextEncoder()
    const key = await crypto.subtle.importKey(
        'raw',
        encoder.encode(JWT_SECRET),
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign']
    )
    const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(data))
    const s = btoa(String.fromCharCode(...new Uint8Array(signature)))
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    return `${h}.${p}.${s}`
}

/**
 * 初始化 localStorage 默认值（避免手动打开页面时为空）
 */
async function initLocalStorage(): Promise<void> {
    if (!localStorage.getItem('token')) {
        localStorage.setItem('token', await makeToken())
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

// 初始化 localStorage 默认值
await initLocalStorage()

// 路由准备就绪后初始化 WebSocket
router.isReady().then(() => {
    const token = localStorage.getItem('token')
    if (token) {
        websocketManager.connect()
    }
})

app.mount('#app')
