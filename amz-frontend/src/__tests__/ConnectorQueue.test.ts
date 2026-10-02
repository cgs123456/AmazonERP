import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ConnectorQueue from '../views/ConnectorQueue.vue'

vi.mock('@/api/connectors', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/connectors')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/connectors'
import { OUTBOX_STATUSES } from '@/api/connectors'

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: null }) as any
const fail = (message: string) => ({ code: 500, message, data: null } as any)

const OUTBOX = ok([
  { id: 901, shopId: 7, operationId: 'createFeedDocument', httpMethod: 'POST',
    requestPath: '/feeds/2021-06-30/documents', status: 'DLQ', attemptCount: 5, maxAttempts: 5,
    responseStatus: 429, marketplaceId: 'ATVPDKIKX0DER', lastErrorCode: 'RATE_LIMITED',
    lastErrorMessage: 'Too Many Requests', createdAt: '2026-10-01T09:00:00' },
  { id: 902, shopId: 7, operationId: 'getOrders', httpMethod: 'GET',
    requestPath: '/orders?CreatedAfter=2026-09-01', status: 'FAILED', attemptCount: 2, maxAttempts: 5,
    responseStatus: 500, lastErrorCode: null, lastErrorMessage: 'upstream boom',
    createdAt: '2026-10-01T10:00:00' },
  { id: 903, shopId: 8, operationId: 'getOrders', httpMethod: 'GET',
    requestPath: '/orders?CreatedAfter=2026-09-02', status: 'SUCCEEDED', attemptCount: 1, maxAttempts: 5,
    responseStatus: 200, lastErrorCode: null, lastErrorMessage: null, createdAt: '2026-10-01T11:00:00' }
])
const RATE = ok([
  { shopId: 7, operationId: 'getOrders', variant: 'default', headerValue: '5;rate=0.45;burst=30',
    observedRatePerSecond: 0.45, effectiveRatePerSecond: 0.4, burst: 30, observedAt: '2026-10-01T12:00:00Z' }
])

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listOutbox).mockResolvedValue(OUTBOX)
  vi.mocked(api.listRateLimits).mockResolvedValue(RATE)
  vi.mocked(api.replayOutbox).mockResolvedValue(ok({ success: true, outcome: 'REPLAYED', status: 'SUCCEEDED', message: null }))
}

const mountPage = async () => {
  const wrapper = mount(ConnectorQueue, { global: stubs })
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

const rowBtn = async (wrapper: any, rowIndex: number, label: string) => {
  const rows = wrapper.find('[data-panel="outbox"]').findAll('tbody tr')
  expect(rows.length, `发件箱没有第 ${rowIndex + 1} 行`).toBeGreaterThan(rowIndex)
  const btn = rows[rowIndex].findAll('button').find((b: any) => b.text() === label)
  expect(btn, `第 ${rowIndex + 1} 行没有按钮「${label}」`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('ConnectorQueue 视图（调用队列与限流）', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    happy()
  })

  it('这一页不看店铺选择：没有 current_shop_id 也照常查队列', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('.shop-tip').exists()).toBe(false)
    expect(api.listOutbox).toHaveBeenCalledTimes(1)
    expect(api.listRateLimits).not.toHaveBeenCalled()
    await openTab(wrapper, '限流观测')
    expect(api.listRateLimits).toHaveBeenCalledTimes(1)
  })

  it('三条边界常驻页面：按授权店铺过滤、人工重放会按原方法重发、未启用不是空队列', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('不看右上角店铺')
    expect(note).toContain('人工点「重放」会按记录原方法重发')
    expect(note).toContain('返回的是失败而不是空列表')
    expect(note).toContain('没跑过的操作不会出现在表里')
  })

  it('发件箱按后端常量给状态选项，DLQ 与写方法标红', async () => {
    const wrapper = await mountPage()
    expect(OUTBOX_STATUSES).toEqual(['PENDING', 'SUCCEEDED', 'FAILED', 'REPLAYING', 'REPLAYED', 'DLQ'])
    const options = wrapper.find('[data-panel="outbox"] select').findAll('option').map((o: any) => o.text())
    expect(options).toEqual(['全部', ...OUTBOX_STATUSES])
    const rows = wrapper.find('[data-panel="outbox"]').findAll('tbody tr')
    expect(rows.length).toBe(3)
    expect(rows[0].text()).toContain('DLQ')
    expect(rows[0].find('.status-tag.urgent').exists()).toBe(true)
    expect(rows[1].text()).toContain('upstream boom')
    expect(rows[1].findAll('.status-tag.urgent').length).toBe(0)
    expect(rows[2].text()).toContain('SUCCEEDED')
  })

  it('筛选与条数上限随请求送出，超过 200 被夹住，非法值不送', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-panel="outbox"] select').setValue('DLQ')
    const first = vi.mocked(api.listOutbox).mock.calls[1] as any[]
    expect(first[0]).toEqual({ status: 'DLQ', limit: 50 })

    await wrapper.find('[data-panel="outbox"] input[type="number"]').setValue('500')
    await clickBtn(wrapper, '刷新')
    expect((vi.mocked(api.listOutbox).mock.calls[2] as any[])[0]).toEqual({ status: 'DLQ', limit: 200 })

    await wrapper.find('[data-panel="outbox"] input[type="number"]').setValue('')
    await clickBtn(wrapper, '刷新')
    expect((vi.mocked(api.listOutbox).mock.calls[3] as any[])[0]).toEqual({ status: 'DLQ', limit: undefined })
  })

  it('outbox 未启用时给失败原因，并说明空表不等于未启用', async () => {
    vi.mocked(api.listOutbox).mockResolvedValue(fail('SP-API Outbox 未启用'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('SP-API Outbox 未启用')
    expect(wrapper.find('[data-panel="outbox"] .empty-row').text()).toContain('这是查询结果，不代表 outbox 未启用')
  })

  it('重放要二次确认，且按方法区分后果说明；取消时一条请求都不发', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 0, '重放')
    expect(wrapper.find('.confirm-detail').text()).toContain('POST /feeds/2021-06-30/documents')
    expect(wrapper.find('.confirm-detail').text()).toContain('可能在平台侧产生真实副作用')
    expect(api.replayOutbox).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(api.replayOutbox).not.toHaveBeenCalled()

    await rowBtn(wrapper, 1, '重放')
    expect(wrapper.find('.confirm-detail').text()).toContain('只读方法')
    expect(wrapper.find('.confirm-detail').text()).toContain('只是再问一次')
    await clickBtn(wrapper, '确认执行')
    expect(api.replayOutbox).toHaveBeenCalledWith(902)
    expect(api.listOutbox).toHaveBeenCalledTimes(2)
  })

  it('后端回 200 但 success=false 时照样报错，且刷新不会把这条错误抹掉', async () => {
    vi.mocked(api.replayOutbox).mockResolvedValue(ok({ success: false, outcome: 'STILL_FAILED' }))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 0, '重放')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('重放结果：STILL_FAILED')
    expect(api.listOutbox).toHaveBeenCalledTimes(2)
  })

  it('重放返回非 200 时说明未成功，同时刷新出最新状态', async () => {
    vi.mocked(api.replayOutbox).mockResolvedValue(fail('Outbox 重放未成功：NOT_FOUND'))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 0, '重放')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('Outbox 重放未成功')
    expect(api.listOutbox).toHaveBeenCalledTimes(2)
  })

  it('限流观测显示真实观测值，空表说明是本进程还没带回速率头', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '限流观测')
    const panel = wrapper.find('[data-panel="ratelimit"]')
    expect(panel.text()).toContain('getOrders')
    expect(panel.text()).toContain('0.45')
    expect(panel.text()).toContain('5;rate=0.45;burst=30')

    vi.mocked(api.listRateLimits).mockResolvedValue(ok(null))
    const empty = await mountPage()
    await openTab(empty, '限流观测')
    expect(empty.find('[data-panel="ratelimit"] .empty-row').text()).toContain('还没有任何限流观测')
    expect(empty.find('[data-panel="ratelimit"]').text()).not.toContain('0.45')
  })
})
