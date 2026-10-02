import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import WarehouseAlerts from '../views/WarehouseAlerts.vue'

vi.mock('@/api/warehouseAlerts', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/warehouseAlerts')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})
vi.mock('@/api/warehouse', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/warehouse')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/warehouseAlerts'
import { listWarehouses } from '@/api/warehouse'
import { ALERT_TYPES } from '@/api/warehouseAlerts'

const SHOP = '900000000000001000'
const ok = <T>(data: T, page?: unknown) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page ?? null }) as any
const pageOf = (nextCursor: string | null, truncated = nextCursor !== null) =>
  ({ size: 50, returned: 1, hasMore: truncated, truncated, nextCursor, total: null })

const WAREHOUSES = ok([
  { id: 5, shopId: 1, warehouseName: '洛杉矶仓', warehouseType: 'OVERSEAS' },
  { id: 6, shopId: 1, warehouseName: '深圳仓', warehouseType: 'DOMESTIC' }
])
const STOCK = ok([
  { id: 71, shopId: 1, warehouseId: 5, warehouseName: '洛杉矶仓', warehouseType: 'OVERSEAS',
    sku: 'SKU-WH-01', asin: 'B0WH01', availableQty: 3, reservedQty: 1, inboundQty: 0,
    transferOutQty: 0, totalQty: 4, unitCost: 12.5, totalValue: 50.0,
    lastInboundDate: '2026-08-01', daysInStock: 62, snapshotTime: '2026-10-01T03:00:00' },
  { id: 72, shopId: 1, warehouseId: 6, warehouseName: '深圳仓', warehouseType: 'DOMESTIC',
    sku: 'SKU-WH-02', asin: null, availableQty: 0, reservedQty: null, inboundQty: null,
    transferOutQty: null, totalQty: null, unitCost: null, totalValue: null,
    lastInboundDate: null, daysInStock: null, snapshotTime: null }
], pageOf(null))
const RULES = ok([
  { id: 81, shopId: 1, sku: 'SKU-WH-01', warehouseId: 5, alertType: 'LOW_STOCK', thresholdValue: 5,
    thresholdUnit: 'QTY', alertLevel: 'CRITICAL', notifyChannels: 'EMAIL', enabled: true, description: '低库存' },
  { id: 82, shopId: 1, sku: null, warehouseId: null, alertType: 'DAMAGE_RISK', thresholdValue: 30,
    thresholdUnit: 'DAYS', alertLevel: 'WARNING', notifyChannels: null, enabled: 0, description: null }
])
const REPORT = {
  shopId: 1, alertRulesChecked: 2, totalTriggered: 1, critical: 1, warning: 0, info: 0,
  alerts: [{ alertId: 81, alertType: 'LOW_STOCK', alertLevel: 'CRITICAL', description: '低库存',
    sku: 'SKU-WH-01', warehouseName: '洛杉矶仓', availableQty: 3, daysInStock: 62, totalValue: 50.0 }],
  stocksTruncated: false, scannedStockCount: 2
}

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(listWarehouses).mockResolvedValue(WAREHOUSES)
  vi.mocked(api.listStock).mockResolvedValue(STOCK)
  vi.mocked(api.listAlerts).mockResolvedValue(RULES)
  vi.mocked(api.createAlert).mockResolvedValue(ok({ id: 83 }))
  vi.mocked(api.toggleAlert).mockResolvedValue(ok(true))
  vi.mocked(api.checkAlerts).mockResolvedValue(ok({ ...REPORT }))
}

const mountPage = async () => {
  const wrapper = mount(WarehouseAlerts, { global: stubs })
  await flushPromises()
  return wrapper
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.tab').find((b: any) => b.text() === label)
  expect(btn, `分区按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

const rowBtn = async (wrapper: any, panel: string, rowIndex: number, label: string) => {
  const rows = wrapper.find(panel).findAll('tbody tr')
  expect(rows.length, `分区 ${panel} 没有第 ${rowIndex + 1} 行`).toBeGreaterThan(rowIndex)
  const btn = rows[rowIndex].findAll('button').find((b: any) => b.text() === label)
  expect(btn, `第 ${rowIndex + 1} 行没有按钮「${label}」`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('WarehouseAlerts 视图（海外仓库存与预警）', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', SHOP)
    vi.clearAllMocks()
    happy()
  })

  it('未选店铺时不请求任何端点', async () => {
    localStorage.clear()
    const wrapper = await mountPage()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(listWarehouses).not.toHaveBeenCalled()
    expect(api.listStock).not.toHaveBeenCalled()
  })

  it('挂载只拉仓库与库存快照，预警规则要进分区才请求', async () => {
    const wrapper = await mountPage()
    expect(wrapper.findAll('.tab').length).toBe(3)
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(api.listStock).toHaveBeenCalledTimes(1)
    expect(api.listAlerts).not.toHaveBeenCalled()

    await openTab(wrapper, '预警规则')
    expect(wrapper.find('[data-panel="alert"]').exists()).toBe(true)
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(api.listAlerts).toHaveBeenCalledTimes(1)

    await openTab(wrapper, '检查结果')
    expect(wrapper.find('[data-panel="check"]').exists()).toBe(true)
    expect(api.checkAlerts).not.toHaveBeenCalled()
  })

  it('四条判读边界常驻页面：不手填库存、只有 5 类被判定、DAYS 的真实语义、检查只读', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('不提供手工录入库存')
    expect(note).toContain('永远不会触发')
    expect(note).toContain('在库天数 ≤ 阈值')
    expect(note).toContain('只做一次判定并返回结果')
    expect(note).toContain('500 行上限')
    expect(ALERT_TYPES).toEqual(['LOW_STOCK', 'STOCKOUT', 'OVERSTOCK', 'AGING', 'NO_MOVEMENT'])
  })

  it('库存快照显示真实列，可用为 0 的行标红，空态说明快照由海外仓回传', async () => {
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="stock"]')
    const rows = panel.findAll('tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('SKU-WH-01')
    expect(rows[0].text()).toContain('洛杉矶仓')
    expect(rows[0].text()).toContain('62')
    expect(rows[0].findAll('.neg').length).toBe(0)
    // 第二行 availableQty=0 → 标红；null 字段显示占位而不是编 0
    expect(rows[1].findAll('.neg').length).toBeGreaterThan(0)
    expect(rows[1].text()).toContain('-')

    vi.mocked(api.listStock).mockResolvedValue(ok([]))
    const empty = await mountPage()
    expect(empty.find('[data-panel="stock"] .empty-row').text()).toContain('快照由海外仓回传写入')
    expect(empty.find('[data-panel="stock"]').text()).not.toContain('SKU-WH-01')
  })

  it('服务端截断时给下一页入口并回传 cursor', async () => {
    vi.mocked(api.listStock)
      .mockResolvedValueOnce(ok([STOCK.data[0]], pageOf('v1:71')))
      .mockResolvedValue(ok([STOCK.data[1]], pageOf(null)))
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="stock"]')
    expect(panel.text()).toContain('后端标记仍有下一页')
    await clickBtn(panel, '加载下一页')
    const calls = vi.mocked(api.listStock).mock.calls as any[]
    expect(calls[1][1].cursor).toBe('v1:71')
    expect(wrapper.find('[data-panel="stock"]').findAll('tbody tr').length).toBe(2)
  })

  it('预警规则把不会被判定的类型与只存不读的渠道标出来', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '预警规则')
    const panel = wrapper.find('[data-panel="alert"]')
    const rows = panel.findAll('tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('LOW_STOCK')
    expect(rows[0].text()).not.toContain('不会被判定')
    expect(rows[0].text()).toContain('EMAIL（只存不读）')
    expect(rows[1].text()).toContain('（不会被判定）')
    expect(rows[1].text()).toContain('全部')
    expect(rows[1].text()).toContain('停用')
  })

  it('新建规则：阈值非法时不发请求，合法时空串转 null 且按启用状态提交', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '预警规则')
    await clickBtn(wrapper, '新建规则')
    await wrapper.find('.modal').findAll('input')[0].setValue('-3')
    await clickBtn(wrapper, '提交')
    expect(api.createAlert).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('阈值必须是不小于 0 的数字')
    expect(wrapper.find('.modal-mask').exists()).toBe(true)

    await wrapper.find('.modal').findAll('input')[0].setValue('5')
    await clickBtn(wrapper, '提交')
    expect(api.createAlert).toHaveBeenCalledTimes(1)
    const body = vi.mocked(api.createAlert).mock.calls[0][0] as any
    expect(body.shopId).toBe(SHOP)
    expect(body.thresholdValue).toBe(5)
    expect(body.alertType).toBe('LOW_STOCK')
    expect(body.enabled).toBe(true)
    expect(body.sku).toBeNull()
    expect(body.warehouseId).toBeNull()
    expect(body.notifyChannels).toBeNull()
    expect(api.listAlerts).toHaveBeenCalledTimes(2)
  })

  it('规则启停以后端为准：提交后重新拉列表', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '预警规则')
    await rowBtn(wrapper, '[data-panel="alert"]', 0, '停用')
    expect(api.toggleAlert).toHaveBeenCalledWith(81, false)
    expect(api.listAlerts).toHaveBeenCalledTimes(2)
  })

  it('立即检查要二次确认，取消时一次请求都不发', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '预警规则')
    await clickBtn(wrapper, '立即检查')
    expect(wrapper.find('.confirm-detail').text()).toContain('只读')
    expect(wrapper.find('.confirm-detail').text()).toContain('上限 500 行')
    expect(api.checkAlerts).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(api.checkAlerts).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="check"]').exists()).toBe(false)
  })

  it('确认检查后切到结果分区，按后端计数与命中行渲染', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '预警规则')
    await clickBtn(wrapper, '立即检查')
    await clickBtn(wrapper, '确认执行')
    expect(api.checkAlerts).toHaveBeenCalledWith(SHOP)
    const panel = wrapper.find('[data-panel="check"]')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('规则 2 条')
    expect(panel.text()).toContain('触发 1 条')
    expect(panel.text()).toContain('CRITICAL 1')
    expect(panel.text()).toContain('SKU-WH-01')
    expect(panel.text()).toContain('本次扫描库存 2 行')
    expect(panel.text()).not.toContain('不是全量')
  })

  it('扫描被截断时必须说明结论不是全量；无命中时说是真实结果', async () => {
    vi.mocked(api.checkAlerts).mockResolvedValue(ok({ ...REPORT, stocksTruncated: true }))
    const wrapper = await mountPage()
    await openTab(wrapper, '检查结果')
    await clickBtn(wrapper, '立即检查')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('[data-panel="check"] .advisory').text()).toContain('不是全量')

    vi.mocked(api.checkAlerts).mockResolvedValue(ok({
      ...REPORT, alerts: [], totalTriggered: 0, critical: 0, stocksTruncated: false
    }))
    const w2 = await mountPage()
    await openTab(w2, '预警规则')
    await clickBtn(w2, '立即检查')
    await clickBtn(w2, '确认执行')
    expect(w2.find('[data-panel="check"]').text()).toContain('这是本次判定的真实结果')
  })

  it('后端非 200 与网络异常都进错误条，异常响应不补出假行', async () => {
    vi.mocked(api.listStock).mockResolvedValue({ code: 500, message: '海外仓服务不可用', data: null } as any)
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('库存快照：海外仓服务不可用')
    expect(wrapper.find('[data-panel="stock"]').findAll('tbody tr')[0].text()).toContain('快照由海外仓回传写入')

    vi.mocked(api.listAlerts).mockRejectedValue(new Error('socket hang up'))
    await openTab(wrapper, '预警规则')
    expect(wrapper.find('.error-zone').text()).toContain('socket hang up')
    expect(wrapper.find('[data-panel="alert"]').text()).toContain('该店铺还没有预警规则')
  })
})
