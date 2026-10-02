import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MultiplatformOps from '../views/MultiplatformOps.vue'

vi.mock('@/api/multiplatform', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/multiplatform')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/multiplatform'

const ok = <T>(data: T, page: any = null) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page }) as any
const fail = (message: string) => ({ code: 500, message, data: null } as any)
const paged = (cursor = 'v1:99') =>
  ({ size: 20, returned: 2, hasMore: true, truncated: true, nextCursor: cursor, total: null })

const ACCOUNTS = [
  {
    id: 7, shopId: 1, platform: 'TEMU', storeName: 'Temu US 旗舰店',
    apiEndpoint: 'https://open-api.temu.com', status: 'ACTIVE',
    tokenExpiresAt: '2026-11-01 00:00:00', lastSyncTime: null, createTime: '2026-08-01 09:00:00',
    apiKey: null, apiKeyEncrypted: null, accessTokenEncrypted: null, refreshTokenEncrypted: null
  },
  {
    id: 8, shopId: 1, platform: 'SHEIN', storeName: null, apiEndpoint: null, status: 'ERROR',
    tokenExpiresAt: null, lastSyncTime: '2026-09-30 02:00:00', apiKey: 'sk-should-never-arrive'
  }
]

const PRODUCTS = [
  {
    id: 31, shopId: 1, platform: 'TEMU', platformProductId: 'TP-1001', title: 'Yoga mat',
    price: 19.9, currency: 'USD', stockQty: 120, status: 'ACTIVE',
    amazonAsin: 'B0ABC12345', amazonSku: 'AMZ-SKU-1'
  },
  {
    id: 32, shopId: 1, platform: 'TIKTOK', platformProductId: 'TT-2002', title: 'Lamp',
    price: 8, currency: 'EUR', stockQty: 0, status: 'OUT_OF_STOCK', amazonAsin: null, amazonSku: null
  }
]

const MESSAGES = [
  {
    id: 41, shopId: 1, platform: 'TEMU', platformMessageId: 'PM-1', buyerName: 'Ana',
    subject: 'When will it ship?', direction: 'IN', status: 'UNREAD', assignedTo: null,
    receiveTime: '2026-10-01 08:00:00'
  },
  {
    id: 42, shopId: 1, platform: 'SHEIN', platformMessageId: 'PM-2', buyerName: 'Bo',
    subject: 'Thanks', direction: 'OUT', status: 'REPLIED', assignedTo: '客服甲',
    receiveTime: '2026-10-01 09:00:00'
  }
]

const INVENTORY = [
  {
    id: 51, shopId: 1, platform: 'TEMU', platformProductId: 'TP-1001', sku: 'SKU-A',
    warehouse: 'GZ-01', availableQty: 0, reservedQty: 2, inboundQty: 30, snapshotTime: '2026-09-28 01:00:00'
  },
  {
    id: 52, shopId: 1, platform: 'TIKTOK', platformProductId: 'TT-2002', sku: 'SKU-B',
    warehouse: null, availableQty: 15, reservedQty: 0, inboundQty: null, snapshotTime: null
  }
]

const AGGREGATE = {
  shopId: 1, grandTotalAvailable: 15, byPlatform: { TEMU: { 'SKU-A': 0 }, TIKTOK: { 'SKU-B': 15 } },
  bySku: { 'SKU-A': 0, 'SKU-B': 15 }, computedAt: '2026-10-02 19:00:00'
}

const WEBHOOKS = [
  {
    id: 61, shopId: 1, platform: 'TEMU', eventType: 'ORDER_CREATED', eventId: 'EV-1',
    status: 'PROCESSED', processResult: '已记录；当前事件分发只写日志，不触发业务动作',
    processTime: '2026-10-01 07:00:00', createTime: '2026-10-01 07:00:00'
  },
  {
    id: 62, shopId: 1, platform: 'SHEIN', eventType: 'REFUND_CREATED', eventId: 'EV-2',
    status: 'FAILED', processResult: 'payload 解析失败', processTime: null, createTime: '2026-10-01 07:30:00'
  }
]

const APPS = [
  {
    id: 71, appName: 'WMS 对接', appKey: 'ak-old', scopes: 'order.read,inventory.read',
    redirectUris: 'https://wms.example/cb', rateLimitRpm: 60, status: 'ACTIVE', ownerShopId: 1
  }
]

const ISSUE = { appId: 71, appKey: 'ak-new', appSecret: 'sk-new-secret-please-copy' }

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listAccounts).mockResolvedValue(ok(ACCOUNTS))
  vi.mocked(api.listProducts).mockResolvedValue(ok(PRODUCTS))
  vi.mocked(api.listMessages).mockResolvedValue(ok(MESSAGES))
  vi.mocked(api.listInventory).mockResolvedValue(ok(INVENTORY))
  vi.mocked(api.aggregatedInventory).mockResolvedValue(ok(AGGREGATE))
  vi.mocked(api.listWebhookEvents).mockResolvedValue(ok(WEBHOOKS))
  vi.mocked(api.listApps).mockResolvedValue(ok(APPS))
  vi.mocked(api.createAccount).mockResolvedValue(ok(ACCOUNTS[0]))
  vi.mocked(api.updateAccount).mockResolvedValue(ok(ACCOUNTS[0]))
  vi.mocked(api.deleteAccount).mockResolvedValue(ok(true))
  vi.mocked(api.mapProduct).mockResolvedValue(ok(true))
  vi.mocked(api.assignMessage).mockResolvedValue(ok(true))
  vi.mocked(api.registerApp).mockResolvedValue(ok(ISSUE))
  vi.mocked(api.rotateAppSecret).mockResolvedValue(ok(ISSUE))
}

const mountPage = async () => {
  const wrapper = mount(MultiplatformOps, { global: stubs })
  await flushPromises()
  return wrapper
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

const openTab = async (wrapper: any, label: string) => {
  const tab = wrapper.findAll('.tab').find((t: any) => t.text() === label)
  expect(tab, `没有「${label}」这个 Tab`).toBeTruthy()
  await tab!.trigger('click')
  await flushPromises()
}

const rowBtn = async (wrapper: any, panel: string, index: number, label: string) => {
  const rows = wrapper.find(`[data-panel="${panel}"]`).findAll('tbody tr')
  expect(rows.length, `${panel} 行数不足`).toBeGreaterThan(index)
  const btn = rows[index].findAll('button').find((b: any) => b.text() === label)
  expect(btn, `第 ${index} 行没有「${label}」`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const confirmText = (wrapper: any) => {
  const box = wrapper.find('.modal')
  expect(box.exists(), '没有出现二次确认框').toBe(true)
  return box.text()
}

const panelText = (wrapper: any, panel: string) => {
  const p = wrapper.find(`[data-panel="${panel}"]`)
  expect(p.exists(), `没有 ${panel} 面板`).toBe(true)
  return p.text()
}

const lastCall = (fn: any) => vi.mocked(fn).mock.calls[vi.mocked(fn).mock.calls.length - 1]

describe('多平台运营台', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    happy()
  })

  it('未选店铺时不查任何列表，也不渲染六个面板', async () => {
    localStorage.clear()
    const wrapper = mount(MultiplatformOps, { global: stubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(api.listAccounts).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="accounts"]').exists()).toBe(false)
    expect(wrapper.find('[data-panel="apps"]').exists()).toBe(false)
  })

  it('说明区写清四条边界：凭证不回显、不做测试连接、不做回复、不做同步', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('凭证只写不回显')
    expect(note).toContain('测试连接')
    expect(note).toContain('消息回复')
    expect(note).toContain('同步')
    expect(note).toContain('不要贴生产密钥')
  })

  it('后端抹掉凭证列这件事不能被页面绕过：任何面板都不许渲染出 apiKey 的值', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).not.toContain('sk-should-never-arrive')
    await openTab(wrapper, '商品映射')
    await openTab(wrapper, '消息')
    await openTab(wrapper, '库存')
    await openTab(wrapper, 'Webhook 事件')
    await openTab(wrapper, 'ISV 应用')
    expect(wrapper.text()).not.toContain('sk-should-never-arrive')
  })

  it('首屏只加载账号列表，其余五个列表要等切到对应 Tab 才拉', async () => {
    const wrapper = await mountPage()
    expect(api.listAccounts).toHaveBeenCalledTimes(1)
    expect(api.listProducts).not.toHaveBeenCalled()
    expect(api.listMessages).not.toHaveBeenCalled()
    expect(api.listInventory).not.toHaveBeenCalled()
    expect(api.listWebhookEvents).not.toHaveBeenCalled()
    expect(api.listApps).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="accounts"]').exists()).toBe(true)
    expect(wrapper.find('[data-panel="products"]').exists()).toBe(false)
  })

  it('切到商品映射才拉商品，并把平台筛选原样带给后端', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '商品映射')
    expect(api.listProducts).toHaveBeenCalledWith('1', expect.objectContaining({ size: 20 }))
    expect(lastCall(api.listProducts)[1].platform).toBeUndefined()

    await wrapper.find('[data-panel="products"] select').setValue('TEMU')
    expect(lastCall(api.listProducts)[1].platform).toBe('TEMU')
    expect(panelText(wrapper, 'products')).toContain('TP-1001')
    expect(panelText(wrapper, 'products')).toContain('未映射')
    expect(panelText(wrapper, 'products')).toContain('OUT_OF_STOCK')
  })

  it('每个列表都各拉各的端点，切回来不重复请求', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '消息')
    await openTab(wrapper, '库存')
    await openTab(wrapper, 'Webhook 事件')
    await openTab(wrapper, 'ISV 应用')
    expect(api.listMessages).toHaveBeenCalledTimes(1)
    expect(api.listInventory).toHaveBeenCalledTimes(1)
    expect(api.listWebhookEvents).toHaveBeenCalledTimes(1)
    expect(api.listApps).toHaveBeenCalledTimes(1)

    await openTab(wrapper, '平台账号')
    expect(api.listAccounts).toHaveBeenCalledTimes(1)
    await openTab(wrapper, '消息')
    expect(api.listMessages).toHaveBeenCalledTimes(1)
  })

  it('消息与 Webhook 的状态筛选走 status 参数，不是前端挑行', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '消息')
    const msgSelects = wrapper.find('[data-panel="messages"]').findAll('select')
    await msgSelects[1].setValue('UNREAD')
    expect(lastCall(api.listMessages)[1].status).toBe('UNREAD')

    await openTab(wrapper, 'Webhook 事件')
    await wrapper.find('[data-panel="webhook"] select').setValue('FAILED')
    expect(lastCall(api.listWebhookEvents)[1].status).toBe('FAILED')
    expect(panelText(wrapper, 'webhook')).toContain('payload 解析失败')
  })

  it('截断时给出下一页并带上游标；没截断时不出现下一页', async () => {
    vi.mocked(api.listAccounts).mockResolvedValue(ok(ACCOUNTS, paged('v1:41')))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-panel="accounts"] .filter-row').text()).toContain('本页不是全量')
    await clickBtn(wrapper, '下一页')
    expect(lastCall(api.listAccounts)[1]).toEqual(expect.objectContaining({ cursor: 'v1:41' }))
    expect(vi.mocked(api.listAccounts).mock.calls.length).toBe(2)

    const noNext = wrapper
    await openTab(noNext, '商品映射')
    expect(noNext.find('[data-panel="products"]').findAll('button')
      .filter((b: any) => b.text() === '下一页').length).toBe(0)
  })

  it('列表接口返回非 200 时在页面顶部报错，不静默变空表', async () => {
    vi.mocked(api.listAccounts).mockResolvedValue(fail('shop not allowed'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('平台账号')
    expect(wrapper.find('.error-zone').text()).toContain('shop not allowed')
    expect(panelText(wrapper, 'accounts')).toContain('这家店还没有平台账号')
  })

  it('新增账号：店铺从当前上下文带上，状态交给后端固定，凭证列不进请求体除非填了', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新增平台账号')
    const form = wrapper.find('.form-card')
    expect(form.exists()).toBe(true)
    expect(form.text()).toContain('状态由后端固定为 ACTIVE')

    // 未填店铺名时创建按钮必须是禁用的
    expect(wrapper.find('.form-actions .page-btn').attributes('disabled')).toBeDefined()
    const inputs = wrapper.find('.form-card').findAll('input')
    await inputs[0].setValue('Temu EU 店')
    const submit = wrapper.find('.form-actions .page-btn')
    expect(submit.attributes('disabled')).toBeUndefined()
    await submit.trigger('click')
    await flushPromises()

    expect(confirmText(wrapper)).toContain('状态由后端固定为 ACTIVE')
    await clickBtn(wrapper, '确认执行')
    const body = lastCall(api.createAccount)[0]
    expect(body).toEqual(expect.objectContaining({
      shopId: 1, platform: 'TEMU', storeName: 'Temu EU 店'
    }))
    expect('apiKey' in body).toBe(false)
    expect('status' in body).toBe(false)
    expect(api.updateAccount).not.toHaveBeenCalled()
  })

  it('编辑账号：apiKey 留空不能把已有凭证清空，也不该把当前店铺 id 覆盖进去', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'accounts', 0, '编辑')
    const inputs = wrapper.find('.form-card').findAll('input')
    expect(inputs.length).toBe(3)
    await inputs[1].setValue('https://open-api.shein.com')
    await clickBtn(wrapper, '保存修改')
    await clickBtn(wrapper, '确认执行')

    expect(lastCall(api.updateAccount)[0]).toBe(7)
    const body = lastCall(api.updateAccount)[1]
    expect('apiKey' in body).toBe(false)
    expect('shopId' in body).toBe(false)
    expect(body).toEqual(expect.objectContaining({ status: 'ACTIVE', storeName: 'Temu US 旗舰店' }))
  })

  it('编辑时填了 apiKey 才作为覆盖写提交', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'accounts', 0, '编辑')
    const inputs = wrapper.find('.form-card').findAll('input')
    await inputs[2].setValue('sk-rotate-me')
    await clickBtn(wrapper, '保存修改')
    await clickBtn(wrapper, '确认执行')
    expect(lastCall(api.updateAccount)[1]).toEqual(expect.objectContaining({ apiKey: 'sk-rotate-me' }))
  })

  it('删除账号是物理删除：确认文案说明后果，返回 false 时不能装作成功', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'accounts', 0, '删除')
    const text = confirmText(wrapper)
    expect(text).toContain('物理删除')
    expect(text).toContain('没有回收站')
    await clickBtn(wrapper, '确认执行')
    expect(api.deleteAccount).toHaveBeenCalledWith(7)
    expect(vi.mocked(api.listAccounts).mock.calls.length).toBe(2)

    vi.mocked(api.deleteAccount).mockResolvedValue(ok(false))
    await rowBtn(wrapper, 'accounts', 0, '删除')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('后端没有删掉任何行')
  })

  it('商品映射：确认文案要给出后端会做的大写归一，提交的是原值', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '商品映射')
    await rowBtn(wrapper, 'products', 1, '映射')
    const inputs = wrapper.find('.form-card').findAll('input')
    await inputs[0].setValue('b0xyz98765')
    await clickBtn(wrapper, '提交映射')
    expect(confirmText(wrapper)).toContain('B0XYZ98765')
    expect(confirmText(wrapper)).toContain('不会把商品推送到任何平台')
    await clickBtn(wrapper, '确认执行')
    expect(api.mapProduct).toHaveBeenCalledWith(32, 'b0xyz98765', '')
  })

  it('消息分配：处理人为空不发请求，成功后的列表刷新失败要看得见', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '消息')
    await rowBtn(wrapper, 'messages', 0, '分配处理人')
    expect(confirmText(wrapper)).toContain('不会通知买家或平台')

    await wrapper.find('.modal input').setValue('   ')
    await clickBtn(wrapper, '确认执行')
    expect(api.assignMessage).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('处理人不能为空')

    vi.mocked(api.listMessages).mockResolvedValue(fail('消息列表挂了'))
    await rowBtn(wrapper, 'messages', 0, '分配处理人')
    await wrapper.find('.modal input').setValue('客服乙')
    await clickBtn(wrapper, '确认执行')
    expect(api.assignMessage).toHaveBeenCalledWith(41, '客服乙')
    // 分配本身成功了，随后的列表刷新失败必须同时可见
    expect(wrapper.find('.error-zone').text()).toContain('消息列表挂了')
  })

  it('库存页：汇总读的是本地表行数求和，时刻要标成计算时刻而不是快照时间', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '库存')
    expect(api.aggregatedInventory).not.toHaveBeenCalled()
    await clickBtn(wrapper, '按平台+SKU 汇总')
    expect(api.aggregatedInventory).toHaveBeenCalledWith('1')
    const panel = panelText(wrapper, 'inventory')
    expect(panel).toContain('本次聚合时刻')
    expect(panel).toContain('2026-10-02 19:00:00')
    expect(panel).toContain('不是平台侧库存快照时间')
    expect(panel).toContain('SKU-A')
    expect(wrapper.find('.metric-grid').text()).toContain('15')
    expect(wrapper.find('.metric-grid').text()).toContain('2')
  })

  it('注册 ISV 应用：一次性密钥卡片只在本页存在，确认后要能关掉', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, 'ISV 应用')
    await clickBtn(wrapper, '注册新应用')
    const inputs = wrapper.find('.form-card').findAll('input')
    expect(inputs.length).toBe(4)
    await inputs[0].setValue('ERP 自建')
    await clickBtn(wrapper, '注册')
    expect(confirmText(wrapper)).toContain('后端只保存其 SHA-256')
    await clickBtn(wrapper, '确认执行')

    expect(lastCall(api.registerApp)[0]).toEqual(expect.objectContaining({
      appName: 'ERP 自建', ownerShopId: 1, rateLimitRpm: 60
    }))
    const card = wrapper.find('.secret-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('ak-new')
    expect(card.text()).toContain('sk-new-secret-please-copy')
    expect(card.text()).toContain('刷新或切换标签后就再也取不到')

    await clickBtn(wrapper, '我已保存')
    expect(wrapper.find('.secret-card').exists()).toBe(false)
  })

  it('轮换密钥要说清旧密钥立刻失效，并且不能把新密钥写进列表缓存', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, 'ISV 应用')
    await rowBtn(wrapper, 'apps', 0, '轮换密钥')
    expect(confirmText(wrapper)).toContain('旧密钥立刻失效')
    await clickBtn(wrapper, '确认执行')
    expect(api.rotateAppSecret).toHaveBeenCalledWith(71)
    expect(wrapper.find('.secret-card').text()).toContain('sk-new-secret-please-copy')
    // 列表刷新用的是后端的应用行，不能把明文密钥塞进去
    expect(vi.mocked(api.listApps).mock.calls.length).toBe(2)
    expect(JSON.stringify(lastCall(api.listApps))).not.toContain('sk-new-secret')
  })

  it('注册失败时不出现密钥卡片，也不把列表刷新当成成功', async () => {
    vi.mocked(api.registerApp).mockResolvedValue(fail('owner_shop_id 不能为空'))
    const wrapper = await mountPage()
    await openTab(wrapper, 'ISV 应用')
    await clickBtn(wrapper, '注册新应用')
    await wrapper.find('.form-card input').setValue('ERP 自建')
    await clickBtn(wrapper, '注册')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.secret-card').exists()).toBe(false)
    expect(wrapper.find('.error-zone').text()).toContain('owner_shop_id 不能为空')
    expect(vi.mocked(api.listApps).mock.calls.length).toBe(1)
  })

  it('三个后端有、语义未定的入口在页面上必须不存在', async () => {
    const wrapper = await mountPage()
    for (const label of ['测试连接', '回复', '同步商品', '同步消息', '同步库存', '查看密钥']) {
      expect(wrapper.findAll('button').filter((b: any) => b.text().includes(label)).length,
        `页面出现了「${label}」按钮`).toBe(0)
    }
    await openTab(wrapper, '商品映射')
    await openTab(wrapper, '消息')
    await openTab(wrapper, '库存')
    await openTab(wrapper, 'ISV 应用')
    for (const label of ['测试连接', '回复', '同步', '查看密钥']) {
      expect(wrapper.findAll('button').filter((b: any) => b.text().includes(label)).length,
        `页面出现了「${label}」按钮`).toBe(0)
    }
  })

  it('空列表要说清为什么空：凭证缺失 / 从没同步过，而不是「没有数据」', async () => {
    vi.mocked(api.listAccounts).mockResolvedValue(ok([]))
    vi.mocked(api.listProducts).mockResolvedValue(ok([]))
    vi.mocked(api.listWebhookEvents).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    expect(panelText(wrapper, 'accounts')).toContain('凭证缺失不等于平台没有数据')
    await openTab(wrapper, '商品映射')
    expect(panelText(wrapper, 'products')).toContain('从没导入过')
    await openTab(wrapper, 'Webhook 事件')
    expect(panelText(wrapper, 'webhook')).toContain('不会改订单/库存/消息')
  })
})
