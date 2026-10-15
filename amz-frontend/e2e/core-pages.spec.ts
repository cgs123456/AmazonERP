/**
 * 核心业务页面 E2E（hermetic：/api/* 由 e2e/support/api-stub.ts 打桩）
 *
 * 覆盖范围就是交接文档 P1-2 点名的四个核心页面：订单、库存、物流、报表。
 * 这里的断言刻意做成「能失败的」：断言的是打桩数据在页面上的真实渲染结果
 * （行数、订单号、金额、健康度计数、补货建议量），而不是「页面标题非空」
 * 这类恒真断言——后者在旧套件里大量存在，绿了也不代表任何东西。
 *
 * 不能证明什么：后端真的会返回这些结构。字段契约由后端集成测试负责。
 */
import { test, expect } from './support/test'

const SEED = {
  token: 'e2e-test-token',
  shops: '[{"id":"1","name":"E2E Alpha"},{"id":"2","name":"E2E Beta"},{"id":"3","name":"E2E Gamma"}]',
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

test.describe('订单管理 /orders', () => {
  test('列表渲染出打桩订单，且状态映射正确', async ({ page }) => {
    await page.goto('/orders')
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('114-1234567-8901234')
    await expect(page.locator('.data-table')).toContainText('$59.98')
    // Amazon 原始状态 → 中文展示状态的映射
    await expect(page.locator('.status-tag', { hasText: '已发货' })).toHaveCount(1)
    await expect(page.locator('.status-tag', { hasText: '待处理' })).toHaveCount(1)
    await expect(page.locator('.status-tag', { hasText: '已取消' })).toHaveCount(1)
    await expect(page.locator('.page-info')).toContainText('3 条')
  })

  test('订单号搜索过滤生效', async ({ page }) => {
    await page.goto('/orders')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
    await page.locator('.filter-input').fill('114-')
    // 订单页有两个 .filter-btn：查询按钮与 P1 CSV 导出加的「导出」按钮（带 .export 修饰类）。
    // 收窄到查询按钮，不用 .first()——那会在按钮顺序变化时静默点错另一个。
    await page.locator('.filter-btn:not(.export)').click()
    await expect(page.locator('.data-table tbody tr')).toHaveCount(1)
    await expect(page.locator('.data-table')).toContainText('114-1234567-8901234')
  })
})

test.describe('库存监控 /inventory', () => {
  test('健康度计数与库存明细来自同一批打桩数据', async ({ page }) => {
    await page.goto('/inventory')
    await expect(page.locator('.health-card.urgent .health-count')).toHaveText('1')
    await expect(page.locator('.health-card.healthy .health-count')).toHaveText('1')
    await expect(page.locator('.health-card.overstock .health-count')).toHaveText('1')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('SKU-A1')
    await expect(page.locator('.data-table')).toContainText('SKU-C3')
  })

  test('补货建议按 SKU join 回填（紧急行带建议量，其余为占位）', async ({ page }) => {
    await page.goto('/inventory')
    const urgentRow = page.locator('.data-table tbody tr', { hasText: 'SKU-B2' })
    await expect(urgentRow).toContainText('紧急')
    await expect(urgentRow).toContainText('50 件')
    // 没有补货建议的 SKU 保持占位，不能被错误回填成 0 之外的数字
    const healthyRow = page.locator('.data-table tbody tr', { hasText: 'SKU-A1' })
    await expect(healthyRow).toContainText('-')
  })
})

test.describe('物流看板 /logistics', () => {
  test('概览 KPI 与状态分布按打桩数据渲染', async ({ page }) => {
    await page.goto('/logistics')
    const main = page.locator('main').first()
    await expect(main).toContainText('共 12 个货件')
    await expect(main).toContainText('在途货件')
    await expect(main).toContainText('7')
    await expect(main).toContainText('异常货件')
    await expect(main).toContainText('18.5 天')
    // 状态分布：运输中 5 / 已创建 2 / 异常 1 / 已送达 4
    await expect(main).toContainText('运输中')
    await expect(main).toContainText('已送达')
  })

  test('比价页逐条有效报价明细：承运商/航线/失效日来自后端', async ({ page }) => {
    const quotePaths: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname.includes('/logistics/v2/quote/list/')) quotePaths.push(u.pathname)
    })
    await page.goto('/logistics')
    await page.locator('.tab', { hasText: '比价' }).click()
    const panel = page.locator('[data-panel="quotes"]')
    await expect(panel).toBeVisible({ timeout: 20000 })

    await expect.poll(() => quotePaths.length).toBe(1)
    await expect(panel).toContainText('Matson')
    await expect(panel).toContainText('CNSZX')
    await expect(panel).toContainText('2026-12-31')
    // 桩返回完整分页元数据，此时不应出现截断/无法确认的提示
    await expect(panel).not.toContainText('不是全量')
  })

  test('头程分摊：未核算行给入口，提交走分摊接口并展示平衡校验', async ({ page }) => {
    const posts: Array<{ url: string; body: string | null }> = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname.includes('/logistics/v2/freight/102/calculate')) {
        posts.push({ url: u.pathname + u.search, body: r.postData() })
      }
    })
    await page.goto('/logistics')
    await page.locator('.tab', { hasText: '头程成本' }).click()
    const row = page.locator('tbody tr', { hasText: 'SHP-2026-0102' }).first()
    await expect(row).toContainText('有费用未摊')
    await row.locator('button', { hasText: '分摊' }).click()
    const form = page.locator('[data-panel="freight-calc"]')
    await expect(form).toBeVisible()
    // 运费预填的是主单已登记的 8000，不是凭空编的默认值
    await expect(form.locator('input').first()).toHaveValue('8000')
    await form.locator('button', { hasText: '提交分摊' }).click()
    await expect.poll(() => posts.length).toBe(1)
    expect(posts[0].url).toContain('method=WEIGHT')
    expect(posts[0].url).toContain('totalFreight=8000')
    const result = page.locator('[data-panel="freight-calc-result"]')
    await expect(result).toContainText('8000')
    await expect(result).toContainText('分摊平衡')
  })
})

test.describe('利润报表 /profit', () => {
  test('汇总卡片与明细行按打桩数据渲染', async ({ page }) => {
    await page.goto('/profit')
    const grid = page.locator('.summary-grid')
    await expect(grid).toContainText('$10,000.00')
    await expect(grid).toContainText('$6,000.00')
    await expect(grid).toContainText('$4,000.00')
    await expect(grid).toContainText('40.0%')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(2)
    await expect(page.locator('.data-table')).toContainText('2026-09-01')
    await expect(page.locator('.data-table')).toContainText('$750.00')
  })
})
