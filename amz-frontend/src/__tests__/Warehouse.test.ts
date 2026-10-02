import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Warehouse from '../views/Warehouse.vue'

vi.mock('@/api/warehouse', () => ({
  listWarehouses: vi.fn(),
  listInventory: vi.fn(),
  listInboundOrders: vi.fn(),
  listOutboundOrders: vi.fn(),
  updateInventoryLocation: vi.fn(),
  LOCATION_CODE_MAX_LENGTH: 50
}))

import { listWarehouses, listInventory, listInboundOrders, listOutboundOrders, updateInventoryLocation } from '@/api/warehouse'

const mockedUpdateLocation = vi.mocked(updateInventoryLocation)

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
describe('Warehouse 库位码就地编辑', () => {
  const row = (id: number | undefined, locationCode?: string) => ({
    id,
    warehouseId: 1,
    shopId: 1,
    sku: 'SKU-' + id,
    quantity: 10,
    availableQuantity: 8,
    locationCode
  })

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedUpdateLocation.mockReset()
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInventory.mockResolvedValue({
      code: 200, message: 'ok',
      data: [row(77, 'A-01-02'), row(undefined, 'B-00-01')],
      _page: page({ returned: 2 })
    })
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const mountPage = async () => {
    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  const editButtons = (wrapper: any) => wrapper.findAll('.loc-btn')

  it('保存走 PUT，路径带 inventoryId、query 带库位码，成功后就地更新这一行', async () => {
    mockedUpdateLocation.mockResolvedValue({ code: 200, message: 'ok', data: row(77, 'B-09-01') })
    const wrapper = await mountPage()

    const btn = editButtons(wrapper).find((b: any) => !b.attributes('disabled'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await wrapper.find('.loc-input').setValue('  B-09-01  ')
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateLocation).toHaveBeenCalledTimes(1)
    expect(mockedUpdateLocation).toHaveBeenCalledWith(77, 'B-09-01')
    // 首尾空格在前端就去掉，后端同规则；页面显示的是返回值而不是输入原样
    expect(wrapper.text()).toContain('B-09-01')
    expect(wrapper.text()).toContain('已保存')
    expect(wrapper.find('.loc-error').exists()).toBe(false)
  })

  it('空库位不发请求，并说明后端不会静默清空', async () => {
    const wrapper = await mountPage()
    await editButtons(wrapper).find((b: any) => !b.attributes('disabled'))!.trigger('click')
    await wrapper.find('.loc-input').setValue('   ')
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateLocation).not.toHaveBeenCalled()
    expect(wrapper.find('.loc-error').text()).toContain('不能为空')
  })

  it('超过 50 字符不发请求，直接把上限说出来', async () => {
    const wrapper = await mountPage()
    await editButtons(wrapper).find((b: any) => !b.attributes('disabled'))!.trigger('click')
    // maxlength 只挡键盘输入，粘贴与程序化赋值仍可能超长，所以断言走 setValue
    await wrapper.find('.loc-input').setValue('X'.repeat(60))
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateLocation).not.toHaveBeenCalled()
    expect(wrapper.find('.loc-error').text()).toContain('50')
  })

  it('后端拒绝时错误留在格子上，行内容不被改动', async () => {
    mockedUpdateLocation.mockResolvedValue({ code: 400, message: '库位码不能为空（要清空请走专门的解绑操作）', data: null as any })
    const wrapper = await mountPage()
    await editButtons(wrapper).find((b: any) => !b.attributes('disabled'))!.trigger('click')
    await wrapper.find('.loc-input').setValue('C-01-01')
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.loc-error').text()).toContain('要清空请走专门的解绑操作')
    // 失败时留在编辑态、输入内容不丢，其它行也没被顺手改写
    expect((wrapper.find('.loc-input').element as HTMLInputElement).value).toBe('C-01-01')
    expect(wrapper.text()).toContain('B-00-01')
    expect(wrapper.text()).not.toContain('已保存')
  })

  it('请求异常（含 403 角色不足）也必须可见', async () => {
    mockedUpdateLocation.mockRejectedValue(new Error('Request failed with status code 403'))
    const wrapper = await mountPage()
    await editButtons(wrapper).find((b: any) => !b.attributes('disabled'))!.trigger('click')
    await wrapper.find('.loc-input').setValue('C-01-02')
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.loc-error').text()).toContain('403')
  })

  it('没有 id 的行不给可点的编辑按钮', async () => {
    const wrapper = await mountPage()
    const buttons = editButtons(wrapper)
    expect(buttons.length).toBe(2)
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(buttons[1].attributes('title')).toContain('没有 id')
  })

  it('取消回到只读视图且不发请求', async () => {
    const wrapper = await mountPage()
    await editButtons(wrapper).find((b: any) => !b.attributes('disabled'))!.trigger('click')
    expect(wrapper.find('.loc-input').exists()).toBe(true)
    await wrapper.findAll('.loc-edit .page-btn').find((b: any) => b.text() === '取消')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.loc-input').exists()).toBe(false)
    expect(mockedUpdateLocation).not.toHaveBeenCalled()
  })
})
