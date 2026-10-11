import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { websocketManager } from './utils/websocket'
import { purgeLegacyPlaceholderShops } from './utils/shop'

/**
 * 初始化 localStorage 默认值（同步，避免 top-level await 导致构建失败）
 * 注意：token 只能由登录流程写入，此处绝不提供占位 token——
 * 占位 token 会绕过路由守卫（表现为“永远登录”），掩盖未登录态 UI，
 * 并导致登出后 reload 自动“复活”，同时让 E2E 守卫测试无法构造无 token 状态
 *
 * 店铺列表同样不再提供占位值：此前这里写死三个示例店铺（识别模式见 utils/shop.ts），
 * 而 axios 拦截器会把选中的 id 当 shopId 请求头发给网关——等于用一个编造的店铺 ID
 * 去撞店铺隔离这条最重要的边界。现在店铺只来自后端：
 *   登录 → GET /user/getInfo 的 user.shops → setShops() 写入
 *   （见 api/auth.ts 的 getUserInfo 与 components/AppHeader.vue 的 loadUserInfo）
 * 未登录或后端未返回时 shops 为空，各页按「请先选择店铺」提示，不再伪造可选项。
 */
function initLocalStorage(): void {
    // 默认用户 ID
    if (!localStorage.getItem('user_id')) {
        localStorage.setItem('user_id', '1')
    }
    // shops 一律由后端填充；这里只清理历史遗留的写死值，避免旧浏览器缓存继续拿假店铺发请求。
    // 判定实现放在 utils/shop.ts（本就属于店铺工具），本文件不出现任何店铺名字面量——
    // 契约测试 src/__tests__/shopSource.test.ts 会直接读本文件源码断言不含占位店铺。
    purgeLegacyPlaceholderShops()
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