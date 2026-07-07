import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { websocketManager } from './utils/websocket'

const app = createApp(App)

app.use(router)

// 路由准备就绪后初始化 WebSocket
router.isReady().then(() => {
    // 检查是否有 token，有则自动连接
    const token = localStorage.getItem('token')
    if (token) {
        websocketManager.connect()
    }
})

app.mount('#app')
