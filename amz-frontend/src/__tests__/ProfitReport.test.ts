import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProfitReport from '../views/ProfitReport.vue'
import type { ProfitRow, ProfitSummary } from '@/api/profit'

// mock @/api/profit，避免触发真实请求。mapMonthlyRows 用真实实现（它就是被测对象的一部分），
// 其余接口函数是 vi.fn()。
vi.mock('@/api/profit', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/profit')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' && k !== 'mapMonthlyRows' ? vi.fn() : v
  })
  return out
})

import { getMonthlyProfitSummary, getProfitReport, listProfitByOrder, listProfitBySku } from '@/api/profit'

const mockedGetProfitReport = vi.mocked(getProfitReport)
const mockedMonthly = vi.mocked(getMonthlyProfitSummary)
const mockedByOrder = vi.mocked(listProfitByOrder)
const mockedBySku = vi.mocked(listProfitBySku)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

describe('ProfitReport 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    mockedGetProfitReport.mockReset()
    mockedMonthly.mockReset()
    mockedByOrder.mockReset()
    mockedBySku.mockReset()
    // 下钻与月度默认「成功但没数据」，各用例按需覆盖；
    // 不这么设的话未 stub 的调用会返回 undefined，页面把它当后端返回非 200 报错——
    // 那条错误会被误读成页面缺陷，而不是测试没铺数据。
    mockedMonthly.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedByOrder.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedBySku.mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('应渲染页面标题与汇总卡片', () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    expect(wrapper.find('.hero-section .hero-title').text()).toBe('利润报表')
    // 4 个汇总卡片
    const cards = wrapper.findAll('.summary-card')
    expect(cards.length).toBe(4)
    // 降级 mock 汇总数据应被渲染
    expect(wrapper.text()).toContain('$12,456.80')
  })

  it('未选择店铺时应显示空店铺提示且不调用 getProfitReport', async () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(mockedGetProfitReport).not.toHaveBeenCalled()
  })

  it('已选店铺且 API 返回 200 时应用接口数据覆盖汇总与 SKU 行', async () => {
    localStorage.setItem('current_shop_id', '1')
    const summary: ProfitSummary = {
      totalRevenue: '$99,999.00',
      totalCost: '$11,111.00',
      grossProfit: '$88,888.00',
      grossMargin: '88.8%'
    }
    const rows: ProfitRow[] = [
      { name: 'API-SKU-1', revenue: '$1,000', cost: '$500', platformFee: '$100', adFee: '$50', shipping: '$10', profit: 340, margin: 34.0 }
    ]
    mockedGetProfitReport.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { summary, rows }
    })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 汇总区被接口数据覆盖
    expect(wrapper.text()).toContain('$99,999.00')
    expect(wrapper.text()).toContain('88.8%')
    // SKU 行被接口数据覆盖，表格第一行显示 API-SKU-1
    expect(wrapper.text()).toContain('API-SKU-1')
    // 原 mock SKU 数据 B08X4-001 不应再出现（已被 rows 覆盖）
    expect(wrapper.text()).not.toContain('B08X4-001')
  })

  it('API 抛异常时降级到 mock 数据，但失败原因必须留在页面上', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockRejectedValue(new Error('network error'))

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级 mock 汇总仍存在
    expect(wrapper.text()).toContain('$12,456.80')
    // 降级 mock SKU 行存在
    expect(wrapper.text()).toContain('B08X4-001')
    expect(wrapper.find('.loading-mask').exists()).toBe(false)
    // 原来这里只有 console.warn：示例数据带着「示例」徽标，但「为什么是示例」看不见，
    // 看的人只能开 devtools 才知道后端根本没答上
    expect(wrapper.find('.error-zone').text()).toContain('利润报表')
    expect(wrapper.find('.error-zone').text()).toContain('network error')
  })

  it('不再有「按店铺」维度：后端没有按店铺聚合利润的端点，就不摆一个填不上的 Tab', async () => {
    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 默认 SKU 维度仍然在
    expect(wrapper.text()).toContain('B08X4-001')
    const tabs = wrapper.findAll('.dim-tab').map(t => t.text())
    expect(tabs.some(t => t.includes('按店铺'))).toBe(false)
    expect(wrapper.find('.notice-zone').text()).toContain('本页不提供「按店铺」维度')
  })

  it('统计区间看得见也改得动：请求带的就是页面上那一段日期', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 此前这两个日期写死在 onMounted 里，页面上既看不到也改不了
    expect(mockedGetProfitReport).toHaveBeenCalledWith('1', '2026-06-01', '2026-06-30')
    expect(wrapper.find('.window-row').text()).toContain('2026-06-01')

    const inputs = wrapper.find('.window-row').findAll('input')
    await inputs[0].setValue('2026-09-01')
    await inputs[1].setValue('2026-09-30')
    await wrapper.find('.window-row .action-btn').trigger('click')
    await flushPromises()

    expect(mockedGetProfitReport).toHaveBeenLastCalledWith('1', '2026-09-01', '2026-09-30')
    expect(wrapper.find('.summary-label').text()).toContain('2026-09-01 ~ 2026-09-30')
  })

  it('「按 SKU×月」读后端 SQL 聚合：切过去才查，缺的费用列写 — 而不是补 0', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)
    mockedMonthly.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { shop_id: 1, sku: 'SKU-A', month: '2026-06', total_revenue: 1000, total_cost: 600, total_profit: 200, margin: 0.2 },
        { shop_id: 1, sku: 'SKU-B', month: '2026-05', total_revenue: 500, total_cost: 500, total_profit: -10, margin: -0.02 }
      ]
    } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()
    expect(mockedMonthly).not.toHaveBeenCalled()

    const monthTab = wrapper.findAll('.dim-tab').find(t => t.text().includes('按 SKU\u00d7月'))!
    await monthTab.trigger('click')
    await flushPromises()

    expect(mockedMonthly).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('SKU-A \u00b7 2026-06')
    // 表格把毛利率按数值打印（20 就是 20%），断言跟着实现而不是跟着小数位想象
    expect(wrapper.text()).toContain('20%')
    // 聚合里没有平台费/广告费/头程运费的分项，必须留白而不是补 0
    expect(wrapper.text()).toContain('\u2014')
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('月度汇总报业务错误时不能显示成「这个店铺没有月度数据」', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)
    mockedMonthly.mockResolvedValue({ code: 500, message: '聚合查询超时', data: null } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()
    const monthTab = wrapper.findAll('.dim-tab').find(t => t.text().includes('按 SKU\u00d7月'))!
    await monthTab.trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('月度汇总')
    expect(wrapper.find('.error-zone').text()).toContain('聚合查询超时')
  })

  it('已选店铺但 API 返回非 200 时应保留降级数据', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({ code: 500, message: 'err', data: null as any })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 降级 mock 数据仍在
    expect(wrapper.text()).toContain('$12,456.80')
    expect(wrapper.text()).toContain('B08X4-001')
  })

  it('降级 mock 展示时应挂示例标识', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockRejectedValue(new Error('network error'))

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.mock-badge').exists()).toBe(true)
    expect(wrapper.find('.mock-badge').text()).toBe('示例数据')
  })

  it('200 后切到月度维度查聚合；聚合为空时说清是这个店铺还没有聚合行', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' },
        rows: [
          { name: 'API-SKU-1', revenue: '$1.00', cost: '$0.50', platformFee: '$0', adFee: '$0', shipping: '$0', profit: 0.5, margin: 50.0 }
        ]
      }
    })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    // 真实数据到手后不能再出现示例店铺行（旧版把 mock 的 Shop A 留在维度里）
    expect(wrapper.text()).not.toContain('Shop A (US)')

    const monthTab = wrapper.findAll('.dim-tab').find(t => t.text().includes('按 SKU\u00d7月'))!
    await monthTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('这个店铺还没有月度聚合行')
    expect(wrapper.text()).not.toContain('暂无利润数据')
  })

  it('下钻：按订单号查利润，费用分项照实渲染，dataComplete 决定这一行能不能当依据', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)
    mockedByOrder.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        {
          id: 901, shopId: 1, amazonOrderId: '111-2222222-3333333', sku: 'SKU-A', statDate: '2026-06-12',
          revenue: 100, productCost: 40, referralFee: 15, adCost: 10,
          fbaFulfillmentFee: 300, fbaStorageFee: 50, vat: 5, netProfit: 20, netMargin: 0.2, dataComplete: true
        },
        {
          id: 900, shopId: 1, amazonOrderId: '111-2222222-3333333', sku: 'SKU-B', statDate: '2026-06-11',
          revenue: 50, productCost: null, referralFee: 7, adCost: null,
          fbaFulfillmentFee: null, fbaStorageFee: null, vat: null, netProfit: -3, netMargin: -0.06, dataComplete: false
        }
      ],
      _page: { size: 20, returned: 2, hasMore: false, truncated: false, nextCursor: null, total: null }
    } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    const panel = wrapper.find('[data-panel="drill"]')
    await panel.find('input').setValue('111-2222222-3333333')
    await panel.findAll('.action-btn')[0].trigger('click')
    await flushPromises()

    expect(mockedByOrder).toHaveBeenCalledWith('1', '111-2222222-3333333', expect.objectContaining({ size: 20 }))
    expect(panel.text()).toContain('2026-06-12')
    expect(panel.text()).toContain('$100.00')
    // FBA 两列合并就是这一行的实际数字（300 + 50）
    expect(panel.text()).toContain('$350.00')
    // 负数把符号放在币种前面，不要写成 $-3.00
    expect(panel.text()).toContain('-$3.00')
    // 成本没取到的项必须留白：$0.00 会被读成「这一项确实花了 0」
    const rows = panel.findAll('tbody tr')
    expect(rows[1].text()).toContain('\u2014')
    expect(rows[1].text()).not.toContain('$0.00')
    expect(rows[0].text()).not.toContain('\u2014')
    // false 是「数据不全」，缺失是「未标注」，两种不能合成一个样子
    expect(panel.text()).toContain('数据不全')
    expect(panel.findAll('.flag.incomplete').length).toBe(1)
    expect(panel.findAll('.flag').length).toBe(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('下钻：空值不发请求；切到 SKU 模式打的是 SKU 那个端点', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    const panel = wrapper.find('[data-panel="drill"]')
    await panel.find('input').setValue('   ')
    await panel.findAll('.action-btn')[0].trigger('click')
    await flushPromises()
    expect(mockedByOrder).not.toHaveBeenCalled()

    await panel.find('select').setValue('sku')
    await panel.find('input').setValue('SKU-A')
    await panel.findAll('.action-btn')[0].trigger('click')
    await flushPromises()

    expect(mockedBySku).toHaveBeenCalledWith('1', 'SKU-A', expect.objectContaining({ size: 20 }))
    expect(mockedByOrder).not.toHaveBeenCalled()
  })

  it('下钻分页：后端标记截断时下一页带上游标，页面上说明这不是全量', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200, message: 'ok',
      data: { summary: { totalRevenue: '$1.00', totalCost: '$0.50', grossProfit: '$0.50', grossMargin: '50.0%' }, rows: [] }
    } as any)
    const row = (id: number) => ({
      id, shopId: 1, amazonOrderId: 'O-1', sku: 'SKU-A', statDate: '2026-06-10',
      revenue: 10, netProfit: 1, netMargin: 0.1, dataComplete: true
    })
    mockedByOrder
      .mockResolvedValueOnce({
        code: 200, message: 'ok', data: [row(701), row(700)],
        _page: { size: 20, returned: 2, hasMore: true, truncated: true, nextCursor: 'v1:700', total: null }
      } as any)
      .mockResolvedValueOnce({ code: 200, message: 'ok', data: [row(699)], _page: null } as any)

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    const panel = wrapper.find('[data-panel="drill"]')
    await panel.find('input').setValue('O-1')
    await panel.findAll('.action-btn')[0].trigger('click')
    await flushPromises()

    expect(panel.text()).toContain('后端标记仍有下一页')

    // 「下一页」只受 busy 控制，所以清空输入再点是走得通的——这一步必须落在守卫里，
    // 否则会用空值拼出 /order/profit/order/1/ 这种打到别人路由上的请求。
    // 顺序也必须是先测空值：第二页返回 _page=null 之后「下一页」按钮就消失了。
    await panel.find('input').setValue('   ')
    await panel.findAll('.action-btn')[1].trigger('click')
    await flushPromises()

    expect(mockedByOrder).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.error-zone').text()).toContain('订单号或 SKU 不能为空')

    await panel.find('input').setValue('O-1')
    await panel.findAll('.action-btn')[1].trigger('click')
    await flushPromises()

    expect(mockedByOrder).toHaveBeenLastCalledWith('1', 'O-1', expect.objectContaining({ cursor: 'v1:700' }))
    expect(mockedByOrder).toHaveBeenCalledTimes(2)
  })

  it('200 真实数据（即使零数据）应摘掉示例标识', async () => {
    localStorage.setItem('current_shop_id', '1')
    mockedGetProfitReport.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        summary: { totalRevenue: '$0.00', totalCost: '$0.00', grossProfit: '$0.00', grossMargin: '0.0%' },
        rows: []
      }
    })

    const wrapper = mount(ProfitReport, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.mock-badge').exists()).toBe(false)
    expect(wrapper.text()).toContain('$0.00')
    expect(wrapper.text()).toContain('暂无利润数据')
  })
})
