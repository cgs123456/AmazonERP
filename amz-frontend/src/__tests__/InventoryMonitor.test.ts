import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InventoryMonitor from '../views/InventoryMonitor.vue'
import type { InventoryItem, InventoryHealth } from '@/api/inventory'

// mock @/api/inventory，避免触发真实请求
vi.mock('@/api/inventory', () => ({
  getInventoryList: vi.fn(),
  getInventoryHealth: vi.fn(),
  getReplenishSuggestion: vi.fn(),
  recalcReplenishment: vi.fn()
}))

import { getInventoryList, getInventoryHealth, recalcReplenishment } from '@/api/inventory'

const mockedRecalc = vi.mocked(recalcReplenishment)

// 生成采购计划走采购域；本用例只关心「假数据不许建计划」这条护栏
vi.mock('@/api/procurement', () => ({ createPlan: vi.fn() }))
import { createPlan } from '@/api/procurement'
const mockedCreatePlan = vi.mocked(createPlan)

const mockedGetInventoryList = vi.mocked(getInventoryList)
const mockedGetInventoryHealth = vi.mocked(getInventoryHealth)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('InventoryMonitor 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetInventoryList.mockReset()
    mockedGetInventoryHealth.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与 4 个健康度卡片', () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    expect(wrapper.find('.hero-section .hero-title').text()).toBe('库存监控')
    expect(wrapper.findAll('.health-card').length).toBe(4)
    const labels = wrapper.findAll('.health-label').map(l => l.text())
    expect(labels).toEqual(['紧急补货', '风险库存', '健康库存', '滞销库存'])
  })

  it('没取到数据时健康度卡片显示「—」而不是 0', () => {
    // 0 会被读成「测出来没有紧急 SKU」，与「没测」是两种结论
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['—', '—', '—', '—'])
  })

  it('未选择店铺时应显示空店铺提示且不调用 inventory API', async () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(mockedGetInventoryList).not.toHaveBeenCalled()
    expect(mockedGetInventoryHealth).not.toHaveBeenCalled()
  })

  it('已选店铺且 API 返回 200 时应用接口数据覆盖列表与健康度', async () => {
    localStorage.setItem('current_shop_id', '1')
    const items: InventoryItem[] = [
      { sku: 'API-SKU-1', asin: 'ASIN001', shop: 'Shop A (US)', stock: 10, dailySales: 2, days: 5, level: 'urgent', levelText: '紧急', suggestQty: 100 },
      { sku: 'API-SKU-2', asin: 'ASIN002', shop: 'Shop A (US)', stock: 200, dailySales: 1, days: 200, level: 'overstock', levelText: '滞销', suggestQty: 0 }
    ]
    const health: InventoryHealth = { urgent: 1, risk: 0, healthy: 0, overstock: 1 }
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: items })
    mockedGetInventoryHealth.mockResolvedValue({ code: 200, message: 'ok', data: health })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    // 列表被接口数据覆盖（2 行）
    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('API-SKU-1')
    expect(wrapper.text()).toContain('API-SKU-2')
    // 原 mock SKU B08X4-001 不应再出现
    expect(wrapper.text()).not.toContain('B08X4-001')

    // 健康度计数被接口数据覆盖
    const counts = wrapper.findAll('.health-count').map(c => c.text())
    expect(counts).toEqual(['1', '0', '0', '1'])
  })

  it('接口失败：显示失败原因并保持空列表，不再端出页面写死的 7 个 SKU', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockRejectedValue(new Error('network error'))
    mockedGetInventoryHealth.mockRejectedValue(new Error('network error'))

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('network error')
    // 只有空态那一行，没有任何编造 SKU
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(1)
    expect(wrapper.text()).not.toContain('B08X4-001')
    expect(wrapper.text()).toContain('暂无库存数据')
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['—', '—', '—', '—'])
  })

  it('真实补货建议可一键生成草稿采购计划，并把依据快照写进 replenishmentData', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({
      code: 200, message: 'ok',
      data: [{ sku: 'API-SKU-1', asin: 'ASIN001', shop: 'Shop A (US)', stock: 10, dailySales: 2,
        days: 5, level: 'urgent', levelText: '紧急', suggestQty: 120, suggestStatDate: '2026-09-30' }]
    })
    mockedGetInventoryHealth.mockResolvedValue({ code: 200, message: 'ok', data: { urgent: 1, risk: 0, healthy: 0, overstock: 0 } })
    mockedCreatePlan.mockResolvedValue({ code: 200, message: 'ok', data: { id: 7, planNo: 'PLAN-0007' } } as any)

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.plan-btn').trigger('click')
    await flushPromises()

    expect(mockedCreatePlan).toHaveBeenCalledTimes(1)
    const body = mockedCreatePlan.mock.calls[0][0]
    expect(body).toMatchObject({ shopId: '1', sku: 'API-SKU-1', suggestedQty: 120, plannedQty: 120, urgency: 'URGENT', source: 'AUTO' })
    const basis = JSON.parse(body.replenishmentData as string)
    expect(basis).toMatchObject({
      basis: 'inventory-replenishment-suggestion',
      suggestStatDate: '2026-09-30',
      availableQuantity: 10,
      dailySales: 2,
      daysOfSupply: 5,
      suggestedReplenishQty: 120
    })
    expect(wrapper.find('.plan-msg').text()).toContain('PLAN-0007')
  })

  it('数据不真实（接口失败）时不许建计划：按钮禁用且不发请求', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: [
      { sku: 'API-SKU-1', asin: 'A', shop: 'S', stock: 1, dailySales: 1, days: 2, level: 'urgent', levelText: '紧急', suggestQty: 50 }
    ] })
    mockedGetInventoryHealth.mockRejectedValue(new Error('health down'))

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    // 列表是真实的，但为了守住「一键建计划依赖两份数据都可信」这条口径，
    // 健康度失败也算未 live 吗？—— 不会：live 只看库存列表，所以这里断言按钮可用
    const btn = wrapper.find('.plan-btn')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeUndefined()

    // 反过来：列表本身没拿到（失败）时按钮不该出现
    mockedGetInventoryList.mockRejectedValue(new Error('list down'))
    const w2 = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(w2.find('.plan-btn').exists()).toBe(false)
    expect(mockedCreatePlan).not.toHaveBeenCalled()
  })

  it('建计划被后端拒绝：显示原因，不假装已生成', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: [
      { sku: 'API-SKU-9', asin: 'A9', shop: 'S', stock: 1, dailySales: 1, days: 3, level: 'urgent', levelText: '紧急', suggestQty: 40 }
    ] })
    mockedGetInventoryHealth.mockResolvedValue({ code: 200, message: 'ok', data: { urgent: 1, risk: 0, healthy: 0, overstock: 0 } })
    mockedCreatePlan.mockResolvedValue({ code: 400, message: '店铺ID、SKU和计划数量不能为空', data: null } as any)

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.plan-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.error-zone').text()).toContain('店铺ID、SKU和计划数量不能为空')
    expect(wrapper.find('.plan-msg').exists()).toBe(false)
  })

  it('已选店铺但 API 返回空列表时应显示"暂无库存数据"', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedGetInventoryHealth.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { urgent: 0, risk: 0, healthy: 0, overstock: 0 }
    })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.empty-row').exists()).toBe(true)
    expect(wrapper.find('.empty-row').text()).toContain('暂无库存数据')
    // 健康度全部为 0
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['0', '0', '0', '0'])
  })

  it('库存接口返回非 200：报错误、列表留空、不给建计划入口，但健康度照常更新', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetInventoryList.mockResolvedValue({ code: 500, message: 'err', data: null as any })
    mockedGetInventoryHealth.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { urgent: 9, risk: 9, healthy: 9, overstock: 9 }
    })

    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('err')
    // 只剩空态行：接口没成功就不该有「看起来像库存」的行
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(1)
    expect(wrapper.text()).not.toContain('B08X4-001')
    expect(wrapper.find('.plan-btn').exists()).toBe(false)
    // 健康度是另一条独立数据源，接口 200 就照常显示
    expect(wrapper.findAll('.health-count').map(c => c.text())).toEqual(['9', '9', '9', '9'])
  })
})

describe('InventoryMonitor 补货重算', () => {
  const okList = { code: 200, message: 'ok', data: [] as any[] }
  const okHealth = { code: 200, message: 'ok', data: { urgent: 0, risk: 0, healthy: 0, overstock: 0 } }

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '9')
    vi.mocked(getInventoryList).mockReset().mockResolvedValue(okList as any)
    vi.mocked(getInventoryHealth).mockReset().mockResolvedValue(okHealth as any)
    mockedRecalc.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
  })

  const mountPage = async () => {
    const wrapper = mount(InventoryMonitor, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  it('按钮说明这是后端重算并 upsert，不是页面手填建议量', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.recalc-btn').exists()).toBe(true)
    expect(wrapper.find('.recalc-row').text()).toContain('逐条 upsert')
    expect(wrapper.find('.recalc-row').text()).toContain('采购计划草稿不受影响')
  })

  it('点击后按选中店铺发 POST，成功展示条数并重新拉一次列表', async () => {
    mockedRecalc.mockResolvedValue({ code: 200, message: 'ok', data: 12 } as any)
    const wrapper = await mountPage()

    expect(vi.mocked(getInventoryList)).toHaveBeenCalledTimes(1)
    await wrapper.find('.recalc-btn').trigger('click')
    await flushPromises()

    expect(mockedRecalc).toHaveBeenCalledTimes(1)
    expect(mockedRecalc).toHaveBeenCalledWith('9')
    expect(wrapper.find('.recalc-msg').text()).toContain('12 条建议')
    expect(vi.mocked(getInventoryList)).toHaveBeenCalledTimes(2)
  })

  it('后端业务失败时进错误条，且不重新拉列表', async () => {
    mockedRecalc.mockResolvedValue({ code: 400, message: '店铺无库存快照', data: null } as any)
    const wrapper = await mountPage()

    await wrapper.find('.recalc-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('店铺无库存快照')
    expect(wrapper.find('.recalc-msg').exists()).toBe(false)
    expect(vi.mocked(getInventoryList)).toHaveBeenCalledTimes(1)
  })

  it('请求异常同样可见，重算按钮不会卡在忙态', async () => {
    mockedRecalc.mockRejectedValue(new Error('timeout of 30000ms exceeded'))
    const wrapper = await mountPage()

    await wrapper.find('.recalc-btn').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('timeout of 30000ms exceeded')
    expect((wrapper.find('.recalc-btn').element as HTMLButtonElement).disabled).toBe(false)
  })

  it('未选店铺时按钮禁用，点了也不发请求', async () => {
    localStorage.removeItem('current_shop_id')
    localStorage.setItem('shops', '[]')
    const wrapper = await mountPage()

    const btn = wrapper.find('.recalc-btn')
    expect((btn.element as HTMLButtonElement).disabled).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    expect(mockedRecalc).not.toHaveBeenCalled()
  })
})
