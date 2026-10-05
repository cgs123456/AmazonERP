import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Warehouse from '../views/Warehouse.vue'

vi.mock('@/api/warehouse', () => ({
  listWarehouses: vi.fn(),
  listInventory: vi.fn(),
  listInboundOrders: vi.fn(),
  listOutboundOrders: vi.fn(),
  updateInventoryLocation: vi.fn(),
  getStockAging: vi.fn(),
  LOCATION_CODE_MAX_LENGTH: 50,
  // 后端返回的是 Map，键是 snake_case；这份常量必须与后端键一一对应
  AGING_BUCKETS: [
    { key: 'fresh_30d', label: '≤30 天' },
    { key: 'mid_31_90d', label: '31–90 天' },
    { key: 'old_91_180d', label: '91–180 天' },
    { key: 'dead_181d_plus', label: '181 天以上' }
  ]
}))

import { listWarehouses, listInventory, listInboundOrders, listOutboundOrders, getStockAging } from '@/api/warehouse'

const mockedAging = vi.mocked(getStockAging)

const renderErrors: unknown[] = []
const globalStubs = {
  config: { errorHandler: (e: unknown) => { renderErrors.push(e) } },
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: { template: '<span />' }
  }
}

const bucket = (count: number, value: string, pct: string) => ({ count, value, pct })
const AGING_OK = {
  shopId: 1,
  totalSkus: 6,
  scannedStockCount: 6,
  stocksTruncated: false,
  aging: {
    fresh_30d: bucket(2, '20.00', '0.2000'),
    mid_31_90d: bucket(1, '10.00', '0.1000'),
    old_91_180d: bucket(1, '30.00', '0.3000'),
    dead_181d_plus: bucket(2, '40.00', '0.4000')
  },
  oldestTop10: [
    { sku: 'SKU-OLD', warehouse: '美西仓', days: 400, qty: 3, value: '30.00' },
    { sku: 'SKU-NO-NAME', warehouse: '', days: 210, qty: 1, value: '10.00' }
  ]
}

const mountPage = async () => {
  const wrapper = mount(Warehouse, { global: globalStubs })
  await flushPromises()
  return wrapper
}

const openInventoryTab = async (wrapper: any) => {
  const tab = wrapper.findAll('.tab-item').find((t: any) => t.text().includes('库存查询'))
  expect(tab, '没有「库存查询」这个 Tab').toBeTruthy()
  await tab!.trigger('click')
  // 面板是 watch(activeTab) 里发的请求：一次 flush 只走完点击的那一 tick，
  // 不补一次就会永远停在「加载中」（这是我第一版跑红的真实原因）
  await flushPromises()
  await flushPromises()
}

describe('海外仓 · 库龄分析面板', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.mocked(listWarehouses).mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(listInventory).mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(listInboundOrders).mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    vi.mocked(listOutboundOrders).mockReset().mockResolvedValue({ code: 200, message: 'ok', data: [] } as any)
    mockedAging.mockReset().mockResolvedValue({ code: 200, message: 'ok', data: AGING_OK } as any)
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('首屏不请求库龄，切到「库存查询」才按当前店铺读一次', async () => {
    const wrapper = await mountPage()
    expect(mockedAging).not.toHaveBeenCalled()
    await openInventoryTab(wrapper)
    expect(mockedAging).toHaveBeenCalledTimes(1)
    expect(mockedAging).toHaveBeenCalledWith('1')
  })

  it('四段用后端 snake_case 键取值：数量、货值、占比都要显示出来', async () => {
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    const card = wrapper.find('[data-panel="aging"]')
    expect(card.exists()).toBe(true)
    if (renderErrors.length) throw renderErrors[0]
    const text = card.text()
    expect(text).toContain('≤30 天')
    expect(text).toContain('2 个 SKU')
    expect(text).toContain('181 天以上')
    expect(text).toContain('40.00')
    expect(text).toContain('40.00%')
    expect(text).toContain('有货 SKU 6 个')
  })

  it('快照没记仓库名的行不能空着，也不能整行不见', async () => {
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    const rows = wrapper.find('[data-panel="aging"]').findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[1].text()).toContain('—（快照未记仓库名）')
    expect(rows[0].text()).toContain('美西仓')
    expect(rows[0].text()).toContain('400')
  })

  it('扫描触顶时必须说明分段与 Top10 只覆盖已扫描部分', async () => {
    mockedAging.mockResolvedValue({
      code: 200, message: 'ok', data: { ...AGING_OK, stocksTruncated: true, scannedStockCount: 5000 }
    } as any)
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    expect(wrapper.find('[data-panel="aging"]').text()).toContain('本次扫描触顶（已扫 5000 行）')
  })

  it('后端失败时给出原因，不渲染四个 0 冒充「没有滞销」', async () => {
    mockedAging.mockResolvedValue({ code: 500, message: '库存快照服务没起', data: null } as any)
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    const card = wrapper.find('[data-panel="aging"]')
    expect(card.text()).toContain('库龄分析失败：库存快照服务没起')
    expect(card.findAll('.aging-cell')).toHaveLength(0)
    expect(card.findAll('tbody tr')).toHaveLength(0)
  })

  it('没有店铺上下文时不发请求，并说明为什么不能猜一家', async () => {
    localStorage.clear()
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    expect(mockedAging).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="aging"]').text()).toContain('未选择店铺')
  })

  it('「重新计算」会带着当前店铺再读一次（缓存不是不刷新的借口）', async () => {
    const wrapper = await mountPage()
    await openInventoryTab(wrapper)
    const btn = wrapper.find('[data-panel="aging"]').findAll('button')
      .find((b: any) => b.text().includes('重新计算'))
    expect(btn, '没有「重新计算」按钮').toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    expect(mockedAging).toHaveBeenCalledTimes(2)
  })
})
