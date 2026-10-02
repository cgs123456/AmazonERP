import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    // 这里试过换成「npm run build && vite preview」，想消掉 dev server 按需编译路由 chunk
    // 造成的冷启动抖动（94 例全量跑两次分别失败 1 个和 5 个，失败集合每次不同、单独跑都过）。
    // 换过去之后 4 个运营台用例全红，根因不是页面：src/api/auth.ts 的
    // baseURL = import.meta.env.DEV ? '/api' : VITE_API_BASE_URL，而 .env.production 填的是
    // 占位域名 https://api.yourdomain.com，生产构建的包请求根本不走同源 /api，
    // hermetic 桩（只拦 localhost:5173）一条都匹配不上 → 列表全空。
    // 所以 preview 方案要连带定「测试模式构建 + 把 VITE_API_BASE_URL 指回 /api」才成立，
    // 那是独立改动，先不把未验证完整的换服务器塞进这个切片；冷启动抖动仍是已知开放项。
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        // 使用 Playwright 自带 Chromium：channel:'chrome' 依赖系统安装的
        // 桌面版 Chrome，CI/新环境无浏览器时启动即失败
        browserName: 'chromium',
      },
    },
  ],
})
