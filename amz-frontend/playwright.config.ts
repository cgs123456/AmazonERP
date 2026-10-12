import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // 零重试：失败了就是失败了，不靠再跑一次把红灯抹平。
  // 成本不是时间（全量约 1 分钟，省下的重试时间反而更多），而是偶发红灯——
  // 若 CI 上开始出现间歇性红，正确动作是去修抖动根因，不要把这个数字加回 1：
  // 加回去只是把「产品缺陷在偶发条件下只红一次」和「环境抖动」这两类问题重新藏起来。
  // 判据（2026-10-12）：改为 0 之前 CI 连跑两轮 126 passed 且 retry 计数均为 0，
  // 即那一次重试根本没被用到——关掉它在这两轮里是零成本的。
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    // 原来是 'on-first-retry'：retries 归 0 后它永远不会触发，等于死配置。
    // 改成失败即保留——零重试下 trace 是唯一的失败现场，诊断抖动要靠它。
    trace: 'retain-on-failure',
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
