import { test, expect } from './support/test'
import type { Page } from '@playwright/test'
import { createHmac } from 'crypto'

/**
 * 前端全交互 E2E（hermetic：/api/* 全部由 e2e/support/api-stub.ts 打桩，不依赖真实后端）
 *
 * 覆盖：侧边栏跳转 / 搜索过滤 / Tab 切换 / 弹窗开关 / 游标分页 / 广告素材查询 / 通知忽略。
 *
 * 断言纪律（写下来以免后人退化）：
 * - 不允许 `if (await x.count())` 存在性守卫：元素不存在就应该红，否则页面改版后用例恒绿、
 *   什么也测不到（本文件在 2026-09-30 之前就有一批这样的用例，已被替换为可失败断言）；
 * - 不允许 `expect(typeof x).toBe('boolean')` 这类恒真断言；
 * - 打桩只证明「前端拿到这批响应时的渲染与交互」，不证明后端契约。
 */

const JWT_SECRET = 'local-e2e-secret-key-0123456789abcdef'
function makeToken(): string {
  const b = (o: any) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const h = b({ alg: 'HS256', typ: 'JWT' })
  const p = b({
    sub: '1', iss: 'amz-erp', aud: 'amz-erp-client',
    shops: ['1', '2', '3'], role: 'ADMIN',
    exp: Math.floor(Date.now() / 1000) + 86400
  })
  const s = createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url')
  return `${h}.${p}.${s}`
}

const NAV = [
  { path: '/orders', linkText: '订单管理' },
  { path: '/inventory', linkText: '库存监控' },
  { path: '/warehouse', linkText: '海外仓' },
  { path: '/ads', linkText: '广告管理' },
  { path: '/profit', linkText: '利润报表' },
  { path: '/finance', linkText: '财务管理' },
  { path: '/selection', linkText: '选品分析' },
  { path: '/notifications', linkText: '消息中心' }
]

/**
 * 数据没回来时页面只渲染 .skeleton-zone，表格不渲染。先等骨架消失再断言行数，
 * 否则慢机器/冷启动下会假红（骨架里也有 .kpi-grid / .chart-card 一类的同名容器）。
 */
async function waitReady(page: Page) {
  await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript((token) => {
    localStorage.setItem('token', token)
    localStorage.setItem('token_expiry', String(Date.now() + 86400_000))
    localStorage.setItem('shops', JSON.stringify([
      { id: '1', name: 'Shop A (US)' },
      { id: '2', name: 'Shop B (UK)' },
      { id: '3', name: 'Shop C (DE)' }
    ]))
    localStorage.setItem('current_shop_id', '1')
    localStorage.setItem('user_id', '1')
  }, makeToken())
})

test.describe('侧边栏导航跳转', () => {
  for (const nav of NAV) {
    test(`侧边栏点击「${nav.linkText}」应跳转 ${nav.path}`, async ({ page }) => {
      await page.goto('/')
      await waitReady(page)
      const link = page.locator('.sidebar-nav .nav-item', { hasText: nav.linkText }).first()
      await link.click()
      await expect(page).toHaveURL(new RegExp(nav.path.replace(/\//g, '\\/')))
    })
  }
})

test.describe('Dashboard 交互', () => {
  test('KPI 卡片与图表区域渲染', async ({ page }) => {
    await page.goto('/')
    await waitReady(page)
    await expect(page.locator('.kpi-card')).toHaveCount(4)
    await expect(page.locator('.kpi-grid')).toContainText('$12345.67')
    await expect(page.locator('.kpi-grid')).toContainText('23')
    await expect(page.locator('.kpi-grid')).toContainText('12.5%')
    await expect(page.locator('.kpi-grid')).toContainText('$536.77')
    await expect(page.locator('.bar-chart .bar-item')).toHaveCount(7)
  })

  test('Agent 快捷入口点击打开聊天浮窗并可关闭', async ({ page }) => {
    await page.goto('/')
    await waitReady(page)
    await page.locator('.agent-card').click()
    await expect(page.locator('.agent-chat-window')).toBeVisible()
    await page.locator('.close-btn').click()
    await expect(page.locator('.agent-chat-window')).toHaveCount(0)
  })

  test('Agent 发送消息应收到打桩 SSE 回复', async ({ page }) => {
    await page.goto('/')
    await waitReady(page)
    await page.locator('.agent-card').click()
    await page.locator('.chat-input').fill('最近7天销量如何')
    await page.locator('.send-btn').click()
    const replies = page.locator('.message.assistant .message-content')
    await expect(replies.last()).toContainText('23 笔订单', { timeout: 15000 })
    await expect(replies.last()).toContainText('$12,345.67')
  })
})

test.describe('OrderList 交互', () => {
  test('订单号搜索走服务端过滤，第一页禁用「上一页」', async ({ page }) => {
    await page.goto('/orders')
    await waitReady(page)
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(3)
    // 第一页：上一页必须禁用
    await expect(page.locator('.page-btn').first()).toBeDisabled()
    // 打桩复现后端 LIKE 语义：'114-' 只命中 114-1234567-8901234
    await page.locator('.filter-input').fill('114-')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('114-1234567-8901234')
  })

  test('清空搜索条件后回到全量 3 条', async ({ page }) => {
    await page.goto('/orders')
    await waitReady(page)
    await page.locator('.filter-input').fill('113-')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(page.locator('.data-table tbody tr')).toHaveCount(1)
    await page.locator('.filter-input').fill('')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
  })
})

test.describe('InventoryMonitor 交互', () => {
  test('健康度卡片与列表同源；3 条 ≤ pageSize 20 时不渲染分页器', async ({ page }) => {
    await page.goto('/inventory')
    await waitReady(page)
    await expect(page.locator('.health-card.urgent .health-count')).toHaveText('1')
    await expect(page.locator('.health-card.healthy .health-count')).toHaveText('1')
    await expect(page.locator('.data-table tbody tr')).toHaveCount(3)
    await expect(page.locator('.data-table')).toContainText('SKU-A1')
    // 分页器是 v-if（total > pageSize）整块不渲染，不是「按钮禁用」；
    // 这里锁定的是「数据量没超页大小时不应出现分页控件」这一行为。
    await expect(page.locator('.table-pager')).toHaveCount(0)
  })
})

test.describe('AdManager 交互', () => {
  test('SB tab 按活动ID查素材；切回 SP 仍是 2 条活动', async ({ page }) => {
    await page.goto('/ads')
    await waitReady(page)
    // 默认 SP 面板：2 条活动（SB/SD/DSP 面板用 v-show 挂着但不可见）
    await expect(page.locator('.data-table:visible tbody tr')).toHaveCount(2)

    await page.locator('.ad-tab-item', { hasText: 'SB 品牌推广' }).click()
    await expect(page.locator('.ad-tab-item.active')).toContainText('SB')
    const sb = page.locator('.ext-section:visible')
    // 未填活动ID时 loadCreatives 直接 return，表格应停在空态行
    await expect(sb.locator('.data-table tbody tr')).toContainText('暂无素材，请输入活动ID查询')
    await sb.locator('input').fill('C-1001')
    await sb.locator('.action-btn', { hasText: '查询' }).click()
    await expect(sb.locator('.data-table tbody tr')).toHaveCount(1)
    await expect(sb.locator('.data-table')).toContainText('C-1001')
    await expect(sb.locator('.data-table')).toContainText('VIDEO')
    await expect(sb.locator('.data-table')).toContainText('Earbuds Pro 2026')
    await expect(sb.locator('.data-table')).toContainText('PENDING')

    await page.locator('.ad-tab-item', { hasText: 'SP 商品推广' }).click()
    await expect(page.locator('.data-table:visible tbody tr')).toHaveCount(2)
    await expect(page.locator('.data-table:visible')).toContainText('关键词-蓝牙耳机-US')
  })

  test('DSP tab 刷新后按广告类型渲染 2 张汇总卡片', async ({ page }) => {
    await page.goto('/ads')
    await waitReady(page)
    await page.locator('.ad-tab-item', { hasText: 'DSP 批量报表' }).click()
    await page.locator('.action-btn', { hasText: '刷新' }).click()
    const cards = page.locator('.summary-card')
    // 打桩 Record<adType, AdSummary> 给了 SP / SB 两个键
    await expect(cards).toHaveCount(2)
    await expect(cards.first()).toContainText('SP')
    await expect(cards.first()).toContainText('$500')
    await expect(cards.first()).toContainText('20%')
    await expect(cards.first()).toContainText('5x')
  })
})

test.describe('Finance 交互', () => {
  test('凭证列表「加载更多」追加第二页并在收口后撤掉截断提示', async ({ page }) => {
    await page.goto('/finance')
    await waitReady(page)
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(3)
    await expect(page.locator('.page-info')).toContainText('已加载 3 条')
    // 服务端声明截断时必须提示，否则用户会把一页当全量
    await expect(page.locator('.truncated-tip')).toBeVisible()

    await page.locator('.page-btn', { hasText: '加载更多' }).click()
    await expect(rows).toHaveCount(4)
    await expect(page.locator('.data-table')).toContainText('V-2026-0004')
    await expect(page.locator('.truncated-tip')).toHaveCount(0)
  })

  test('来源类型筛选走服务端过滤（需点查询，select 不自动重拉）', async ({ page }) => {
    await page.goto('/finance')
    await waitReady(page)
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(3)

    const select = page.locator('.filter-select')
    await select.selectOption('ORDER')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('V-2026-0001')

    await select.selectOption('REFUND')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('V-2026-0003')

    await select.selectOption('')
    await page.locator('.filter-btn', { hasText: /^查询$/ }).click()
    await expect(rows).toHaveCount(3)
  })
})

test.describe('Warehouse 交互', () => {
  test('四个 Tab 分别渲染各自打桩数据', async ({ page }) => {
    await page.goto('/warehouse')
    await waitReady(page)
    // 四个面板用 v-show 同时挂在 DOM 上，只统计当前可见面板的行
    const rows = page.locator('.panel:visible .data-table tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(page.locator('.panel:visible')).toContainText('US-West-FBA')
    await expect(page.locator('.panel:visible')).toContainText('DE-ThirdParty')

    await page.locator('.tab-item', { hasText: '库存查询' }).click()
    await expect(rows).toHaveCount(1)
    await expect(page.locator('.panel:visible')).toContainText('SKU-A1')
    await expect(page.locator('.panel:visible')).toContainText('90')

    await page.locator('.tab-item', { hasText: '入库单' }).click()
    await expect(rows).toHaveCount(1)
    await expect(page.locator('.panel:visible')).toContainText('IN-2026-0001')
    await expect(page.locator('.panel:visible')).toContainText('IN_TRANSIT')

    await page.locator('.tab-item', { hasText: '出库单' }).click()
    await expect(rows).toHaveCount(1)
    await expect(page.locator('.panel:visible')).toContainText('OUT-2026-0001')
    // PENDING 状态才出现「拣货」
    await expect(page.locator('.panel:visible .action-btn', { hasText: '拣货' })).toBeVisible()
  })

  test('新建仓库弹窗可打开并取消', async ({ page }) => {
    await page.goto('/warehouse')
    await waitReady(page)
    await page.locator('.primary-btn', { hasText: '新建仓库' }).click()
    const modal = page.locator('.modal-mask .modal')
    await expect(modal).toBeVisible()
    await expect(modal.locator('h3')).toHaveText('新建仓库')
    await modal.locator('.modal-actions button', { hasText: '取消' }).click()
    await expect(modal).toHaveCount(0)
  })
})

test.describe('ProductSelection 交互', () => {
  test('关键词分析返回打桩机会列表', async ({ page }) => {
    await page.goto('/selection')
    await waitReady(page)
    await page.locator('.keyword-input').fill('wireless earbuds')
    await page.locator('.primary-btn', { hasText: '分析市场' }).click()
    await expect(page.locator('.result-section .section-title')).toContainText('wireless earbuds')
    const rows = page.locator('.data-table tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(rows.first()).toContainText('B0SELECT1')
    await expect(rows.first()).toContainText('82')
  })
})

test.describe('Notifications 交互', () => {
  // 该页没有 REST 端点：数据是前端硬编码 5 条 + WebSocket，断言的是硬编码数据
  test('点「忽略」后条目减少一条', async ({ page }) => {
    await page.goto('/notifications')
    const items = page.locator('.notification-item')
    await expect(items).toHaveCount(5)
    await items.first().locator('.action-btn', { hasText: '忽略' }).click()
    await expect(page.locator('.notification-item')).toHaveCount(4)
  })

  test('切到「订单异常」只剩 1 条且 Tab 高亮', async ({ page }) => {
    await page.goto('/notifications')
    const tab = page.locator('.tab-item', { hasText: '订单异常' })
    await tab.click()
    await expect(tab).toHaveClass(/active/)
    await expect(page.locator('.notification-item')).toHaveCount(1)
    await expect(page.locator('.notification-item')).toContainText('114-1234567-8901234')
  })
})

test.describe('未登录态', () => {
  test('无 token 时 header 显示登录入口', async ({ browser }) => {
    // 自建干净上下文：无任何 token，也不装 /api 桩（userInfo 取不到 -> v-if="!userInfo" 分支）
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    await page.goto('/')
    await expect(page.locator('.login-btn-header')).toBeVisible({ timeout: 15000 })
    await ctx.close()
  })
})

test.describe('404 页面', () => {
  test('未知路径显示 404 且「返回首页」可回到 /', async ({ page }) => {
    await page.goto('/this-page-does-not-exist')
    await expect(page.locator('.not-found-page')).toBeVisible()
    await expect(page.locator('.not-found-page .hero-title')).toHaveText('404')
    await page.locator('.hero-cta', { hasText: '返回首页' }).click()
    await expect(page).toHaveURL(/\/$/)
  })
})
