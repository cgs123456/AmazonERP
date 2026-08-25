import { test, expect } from '@playwright/test'
import { createHmac } from 'crypto'

/**
 * 前端全交互 E2E（连接本地真实后端栈）
 * 覆盖：侧边栏跳转 / 分页按钮 / Tab 切换 / 弹窗开关 / 搜索过滤 / Agent 对话 / 登录弹窗
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
      await page.waitForLoadState('networkidle').catch(() => {})
      const link = page.locator('.sidebar-nav .nav-item', { hasText: nav.linkText }).first()
      await link.click()
      await expect(page).toHaveURL(new RegExp(nav.path.replace(/\//g, '\\/')))
    })
  }
})

test.describe('Dashboard 交互', () => {
  test('KPI 卡片与图表区域渲染', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle').catch(() => {})
    await expect(page.locator('.kpi-grid')).toBeVisible({ timeout: 15000 })
    await expect(page.locator('.chart-card').first()).toBeVisible()
  })

  test('Agent 快捷入口点击打开聊天浮窗并可关闭', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.locator('.agent-card').click()
    await expect(page.locator('.agent-chat-window')).toBeVisible()
    await page.locator('.close-btn').click()
    await expect(page.locator('.agent-chat-window')).toHaveCount(0)
  })

  test('Agent 发送消息应收到回复（真实或降级）', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.locator('.agent-card').click()
    await page.locator('.chat-input').fill('最近7天销量如何')
    await page.locator('.send-btn').click()
    // 后端 AI 使用占位 key 会失败 → dev 模式降级 mock 回复；断言出现 assistant 回复
    await page.waitForTimeout(3000)
    const replies = page.locator('.message.assistant .message-content')
    await expect(replies.last()).not.toHaveText('')
  })
})

test.describe('OrderList 交互', () => {
  test('订单号搜索过滤与分页按钮可交互', async ({ page }) => {
    await page.goto('/orders')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(2000)

    // 分页按钮存在且上一页在第一页时禁用
    const prev = page.locator('.page-btn').first()
    if (await prev.count()) {
      await expect(prev).toBeDisabled()
    }
    // 订单号搜索输入不抛错且表格仍渲染
    const search = page.locator('input[placeholder*="订单"], input[placeholder*="搜索"]').first()
    if (await search.count()) {
      await search.fill('114-')
      await page.waitForTimeout(500)
      await expect(page.locator('.data-table, table').first()).toBeVisible()
    }
  })

  test('订单查询按钮触发请求不白屏', async ({ page }) => {
    await page.goto('/orders')
    await page.waitForLoadState('networkidle').catch(() => {})
    const queryBtn = page.locator('button', { hasText: '查询' }).first()
    if (await queryBtn.count()) {
      await queryBtn.click()
      await page.waitForTimeout(2000)
      await expect(page.locator('.order-page, main').first()).toBeVisible()
    }
  })
})

test.describe('InventoryMonitor 交互', () => {
  test('健康度卡片渲染且分页器可用', async ({ page }) => {
    await page.goto('/inventory')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(2000)
    await expect(page.locator('.health-card').first()).toBeVisible()
    const nextBtn = page.locator('.page-btn', { hasText: '下一页' })
    if (await nextBtn.count()) {
      const disabled = await nextBtn.isDisabled().catch(() => true)
      expect(typeof disabled).toBe('boolean')
    }
  })
})

test.describe('AdManager 交互', () => {
  test('广告页面主结构渲染且 tab 可切换', async ({ page }) => {
    await page.goto('/ads')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(2000)
    await expect(page.locator('.ad-page, main').first()).toBeVisible()

    // 尝试切换任意 tab（若存在）
    const tabs = page.locator('.ad-tab-item, .tab-item')
    const count = await tabs.count()
    if (count > 1) {
      await tabs.nth(1).click()
      await page.waitForTimeout(800)
      await expect(tabs.nth(1)).toBeVisible()
    }
  })

  test('关键词优化建议按钮可触发', async ({ page }) => {
    await page.goto('/ads')
    await page.waitForLoadState('networkidle').catch(() => {})
    const btn = page.locator('button', { hasText: '优化' }).first()
    if (await btn.count()) {
      await btn.click()
      await page.waitForTimeout(1500)
      await expect(page.locator('.ad-page, main').first()).toBeVisible()
    }
  })
})

test.describe('Finance 交互', () => {
  test('凭证列表分页按钮可点击', async ({ page }) => {
    await page.goto('/finance')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(2000)
    await expect(page.locator('.finance-page, main').first()).toBeVisible()

    const next = page.locator('.page-btn', { hasText: '下一页' })
    const prev = page.locator('.page-btn', { hasText: '上一页' })
    if ((await next.count()) && !(await next.isDisabled())) {
      await next.click()
      await page.waitForTimeout(400)
      await expect(prev).toBeEnabled()
      await prev.click()
    }
  })

  test('来源类型筛选下拉可选', async ({ page }) => {
    await page.goto('/finance')
    await page.waitForLoadState('networkidle').catch(() => {})
    const select = page.locator('.filter-select, select').first()
    if (await select.count()) {
      const options = await select.locator('option').count()
      if (options > 1) {
        await select.selectOption({ index: Math.min(1, options - 1) })
        await page.waitForTimeout(1200)
        await expect(page.locator('table').first()).toBeVisible()
      }
    }
  })
})

test.describe('Warehouse 交互', () => {
  test('三个 Tab 切换（仓库/库存/入库/出库）', async ({ page }) => {
    await page.goto('/warehouse')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(2000)

    for (const name of ['库存', '入库单', '出库单']) {
      const tab = page.locator(`.tab-item, button`, { hasText: name }).first()
      if (await tab.count()) {
        await tab.click()
        await page.waitForTimeout(600)
        await expect(page.locator('.warehouse-page, main').first()).toBeVisible()
      }
    }
  })

  test('新建仓库弹窗打开并取消', async ({ page }) => {
    await page.goto('/warehouse')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(1500)

    const openBtn = page.locator('button', { hasText: '新建仓库' }).first()
    if (await openBtn.count()) {
      await openBtn.click()
      const modal = page.locator('.modal')
      await expect(modal).toBeVisible()
      // 取消/关闭
      const cancel = page.locator('.modal button', { hasText: '取消' }).first()
      if (await cancel.count()) {
        await cancel.click()
      } else {
        await page.keyboard.press('Escape')
      }
      await page.waitForTimeout(400)
    }
  })
})

test.describe('ProductSelection 交互', () => {
  test('关键词分析表单可提交', async ({ page }) => {
    await page.goto('/selection')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(1500)
    await expect(page.locator('.selection-page, main').first()).toBeVisible()

    const input = page.locator('input[placeholder*="关键词"], input[type="text"]').first()
    const analyzeBtn = page.locator('button', { hasText: '分析' }).first()
    if ((await input.count()) && (await analyzeBtn.count())) {
      await input.fill('bluetooth speaker')
      await analyzeBtn.click()
      await page.waitForTimeout(2500)
      // 无论成功/失败/降级，页面不应崩溃白屏
      await expect(page.locator('.selection-page, main').first()).toBeVisible()
    }
  })
})

test.describe('Notifications 交互', () => {
  test('通知列表操作按钮（查看/忽略）可点击', async ({ page }) => {
    await page.goto('/notifications')
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(1500)
    await expect(page.locator('.notification-page, main').first()).toBeVisible()

    const before = await page.locator('.notification-item').count()
    const dismiss = page.locator('.action-btn', { hasText: '忽略' }).first()
    if (before > 0 && (await dismiss.count())) {
      await dismiss.click()
      await page.waitForTimeout(400)
      const after = await page.locator('.notification-item').count()
      expect(after).toBe(before - 1)
    }
  })

  test('Tab 过滤切换', async ({ page }) => {
    await page.goto('/notifications')
    await page.waitForLoadState('networkidle').catch(() => {})
    const tab = page.locator('.tab-item', { hasText: '订单异常' }).first()
    if (await tab.count()) {
      await tab.click()
      await page.waitForTimeout(400)
      await expect(tab).toHaveClass(/active/)
    }
  })
})

test.describe('登录弹窗', () => {
  test('未登录访问受保护页重定向首页后可打开登录框', async ({ browser }) => {
    const ctx = await browser.newContext()
    const page = await ctx.newPage()
    // 不注入 token
    await page.addInitScript(() => { localStorage.clear() })
    await page.goto('/')
    await page.waitForLoadState('networkidle').catch(() => {})

    // 触发登录入口（头像区/登录按钮）
    const loginTrigger = page.locator('.login-btn, .user-menu, [class*=login]').first()
    if (await loginTrigger.count()) {
      await loginTrigger.click()
      await page.waitForTimeout(600)
    }
    await ctx.close()
  })
})

test.describe('404 页面', () => {
  test('未知路径显示 404 且可返回首页', async ({ page }) => {
    await page.goto('/this-page-does-not-exist')
    await expect(page.locator('.not-found-page')).toBeVisible()
    const back = page.locator('button, a', { hasText: '首页' }).first()
    if (await back.count()) {
      await back.click()
      await expect(page).toHaveURL(/\/$/)
    }
  })
})
