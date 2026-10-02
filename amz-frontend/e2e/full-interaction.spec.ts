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
  { path: '/notifications', linkText: '消息中心' },
  { path: '/customer', linkText: '客服中心' }
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
