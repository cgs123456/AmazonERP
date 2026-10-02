/**
 * Amazon ERP — 全页面渲染 E2E（Playwright，hermetic）
 *
 * /api/* 由 e2e/support/api-stub.ts 打桩（./support/test 的 apiStub fixture 自动安装），
 * 所以这里断言的是「前端拿到这批固定响应时应渲染出的具体结果」：行数、单号、金额、
 * 状态中文映射、趋势点数、分页文案。它们都能失败——页面一旦渲染不出来就会红。
 *
 * 与 core-pages.spec.ts 的分工：
 * - core-pages：订单/库存/物流/利润的深度业务断言；
 * - 本文件：逐页冒烟，重点补齐此前只做恒真断言的五个页面
 *   （/ads、/finance、/selection、/warehouse、/notifications）。
 *
 * 边界（写下来以免后人误读）：
 * - 打桩不证明后端契约；后端改字段名而前端未同步时，这里查不出来。
 * - /notifications 没有 REST 端点：数据只来自 WebSocket（服务端不落库、前端不摆示例），
 *   因此那里断言的是硬编码数据，不是打桩响应。
 */
import { test, expect } from './support/test'

// 业务页都受「未选店铺不发请求」守卫保护：不播种店铺，页面会停在 shop-tip，
// 断言会红（这是对的——不播种就测不到渲染路径）。
const SEED = {
  token: 'e2e-test-token',
  shops: '[{"id":"1","name":"Shop A (US)"},{"id":"2","name":"Shop B (UK)"},{"id":"3","name":"Shop C (DE)"}]',
  shop: '1',
  user: '1'
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript((s) => {
    localStorage.setItem('token', s.token)
    localStorage.setItem('token_expiry', String(Date.now() + 86400_000))
    localStorage.setItem('shops', s.shops)
    localStorage.setItem('current_shop_id', s.shop)
    localStorage.setItem('user_id', s.user)
  }, SEED)
})

// ═══ 1. 首页 / Dashboard ═══
test.describe('Dashboard', () => {
  test('渲染出打桩 KPI 与 7 天销售趋势', async ({ page }) => {
    await page.goto('/')
    // 骨架屏不消失说明数据根本没渲染出来，此时断言 KPI 数值才是有意义的
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
    await expect(page.locator('.kpi-card')).toHaveCount(4)
    const grid = page.locator('.kpi-grid').first()
    await expect(grid).toContainText('$12345.67')
    await expect(grid).toContainText('12.5%')
    await expect(page.locator('.bar-chart .bar-item')).toHaveCount(7)
  })
})

// ═══ 2. 订单管理 ═══
test.describe('Orders', () => {
  test('渲染 3 条打桩订单，Amazon 状态已映射为中文', async ({ page }) => {
    await page.goto('/orders')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('114-1234567-8901234')
    await expect(page.locator('.data-table')).toContainText('$59.98')
    await expect(page.locator('.status-tag', { hasText: '已发货' })).toHaveCount(1)
    await expect(page.locator('.status-tag', { hasText: '待处理' })).toHaveCount(1)
    await expect(page.locator('.page-info')).toContainText('3 条')
  })

  test('未登录应重定向到首页', async ({ browser }) => {
    // 自建干净上下文：无任何 token（beforeEach 的 initScript 只作用于 fixture page）
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    await page.goto('/orders')
    await page.waitForTimeout(2000)
    const url = page.url()
    expect(url).toMatch(/\/($|#)/) // 应该在 / 或 /#
    await ctx.close()
  })
})

// ═══ 3. 库存监控 ═══
test.describe('Inventory', () => {
  test('健康度计数与明细表来自同一批打桩数据', async ({ page }) => {
    await page.goto('/inventory')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
    await expect(page.locator('.health-card.urgent .health-count')).toHaveText('1')
    await expect(page.locator('.health-card.healthy .health-count')).toHaveText('1')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('SKU-A1')
    await expect(page.locator('.data-table')).toContainText('SKU-C3')
  })
})

// ═══ 4. 广告管理 ═══
test.describe('Ads', () => {
  test('ACoS 概览 / 7 天趋势点 / 2 条活动均来自打桩响应', async ({ page }) => {
    await page.goto('/ads')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0)
    await expect(page.locator('.acos-card')).toHaveCount(4)
    // 打桩刻意用整数金额：花费 500 / 销售额 2500 -> ACoS 20.0%、ROAS 5.00x，可手算核对
    await expect(page.locator('.acos-card', { hasText: '整体 ACoS' })).toContainText('20%')
    await expect(page.locator('.acos-card', { hasText: '广告花费' })).toContainText('$500.00')
    await expect(page.locator('.acos-card', { hasText: '广告销售额' })).toContainText('$2,500.00')
    await expect(page.locator('.acos-card', { hasText: 'ROAS' })).toContainText('5.00x')
    // 趋势点数 = /ad/trend 的 7 天数据；少一个点说明趋势没渲染出来
    await expect(page.locator('.trend-dot')).toHaveCount(7)
    const spTable = page.locator('.table-card', { hasText: '活动名称' })
    await expect(spTable.locator('tbody tr')).toHaveCount(2)
    await expect(spTable).toContainText('关键词-蓝牙耳机-US')
    await expect(spTable).toContainText('运行中')
    await expect(spTable).toContainText('自动广告-全店铺')
    await expect(spTable).toContainText('已暂停')
  })
})

// ═══ 5. 利润报表 ═══
test.describe('Profit', () => {
  test('汇总卡片与明细行按打桩数据渲染', async ({ page }) => {
    await page.goto('/profit')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
    const grid = page.locator('.summary-grid')
    await expect(grid).toContainText('$10,000.00')
    await expect(grid).toContainText('40.0%')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(2)
  })
})

// ═══ 6. 财务管理 ═══
test.describe('Finance', () => {
  test('凭证列表渲染 3 条打桩凭证，「加载更多」追加第二页', async ({ page }) => {
    await page.goto('/finance')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0)
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('V-2026-0001')
    // 来源类型与金蝶状态的中文映射（Finance.vue 的 sourceTypeText / syncStatusText）
    await expect(page.locator('.data-table')).toContainText('订单收入')
    await expect(page.locator('.data-table')).toContainText('待同步')
    await expect(page.locator('.page-info')).toContainText('已加载 3 条')
    // 服务端声明截断时必须有提示，否则用户会把一页当全量
    await expect(page.locator('.truncated-tip')).toBeVisible()

    await page.locator('.page-btn', { hasText: '加载更多' }).click()
    await expect(rows).toHaveCount(4)
    await expect(page.locator('.data-table')).toContainText('V-2026-0004')
    await expect(page.locator('.truncated-tip')).toHaveCount(0)
  })

  test('利润查询返回打桩利润并渲染为 CNY', async ({ page }) => {
    await page.goto('/finance')
    await page.locator('.dim-tab', { hasText: '利润查询' }).click()
    await page.locator('.filter-btn', { hasText: '查询利润' }).click()
    await expect(page.locator('.summary-value').first()).toContainText('12345.67')
  })
})

// ═══ 7. 选品分析 ═══
test.describe('Product Selection', () => {
  test('分析市场后渲染 2 条机会，AI 建议来自打桩', async ({ page }) => {
    await page.goto('/selection')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0)
    // 机会列表要点击「分析市场」后才拉，进入时应是空态而不是空白页
    await expect(page.locator('.empty-state')).toContainText('暂无机会数据，请先进行市场分析')

    await page.locator('.primary-btn', { hasText: '分析市场' }).click()
    await expect(page.locator('.result-section .section-title')).toContainText('wireless earbuds')
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(rows.first()).toContainText('B0SELECT1')
    await expect(rows.first()).toContainText('82')
    await expect(rows.first()).toContainText('低')
    await expect(rows.first()).toContainText('↑ 上升')
    await expect(rows.nth(1)).toContainText('B0SELECT2')
    await expect(rows.nth(1)).toContainText('46')
    await expect(rows.nth(1)).toContainText('→ 平稳')
    // 平均评分 (82+46)/2 = 64.0
    await expect(page.locator('.gauge-num')).toHaveText('64.0')
    await expect(page.locator('.gauge-desc')).toHaveText('中等机会')

    await rows.first().locator('.action-btn', { hasText: 'AI 建议' }).click()
    await expect(page.locator('.ai-summary')).toContainText('建议小批量试单验证转化率')
    await expect(page.locator('.ai-suggestion')).toContainText('首批 300 件')
  })
})

// ═══ 8. 仓库管理 ═══
test.describe('Warehouse', () => {
  test('仓库列表与入库单 Tab 分别渲染打桩数据', async ({ page }) => {
    await page.goto('/warehouse')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0)
    // 四个面板用 v-show 同时挂在 DOM 上，必须只统计当前可见面板的行
    const rows = page.locator('.panel:visible .data-table tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(page.locator('.panel:visible')).toContainText('US-West-FBA')
    await expect(page.locator('.panel:visible')).toContainText('DE-ThirdParty')

    await page.locator('.tab-item', { hasText: '入库单' }).click()
    await expect(rows).toHaveCount(1)
    await expect(page.locator('.panel:visible')).toContainText('IN-2026-0001')
    await expect(page.locator('.panel:visible')).toContainText('IN_TRANSIT')
  })
})

// ═══ 9. 消息通知 ═══
test.describe('Notifications', () => {
  test('消息中心不再摆硬编码示例：空态 + 不持久化说明（该页无 REST 端点）', async ({ page }) => {
    await page.goto('/notifications')
    // 之前这里断言的是 5 条写死通知，等于把伪造数据固化成契约
    await expect(page.locator('.notification-item')).toHaveCount(0)
    await expect(page.locator('.empty-state')).toContainText('本次会话还没有收到推送')
    await expect(page.locator('.notice-zone')).toContainText('服务端不落库')
    // Tab 仍可切换，且切过去仍是空而不是造出来的行
    await page.locator('.tab-item', { hasText: '订单异常' }).click()
    await expect(page.locator('.tab-item.active')).toContainText('订单异常')
    await expect(page.locator('.notification-item')).toHaveCount(0)
  })
})

// ═══ 10. 404 页面 ═══
test.describe('404', () => {
  test('访问不存在路径应显示 404 页面', async ({ page }) => {
    await page.goto('/this-path-does-not-exist-12345')
    await expect(page.locator('.not-found-page')).toBeVisible()
    await expect(page.locator('.not-found-page .hero-title')).toHaveText('404')
  })
})

// ═══ 11. 路由守卫 ═══
test.describe('Route Guard', () => {
  test('受保护路由在无 token 时应重定向到首页', async ({ browser }) => {
    // 自建干净上下文确保无 token
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    await page.goto('/orders', { timeout: 10000 })
    await page.waitForTimeout(2000)
    const url = page.url()
    // 无 token 应被重定向到首页 /
    expect(url).not.toContain('/orders')
    await ctx.close()
  })

  test('过期 token 应触发清除并重定向', async ({ browser }) => {
    // 自建上下文并注入过期 token
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    await page.goto('/')
    await page.evaluate(() => {
      localStorage.setItem('token', 'expired-fake-token')
      localStorage.setItem('token_expiry', (Date.now() - 86400000).toString())
    })
    await page.goto('/orders', { timeout: 10000 })
    await page.waitForTimeout(2000)
    const url = page.url()
    expect(url).not.toContain('/orders')
    // token 应被守卫清除
    const token = await page.evaluate(() => localStorage.getItem('token'))
    expect(token).toBeNull()
    await ctx.close()
  })
})