import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { useToast } from '@/composables/useToast'
import Warehouse from '../views/Warehouse.vue'
import type { InboundOrder, OutboundOrder } from '@/api/warehouse'
import type { StocktakeRow } from '@/api/stocktake'

/** 模板里按按钮文案找按钮：这些用例要点的是「当前状态应该出现的那个动作」 */
const btn = (wrapper: any, text: string) =>
  wrapper.findAll('button').filter((b: any) => b.text() === text)

vi.mock('@/api/warehouse', () => ({
  listWarehouses: vi.fn(),
  listInventory: vi.fn(),
  listInboundOrders: vi.fn(),
  listOutboundOrders: vi.fn(),
  updateInventoryLocation: vi.fn(),
  createWarehouse: vi.fn(),
  updateWarehouse: vi.fn(),
  createInboundOrder: vi.fn(),
  transitInbound: vi.fn(),
  receiveInbound: vi.fn(),
  batchReceiveInbound: vi.fn(),
  cancelInbound: vi.fn(),
  createOutboundOrder: vi.fn(),
  pickOutbound: vi.fn(),
  packOutbound: vi.fn(),
  shipOutbound: vi.fn(),
  cancelOutbound: vi.fn(),
  LOCATION_CODE_MAX_LENGTH: 50
}))

vi.mock('@/api/stocktake', () => ({
  listStocktakes: vi.fn(),
  createStocktake: vi.fn(),
  getStocktakeDetail: vi.fn(),
  countStocktake: vi.fn(),
  applyStocktake: vi.fn(),
  cancelStocktake: vi.fn(),
  STOCKTAKE_STATUS_LABELS: { DRAFT: '盘点中', COUNTED: '已录实盘', APPLIED: '已落账', CANCELLED: '已取消' }
}))

import {
  listWarehouses, listInventory, listInboundOrders, listOutboundOrders, updateInventoryLocation, createWarehouse, updateWarehouse,
  createInboundOrder, transitInbound, receiveInbound, batchReceiveInbound, cancelInbound,
  createOutboundOrder, pickOutbound, packOutbound, shipOutbound, cancelOutbound
} from '@/api/warehouse'
import {
  listStocktakes, createStocktake, getStocktakeDetail, countStocktake, applyStocktake, cancelStocktake
} from '@/api/stocktake'

const mockedUpdateLocation = vi.mocked(updateInventoryLocation)
const mockedCreateWarehouse = vi.mocked(createWarehouse)
const mockedUpdateWarehouse = vi.mocked(updateWarehouse)

const mockedListWarehouses = vi.mocked(listWarehouses)
const mockedListInventory = vi.mocked(listInventory)
const mockedListInboundOrders = vi.mocked(listInboundOrders)
const mockedListOutboundOrders = vi.mocked(listOutboundOrders)

const mockedCreateInbound = vi.mocked(createInboundOrder)
const mockedTransitInbound = vi.mocked(transitInbound)
const mockedReceiveInbound = vi.mocked(receiveInbound)
const mockedBatchReceive = vi.mocked(batchReceiveInbound)
const mockedCancelInbound = vi.mocked(cancelInbound)
const mockedCreateOutbound = vi.mocked(createOutboundOrder)
const mockedPickOutbound = vi.mocked(pickOutbound)
const mockedPackOutbound = vi.mocked(packOutbound)
const mockedShipOutbound = vi.mocked(shipOutbound)
const mockedCancelOutbound = vi.mocked(cancelOutbound)
const mockedListStocktakes = vi.mocked(listStocktakes)
const mockedCreateStocktake = vi.mocked(createStocktake)
const mockedGetStocktakeDetail = vi.mocked(getStocktakeDetail)
const mockedCountStocktake = vi.mocked(countStocktake)
const mockedApplyStocktake = vi.mocked(applyStocktake)
const mockedCancelStocktake = vi.mocked(cancelStocktake)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: { template: '<span />' },
    // §S32：盘点已拆为 StocktakePanel，但本文件有一组断言直接读「盘点写入口」的
    // 可见性与说明文案。shallow 会把子组件桩成一个空标签，那些断言就查不到——
    // 那不是功能坏了，是挂载方式让被测内容不存在。这里显式放行真实组件。
    // （不整体改成 full mount，是为了避免其余子组件真实渲染引入无关请求。）
    StocktakePanel: false
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

describe('Warehouse 库位入口的角色门禁', () => {
  const row = { id: 77, warehouseId: 1, shopId: 1, sku: 'SKU-77', quantity: 10, availableQuantity: 8, locationCode: 'A-01-02' }

  const mountWithRole = async (role: string | null) => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    if (role === null) localStorage.removeItem('user_role')
    else localStorage.setItem('user_role', role)
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [row], _page: page({ returned: 1 }) })
    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  it('VIEWER 看不到改库位入口，但库位值仍然可读', async () => {
    const wrapper = await mountWithRole('VIEWER')
    expect(wrapper.find('.loc-btn').exists()).toBe(false)
    expect(wrapper.find('.loc-locked').text()).toContain('需 OPERATOR/ADMIN')
    expect(wrapper.text()).toContain('A-01-02')
  })

  it('ADMIN 与 OPERATOR 有入口', async () => {
    for (const role of ['ADMIN', 'OPERATOR']) {
      const wrapper = await mountWithRole(role)
      expect(wrapper.find('.loc-btn').exists(), role).toBe(true)
      expect(wrapper.find('.loc-locked').exists(), role).toBe(false)
    }
  })

  it('角色未知（AppHeader 还没回 getInfo）时不隐藏入口，权限由后端判', async () => {
    const wrapper = await mountWithRole(null)
    expect(wrapper.find('.loc-btn').exists()).toBe(true)
  })
})

describe('Warehouse 仓库新建/编辑入口的角色门禁', () => {
  /**
   * 后端 PUT/POST /logistics/warehouse 都是 @RequireRole({"OPERATOR","ADMIN"})，
   * 而「+ 新建仓库」与行内「编辑」此前没有任何门控——VIEWER 点下去必然 403。
   * 库位码就地编辑（updateInventoryLocation）早就按同样守卫接了门禁，
   * 这里把仓库写入口补齐到同一口径，不让两个相邻按钮一个能点一个不能点。
   */
  const wh = (id: number, name: string, code: string) => ({
    id, shopId: 1, warehouseName: name, warehouseCode: code,
    warehouseType: 'THIRD_PARTY' as const, country: 'US', city: 'LA', contactName: '张三', capacityCbm: 100
  })

  const mountWithRole = async (role: string | null) => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    if (role === null) localStorage.removeItem('user_role')
    else localStorage.setItem('user_role', role)
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [wh(5, '洛杉矶仓', 'LA-01')] })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  const writeEntry = (wrapper: any) =>
    wrapper.findAll('button').filter((b: any) => b.text() === '+ 新建仓库' || b.text() === '编辑')

  it('VIEWER 看不到新建/编辑入口，也不发写请求', async () => {
    const wrapper = await mountWithRole('VIEWER')
    expect(writeEntry(wrapper).length).toBe(0)
    // 说明要留在页面上：不解释「为什么没有按钮」用户只会以为功能没了
    expect(wrapper.text()).toContain('OPERATOR/ADMIN')
    expect(mockedListWarehouses).toHaveBeenCalled()
  })

  it('ADMIN 与 OPERATOR 有入口', async () => {
    for (const role of ['ADMIN', 'OPERATOR']) {
      const wrapper = await mountWithRole(role)
      expect(writeEntry(wrapper).length, role).toBe(2)
    }
  })

  it('角色未知时不隐藏入口（权限权威判定在后端）', async () => {
    const wrapper = await mountWithRole(null)
    expect(writeEntry(wrapper).length).toBe(2)
  })

  it('VIEWER 直接调提交也不会发出写请求（入口隐藏之外的兜底）', async () => {
    const wrapper = await mountWithRole('VIEWER')
    const vm = wrapper.vm as any
    await vm.submitWarehouse()
    await flushPromises()
    expect(mockedUpdateWarehouse).not.toHaveBeenCalled()
    expect(mockedCreateWarehouse).not.toHaveBeenCalled()
  })
})

describe('Warehouse 编辑仓库（此前 updateWarehouse 零调用）', () => {
  /**
   * 弹窗标题早就写了 `form.id ? '编辑仓库' : '新建仓库'` 这个分支，但仓库列表既没有
   * 「编辑」入口、submitWarehouse 也无条件调 createWarehouse——编辑仓库根本不可达，
   * 且误点只会「新建一个同名仓库」。本组钉住编辑链路：入口、回填、走 PUT、成功后重拉。
   */
  const wh = (id: number, name: string, code: string) => ({
    id, shopId: 1, warehouseName: name, warehouseCode: code,
    warehouseType: 'THIRD_PARTY' as const, country: 'US', city: 'LA', contactName: '张三', capacityCbm: 100
  })

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedListWarehouses.mockReset()
    mockedCreateWarehouse.mockReset()
    mockedUpdateWarehouse.mockReset()
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [wh(5, '洛杉矶仓', 'LA-01')] })
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

  const editBtn = (wrapper: any) =>
    wrapper.findAll('button').find((b: any) => b.text() === '编辑')

  it('仓库行提供「编辑」入口（弹窗的编辑分支可达）', async () => {
    const wrapper = await mountPage()
    expect(editBtn(wrapper)).toBeTruthy()
  })

  it('点编辑把该行数据回填进弹窗，标题为「编辑仓库」', async () => {
    const wrapper = await mountPage()
    await editBtn(wrapper)!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('编辑仓库')
    const nameInput = dialog.findAll('input')[0].element as HTMLInputElement
    expect(nameInput.value).toBe('洛杉矶仓')
    const codeInput = dialog.findAll('input')[1].element as HTMLInputElement
    expect(codeInput.value).toBe('LA-01')
  })

  it('编辑保存走 updateWarehouse（PUT），不误调 createWarehouse', async () => {
    mockedUpdateWarehouse.mockResolvedValue({ code: 200, message: 'ok', data: wh(5, '洛杉矶仓2', 'LA-01') })
    const wrapper = await mountPage()
    await editBtn(wrapper)!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('input')[0].setValue('洛杉矶仓2')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedUpdateWarehouse).toHaveBeenCalledTimes(1)
    expect(mockedUpdateWarehouse).toHaveBeenCalledWith(expect.objectContaining({ id: 5, warehouseName: '洛杉矶仓2' }))
    expect(mockedCreateWarehouse).not.toHaveBeenCalled()
    // 成功后重拉列表
    expect(mockedListWarehouses).toHaveBeenCalledTimes(2)
  })

  it('新建仍走 createWarehouse（编辑分支不误伤新建）', async () => {
    mockedCreateWarehouse.mockResolvedValue({ code: 200, message: 'ok', data: wh(9, '新仓', 'NEW-01') })
    const wrapper = await mountPage()
    await wrapper.findAll('button').find((b: any) => b.text() === '+ 新建仓库')!.trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.modal-mask')
    expect(dialog.text()).toContain('新建仓库')
    await dialog.findAll('input')[0].setValue('新仓')
    await dialog.findAll('input')[1].setValue('NEW-01')
    await dialog.findAll('button').find((b: any) => b.text() === '保存')!.trigger('click')
    await flushPromises()

    expect(mockedCreateWarehouse).toHaveBeenCalledTimes(1)
    expect(mockedUpdateWarehouse).not.toHaveBeenCalled()
  })
})

describe('Warehouse WMS 单据写入口的角色门禁（入库/出库/盘点）', () => {
  /**
   * 后端 InboundController / OutboundController / StocktakeController 的 15 个写端点
   * 全是 @RequireRole({"OPERATOR","ADMIN"})，而页面此前只给「仓库新建/编辑」和「改库位」
   * 做了门控——入库单、出库单、盘点单三个 Tab 的写入口对 VIEWER 全绿可点。
   * 同一屏上「编辑」是灰字说明、「拣货」却能点，用户只会以为功能坏了。
   */
  const wh = (id: number, name: string) => ({
    id, shopId: 1, warehouseName: name, warehouseCode: `W-${id}`,
    warehouseType: 'THIRD_PARTY' as const, country: 'US'
  })

  const inbound = (id: number, no: string, status: InboundOrder['status']) =>
    ({ id, shopId: 1, warehouseId: 5, inboundNo: no, status })
  const outbound = (id: number, no: string, status: OutboundOrder['status']) =>
    ({ id, shopId: 1, warehouseId: 5, outboundNo: no, status })

  const mountWithRole = async (role: string | null, opts: {
    inbounds?: InboundOrder[]; outbounds?: OutboundOrder[]; stocktakes?: StocktakeRow[]
  } = {}) => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    if (role === null) localStorage.removeItem('user_role')
    else localStorage.setItem('user_role', role)

    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [wh(5, '洛杉矶仓')] })
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: opts.inbounds ?? [], _page: page({ returned: 0 }) })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: opts.outbounds ?? [], _page: page({ returned: 0 }) })
    mockedListStocktakes.mockResolvedValue({ code: 200, message: 'ok', data: opts.stocktakes ?? [] })

    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    localStorage.clear()
    mockedCreateInbound.mockReset()
    mockedTransitInbound.mockReset()
    mockedReceiveInbound.mockReset()
    mockedBatchReceive.mockReset()
    mockedCancelInbound.mockReset()
    mockedCreateOutbound.mockReset()
    mockedPickOutbound.mockReset()
    mockedPackOutbound.mockReset()
    mockedShipOutbound.mockReset()
    mockedCancelOutbound.mockReset()
    mockedListStocktakes.mockReset()
    mockedCreateStocktake.mockReset()
    mockedGetStocktakeDetail.mockReset()
    mockedCountStocktake.mockReset()
    mockedApplyStocktake.mockReset()
    mockedCancelStocktake.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('VIEWER 看不到任何 WMS 写入口，但说明留在页面上', async () => {
    const wrapper = await mountWithRole('VIEWER', {
      inbounds: [inbound(1, 'IN-1', 'PENDING')],
      outbounds: [outbound(2, 'OUT-1', 'PENDING')],
      stocktakes: [{ id: 3, stocktakeNo: 'ST-1', warehouseId: 5, status: 'COUNTED' }]
    })

    expect(btn(wrapper, '+ 创建入库单').length).toBe(0)
    expect(btn(wrapper, '+ 创建出库单').length).toBe(0)
    expect(btn(wrapper, '+ 新建盘点单').length).toBe(0)
    // 三个 Tab 的说明都要在同一屏上出现（v-show 的面板都在 DOM 里）
    expect(wrapper.text()).toContain('入库单操作需 OPERATOR/ADMIN')
    expect(wrapper.text()).toContain('出库单操作需 OPERATOR/ADMIN')
    expect(wrapper.text()).toContain('盘点操作需 OPERATOR/ADMIN')

    // 行内动作全部收起，只剩「明细」（只读）。
    // 刻意按按钮而不是按页面文本断言：出库单表头有一列叫「发货数」，
    // 用 not.toContain('发货') 会把列名当成按钮误判。
    for (const tab of ['入库单', '出库单', '库存盘点']) {
      await wrapper.findAll('.tab-item').find((t: any) => t.text().includes(tab))!.trigger('click')
      await flushPromises()
      for (const action of ['拣货', '打包', '发货', '取消', '落账', '运输中', '到货验收']) {
        expect(btn(wrapper, action).length, `${tab} 不应出现「${action}」按钮`).toBe(0)
      }
      // 「明细」是盘点单的只读入口：VIEWER 仍然能看盘点内容，只是不能改
      if (tab === '库存盘点') {
        expect(btn(wrapper, '明细').length, '盘点的只读明细必须保留').toBe(1)
      }
    }
  })

  it('OPERATOR 与 ADMIN 有全部 WMS 写入口', async () => {
    for (const role of ['OPERATOR', 'ADMIN']) {
      const wrapper = await mountWithRole(role, {
        inbounds: [inbound(1, 'IN-1', 'IN_TRANSIT')],
        outbounds: [outbound(2, 'OUT-1', 'PENDING')]
      })
      expect(btn(wrapper, '+ 创建入库单').length, role).toBe(1)
      expect(btn(wrapper, '+ 创建出库单').length, role).toBe(1)
      expect(btn(wrapper, '+ 新建盘点单').length, role).toBe(1)
    }
  })

  it('角色未知时不隐藏入口（权限权威判定在后端）', async () => {
    const wrapper = await mountWithRole(null, { inbounds: [inbound(1, 'IN-1', 'PENDING')] })
    expect(btn(wrapper, '+ 创建入库单').length).toBe(1)
  })

  it('VIEWER 直接调 submitInbound 不发写请求', async () => {
    const wrapper = await mountWithRole('VIEWER')
    await (wrapper.vm as any).submitInbound()
    await flushPromises()
    expect(mockedCreateInbound).not.toHaveBeenCalled()
  })

  it('VIEWER 直接调拣货/发货/落账都不发写请求（入口隐藏之外的兜底）', async () => {
    const wrapper = await mountWithRole('VIEWER')
    const vm = wrapper.vm as any
    await vm.doPick(2)
    await vm.doPack(2)
    await vm.confirmShip()
    // §S32：落账等盘点操作已随面板拆进 StocktakePanel，父实例上不再有这些方法。
    // 这里仍要验证「入口隐藏之外的兜底」——即直接调方法也会被角色守卫挡下——
    // 所以改为从子组件实例调用，断言的语义不变。
    const panel = wrapper.findComponent({ name: 'StocktakePanel' })
    await (panel.vm as any).applyStocktakeRow({ id: 3 })
    await flushPromises()
    expect(mockedPickOutbound).not.toHaveBeenCalled()
    expect(mockedPackOutbound).not.toHaveBeenCalled()
    expect(mockedShipOutbound).not.toHaveBeenCalled()
    expect(mockedApplyStocktake).not.toHaveBeenCalled()
  })
})

describe('Warehouse WMS 写操作：后端业务拒绝必须当成失败', () => {
  /**
   * 本组钉住的是本页最严重的一类缺陷，与角色门禁无关、OPERATOR 同样受害：
   * <p>
   * RequireRoleAspect 与所有业务校验失败都返回 Result.failure()，
   * 即 **HTTP 200 + code=400**（GlobalExceptionHandler 上「业务拒绝刻意保持 200」的注释）。
   * axios 因此是 <b>resolve</b> 而不是 reject，`catch` 块根本不执行。
   * <p>
   * 本页此前写成 `try { await x(); 关弹窗(); 重拉列表(); } catch { toast('请稍后重试') }`，
   * 于是「角色不够 / 状态不对」被当成成功：弹窗关闭、列表刷新，用户以为办成了。
   * 而那条 toast（在的几处）又是通用文案，把后端已经写好的原因盖掉了。
   */
  const wh = (id: number, name: string) => ({
    id, shopId: 1, warehouseName: name, warehouseCode: `W-${id}`,
    warehouseType: 'THIRD_PARTY' as const, country: 'US'
  })
  const inbound = (id: number, no: string, status: InboundOrder['status']) =>
    ({ id, shopId: 1, warehouseId: 5, inboundNo: no, status })
  const outbound = (id: number, no: string, status: OutboundOrder['status']) =>
    ({ id, shopId: 1, warehouseId: 5, outboundNo: no, status })

  /** 后端角色拒绝的真实形状：HTTP 200 + code=400（RequireRoleAspect:59） */
  const REJECTED = {
    code: 400,
    message: '无权限执行该操作（需要角色：OPERATOR/ADMIN）',
    data: null as any
  }

  const mountPage = async (outStatus: OutboundOrder['status'] = 'PENDING') => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    localStorage.setItem('user_role', 'OPERATOR')
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [wh(5, '洛杉矶仓')] })
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [inbound(1, 'IN-1', 'IN_TRANSIT')], _page: page({ returned: 1 }) })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [outbound(2, 'OUT-1', outStatus)], _page: page({ returned: 1 }) })
    mockedListStocktakes.mockResolvedValue({ code: 200, message: 'ok', data: [{ id: 3, stocktakeNo: 'ST-1', warehouseId: 5, status: 'COUNTED' }] })
    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    localStorage.clear()
    mockedCreateInbound.mockReset()
    mockedTransitInbound.mockReset()
    mockedReceiveInbound.mockReset()
    mockedCancelInbound.mockReset()
    mockedCreateOutbound.mockReset()
    mockedPickOutbound.mockReset()
    mockedPackOutbound.mockReset()
    mockedShipOutbound.mockReset()
    mockedCancelOutbound.mockReset()
    mockedCreateStocktake.mockReset()
    mockedCountStocktake.mockReset()
    mockedApplyStocktake.mockReset()
    mockedCancelStocktake.mockReset()
    mockedListStocktakes.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const openTab = async (wrapper: any, label: string) => {
    await wrapper.findAll('.tab-item').find((t: any) => t.text().includes(label))!.trigger('click')
    await flushPromises()
  }

  it('创建入库单被拒：不关弹窗、不重拉列表、后端原因透出', async () => {
    mockedCreateInbound.mockResolvedValue(REJECTED)
    const wrapper = await mountPage()
    await openTab(wrapper, '入库单')

    await btn(wrapper, '+ 创建入库单')[0].trigger('click')
    await flushPromises()
    // 弹窗打开后才能点「创建」；此前被拒时弹窗会直接关掉，看起来像成功了
    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('button').find((b: any) => b.text() === '创建')!.trigger('click')
    await flushPromises()

    expect(mockedCreateInbound).toHaveBeenCalledTimes(1)
    // 关键断言：被拒后弹窗还在（用户能看到自己填的内容，也知道没成功）
    expect(wrapper.find('.modal-mask').exists()).toBe(true)
    expect(mockedListInboundOrders).toHaveBeenCalledTimes(1) // 只有首屏那一次，没有重拉
  })

  it('拣货被拒：提示后端原因，而不是「请稍后重试」', async () => {
    mockedPickOutbound.mockResolvedValue(REJECTED)
    const wrapper = await mountPage()
    await openTab(wrapper, '出库单')

    await btn(wrapper, '拣货')[0].trigger('click')
    await flushPromises()

    expect(mockedPickOutbound).toHaveBeenCalledTimes(1)
    expect(mockedListOutboundOrders).toHaveBeenCalledTimes(1)
  })

  it('发货被拒：弹窗不关，原因可读', async () => {
    mockedShipOutbound.mockResolvedValue(REJECTED)
    // 「发货」按钮只在 PACKED 状态出现，其它用例默认是 PENDING（只有拣货）
    const wrapper = await mountPage('PACKED')
    await openTab(wrapper, '出库单')

    await btn(wrapper, '发货')[0].trigger('click')
    await flushPromises()
    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('button').find((b: any) => b.text() === '确认发货')!.trigger('click')
    await flushPromises()

    expect(mockedShipOutbound).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.modal-mask').exists()).toBe(true)
  })

  it('盘点落账被拒：不重拉列表', async () => {
    mockedApplyStocktake.mockResolvedValue(REJECTED)
    const wrapper = await mountPage()
    await openTab(wrapper, '库存盘点')

    await btn(wrapper, '落账')[0].trigger('click')
    await flushPromises()

    expect(mockedApplyStocktake).toHaveBeenCalledTimes(1)
    expect(mockedListStocktakes).toHaveBeenCalledTimes(1)
  })

  it('到货验收被拒：不关弹窗、不重拉库存', async () => {
    mockedReceiveInbound.mockResolvedValue(REJECTED)
    const wrapper = await mountPage()
    await openTab(wrapper, '入库单')

    await btn(wrapper, '到货验收')[0].trigger('click')
    await flushPromises()
    const dialog = wrapper.find('.modal-mask')
    await dialog.findAll('button').find((b: any) => b.text() === '确认收货')!.trigger('click')
    await flushPromises()

    expect(mockedReceiveInbound).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.modal-mask').exists()).toBe(true)
  })

  it('成功路径不受影响：code=200 才关弹窗并重拉', async () => {
    mockedPickOutbound.mockResolvedValue({ code: 200, message: 'ok', data: outbound(2, 'OUT-1', 'PICKING') })
    const wrapper = await mountPage()
    await openTab(wrapper, '出库单')

    await btn(wrapper, '拣货')[0].trigger('click')
    await flushPromises()

    expect(mockedPickOutbound).toHaveBeenCalledTimes(1)
    expect(mockedListOutboundOrders).toHaveBeenCalledTimes(2) // 首屏 + 成功后重拉
  })
})

describe('Warehouse 无仓库时打开建单弹窗', () => {
  /**
   * openInboundDialog / openOutboundDialog 原本用 warehouses[0]?.id || 0 回填仓库。
   * 没有仓库时下拉是空的、提交值退化成 0，后端按无效仓库拒绝，而 catch 只写 console——
   * OPERATOR 会看到「弹窗一开一关、什么都没发生」。零反馈比明确报错更难排查。
   */
  const mountPage = async () => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    localStorage.setItem('user_role', 'OPERATOR')
    mockedListWarehouses.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    mockedListInventory.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListInboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListOutboundOrders.mockResolvedValue({ code: 200, message: 'ok', data: [], _page: page({ returned: 0 }) })
    mockedListStocktakes.mockResolvedValue({ code: 200, message: 'ok', data: [] })
    const wrapper = mount(Warehouse, { shallow: true, global: globalStubs })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    mockedCreateInbound.mockReset()
    mockedCreateOutbound.mockReset()
    mockedCreateStocktake.mockReset()
    mockedListStocktakes.mockReset()
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  const openTab = async (wrapper: any, label: string) => {
    await wrapper.findAll('.tab-item').find((t: any) => t.text().includes(label))!.trigger('click')
    await flushPromises()
  }

  it('无仓库时点「+ 创建入库单」不打开弹窗，并说明原因', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '入库单')
    await wrapper.findAll('button').find((b: any) => b.text() === '+ 创建入库单')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal-mask').exists()).toBe(false)
    expect(mockedCreateInbound).not.toHaveBeenCalled()
    // 必须说出「为什么没反应」：只说「没打开弹窗」的话，去掉守卫让代码抛错也会绿
    expect(useToast().toastMessage.value).toContain('还没有可用仓库')
  })

  it('无仓库时点「+ 创建出库单」不打开弹窗，并说明原因', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '出库单')
    await wrapper.findAll('button').find((b: any) => b.text() === '+ 创建出库单')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal-mask').exists()).toBe(false)
    expect(useToast().toastMessage.value).toContain('还没有可用仓库')
  })

  it('无仓库时点「+ 新建盘点单」不打开弹窗，并说明原因', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '库存盘点')
    await wrapper.findAll('button').find((b: any) => b.text() === '+ 新建盘点单')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal-mask').exists()).toBe(false)
    expect(mockedCreateStocktake).not.toHaveBeenCalled()
    expect(useToast().toastMessage.value).toContain('还没有可用仓库')
  })
})
