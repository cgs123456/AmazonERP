import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CustomerService from '../views/CustomerService.vue'

// 整个客服 API 层自动 mock：枚举常量保留真实值，函数全部换成 vi.fn()
vi.mock('@/api/customer', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/customer')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as cust from '@/api/customer'

const SHOP = '900000000000001000'

const ok = <T>(data: T, page?: unknown) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page ?? null }) as any
const pageOf = (nextCursor: string | null, truncated = nextCursor !== null) =>
  ({ size: 50, returned: 1, hasMore: truncated, truncated, nextCursor, total: null })

const TICKETS = ok([
  { id: 1, shopId: 1, amazonOrderId: '114-7712567-000001', buyerName: 'Ana', channel: 'MESSAGE',
    category: 'LOGISTICS', sentiment: 'NEGATIVE', priority: 'HIGH', status: 'PENDING',
    content: 'package not received', reply: '' },
  { id: 2, shopId: 1, amazonOrderId: '114-7712567-000002', buyerId: 'buyer-2', channel: 'RETURN',
    category: 'REFUND', sentiment: 'NEUTRAL', priority: 'LOW', status: 'RESOLVED',
    content: 'refund ok', reply: 'done' }
], pageOf(null))

const TEMPLATES = ok([
  { id: 11, shopId: 1, templateName: 'Sorry for the delay', templateType: 'AFTERSALE', language: 'en',
    triggerEvent: 'NEGATIVE_REVIEW', triggerDelayHours: 24, enabled: 1, subject: 'About your order', body: 'Dear {name},' },
  { id: 12, shopId: 1, templateName: 'Shipping notice', templateType: 'SHIPPING', language: 'en',
    triggerEvent: 'SHIPPING_DELAY', triggerDelayHours: 0, enabled: 0, subject: 'Tracking', body: 'Your tracking no is' },
  { id: 13, shopId: 1, templateName: 'Review request', templateType: 'REVIEW_REQUEST', language: 'en',
    triggerEvent: '', triggerDelayHours: 0, enabled: 1, subject: 'Review', body: 'Would you like to review' }
], pageOf(null))

const TASKS = ok([
  { id: 21, shopId: 1, amazonOrderId: '114-7712567-000001', asin: 'B0TEST01', buyerEmail: 'ana@example.com',
    subject: 'About your order', status: 'PENDING', scheduledTime: '2026-10-02T10:00:00',
    sentTime: null, source: 'MANUAL', failureReason: null }
], pageOf(null))

const REVIEWS = ok([
  { id: 31, shopId: 1, asin: 'B0TEST01', reviewerName: 'Ana', reviewRating: 1, reviewTitle: 'Terrible',
    reviewDate: '2026-09-28', verifiedPurchase: 1, status: 'DETECTED', matchedOrderId: null,
    contactEmailTaskId: null }
], pageOf(null))

const SOLICITATIONS = ok([
  { id: 41, shopId: 1, amazonOrderId: '114-7712567-000009', asin: 'B0TEST01', channel: 'EMAIL',
    status: 'SENT', failureReason: null }
])

const RMAS = ok([
  { id: 51, shopId: 1, rmaNo: 'RMA-0001', amazonOrderId: '114-7712567-000003', asin: 'B0TEST01',
    sku: 'SKU-1', returnType: 'REFUND', productCondition: 'OPENED', refundAmount: 12.5,
    status: 'PENDING', labelUrl: null, trackingNo: null }
], pageOf(null))

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(cust.listTickets).mockResolvedValue(TICKETS)
  vi.mocked(cust.listTemplates).mockResolvedValue(TEMPLATES)
  vi.mocked(cust.listEmailTasks).mockResolvedValue(TASKS)
  vi.mocked(cust.listNegativeReviews).mockResolvedValue(REVIEWS)
  vi.mocked(cust.listSolicitations).mockResolvedValue(SOLICITATIONS)
  vi.mocked(cust.listRmas).mockResolvedValue(RMAS)
  vi.mocked(cust.processPendingEmails).mockResolvedValue(ok({ processed: 1, failed: 0 }))
  vi.mocked(cust.replyTicket).mockResolvedValue(ok({ id: 1, status: 'REPLIED' }))
  vi.mocked(cust.updateTemplate).mockResolvedValue(ok({ id: 11 }))
  vi.mocked(cust.createTemplate).mockResolvedValue(ok({ id: 14 }))
  vi.mocked(cust.toggleTemplate).mockResolvedValue(ok(true))
  vi.mocked(cust.triggerEmail).mockResolvedValue(ok({ id: 22 }))
  vi.mocked(cust.matchReviewToOrder).mockResolvedValue(ok({ matchedOrderId: '114-7712567-000001' }))
  vi.mocked(cust.followUpNegativeReview).mockResolvedValue(ok({ id: 23 }))
  vi.mocked(cust.solicitReviews).mockResolvedValue(ok(5))
  vi.mocked(cust.createRma).mockResolvedValue(ok({ id: 52 }))
  vi.mocked(cust.updateRmaStatus).mockResolvedValue(ok({ id: 51, status: 'APPROVED' }))
  vi.mocked(cust.createManualTask).mockResolvedValue(ok({ id: 24 }))
  vi.mocked(cust.saveNegativeReview).mockResolvedValue(ok({ id: 32 }))
  vi.mocked(cust.receiveTicket).mockResolvedValue(ok({ id: 3, status: 'PENDING' }))
}

const mountPage = async () => {
  const wrapper = mount(CustomerService, { global: stubs })
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
  const btn = wrapper.findAll('button').find((b: any) => b.text() === label)
  expect(btn, `按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('CustomerService 视图（客服中心）', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', SHOP)
    vi.clearAllMocks()
    happy()
  })

  it('未选店铺时不请求任何客服端点', async () => {
    localStorage.clear()
    const wrapper = await mountPage()
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(wrapper.findAll('.tab').length).toBe(0)
    expect(cust.listTickets).not.toHaveBeenCalled()
    expect(cust.listRmas).not.toHaveBeenCalled()
  })

  it('五个分区一次只渲染一个，进入分区才请求对应端点', async () => {
    const wrapper = await mountPage()
    expect(wrapper.findAll('.tab').length).toBe(5)
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(wrapper.find('[data-panel="ticket"]').exists()).toBe(true)
    expect(cust.listTickets).toHaveBeenCalledTimes(1)
    expect(cust.listNegativeReviews).not.toHaveBeenCalled()
    expect(cust.listRmas).not.toHaveBeenCalled()

    await openTab(wrapper, '差评与索评')
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(wrapper.find('[data-panel="review"]').exists()).toBe(true)
    expect(cust.listNegativeReviews).toHaveBeenCalledTimes(1)
    expect(cust.listSolicitations).toHaveBeenCalledTimes(1)

    await openTab(wrapper, 'RMA')
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(wrapper.find('[data-panel="rma"]').exists()).toBe(true)
    expect(cust.listRmas).toHaveBeenCalledTimes(1)

    await openTab(wrapper, '邮件任务')
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(wrapper.find('[data-panel="task"]').exists()).toBe(true)
    expect(cust.listEmailTasks).toHaveBeenCalledTimes(1)
    // 事件项来自模板，进入任务分区就会拉模板
    expect(cust.listTemplates).toHaveBeenCalledTimes(1)
    // 同分区再次进入不重复请求
    await openTab(wrapper, '邮件任务')
    expect(cust.listEmailTasks).toHaveBeenCalledTimes(1)
  })

  it('三条通道现状常驻页面：不发信、不假装调用 SP-API、分类不是模型', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('邮件发送通道未接入')
    expect(note).toContain('只会把任务标成')
    expect(note).toContain('SP-API')
    expect(note).toContain('关键词规则匹配')
  })

  it('工单表原样显示后端分类与情感，已解决行的回复按钮禁用且没有伪装成升级', async () => {
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="ticket"]')
    expect(panel.text()).toContain('114-7712567-000001')
    expect(panel.text()).toContain('LOGISTICS')
    expect(panel.text()).toContain('NEGATIVE')
    expect(panel.text()).toContain('PENDING')
    expect(panel.findAll('tbody tr').length).toBe(2)
    const rowButtons = panel.findAll('tbody button')
    expect(rowButtons.length).toBe(2)
    expect(rowButtons[0].attributes('disabled')).toBeUndefined()
    expect(rowButtons[1].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').map((b: any) => b.text())).not.toContain('升级')
  })

  it('工单为空时显示空态，不落到任何假数据', async () => {
    vi.mocked(cust.listTickets).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="ticket"]')
    expect(panel.find('.empty-row').text()).toContain('该店铺暂无工单')
    expect(panel.text()).not.toContain('114-7712567-000001')
  })

  it('服务端截断时才给下一页入口，并把 nextCursor 原样回传', async () => {
    vi.mocked(cust.listTickets)
      .mockResolvedValueOnce(ok([TICKETS.data[0]], pageOf('v1:MQ==')))
      .mockResolvedValue(ok([...TICKETS.data], pageOf(null)))
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="ticket"]')
    expect(panel.text()).toContain('后端标记仍有下一页')
    await clickBtn(panel, '加载下一页')
    const calls = vi.mocked(cust.listTickets).mock.calls as any[]
    expect(calls.length).toBe(2)
    expect(calls[0][1].cursor).toBeUndefined()
    expect(calls[1][1].cursor).toBe('v1:MQ==')
    expect(wrapper.find('[data-panel="ticket"]').findAll('tbody tr').length).toBe(3)
    expect(wrapper.find('[data-panel="ticket"]').findAll('.page-btn').length).toBe(0)
  })

  it('三个通道受限动作先确认再执行，取消时一个请求都不发', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '邮件任务')

    await clickBtn(wrapper, '处理待发队列')
    expect(wrapper.find('.confirm-detail').text()).toContain('邮件通道目前未接入')
    expect(cust.processPendingEmails).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(cust.processPendingEmails).not.toHaveBeenCalled()
    expect(wrapper.find('.modal-mask').exists()).toBe(false)

    await clickBtn(wrapper, '处理待发队列')
    await clickBtn(wrapper, '确认执行')
    expect(cust.processPendingEmails).toHaveBeenCalledTimes(1)
    expect(cust.processPendingEmails).toHaveBeenCalledWith(SHOP)
    expect(wrapper.find('[data-panel="task"]').text()).toContain('处理结果')

    await openTab(wrapper, '差评与索评')
    await clickBtn(wrapper, '批量索评')
    expect(wrapper.find('.confirm-detail').text()).toContain('非模拟环境后端会直接抛错')
    expect(cust.solicitReviews).not.toHaveBeenCalled()
    await clickBtn(wrapper, '确认执行')
    expect(cust.solicitReviews).toHaveBeenCalledWith(SHOP)

    await clickBtn(wrapper, '匹配订单')
    expect(wrapper.find('.confirm-detail').text()).toContain('SIMULATED-MATCH-*')
    expect(cust.matchReviewToOrder).not.toHaveBeenCalled()
    await clickBtn(wrapper, '确认执行')
    expect(cust.matchReviewToOrder).toHaveBeenCalledWith(31)
  })

  it('事件下拉只列启用中模板的 triggerEvent，不在前端编枚举', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '邮件任务')
    await clickBtn(wrapper, '按事件触发')
    const options = wrapper.find('.modal').findAll('select option').map((o: any) => o.text())
    expect(options).toEqual(['选择启用中模板的 triggerEvent', 'NEGATIVE_REVIEW'])
    expect(options.join()).not.toContain('SHIPPING_DELAY')

    await wrapper.find('.modal select').setValue('NEGATIVE_REVIEW')
    await wrapper.find('.modal input').setValue('114-7712567-000007')
    await clickBtn(wrapper, '提交')
    expect(cust.triggerEmail).toHaveBeenCalledTimes(1)
    const args = vi.mocked(cust.triggerEmail).mock.calls[0] as any[]
    expect(args[0]).toBe(SHOP)
    expect(args[1].eventType).toBe('NEGATIVE_REVIEW')
    expect(args[1].orderId).toBe('114-7712567-000007')
  })

  it('回复工单用行 id 调 reply，模板更新走 PUT 且请求体不带 id 与回填对象', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '回复')
    expect(wrapper.find('.modal h3').text()).toBe('回复工单')
    await wrapper.find('.modal textarea').setValue('thanks a lot')
    await clickBtn(wrapper, '提交')
    expect(cust.replyTicket).toHaveBeenCalledWith(1, 'thanks a lot')

    await openTab(wrapper, '邮件模板')
    await clickBtn(wrapper, '编辑')
    await wrapper.find('.modal input').setValue('Sorry again')
    await clickBtn(wrapper, '提交')
    expect(cust.updateTemplate).toHaveBeenCalledTimes(1)
    expect(cust.createTemplate).not.toHaveBeenCalled()
    const args = vi.mocked(cust.updateTemplate).mock.calls[0] as any[]
    expect(args[0]).toBe(11)
    expect(args[1].shopId).toBe(SHOP)
    expect(args[1].templateName).toBe('Sorry again')
    expect('id' in args[1]).toBe(false)
    expect('template' in args[1]).toBe(false)
  })

  it('模板停用回写后端并重新加载列表', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '邮件模板')
    expect(vi.mocked(cust.listTemplates).mock.calls[0][0]).toBe(SHOP)
    await clickBtn(wrapper, '停用')
    expect(cust.toggleTemplate).toHaveBeenCalledWith(11, false)
    expect(cust.listTemplates).toHaveBeenCalledTimes(2)
  })

  it('差评分区显示后端真实字段，未匹配订单显示 - 而不是编造订单号', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '差评与索评')
    const panel = wrapper.find('[data-panel="review"]')
    expect(panel.text()).toContain('B0TEST01')
    expect(panel.text()).toContain('Terrible')
    expect(panel.text()).toContain('DETECTED')
    expect(panel.text()).not.toContain('SIMULATED')
    expect(panel.findAll('tbody')[0].text()).toContain('-')
    expect(panel.text()).toContain('114-7712567-000009')
  })

  it('RMA 状态下拉真实提交，选回原状态不产生请求', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, 'RMA')
    const select = wrapper.find('[data-panel="rma"]').findAll('tbody select')[0]
    await select.setValue('APPROVED')
    await flushPromises()
    expect(cust.updateRmaStatus).toHaveBeenCalledTimes(1)
    expect(cust.updateRmaStatus).toHaveBeenCalledWith(51, 'APPROVED')
    await wrapper.find('[data-panel="rma"]').findAll('tbody select')[0].setValue('PENDING')
    expect(cust.updateRmaStatus).toHaveBeenCalledTimes(1)
  })

  it('手动任务缺必填时只报错，不发请求也不关弹窗', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '邮件任务')
    await clickBtn(wrapper, '手动建任务')
    await clickBtn(wrapper, '提交')
    expect(cust.createManualTask).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('手动任务：订单号、收件人邮箱、正文必填')
    expect(wrapper.find('.modal-mask').exists()).toBe(true)
  })

  it('后端非 200 与网络异常都落到页面错误条，不静默显示成没有数据', async () => {
    vi.mocked(cust.listRmas).mockResolvedValue({ code: 500, message: 'RMA 服务不可用', data: null } as any)
    const wrapper = await mountPage()
    await openTab(wrapper, 'RMA')
    expect(wrapper.find('.error-zone').text()).toContain('RMA 列表')
    expect(wrapper.find('.error-zone').text()).toContain('RMA 服务不可用')

    vi.mocked(cust.listNegativeReviews).mockRejectedValue(new Error('network down'))
    vi.mocked(cust.listSolicitations).mockResolvedValue(ok([]))
    await openTab(wrapper, '差评与索评')
    expect(wrapper.find('.error-zone').text()).toContain('差评列表')
    expect(wrapper.find('.error-zone').text()).toContain('network down')
  })
})
