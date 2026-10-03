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
  { path: '/order-audit', linkText: '订单审单' },
  { path: '/inventory', linkText: '库存监控' },
  { path: '/warehouse', linkText: '海外仓' },
  { path: '/warehouse-alerts', linkText: '海外仓预警' },
  { path: '/ads', linkText: '广告管理' },
  { path: '/profit', linkText: '利润报表' },
  { path: '/finance', linkText: '财务管理' },
  { path: '/selection', linkText: '选品分析' },
  { path: '/search', linkText: '商品搜索' },
  { path: '/procurement', linkText: '采购供应链' },
  { path: '/connectors', linkText: '连接器状态' },
  { path: '/connector-queue', linkText: '调用队列' },
  { path: '/ad-search-terms', linkText: '搜索词与规则' },
  { path: '/ad-bid-schedule', linkText: '分时调价' },
  { path: '/multiplatform', linkText: '多平台订单' },
  { path: '/multiplatform-ops', linkText: '多平台运营台' },
  { path: '/ops-alerts', linkText: '运营预警台' },
  { path: '/notifications', linkText: '消息中心' },
  { path: '/customer', linkText: '客服中心' },
  { path: '/agent-memory', linkText: '助手记忆' }
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
      // 超时放宽到 20s：dev server 是按需编译路由 chunk 的，某个页面在本轮里第一次
      // 被访问时要现场编译（实测冷启动 4.4s，全套跑到第 16 个时能顶过默认 10s），
      // 这属于测试环境的编译耗时，不是页面不跳转。
      await expect(page).toHaveURL(new RegExp(nav.path.replace(/\//g, '\\/')), { timeout: 20000 })
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
  test('日报回补入口把后端计数原样报出来，并且不会变成第二个 ext-section', async ({ page }) => {
    const syncs: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/ad/reports/sync') syncs.push(r.method())
    })
    await page.goto('/ads')
    await waitReady(page)
    const card = page.locator('[data-panel="sync"]')
    // 常驻可见的卡片不能污染 .ext-section:visible 这类活动区定位器
    await expect(card).not.toHaveClass(/ext-section/)

    await expect(card).toContainText('日报同步回补')
    await expect(card).not.toContainText('回补窗口')
    await card.locator('.action-btn', { hasText: '展开' }).click()
    await expect(card).toContainText('最多 30 天')
    expect(syncs).toEqual([])

    await card.locator('.action-btn', { hasText: '同步当前店铺' }).click()
    await expect.poll(() => syncs.length).toBe(1)
    expect(syncs[0]).toBe('POST')
    await expect(card.locator('.ops-result')).toContainText('店铺 1')
    await expect(card.locator('.ops-result')).toContainText('落库 12 行')
    await expect(card.locator('.ops-error')).toHaveCount(0)
  })

  test('SP 表格的花费/销售额来自日报关联，不是扩展表的零值列', async ({ page }) => {
    await page.goto('/ads')
    await waitReady(page)
    const row = page.locator('table.data-table tbody tr', { hasText: '关键词-蓝牙耳机-US' }).first()
    // AD_REPORTS 里 C-1001 是 cost 320 / sales 1600；ext 表的 spend/sales 是 0，
    // 读错来源会显示 $0.00 与 0%。
    await expect(row).toContainText('$320.00')
    await expect(row).toContainText('$1,600.00')
    await expect(row).toContainText('20%')
    await expect(row).toContainText('$50')
    await expect(page.locator('[data-note="sp-source"]')).toContainText('按 campaignId 关联')
  })

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
  // 该页没有 REST 端点，也不再有前端示例数据：能验的是空态、Tab 切换与连接状态提示。
  // 「忽略/查看」按钮要等 WebSocket 真推消息才有对象，桩环境不驱 WS，故不在此断言。
  test('连接未建立时给出重连提示', async ({ page }) => {
    await page.goto('/notifications')
    await expect(page.locator('.connection-status')).toContainText('消息服务未连接')
  })

  test('切到「订单异常」Tab 高亮且列表为空（不伪造行）', async ({ page }) => {
    await page.goto('/notifications')
    const tab = page.locator('.tab-item', { hasText: '订单异常' })
    await tab.click()
    await expect(tab).toHaveClass(/active/)
    await expect(page.locator('.notification-item')).toHaveCount(0)
  })
})

test.describe('CustomerService 交互（客服中心）', () => {
  // 这一组额外锁一条历史缺陷：五个分区曾是 <div class="table-pager"> 用 </table> 收尾，
  // Vue 解析器把「差评与索评」「RMA」嵌进了「邮件任务」的 v-if 里，那两个分区永远点不出来。
  // 单测里它表现为 vue-tsc 的 TS2367 类型收窄；这里用真实浏览器再锁一次渲染结果。
  test('工单分区渲染后端字段，切换分区时旧分区整体消失', async ({ page }) => {
    await page.goto('/customer')
    const ticketPanel = page.locator('.tab-panel[data-panel="ticket"]')
    await expect(ticketPanel).toContainText('114-7712567-000001')
    await expect(ticketPanel).toContainText('LOGISTICS')
    await expect(ticketPanel).toContainText('NEGATIVE')
    await expect(page.locator('.tab-panel')).toHaveCount(1)

    await page.locator('.tab', { hasText: '差评与索评' }).click()
    await expect(page.locator('.tab-panel[data-panel="review"]')).toContainText('B0CUST01')
    await expect(page.locator('.tab-panel[data-panel="task"]')).toHaveCount(0)
    await expect(page.locator('.tab-panel')).toHaveCount(1)

    await page.locator('.tab', { hasText: 'RMA' }).click()
    await expect(page.locator('.tab-panel[data-panel="rma"]')).toContainText('RMA-0001')
    await expect(page.locator('.tab-panel')).toHaveCount(1)
  })

  test('三条通道现状常驻在页面上，不把没接的通道显示成已办', async ({ page }) => {
    await page.goto('/customer')
    await expect(page.locator('.notice-zone')).toContainText('邮件发送通道未接入')
    await expect(page.locator('.notice-zone')).toContainText('SP-API')
    await expect(page.locator('.notice-zone')).toContainText('关键词规则匹配')
  })

  test('待发邮件队列要二次确认：取消不执行，确认后才写处理结果', async ({ page }) => {
    await page.goto('/customer')
    await page.locator('.tab', { hasText: '邮件任务' }).click()
    await page.locator('.action-btn', { hasText: '处理待发队列' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('邮件通道目前未接入')
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    await expect(page.locator('.modal-mask')).toHaveCount(0)
    await expect(page.locator('.tab-panel[data-panel="task"]')).not.toContainText('处理结果')

    await page.locator('.action-btn', { hasText: '处理待发队列' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect(page.locator('.tab-panel[data-panel="task"]')).toContainText('处理结果')
  })

  test('「按事件触发」的事件项来自启用中模板，不含停用模板的事件', async ({ page }) => {
    await page.goto('/customer')
    await page.locator('.tab', { hasText: '邮件任务' }).click()
    await page.locator('.action-btn', { hasText: '按事件触发' }).click()
    const options = page.locator('.modal select option')
    await expect(options).toHaveCount(2)
    await expect(options.nth(1)).toHaveText('NEGATIVE_REVIEW')
    await expect(page.locator('.modal')).not.toContainText('SHIPPING_DELAY')
  })

  test('差评匹配订单同样要确认，确认后关闭弹窗且不报错', async ({ page }) => {
    await page.goto('/customer')
    await page.locator('.tab', { hasText: '差评与索评' }).click()
    await expect(page.locator('.tab-panel[data-panel="review"]')).toContainText('DETECTED')
    await page.locator('.action-btn', { hasText: '匹配订单' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('SIMULATED-MATCH-*')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect(page.locator('.modal-mask')).toHaveCount(0)
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('OrderAudit 交互（订单审单）', () => {
  test('规则分区渲染后端条件，判不出来的字段与仅建议动作有标注', async ({ page }) => {
    await page.goto('/order-audit')
    const panel = page.locator('.tab-panel[data-panel="rule"]')
    await expect(panel).toContainText('PO Box地址检测')
    await expect(panel).toContainText('final_price GT 500')
    await expect(panel.locator('.neg')).toHaveCount(2)
    await expect(panel).toContainText('仅建议')
    await expect(panel.locator('tbody tr')).toHaveCount(3)
    await expect(page.locator('.tab-panel')).toHaveCount(1)
  })

  test('删除规则要二次确认：取消时一条写请求都不发', async ({ page }) => {
    const writes: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/order/audit/rule/') && r.method() !== 'GET') {
        writes.push(`${r.method()} ${new URL(r.url()).pathname}`)
      }
    })
    await page.goto('/order-audit')
    const row = page.locator('.tab-panel[data-panel="rule"] tbody tr').first()
    await row.locator('button', { hasText: '删除' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('不存在或不属于本店都会直接报错')
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    await expect(page.locator('.modal-mask')).toHaveCount(0)
    expect(writes).toEqual([])

    await row.locator('button', { hasText: '删除' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => writes.length).toBe(1)
    expect(writes[0]).toBe('DELETE /api/order/audit/rule/1')
  })

  test('单订单审单先挡住空订单号，再把命中与未判定分开显示', async ({ page }) => {
    await page.goto('/order-audit')
    await page.locator('.tab', { hasText: '单订单审单' }).click()
    const panel = page.locator('.tab-panel[data-panel="audit"]')
    await panel.locator('button', { hasText: '审这一单' }).click()
    await expect(page.locator('.error-zone')).toContainText('订单号必填')

    await panel.locator('input').first().fill('114-1111111-1111111')
    await panel.locator('button', { hasText: '审这一单' }).click()
    await expect(panel).toContainText('REVIEW')
    await expect(panel).toContainText('命中 1 条')
    await expect(panel).toContainText('未判定 1 条')
    await expect(panel).toContainText('条件字段 shipping_address 取不到值')
    await expect(panel.locator('.advisory')).toContainText('没有合并/拆单实现')
  })

  test('批量审单逐条渲染 verdict，不把一批压成一个结论', async ({ page }) => {
    await page.goto('/order-audit')
    await page.locator('.tab', { hasText: '批量审单' }).click()
    const panel = page.locator('.tab-panel[data-panel="batch"]')
    await panel.locator('textarea').fill('[{"amazonOrderId":"114-1"},{"amazonOrderId":"114-2","finalPrice":900}]')
    await panel.locator('button', { hasText: '执行批量审单' }).click()
    await expect(panel).toContainText('共 2 条')
    await expect(panel.locator('tbody tr')).toHaveCount(2)
    await expect(panel).toContainText('PASS')
    await expect(panel).toContainText('BLOCKED')
  })

  test('路由结果为空仓名时显示未解析，不拼一个假仓库', async ({ page }) => {
    await page.goto('/order-audit')
    await page.locator('.tab', { hasText: '发货路由' }).click()
    const panel = page.locator('.tab-panel[data-panel="route"]')
    await panel.locator('button', { hasText: '生成并入库' }).click()
    await expect(page.locator('.error-zone')).toContainText('订单号与 SKU 必填')

    await panel.locator('input').nth(0).fill('114-1111111-1111111')
    await panel.locator('input').nth(1).fill('SKU-1')
    await panel.locator('button', { hasText: '生成并入库' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('插入一行')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect(panel).toContainText('未解析（需物流模块确认）')
    await expect(panel).not.toContainText('FBA-Warehouse')
  })

  test('拆分日志空态说明系统里这张表没有写入方', async ({ page }) => {
    await page.goto('/order-audit')
    await page.locator('.tab', { hasText: '拆分日志' }).click()
    await expect(page.locator('.tab-panel[data-panel="split"]'))
      .toContainText('全仓没有 amz_order_split_log 的插入点')
  })
})

test.describe('ProductSearch 交互（商品搜索）', () => {
  test('进入页面即取热搜与历史，且这一页不要求先选店铺', async ({ page }) => {
    await page.goto('/search')
    await expect(page.locator('.shop-tip')).toHaveCount(0)
    await expect(page.locator('.notice-zone')).toContainText('登录用户')
    const hotRow = page.locator('tbody tr', { hasText: '搜这个词' }).first()
    await expect(hotRow).toContainText('earbuds')
    await expect(hotRow).toContainText('12')
    await expect(page.locator('tbody tr', { hasText: '再搜一次' })).toHaveCount(2)
  })

  test('关键词搜索渲染 ES 命中的真实字段，空字段显示占位而不是编值', async ({ page }) => {
    await page.goto('/search')
    // 页面里有两块 .empty-block（结果区与热搜加载态），必须按文案定位，不能用类名
    await expect(page.getByText('还没搜索。上方输入关键词后回车即可。')).toBeVisible()
    await page.locator('input').first().fill('earbuds')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    const rows = page.locator('tbody tr', { hasText: 'SKU-WE-01' })
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('Wireless Earbuds Pro')
    await expect(rows.first()).toContainText('39.9')
    await expect(rows.first()).toContainText('卖家A')
    await expect(page.locator('tbody tr', { hasText: 'Earbuds Case' }).first()).toContainText('-')
    await expect(page.getByText('还没搜索。上方输入关键词后回车即可。')).toHaveCount(0)
  })

  test('点热搜里的词会填进输入框并立刻按这个词检索', async ({ page }) => {
    await page.goto('/search')
    await page.locator('button', { hasText: '搜这个词' }).first().click()
    await expect(page.locator('input').first()).toHaveValue('earbuds')
    await expect(page.locator('tbody tr', { hasText: 'SKU-WE-01' })).toHaveCount(1)
  })

  test('清空历史要二次确认：取消时一条 DELETE 都不发', async ({ page }) => {
    const dels: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/search/deleteHistory') dels.push(r.method())
    })
    await page.goto('/search')
    await page.getByRole('button', { name: '清空历史' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('不可恢复')
    await expect(page.locator('.confirm-detail')).toContainText('2 条')
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    await expect(page.locator('.modal-mask')).toHaveCount(0)
    expect(dels).toEqual([])

    await page.getByRole('button', { name: '清空历史' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => dels.length).toBe(1)
    expect(dels[0]).toBe('DELETE')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('Procurement 交互（采购供应链）', () => {
  test('计划行按状态机给操作，操作人没填时通过/驳回不可点', async ({ page }) => {
    await page.goto('/procurement')
    await waitReady(page)
    await page.locator('.tab', { hasText: '采购计划' }).click()
    const pending = page.locator('tbody tr', { hasText: 'PLAN-0012' }).first()
    await expect(pending).toContainText('PENDING_APPROVAL')
    await expect(pending.locator('button', { hasText: '通过' })).toBeDisabled()
    await expect(pending.locator('button', { hasText: '驳回' })).toBeDisabled()
    await expect(pending.locator('button', { hasText: '转采购单' })).toHaveCount(0)
    const approved = page.locator('tbody tr', { hasText: 'PLAN-0013' }).first()
    await expect(approved.locator('button', { hasText: '转采购单' })).toBeVisible()
    await expect(approved.locator('button', { hasText: '通过' })).toHaveCount(0)
  })

  test('审批请求把操作人与意见一起送出去，并打到那条计划上', async ({ page }) => {
    const reqs: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname.endsWith('/approve')) reqs.push(decodeURIComponent(r.url()))
    })
    await page.goto('/procurement')
    await waitReady(page)
    await page.locator('.tab', { hasText: '采购计划' }).click()
    const pending = page.locator('tbody tr', { hasText: 'PLAN-0012' }).first()
    await pending.locator('input').first().fill('张经理')
    await pending.locator('input').nth(1).fill('价格已核')
    await pending.locator('button', { hasText: '通过' }).click()
    await expect.poll(() => reqs.length).toBe(1)
    expect(reqs[0]).toContain('/procurement/plan/12/approve')
    expect(reqs[0]).toContain('operator=张经理')
    expect(reqs[0]).toContain('approved=true')
    expect(reqs[0]).toContain('comment=价格已核')
  })

  test('审批留痕按计划展开：有留痕渲染真实动作，没有留痕给诚实空态而不是编一行', async ({ page }) => {
    await page.goto('/procurement')
    await waitReady(page)
    await page.locator('.tab', { hasText: '采购计划' }).click()
    const pending = page.locator('tbody tr', { hasText: 'PLAN-0012' }).first()
    await pending.locator('button', { hasText: '审批留痕' }).click()
    await expect(page.locator('.trail-row')).toHaveCount(1)
    await expect(page.locator('.trail-row')).toContainText('APPROVE')
    await expect(page.locator('.trail-row')).toContainText('张经理')
    await expect(page.locator('.trail-row')).toContainText('价格已核')
    await expect(page.locator('.trail-row')).toContainText('（无意见）')
    await expect(page.locator('.trail-row tbody tr')).toHaveCount(2)

    // 同一行再点一次是收起，不是重新拉取
    await pending.locator('button', { hasText: '审批留痕' }).click()
    await expect(page.locator('.trail-row')).toHaveCount(0)

    await page.locator('tbody tr', { hasText: 'PLAN-0013' }).first()
      .locator('button', { hasText: '审批留痕' }).click()
    await expect(page.locator('.trail-row')).toContainText('不会伪造')
    await expect(page.locator('.trail-row tbody tr')).toHaveCount(0)
  })
})

test.describe('WarehouseAlerts 交互（海外仓库存与预警）', () => {
  test('库存快照渲染真实列，预警规则给不可判定类型与只存不读的渠道标注', async ({ page }) => {
    await page.goto('/warehouse-alerts')
    const stock = page.locator('.tab-panel[data-panel="stock"]')
    await expect(stock.locator('tbody tr')).toHaveCount(2)
    await expect(stock).toContainText('SKU-WH-01')
    await expect(stock).toContainText('洛杉矶仓')
    await expect(stock).toContainText('62')
    await expect(page.locator('.notice-zone')).toContainText('不提供手工录入库存')

    await page.locator('.tab', { hasText: '预警规则' }).click()
    const rules = page.locator('.tab-panel[data-panel="alert"]')
    await expect(rules).toContainText('LOW_STOCK')
    await expect(rules).toContainText('EMAIL（只存不读）')
    await expect(rules).toContainText('（不会被判定）')
    await expect(page.locator('.tab-panel')).toHaveCount(1)
  })

  test('新建规则的类型下拉只有后端真会判定的那 5 个', async ({ page }) => {
    await page.goto('/warehouse-alerts')
    await page.locator('.tab', { hasText: '预警规则' }).click()
    await page.getByRole('button', { name: '新建规则' }).click()
    const options = await page.locator('.modal select').first().locator('option').allTextContents()
    expect(options).toEqual(['LOW_STOCK', 'STOCKOUT', 'OVERSTOCK', 'AGING', 'NO_MOVEMENT'])
    expect(options).not.toContain('DAMAGE_RISK')
    await expect(page.locator('.modal-note')).toContainText('会被真的判定')
    await expect(page.locator('.modal-note')).toContainText('通知渠道存了也没人读')
  })

  test('立即检查要确认，确认后才出计数，扫描截断时说明结论不是全量', async ({ page }) => {
    const checks: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname.includes('/alert/check/')) checks.push(r.method())
    })
    await page.goto('/warehouse-alerts')
    await page.locator('.tab', { hasText: '预警规则' }).click()
    await page.getByRole('button', { name: '立即检查' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('上限 500 行')
    expect(checks).toEqual([])
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()

    const panel = page.locator('.tab-panel[data-panel="check"]')
    await expect(panel).toContainText('触发 1 条')
    await expect(panel).toContainText('CRITICAL 1')
    await expect(panel).toContainText('SKU-WH-01')
    await expect(panel.locator('.advisory')).toContainText('不是全量')
    expect(checks).toEqual(['GET'])
  })
})

test.describe('ConnectorQueue 交互（调用队列与限流）', () => {
  test('发件箱按状态与方法标注，页面说明人工重放的后果', async ({ page }) => {
    await page.goto('/connector-queue')
    const panel = page.locator('.tab-panel[data-panel="outbox"]')
    await expect(panel.locator('tbody tr')).toHaveCount(3)
    await expect(panel).toContainText('DLQ')
    await expect(panel).toContainText('RATE_LIMITED')
    await expect(panel.locator('tr', { hasText: '/feeds/2021-06-30/documents' })).toContainText('POST')
    await expect(page.locator('.notice-zone')).toContainText('人工点「重放」会按记录原方法重发')
    await expect(page.locator('.notice-zone')).toContainText('不看右上角店铺')
  })

  test('状态筛选真的带到后端，而不是前端过滤', async ({ page }) => {
    const urls: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/connectors/outbox') urls.push(decodeURIComponent(r.url()))
    })
    await page.goto('/connector-queue')
    await expect(page.locator('.tab-panel[data-panel="outbox"] tbody tr')).toHaveCount(3)
    await page.locator('.tab-panel[data-panel="outbox"] select').first().selectOption('DLQ')
    await expect.poll(() => urls.length).toBe(2)
    expect(urls[1]).toContain('status=DLQ')
    expect(urls[0]).not.toContain('status=')
  })

  test('重放要二次确认，确认后才发 POST 并刷新列表', async ({ page }) => {
    const replays: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/connectors/outbox/901/replay') replays.push(r.method())
    })
    await page.goto('/connector-queue')
    // 表里显示的是方法与路径（operationId 没有列），按路径定位那一行
    const row = page.locator('tbody tr', { hasText: '/feeds/2021-06-30/documents' }).first()
    await row.locator('button', { hasText: '重放' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('可能在平台侧产生真实副作用')
    expect(replays).toEqual([])
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    expect(replays).toEqual([])

    await row.locator('button', { hasText: '重放' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => replays.length).toBe(1)
    expect(replays[0]).toBe('POST')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('AdSearchTerms 交互（搜索词分析与广告规则）', () => {
  test('规则表按后端语义渲染，死规则的缺上界要看得见', async ({ page }) => {
    await page.goto('/ad-search-terms')
    const panel = page.locator('.tab-panel[data-panel="rules"]')
    await expect(panel.locator('tbody tr')).toHaveCount(2)
    await expect(panel).toContainText('ACOS GT 50')
    await expect(panel).toContainText('CAMPAIGN=camp-777')
    await expect(panel).toContainText('缺上界')
    await expect(page.locator('.notice-zone')).toContainText('没有自动来源')
    await expect(page.locator('.notice-zone')).toContainText('只产出建议')
  })

  test('出建议必须二次确认，结果卡由后端标记决定文案', async ({ page }) => {
    const executes: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/ad/search-term/rule/41/execute') executes.push(r.method())
    })
    await page.goto('/ad-search-terms')
    const row = page.locator('tbody tr', { hasText: '高ACoS自动暂停' }).first()
    await row.locator('button', { hasText: '出建议' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('不会暂停投放')
    expect(executes).toEqual([])
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    expect(executes).toEqual([])

    await row.locator('button', { hasText: '出建议' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => executes.length).toBe(1)
    const card = page.locator('.result-card')
    await expect(card).toContainText('仅建议，未下发广告账号')
    await expect(card).toContainText('暂停该搜索词所在投放')
    await expect(card).toContainText('未调用广告 API')
  })

  test('报表与分析分区各自取数，空态不把没数据说成好结果', async ({ page }) => {
    await page.goto('/ad-search-terms')
    await page.locator('.tab', { hasText: '搜索词报表' }).click()
    const terms = page.locator('.tab-panel[data-panel="terms"]')
    await expect(terms.locator('tbody tr')).toHaveCount(1)
    await expect(terms).toContainText('yoga mat')

    await page.locator('.tab', { hasText: '综合分析' }).click()
    await page.locator('.tab-panel[data-panel="analyze"] button', { hasText: '重新分析' }).click()
    const analyze = page.locator('.tab-panel[data-panel="analyze"]')
    await expect(analyze).toContainText('扫描行数')
    await expect(analyze.locator('.metric-value').first()).toHaveText('1')
    await expect(analyze).toContainText('yoga mat')

    await page.locator('.tab', { hasText: 'ASIN 反查' }).click()
    await page.locator('.tab-panel[data-panel="asin"] input').first().fill('B0ABC12345')
    await page.locator('.tab-panel[data-panel="asin"] button', { hasText: '反查' }).click()
    await expect(page.locator('.tab-panel[data-panel="asin"] tbody tr')).toHaveCount(1)
  })
})

test.describe('AdBidSchedule 交互（分时调价）', () => {
  test('列表解释时段与倍率，并声明本页会真实改价', async ({ page }) => {
    await page.goto('/ad-bid-schedule')
    const panel = page.locator('[data-panel="list"]')
    await expect(panel.locator('tbody tr')).toHaveCount(3)
    await expect(panel).toContainText('20:00-24:00')
    await expect(panel).toContainText('全部活动')
    await expect(panel).toContainText('× 1.5')
    await expect(panel).toContainText('启用中（会改价）')
    await expect(page.locator('.notice-zone')).toContainText('真实修改广告账号竞价')
    await expect(page.locator('.notice-zone')).toContainText('不支持跨零点')
  })

  test('跨零点的窗口在前端就被拦住，改成合法才允许提交', async ({ page }) => {
    await page.goto('/ad-bid-schedule')
    const creates: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/ad/bidSchedule') creates.push(r.method())
    })
    await page.locator('button', { hasText: '新建调价规则' }).click()
    const form = page.locator('.form-card')
    await form.locator('select').first().selectOption('22')
    await form.locator('select').nth(1).selectOption('2')
    const submit = form.locator('.form-actions .page-btn', { hasText: '创建规则' })
    await expect(form).toContainText('永远不会生效')
    await expect(submit).toBeDisabled()
    expect(creates).toEqual([])

    await form.locator('select').nth(1).selectOption('23')
    await expect(submit).toBeEnabled()
    await submit.click()
    await expect(page.locator('.confirm-detail')).toContainText('基准价 × 1.2')
    expect(creates).toEqual([])
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => creates.length).toBe(1)
    expect(creates[0]).toBe('POST')
  })

  test('停用要确认，且确认文案承认已改出的价不会回滚', async ({ page }) => {
    const toggles: string[] = []
    page.on('request', (r) => {
      if (/\/api\/ad\/bidSchedule\/\d+\/toggle/.test(new URL(r.url()).pathname)) toggles.push(r.url())
    })
    await page.goto('/ad-bid-schedule')
    const row = page.locator('tbody tr', { hasText: '× 1.5' }).first()
    await row.locator('button', { hasText: '停用' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('已经按这条规则改过的竞价会留在广告账号上')
    expect(toggles).toEqual([])
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    expect(toggles).toEqual([])

    await row.locator('button', { hasText: '停用' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => toggles.length).toBe(1)
    expect(decodeURIComponent(toggles[0])).toContain('enabled=false')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('ListingMonitor 交互（商品与 Listing 监控）', () => {
  test('整页没有接口失败，趋势查询带 days 且缺失排名显示 —', async ({ page }) => {
    const urls: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname.includes('/ranking/trend/')) urls.push(decodeURIComponent(u.search))
    })
    await page.goto('/listings')
    await expect(page.locator('.error-zone')).toHaveCount(0)
    await expect(page.locator('.tab')).toHaveCount(6)

    await page.locator('.tab', { hasText: '关键词排名' }).click()
    const trend = page.locator('[data-panel="trend"]')
    await expect(trend.locator('.action-btn')).toBeDisabled()
    await trend.locator('input').first().fill('B00000000001')
    await trend.locator('.action-btn').click()
    await expect.poll(() => urls.length).toBe(1)
    expect(urls[0]).toContain('days=30')
    await expect(trend).toContainText('yoga mat · 2 个点')
    await expect(trend.locator('tbody tr').first()).toContainText('—')
    await expect(trend).not.toContainText('命中后端单读上限')
  })

  test('人工登记自查：换行能传出去，A+ 未检查要如实显示，写完会重算列表与概览', async ({ page }) => {
    const checks: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname.endsWith('/product/listing-monitor/health/check')) checks.push(u.search)
    })
    await page.goto('/listings')
    const panel = page.locator('[data-panel="check"]')
    await expect(panel).toContainText('会写入健康度表')
    await expect(panel.locator('.action-btn')).toBeDisabled()

    await panel.locator('input').first().fill('B000026108')
    await panel.locator('textarea').fill('第一条\n第二条\n第三条\n第四条\n第五条')
    await panel.locator('.action-btn').click()
    await expect.poll(() => checks.length).toBe(1)
    // 五点必须是 5 段：换行如果在这里丢了，后端会判成「不足 5 条」
    expect((checks[0].match(/%0A|\n/g) || []).length).toBe(4)
    expect(checks[0]).toContain('asin=B000026108')
    expect(checks[0]).not.toContain('aplus=')

    await expect(page.locator('.check-result')).toContainText('CRITICAL')
    await expect(page.locator('.check-result')).toContainText('未检查')
    // 写库之后健康度列表与概览必须重算（初载 1 次 + 登记后 1 次）
    await expect(page.locator('[data-panel="check"]')).toBeVisible()
  })

  test('竞品对比：说清只有竞品一侧，三态列不把未知画成「否」', async ({ page }) => {
    await page.goto('/listings')
    await page.locator('.tab', { hasText: '竞品监控' }).click()
    const cmp = page.locator('[data-panel="compare"]')
    await cmp.locator('input').nth(1).fill('B0COMPET01')
    await cmp.locator('.action-btn').click()
    await expect(cmp).toContainText('不是「并排对比」')
    await expect(cmp).toContainText('2026-10-01')
    const row = cmp.locator('tbody tr').first()
    await expect(row).toContainText('是')
    await expect(row).toContainText('否')
    await expect(row).toContainText('—')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('MultiplatformOrders 交互（多平台订单）', () => {
  test('订单列表按平台与状态标注，说明区讲清本地表与真实回传', async ({ page }) => {
    await page.goto('/multiplatform')
    const panel = page.locator('[data-panel="orders"]')
    await expect(panel.locator('tbody tr')).toHaveCount(2)
    await expect(panel).toContainText('TE-9001')
    await expect(panel).toContainText('TEMU')
    await expect(panel).toContainText('PAID')
    await expect(panel).toContainText('186.5')
    await expect(page.locator('.notice-zone')).toContainText('本地统一订单表')
    await expect(page.locator('.notice-zone')).toContainText('真实回传给平台')
    await expect(page.locator('.notice-zone')).toContainText('不提供')
  })

  test('全平台同步要确认，结果点名失败的平台', async ({ page }) => {
    const syncs: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/multiplatform/sync/all/1') syncs.push(r.method())
    })
    await page.goto('/multiplatform')
    await page.locator('[data-panel="sync"] button', { hasText: '同步全部平台' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('单个平台失败不会中断另外两个')
    expect(syncs).toEqual([])
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => syncs.length).toBe(1)
    const result = page.locator('[data-panel="sync"] .sync-result').first()
    await expect(result).toContainText('尝试 3')
    await expect(result).toContainText('失败平台：TIKTOK')
    await expect(result).toContainText('才等于「确实没有新单」')
  })

  test('发货回传需要运单号，且真的按 POST 发给平台回传端点', async ({ page }) => {
    const ships: string[] = []
    page.on('request', (r) => {
      if (/\/api\/multiplatform\/order\/\d+\/ship/.test(new URL(r.url()).pathname)) ships.push(decodeURIComponent(r.url()))
    })
    await page.goto('/multiplatform')
    const row = page.locator('tbody tr', { hasText: 'TE-9001' }).first()
    await row.locator('button', { hasText: '发货回传' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('运单号会被提交给该平台')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    // 空运单号不发请求
    expect(ships).toEqual([])

    await row.locator('button', { hasText: '发货回传' }).click()
    await page.locator('.modal input').fill('TRK-NEW-1')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => ships.length).toBe(1)
    expect(ships[0]).toContain('trackingNo=TRK-NEW-1')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })
})

test.describe('MultiplatformOps 交互（多平台运营台）', () => {
  test('六个 Tab 各自拉自己的列表，账号行里没有凭证值', async ({ page }) => {
    const paths: string[] = []
    page.on('request', (r) => {
      const p = new URL(r.url()).pathname
      if (p.startsWith('/api/multiplatform/')) paths.push(p)
    })
    await page.goto('/multiplatform-ops')
    const accounts = page.locator('[data-panel="accounts"]')
    await expect(accounts.locator('tbody tr')).toHaveCount(2)
    await expect(accounts).toContainText('Temu US 旗舰店')
    await expect(accounts).toContainText('从未')
    await expect(page.locator('.notice-zone')).toContainText('凭证只写不回显')
    // 后端有、语义未定的三个入口不能出现在页面上
    for (const label of ['测试连接', '回复', '同步', '查看密钥']) {
      await expect(page.locator('.tab-panel button', { hasText: label })).toHaveCount(0)
    }
    // 首屏只应请求账号列表，其余五个要等切 Tab
    expect(paths).toEqual(['/api/multiplatform/account/list/1'])

    await page.locator('.tab', { hasText: '库存' }).click()
    await expect(page.locator('[data-panel="inventory"]')).toContainText('SKU-A')
    await page.locator('[data-panel="inventory"] button', { hasText: '按平台+SKU 汇总' }).click()
    await expect(page.locator('[data-panel="inventory"] .metric-grid')).toContainText('15')
    await expect(page.locator('[data-panel="inventory"]')).toContainText('不是平台侧库存快照时间')

    await page.locator('.tab', { hasText: 'Webhook 事件' }).click()
    await expect(page.locator('[data-panel="webhook"]')).toContainText('只写日志，不触发业务动作')
    expect(paths).toContain('/api/multiplatform/webhook/list/1')
  })

  test('新增账号要确认才落库，apiKey 留空时请求体里根本不出现这个键', async ({ page }) => {
    const bodies: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/multiplatform/account' && r.method() === 'POST') {
        bodies.push(r.postData() || '')
      }
    })
    await page.goto('/multiplatform-ops')
    await page.locator('[data-panel="accounts"] button', { hasText: '新增平台账号' }).click()
    await page.locator('.form-card input').first().fill('Temu EU 店')
    await page.locator('.form-actions button', { hasText: '创建账号' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('状态由后端固定为 ACTIVE')
    expect(bodies).toEqual([])
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => bodies.length).toBe(1)
    const body = JSON.parse(bodies[0])
    expect(body.storeName).toBe('Temu EU 店')
    expect(body.shopId).toBe(1)
    expect('apiKey' in body).toBe(false)
    expect('status' in body).toBe(false)
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })

  test('删除账号说明是物理删除；分配处理人空值不发请求', async ({ page }) => {
    const assigns: string[] = []
    page.on('request', (r) => {
      if (/\/api\/multiplatform\/message\/\d+\/assign/.test(new URL(r.url()).pathname)) {
        assigns.push(decodeURIComponent(r.url()))
      }
    })
    await page.goto('/multiplatform-ops')
    await page.locator('[data-panel="accounts"] tbody tr').first()
      .locator('button', { hasText: '删除' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('物理删除')
    await page.locator('.modal-actions button', { hasText: '取消' }).click()
    await expect(page.locator('.modal')).toHaveCount(0)

    await page.locator('.tab', { hasText: '消息' }).click()
    const msgRow = page.locator('[data-panel="messages"] tbody tr').first()
    await expect(msgRow).toContainText('未分配')
    await msgRow.locator('button', { hasText: '分配处理人' }).click()
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    expect(assigns).toEqual([])
    await expect(page.locator('.error-zone')).toContainText('处理人不能为空')

    await msgRow.locator('button', { hasText: '分配处理人' }).click()
    await page.locator('.modal input').fill('客服乙')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => assigns.length).toBe(1)
    // 记录时已 decodeURIComponent，所以这里断言解码后的中文，而不是 %E5%AE%A2…
    expect(assigns[0]).toContain('assignedTo=客服乙')
  })

  test('轮换密钥后明文只显示一次，关掉就取不回来', async ({ page }) => {
    await page.goto('/multiplatform-ops')
    await page.locator('.tab', { hasText: 'ISV 应用' }).click()
    const appRow = page.locator('[data-panel="apps"] tbody tr').first()
    await expect(appRow).toContainText('ak-visible-part')
    await appRow.locator('button', { hasText: '轮换密钥' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('旧密钥立刻失效')
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()

    const card = page.locator('.secret-card')
    await expect(card).toContainText('ak-issued-e2e')
    await expect(card).toContainText('sk-issued-e2e-secret')
    await card.locator('button', { hasText: '我已保存' }).click()
    await expect(card).toHaveCount(0)
    // 关掉之后没有任何端点可以再取回明文：列表行仍然只有可见部分
    await expect(page.locator('[data-panel="apps"]')).not.toContainText('sk-issued-e2e-secret')
  })
})

test.describe('ProfitReport 交互（利润报表与下钻）', () => {
  test('统计区间可见可改，且不再有填不上的「按店铺」维度', async ({ page }) => {
    const reportCalls: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname === '/api/order/profit/report') reportCalls.push(u.search)
    })
    await page.goto('/profit')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })

    // 此前这个区间写死在代码里，页面上看不到；现在它是能改的输入，并且标进了汇总卡片
    await expect(page.locator('.window-row')).toContainText('当前区间 2026-06-01 ~ 2026-06-30')
    await expect(page.locator('.summary-label').first()).toContainText('2026-06-01 ~ 2026-06-30')
    expect(reportCalls.length).toBe(1)
    expect(decodeURIComponent(reportCalls[0])).toContain('startDate=2026-06-01')

    const dates = page.locator('.window-row input')
    await dates.nth(0).fill('2026-09-01')
    await dates.nth(1).fill('2026-09-30')
    await page.locator('.window-row .action-btn').click()
    await expect.poll(() => reportCalls.length).toBe(2)
    expect(decodeURIComponent(reportCalls[1])).toContain('startDate=2026-09-01')
    expect(decodeURIComponent(reportCalls[1])).toContain('endDate=2026-09-30')

    const tabs = page.locator('.dim-tab')
    await expect(tabs).toHaveCount(2)
    await expect(tabs.filter({ hasText: '按店铺' })).toHaveCount(0)
    await expect(page.locator('.notice-zone')).toContainText('本页不提供「按店铺」维度')
  })

  test('「按 SKU×月」真的去查后端聚合，而不是把示例月份搬过来', async ({ page }) => {
    const hits: string[] = []
    page.on('request', (r) => {
      if (new URL(r.url()).pathname === '/api/order/profit/summary/1') hits.push('summary')
    })
    await page.goto('/profit')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })
    expect(hits).toEqual([])

    await page.locator('.dim-tab', { hasText: '按 SKU×月' }).click()
    await expect.poll(() => hits.length).toBe(1)
    const panel = page.locator('.table-card').first()
    await expect(panel).toContainText('SKU-A1 · 2026-09')
    await expect(panel).toContainText('SKU-B2 · 2026-08')
    // 聚合行没有费用分项，缺的列必须是留白而不是 0
    await expect(panel).toContainText('—')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })

  test('下钻按订单号取利润行，成本没取全的行标成「数据不全」', async ({ page }) => {
    const urls: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (u.pathname.startsWith('/api/order/profit/order/')) urls.push(u.pathname + u.search)
    })
    await page.goto('/profit')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 15000 })

    const panel = page.locator('[data-panel="drill"]')
    await expect(panel).toContainText('填一个订单号或 SKU 再查')
    expect(urls).toEqual([])

    await panel.locator('input').fill('111-2222222-3333333')
    await panel.locator('.action-btn', { hasText: '查询' }).click()
    await expect.poll(() => urls.length).toBe(1)
    expect(urls[0]).toContain('/api/order/profit/order/1/111-2222222-3333333')
    expect(urls[0]).toContain('size=20')

    await expect(panel).toContainText('2026-09-01')
    await expect(panel).toContainText('$5,000.00')
    await expect(panel.locator('.flag.incomplete')).toHaveCount(1)
    await expect(panel.locator('.flag')).toHaveCount(2)
    // 桩给的是 FULL_PAGE（hasMore=false），所以这里应当报「共 N 行」而不是「仍有下一页」
    await expect(panel).toContainText('共 2 行（按记录倒序）')
  })
})

test.describe('OpsAlerts 交互（运营预警台）', () => {
  test('差评告警渲染真实列，已处理的行不给按钮，三个扫描入口都不存在', async ({ page }) => {
    await page.goto('/ops-alerts')
    const panel = page.locator('[data-panel="reviews"]')
    await expect(panel.locator('tbody tr')).toHaveCount(2)
    await expect(panel).toContainText('B0REVIEW01')
    await expect(panel).toContainText('R1001')
    await expect(panel).toContainText('未记录')
    await expect(page.locator('.notice-zone')).toContainText('ThreadLocalRandom')
    await expect(page.locator('.notice-zone')).toContainText('不会联系买家')

    // 只有 NEW 那行有可用的「标记已处理」
    const newRow = panel.locator('tbody tr', { hasText: 'B0REVIEW01' })
    const handledRow = panel.locator('tbody tr', { hasText: 'B0REVIEW02' })
    await expect(newRow.locator('button', { hasText: '标记已处理' })).toBeEnabled()
    await expect(handledRow.locator('button', { hasText: '标记已处理' })).toBeDisabled()

    for (const label of ['扫描', '抓取', '忽略']) {
      await expect(page.locator('.tab-panel button', { hasText: label })).toHaveCount(0)
    }
    await page.locator('.tab', { hasText: '跟卖告警' }).click()
    const hijack = page.locator('[data-panel="hijacks"]')
    await expect(hijack).toContainText('已被抢走')
    await expect(hijack).toContainText('本页只读')
    await expect(hijack.locator('button', { hasText: '标记已处理' })).toHaveCount(0)
  })

  test('标记已处理要先确认，确认后才发那个 POST', async ({ page }) => {
    const posts: string[] = []
    page.on('request', (r) => {
      if (/\/api\/ops\/review\/\d+\/handle/.test(new URL(r.url()).pathname)) posts.push(r.method())
    })
    await page.goto('/ops-alerts')
    await page.locator('[data-panel="reviews"] tbody tr', { hasText: 'B0REVIEW01' })
      .locator('button', { hasText: '标记已处理' }).click()
    await expect(page.locator('.confirm-detail')).toContainText('NEW 改成 HANDLED')
    expect(posts).toEqual([])
    await page.locator('.modal-actions button', { hasText: '确认执行' }).click()
    await expect.poll(() => posts.length).toBe(1)
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })

  test('排名趋势要两个条件都填，查询按 keyword+ASIN 打给后端', async ({ page }) => {
    const urls: string[] = []
    page.on('request', (r) => {
      const p = new URL(r.url())
      if (p.pathname === '/api/ops/rank/trend') urls.push(decodeURIComponent(p.search))
    })
    await page.goto('/ops-alerts')
    await page.locator('.tab', { hasText: '关键词排名' }).click()
    const form = page.locator('[data-panel="rank"] .form-card')
    await expect(form.locator('button', { hasText: '查询趋势' })).toBeDisabled()
    await form.locator('input').first().fill('wireless earbuds')
    await expect(form.locator('button', { hasText: '查询趋势' })).toBeDisabled()
    expect(urls).toEqual([])

    await form.locator('input').nth(1).fill('B0123456789')
    await form.locator('button', { hasText: '查询趋势' }).click()
    await expect.poll(() => urls.length).toBe(1)
    // 空格编码成 %20 还是 + 由 axios/URLSearchParams 决定，两种都算带上了这个关键词
    expect(urls[0]).toMatch(/keyword=wireless[+ ]earbuds/)
    expect(urls[0]).toContain('asin=B0123456789')
    const metrics = page.locator('[data-panel="rank"] .metric-grid')
    await expect(metrics).toContainText('最新排名')
    await expect(metrics).toContainText('31')
    await expect(page.locator('[data-panel="rank"]')).toContainText('最近 200 个点')
  })
})

test.describe('AgentMemory 交互（助手记忆）', () => {
  test('偏好与对话按真实表渲染，且不给出没有后端的入口', async ({ page }) => {
    await page.goto('/agent-memory')
    await expect(page.locator('.skeleton-zone')).toHaveCount(0, { timeout: 20000 })

    // 身份取自 GET /user/getInfo（桩里 id=1），会话键随之为 sess-1
    await expect(page.locator('.meta-row')).toContainText('用户 ID 1')
    await expect(page.locator('.filter-row')).toContainText('sess-1')
    await expect(page.locator('.notice-zone')).toContainText('main.ts')

    const inputs = page.locator('.form-card .field input')
    await expect(inputs.nth(0)).toHaveValue('E2E 用户')
    await expect(inputs.nth(1)).toHaveValue('1')
    await expect(inputs.nth(2)).toHaveValue('瑜伽用品')
    await expect(page.locator('.lang-row')).toContainText('当前生效 ZH')

    const rows = page.locator('.history-card tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0)).toContainText('最近7天销量如何？')
    await expect(rows.nth(1)).toContainText('近 7 天共 12 单。')

    // 三个后端存在但没有真实数据源/需要 LLM key 的入口，页面上必须不存在
    for (const label of ['发起对话', '扫描提醒', '运行评测']) {
      await expect(page.locator('button', { hasText: label })).toHaveCount(0)
    }
  })

  test('保存偏好只带非空字段，语言切换走 /language 便捷端点', async ({ page }) => {
    const calls: Array<{ path: string; search: string; body: string }> = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      // 只记写入类请求：页面加载本身会打两条 GET（偏好 + 历史），
      // 把它们算进来会让「点了几个按钮发了几个请求」的断言失去意义。
      if (u.pathname.startsWith('/api/ai/agent/memory/') && r.method() !== 'GET') {
        calls.push({ path: u.pathname, search: u.search, body: r.postData() || '' })
      }
    })

    await page.goto('/agent-memory')
    const inputs = page.locator('.form-card .field input')
    await expect(inputs.nth(0)).toHaveValue('E2E 用户', { timeout: 20000 })
    await inputs.nth(2).fill('')
    await page.locator('.form-actions button', { hasText: '保存偏好' }).click()
    await expect.poll(() => calls.length).toBe(1)
    expect(calls[0].path).toBe('/api/ai/agent/memory/preference')
    const sent = JSON.parse(calls[0].body) as Record<string, unknown>
    // 清空的品类不会被提交：后端按非空字段更新，提交空串也不等于清空，
    // 所以这里只应看到仍然有值的两个字段。
    expect(Object.keys(sent).sort()).toEqual(['nickname', 'preferredShopId'])
    expect(sent.nickname).toBe('E2E 用户')
    expect(sent.preferredShopId).toBe(1)
    await expect(page.locator('.form-actions')).toContainText('已保存')

    await page.locator('.lang-row select').selectOption('EN')
    await page.locator('.lang-row button', { hasText: '仅切换语言' }).click()
    await expect.poll(() => calls.length).toBe(2)
    expect(calls[1].path).toBe('/api/ai/agent/memory/language')
    expect(calls[1].search).toContain('language=EN')
    await expect(page.locator('.lang-row')).toContainText('当前生效 EN')
  })
})

test.describe('库位编辑与补货重算', () => {
  test('库存列表就地改库位：把新值 PUT 给后端并回显到这一行', async ({ page }) => {
    const puts: string[] = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (r.method() === 'PUT' && u.pathname.startsWith('/api/logistics/warehouse/inventory/')) {
        puts.push(decodeURIComponent(u.pathname + u.search))
      }
    })

    await page.goto('/warehouse')
    // 库存表在「库存查询」Tab 里，默认 Tab 是仓库列表；不切过去元素在 DOM 里但不可见
    await page.locator('.tab-item', { hasText: '库存查询' }).click()
    const first = page.locator('.loc-btn').first()
    await expect(first).toBeEnabled({ timeout: 20000 })
    await first.click()
    await page.locator('.loc-input').fill('B-09-09')
    await page.locator('.loc-edit button', { hasText: '保存' }).click()

    await expect.poll(() => puts.length).toBe(1)
    expect(puts[0]).toContain('/logistics/warehouse/inventory/1/location')
    expect(puts[0]).toContain('locationCode=B-09-09')
    await expect(page.locator('.loc-view').first()).toContainText('B-09-09')
    await expect(page.locator('.loc-saved')).toContainText('已保存')
    await expect(page.locator('.loc-error')).toHaveCount(0)
  })

  test('空库位不发请求，并说明后端不做静默清空', async ({ page }) => {
    let puts = 0
    page.on('request', (r) => { if (r.method() === 'PUT') puts++ })

    await page.goto('/warehouse')
    await page.locator('.tab-item', { hasText: '库存查询' }).click()
    await expect(page.locator('.loc-btn').first()).toBeEnabled({ timeout: 20000 })
    await page.locator('.loc-btn').first().click()
    await page.locator('.loc-input').fill('   ')
    await page.locator('.loc-edit button', { hasText: '保存' }).click()

    await expect(page.locator('.loc-error')).toContainText('不能为空')
    expect(puts).toBe(0)
  })

  test('库存监控页重算补货建议：POST calc 后展示条数', async ({ page }) => {
    const posts: string[] = []
    page.on('request', (r) => {
      const p = new URL(r.url()).pathname
      if (r.method() === 'POST' && p.startsWith('/api/spapi/replenish/calc/')) posts.push(p)
    })

    await page.goto('/inventory')
    await expect(page.locator('.recalc-btn')).toBeEnabled({ timeout: 20000 })
    // 说明文案只在还没重算时出现（成功后同一位置换成结果条数），所以点之前先断言
    await expect(page.locator('.recalc-hint')).toContainText('逐条 upsert')
    await page.locator('.recalc-btn').click()

    await expect.poll(() => posts.length).toBe(1)
    expect(posts[0]).toBe('/api/spapi/replenish/calc/1')
    await expect(page.locator('.recalc-msg')).toContainText('3 条建议')
  })
})

test.describe('个人资料', () => {
  test('只读区打码手机号，保存只提交改过的那一个字段', async ({ page }) => {
    const puts: Array<{ path: string; body: string }> = []
    page.on('request', (r) => {
      const u = new URL(r.url())
      if (r.method() === 'PUT' && u.pathname === '/api/user/editInfo') {
        puts.push({ path: u.pathname, body: r.postData() || '' })
      }
    })

    await page.goto('/profile')
    const grid = page.locator('.field-grid')
    await expect(grid).toContainText('138****0000', { timeout: 20000 })
    await expect(grid).not.toContainText('13800000000')
    await expect(page.locator('.kv:has(.k:text("角色")) .v')).toHaveText('ADMIN')

    await page.locator('.field input').first().fill('E2E 改名')
    await expect(page.locator('.form-actions')).toContainText('将提交 1 个字段：昵称')
    await page.locator('.form-actions .action-btn.primary').click()

    await expect.poll(() => puts.length).toBe(1)
    const sent = JSON.parse(puts[0].body) as Record<string, unknown>
    expect(Object.keys(sent)).toEqual(['nickname'])
    expect(sent.nickname).toBe('E2E 改名')
    await expect(page.locator('.saved-tip')).toContainText('已保存 1 个字段')
    await expect(page.locator('.error-zone')).toHaveCount(0)
  })

  test('没有改动时保存按钮禁用，避免提交一次空更新', async ({ page }) => {
    await page.goto('/profile')
    await expect(page.locator('.form-actions')).toContainText('没有改动', { timeout: 20000 })
    await expect(page.locator('.form-actions .action-btn.primary')).toBeDisabled()
  })

  test('角色是 VIEWER 时海外仓不给改库位入口', async ({ page }) => {
    // 后注册的 route 先匹配：用它覆盖 installApiStub 里那个 ADMIN 用户，
    // 才能真的走到「入口隐藏」这条分支（角色未知时是故意不隐藏的）。
    await page.route(/\/api\/user\/getInfo/, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: { user: { id: 1, phone: '13800000000', nickname: 'E2E', role: 'VIEWER' } } })
      })
    )

    await page.goto('/warehouse')
    await page.locator('.tab-item', { hasText: '库存查询' }).click()
    await expect(page.locator('.loc-locked')).toContainText('需 OPERATOR/ADMIN', { timeout: 20000 })
    await expect(page.locator('.loc-btn')).toHaveCount(0)
    // 入口隐藏不等于数据被藏起来：库位值本身必须仍然可读
    await expect(page.locator('.loc-view').first()).toContainText('A-01-03')
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
