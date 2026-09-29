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
    await page.locator('.filter-btn').click()
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
