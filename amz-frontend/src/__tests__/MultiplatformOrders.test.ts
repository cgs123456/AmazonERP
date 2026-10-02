import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import MultiplatformOrders from '../views/MultiplatformOrders.vue'

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
const paged = () => ({ size: 20, returned: 2, hasMore: true, truncated: true, nextCursor: 'v1:41', total: null })

const ORDERS = [
  {
    id: 61, unifiedOrderNo: 'UO1', platform: 'TEMU', platformOrderNo: 'TE-9001', shopId: 1,
    buyerNickname: 'Ana', shipCountry: 'US', sku: 'SKU-A', productName: 'Yoga mat',
    quantity: 2, originalAmount: 25.9, currency: 'USD', cnyAmount: 186.5,
    status: 'PAID', trackingNo: null, orderCreateTime: '2026-09-30 10:12:00'
  },
  {
    id: 62, unifiedOrderNo: 'UO2', platform: 'SHEIN', platformOrderNo: 'SH-7001', shopId: 1,
    buyerNickname: null, shipCountry: 'DE', sku: null, productName: 'Lamp',
    quantity: 1, originalAmount: 12, currency: 'EUR', cnyAmount: null,
    status: 'SHIPPED', trackingNo: 'TRK-OLD', orderCreateTime: null
  }
]

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listOrders).mockResolvedValue(ok(ORDERS))
  vi.mocked(api.listOrdersByPlatform).mockResolvedValue(ok([ORDERS[0]]))
  vi.mocked(api.syncAllPlatforms).mockResolvedValue(
    ok({ attempted: 3, succeeded: 2, failed: 1, inserted: 0, failedPlatforms: ['TIKTOK'] }))
  vi.mocked(api.syncPlatform).mockResolvedValue(ok(4))
  vi.mocked(api.markOrderShipped).mockResolvedValue(ok(true))
}

const mountPage = async () => {
  const wrapper = mount(MultiplatformOrders, { global: stubs })
  await flushPromises()
  return wrapper
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
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

describe('多平台订单页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    happy()
  })

  it('未选店铺时既不查列表也不给同步入口', async () => {
    localStorage.clear()
    const wrapper = mount(MultiplatformOrders, { global: stubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(api.listOrders).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="sync"]').exists()).toBe(false)
    expect(wrapper.find('[data-panel="orders"]').exists()).toBe(false)
  })

  it('说明区把「本地表可能从没同步过」与「不提供未实现按钮」讲清楚', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('本地统一订单表')
    expect(note).toContain('单个平台失败不影响另外两个')
    expect(note).toContain('真实回传给平台')
    expect(note).toContain('不提供')
  })

  it('列表默认查全部平台，选了平台才走按平台的那个端点', async () => {
    const wrapper = await mountPage()
    expect(api.listOrders).toHaveBeenCalledWith('1', expect.objectContaining({ size: 20 }))
    expect(api.listOrdersByPlatform).not.toHaveBeenCalled()

    const panel = wrapper.find('[data-panel="orders"]')
    expect(panel.text()).toContain('TE-9001')
    expect(panel.text()).toContain('UO2')

    await panel.find('select').setValue('TEMU')
    expect(api.listOrdersByPlatform).toHaveBeenCalledWith('1', 'TEMU',
      expect.objectContaining({ size: 20 }))
  })

  it('截断时给出下一页并带上游标', async () => {
    vi.mocked(api.listOrders).mockResolvedValue(ok(ORDERS, paged()))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-panel="orders"] .filter-row').text()).toContain('本页不是全量')
    await clickBtn(wrapper, '下一页')
    expect(api.listOrders).toHaveBeenLastCalledWith('1', expect.objectContaining({ cursor: 'v1:41' }))
    expect(wrapper.findAll('[data-panel="orders"] tbody tr').length).toBe(4)
  })

  it('金额缺失显示占位而不是 0，人民币折算只在后端给了的时候显示', async () => {
    const wrapper = await mountPage()
    const rows = wrapper.findAll('[data-panel="orders"] tbody tr')
    expect(rows[0].text()).toContain('186.5')
    expect(rows[1].text()).not.toContain('CNY')
    expect(rows[1].text()).toContain('-')
  })

  it('全平台同步要确认，结果按后端计数显示并点名失败平台', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '同步全部平台')
    expect(api.syncAllPlatforms).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('单个平台失败不会中断另外两个')
    await clickBtn(wrapper, '确认执行')

    expect(api.syncAllPlatforms).toHaveBeenCalledWith('1')
    const result = wrapper.find('[data-panel="sync"] .sync-result').text()
    expect(result).toContain('尝试 3')
    expect(result).toContain('失败 1')
    expect(result).toContain('失败平台：TIKTOK')
    // 新增 0 且 failed>0，页面必须把它和「没有新单」区分开
    expect(result).toContain('才等于「确实没有新单」')
    expect(wrapper.findAll('[data-panel="orders"] tbody tr').length).toBe(2)
  })

  it('单平台同步说明新增条数不是平台总量', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '仅同步 TEMU')
    expect(confirmText(wrapper)).toContain('能力未接入')
    await clickBtn(wrapper, '确认执行')

    expect(api.syncPlatform).toHaveBeenCalledWith('1', 'TEMU')
    const blocks = wrapper.findAll('[data-panel="sync"] .sync-result')
    expect(blocks[blocks.length - 1].text()).toContain('本次新增入库 4 单')
    expect(blocks[blocks.length - 1].text()).toContain('不是平台总单量')
  })

  it('发货回传需要运单号，空的时候不发请求', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'orders', 0, '发货回传')
    expect(confirmText(wrapper)).toContain('运单号会被提交给该平台')
    await clickBtn(wrapper, '确认执行')
    expect(api.markOrderShipped).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('运单号不能为空')

    await rowBtn(wrapper, 'orders', 0, '发货回传')
    await wrapper.find('.modal input').setValue('TRK-NEW-1')
    await clickBtn(wrapper, '确认执行')
    expect(api.markOrderShipped).toHaveBeenCalledWith(61, 'TRK-NEW-1')
    expect(vi.mocked(api.listOrders).mock.calls.length).toBe(2)
  })

  it('平台没接受回传时不能说成本地已发货', async () => {
    vi.mocked(api.markOrderShipped).mockResolvedValue(ok(false))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'orders', 0, '发货回传')
    await wrapper.find('.modal input').setValue('TRK-X')
    await clickBtn(wrapper, '确认执行')

    expect(wrapper.find('.error-zone').text()).toContain('未接受这次发货回传')
    expect(wrapper.find('.error-zone').text()).toContain('PAID')
    // 状态没变也要把列表读回来，不能停在点击前的画面
    expect(vi.mocked(api.listOrders).mock.calls.length).toBe(2)
  })

  it('已经是终态的订单，确认文案提示重复回传会被拒', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'orders', 1, '发货回传')
    const detail = wrapper.find('.confirm-detail').text()
    expect(detail).toContain('这单当前已是 SHIPPED')
    // 运单号预填的是订单上已有的值，方便修正而不是从零猜
    expect((wrapper.find('.modal input').element as any).value).toBe('TRK-OLD')
  })

  it('列表接口失败时空态不许暗示「平台没有订单」', async () => {
    vi.mocked(api.listOrders).mockResolvedValue(fail('店铺不存在或无权访问'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('店铺不存在或无权访问')
    const text = wrapper.find('[data-panel="orders"]').text()
    expect(text).toContain('这两种情况在同步结果出来之前分不出来')
  })

  it('同步被后端拒绝时不清空上一次结果之外的状态，也不刷列表', async () => {
    vi.mocked(api.syncAllPlatforms).mockResolvedValue(fail('该平台的真实客户端尚未接入'))
    const wrapper = await mountPage()
    const before = vi.mocked(api.listOrders).mock.calls.length
    await clickBtn(wrapper, '同步全部平台')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('尚未接入')
    expect(wrapper.find('.sync-result').exists()).toBe(false)
    expect(vi.mocked(api.listOrders).mock.calls.length).toBe(before)
  })
})
