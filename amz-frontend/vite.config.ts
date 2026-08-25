/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import { configDefaults } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'

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
          ].map(([prefix, target]) => [prefix, { target, changeOrigin: true, rewrite: (p: string) => p.replace(/^\/api/, '') }])
        )
        // 单网关模式（Nacos+Gateway 可用时启用）:
        // '/api': { target: env.VITE_API_BASE_URL, changeOrigin: true, rewrite: (path) => path.replace(/^\/api/, '') }
      }
    },
    esbuild: {
      // 生产构建（mode=production）时移除 console 与 debugger，dev 模式保留 console
      drop: mode === 'production' ? ['console', 'debugger'] : []
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
