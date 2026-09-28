import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Warehouse from '../views/Warehouse.vue'

vi.mock('@/api/warehouse', () => ({
  listWarehouses: vi.fn(),
  listInventory: vi.fn(),
  listInboundOrders: vi.fn(),
  listOutboundOrders: vi.fn()
}))

import { listWarehouses, listInventory, listInboundOrders, listOutboundOrders } from '@/api/warehouse'

const mockedListWarehouses = vi.mocked(listWarehouses)
const mockedListInventory = vi.mocked(listInventory)
const mockedListInboundOrders = vi.mocked(listInboundOrders)
const mockedListOutboundOrders = vi.mocked(listOutboundOrders)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: { template: '<span />' }
  }
}

const inventoryRow = (id: number, sku: string) => ({
  id,
  warehouseId: 1,
  shopId: 1,
  sku,
  quantity: 10,
  availableQuantity: 8
})

const outboundRow = (id: number, outboundNo: string) => ({
  id,
  shopId: 1,
  warehouseId: 1,
  outboundNo,
  status: 'PENDING' as const
})

const inboundRow = (id: number, inboundNo: string) => ({
  id,
  shopId: 1,
  warehouseId: 1,
  inboundNo,
  status: 'PENDING' as const
})
const page = (overrides: Record<string, unknown> = {}) => ({
  size: 50,
  returned: 1,
  hasMore: false,
  truncated: false,
  nextCursor: null,
  total: null,
  ...overrides
})

describe('Warehouse 服务端游标分页', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedListWarehouses.mockReset()
    mockedListInventory.mockReset()
    mockedListInboundOrders.mockReset()
    mockedListOutboundOrders.mockReset()
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [] })
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('库存首屏必须携带 size，并按服务端 nextCursor 继续加载', async () => {
    mockedListInventory
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [inventoryRow(1, 'SKU-1')],
        _page: page({ returned: 1, hasMore: true, truncated: true, nextCursor: 'INV-CUR' })
      })
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [inventoryRow(2, 'SKU-2')],
        _page: page()
      })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })

    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedListInventory).toHaveBeenCalledWith({ shopId: 1, size: 50, cursor: undefined })
    expect(wrapper.text()).toContain('已加载 1 条')
    expect(wrapper.text()).toContain('仍有更多未加载')

    const loadMore = wrapper.findAll('.page-btn').find((button) => button.text().includes('加载更多'))
    expect(loadMore).toBeTruthy()
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedListInventory).toHaveBeenLastCalledWith({ shopId: 1, size: 50, cursor: 'INV-CUR' })
    expect(wrapper.text()).toContain('SKU-1')
    expect(wrapper.text()).toContain('SKU-2')
    expect(wrapper.text()).toContain('已加载 2 条')
  })

  it('出库单必须使用服务端分页游标，不能再依赖前端本地分页', async () => {
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListOutboundOrders
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [outboundRow(1, 'OUT-1')],
        _page: page({ returned: 1, hasMore: true, truncated: true, nextCursor: 'OUT-CUR' })
      })
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [outboundRow(2, 'OUT-2')],
        _page: page()
      })

    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedListOutboundOrders).toHaveBeenCalledWith(1, undefined, 50, undefined)
    const loadMore = wrapper.findAll('.page-btn').find((button) => button.text().includes('加载更多'))
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedListOutboundOrders).toHaveBeenLastCalledWith(1, undefined, 50, 'OUT-CUR')
    expect(wrapper.text()).toContain('OUT-1')
    expect(wrapper.text()).toContain('OUT-2')
  })

  it('入库单必须使用服务端分页游标，不能在截断后冒充全量', async () => {
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInboundOrders
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [inboundRow(1, 'IN-1')],
        _page: page({ returned: 1, hasMore: true, truncated: true, nextCursor: 'INB-CUR' })
      })
      .mockResolvedValueOnce({
        code: 200,
        message: 'ok',
        data: [inboundRow(2, 'IN-2')],
        _page: page()
      })

    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(mockedListInboundOrders).toHaveBeenCalledWith(1, undefined, 50, undefined)
    expect(wrapper.text()).toContain('已加载 1 条')
    expect(wrapper.text()).toContain('仍有更多未加载')

    const loadMore = wrapper.findAll('.page-btn').find((button) => button.text().includes('加载更多'))
    expect(loadMore).toBeTruthy()
    await loadMore!.trigger('click')
    await flushPromises()

    expect(mockedListInboundOrders).toHaveBeenLastCalledWith(1, undefined, 50, 'INB-CUR')
    expect(wrapper.text()).toContain('IN-1')
    expect(wrapper.text()).toContain('IN-2')
    expect(wrapper.text()).toContain('已加载 2 条')
  })
  it('_page 缺失时必须提示完整性未知，不得把响应冒充全量', async () => {
    mockedListInventory.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [inventoryRow(1, 'SKU-UNKNOWN')]
    })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })

    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).toContain('完整性无法确认')
    expect(wrapper.text()).toContain('当前列表不能视为全量')
  })
})