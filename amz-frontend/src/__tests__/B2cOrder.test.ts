import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import B2cOrder from '../views/B2cOrder.vue'

vi.mock('@/api/order', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/order')
  return { ...actual, saveB2cOrder: vi.fn(), getMyB2cOrders: vi.fn() }
})
vi.mock('@/api/listing', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/listing')
  return { ...actual, listMaster: vi.fn() }
})

import { saveB2cOrder, getMyB2cOrders } from '@/api/order'
import { listMaster } from '@/api/listing'

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: null }) as any
const fail = (code: number, message: string) => ({ code, message, data: null } as any)

const MASTER = [
  { id: 31, shopId: 1, sku: 'SKU-A', title: 'Yoga mat', price: 19.9, marketplaceId: 'ATVPDKIKX0DER' },
  { id: 32, shopId: 1, sku: 'SKU-B', title: null, price: null, marketplaceId: 'ATVPDKIKX0DER' }
]
const MINE = [
  { id: 9001, productId: 31, userId: 42, quantity: null, finalPrice: 19.9, status: 0 },
  { id: 9002, productId: 32, userId: 42, quantity: 2, finalPrice: 8, status: 1 }
]

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const mountPage = async () => {
  const wrapper = mount(B2cOrder, { global: stubs })
  await flushPromises()
  return wrapper
}

const pickProduct = async (wrapper: any, id: number) => {
  await wrapper.find('select').setValue(String(id))
  await flushPromises()
}

const fillPrice = async (wrapper: any, price: string) => {
  await wrapper.find('input[inputmode="decimal"]').setValue(price)
  await flushPromises()
}

const submitBtn = (wrapper: any) =>
  wrapper.findAll('button').find((b: any) => b.text().includes('提交下单'))

describe('自建下单（B2C）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    vi.mocked(listMaster).mockResolvedValue(ok(MASTER) as any)
    vi.mocked(getMyB2cOrders).mockResolvedValue(ok(MINE) as any)
    vi.mocked(saveB2cOrder).mockResolvedValue(ok(null) as any)
  })

  it('未选店铺时不读商品主数据', async () => {
    localStorage.clear()
    const wrapper = await mountPage()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(listMaster).not.toHaveBeenCalled()
  })

  it('首屏按店铺读主数据，并把我的下单原样列出来（状态码要翻成人话）', async () => {
    const wrapper = await mountPage()
    expect(listMaster).toHaveBeenCalledWith('1')
    expect(getMyB2cOrders).toHaveBeenCalledTimes(1)
    const options = wrapper.findAll('select option')
    expect(options[1].text()).toContain('SKU-A')
    expect(options[1].text()).toContain('Yoga mat')
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('待付款')
    expect(rows[1].text()).toContain('已付款')
  })

  it('没选商品或没填单价时不能提交', async () => {
    const wrapper = await mountPage()
    expect(submitBtn(wrapper)!.attributes('disabled')).toBeDefined()
    await pickProduct(wrapper, 31)
    expect(submitBtn(wrapper)!.attributes('disabled')).toBeDefined()
    await fillPrice(wrapper, '19.90')
    expect(submitBtn(wrapper)!.attributes('disabled')).toBeUndefined()
  })

  it('提交体里只有商品与价格：身份与幂等键归后端，页面不许代填', async () => {
    const wrapper = await mountPage()
    await pickProduct(wrapper, 31)
    await fillPrice(wrapper, '19.90')
    await submitBtn(wrapper)!.trigger('click')
    await flushPromises()

    const payload = vi.mocked(saveB2cOrder).mock.calls[0][0] as unknown as Record<string, unknown>
    expect(payload).toEqual({ productId: 31, price: 19.9 })
    expect('userId' in payload).toBe(false)
    expect('messageId' in payload).toBe(false)
  })

  it('成功只说明「已投递」：绿条讲清没落库，绝不出现红色成单口吻', async () => {
    const wrapper = await mountPage()
    await pickProduct(wrapper, 31)
    await fillPrice(wrapper, '19.90')
    await submitBtn(wrapper)!.trigger('click')
    await flushPromises()

    const note = wrapper.find('.note-line').text()
    expect(note).toContain('已投递下单消息')
    expect(note).toContain('才算成单')
    expect(wrapper.find('.error-zone').exists()).toBe(false)
    // 投递后重拉一次，让「有没有真的落库」当场可见（首屏 1 次 + 提交后 1 次）
    expect(getMyB2cOrders).toHaveBeenCalledTimes(2)
  })

  it('后端当场拒（金额口径）时读原文，不显示成提交成功，也不重拉列表', async () => {
    vi.mocked(saveB2cOrder).mockResolvedValue(
      fail(400, '订单金额非法：price 必须大于 0，实际 0') as any)
    const wrapper = await mountPage()
    await pickProduct(wrapper, 31)
    await fillPrice(wrapper, '0')
    await submitBtn(wrapper)!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-zone').text()).toContain('订单金额非法：price 必须大于 0')
    expect(wrapper.find('.note-line').exists()).toBe(false)
    expect(getMyB2cOrders).toHaveBeenCalledTimes(1)
  })

  it('属性：空白行不进请求体，填了的按 label + 单值数组提交', async () => {
    const wrapper = await mountPage()
    await pickProduct(wrapper, 31)
    await fillPrice(wrapper, '19.90')
    const addBtn = wrapper.findAll('button').find((b: any) => b.text() === '加一项')
    await addBtn!.trigger('click')
    await flushPromises()
    const attrInputs = wrapper.findAll('.attr-row input')
    await attrInputs[0].setValue('颜色')
    await attrInputs[1].setValue('黑')
    await addBtn!.trigger('click')
    await flushPromises()
    await submitBtn(wrapper)!.trigger('click')
    await flushPromises()

    const payload = vi.mocked(saveB2cOrder).mock.calls[0][0] as unknown as Record<string, unknown>
    expect(payload.selectAttributes).toEqual([{ label: '颜色', value: ['黑'] }])
  })

  it('主数据读失败时下拉是空的，提交仍然不可用（不把「没商品」当成「可以随便填」）', async () => {
    vi.mocked(listMaster).mockResolvedValue(fail(500, '商品服务没起') as any)
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('商品服务没起')
    await fillPrice(wrapper, '19.90')
    expect(submitBtn(wrapper)!.attributes('disabled')).toBeDefined()
  })
})
