/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import { configDefaults } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { rewriteProxyPath } from './src/utils/viteProxy'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd())

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src')
      }
    },
    server: {
      port: 5173,
      host: true,
      proxy: {
        // 本地多服务直连模式（无需网关/Nacos）：按路径前缀转发到各微服务端口。
        // 生产/完整联调仍走 VITE_API_BASE_URL 指向网关的单 /api 代理（见下方注释）。
        ...Object.fromEntries(
          [
            ['/api/user', 'http://localhost:8080'],
            ['/api/report', 'http://localhost:8102'],
            ['/api/order', 'http://localhost:8105'],
            ['/api/spapi', 'http://localhost:8096'],
            ['/api/connectors', 'http://localhost:8096'],
            ['/api/preflight', 'http://localhost:8096'],
            ['/api/credentials', 'http://localhost:8096'],
            ['/api/ad', 'http://localhost:8097'],
            ['/api/finance', 'http://localhost:8103'],
            ['/api/ops', 'http://localhost:8101'],
            ['/api/logistics', 'http://localhost:8100'],
            ['/api/procurement', 'http://localhost:8098'],
            ['/api/customer', 'http://localhost:8099'],
            ['/api/ai', 'http://localhost:8091'],
            ['/api/search', 'http://localhost:8090'],
            ['/api/multiplatform', 'http://localhost:8104'],
            ['/api/product', 'http://localhost:8095']
          ].map(([prefix, target]) => [prefix, { target, changeOrigin: true, rewrite: (p: string) => rewriteProxyPath(prefix, p) }])
        )
        // 单网关模式（Nacos+Gateway 可用时启用）:
        // '/api': { target: env.VITE_API_BASE_URL, changeOrigin: true, rewrite: (path) => path.replace(/^\/api/, '') }
      }
    },
    esbuild: {
      // 生产构建（mode=production）时移除 console 与 debugger，dev 模式保留 console
      drop: mode === 'production' ? ['console', 'debugger'] : []
    },
    optimizeDeps: {
      // 路由组件全是动态 import，vite 的依赖预扫描从 index.html 出发爬不到它们，
      // 于是每个页面第一次被请求时才现场发现依赖 -> 触发重新优化 -> 同一时刻在飞的
      // 模块请求被打断。E2E 实测（2026-10-02，冷启动连跑多轮）表现为：
      //   TypeError: Failed to fetch dynamically imported module: /src/views/Xxx.vue
      // 页面只剩空壳，用例等 30s 后超时；受影响的不只新页面，也包括既有用例。
      // 把页面与组件列进 entries，让优化在启动时一次做完，跑测试期间不再重启。
      entries: ['index.html', 'src/main.ts', 'src/views/*.vue', 'src/components/*.vue']
    },
    test: {
      environment: 'jsdom',
      globals: true,
      // e2e 目录为 Playwright 用例，由 playwright.config.ts 单独执行，避免被 vitest 误收集
      exclude: [...configDefaults.exclude, 'e2e/**'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'json', 'html']
      }
    }
  }
})
