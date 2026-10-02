import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdBidSchedule from '../views/AdBidSchedule.vue'

vi.mock('@/api/adBidSchedule', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/adBidSchedule')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' && k !== 'hourRangeText' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/adBidSchedule'

const ok = <T>(data: T, page: any = null) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page }) as any
const fail = (message: string) => ({ code: 500, message, data: null } as any)
const paged = () => ({ size: 20, returned: 2, hasMore: true, truncated: true, nextCursor: 'v1:99', total: null })

const SCHEDULES = [
  { id: 91, shopId: 1, campaignId: null, startHour: 20, endHour: 23, multiplier: 1.5, enabled: 1 },
  { id: 92, shopId: 1, campaignId: 'camp-777', startHour: 0, endHour: 6, multiplier: 0.7, enabled: 0 }
]

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listBidSchedules).mockResolvedValue(ok(SCHEDULES))
  vi.mocked(api.createBidSchedule).mockResolvedValue(ok(SCHEDULES[0]))
  vi.mocked(api.updateBidSchedule).mockResolvedValue(ok(SCHEDULES[0]))
  vi.mocked(api.toggleBidSchedule).mockResolvedValue(ok(true))
  vi.mocked(api.deleteBidSchedule).mockResolvedValue(ok(true))
}

const mountPage = async () => {
  const wrapper = mount(AdBidSchedule, { global: stubs })
  await flushPromises()
  return wrapper
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

const rowBtn = async (wrapper: any, index: number, label: string) => {
  const rows = wrapper.find('[data-panel="list"]').findAll('tbody tr')
  expect(rows.length, '行数不足').toBeGreaterThan(index)
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

const formInputs = (wrapper: any) => wrapper.find('.form-card').findAll('input')
const formSelects = (wrapper: any) => wrapper.find('.form-card').findAll('select')

describe('分时调价页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    happy()
  })

  it('未选店铺时不查列表，也不给新建入口', async () => {
    localStorage.clear()
    const wrapper = mount(AdBidSchedule, { global: stubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(api.listBidSchedules).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="list"]').exists()).toBe(false)
  })

  it('说明区把「本页会真实改价」和「不支持跨零点」写在最前面', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('真实修改广告账号竞价')
    expect(note).toContain('不支持跨零点')
    expect(note).toContain('不会自动回滚')
    expect(note).toContain('基准价')
  })

  it('列表把小时区间与倍率解释成人话，启用中的规则标成会改价', async () => {
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="list"]')
    expect(api.listBidSchedules).toHaveBeenCalledWith('1', expect.objectContaining({ size: 20 }))
    expect(panel.text()).toContain('20:00-24:00')
    expect(panel.text()).toContain('00:00-07:00')
    expect(panel.text()).toContain('全部活动')
    expect(panel.text()).toContain('camp-777')
    expect(panel.text()).toContain('启用中（会改价）')
    expect(panel.text()).toContain('已停用')
  })

  it('跨零点的时段不让提交，并把后端的拒因显示出来', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建调价规则')
    const selects = formSelects(wrapper)
    await selects[0].setValue('22')
    await selects[1].setValue('2')
    expect(wrapper.find('.reject-line').text()).toContain('永远不会生效')
    expect(wrapper.find('.form-actions .page-btn').attributes('disabled')).toBeDefined()
    await clickBtn(wrapper, '新建调价规则')
    expect(api.createBidSchedule).not.toHaveBeenCalled()
  })

  it('倍率越出 0.10~5.00 时禁止提交', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建调价规则')
    await formInputs(wrapper)[1].setValue('50')
    expect(wrapper.find('.reject-line').text()).toContain('倍率只能落在')
    expect(wrapper.find('.form-actions .page-btn').attributes('disabled')).toBeDefined()
  })

  it('创建要确认「下一个整点即开始改价」，确认后带上当前店铺', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建调价规则')
    await formInputs(wrapper)[0].setValue('camp-888')
    await clickBtn(wrapper, '创建规则')
    expect(api.createBidSchedule).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('每个整点会把关键词竞价改成 基准价 × 1.2')
    await clickBtn(wrapper, '确认执行')
    const body = vi.mocked(api.createBidSchedule).mock.calls[0][0] as any
    expect(body.shopId).toBe(1)
    expect(body.campaignId).toBe('camp-888')
    expect(body.multiplier).toBe(1.2)
    expect(body.enabled).toBe(1)
    expect(body.id).toBeUndefined()
    expect(vi.mocked(api.listBidSchedules).mock.calls.length).toBe(2)
  })

  it('活动留空提交 null（全店语义），不能提交空串', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建调价规则')
    await clickBtn(wrapper, '创建规则')
    await clickBtn(wrapper, '确认执行')
    const body = vi.mocked(api.createBidSchedule).mock.calls[0][0] as any
    expect(body.campaignId).toBeNull()
  })

  it('编辑只提交被改字段与归属无关的表单值，不带 shopId', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 1, '编辑')
    await formInputs(wrapper)[1].setValue('0.9')
    await clickBtn(wrapper, '保存修改')
    expect(confirmText(wrapper)).toContain('规则 #92')
    await clickBtn(wrapper, '确认执行')
    expect(api.updateBidSchedule).toHaveBeenCalledWith(92, expect.objectContaining({ multiplier: 0.9 }))
    const patch = vi.mocked(api.updateBidSchedule).mock.calls[0][1] as any
    expect(patch.shopId).toBeUndefined()
    expect(patch.id).toBeUndefined()
  })

  it('启用前说清会开始改价，停用后说清已改出的价不回收', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 1, '启用')
    expect(confirmText(wrapper)).toContain('每个整点都会按 基准价 × 0.7 改价')
    await clickBtn(wrapper, '确认执行')
    expect(api.toggleBidSchedule).toHaveBeenCalledWith(92, true)

    await rowBtn(wrapper, 0, '停用')
    expect(confirmText(wrapper)).toContain('已经按这条规则改过的竞价会留在广告账号上')
    await clickBtn(wrapper, '确认执行')
    expect(api.toggleBidSchedule).toHaveBeenCalledWith(91, false)
  })

  it('删除必须确认，且文案承认这是物理删除', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 0, '删除')
    expect(api.deleteBidSchedule).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('物理删除')
    await clickBtn(wrapper, '确认执行')
    expect(api.deleteBidSchedule).toHaveBeenCalledWith(91)
  })

  it('截断时给出下一页并带上游标', async () => {
    vi.mocked(api.listBidSchedules).mockResolvedValue(ok(SCHEDULES, paged()))
    const wrapper = await mountPage()
    expect(wrapper.find('.filter-row').text()).toContain('本页不是全量')
    await clickBtn(wrapper, '下一页')
    expect(api.listBidSchedules).toHaveBeenLastCalledWith('1', expect.objectContaining({ cursor: 'v1:99' }))
    expect(wrapper.find('[data-panel="list"] tbody').findAll('tr').length).toBe(4)
  })

  it('后端拒绝时保留表单与错误条，不假装保存成功', async () => {
    vi.mocked(api.createBidSchedule).mockResolvedValue(fail('暂不支持跨零点窗口'))
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建调价规则')
    await clickBtn(wrapper, '创建规则')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('暂不支持跨零点窗口')
    expect(wrapper.find('.form-card').exists()).toBe(true)
    expect(vi.mocked(api.listBidSchedules).mock.calls.length).toBe(1)
  })

  it('列表接口失败时空态文案不暗示「已经改过价」', async () => {
    vi.mocked(api.listBidSchedules).mockResolvedValue(fail('店铺不存在或无权访问'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('店铺不存在或无权访问')
    const empty = wrapper.find('[data-panel="list"] tbody').text()
    expect(empty).toContain('还没有调价规则')
    expect(empty).toContain('不会被本页改动')
  })
})
